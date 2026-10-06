package com.ccm.app.tools.bash

import android.app.IntentService
import android.content.Intent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * Termux RUN_COMMAND 的结果接收器（2026-10-06 加）。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么是 IntentService 而不是动态注册的 BroadcastReceiver
 * ══════════════════════════════════════════════════════════════
 *
 * 官方 RUN_COMMAND-Intent wiki 的示例就是 IntentService + PendingIntent.getService。
 * 之前用 BroadcastReceiver + getBroadcast 时完全不工作，原因：
 *
 *  1. **requestCode 必须唯一**（官方明确警告）—— 固定 0 时第一次投递后
 *     PendingIntent 被系统取消，之后所有调用都收不到结果。
 *  2. **动态注册的 receiver 在 Android 13+ 要指定 exported 标志**，
 *     14+ 对隐式广播限制更多；而 PendingIntent 投给 Service 由系统处理，
 *     不受这些限制。
 *
 * ══════════════════════════════════════════════════════════════
 *  结果怎么回到调用方
 * ══════════════════════════════════════════════════════════════
 *
 * 调用方（TermuxChannel.execute）在发命令前 [register] 一个队列，
 * 用 executionId 做键；本 Service 收到结果后按 id 找到队列投进去。
 * 调用方 poll 队列（带超时）。
 *
 * 队列用 ArrayBlockingQueue（容量 1）—— 一个 executionId 只会有一个结果，
 * offer 不会阻塞（满了也只是丢弃，不该卡住 Service 的主线程）。
 */
class TermuxResultService : IntentService("TermuxResultService") {

    companion object {
        private const val TAG = "CCM-TermuxResult"
        const val EXTRA_EXECUTION_ID = "ccm_execution_id"

        /** executionId → 等待结果的队列。 */
        private val pending = ConcurrentHashMap<Int, ArrayBlockingQueue<android.os.Bundle>>()

        /** 注册一个等待队列（调用方在发命令前调）。 */
        fun register(executionId: Int, queue: ArrayBlockingQueue<android.os.Bundle>) {
            pending[executionId] = queue
        }

        /** 取走并移除（调用方 poll 完调，防泄漏）。 */
        fun unregister(executionId: Int) {
            pending.remove(executionId)
        }
    }

    override fun onHandleIntent(intent: Intent?) {
        if (intent == null) {
            android.util.Log.w(TAG, "onHandleIntent: intent == null")
            return
        }
        val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        // 诊断日志（2026-10-06）：这条链路原来完全静默，
        // 出问题时只能看到「exit=-1 无输出」，不知道结果到底有没有回来。
        android.util.Log.i(TAG, "收到 Termux 结果：executionId=$id, keys=${intent.extras?.keySet()?.joinToString()}")
        if (id < 0) {
            android.util.Log.w(TAG, "executionId 缺失（-1）—— 投递的 Intent 不是我们发出的？")
            return
        }
        val queue = pending.remove(id)
        if (queue == null) {
            android.util.Log.w(TAG, "没有等待队列（id=$id）—— 可能已超时清理")
            return
        }
        val extras = intent.extras
        if (extras == null) {
            android.util.Log.w(TAG, "结果 extras 为 null —— 投递了空结果")
        } else {
            // 官方格式：结果在 "result" 这个 Bundle 里
            val rb = extras.getBundle("result")
            android.util.Log.i(
                TAG,
                "结果内容：bundle=${if (rb != null) "有" else "无"}，" +
                    "exitCode=${rb?.getInt("exitCode", Int.MIN_VALUE)}，" +
                    "stdout_len=${rb?.getString("stdout")?.length ?: -1}",
            )
        }
        queue.offer(extras ?: android.os.Bundle())
    }
}
