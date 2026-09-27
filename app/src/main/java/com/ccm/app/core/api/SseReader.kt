package com.ccm.app.core.api

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SSE（Server-Sent Events）读取器。
 *
 * ## 职责边界
 * 本类**只负责**「从 HTTP 响应体里逐行读出 `data:` 负载」这一件事。
 * 解析成 [ApiTypes.StreamEvent] 是 [StreamParser] 的活，发请求是 [ApiClient] 的活。
 * 拆开是因为三套协议的 SSE 传输层**完全相同**（都是 `data: {...}\n\n`），
 * 只有负载结构不同 —— 传输逻辑只该写一遍。
 *
 * ## 为什么用 OkHttp 而不是 Ktor
 * Node 版踩过一个血泪 bug：「一次流超时后所有请求永久卡死」，根因是连接池被
 * 半开的坏连接污染，只能靠 `Connection: close` 绕开。OkHttp 可以给流式请求
 * **单独一个 client 实例**（独立连接池），从根上杜绝污染 —— 这是选它的首要原因。
 *
 * ## 取消语义（对应 Node 版的 AbortController）
 * [streamSse] 内部用 [callbackFlow]：collector 取消 → `awaitClose` → `call.cancel()`，
 * 连接立即断开。这对应 Node 版 `streamController.abort()`。
 */
object SseReader {

    /**
     * 流式静默超时（ms）—— 距上一个事件超过这个时间就判定「卡死」。
     *
     * 300s 对齐 Node 版 watchdog。**不能设更小**：实测首 token 最大 196.7s
     * （8 次超 60s），设 60s 会把「慢但能成功」的请求全误杀 ——
     * 这正是 Node 版 2026-09-01 那次「改坏体验」的根因。
     */
    const val STREAM_SILENCE_TIMEOUT_MS = 300_000L

    /** 看门狗轮询间隔（ms）。比超时小两个数量级，保证判定及时且不空转。 */
    private const val WATCHDOG_POLL_MS = 5_000L

    /** 共享的 JSON 解析器（宽松模式：忽略未知字段，容忍网关加料）。 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * 建一个**专供流式请求**的 OkHttpClient。
     *
     * ## 为什么必须独立实例（不是共用）
     * 独立连接池 = 流式请求的坏连接不会污染普通请求。
     * Node 版的教训：一个流超时后，**连 body 只有几十字节的请求也卡死**，
     * 因为坏连接留在共享池里被后续请求复用。OkHttp 的独立实例从根上隔离。
     *
     * ## 超时设计（对齐 Node 版的双层）
     * - `connectTimeout` = 60s：只管**建连**阶段，快速失败去重试/换 key
     * - `readTimeout` = 0（不限制）：**body 阶段由 watchdog 管**，不在这里设
     *
     * 为什么 readTimeout 给 0：Node 版实测过「首 token 中位 13.6s / p90 30.9s /
     * 最大 196.7s」，把 readTimeout 设成 60s 会把「慢但能成功」的请求全误杀。
     * 流式的看门狗（300s 无事件）由 Agent 层负责，那里能区分「慢」和「死」。
     *
     * @param connectTimeoutMs 建连超时
     */
    fun createStreamClient(connectTimeoutMs: Long = 60_000L): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)      // body 阶段不设限，交给 watchdog
            .writeTimeout(60_000L, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)            // 重试由上层统一管，防跨层叠加
            .build()

    /**
     * 建一个**普通请求**用的 OkHttpClient（非流式、body 小）。
     *
     * 这个可以复用连接（默认池），因为响应体小、读完就关，不会留半开连接。
     */
    fun createPlainClient(
        connectTimeoutMs: Long = 30_000L,
        readTimeoutMs: Long = 120_000L,
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /**
     * 发起请求并把 SSE 的 `data:` 负载逐条吐成 Flow。
     *
     * ## 行为
     * - 只吐**非空**的 `data:` 行负载（注释行 `:` 开头、空行、`event:` 行都跳过）
     * - 遇到 `[DONE]` 时**吐出来然后结束**（由上层决定语义 —— OpenAI 用 `[DONE]`
     *   标记结束，Anthropic 用 `message_stop` 事件，两者都要能被上层看到）
     * - HTTP 非 2xx：读完整 body（错误信息在里面）后抛 [ApiTypes.ApiException]
     *
     * ## 取消
     * Flow 被取消 → `call.cancel()` → 连接立即断开。
     * **不需要额外的 AbortController** —— 协程取消就是取消信号。
     *
     * @param request 已构造好的请求（含 header、body）
     * @param client 流式专用 client（见 [createStreamClient]）
     */
    fun streamSse(
        request: Request,
        client: OkHttpClient,
        onCall: (Call) -> Unit = {},
        onHttpError: ((Int, String) -> Unit)? = null,
    ): Flow<String> = callbackFlow {
        val call = client.newCall(request)
        onCall(call)

        // ═════════════════ 流式看门狗（**必须有**） ═════════════════
        //
        // 【为什么不能省】`createStreamClient` 把 readTimeout 设成 0（不限制），
        // 因为实测「首 token 中位 13.6s / p90 30.9s / 最大 196.7s」，
        // 固定超时会把「慢但能成功」的请求误杀。**但代价是：如果网关真的挂了
        // （TCP 连上了却永不发数据），socket 读会永久阻塞 → 用户看到「一直转圈，
        // 连超时都不报」，只能杀进程。**
        //
        // Node 版用 300s 静默看门狗兜底（CLAUDE.md 记的「watchdog(300s)」）。
        // 这里补上同等能力：**距上一个事件超过 300s → 主动 cancel 连接**。
        //
        // phase 用来区分「请求没发出去」「响应头没回来」「body 卡住」——
        // Node 版排查时因为只有 `event_count: 0` 而无法定位，只能靠猜。
        val lastEventAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val phase = java.util.concurrent.atomic.AtomicReference("request_start")
        val watchdogFired = java.util.concurrent.atomic.AtomicBoolean(false)

        val watchdog = launch {
            while (isActive) {
                delay(WATCHDOG_POLL_MS)
                val silent = System.currentTimeMillis() - lastEventAt.get()
                if (silent >= STREAM_SILENCE_TIMEOUT_MS) {
                    watchdogFired.set(true)
                    call.cancel()   // 解除 socket 阻塞读，走下面的 IOException 分支
                    break
                }
            }
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                // 协程已取消导致的失败是正常路径，不要报错
                if (!isActive) return
                if (watchdogFired.get()) {
                    close(streamTimeoutError(phase.get(), call))
                    return
                }
                close(ApiTypes.ApiException(
                    message = "网络错误: ${e.message ?: e.javaClass.simpleName}",
                    statusCode = 0,
                    retryable = true,
                    cause = e,
                ))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = try {
                            resp.body?.string().orEmpty()
                        } catch (_: Throwable) {
                            ""
                        }
                        onHttpError?.invoke(resp.code, body)
                        close(ApiTypes.ApiException(
                            message = "HTTP ${resp.code}: ${body.take(2000)}",
                            statusCode = resp.code,
                            // 401/403 也标可重试 —— 语义是「这个 key 不行」，
                            // 上层换一个 key 可能就成功（与 ApiClient.isRetryableStatus 一致）
                            retryable = resp.code == 429 || resp.code >= 500 ||
                                resp.code == 401 || resp.code == 403,
                        ))
                        return
                    }

                    val source = resp.body?.source()
                    if (source == null) {
                        close(ApiTypes.ApiException("响应体为空", statusCode = resp.code))
                        return
                    }

                    phase.set("streaming")
                    lastEventAt.set(System.currentTimeMillis())

                    try {
                        readLoop(source) { line ->
                            lastEventAt.set(System.currentTimeMillis())
                            trySend(line)
                        }
                        close()
                    } catch (e: IOException) {
                        if (watchdogFired.get()) {
                            close(streamTimeoutError(phase.get(), call))
                        } else {
                            // 网络中断：把已收到的数据留下，报错结束
                            close(ApiTypes.ApiException(
                                message = "流中断: ${e.message ?: e.javaClass.simpleName}",
                                statusCode = 0,
                                retryable = true,
                                cause = e,
                            ))
                        }
                    } catch (e: Throwable) {
                        close(e)
                    }
                }
            }
        })

        // Flow 取消 → 断开连接（对应 Node 版 streamController.abort()）
        awaitClose {
            watchdog.cancel()
            call.cancel()
        }
    }

    /**
     * 构造流式超时异常。
     *
     * 消息里带 **"Stream timeout"** 是刻意的 —— `ErrorClassifier` 靠这个字符串
     * 把它归到 `stream_timeout`（不可重试：同一 stream 重发没意义）。
     * 带 phase 是为了排查时能区分「请求没发出去」和「响应头没回来」。
     */
    private fun streamTimeoutError(phase: String, call: Call): ApiTypes.ApiException =
        ApiTypes.ApiException(
            message = "Stream timeout: 静默 ${STREAM_SILENCE_TIMEOUT_MS / 1000}s 无任何事件" +
                " (phase=$phase, sent=${runCatching { call.request().url.encodedPath }.getOrDefault("?")})",
            statusCode = 0,
            retryable = false,
            cause = java.io.InterruptedIOException("stream watchdog"),
        )

    /**
     * 逐行读取并提取 `data:` 负载。
     *
     * SSE 格式（RFC 规范 + 各家实现）：
     * ```
     * : this is a comment (心跳)          ← 跳过
     * event: message_stop                 ← 跳过（Anthropic 用，负载里也有 type）
     * data: {"choices":[...]}             ← 提取
     *                                     ← 空行 = 事件分隔，跳过
     * data: [DONE]                        ← 提取
     * ```
     *
     * **多行 data 的处理**：SSE 规范允许一个事件有多行 `data:`，应该用 `\n` 拼接。
     * 但 OpenAI/Anthropic 实际都只发单行 JSON，所以这里按单行处理 ——
     * 若真遇到多行（极罕见），会各自作为独立负载吐出，上层解析失败会记录
     * [ApiTypes.StreamEvent.ParseError] 但不会中断流。
     *
     * @param onData 每读到一条 `data:` 负载时调用
     */
    private fun readLoop(source: BufferedSource, onData: (String) -> Unit) {
        while (true) {
            val line = source.readUtf8Line() ?: break   // null = 流结束
            if (line.isEmpty()) continue                 // 事件分隔空行
            if (line[0] == ':') continue                 // 注释/心跳
            if (!line.startsWith("data:")) continue      // event:/id:/retry: 等

            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            onData(payload)
        }
    }
}
