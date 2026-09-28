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
    /**
     * 深度思考强度（none/minimal/low/medium/high/xhigh/max）。
     * audit-core #2：AppConfig.effort 有两处写入、core 层零消费 ——
     * 设置页的「扩展思考」开关是假的。这里接进 openai 协议请求体。
     * anthropic 协议的 thinking budget 映射未做（当前主力是 openai 兼容）。
     */
    private val effort: String? = null,
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
    ): Flow<ApiTypes.StreamEvent> = flow {
        val body = buildRequestBody(system, messages, tools, stream = true)

        // ── 重试循环（**重试只在这一层**，见类注释规则 1） ──
        //
        // 【为什么流式也必须重试】早期实现只发一次、失败就往上抛一个
        // `retriesExhausted = true` 的异常，但**它一次都没重试过** —— 于是：
        //   ① 池里另外几个 key 永远轮不到（Node 版踩过的「池形同虚设」原样复现）
        //   ② 谎报「下层已重试穷尽」，Agent 层据此不再重试 → 一次 429/503 直接失败
        // 现在流式与非流式走同一套语义：可重试错误 → 换 key + 退避 → 再发；
        // 只有**真正重试耗尽**（或错误不可重试）才打 `retriesExhausted = true`。
        var lastError: ApiTypes.ApiException? = null
        var emittedAny = false

        for (attempt in 0 until maxRetries) {
            val parser = StreamParser(protocol)
            lastParser = parser

            var failure: ApiTypes.ApiException? = null
            try {
                // 每次重试都重新构造请求 —— key 可能在上一轮被 rotate 换掉
                streamOnce(body, parser).collect { ev ->
                    // 只有「已经吐给用户看过」的内容才阻止重试。
                    // Usage / Done / ParseError 不算 —— 它们不产生可见输出，
                    // 重发不会让用户看到内容闪回。
                    if (ev is ApiTypes.StreamEvent.Text ||
                        ev is ApiTypes.StreamEvent.Reasoning ||
                        ev is ApiTypes.StreamEvent.ToolCallDelta
                    ) {
                        emittedAny = true
                    }
                    emit(ev)
                }
            } catch (e: ApiTypes.ApiException) {
                failure = e
            }

            if (failure == null) return@flow   // 这一轮正常跑完了

            lastError = failure

            // 换 key：401/403/429 是「这个 key 不行」，换一个可能就成功。
            // 这是 key 池存在的意义 —— 不能像早期实现那样整条绕过。
            if (shouldRotateKey(failure.statusCode)) {
                val next = keyPool.rotate("HTTP ${failure.statusCode}")
                if (next != null) onKeySwitch?.invoke(next)
            }

            val canRetry = failure.retryable &&
                attempt < maxRetries - 1 &&
                // 已经吐过正文就**不能**重试：重发会让 UI 看到内容从头再来一遍
                !emittedAny
            if (!canRetry) break

            val backoff = backoffMs(attempt)
            onRetry?.invoke(AttemptInfo(attempt + 1, maxRetries, failure.message, backoff))
            kotlinx.coroutines.delay(backoff)
        }

        val err = lastError ?: ApiTypes.ApiException("未知错误")
        // 重试耗尽（或不可重试）—— 打标记，上层绝不能再重试
        throw ApiTypes.ApiException(
            message = err.message,
            statusCode = err.statusCode,
            retryable = err.retryable,
            retriesExhausted = true,
            cause = err,
        )
    }

    /**
     * 单次流式尝试 —— 发一次请求，把 SSE 事件原样吐出来。
     *
     * 失败时以 [ApiTypes.ApiException] 结束这个 Flow（**不在这里重试**，
     * 重试由 [stream] 统一管，保证「重试只在一层」）。
     *
     * ⚠️ **读循环委托给 [SseReader.streamSse]，不要在这里再手写一份**。
     * 早期实现自己抄了一遍 readUtf8Line 循环，代价是 SseReader 那份带
     * 看门狗的实现成了死代码、**这条真正在跑的路径反而没有超时保护** ——
     * 网关挂起（TCP 连上却永不发数据）时 socket 永久阻塞，用户看到「一直转圈，
     * 连超时都不报」。重复实现必然漂移，统一到一处。
     */
    private fun streamOnce(
        body: JsonObject,
        parser: StreamParser,
    ): Flow<ApiTypes.StreamEvent> = flow {
        val request = buildRequest(body, stream = true)
        try {
            SseReader.streamSse(
                request = request,
                client = streamClient,
                onCall = { activeCall = it },
                onHttpError = { code, errBody ->
                    // 能力探测：400 里提到 image/vision → 记下该端点不支持图片
                    if (code == 400 && looksLikeVisionError(errBody)) markVisionUnsupported()
                },
            ).collect { payload ->
                for (ev in parser.parse(payload)) emit(ev)
            }
        } finally {
            // 成功、失败、取消三条路径都要清 —— 否则 cancelActiveStream()
            // 会去 cancel 一个早就结束的 call，真正在跑的那条取消不掉
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

        // ⚠️ 会话历史是**协议无关的中间表示**，形态取自 Anthropic
        // （assistant 里放 `tool_use` 块、结果放 user 里的 `tool_result` 块）。
        // 每种协议在**这里**转成自己的线格式 —— 这是全项目唯一的转换点。
        //
        // 【为什么必须有这一步】早期实现直接把中间表示塞进三种协议，后果是
        // **OpenAI 协议（绝大多数中转站、也是默认值）下工具调用完全失效**：
        // OpenAI 要求 assistant 用 `tool_calls` 字段、结果用独立的
        // `{"role":"tool","tool_call_id":...}` 消息，收到 `tool_use` 块时
        // 要么报 400，要么静默忽略 —— 表现为「模型永远不调用工具，只会说话」。
        return when (protocol) {
            Protocol.ANTHROPIC -> buildAnthropicBody(system, toAnthropicMessages(messages), effectiveTools, stream, maxTok)
            Protocol.RESPONSES -> buildResponsesBody(system, toResponsesInput(messages), effectiveTools, stream, maxTok)
            Protocol.OPENAI -> buildOpenAiBody(system, toOpenAiMessages(messages), effectiveTools, stream, maxTok)
        }
    }

    // ───────────── 中间表示 → Anthropic 线格式 ─────────────

    /**
     * 把中间表示转成 Anthropic 的 `messages` 数组。
     *
     * 中间表示的**骨架本来就取自 Anthropic**（assistant 放 `tool_use` 块、
     * 结果放 user 的 `tool_result` 块），所以这里只需修一处真实差异：
     *
     * **图片**：中间表示是 OpenAI 形态 `{type:"image_url", image_url:{url:"data:..."}}`，
     * 而 Anthropic 要 `{type:"image", source:{type:"base64", media_type, data}}`。
     * 直接透传会 400（`image_url: Extra inputs are not permitted`），
     * 且因为只有带图的消息才触发，属于「平时好好的，一发图就挂」的隐蔽故障。
     *
     * 其余块（text / tool_use / tool_result）结构一致，原样透传。
     */
    private fun toAnthropicMessages(messages: List<JsonObject>): List<JsonObject> =
        messages.map { m ->
            val contentEl = m["content"]
            // 纯文本简写形态：Anthropic 也接受 `content: "..."`，原样透传
            if (contentEl is JsonPrimitive) return@map m

            val blocks = contentEl as? kotlinx.serialization.json.JsonArray ?: return@map m

            buildJsonObject {
                m["role"]?.let { put("role", it) }
                put("content", buildJsonArray {
                    blocks.forEach { b ->
                        val o = b as? JsonObject ?: return@forEach
                        val url = if (prim(o["type"]) in setOf("image_url", "image")) imageUrlOf(o) else null
                        if (url != null) {
                            val (mediaType, data) = splitDataUrl(url)
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", mediaType)
                                    put("data", data)
                                })
                            })
                        } else {
                            add(o)
                        }
                    }
                })
            }
        }

    /**
     * 拆 data URL 成 `(mediaType, base64数据)`。
     *
     * `data:image/png;base64,AAAA` → `("image/png", "AAAA")`。
     * 不是 data URL 时按「无媒体类型」处理（Anthropic 只接受 base64，给个兜底类型）。
     */
    private fun splitDataUrl(url: String): Pair<String, String> {
        if (!url.startsWith("data:")) return "image/png" to url
        val comma = url.indexOf(',')
        if (comma < 0) return "image/png" to url
        val header = url.substring(5, comma)          // "image/png;base64"
        val data = url.substring(comma + 1)
        val mediaType = header.substringBefore(';').ifEmpty { "image/png" }
        return mediaType to data
    }

    // ───────────── 中间表示 → OpenAI 线格式 ─────────────

    /**
     * 把中间表示转成 OpenAI 的 `messages` 数组。
     *
     * 三处关键差异（漏一处工具就用不了）：
     * 1. assistant 的工具调用 → **`tool_calls` 字段**（不是 content 里的块），
     *    且 `arguments` 必须是**JSON 字符串**（不是对象）
     * 2. 工具结果 → **独立的 `{"role":"tool","tool_call_id":...}` 消息**，
     *    一条结果一条消息（不能塞进 user 的 content 数组）
     * 3. 图片格式恰好一致（`image_url.url`），可直接透传
     */
    private fun toOpenAiMessages(messages: List<JsonObject>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()

        for (m in messages) {
            val role = (m["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val contentEl = m["content"]

            // 纯文本简写形态：原样透传（最省 token，也是常规形态）
            if (contentEl is JsonPrimitive) {
                out += buildJsonObject {
                    put("role", role)
                    put("content", contentEl)
                }
                continue
            }

            val blocks = contentEl as? kotlinx.serialization.json.JsonArray ?: continue

            // ── 工具结果 → 拆成独立的 role:"tool" 消息 ──
            val toolResults = blocks.filter { b ->
                (b as? JsonObject)?.get("type")?.let { prim(it) } == "tool_result"
            }
            if (toolResults.isNotEmpty()) {
                for (tr in toolResults) {
                    val o = tr as? JsonObject ?: continue
                    out += buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", prim(o["tool_use_id"]))
                        // 失败的也要有内容 —— OpenAI 不接受空 content
                        val body = prim(o["content"])
                        put("content", body.ifEmpty { "(空结果)" })
                    }
                }
                continue
            }

            // ── assistant 的工具调用 → tool_calls 字段 ──
            val toolUses = blocks.filter { b ->
                (b as? JsonObject)?.get("type")?.let { prim(it) } == "tool_use"
            }
            if (toolUses.isNotEmpty()) {
                val text = blocks.joinToString("") { b ->
                    val o = b as? JsonObject ?: return@joinToString ""
                    if (prim(o["type"]) == "text") prim(o["text"]) else ""
                }
                out += buildJsonObject {
                    put("role", role)
                    // 有 tool_calls 时 content 可以为空串，但不能缺字段
                    put("content", text)
                    put("tool_calls", buildJsonArray {
                        toolUses.forEach { tu ->
                            val o = tu as? JsonObject ?: return@forEach
                            add(buildJsonObject {
                                put("id", prim(o["id"]))
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", prim(o["name"]))
                                    // ⚠️ 必须是**字符串**，传对象会被拒
                                    put("arguments", o["input"]?.toString() ?: "{}")
                                })
                            })
                        }
                    })
                }
                continue
            }

            // ── 其余（文本 + 图片）→ 数组形态 ──
            out += buildJsonObject {
                put("role", role)
                put("content", buildJsonArray {
                    blocks.forEach { b ->
                        val o = b as? JsonObject ?: return@forEach
                        when (prim(o["type"])) {
                            "text" -> add(buildJsonObject {
                                put("type", "text")
                                put("text", prim(o["text"]))
                            })
                            // ⚠️ 中间表示里图片块的 type 就是 `image_url`，
                            // 且 image_url 是 {url:...} 对象（对齐 OpenAI 线格式）。
                            // 两种形态都认，避免以后中间表示改成 `image` 时静默丢图。
                            "image_url", "image" -> {
                                val url = imageUrlOf(o) ?: return@forEach
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", url)
                                    })
                                })
                            }
                            else -> Unit   // 未知块丢弃，不要原样透传（会 400）
                        }
                    }
                })
            }
        }

        return out
    }

    /**
     * 从内容块里取图片 URL。
     *
     * 兼容两种形态：`image_url: {url: "..."}`（OpenAI 风格，当前中间表示用的）
     * 和 `image_url: "..."` / `url: "..."`（扁平写法）。
     */
    private fun imageUrlOf(block: JsonObject): String? {
        val raw = block["image_url"] ?: block["url"] ?: return null
        return when (raw) {
            is JsonPrimitive -> raw.content
            is JsonObject -> (raw["url"] as? JsonPrimitive)?.content
            else -> null
        }?.takeIf { it.isNotEmpty() }
    }

    // ───────────── 中间表示 → Responses 线格式 ─────────────

    /**
     * 把中间表示转成 Responses 的 `input` 数组。
     *
     * Responses 的 input 是**扁平事件流**，与 chat 的 messages 结构不同：
     * - 文本消息 → `{type:"message", role, content:[{type:"input_text"|"output_text", text}]}`
     * - 工具调用 → `{type:"function_call", call_id, name, arguments}`
     * - 工具结果 → `{type:"function_call_output", call_id, output}`
     * - 图片 → `{type:"input_image", image_url}`
     */
    private fun toResponsesInput(messages: List<JsonObject>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()

        for (m in messages) {
            val role = (m["role"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            val contentEl = m["content"]

            if (contentEl is JsonPrimitive) {
                val text = contentEl.takeIf { it.isString }?.content.orEmpty()
                if (text.isNotEmpty()) out += responsesTextMessage(role, text)
                continue
            }

            val blocks = contentEl as? kotlinx.serialization.json.JsonArray ?: continue

            // 工具调用 / 结果先展开成扁平事件，文本与图片合成 message
            val textSb = StringBuilder()
            val parts = mutableListOf<JsonObject>()

            blocks.forEach { b ->
                val o = b as? JsonObject ?: return@forEach
                when (prim(o["type"])) {
                    "text" -> textSb.append(prim(o["text"]))
                    "image_url", "image" -> imageUrlOf(o)?.let { url ->
                        parts += buildJsonObject {
                            put("type", "input_image")
                            put("image_url", url)
                        }
                    }
                    "tool_use" -> out += buildJsonObject {
                        put("type", "function_call")
                        put("call_id", prim(o["id"]))
                        put("name", prim(o["name"]))
                        put("arguments", o["input"]?.toString() ?: "{}")
                    }
                    "tool_result" -> out += buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", prim(o["tool_use_id"]))
                        put("output", prim(o["content"]).ifEmpty { "(空结果)" })
                    }
                }
            }

            if (textSb.isNotEmpty() || parts.isNotEmpty()) {
                out += buildJsonObject {
                    put("type", "message")
                    put("role", role)
                    put("content", buildJsonArray {
                        if (textSb.isNotEmpty()) {
                            add(buildJsonObject {
                                put("type", if (role == "assistant") "output_text" else "input_text")
                                put("text", textSb.toString())
                            })
                        }
                        parts.forEach { add(it) }
                    })
                }
            }
        }

        return out
    }

    private fun responsesTextMessage(role: String, text: String): JsonObject = buildJsonObject {
        put("type", "message")
        put("role", role)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", if (role == "assistant") "output_text" else "input_text")
                put("text", text)
            })
        })
    }

    /** 安全取字符串（非字符串/缺失 → 空串）。 */
    private fun prim(el: kotlinx.serialization.json.JsonElement?): String =
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

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
        // 深度思考（audit-core #2）：none 不发（等价默认关）；
        // xhigh/max 是 CLI 扩展档，OpenAI 官方只认到 high → 归一到 high。
        effort?.takeIf { it.isNotBlank() && it != "none" }?.let { e ->
            put("reasoning_effort", if (e in setOf("minimal", "low", "medium", "high")) e else "high")
        }
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
