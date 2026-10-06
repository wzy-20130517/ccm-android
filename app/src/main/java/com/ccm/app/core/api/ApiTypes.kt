package com.ccm.app.core.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * API 层的请求/响应模型。
 *
 * 这些类型**只用于 API 通信**，不是会话历史模型（那是 `core/session/Message.kt`）。
 * 两者的区别：会话历史要落盘、要兼容 Node 版格式；这里的是「发出去/收回来」的线格式。
 *
 * ## 为什么不直接用 Message
 * 三套协议的线格式互不相同（OpenAI 用 `tool_calls`、Anthropic 用 `tool_use` block、
 * Responses 用 `function_call` item），而会话历史是**协议无关**的中间表示。
 * 混用会导致「存进去是 OpenAI 格式，切到 Anthropic 就发不出去」。
 */
object ApiTypes {

    // ───────────────────────── 协议 ─────────────────────────
    //
    // ⚠️ 协议枚举**不在这里** —— 唯一真源是 `core.provider.Protocol`
    // （带 `path` / `baseShouldHaveV1` / `normalizeBaseUrl` / `toConfigString`）。
    //
    // 【为什么删掉这里的重复定义】曾经这里也有一份 `enum class Protocol(val id)`，
    // 同一件事有了两个真源：配置层存 `provider.Protocol`，请求层用 `ApiTypes.Protocol`。
    // 加新协议要改两处，漏一处就是「配置能存但请求发不出」这类静默故障。
    // 统一后**所有协议判断都走 `core.provider.Protocol`**。

    // ───────────────────────── 流式事件 ─────────────────────────

    /**
     * 从 SSE 流里解析出的事件。
     *
     * 三套协议的原始事件格式完全不同，但都能归一到这几种。
     * `ApiClient.stream()` 吐这些，`AgentLoop` 消费它们拼装消息。
     */
    sealed class StreamEvent {

        /** 正文增量。 */
        data class Text(val text: String) : StreamEvent()

        /**
         * 思考增量（reasoning / thinking）。
         *
         * 来源：OpenAI 的 `delta.reasoning_content`（GLM/DeepSeek 系）、
         * Anthropic 的 `thinking_delta`、Responses 的 `reasoning_summary_text.delta`。
         */
        data class Reasoning(val text: String) : StreamEvent()

        /**
         * 工具调用参数增量（**流式拼接**）。
         *
         * ⚠️ 工具的 arguments 是**分片到达**的，必须按 [index] 累加拼接，
         * 直到流结束才是完整 JSON。直接解析单片会得到残缺 JSON。
         *
         * @param index 工具调用序号（一轮可能有多个工具调用）
         * @param id 工具调用 id（通常只在第一片出现，后续片为 null）
         * @param name 工具名（通常只在第一片出现）
         * @param argumentsDelta 本次到达的参数片段
         */
        data class ToolCallDelta(
            val index: Int,
            val id: String?,
            val name: String?,
            val argumentsDelta: String,
        ) : StreamEvent()

        /**
         * 流结束标记（收到 `[DONE]` 或 message_stop）。
         *
         * @param finishReason 上游给的结束原因（`length` = 被 max_output_tokens
         *   截断，`stop` = 正常结束，`tool_calls` = 要调工具）。
         *   【2026-10-06 加】原来 Done 不带这个字段，AgentLoop 无法区分
         *   「正常结束」和「被截断」—— 截断时只能等用户催「继续」。
         */
        data class Done(val finishReason: String? = null) : StreamEvent()

        /**
         * 用量统计（通常流末尾才给）。
         *
         * 有些网关只在流末给 usage，有些中间也发；取最后一次非零值。
         */
        data class Usage(
            val inputTokens: Int,
            val outputTokens: Int,
            /** 命中缓存的输入 token（Anthropic 的 cache_read_input_tokens）。 */
            val cacheReadTokens: Int = 0,
            /** 写入缓存的输入 token。 */
            val cacheWriteTokens: Int = 0,
        ) : StreamEvent()

        /**
         * 解析错误（SSE 里有非法 JSON、或结构不符合预期）。
         *
         * **不是致命错误** —— 单条事件解析失败不该中断整个流，
         * 记录后继续读下一条（网关偶尔会发心跳/注释行）。
         */
        data class ParseError(
            val protocol: String,
            val error: String,
            val raw: String,
        ) : StreamEvent()
    }

    // ───────────────────────── 用量 ─────────────────────────

    /**
     * token 用量。
     *
     * 字段名对齐 OpenAI 的 `usage` 对象，但 `cacheRead` / `cacheWrite` 来自 Anthropic。
     */
    @Serializable
    data class TokenUsage(
        @SerialName("prompt_tokens") val promptTokens: Int = 0,
        @SerialName("completion_tokens") val completionTokens: Int = 0,
        @SerialName("total_tokens") val totalTokens: Int = 0,
        /** Anthropic：命中缓存的输入 token。 */
        @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
        /** Anthropic：写入缓存的输入 token。 */
        @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    ) {
        val isEmpty: Boolean get() = promptTokens == 0 && completionTokens == 0 && totalTokens == 0
    }

    // ───────────────────────── 工具定义 ─────────────────────────

    /**
     * 发给模型的工具定义（线格式）。
     *
     * 三套协议的外层字段名不同（OpenAI 用 `function.parameters`、
     * Anthropic 用 `input_schema`），但都指向同一份 [parameters]。
     * 序列化时由 [Protocol] 决定外壳。
     */
    data class ToolDefinition(
        val name: String,
        val description: String,
        /** JSON Schema。**必须经 normalizeToolSchema 处理过**（防 Gemini 400）。 */
        val parameters: JsonObject,
    )

    // ───────────────────────── 非流式响应 ─────────────────────────

    /**
     * 一次完整响应的归一化结果。
     *
     * 三套协议的响应体结构完全不同，都归到这里。
     * 流式路径不走这里（流式边收边吐 [StreamEvent]）。
     */
    data class ChatResponse(
        /** 正文。 */
        val text: String,
        /** 思考内容（可能为 null）。 */
        val reasoning: String? = null,
        /** 工具调用列表。 */
        val toolCalls: List<ToolCall> = emptyList(),
        /** 用量。 */
        val usage: TokenUsage = TokenUsage(),
        /** 结束原因：`stop` / `length` / `tool_calls` / `content_filter`。 */
        val finishReason: String? = null,
    )

    /**
     * 一个工具调用（已拼装完整）。
     *
     * [arguments] 是**原始 JSON 字符串** —— 不在这里解析，因为：
     * 1. 模型可能给出非法 JSON（少个括号），解析失败要在工具层报「参数不合法」，
     *    而不是让整个响应解析崩掉
     * 2. 有些工具的参数故意接受非对象（如直接给个字符串）
     */
    data class ToolCall(
        val id: String,
        val name: String,
        val arguments: String,
    )

    // ───────────────────────── 错误 ─────────────────────────

    /**
     * API 调用失败。
     *
     * @param message 面向人的错误描述（含 HTTP 状态码，供 `_classifyError` 正则匹配）
     * @param statusCode HTTP 状态码，0 = 非 HTTP 错误（网络层）
     * @param retryable 是否可重试。由 [ErrorClassifier] 填。
     * @param retriesExhausted 底层是否已重试穷尽。**true 时上层绝不能再重试**
     *   —— 防「重试跨层叠加」（Node 版踩过：60s×3×4 = 687s 静默卡死）
     */
    class ApiException(
        override val message: String,
        val statusCode: Int = 0,
        val retryable: Boolean = false,
        val retriesExhausted: Boolean = false,
        cause: Throwable? = null,
    ) : Exception(message, cause)
}
