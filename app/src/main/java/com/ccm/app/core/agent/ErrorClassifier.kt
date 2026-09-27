package com.ccm.app.core.agent

import com.ccm.app.core.api.ApiTypes

/**
 * 错误分类与重试决策。
 *
 * 从 `AgentLoop` 抽出，因为这是**整个项目最容易出错、也最该被测试**的一块 ——
 * Node 版在这里踩过的坑，每一个都让用户白等几分钟到十几分钟。
 *
 * ## 核心原则：重试只允许一层
 *
 * ```
 * ❌ 错误示范（Node 版真实事故）
 *    api 层重试 3 次  →  agent 层再重试 4 轮  =  12 次请求 / 687 秒
 *    用户视角：「十几分钟一声不响，连超时都不报」
 *    而且 watchdog 也不会响 —— 每次 fetch 都先抛错，够不到它的门槛
 * ```
 *
 * 所以判据是：**下层说「我已经重试穷尽了」（`retriesExhausted`），上层就必须闭嘴。**
 * 这一条优先级最高，先于所有其他判断。
 *
 * ## 分类表（与 Node 版 `_classifyError` 的 name 字段一一对应）
 *
 * | 分类 | 可重试 | 说明 |
 * |---|---|---|
 * | `retries_exhausted` | ✗ | 下层已重试穷尽，**绝不再叠加** |
 * | `context_overflow` | ✗ | 输入超上下文窗口，重试同样请求无意义 |
 * | `stream_timeout` | ✗ | 流级 watchdog 卡死，重试同一 stream 没意义 |
 * | `connect_timeout` | ✗ | fetch 层超时（api 内部已重试过） |
 * | `auth` (401/403) | ✓ | **「这个 key 不行」不是「这个请求不行」** —— 换 key 可能成功 |
 * | `rate_limit` (429) | ✓ | 限流，退避后可能成功 |
 * | `server` (5xx) | ✓ | 服务端错误 |
 * | `client_4xx` | ✗ | 请求本身的问题，重试同样的请求无意义 |
 * | `network` | ✓ | 网络抖动 |
 * | `unknown` | ✗ | 未知，安全默认不重试 |
 *
 * ## 401/403 为什么可重试（反直觉但重要）
 *
 * Node 版曾把 4xx 全判死（除 429），后果是：
 * API 层的 `isKeyExhaustedError` 明明认得 `GROUP_DELETED` / `invalid_api_key`、
 * 想换 key 重试，但请求在到达那一步**之前**就被否决了。
 * 实测 trace：`retry:0 · retryable:false` —— 池里另外 3 个 key 一次都没被用过。
 * 用户观察到的关键特征是「**只报同一个 403，从不变化**」。
 */
object ErrorClassifier {

    /** 分类结果。 */
    data class Classification(
        /** 分类名（写进 trace，**不要改这些字符串** —— 历史日志会对不上）。 */
        val name: String,
        /** 是否可重试。 */
        val retryable: Boolean,
        /** 面向人的说明。 */
        val description: String,
    )

    /**
     * 分类一个 API 异常。
     *
     * ⚠️ **顺序很重要**：`retriesExhausted` 必须最先判 —— 它是「下层已重试穷尽」
     * 的硬标记，看到它就绝不能再重试。
     */
    fun classify(e: ApiTypes.ApiException): Classification {
        // ① 最高优先级：下层已重试穷尽
        if (e.retriesExhausted) {
            return Classification(
                name = "retries_exhausted",
                retryable = false,
                description = "下层已重试穷尽，不再叠加（防跨层重试放大）",
            )
        }

        // ② 上下文超限：重试同样的请求必然再失败
        val msg = e.message
        if (isContextOverflow(msg)) {
            return Classification(
                name = "context_overflow",
                retryable = false,
                description = "输入超过模型上下文窗口，重试相同请求没有意义",
            )
        }

        // ③ 按 HTTP 状态码判定（比在整条消息里搜三位数可靠得多）
        when (e.statusCode) {
            401, 403 -> return Classification(
                name = "auth",
                retryable = true,
                description = "HTTP ${e.statusCode} 认证/授权失败，换 key 后可能成功",
            )
            429 -> return Classification(
                name = "rate_limit",
                retryable = true,
                description = "限流，可重试",
            )
            in 500..599 -> return Classification(
                name = "server",
                retryable = true,
                description = "服务端错误，可重试",
            )
            in 400..499 -> return Classification(
                name = "client_4xx",
                retryable = false,
                description = "HTTP ${e.statusCode} 请求错误，重试无意义",
            )
        }

        // ④ 按消息特征兜底（没有 HTTP 前缀的错误）
        return when {
            msg.contains("Stream timeout", ignoreCase = true) -> Classification(
                "stream_timeout", false, "流级 watchdog 卡死，重试同一 stream 没意义",
            )
            CONNECT_TIMEOUT_RE.containsMatchIn(msg) -> Classification(
                "connect_timeout", false, "fetch 层超时（api 内部已重试），不再叠加重试",
            )
            msg.contains("Request aborted by transport", ignoreCase = true) -> Classification(
                "transport_abort", false, "传输层中断（api 内部已重试）",
            )
            NETWORK_RE.containsMatchIn(msg) -> Classification(
                "network", true, "网络错误，可重试",
            )
            RATE_LIMIT_RE.containsMatchIn(msg) -> Classification(
                "rate_limit", true, "限流，可重试",
            )
            SERVER_TEXT_RE.containsMatchIn(msg) -> Classification(
                "server", true, "服务端错误，可重试",
            )
            else -> Classification("unknown", false, "未知错误，不重试")
        }
    }

    /** 便捷：要不要重试。 */
    fun shouldRetry(e: ApiTypes.ApiException): Boolean = classify(e).retryable

    /**
     * 是否「上下文超限」。
     *
     * 判据来自各家 API 的真实报错文案（中文的也要认 —— 国内中转站会翻译）。
     */
    fun isContextOverflow(msg: String): Boolean =
        CONTEXT_OVERFLOW_RE.containsMatchIn(msg)

    /**
     * 退避时长（指数）。
     *
     * 1s → 2s → 4s。**不要设得更长** —— 用户等的是这个时间，
     * 而重试本来就有失败概率，长退避会让「必然失败」的场景白等更久。
     */
    fun backoffMs(attempt: Int): Long = 1000L shl attempt.coerceIn(0, 4)

    // ───────────────────────── 正则表 ─────────────────────────

    private val CONTEXT_OVERFLOW_RE = Regex(
        "input\\s+exceeds?\\s+(?:the\\s+)?context\\s+window" +
            "|context\\s*(?:window|length|limit)" +
            "|maximum\\s+context" +
            "|too\\s+many\\s+tokens" +
            "|prompt\\s+(?:is\\s+)?too\\s+long" +
            "|上下文.{0,12}(?:超|过大|限制)",
        RegexOption.IGNORE_CASE,
    )

    /** fetch 层超时（api.mjs 的 `Request timeout after Nms`）。 */
    private val CONNECT_TIMEOUT_RE = Regex("Request timeout after \\d+ms", RegexOption.IGNORE_CASE)

    private val NETWORK_RE = Regex(
        "ETIMEDOUT|ECONNRESET|ECONNREFUSED|socket hang up|network error|fetch failed|" +
            "ENOTFOUND|EAI_AGAIN|network|连接.*(?:失败|中断|重置)",
        RegexOption.IGNORE_CASE,
    )

    private val RATE_LIMIT_RE = Regex("\\b429\\b|rate limit|quota", RegexOption.IGNORE_CASE)

    /**
     * 服务端错误的**文本特征**（没有 HTTP 前缀时用）。
     *
     * 注意：**不要用裸的 `\b5\d\d\b`** —— 它会把 4xx 里出现的数字误当状态码：
     * - `HTTP 400: max_tokens must be <= 512` → 512 命中 → 误判可重试
     * - `HTTP 400: temperature 0.500 invalid` → 500 命中 → 误判可重试
     * 结果是必然失败的参数错误被重试 5 次，白等 31 秒。
     *
     * 另外中转站（new_api 等）的瞬时抖动不带 HTTP 前缀，纯文案特征：
     * 「该模型所有渠道都因错误而被禁用，请稍后重试」—— 上游 5xx 被网关包装后的说法。
     */
    private val SERVER_TEXT_RE = Regex(
        "SERVICE_BUSY|LITELLM_UNAVAILABLE|upstream_unavailable|bad\\s+gateway|" +
            "service\\s+unavailable|internal\\s+server\\s+error|请稍后重试|稍后重试",
        RegexOption.IGNORE_CASE,
    )
}
