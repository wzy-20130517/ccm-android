package com.ccm.app.core.compact

import com.ccm.app.core.session.Message

/**
 * 自动压缩决策 + 断路器。
 *
 * 对应 Node 版 `core/auto-compact.mjs`（197 行）。
 *
 * ## ⚠️ 默认**关闭**（这是用户明确要求，不是偷懒）
 *
 * 用户被自动压缩搞丢过记忆，**非常反感**。所以：
 * - [isEnabled] 默认 false
 * - 只有用户显式设了固定阈值（[tokenLimit] / [messageLimit] > 0）才会启用
 * - 上下文超长时**直接报错提示用户**，不自动截断历史（见 `AgentLoop` 的 context_overflow 处理）
 *
 * 这条约束写在 `CLAUDE.md` 的「压缩纪律」里，改动前先看那里。
 *
 * ## 水位分级（给 UI 显示，**不驱动自动压缩**）
 *
 * ```
 * 剩余 token          等级        含义
 * ────────────────────────────────────────────
 * ≤ 13000            blocking    快满了，该动手了
 * ≤ 20000            error       明显紧张
 * ≤ 33000            warning     有点紧张
 * > 33000            ok          正常
 * ```
 *
 * ⚠️ **这些分级只用于状态栏提示**，不触发任何自动行为。
 * 早期版本用 `warning` 等级驱动自动摘要，结果「用户还在正常聊天就被压缩了」。
 *
 * ## 断路器（连续 3 次失败就停）
 *
 * 压缩要调一次 API（生成摘要）。如果那个请求一直失败（key 失效、模型不支持），
 * 每次到阈值就重试一遍 = **每轮都白烧一次失败请求**。
 * 所以连续失败 3 次就停用，提示用户手动 `/compact`。
 */
class AutoCompact(
    /** token 阈值（0 = 不按 token 触发）。 */
    @Volatile
    var tokenLimit: Int = 0,
    /** 消息条数阈值（0 = 不按条数触发）。 */
    @Volatile
    var messageLimit: Int = 0,
    /** 模型上下文窗口。 */
    @Volatile
    var maxContext: Int = 1_000_000,
) {

    /** 连续失败次数（断路器用）。 */
    @Volatile
    private var consecutiveFailures: Int = 0

    /**
     * 自动压缩是否启用。
     *
     * **默认 false** —— 只有用户设了固定阈值才开（见类注释）。
     */
    val isEnabled: Boolean
        get() = tokenLimit > 0 || messageLimit > 0

    /** 断路器是否已跳闸（连续 3 次失败）。 */
    val isTripped: Boolean
        get() = consecutiveFailures >= MAX_FAILURES

    /** 连续失败次数。 */
    val failures: Int
        get() = consecutiveFailures

    /**
     * 本轮结束后是否该尝试自动压缩。
     *
     * ⚠️ **默认返回 false**（未启用时直接跳过，不评估、不打日志）。
     */
    fun shouldCompact(messages: List<Message>, lastPromptTokens: Int): Boolean {
        if (!isEnabled) return false
        if (isTripped) return false

        // 固定消息条数
        if (messageLimit > 0 && messages.size > messageLimit) return true
        // 固定 token（lastPromptTokens 来自 API 返回的真实用量）
        if (tokenLimit > 0 && lastPromptTokens > 0 && lastPromptTokens > tokenLimit) return true

        return false
    }

    /**
     * 上下文水位（给 UI 状态栏显示）。
     *
     * @param lastPromptTokens 上次请求的真实 prompt_tokens（0 = 未知）
     */
    fun waterLevel(lastPromptTokens: Int): WaterLevel {
        if (lastPromptTokens <= 0) {
            return WaterLevel(Level.OK, -1)
        }
        val remain = maxContext - lastPromptTokens
        val level = when {
            remain <= MARGIN_BLOCKING -> Level.BLOCKING
            remain <= MARGIN_ERROR -> Level.ERROR
            remain <= MARGIN_WARNING -> Level.WARNING
            else -> Level.OK
        }
        return WaterLevel(level, remain)
    }

    /** 压缩成功 → 重置断路器。 */
    fun reportSuccess() {
        consecutiveFailures = 0
    }

    /** 压缩失败 → 计数（连续 3 次后跳闸）。 */
    fun reportFailure() {
        consecutiveFailures++
    }

    /** 手动重置断路器（用户 `/compact` 成功后调）。 */
    fun resetFailures() {
        consecutiveFailures = 0
    }

    /** 水位等级。 */
    enum class Level {
        OK,
        WARNING,
        ERROR,
        BLOCKING,
    }

    /** 水位结果。 */
    data class WaterLevel(val level: Level, val remainTokens: Int) {
        /** 给 UI 的一句话描述。 */
        val label: String
            get() = when (level) {
                Level.OK -> "正常"
                Level.WARNING -> "上下文偏紧"
                Level.ERROR -> "上下文紧张，建议 /compact"
                Level.BLOCKING -> "上下文即将耗尽，请立即 /compact"
            }
    }

    companion object {
        /** 断路器阈值：连续 3 次失败停用。 */
        const val MAX_FAILURES = 3

        /** blocking 水位：剩余 ≤ 13000。 */
        const val MARGIN_BLOCKING = 13_000

        /** error 水位：剩余 ≤ 20000。 */
        const val MARGIN_ERROR = 20_000

        /** warning 水位：剩余 ≤ 33000。 */
        const val MARGIN_WARNING = 33_000
    }
}
