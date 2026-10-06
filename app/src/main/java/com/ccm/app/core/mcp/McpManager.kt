package com.ccm.app.core.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * MCP 管理器 —— 读配置、连服务器、暴露工具。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * 配置位置：`files/mcp.json`（对齐 CLI 的 `~/.claude-code-mobile/mcp.json`）
 *
 * ## 支持的传输类型
 * - **HTTP/SSE**（`url` 字段）—— ✅ 已支持
 * - **stdio**（`command` + `args`）—— ❌ 需要 proot 常驻进程支持（见 McpClient 注释）
 *
 * 配置里是 stdio 类型的会被跳过（并在 `/mcp` 里如实标注原因）。
 * ═══════════════════════════════════════════════════════════════
 */
class McpManager(private val configFile: File) {

    companion object {
        private const val TAG = "McpManager"
    }

    /** 一个服务器的配置。 */
    data class ServerConfig(
        val name: String,
        /** HTTP/SSE 的地址（null = stdio 类型，当前不支持）。 */
        val url: String?,
        val headers: Map<String, String>,
        /** stdio 类型的 command（单个字符串，仅用于展示）。 */
        val command: String?,
        /** stdio 的完整命令行（command + args）。 */
        val commandLine: List<String> = emptyList(),
        /** 环境变量。 */
        val env: Map<String, String> = emptyMap(),
        /** 是否被禁用。 */
        val disabled: Boolean,
    )

    private val clients = mutableMapOf<String, McpClient>()

    /**
     * stdio 传输（问题40）—— 与 HTTP 的 McpClient 并存。
     *
     * 用 `Transport` 统一两种类型，避免调用方判断。
     */
    private sealed interface Transport {
        suspend fun connect(): Boolean
        suspend fun listTools(): List<McpClient.McpTool>
        suspend fun callTool(name: String, args: Map<String, Any?>): McpClient.CallResult
        suspend fun close()
    }

    private class HttpTransport(val c: McpClient) : Transport {
        override suspend fun connect() = c.connect()
        override suspend fun listTools() = c.listTools()
        override suspend fun callTool(name: String, args: Map<String, Any?>) = c.callTool(name, args)
        override suspend fun close() { c.close() }
    }

    private class StdioTransport(val t: McpStdioTransport) : Transport {
        override suspend fun connect() = t.connect()
        override suspend fun listTools() = t.listTools()
        override suspend fun callTool(name: String, args: Map<String, Any?>) = t.callTool(name, args)
        override suspend fun close() { t.close() }
    }

    private val transports = mutableMapOf<String, Transport>()

    /** 读配置。 */
    fun loadServers(): List<ServerConfig> = try {
        if (!configFile.exists()) emptyList()
        else {
            val root = JSONObject(configFile.readText())
            val servers = root.optJSONObject("mcpServers") ?: JSONObject()
            servers.keys().asSequence().mapNotNull { name ->
                val o = servers.optJSONObject(name) ?: return@mapNotNull null
                val headers = mutableMapOf<String, String>()
                o.optJSONObject("headers")?.let { h ->
                    h.keys().forEach { k -> headers[k] = h.optString(k, "") }
                }
                // stdio 的完整命令行 = command + args
                val cmd = o.optString("command", "").takeIf { it.isNotBlank() }
                val argsArr = o.optJSONArray("args")
                val cmdLine = if (cmd != null) {
                    val list = mutableListOf(cmd)
                    if (argsArr != null) {
                        for (i in 0 until argsArr.length()) {
                            argsArr.optString(i, "")?.let { if (it.isNotEmpty()) list += it }
                        }
                    }
                    list
                } else emptyList()

                // env 环境变量
                val envMap = mutableMapOf<String, String>()
                o.optJSONObject("env")?.let { e ->
                    e.keys().forEach { k -> envMap[k] = e.optString(k, "") }
                }

                ServerConfig(
                    name = name,
                    url = o.optString("url", "").takeIf { it.isNotBlank() },
                    headers = headers,
                    command = cmd,
                    commandLine = cmdLine,
                    env = envMap,
                    disabled = o.optBoolean("disabled", false),
                )
            }.toList()
        }
    } catch (t: Throwable) {
        Log.e(TAG, "读配置失败：${t.message}", t)
        emptyList()
    }

    /**
     * 连所有可用的服务器，返回「服务器名 → 工具列表」。
     *
     * stdio 类型跳过（返回空列表，但保留在 loadServers 里供 UI 展示）。
     */
    suspend fun connectAll(): Map<String, List<McpClient.McpTool>> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<String, List<McpClient.McpTool>>()
        for (cfg in loadServers()) {
            if (cfg.disabled) continue
            try {
                val t = transports.getOrPut(cfg.name) {
                    when {
                        // HTTP/SSE 类型
                        cfg.url != null -> HttpTransport(McpClient(cfg.name, cfg.url, cfg.headers))
                        // stdio 类型（问题40：新增支持）
                        cfg.command != null -> StdioTransport(
                            McpStdioTransport(
                                serverName = cfg.name,
                                command = cfg.commandLine,
                                env = cfg.env,
                            )
                        )
                        else -> return@getOrPut null!!
                    }
                }
                if (t.connect()) {
                    out[cfg.name] = t.listTools()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[${cfg.name}] 连接异常：${t.message}", t)
            }
        }
        out
    }

    /** 调一个 MCP 工具。 */
    suspend fun call(server: String, tool: String, args: Map<String, Any?>): McpClient.CallResult {
        val t = transports[server] ?: return McpClient.CallResult(false, null, "服务器未连接：$server")
        return t.callTool(tool, args)
    }

    /** 关全部。 */
    suspend fun closeAll() = withContext(Dispatchers.IO) {
        transports.values.forEach { try { it.close() } catch (_: Throwable) {} }
        transports.clear()
    }
}
