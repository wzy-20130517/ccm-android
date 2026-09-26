package com.ccm.app.runtime

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.tukaani.xz.XZInputStream
import java.util.zip.GZIPInputStream

/**
 * tar.gz 解压器。
 *
 * 【为什么不用系统 tar 命令】
 * Termux 的 tar 在 proot 里可用，但 App 进程里直接调 /system/bin/tar 不一定存在，
 * 且不同 ROM 差异大。自己实现 tar 解析更可控（tar 格式很简单）。
 *
 * 【支持范围】
 * - ustar / GNU tar 的普通文件、目录、符号链接、硬链接
 * - **PAX 扩展头（type 'x' / 'g'）** —— 见下方 ⚠
 * - 设备文件等特殊条目跳过
 * - 不保留完整权限（Android 上没意义），只区分"可执行"与否
 *
 * ════════════════════════════════════════════════════════════════
 * 【2026-09-23 修复：PAX 头导致整个 rootfs 解压错位】
 *
 * 症状：装完 Ubuntu 后 /bin /usr/bin /lib 全都不存在，根目录却多出 90 多个
 * 名字是乱码的条目（"%s %s)\n" "$DISTRIB_DESCRIPTION"、"* * * root test -e" 之类）。
 * 后果：apt 的 /usr/lib/apt/methods 下的可执行文件找不到 → node 等包全都装不上。
 *
 * 根因有两个，叠在一起：
 *
 * ① **同一个 payload 被跳了两遍**。`when` 的 `else` 分支里 `skip(size)` 跳了一次，
 *    然后 `when` 之后那段「非普通文件也要跳数据」又跳一次。对 type='x' 的 PAX 头
 *    （每个文件前面都有一个），等于多跳 60 字节 → 流位置错位到下一个 header 中间
 *    → 之后读出的「文件名」是内存里的任意字节。
 *
 * ② **完全没处理 PAX 头**。原代码只认 GNU 的 'L'/'K'，而这批 rootfs 是 PAX 格式
 *    （`tar --format=posix` 默认产出），typeFlag='x'。
 *
 * 修法：把「跳数据」收敛成**唯一一处**，并把 'x'/'g' 当成合法的「读掉 payload 后
 * 继续下一个条目」类型（与 'L'/'K' 同一处理方式）。
 *
 * ⚠ 以后改这个文件：**任何类型都必须恰好消费一次 `align512(size)` 字节**。
 *   想加新类型时，要么在 `when` 里自己读完并 `continue`，要么别碰它让末尾统一跳。
 *   两处都跳就是这次的事故。
 * ════════════════════════════════════════════════════════════════
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
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        // 失败原因要能被安装日志看到 —— 原来只写 Log.e（进 logcat），
        // 而用户和我们看的是 install.log，那里只有一句「解压失败」，
        // 等于把最有用的信息（异常类型和消息）藏起来了。
        onError: (String) -> Unit = {}
    ): Boolean {
        val total = archive.length()
        var processed = 0L

        try {
            // 【2026-09-24】按 magic bytes 自动识别压缩格式。
            // 原来只认 gzip，而 Node.js 官方发的是 .tar.xz（比 .tar.gz 小一半）。
            // 靠文件名后缀判断不可靠（下载时可能改名），所以读前 6 字节看魔数：
            //   gzip: 1f 8b
            //   xz:   fd 37 7a 58 5a 00  ("\xFD7zXZ\0")
            openDecompressed(archive).use { gz ->
                val header = ByteArray(BLOCK)
                var longName: String? = null
                var longLink: String? = null
                // PAX 头里可能覆盖 path / linkpath（优先级高于 GNU 的 L/K）
                var paxPath: String? = null
                var paxLink: String? = null

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

                    // ── 这几类只携带元数据，读完 payload 就继续 ──
                    // 'L'/'K' = GNU long name / long link
                    // 'x'/'g' = PAX 扩展头（per-file / global）
                    //
                    // ⚠ 这些分支**自己消费 payload 并 continue**，
                    //   绝不能掉到下面那段统一跳数据里（历史事故见文件头注释）。
                    if (typeFlag == 'L' || typeFlag == 'K' || typeFlag == 'x' || typeFlag == 'g') {
                        val payload = readBlockString(gz, align512(size))
                        processed += align512(size)
                        when (typeFlag) {
                            'L' -> longName = payload
                            'K' -> longLink = payload
                            'x', 'g' -> {
                                // PAX 格式：每行 "<len> key=value\n"
                                // 只关心 path 和 linkpath，其余（atime/ctime/mtime）忽略。
                                for (line in payload.split('\n')) {
                                    val eq = line.indexOf('=')
                                    if (eq <= 0) continue
                                    // 长度前缀可能带空格： "30 path=..."
                                    val kv = line.substring(0, eq).trim()
                                    val key = kv.substringAfterLast(' ', kv)
                                    val value = line.substring(eq + 1)
                                    when (key) {
                                        "path" -> paxPath = value
                                        "linkpath" -> paxLink = value
                                    }
                                }
                            }
                        }
                        continue
                    }

                    // 拼接顺序：PAX path > GNU longName > prefix/name
                    var fullName = paxPath
                        ?: longName
                        ?: (if (prefix.isNotEmpty()) "$prefix/$name" else name)
                    paxPath = null
                    longName = null

                    // 安全检查：禁止路径穿越
                    val clean = fullName.trimStart('/').replace("../", "")
                    if (clean.isEmpty() || clean.contains("..")) {
                        if (size > 0) { skip(gz, align512(size)); processed += align512(size) }
                        continue
                    }

                    val outFile = File(destDir, clean)
                    // 记录本轮 payload 是否已被消费（消费过就不再统一跳）
                    var consumed = false

                    when (typeFlag) {
                        '5' -> outFile.mkdirs()

                        '2' -> {  // 符号链接
                            val target = paxLink ?: longLink ?: readString(header, 157, 100)
                            outFile.parentFile?.mkdirs()
                            if (!createLink(target, outFile)) {
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
                            // proot 的 --link2symlink 扩展也是同样的思路：用符号链接
                            // 模拟硬链接。这里在解压阶段就做掉，不依赖 proot 运行时。
                            val target = paxLink ?: longLink ?: readString(header, 157, 100)
                            if (target.isNotEmpty()) {
                                outFile.parentFile?.mkdirs()
                                // 硬链接的 target 是「归档内路径」，可能是绝对路径
                                // （/usr/bin/perl），也可能是相对当前目录的。
                                // 转成符号链接时用相对路径，这样整个 rootfs 可以搬迁。
                                val linkTarget = if (target.startsWith("/")) {
                                    val depth = clean.count { it == '/' }
                                    "../".repeat(depth) + target.trimStart('/')
                                } else target
                                if (!createLink(linkTarget, outFile)) {
                                    Log.w(TAG, "硬链接转换失败: $clean -> $target")
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
                            // ⚠️⚠️ 必须补读 512 对齐的 padding ⚠️⚠️
                            //
                            // tar 的每个条目 payload 都补齐到 512 边界：size=100 的文件
                            // 实际占 512 字节（100 数据 + 412 填充）。
                            //
                            // 原来这里写完 size 字节就置 consumed=true，末尾那段统一跳
                            // 就被跳过 —— 于是**每条目少读 (align512(size)-size) 字节**。
                            // 前几个条目（size 恰好是 512 倍数或 0）看不出问题，
                            // 一旦遇到 size=100 这种就错位，之后读出的「文件名」全是
                            // 内存里的任意字节（实测出现 "DPkg::Pre-Install-Pkgs {"）。
                            //
                            // 定位方式：在 Termux 里用 kotlinc 把 TarExtractor 单独编译成
                            // jar，喂真实 rootfs.tar.gz 跑，加 TAR_DEBUG 打每条 header。
                            // 表现是日志停在第 14 个条目（第一个 size 非 512 倍数的文件）之后。
                            val pad = align512(size) - size
                            if (pad > 0) {
                                skip(gz, pad)
                                processed += pad
                            }
                            // 可执行位：只判 owner(0o100)+group(0o010) 会漏掉 other(0o001)，
                            // 而 apt 的 method 是 0111，漏判会让 apt update 静默失败。
                            val mode = readOctal(header, 100, 8)
                            if (mode and 0b001_001_001L != 0L) {
                                outFile.setExecutable(true, false)
                            }
                            consumed = true
                        }

                        else -> { /* 设备文件等：落到下面统一跳 */ }
                    }

                    // 清理只在「有 PAX/L/K 头时才有值」的状态，防止污染下一条目
                    paxLink = null
                    longLink = null

                    // ⚠ 统一跳数据 —— **全流程唯此一处**（除上面自己 continue 的类型）。
                    //   历史事故：这里和 when 的 else 分支各跳一次，导致 tar 流错位。
                    if (!consumed && size > 0) {
                        skip(gz, align512(size))
                        processed += align512(size)
                    }

                    onProgress(processed, total)
                }
            }
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "解压失败", t)
            // 把异常摘要交给调用方写进安装日志
            val where = t.stackTrace.firstOrNull()?.let { " (${it.fileName}:${it.lineNumber})" } ?: ""
            onError("${t.javaClass.simpleName}: ${t.message ?: "(无消息)"}$where")
            return false
        }
    }

    /**
     * 按魔数打开合适的解压流。
     *
     * gzip 和 xz 都支持 —— see extract() 里的说明。
     * zstd 不在 Android 的常见发行物里，暂不支持（用不到）。
     */
    private fun openDecompressed(archive: File): java.io.InputStream {
        val head = ByteArray(6)
        FileInputStream(archive).use { it.read(head) }
        return when {
            head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte() ->
                GZIPInputStream(FileInputStream(archive), 64 * 1024)
            head[0] == 0xFD.toByte() && head[1] == 0x37.toByte() &&
            head[2] == 0x7A.toByte() && head[3] == 0x58.toByte() &&
            head[4] == 0x5A.toByte() && head[5] == 0x00.toByte() ->
                XZInputStream(FileInputStream(archive), 64 * 1024)
            else -> GZIPInputStream(FileInputStream(archive), 64 * 1024)
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
        // base-256 编码（大数）：首字节 >= 0x80 时不是八进制文本
        if (offset < buf.size && (buf[offset].toInt() and 0xFF) >= 0x80) {
            var v = 0L
            for (i in offset until minOf(offset + len, buf.size)) {
                v = (v shl 8) or (buf[i].toLong() and 0xFF)
            }
            // 去掉最高位的标记位
            v = v and 0x7FFFFFFFFFFFFFFFL
            return v
        }
        val s = readString(buf, offset, len).trim().trimEnd('\u0000')
        if (s.isEmpty()) return 0
        return try {
            s.toLong(8)
        } catch (e: NumberFormatException) {
            0
        }
    }

    /**
     * 读一个 **已补齐到 512 边界** 的数据块。
     *
     * 调用方传 align512(size)，这样函数内部一次读完（含 padding），
     * 不会把流留在 payload 中间。
     */
    private fun readBlockString(gz: java.io.InputStream, paddedSize: Long): String {
        if (paddedSize <= 0) return ""
        val buf = ByteArray(paddedSize.toInt())
        readFully(gz, buf)
        // 去掉尾部 padding 的 0 字节
        var end = buf.size
        while (end > 0 && buf[end - 1] == 0.toByte()) end--
        return String(buf, 0, end, Charsets.UTF_8)
    }

    /**
     * 建符号链接（尽量稳）。
     *
     * 【为什么不用 Runtime.exec("ln")】
     * Android 上 /system/bin/ln 通常是 toybox 的软链，存在但**不保证每个 ROM 都有**；
     * 而且 fork 一个进程只为建链接，几千个条目就是几千次 fork（rootfs 里有 196 个）。
     *
     * 【为什么准备两条路】
     * java.nio.file.Files.createSymbolicLink 是标准做法，但在 App 私有目录上
     * 某些 ROM 的 SELinux 策略会拒绝（抛 FileSystemException）。
     * 这时退回 toybox 的 ln —— 它是 system 分区的可执行文件，权限上更宽松。
     */
    private fun createLink(target: String, linkFile: File): Boolean {
        // 路 1：NIO
        try {
            java.nio.file.Files.deleteIfExists(linkFile.toPath())
            java.nio.file.Files.createSymbolicLink(linkFile.toPath(), File(target).toPath())
            return true
        } catch (t: Throwable) {
            // 落到路 2
        }
        // 路 2：toybox ln
        return try {
            if (linkFile.exists()) linkFile.delete()
            val p = ProcessBuilder("ln", "-sf", target, linkFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            p.waitFor() == 0 && (linkFile.exists() || java.nio.file.Files.isSymbolicLink(linkFile.toPath()))
        } catch (t: Throwable) {
            false
        }
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
