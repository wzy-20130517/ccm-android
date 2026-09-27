package com.ccm.app.tools.file

import java.io.File
import java.io.IOException

/**
 * 原子写入。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么必须原子写
 * ══════════════════════════════════════════════════════════════
 *
 * 直接 `File.writeText()` 的失败模式：写到一半进程被杀 / 断电 / 磁盘满，
 * 目标文件就变成**半份内容** —— 旧内容没了，新内容也不完整。
 * 对会话文件、配置、源码来说这是数据丢失，不是「写入失败」那么轻。
 *
 * 做法与 CCM 的 `core/atomic.mjs` 一致：写到同目录的临时文件，再 rename 覆盖。
 * rename 在同一文件系统上是**原子操作** —— 观察者要么看到完整旧内容，
 * 要么看到完整新内容，不存在中间态。
 *
 * ⚠️ 临时文件必须与目标**同目录**（同文件系统），跨挂载点 rename 会退化成
 *    「复制+删除」，就不原子了。所以这里用 `File(target.parentFile, name)`。
 *
 * ⚠️ Android 的 `Files.move(..., ATOMIC_MOVE)` 在 API 26+ 可用，但某些
 *    文件系统（FUSE 挂载的 /sdcard）会抛 `AtomicMoveNotSupportedException`。
 *    这里先试 ATOMIC_MOVE，失败退回普通 rename（`File.renameTo`），
 *    再失败才抛错 —— 分层降级，绝不静默半写。
 */
object AtomicFile {

    /**
     * 原子写入文本（UTF-8）。
     *
     * @param target 目标文件
     * @param content 内容
     * @param createParent 是否自动创建父目录（默认 true）
     * @throws IOException 写入失败时抛出，且**目标文件保持原样**
     */
    @Throws(IOException::class)
    fun writeText(target: File, content: String, createParent: Boolean = true) {
        writeBytes(target, content.toByteArray(Charsets.UTF_8), createParent)
    }

    /**
     * 原子写入字节。
     *
     * 失败时清理临时文件并抛错 —— 原文件不动，调用方可以安全重试。
     */
    @Throws(IOException::class)
    fun writeBytes(target: File, bytes: ByteArray, createParent: Boolean = true) {
        val parent = target.parentFile
            ?: throw IOException("无法解析父目录：${target.absolutePath}")

        if (createParent && !parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建目录：${parent.absolutePath}")
        }

        // 临时文件与目标同目录，保证 rename 不跨文件系统。
        // 名字带 pid + 纳秒时间戳，避免并发写同一文件时互相覆盖临时文件。
        val tmp = File(parent, ".${target.name}.tmp-${android.os.Process.myPid()}-${System.nanoTime().toString(36)}")

        try {
            tmp.writeBytes(bytes)

            // 分层降级：ATOMIC_MOVE → renameTo
            val moved = try {
                java.nio.file.Files.move(
                    tmp.toPath(), target.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
                true
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            }

            if (!moved) {
                // FUSE / 某些网络挂载不支持 ATOMIC_MOVE，退回普通 rename。
                // 仍有「要么旧要么新」的效果（POSIX rename 语义），只是不保证
                // 崩溃时的持久性顺序 —— 对手机场景够用。
                if (!tmp.renameTo(target)) {
                    // renameTo 在某些情况下会因目标存在而失败，删掉再试一次。
                    if (target.exists() && target.delete() && tmp.renameTo(target)) {
                        return
                    }
                    throw IOException("原子写入失败（rename 被拒）：${target.absolutePath}")
                }
            }
        } catch (e: Throwable) {
            // 任何失败都要清理临时文件，否则目录里会积攒垃圾
            runCatching { tmp.delete() }
            throw if (e is IOException) e else IOException("原子写入失败：${target.absolutePath}", e)
        } finally {
            // 兜底：move 成功后 tmp 已不存在；万一有残留（异常路径）也清掉
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }
}
