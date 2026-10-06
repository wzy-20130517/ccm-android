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
        /** stdio 类型的 command（仅用于展示「为什么不支持」）。 */
        val command: String?,
        /** 是否被禁用。 */
        val disabled: Boolean,
    )

    private val clients = mutableMapOf<String, McpClient>()

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
                ServerConfig(
                    name = name,
                    url = o.optString("url", "").takeIf { it.isNotBlank() },
                    headers = headers,
                    command = o.optString("command", "").takeIf { it.isNotBlank() },
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
            val url = cfg.url ?: continue   // stdio 跳过
            try {
                val c = clients.getOrPut(cfg.name) { McpClient(cfg.name, url, cfg.headers) }
                if (c.connect()) {
                    out[cfg.name] = c.listTools()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[${cfg.name}] 连接异常：${t.message}", t)
            }
        }
        out
    }

    /** 调一个 MCP 工具。 */
    suspend fun call(server: String, tool: String, args: Map<String, Any?>): McpClient.CallResult {
        val c = clients[server] ?: return McpClient.CallResult(false, null, "服务器未连接：$server")
        return c.callTool(tool, args)
    }

    /** 关全部。 */
    suspend fun closeAll() = withContext(Dispatchers.IO) {
        clients.values.forEach { try { it.close() } catch (_: Throwable) {} }
        clients.clear()
    }
}
