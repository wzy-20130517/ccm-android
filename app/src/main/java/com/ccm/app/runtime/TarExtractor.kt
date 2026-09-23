package com.ccm.app.runtime

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * tar.gz 解压器。
 *
 * 【为什么不用系统 tar 命令】
 * Termux 的 tar 在 proot 里可用，但 App 进程里直接调 /system/bin/tar 不一定存在，
 * 且不同 ROM 差异大。自己实现 tar 解析更可控（tar 格式很简单）。
 *
 * 【支持范围】
 * - 只处理 ustar / GNU tar 的普通文件、目录、符号链接
 * - 硬链接、设备文件等特殊条目跳过（rootfs 里少见）
 * - 不保留完整权限（Android 上没意义），只区分"可执行"与否
 */
object TarExtractor {

    private const val TAG = "TarExtractor"
    private const val BLOCK = 512

    /**
     * 解压 tar.gz。
     *
     * @param archive .tar.gz 文件
     * @param destDir 目标目录
     * @param onProgress (已处理字节, 总字节)
     */
    fun extract(
        archive: File,
        destDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean {
        val total = archive.length()
        var processed = 0L

        try {
            GZIPInputStream(FileInputStream(archive), 64 * 1024).use { gz ->
                val header = ByteArray(BLOCK)
                var longName: String? = null
                var longLink: String? = null

                while (true) {
                    // 读一个 512 字节头块
                    val n = readFully(gz, header)
                    if (n < BLOCK) break
                    processed += BLOCK

                    // 全零块 = 归档结束
                    if (header.all { it == 0.toByte() }) break

                    var name = readString(header, 0, 100)
                    val size = readOctal(header, 124, 12)
                    val typeFlag = header[156].toInt().toChar()
                    val prefix = readString(header, 345, 155)

                    // GNU long name (type 'L') / long link (type 'K')
                    if (typeFlag == 'L') {
                        longName = readBlockString(gz, size)
                        processed += align512(size)
                        continue
                    }
                    if (typeFlag == 'K') {
                        longLink = readBlockString(gz, size)
                        processed += align512(size)
                        continue
                    }

                    // 拼接 prefix（ustar）
                    var fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
                    longName?.let { fullName = it; longName = null }

                    // 安全检查：禁止路径穿越
                    val clean = fullName.trimStart('/').replace("../", "")
                    if (clean.isEmpty() || clean.contains("..")) {
                        skip(gz, size)
                        processed += align512(size)
                        continue
                    }

                    val outFile = File(destDir, clean)

                    when (typeFlag) {
                        '5' -> {  // 目录
                            outFile.mkdirs()
                        }
                        '2' -> {  // 符号链接
                            val target = longLink ?: readString(header, 157, 100)
                            longLink = null
                            outFile.parentFile?.mkdirs()
                            try {
                                if (outFile.exists()) outFile.delete()
                                Runtime.getRuntime().exec(
                                    arrayOf("ln", "-sf", target, outFile.absolutePath)
                                ).waitFor()
                            } catch (t: Throwable) {
                                // 符号链接失败不致命（多数场景不需要）
                                Log.w(TAG, "symlink 失败: $clean -> $target")
                            }
                        }
                        '1' -> {  // 硬链接 → 转成符号链接
                            //
                            // 【为什么转符号链接】
                            // Android 的 App 私有目录（filesDir）实际是 ext4，理论上
                            // 支持硬链接，但实测 tar 解压时报 "Cannot hard link to ...:
                            // Permission denied"（SELinux 策略限制）。
                            //
                            // 影响面很小（Ubuntu base rootfs 里只有 2 个硬链接：
                            // usr/bin/perl → perl5.38.2、usr/bin/uncompress → gunzip），
                            // 但缺了别名会让某些脚本找不到解释器。
                            //
                            // proot 的 --link2symlink 扩展也是同样的思路：
                            // 用符号链接模拟硬链接。这里在解压阶段就做掉，
                            // 不依赖 proot 运行时。
                            val target = longLink ?: readString(header, 157, 100)
                            longLink = null
                            if (target.isNotEmpty()) {
                                outFile.parentFile?.mkdirs()
                                try {
                                    if (outFile.exists()) outFile.delete()
                                    // 硬链接的 target 是 rootfs 内的相对路径
                                    val linkTarget = if (target.startsWith("/")) {
                                        // 绝对路径 → 转成相对于当前文件目录的路径
                                        val depth = clean.count { it == '/' }
                                        "../".repeat(depth) + target.trimStart('/')
                                    } else target
                                    Runtime.getRuntime().exec(
                                        arrayOf("ln", "-sf", linkTarget, outFile.absolutePath)
                                    ).waitFor()
                                    Log.d(TAG, "硬链接转符号链接: $clean -> $linkTarget")
                                } catch (t: Throwable) {
                                    Log.w(TAG, "硬链接转换失败: $clean -> $target (${t.message})")
                                }
                            }
                        }
                        '0', '\u0000', '7' -> {  // 普通文件（'7' 是 contiguous）
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                var remaining = size
                                val buf = ByteArray(64 * 1024)
                                while (remaining > 0) {
                                    val toRead = minOf(remaining, buf.size.toLong()).toInt()
                                    val r = gz.read(buf, 0, toRead)
                                    if (r <= 0) break
                                    out.write(buf, 0, r)
                                    remaining -= r
                                }
                            }
                            processed += size
                            // 可执行位（tar 的 mode 字段，12 位八进制）
                            //
                            // 【为什么判断 0o111 而不是分别判 owner/group】
                            // tar 里常见的 mode 是 0755（owner rwx + group/other rx）
                            // 或 0644（无执行位）。只要任一位是 x，就说明这是可执行文件。
                            // 之前只判 owner(0o100) 和 group(0o010)，漏掉了 other(0o001)，
                            // 而 Ubuntu rootfs 里大量文件是 0755，owner 位确实置了 —— 但
                            // 有些包（如 apt 的 method）是 0111 或 0555，判断不全会漏。
                            //
                            // 真机实测：这一步漏判会导致 apt update 静默失败
                            // （/usr/lib/apt/methods/http 不可执行）。
                            val mode = readOctal(header, 100, 8)
                            if (mode and 0b001_001_001L != 0L) {
                                outFile.setExecutable(true, false)
                            }
                            // 补齐到 512 边界
                            val pad = align512(size) - size
                            if (pad > 0) { skip(gz, pad); processed += pad }
                            onProgress(processed, total)
                            continue
                        }
                        else -> {  // 其他类型跳过
                            skip(gz, size)
                        }
                    }

                    // 非普通文件也要跳数据（目录 size=0）
                    if (typeFlag != '0' && typeFlag != '\u0000' && typeFlag != '7') {
                        if (size > 0) {
                            skip(gz, size)
                            processed += align512(size)
                        }
                    }
                    onProgress(processed, total)
                }
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "解压失败", t)
            return false
        }
    }

    // ── 工具方法 ──────────────────────────────────

    private fun readFully(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
        return off
    }

    private fun readString(buf: ByteArray, offset: Int, len: Int): String {
        var end = offset
        val max = minOf(offset + len, buf.size)
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8).trim()
    }

    private fun readOctal(buf: ByteArray, offset: Int, len: Int): Long {
        val s = readString(buf, offset, len).trim().trimEnd('\u0000')
        if (s.isEmpty()) return 0
        return try {
            s.toLong(8)
        } catch (e: NumberFormatException) {
            // 某些 tar 用 base-256 编码大数
            0
        }
    }

    /** 读一个 size 字节的数据块（用于 GNU long name） */
    private fun readBlockString(gz: java.io.InputStream, size: Long): String {
        val buf = ByteArray(size.toInt())
        readFully(gz, buf)
        return String(buf, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun skip(input: java.io.InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(remaining, buf.size.toLong()).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun align512(size: Long): Long = ((size + BLOCK - 1) / BLOCK) * BLOCK
}
