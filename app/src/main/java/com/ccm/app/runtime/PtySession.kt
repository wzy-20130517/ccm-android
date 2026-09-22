package com.ccm.app.runtime

import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一个 PTY 会话 —— 对应一个在 rootfs 里跑的 shell 进程。
 *
 * 【为什么需要 PTY 而不是普通管道】
 * 很多程序（git、apt、npm）会检测 stdout 是不是 tty：
 * - 不是 tty → 关闭颜色、关闭进度条、缓冲输出（导致"卡住"的假象）
 * - 是 tty  → 正常交互行为
 * 而且 apt/git 会问 y/n，没有 PTY 就没法交互。
 *
 * 【实现】
 * 用 `script` 命令在 proot 里创建 pty（util-linux 自带）：
 *   proot ... script -qfc "bash -l" /dev/null
 * 这样 stdin/stdout 就是我们和 pty 之间的桥，天然带 tty 语义。
 *
 * 另一种方式是 NDK 调 openpty()，但那样要写 JNI + 处理 fd 传递，
 * 复杂度高得多。`script` 方案零 JNI，实测够用。
 */
class PtySession(
    private val prootArgs: List<String>,
    private val cwd: File,
    private val env: Map<String, String> = emptyMap(),
    private val onOutput: (String) -> Unit,
    private val onExit: (Int) -> Unit = {}
) {

    companion object {
        private const val TAG = "PtySession"
        private const val BUF = 4096
    }

    private var process: Process? = null
    private var readerThread: Thread? = null
    private val running = AtomicBoolean(false)

    val isRunning: Boolean get() = running.get()

    /** 启动会话 */
    fun start(): Boolean {
        if (running.get()) return false
        return try {
            val pb = ProcessBuilder(prootArgs)
            pb.directory(cwd)
            pb.redirectErrorStream(true)   // stderr 合并到 stdout（终端就是这样）
            env.forEach { (k, v) -> pb.environment()[k] = v }

            val p = pb.start()
            process = p
            running.set(true)

            // 读线程：持续把输出喂给回调
            readerThread = Thread({
                val buf = ByteArray(BUF)
                try {
                    val input: InputStream = p.inputStream
                    while (running.get()) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        // 原样透传字节 → 字符串（终端控制序列保留）
                        onOutput(String(buf, 0, n, Charsets.UTF_8))
                    }
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "读取中断: ${e.message}")
                } finally {
                    val code = try { p.waitFor() } catch (e: Throwable) { -1 }
                    running.set(false)
                    onExit(code)
                }
            }, "pty-reader").apply { isDaemon = true; start() }

            true
        } catch (t: Throwable) {
            Log.e(TAG, "启动失败", t)
            running.set(false)
            false
        }
    }

    /** 写入数据（用户输入 / 命令） */
    fun write(data: String): Boolean {
        val p = process ?: return false
        if (!running.get()) return false
        return try {
            val out: OutputStream = p.outputStream
            out.write(data.toByteArray(Charsets.UTF_8))
            out.flush()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "写入失败: ${t.message}")
            false
        }
    }

    /** 发送一行命令（自动补 \n） */
    fun sendLine(line: String): Boolean = write(line + "\n")

    /** 发送 Ctrl+C（中断当前命令） */
    fun interrupt(): Boolean = write("\u0003")

    /** 发送 Ctrl+D（EOF） */
    fun eof(): Boolean = write("\u0004")

    /** 调整终端大小（发 SIGWINCH + 设置 stty） */
    fun resize(cols: Int, rows: Int): Boolean {
        // 通过 stty 设置（简单可靠）
        return sendLine("stty cols $cols rows $rows 2>/dev/null")
    }

    /** 终止会话 */
    fun kill() {
        running.set(false)
        try {
            process?.destroy()
            // 给它 300ms 优雅退出
            Thread {
                try {
                    if (!process!!.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        process?.destroyForcibly()
                    }
                } catch (_: Throwable) {}
            }.start()
        } catch (t: Throwable) {
            Log.w(TAG, "kill 异常: ${t.message}")
        }
    }

    /** 等待退出 */
    fun waitFor(timeoutMs: Long = 0): Int {
        val p = process ?: return -1
        return try {
            if (timeoutMs > 0) {
                if (p.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) p.exitValue() else -2
            } else {
                p.waitFor()
            }
        } catch (t: Throwable) { -1 }
    }
}
