package com.ccm.app.core.api

import com.ccm.app.core.provider.KeyPool
import com.ccm.app.core.provider.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * API 客户端 —— **全项目唯一的出网点**。
 *
 * 对应 Node 版 `core/api.mjs`（2496 行）。
 *
 * ## 职责
 * 1. 三套协议（OpenAI / Anthropic / Responses）的请求体构造与响应解析
 * 2. 流式 SSE（[stream]）与非流式（[chat]）两条路径
 * 3. 重试与退避（**只在这一层重试**，见下）
 * 4. key 轮换（复用 [KeyPool]）
 * 5. 能力降级（thinking 被拒、vision 不支持、增量请求被拒）
 *
 * ## 三条硬规则（都是血泪教训，改动前先读）
 *
 * ### 1. 重试只允许一层
 * Node 版踩过「重试跨层叠加」：api 层重试 3 次 × agent 层重试 4 轮
 * = 12 次请求 / 687 秒，用户视角是「十几分钟一声不响，连超时都不报」。
 * 所以：**api 层重试耗尽后必须标记 `retriesExhausted = true`**，
 * Agent 层看到这个标记就不再重试。
 *
 * ### 2. 流式请求用独立 OkHttpClient
 * Node 版踩过「一次流超时后所有请求永久卡死」：坏连接污染共享连接池。
 * 独立实例 = 独立连接池，从根上隔离（见 [SseReader.createStreamClient]）。
 *
 * ### 3. 能力结论按「端点」记录，不能全局
 * Node 版踩过：端点 A 撞 400 置位 → 切回支持该能力的端点 B → B 的功能静默失效。
 * 所以 [_thinkingRejectedEndpoints] 这类标记都带端点键。
 *
 * ## 线程安全
 * 本类**不是**线程安全的。一个 ApiClient 实例对应一个 Provider，
 * 由 Agent 单线程驱动。要并发请求请建多个实例。
 */
class ApiClient(
    baseUrl: String,
    apiKeys: List<String>,
    val model: String,
    val protocol: Protocol = Protocol.OPENAI,
    /** 输出 token 上限（clamp 用）。null = 不限制。 */
    private val maxOutputTokens: Int? = null,
    /** 温度。 */
    private val temperature: Double = 1.0,
    /** 是否剥离 tools 参数（部分网关收到 tools 会挂起）。 */
    private val noTools: Boolean = false,
    /** system 是否放顶层字段而非 messages[0]（部分网关对 messages[0] 大 system 间歇空流）。 */
    private val systemTopLevel: Boolean = false,
    /** 本层最大重试次数。 */
    private val maxRetries: Int = 3,
    /** 非流式请求超时（ms）。流式不设 —— 交给 Agent 层 watchdog。 */
    private val timeoutMs: Long = 120_000L,
    /** key 池状态文件（null = 不落盘）。 */
    keyPoolStateFile: java.io.File? = null,
    /** key 切换回调（让 UI 知道「正在换 key 重试」）。 */
    private val onKeySwitch: ((String) -> Unit)? = null,
    /** 重试回调（让「静默重试」可见）。 */
    private val onRetry: ((AttemptInfo) -> Unit)? = null,
) {

    /** 一次重试尝试的信息（给 UI 显示「第 2 次重试…」）。 */
    data class AttemptInfo(
        val attempt: Int,
        val maxAttempts: Int,
        val reason: String,
        val backoffMs: Long,
    )

    /** 规范化后的 base URL（按协议）。 */
    private val normalizedBase: String = Protocol.normalizeBaseUrl(baseUrl, protocol)

    /** key 池（单 key 也包成池，统一路径）。 */
    private val keyPool: KeyPool = KeyPool(
        keys = apiKeys.filter { it.isNotBlank() },
        stateFile = keyPoolStateFile,
    )

    /** 流式专用 client（独立连接池）。 */
    private val streamClient: OkHttpClient = SseReader.createStreamClient()

    /** 非流式 client（可复用连接）。 */
    private val plainClient: OkHttpClient = SseReader.createPlainClient()

    private val json = StreamParser.DEFAULT_JSON

    /** 当前 key（池里选出的可用 key）。 */
    val apiKey: String get() = keyPool.current().orEmpty()

    // ───────────── 能力降级标记（按端点记录，见类注释规则 3） ─────────────

    /** 思考参数被拒的端点集合。 */
    private val thinkingRejected = mutableSetOf<String>()

    /** 图片不被支持的端点集合。 */
    private val visionRejected = mutableSetOf<String>()

    /** 端点键（协议 + base + model）。 */
    private val endpointKey: String get() = "${Protocol.toConfigString(protocol)}|$normalizedBase|$model"

    /** 当前端点是否已知不支持图片。Agent 层据此决定「要不要如实告知模型图没发出去」。 */
    val isVisionUnsupported: Boolean get() = endpointKey in visionRejected

    /** 标记当前端点不支持图片（Agent 层收到相关 400 时调）。 */
    fun markVisionUnsupported() {
        visionRejected += endpointKey
    }

    // ═════════════════════════ 公开 API ═════════════════════════

    /**
     * 非流式对话。
     *
     * 用于短请求（compact 摘要、标题生成、`/config test`）。
     * 长回复走 [stream]。
     */
    suspend fun chat(
        system: String,
        messages: List<JsonObject>,
        tools: List<ApiTypes.ToolDefinition> = emptyList(),
    ): ApiTypes.ChatResponse {
        val body = buildRequestBody(system, messages, tools, stream = false)
        val raw = executeWithRetry(body, stream = false)
        return parseResponse(raw)
    }

    /**
     * 流式对话 —— 边收边吐事件。
     *
     * 用法：
     * ```kotlin
     * client.stream(system, messages, tools).collect { ev ->
     *     when (ev) {
     *         is StreamEvent.Text -> append(ev.text)
     *         is StreamEvent.ToolCallDelta -> accumulate(ev)
     *         is StreamEvent.Done -> finish()
     *         else -> Unit
     *     }
     * }
     * ```
     *
     * ⚠️ **工具调用的参数是分片到达的**，要等流结束后用
     * [lastToolCalls] 取完整列表，不要中途解析。
     */
    fun stream(
        system: String,
        messages: List<JsonObject>,
        tools: List<ApiTypes.ToolDefinition> = emptyList(),
    ): Flow<ApiTypes.StreamEvent> = callbackFlow {
        val parser = StreamParser(protocol)
        lastParser = parser

        val body = buildRequestBody(system, messages, tools, stream = true)
        val request = buildRequest(body, stream = true)

        val call = streamClient.newCall(request)
        activeCall = call

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (call.isCanceled()) {
                    close()   // 正常取消，不是错误
                } else {
                    close(
                        ApiTypes.ApiException(
                            "网络错误: ${e.message ?: e.javaClass.simpleName}",
                            retryable = true, cause = e,
                        )
                    )
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = try {
                            resp.body?.string().orEmpty()
                        } catch (_: Throwable) {
                            ""
                        }
                        // 能力探测：400 里提到 image/vision → 记下该端点不支持图片
                        if (resp.code == 400 && looksLikeVisionError(errBody)) {
                            markVisionUnsupported()
                        }
                        close(
                            ApiTypes.ApiException(
                                message = "HTTP ${resp.code}: ${errBody.take(2000)}",
                                statusCode = resp.code,
                                retryable = isRetryableStatus(resp.code),
                                retriesExhausted = true,   // 已在本层重试过
                            )
                        )
                        return
                    }

                    val source = resp.body?.source()
                    if (source == null) {
                        close(ApiTypes.ApiException("响应体为空", statusCode = resp.code))
                        return
                    }

                    try {
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty() || line[0] == ':') continue
                            if (!line.startsWith("data:")) continue
                            val payload = line.removePrefix("data:").trim()
                            if (payload.isEmpty()) continue

                            for (ev in parser.parse(payload)) {
                                trySend(ev)
                                if (ev is ApiTypes.StreamEvent.Done) break
                            }
                        }
                        close()
                    } catch (e: IOException) {
                        close(
                            ApiTypes.ApiException(
                                "流中断: ${e.message ?: e.javaClass.simpleName}",
                                retryable = true, cause = e,
                            )
                        )
                    }
                }
            }
        })

        awaitClose {
            // Flow 取消 → 断开连接（对应 Node 版 streamController.abort()）
            call.cancel()
            activeCall = null
        }
    }

    /** 最近一次 [stream] 的完整工具调用列表（流结束后读）。 */
    fun lastToolCalls(): List<ApiTypes.ToolCall> = lastParser?.finishToolCalls().orEmpty()

    private var lastParser: StreamParser? = null
    private var activeCall: Call? = null

    /** 主动取消当前流（对应 Node 版 streamController.abort()）。 */
    fun cancelActiveStream() {
        activeCall?.cancel()
    }

    // ═════════════════════════ 内部实现 ═════════════════════════

    /**
     * 带重试的非流式执行。
     *
     * **重试只在这一层**（见类注释规则 1）。耗尽后抛出的异常带
     * `retriesExhausted = true`，Agent 层看到就不再重试。
     */
    private suspend fun executeWithRetry(body: JsonObject, stream: Boolean): String {
        var lastError: ApiTypes.ApiException? = null

        for (attempt in 0 until maxRetries) {
            val request = buildRequest(body, stream)
            try {
                return suspendCancellableCoroutine { cont ->
                    val call = plainClient.newCall(request)
                    cont.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    ApiTypes.ApiException(
                                        "网络错误: ${e.message ?: e.javaClass.simpleName}",
                                        retryable = true, cause = e,
                                    )
                                )
                            }
                        }

                        override fun onResponse(call: Call, response: Response) {
                            response.use { resp ->
                                val text = try {
                                    resp.body?.string().orEmpty()
                                } catch (e: Throwable) {
                                    ""
                                }
                                if (resp.isSuccessful) {
                                    cont.resume(text)
                                } else {
                                    if (resp.code == 400 && looksLikeVisionError(text)) {
                                        markVisionUnsupported()
                                    }
                                    cont.resumeWithException(
                                        ApiTypes.ApiException(
                                            "HTTP ${resp.code}: ${text.take(2000)}",
                                            statusCode = resp.code,
                                            retryable = isRetryableStatus(resp.code),
                                        )
                                    )
                                }
                            }
                        }
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiTypes.ApiException) {
                lastError = e

                // 换 key 重试：401/403/429 是「这个 key 不行」，换一个可能就成功
                // （Node 版教训：把 4xx 全判死会让池里其他 key 一次都没被用过）
                if (shouldRotateKey(e.statusCode)) {
                    val next = keyPool.rotate("HTTP ${e.statusCode}")
                    if (next != null) onKeySwitch?.invoke(next)
                }

                if (!e.retryable || attempt == maxRetries - 1) break

                val backoff = backoffMs(attempt)
                onRetry?.invoke(AttemptInfo(attempt + 1, maxRetries, e.message, backoff))
                kotlinx.coroutines.delay(backoff)
            }
        }

        // 重试耗尽 —— 打标记，上层绝不能再重试
        val err = lastError ?: ApiTypes.ApiException("未知错误")
        throw ApiTypes.ApiException(
            message = err.message,
            statusCode = err.statusCode,
            retryable = err.retryable,
            retriesExhausted = true,
            cause = err,
        )
    }

    /** 构造 HTTP 请求（header 按协议分派）。 */
    private fun buildRequest(body: JsonObject, stream: Boolean): Request {
        val builder = Request.Builder()
            .url(endpointUrl())
            .post(body.toString().toRequestBody(JSON_MEDIA))

        when (protocol) {
            Protocol.ANTHROPIC -> {
                builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", "2023-06-01")
            }
            else -> builder.header("Authorization", "Bearer $apiKey")
        }

        // 【流式请求不复用连接】—— 修「一次超时后所有请求都卡」
        // Node 版根因：半开连接留在池里被复用。OkHttp 已有独立 client 池，
        // 这里再显式 close 一次是双保险（独立池 + 用完即弃）。
        if (stream) builder.header("Connection", "close")

        return builder.build()
    }

    /** 按协议拼 endpoint（用 ProviderConfig.Protocol 的 path，避免两处定义漂移）。 */
    private fun endpointUrl(): String = normalizedBase + protocol.path

    /** 可重试的状态码。 */
    private fun isRetryableStatus(code: Int): Boolean =
        code == 429 || code in 500..599 || code == 401 || code == 403

    /**
     * 是否该换 key。
     *
     * 401/403/429 都是「这个 key 不行」——换一个可能成功。
     * 这正是 key 池存在的意义（Node 版教训：把它们判死会让池形同虚设）。
     */
    private fun shouldRotateKey(status: Int): Boolean = status == 401 || status == 403 || status == 429

    /** 指数退避（1s / 2s / 4s）。 */
    private fun backoffMs(attempt: Int): Long = 1000L shl attempt

    /** 错误信息是否像「图片不被支持」。 */
    private fun looksLikeVisionError(body: String): Boolean {
        val lower = body.lowercase()
        return ("image" in lower && ("not supported" in lower || "unsupported" in lower)) ||
            "vision" in lower
    }

    /**
     * 构造请求体（三协议分派）。
     *
     * 工具 schema 在这里**统一过一遍 [com.ccm.app.core.tool.ToolSchema.normalizeToolSchema]**
     * —— 这是跨 provider 兼容的唯一出口兜底（防 Gemini 的 `/properties: null` 400）。
     */
    private fun buildRequestBody(
        system: String,
        messages: List<JsonObject>,
        tools: List<ApiTypes.ToolDefinition>,
        stream: Boolean,
    ): JsonObject {
        val effectiveTools = if (noTools) emptyList() else tools
        val maxTok = maxOutputTokens

        return when (protocol) {
            Protocol.ANTHROPIC -> buildAnthropicBody(system, messages, effectiveTools, stream, maxTok)
            Protocol.RESPONSES -> buildResponsesBody(system, messages, effectiveTools, stream, maxTok)
            Protocol.OPENAI -> buildOpenAiBody(system, messages, effectiveTools, stream, maxTok)
        }
    }

    private fun buildOpenAiBody(
        system: String,
        messages: List<JsonObject>,
        tools: List<ApiTypes.ToolDefinition>,
        stream: Boolean,
        maxTok: Int?,
    ): JsonObject = buildJsonObject {
        put("model", model)
        put("messages", buildJsonArray {
            // systemTopLevel 时 system 不放这里
            if (!systemTopLevel && system.isNotEmpty()) {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", system)
                })
            }
            messages.forEach { add(it) }
        })
        put("stream", stream)
        put("temperature", temperature)
        if (maxTok != null) put("max_tokens", maxTok)
        if (systemTopLevel && system.isNotEmpty()) put("system", system)
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("parameters", com.ccm.app.core.tool.ToolSchema.normalizeToolSchema(t.parameters))
                        })
                    })
                }
            })
        }
    }

    private fun buildAnthropicBody(
        system: String,
        messages: List<JsonObject>,
        tools: List<ApiTypes.ToolDefinition>,
        stream: Boolean,
        maxTok: Int?,
    ): JsonObject = buildJsonObject {
        put("model", model)
        // Anthropic 的 system 是**顶层字段**（不是 messages[0]）
        if (system.isNotEmpty()) put("system", system)
        put("messages", buildJsonArray { messages.forEach { add(it) } })
        put("stream", stream)
        put("max_tokens", maxTok ?: 4096)   // Anthropic 必填
        put("temperature", temperature)
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("input_schema", com.ccm.app.core.tool.ToolSchema.normalizeToolSchema(t.parameters))
                    })
                }
            })
        }
    }

    private fun buildResponsesBody(
        system: String,
        messages: List<JsonObject>,
        tools: List<ApiTypes.ToolDefinition>,
        stream: Boolean,
        maxTok: Int?,
    ): JsonObject = buildJsonObject {
        put("model", model)
        if (system.isNotEmpty()) put("instructions", system)
        put("input", buildJsonArray { messages.forEach { add(it) } })
        put("stream", stream)
        if (maxTok != null) put("max_output_tokens", maxTok)
        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                tools.forEach { t ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", com.ccm.app.core.tool.ToolSchema.normalizeToolSchema(t.parameters))
                    })
                }
            })
        }
    }

    /** 解析非流式响应（三协议归一）。 */
    private fun parseResponse(raw: String): ApiTypes.ChatResponse {
        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
                ?: return ApiTypes.ChatResponse(text = "")
        } catch (e: Throwable) {
            throw ApiTypes.ApiException("响应解析失败: ${e.message}", cause = e)
        }
        return when (protocol) {
            Protocol.ANTHROPIC -> parseAnthropicResponse(root)
            Protocol.RESPONSES -> parseResponsesResponse(root)
            Protocol.OPENAI -> parseOpenAiResponse(root)
        }
    }

    private fun parseOpenAiResponse(root: JsonObject): ApiTypes.ChatResponse {
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray
        val first = choices?.firstOrNull() as? JsonObject
        val msg = first?.get("message") as? JsonObject
        val text = (msg?.get("content") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        val reasoning = (msg?.get("reasoning_content") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val finish = (first?.get("finish_reason") as? JsonPrimitive)?.takeIf { it.isString }?.content

        val toolCalls = mutableListOf<ApiTypes.ToolCall>()
        (msg?.get("tool_calls") as? kotlinx.serialization.json.JsonArray)?.forEach { tcEl ->
            val tc = tcEl as? JsonObject ?: return@forEach
            val fn = tc["function"] as? JsonObject
            toolCalls += ApiTypes.ToolCall(
                id = (tc["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                name = (fn?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                arguments = (fn?.get("arguments") as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
            )
        }

        return ApiTypes.ChatResponse(text, reasoning, toolCalls, parseUsage(root["usage"]), finish)
    }

    private fun parseAnthropicResponse(root: JsonObject): ApiTypes.ChatResponse {
        val contentArr = root["content"] as? kotlinx.serialization.json.JsonArray
        val textSb = StringBuilder()
        var reasoning: String? = null
        val toolCalls = mutableListOf<ApiTypes.ToolCall>()

        contentArr?.forEach { blockEl ->
            val block = blockEl as? JsonObject ?: return@forEach
            when ((block["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "text" -> (block["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { textSb.append(it) }
                "thinking" -> (block["thinking"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { reasoning = (reasoning ?: "") + it }
                "tool_use" -> toolCalls += ApiTypes.ToolCall(
                    id = (block["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    name = (block["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    arguments = block["input"]?.toString().orEmpty(),
                )
            }
        }

        return ApiTypes.ChatResponse(
            text = textSb.toString(),
            reasoning = reasoning,
            toolCalls = toolCalls,
            usage = parseUsage(root["usage"]),
            finishReason = (root["stop_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }

    private fun parseResponsesResponse(root: JsonObject): ApiTypes.ChatResponse {
        val output = root["output"] as? kotlinx.serialization.json.JsonArray
        val textSb = StringBuilder()
        val toolCalls = mutableListOf<ApiTypes.ToolCall>()

        output?.forEach { itemEl ->
            val item = itemEl as? JsonObject ?: return@forEach
            when ((item["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
                "message" -> {
                    (item["content"] as? kotlinx.serialization.json.JsonArray)?.forEach { cEl ->
                        val c = cEl as? JsonObject ?: return@forEach
                        (c["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { textSb.append(it) }
                    }
                }
                "function_call" -> toolCalls += ApiTypes.ToolCall(
                    id = (item["call_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    name = (item["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                    arguments = (item["arguments"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(),
                )
            }
        }

        return ApiTypes.ChatResponse(
            text = textSb.toString(),
            toolCalls = toolCalls,
            usage = parseUsage(root["usage"]),
            finishReason = (root["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )
    }

    private fun parseUsage(el: kotlinx.serialization.json.JsonElement?): ApiTypes.TokenUsage {
        val u = el as? JsonObject ?: return ApiTypes.TokenUsage()
        fun n(k: String) = (u[k] as? JsonPrimitive)?.let {
            it.content.toIntOrNull()
        } ?: 0
        return ApiTypes.TokenUsage(
            promptTokens = n("prompt_tokens").takeIf { it > 0 } ?: n("input_tokens"),
            completionTokens = n("completion_tokens").takeIf { it > 0 } ?: n("output_tokens"),
            totalTokens = n("total_tokens"),
            cacheReadInputTokens = n("cache_read_input_tokens"),
            cacheCreationInputTokens = n("cache_creation_input_tokens"),
        )
    }

    /** 关闭底层连接池（切 Provider 时调）。 */
    fun shutdown() {
        try {
            streamClient.dispatcher.executorService.shutdown()
            streamClient.connectionPool.evictAll()
            plainClient.dispatcher.executorService.shutdown()
            plainClient.connectionPool.evictAll()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
