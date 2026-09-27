package com.ccm.app.core.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 会话自动保存。
 *
 * 对应 Node 版 `core/session-auto.mjs`。
 *
 * ## 为什么要「延迟合并」而不是每次改动都写盘
 *
 * Agent 一轮 run 会产生几十次消息追加。每次都写盘的话：
 * - **磁盘压力**：会话文件可能几百 KB，一轮几十次写 = 几 MB IO
 * - **耗电**：手机上闪存写入是耗电大户
 * - **无意义**：用户看不到中间态，只关心最终结果
 *
 * 所以用「**脏标记 + 定时落盘**」：
 * ```
 * 消息变化 → 标脏 → 等 30 秒 → 真的写一次
 * 期间又变化 → 重置计时（继续等）
 * ```
 * 这就是**防抖**（debounce），Node 版用的是 30 秒间隔。
 *
 * ## 关键保证：退出时必须 flush
 *
 * 防抖的代价是「最后 30 秒的改动还在内存里」。如果这时进程被杀，
 * 那部分对话就丢了。所以：
 * - [flush] 必须能在 `onPause` / `onDestroy` 里同步调用
 * - [stop] 停掉定时器并 flush
 *
 * ## 用法
 * ```kotlin
 * val auto = SessionAuto(store, sessionId, scope) { agentLoop.getHistory() }
 * auto.start()                        // 开定时器
 * auto.markDirty()                    // 消息变了就标一下（很便宜）
 * auto.flush()                        // 退出前强制写盘
 * ```
 */
class SessionAuto(
    private val store: SessionStore,
    /** 当前会话 id（可变 —— 用户可能 /new 换会话）。 */
    private var sessionId: String,
    /** 取当前历史的函数（惰性取，因为历史一直在变）。 */
    private val historyProvider: () -> List<Message>,
    /** 协程作用域（通常是 App 级 scope）。 */
    private val scope: CoroutineScope,
    /** 防抖间隔（毫秒）。 */
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {

    @Volatile
    private var dirty = false

    @Volatile
    private var lastSavedAt: Long = 0

    private var timerJob: Job? = null

    /** 会话创建时间（首次保存时定）。 */
    private var createdAt: Long = System.currentTimeMillis()

    /** 会话名（`/rename` 设置）。 */
    @Volatile
    var title: String? = null

    /** 是否正在运行。 */
    @Volatile
    private var running = false

    /** 启动定时落盘循环。 */
    fun start() {
        if (running) return
        running = true
        timerJob = scope.launch {
            while (isActive && running) {
                delay(debounceMs)
                if (dirty) {
                    saveNow()
                }
            }
        }
    }

    /** 停止定时器并强制落盘（退出前调）。 */
    fun stop() {
        running = false
        timerJob?.cancel()
        timerJob = null
        flush()
    }

    /** 标记「有改动，待落盘」。很便宜，可以随便调。 */
    fun markDirty() {
        dirty = true
    }

    /**
     * 立即落盘（如果脏）。
     *
     * 同步执行 —— 用于 `onPause` / `onDestroy` 这种「马上就要走」的时机，
     * 不能等协程调度。
     */
    fun flush() {
        if (!dirty) return
        saveNow()
    }

    /** 换会话（`/new` / `/resume` 时调）。会先 flush 旧会话。 */
    fun switchTo(newSessionId: String, newTitle: String? = null) {
        flush()
        sessionId = newSessionId
        title = newTitle
        createdAt = System.currentTimeMillis()
        dirty = false
    }

    /** 当前会话 id。 */
    val currentSessionId: String get() = sessionId

    /** 上次成功落盘的时间戳（0 = 从未）。 */
    val lastSaved: Long get() = lastSavedAt

    // ═════════════════════════ 内部 ═════════════════════════

    private fun saveNow() {
        try {
            val history = historyProvider()
            if (history.isEmpty() && lastSavedAt == 0L) {
                // 从没存过且历史为空 → 不创建空文件
                dirty = false
                return
            }
            store.save(
                Session(
                    sessionId = sessionId,
                    title = title,
                    createdAt = createdAt,
                    updatedAt = System.currentTimeMillis(),
                    messages = history,
                )
            )
            lastSavedAt = System.currentTimeMillis()
            dirty = false
        } catch (_: Throwable) {
            // 保存失败不清脏标记 —— 下次定时器到点还会再试
        }
    }

    companion object {
        /**
         * 防抖间隔：30 秒（对齐 Node 版）。
         *
         * 调大 → 丢数据的窗口更长；调小 → 写盘更频繁（耗电）。
         * 30 秒是 Node 版长期使用的值。
         */
        const val DEFAULT_DEBOUNCE_MS = 30_000L
    }
}
