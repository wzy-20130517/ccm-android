package com.ccm.app.core.api

import com.ccm.app.core.provider.Protocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 三套协议的 SSE 负载解析。
 *
 * 输入是 [SseReader] 吐出的单条 `data:` 负载（已去掉前缀），
 * 输出是归一化的 [ApiTypes.StreamEvent]。
 *
 * ## 为什么集中在一个类里
 * 三套协议的事件名、字段名、嵌套层次都不一样，但**语义一一对应**：
 *
 * | 语义 | OpenAI | Anthropic | Responses |
 * |---|---|---|---|
 * | 正文增量 | `choices[].delta.content` | `content_block_delta.text_delta` | `response.output_text.delta` |
 * | 思考增量 | `delta.reasoning_content` | `content_block_delta.thinking_delta` | `response.reasoning_summary_text.delta` |
 * | 工具参数 | `delta.tool_calls[].function.arguments` | `content_block_delta.input_json_delta` | `response.function_call_arguments.delta` |
 * | 结束 | `[DONE]` | `message_stop` | `response.completed` |
 *
 * 放在一起才能一眼看出对应关系，也方便加新协议。
 *
 * ## 容错原则
 * **单条事件解析失败绝不中断流**。网关偶尔会发心跳、注释、或格式不完全合规的
 * 事件；为一条坏数据丢掉整个响应是荒谬的。解析失败吐 [ApiTypes.StreamEvent.ParseError]
 * 让上层记 trace，然后继续。
 */
class StreamParser(
    private val protocol: Protocol,
    private val json: Json = DEFAULT_JSON,
) {

    /**
     * 工具调用的**流式拼接状态**。
     *
     * ⚠️ 这是本类唯一的可变状态，而且**必须每次请求新建**。
     * Node 版踩过「增量状态跨请求残留」的坑（见 `resetIncrementalState`），
     * 表现为「上一次的工具参数混进这一次」。
     */
    private val toolCallAccum = LinkedHashMap<Int, ToolCallAccum>()

    /** 累计的工具调用（流结束后读）。 */
    private data class ToolCallAccum(
        var id: String = "",
        var name: String = "",
        val args: StringBuilder = StringBuilder(),
    )

    /**
     * 解析一条 SSE 负载。
     *
     * @param data 单条 `data:` 后的负载（已 trim）
     * @return 零到多个事件（一条负载可能拆出多个语义，也可能什么都不产生）
     */
    /** 最近一次 finish_reason（流末 [DONE] 时带出去，见 Done 的注释）。 */
    private var lastFinishReason: String? = null

    fun parse(data: String): List<ApiTypes.StreamEvent> {
        // 结束标记：OpenAI 用 [DONE]
        // 【2026-10-06】带上上一 chunk 记下的 finish_reason ——
        // 原来是 `Done`（无参），AgentLoop 不知道是被截断还是正常结束。
        if (data == "[DONE]") return listOf(ApiTypes.StreamEvent.Done(lastFinishReason))

        val root: JsonObject = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Throwable) {
            return listOf(
                ApiTypes.StreamEvent.ParseError(
                    protocol = Protocol.toConfigString(protocol),
                    error = e.message ?: "invalid SSE JSON",
                    raw = data.take(200),
                )
            )
        }

        return try {
            when (protocol) {
                Protocol.OPENAI -> parseOpenAi(root)
                Protocol.ANTHROPIC -> parseAnthropic(root)
                Protocol.RESPONSES -> parseResponses(root)
            }
        } catch (e: Throwable) {
            listOf(
                ApiTypes.StreamEvent.ParseError(
                    protocol = Protocol.toConfigString(protocol),
                    error = e.message ?: "parse failed",
                    raw = data.take(200),
                )
            )
        }
    }

    /**
     * 流结束后的完整工具调用列表。
     *
     * **必须在流读完后调用** —— 参数是分片拼的，中途调用会拿到残缺 JSON。
     */
    fun finishToolCalls(): List<ApiTypes.ToolCall> =
        toolCallAccum.entries.sortedBy { it.key }.map { (_, a) ->
            ApiTypes.ToolCall(id = a.id, name = a.name, arguments = a.args.toString())
        }

    // ═════════════════════════ OpenAI ═════════════════════════

    private fun parseOpenAi(root: JsonObject): List<ApiTypes.StreamEvent> {
        val out = mutableListOf<ApiTypes.StreamEvent>()

        // usage 可能独立出现在某个 chunk（stream_options.include_usage）
        root["usage"]?.let { u ->
            if (u is JsonObject) out += parseOpenAiUsage(u)
        }

        val choices = root["choices"] as? JsonArray ?: return out
        for (choiceEl in choices) {
            val choice = choiceEl as? JsonObject ?: continue
            val delta = choice["delta"] as? JsonObject

            if (delta != null) {
                // 正文
                (delta["content"] as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { out += ApiTypes.StreamEvent.Text(it) }

                // 思考：GLM/DeepSeek 系用 reasoning_content，少数用 reasoning
                val reasoning = (delta["reasoning_content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: (delta["reasoning"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!reasoning.isNullOrEmpty()) {
                    out += ApiTypes.StreamEvent.Reasoning(reasoning)
                }

                // 工具调用增量
                (delta["tool_calls"] as? JsonArray)?.forEachIndexed { fallbackIdx, tcEl ->
                    val tc = tcEl as? JsonObject ?: return@forEachIndexed
                    val idx = (tc["index"] as? JsonPrimitive)?.intOrNull ?: fallbackIdx
                    val id = (tc["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val fn = tc["function"] as? JsonObject
                    val name = (fn?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val argsDelta = (fn?.get("arguments") as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content.orEmpty()

                    val acc = toolCallAccum.getOrPut(idx) { ToolCallAccum() }
                    if (!id.isNullOrEmpty()) acc.id = id
                    if (!name.isNullOrEmpty()) acc.name = name
                    if (argsDelta.isNotEmpty()) acc.args.append(argsDelta)

                    out += ApiTypes.StreamEvent.ToolCallDelta(
                        index = idx, id = id, name = name, argumentsDelta = argsDelta,
                    )
                }
            }

            // finish_reason 非空表示这一轮结束
            val finish = (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (!finish.isNullOrEmpty()) {
                // OpenAI 流在最后一个 chunk 给 finish_reason，随后才有 [DONE]
                // 这里不吐 Done —— 让 [DONE] 统一负责，避免上层收到两次结束。
                // 【2026-10-06】但要把 finish_reason **记下来**，等 [DONE] 时带上
                // （原来直接丢弃 → AgentLoop 无法识别 length 截断）。
                lastFinishReason = finish
            }
        }
        return out
    }

    private fun parseOpenAiUsage(u: JsonObject): ApiTypes.StreamEvent.Usage = ApiTypes.StreamEvent.Usage(
        inputTokens = (u["prompt_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
        outputTokens = (u["completion_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
    )

    // ═════════════════════════ Anthropic ═════════════════════════

    private fun parseAnthropic(root: JsonObject): List<ApiTypes.StreamEvent> {
        val out = mutableListOf<ApiTypes.StreamEvent>()
        val type = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return out

        when (type) {
            "content_block_delta" -> {
                val delta = root["delta"] as? JsonObject ?: return out
                val deltaType = (delta["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                when (deltaType) {
                    "text_delta" -> {
                        val t = (delta["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (!t.isNullOrEmpty()) out += ApiTypes.StreamEvent.Text(t)
                    }
                    "thinking_delta" -> {
                        val t = (delta["thinking"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        if (!t.isNullOrEmpty()) out += ApiTypes.StreamEvent.Reasoning(t)
                    }
                    "input_json_delta" -> {
                        // 工具参数增量。Anthropic 不带 index，用 content_block 的 index
                        val idx = (root["index"] as? JsonPrimitive)?.intOrNull ?: 0
                        val partial = (delta["partial_json"] as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content.orEmpty()
                        val acc = toolCallAccum.getOrPut(idx) { ToolCallAccum() }
                        if (partial.isNotEmpty()) acc.args.append(partial)
                        out += ApiTypes.StreamEvent.ToolCallDelta(
                            index = idx, id = null, name = null, argumentsDelta = partial,
                        )
                    }
                }
            }

            "content_block_start" -> {
                // 工具调用开始时给 id + name
                val idx = (root["index"] as? JsonPrimitive)?.intOrNull ?: 0
                val block = root["content_block"] as? JsonObject ?: return out
                val blockType = (block["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (blockType == "tool_use") {
                    val id = (block["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val name = (block["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val acc = toolCallAccum.getOrPut(idx) { ToolCallAccum() }
                    if (!id.isNullOrEmpty()) acc.id = id
                    if (!name.isNullOrEmpty()) acc.name = name
                    out += ApiTypes.StreamEvent.ToolCallDelta(
                        index = idx, id = id, name = name, argumentsDelta = "",
                    )
                }
            }

            "message_delta" -> {
                // 这里的 usage 是最终值（output_tokens）
                // 【2026-10-06】也带 stop_reason（anthropic 的结束原因在这里）
                (root["delta"] as? JsonObject)?.let { d ->
                    (d["stop_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
                        lastFinishReason = it
                    }
                }
                val usage = root["usage"] as? JsonObject
                if (usage != null) {
                    out += ApiTypes.StreamEvent.Usage(
                        inputTokens = (usage["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                        outputTokens = (usage["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                        cacheReadTokens = (usage["cache_read_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                        cacheWriteTokens = (usage["cache_creation_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                    )
                }
            }

            "message_start" -> {
                // 开头的 usage（input_tokens 在这里）
                val msg = root["message"] as? JsonObject
                val usage = msg?.get("usage") as? JsonObject
                if (usage != null) {
                    out += ApiTypes.StreamEvent.Usage(
                        inputTokens = (usage["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                        outputTokens = (usage["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                        cacheReadTokens = (usage["cache_read_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                        cacheWriteTokens = (usage["cache_creation_input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                    )
                }
            }

            "message_stop" -> out += ApiTypes.StreamEvent.Done(lastFinishReason)

            "error" -> {
                val err = root["error"] as? JsonObject
                val msg = (err?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: "unknown anthropic stream error"
                out += ApiTypes.StreamEvent.ParseError(Protocol.toConfigString(protocol), msg, "")
            }

            // ping / content_block_stop 等：正常心跳，忽略
        }
        return out
    }

    // ═════════════════════════ Responses ═════════════════════════

    private fun parseResponses(root: JsonObject): List<ApiTypes.StreamEvent> {
        val out = mutableListOf<ApiTypes.StreamEvent>()
        val type = (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return out

        when (type) {
            "response.output_text.delta" ->
                (root["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { out += ApiTypes.StreamEvent.Text(it) }

            "response.reasoning_summary_text.delta" ->
                (root["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { out += ApiTypes.StreamEvent.Reasoning(it) }

            "response.function_call_arguments.delta" -> {
                val idx = (root["output_index"] as? JsonPrimitive)?.intOrNull ?: 0
                val delta = (root["delta"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                val acc = toolCallAccum.getOrPut(idx) { ToolCallAccum() }
                if (delta.isNotEmpty()) acc.args.append(delta)
                out += ApiTypes.StreamEvent.ToolCallDelta(idx, null, null, delta)
            }

            // 工具调用开始时给 name/arguments（arguments 可能为空）
            "response.output_item.added" -> {
                val idx = (root["output_index"] as? JsonPrimitive)?.intOrNull ?: 0
                val item = root["item"] as? JsonObject
                if (item != null &&
                    (item["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "function_call"
                ) {
                    val callId = (item["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val name = (item["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    val acc = toolCallAccum.getOrPut(idx) { ToolCallAccum() }
                    if (!callId.isNullOrEmpty()) acc.id = callId
                    if (!name.isNullOrEmpty()) acc.name = name
                    // 有些网关把完整 arguments 也放在这里
                    (item["arguments"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { if (acc.args.isEmpty()) acc.args.append(it) }
                }
            }

            "response.completed", "response.done" -> {
                // 最终 usage 在 response.usage
                val resp = root["response"] as? JsonObject
                val usage = resp?.get("usage") as? JsonObject
                if (usage != null) {
                    out += ApiTypes.StreamEvent.Usage(
                        inputTokens = (usage["input_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                        outputTokens = (usage["output_tokens"] as? JsonPrimitive)?.intOrNull ?: 0,
                    )
                }
                out += ApiTypes.StreamEvent.Done()
            }

            "response.failed", "error" -> {
                val err = (root["error"] as? JsonObject)
                    ?: ((root["response"] as? JsonObject)?.get("error") as? JsonObject)
                val msg = (err?.get("message") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: "unknown responses stream error"
                out += ApiTypes.StreamEvent.ParseError(Protocol.toConfigString(protocol), msg, "")
            }
        }
        return out
    }

    companion object {
        /** 宽松 JSON：忽略未知字段、容忍轻微格式问题。 */
        val DEFAULT_JSON: Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }
    }
}
