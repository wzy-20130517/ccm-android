package com.ccm.app.core.mcp

import android.util.Log
import com.ccm.app.runtime.ProotRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * MCP 管理器 —— 读配置、连服务器、暴露工具。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 新建 · 2026-10-07 补 stdio 落地】
 *
 * 配置位置：`files/mcp.json`（对齐 CLI 的 `~/.claude-code-mobile/mcp.json`）
 *
 * ## 支持的传输类型
 *
 * | 类型 | 判据 | 传输 |
 * |---|---|---|
 * | HTTP/SSE | 有 `url` | [McpClient]（官方 Kotlin SDK） |
 * | stdio | 有 `command` | [McpStdioTransport]（proot 包装，见 [McpLaunch]） |
 *
 * **判据对齐 CLI**：没有 `transport` 字段时，有 `command` 即 stdio、
 * 有 `url` 即 HTTP。两者都有时优先 HTTP（与 CLI 的 if/else 顺序一致）。
 *
 * ## runtime 为什么要注入
 *
 * stdio 的 node 在 rootfs 里，启动要经 proot 包装 —— 需要 [ProotRuntime]
 * 拿 rootfs 路径、proot 二进制、loader 等。传 null 时退化为裸跑
 * （只适合「command 本来就是宿主可执行文件」的配置）。
 * ═══════════════════════════════════════════════════════════════
 */
class McpManager(
    private val configFile: File,
    /** proot 运行时（stdio 用；null = 裸跑）。 */
    private val runtime: ProotRuntime? = null,
) {

    companion object {
        private const val TAG = "McpManager"
    }

    /** 一个服务器的配置。 */
    data class ServerConfig(
        val name: String,
        /** HTTP/SSE 的地址（null = 非 HTTP 类型）。 */
        val url: String?,
        val headers: Map<String, String>,
        /** stdio 的 command（单个字符串，仅用于展示）。 */
        val command: String?,
        /** stdio 的完整命令行（command + args）。 */
        val commandLine: List<String> = emptyList(),
        /** 环境变量。 */
        val env: Map<String, String> = emptyMap(),
        /** 是否被禁用。 */
        val disabled: Boolean,
    ) {
        /** 这条配置能不能启动（HTTP 或 stdio 至少有一个）。 */
        val runnable: Boolean get() = url != null || commandLine.isNotEmpty()

        /** 传输类型的展示名。 */
        val transportLabel: String
            get() = when {
                url != null -> "HTTP/SSE"
                commandLine.isNotEmpty() -> "stdio"
                else -> "未配置"
            }
    }

    /**
     * 传输抽象 —— 让调用方不用判断类型。
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

    /**
     * 已建立的传输（服务器名 → 传输）。
     *
     * ⚠️ 用 ConcurrentHashMap：`connectAll()`（写）与 `call()`（读）
     * 可能来自不同协程 —— 子 Agent 并发调 MCP 工具、UI 同时刷 /mcp 列表
     * 都会同时碰它。普通 MutableMap 在这种交错下会抛
     * ConcurrentModificationException 或读到半更新状态。
     */
    private val transports = java.util.concurrent.ConcurrentHashMap<String, Transport>()

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
                            val a = argsArr.optString(i, "")
                            if (a.isNotEmpty()) list += a
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
     * 单个服务器失败只记日志，不影响其他的（对齐 CLI 的逐个 try）。
     */
    suspend fun connectAll(): Map<String, List<McpClient.McpTool>> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<String, List<McpClient.McpTool>>()
        for (cfg in loadServers()) {
            if (cfg.disabled) continue
            if (!cfg.runnable) continue
            try {
                // 【修 NPE】原来是 `else -> return@getOrPut null!!` ——
                // runnable 已经挡掉不可启动的配置，这里的 null 分支不该到达；
                // 真到了也只是「这条跳过」，不该把整个 connectAll 炸掉。
                //
                // 【并发】两个协程同时 connectAll 时，双方都会看到缓存里没有
                // 各建一个 —— 用 putIfAbsent 让后到的复用先到的，把多出来的关掉。
                val cached = transports[cfg.name]
                val t: Transport
                if (cached != null) {
                    t = cached
                } else {
                    val built = buildTransport(cfg)
                    if (built == null) {
                        Log.w(TAG, "[${cfg.name}] 无法创建传输（配置不完整）")
                        continue
                    }
                    val prev = transports.putIfAbsent(cfg.name, built)
                    if (prev != null) {
                        // 别人先放进去了 → 用它的，自己这个没连过，直接丢弃
                        try { built.close() } catch (_: Throwable) {}
                        t = prev
                    } else {
                        t = built
                    }
                }

                if (t.connect()) {
                    out[cfg.name] = t.listTools()
                } else {
                    // 连不上就摘掉 + 关掉 —— 否则下次调用直接拿到死传输。
                    // remove(k, v) 只摘自己那个，避免误摘别人刚换上的新传输。
                    transports.remove(cfg.name, t)
                    try { t.close() } catch (_: Throwable) {}
                }
            } catch (t: Throwable) {
                Log.e(TAG, "[${cfg.name}] 连接异常：${t.message}", t)
            }
        }
        out
    }

    /** 按配置建传输（不连接）。 */
    private fun buildTransport(cfg: ServerConfig): Transport? = when {
        cfg.url != null -> HttpTransport(McpClient(cfg.name, cfg.url, cfg.headers))
        cfg.commandLine.isNotEmpty() -> StdioTransport(
            McpStdioTransport(
                serverName = cfg.name,
                commandLine = cfg.commandLine,
                env = cfg.env,
                runtime = runtime,
            )
        )
        else -> null
    }

    /** 调一个 MCP 工具。 */
    suspend fun call(server: String, tool: String, args: Map<String, Any?>): McpClient.CallResult {
        val t = transports[server]
            ?: return McpClient.CallResult(false, null, "服务器未连接：$server")
        return t.callTool(tool, args)
    }

    /**
     * 关全部。
     *
     * 除了本实例持有的传输，还清掉 [McpStdioTransport] 的全局注册表 ——
     * manager 由 ToolsBootstrap 装配时 new 出来，App 层没人持有它的引用；
     * 只关实例内的会漏掉「其他 manager 实例 / 已从 transports 摘除但
     * 进程还活着」的那些 proot/node 子进程。
     */
    suspend fun closeAll() = withContext(Dispatchers.IO) {
        transports.values.forEach { try { it.close() } catch (_: Throwable) {} }
        transports.clear()
        McpStdioTransport.closeAll()
    }
}
