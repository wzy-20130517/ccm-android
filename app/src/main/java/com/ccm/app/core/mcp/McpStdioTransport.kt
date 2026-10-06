package com.ccm.app.core.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit

/**
 * MCP stdio 传输 —— 直接跑用户配的 command（不假设任何解释器）。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * ## 设计原则：不假设用户环境
 *
 * 用户配的 `command` 是什么就跑什么：
 * - `node /path/to/server.mjs` —— 需要用户的 PATH 里有 node
 * - `/data/data/xxx/files/rootfs/usr/bin/python3 ...` —— 绝对路径，直接跑
 * - `npx -y @modelcontextprotocol/server-xxx` —— 需要 npx
 *
 * **不做任何环境假设**（不检测 proot、不检测 node、不检测 python）。
 * 用户配的能跑就跑，跑不起来就如实报错。
 *
 * ## 与 proot 的关系
 *
 * **不经过 proot** —— 直接用 ProcessBuilder 跑。
 * 理由：
 * 1. proot 的 stdin 被重定向到 /dev/null（防 apt 卡死），改它影响面大
 * 2. MCP server 通常是纯 JS/Python 脚本，不需要 proot 的隔离
 * 3. 用户如果想让它在 proot 里跑，自己配 `command` 为 proot 的完整命令行
 *
 * ## 生命周期
 *
 * - **启动**：首次调用时懒加载（连上 → initialize 握手）
 * - **保持**：进程常驻（MCP 是有状态协议）
 * - **停止**：close() 时 destroy 进程
 * - **超时**：读响应 60s 超时（可配）
 * ═══════════════════════════════════════════════════════════════
 */
class McpStdioTransport(
    private val serverName: String,
    private val command: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val workDir: String? = null,
) {

    companion object {
        private const val TAG = "McpStdio"
        private const val READ_TIMEOUT_MS = 60_000L
    }

    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var nextId = 1
    private val lock = Any()

    /** 是否已连接。 */
    val isConnected: Boolean get() = process?.isAlive == true

    /**
     * 启动进程 + 初始化握手。
     *
     * @return 成功与否
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        try {
            val pb = ProcessBuilder(command)
            workDir?.let { pb.directory(File(it)) }
            // 环境变量：继承 + 用户配的
            pb.environment().putAll(env)
            pb.redirectErrorStream(false)

            val p = pb.start()
            process = p
            writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
            reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))

            // stderr 转发到日志（异步）
            Thread {
                try {
                    BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { r ->
                        r.lineSequence().forEach { line ->
                            Log.w(TAG, "[$serverName] $line")
                        }
                    }
                } catch (_: Throwable) {}
            }.apply { isDaemon = true }.start()

            // initialize 握手
            val initResp = request(
                "initialize",
                JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "CCM")
                        put("version", "1.0")
                    })
                },
            )
            if (initResp == null) {
                Log.e(TAG, "[$serverName] initialize 失败")
                close()
                return@withContext false
            }

            // 发 initialized 通知（无 id，不读响应）
            notify("notifications/initialized", JSONObject())

            // ⚠️ Android 的 Process 没有 pid() 方法（那是 Java 9+）——
            // 用反射读 field（Java 8 兼容），失败就不显示
            val pid = try {
                val f2 = p.javaClass.getDeclaredField("pid").apply { isAccessible = true }
                f2.getInt(p)
            } catch (_: Throwable) { -1 }
            Log.i(TAG, "[$serverName] 已连接（pid=$pid）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "[$serverName] 启动失败：${t.message}", t)
            close()
            false
        }
    }

    /** 发请求并等响应（同步阻塞，调用方在 IO 线程）。 */
    private fun request(method: String, params: JSONObject): JSONObject? {
        synchronized(lock) {
            val p = process ?: return null
            val w = writer ?: return null
            val r = reader ?: return null
            val id = nextId++

            try {
                val req = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", id)
                    put("method", method)
                    put("params", params)
                }
                w.write(req.toString())
                w.write("\n")
                w.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "[$serverName] 写请求失败：${t.message}", t)
                return null
            }

            // 读响应（跳过通知）
            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val line = try {
                    // 用 available() 检查可读，避免永久阻塞
                    if (r.ready()) r.readLine() else {
                        Thread.sleep(50)
                        if (p.isAlive) continue else return null
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "[$serverName] 读响应失败：${t.message}", t)
                    return null
                }
                if (line.isNullOrBlank()) continue
                try {
                    val resp = JSONObject(line)
                    if (resp.optInt("id", -1) == id) return resp
                    // 不是我们的响应（通知或其他）→ 继续读
                } catch (_: Throwable) {
                    // 不是 JSON，忽略
                }
            }
            Log.w(TAG, "[$serverName] 等 $method 响应超时")
            return null
        }
    }

    /** 发通知（无 id，不等响应）。 */
    private fun notify(method: String, params: JSONObject) {
        synchronized(lock) {
            try {
                val w = writer ?: return
                val msg = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("method", method)
                    put("params", params)
                }
                w.write(msg.toString())
                w.write("\n")
                w.flush()
            } catch (_: Throwable) {}
        }
    }

    /** 列工具。 */
    suspend fun listTools(): List<McpClient.McpTool> = withContext(Dispatchers.IO) {
        val resp = request("tools/list", JSONObject()) ?: return@withContext emptyList()
        val result = resp.optJSONObject("result") ?: return@withContext emptyList()
        val tools = result.optJSONArray("tools") ?: return@withContext emptyList()
        (0 until tools.length()).mapNotNull { i ->
            val t = tools.optJSONObject(i) ?: return@mapNotNull null
            McpClient.McpTool(
                name = t.optString("name", ""),
                description = t.optString("description", ""),
                inputSchema = t.optJSONObject("inputSchema")?.toString() ?: "{}",
            )
        }
    }

    /** 调工具。 */
    suspend fun callTool(name: String, args: Map<String, Any?>): McpClient.CallResult =
        withContext(Dispatchers.IO) {
            val params = JSONObject().apply {
                put("name", name)
                put("arguments", JSONObject(args as Map<*, *>))
            }
            val resp = request("tools/call", params)
                ?: return@withContext McpClient.CallResult(false, null, "无响应（进程可能已退出）")
            val err = resp.optJSONObject("error")
            if (err != null) {
                return@withContext McpClient.CallResult(false, null, err.optString("message", "未知错误"))
            }
            val result = resp.optJSONObject("result")
            McpClient.CallResult(
                success = result?.optBoolean("isError") != true,
                result = result,
                error = if (result?.optBoolean("isError") == true) "工具返回错误" else null,
            )
        }

    /** 关闭进程。 */
    fun close() {
        try { writer?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try {
            process?.let { p ->
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        } catch (_: Throwable) {}
        process = null
        writer = null
        reader = null
    }
}
