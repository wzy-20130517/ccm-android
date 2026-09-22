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
                            // 可执行位（tar 的 mode 字段）
                            val mode = readOctal(header, 100, 8)
                            if (mode and 0b001_000_000 != 0L || mode and 0b000_001_000 != 0L) {
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
