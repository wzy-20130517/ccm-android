package com.ccm.app.core.mcp

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.http.HttpHeaders
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * MCP 客户端 —— 基于官方 Kotlin SDK。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * 用户报「mcp 可以接，你看 operit 的实现」。
 *
 * 调研 Operit（AAswordman/Operit，8416 星）后确认：它用的是
 * **官方 Kotlin SDK**（`io.modelcontextprotocol:kotlin-sdk-client:0.10.0`）
 * + Ktor 传输层。直接用 SDK 比自己实现 JSON-RPC 稳得多。
 *
 * 本文件是 CCM 的接入（精简版：**先支持 HTTP/SSE 远程服务器**）。
 * ═══════════════════════════════════════════════════════════════
 *
 * ## 为什么先做 HTTP 不做 stdio
 *
 * CLI 的 mcp.json 大多是 **stdio**（`command` + `args` 启动子进程）——
 * 那需要**持久双向管道**，而 APK 的 proot 通道是一次性命令
 * （跑完即退），没有长连接能力。要实现 stdio 得先给 proot 加
 * 「常驻进程 + stdin/stdout 流」支持（大工程）。
 *
 * HTTP/SSE 类型（`url` 字段）**立刻可用** —— 很多 MCP server
 * （如远程部署的、或本地 HTTP 桥）走这个。
 *
 * ## 配置格式（对齐 CLI 的 mcp.json）
 * ```json
 * {
 *   "mcpServers": {
 *     "my-server": {
 *       "url": "http://127.0.0.1:3001/mcp",
 *       "headers": { "Authorization": "Bearer xxx" }
 *     }
 *   }
 * }
 * ```
 */
class McpClient(
    private val serverName: String,
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) {

    companion object {
        private const val TAG = "McpClient"
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val REQUEST_TIMEOUT_MS = 60_000L
    }

    /** 一个 MCP 工具的定义。 */
    data class McpTool(
        val name: String,
        val description: String,
        /** JSON Schema（字符串形式）。 */
        val inputSchema: String,
    )

    /** 调用结果。 */
    data class CallResult(
        val success: Boolean,
        val result: JSONObject?,
        val error: String? = null,
    )

    private val httpClient = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            socketTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    private var client: Client? = null
    private var connected = false

    /** 连接（幂等）。 */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (connected && client != null) return@withContext true
        try {
            val transport = StreamableHttpClientTransport(
                client = httpClient,
                url = url,
                requestBuilder = { applyHeaders(this) },
            )
            val c = Client(
                clientInfo = Implementation(name = "CCM", version = "1.0"),
            )
            c.connect(transport)
            client = c
            connected = true
            Log.i(TAG, "[$serverName] 已连接 $url")
            true
        } catch (t: Throwable) {
            connected = false
            client = null
            Log.e(TAG, "[$serverName] 连接失败：${t.message}", t)
            false
        }
    }

    fun isConnected(): Boolean = connected && client != null

    /** 列工具。 */
    suspend fun listTools(): List<McpTool> = withContext(Dispatchers.IO) {
        val c = client ?: return@withContext emptyList()
        try {
            val result = c.listTools()
            result.tools.map { tool ->
                val schema = JSONObject().apply {
                    put("type", "object")
                    tool.inputSchema.properties?.let {
                        put("properties", JSONObject(it.toString()))
                    }
                    tool.inputSchema.required?.let { put("required", it) }
                }
                McpTool(
                    name = tool.name,
                    description = tool.description.orEmpty(),
                    inputSchema = schema.toString(),
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "[$serverName] listTools 失败：${t.message}", t)
            emptyList()
        }
    }

    /** 调工具。 */
    suspend fun callTool(name: String, arguments: Map<String, Any?>): CallResult =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext CallResult(false, null, "未连接")
            try {
                val resp = c.callTool(name = name, arguments = arguments)
                val json = JSONObject(
                    kotlinx.serialization.json.Json.encodeToString(
                        io.modelcontextprotocol.kotlin.sdk.types.CallToolResult.serializer(),
                        resp,
                    )
                )
                CallResult(
                    success = resp.isError != true,
                    result = json,
                    error = if (resp.isError == true) json.optString("content") else null,
                )
            } catch (t: Throwable) {
                connected = false
                Log.e(TAG, "[$serverName] callTool($name) 失败：${t.message}", t)
                CallResult(false, null, t.message)
            }
        }

    /** 关闭。 */
    suspend fun close() = withContext(Dispatchers.IO) {
        connected = false
        val c = client
        client = null
        try { c?.close() } catch (_: Throwable) {}
        try { httpClient.close() } catch (_: Throwable) {}
    }

    private fun applyHeaders(builder: HttpRequestBuilder) {
        builder.headers {
            headers.forEach { (k, v) ->
                remove(k)
                append(k, v)
            }
        }
    }
}
