package com.ccm.app.runtime

import android.content.Context
import android.os.Process as AndroidProcess
import android.util.Log
import java.io.File

/**
 * 安装锁 —— 防并发安装（同一个 rootfs 被两个任务同时改）。
 *
 * 【为什么需要】
 * 用户连点两次「开始安装」是很常见的行为（等待时间长，会怀疑没点上）。
 * 没有锁的话两个任务会：
 *   · 同时 deleteRecursively() 同一个目录
 *   · 同时往同一个路径解压
 *   · 互相干扰对方的 renameTo
 * 结果必然是一个乱七八糟的 rootfs，而且报错信息完全看不出是并发导致的。
 *
 * 【为什么用目录锁而不是文件锁】
 * `mkdir` 在 POSIX 上是**原子操作** —— 要么成功要么失败，不存在竞争窗口。
 * 而「检查文件存在 → 创建文件」是两步，两个进程可能都通过检查然后都创建。
 * 这是 Unix 世界最经典的锁实现方式（Maildir、cron 都用它）。
 *
 * 【僵尸锁处理】
 * 用户可能强杀 App，锁目录留着。这时不能永远卡住 —— 我们往锁里写 PID，
 * 新来的检查「这个 PID 还活着吗」（kill -0 语义），不活就清掉重来。
 * 注意 PID 复用的极端情况：如果 PID 被新进程复用了，我们会误判锁还活着，
 * 但那种情况下等 120 秒也会超时退出，不会死锁。
 *
 * 【参考实现】
 * AAswordman/OperitTerminalCore 的 install_ubuntu()：用 LOCK_DIR + pid 文件
 * + 120 次重试（每次 sleep 1）。我这里把「重试等待」做成阻塞的，
 * 因为 Android 侧本来就在 IO 线程里跑。
 */
class InstallLock(private val context: Context, private val name: String) {

    companion object {
        private const val TAG = "InstallLock"

        /** 等锁最多等多久（毫秒）。120 秒 ≈ Operit 的 120 次 × 1 秒 */
        private const val WAIT_TIMEOUT_MS = 120_000L

        /** 轮询间隔 */
        private const val POLL_MS = 500L
    }

    private val lockDir = File(context.filesDir, "$name.install.lock")
    private val pidFile = File(lockDir, "pid")
    private var held = false

    /**
     * 抢锁。阻塞直到拿到或超时。
     * @return true=拿到锁（调用方最后必须 release）；false=超时
     */
    fun acquire(): Boolean {
        if (held) return true
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        var attempt = 0

        while (System.currentTimeMillis() < deadline) {
            // mkdir 是原子的：只有一个人能成功
            if (lockDir.mkdirs()) {
                try { pidFile.writeText(AndroidProcess.myPid().toString()) } catch (_: Throwable) {}
                held = true
                Log.i(TAG, "拿到安装锁：${lockDir.absolutePath}")
                return true
            }

            // 没抢到 → 看看持有者是否还活着
            if (isStale()) {
                Log.w(TAG, "发现僵尸锁（持有进程已不在），清理重试")
                lockDir.deleteRecursively()
                attempt++
                if (attempt > 3) {
                    // 连续三次都判为僵尸却还是抢不到 —— 可能是权限问题或竞态
                    Log.e(TAG, "反复清理僵尸锁仍抢不到，放弃")
                    return false
                }
                continue
            }

            try { Thread.sleep(POLL_MS) } catch (_: InterruptedException) { return false }
        }

        Log.w(TAG, "等锁超时（${WAIT_TIMEOUT_MS}ms）")
        return false
    }

    /** 释放锁 */
    fun release() {
        if (!held) return
        held = false
        try {
            pidFile.delete()
            lockDir.delete()
            Log.i(TAG, "已释放安装锁")
        } catch (t: Throwable) {
            Log.w(TAG, "释放锁失败（不影响后续，僵尸锁会被下一个安装清理）", t)
        }
    }

    /**
     * 锁是不是僵尸（持有者进程已死）。
     *
     * 判断依据：
     *   · pid 文件不存在或读不出 → 视为僵尸（可能刚 mkdir 完还没来得及写）
     *   · pid 存在但进程不在 → 僵尸
     *
     * ⚠ 第二种情况要**稍微等一会再判**：刚拿到锁的那个进程可能正在写 pid 文件，
     *   这时候 pid 文件还不存在，直接判僵尸会把别人的活锁抢掉。
     *   这里用「pid 文件不存在时，看锁目录的创建时间」来区分：
     *   刚创建（<3 秒）的锁不判僵尸。
     */
    private fun isStale(): Boolean {
        return try {
            if (pidFile.exists()) {
                val pid = pidFile.readText().trim().toIntOrNull() ?: return true
                // kill -0 语义：进程存在则不抛异常。Java 里用 /proc/<pid> 判断
                !File("/proc/$pid").exists()
            } else {
                // pid 文件还没写 —— 只有锁目录存在很久了才判僵尸
                val ageMs = System.currentTimeMillis() - lockDir.lastModified()
                ageMs > 3000
            }
        } catch (t: Throwable) {
            // 读不了就当活锁（保守），让它超时退出比误抢安全
            false
        }
    }
}
