package com.ccm.app.core.mcp

import android.util.Log
import com.ccm.app.runtime.ProotRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * MCP stdio 传输 —— 起子进程，JSON-RPC over stdin/stdout。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 新建 · 2026-10-07 补 proot 支持】
 *
 * ## 与 CLI 的对应关系
 *
 * 对齐 `core/integrations/mcp-client.mjs` 的协议语义：
 * - 请求 `{jsonrpc:"2.0", id, method, params}` + `\n`，stdout 按行读
 * - **只认 JSON 行**，非 JSON（日志/REPL 噪音）丢弃 —— server 的
 *   `console.log` 会混进 stdout，不滤掉会把响应流搅乱
 * - 进程 error/close 时**立刻让在等的请求失败**，不傻等到超时
 *   （CLI 踩过：command 不存在时 spawn 秒失败，但请求傻等 30 秒，
 *    表现为「/tools 卡 27 秒」）
 *
 * ## 走不走 proot
 *
 * 由 [McpLaunch] 决定并给出 argv（见那个文件的说明）：
 * - node 家族 → 包 proot（node 在 rootfs 里）
 * - 已是 proot 形态 → 只规整路径
 * - 其他 → 裸跑
 *
 * 本类负责把 [McpLaunch.Spec.needsProotEnv] 对应的环境补上 ——
 * 缺 `PROOT_TMP_DIR` / `PROOT_LOADER` 时 proot 直接 fatal
 * （DshHostManager 真机实测：`can't create temporary file` +
 *  `execve(/usr/bin/env): Function not implemented`）。
 *
 * ## 生命周期
 *
 * - **启动**：首次 connect 时起进程 + initialize 握手
 * - **保持**：进程常驻（MCP 是有状态协议）
 * - **空闲超时**：超过 [IDLE_TIMEOUT_MS] 没请求就关（见下）
 * - **App 退出**：走 [closeAll] 统一杀（注册表在 companion）
 * ═══════════════════════════════════════════════════════════════
 */
class McpStdioTransport(
    private val serverName: String,
    private val commandLine: List<String>,
    private val env: Map<String, String> = emptyMap(),
    /** proot 运行时（null = 只能裸跑，用于测试等场景）。 */
    private val runtime: ProotRuntime? = null,
) {

    companion object {
        private const val TAG = "McpStdio"

        /**
         * 单请求超时。
         *
         * 对齐 CLI 的语义：本机子进程正常毫秒级返回，这个定时器只兜
         * 「起来了但不响应」。CLI 取 10s，APK 放宽到 30s —— 手机端
         * 有些 MCP 工具要过网络（如 mail-qq 走 IMAP），留点余量。
         */
        private const val READ_TIMEOUT_MS = 30_000L

        /**
         * 空闲多久关进程。
         *
         * 【为什么要关】MCP server 是常驻 node 进程，每个约 30~60MB。
         * 用户配了四五个 server 却一天只用一个时，不该让其余几个
         * 一直占着内存。下次要用会重新连（见 callTool 里的懒重连）。
         */
        private const val IDLE_TIMEOUT_MS = 10 * 60_000L

        /** 空闲检查的扫描间隔。 */
        private const val SWEEP_INTERVAL_MS = 30_000L

        /**
         * 活着的传输实例（App 退出时统一关）。
         *
         * 用 ConcurrentHashMap 的 keySet —— 元素是**实例身份**比较
         * （本类不重写 equals/hashCode），所以同名 server 的多个实例
         * 不会互相顶掉。
         */
        private val live = ConcurrentHashMap.newKeySet<McpStdioTransport>()

        @Volatile
        private var sweeper: Thread? = null

        /** shutdown hook 是否已注册（首次起进程时装，避免重复装）。 */
        @Volatile
        private var hookInstalled = false

        /**
         * 关掉所有 stdio 进程 —— App 退出时调。
         *
         * 【为什么要有】`McpManager.closeAll()` 是实例方法，而 manager
         * 由 ToolsBootstrap 装配时 new 出来，没人持有它的引用 ——
         * App 退出时那些 proot/node 进程会残留（Android 杀 App 进程时
         * 子进程不一定跟着死，尤其 proot 这种 ptrace 进程）。
         *
         * @param fast true = 不等进程收尾（只发 SIGTERM 就返回）。
         *   shutdown hook 里必须用 fast —— 那里每个进程等 3 秒的话，
         *   配 5 个 server 就是 15 秒的退出卡顿。SIGTERM 发出后
         *   proot 的 `--kill-on-exit` 和 server 自身的 stdin-EOF
         *   会兜住清理。
         */
        fun closeAll(fast: Boolean = false) {
            val snapshot = live.toList()
            if (snapshot.isEmpty()) return
            Log.i(TAG, "关闭全部 stdio 传输（${snapshot.size} 个${if (fast) "，快速模式" else ""}）")
            snapshot.forEach { try { it.close(fast) } catch (_: Throwable) {} }
            live.clear()
        }

        /** 当前活着的实例数（诊断用）。 */
        fun liveCount(): Int = live.size

        /**
         * 装 JVM shutdown hook —— 进程退出时兜底杀子进程。
         *
         * 【边界要说清】它在**进程正常退出**（System.exit / Runtime.exit）
         * 时触发；被系统 LMK 强杀（SIGKILL）时不触发 —— 那种情况靠
         * proot 的 `--kill-on-exit` 加 MCP server 自身的 stdin-EOF 退出
         * 兜住（stdin 写端随 App 消失 → server 读到 EOF → 自行退出）。
         * 两道防线互补，都不能少。
         */
        private fun installShutdownHook() {
            if (hookInstalled) return
            synchronized(this) {
                if (hookInstalled) return
                try {
                    Runtime.getRuntime().addShutdownHook(
                        Thread {
                            Log.i(TAG, "进程退出，关闭 MCP stdio 子进程")
                            closeAll(fast = true)
                        }.apply { name = "mcp-stdio-shutdown" }
                    )
                    hookInstalled = true
                } catch (t: Throwable) {
                    // 装不上不影响功能（还有显式 closeAll 和 kill-on-exit）
                    Log.w(TAG, "注册 shutdown hook 失败：${t.message}")
                }
            }
        }

        private fun register(t: McpStdioTransport) {
            live.add(t)
            installShutdownHook()
            ensureSweeper()
        }

        private fun unregister(t: McpStdioTransport) {
            live.remove(t)
        }

        /** 懒启动空闲扫描线程（队列空时自己退出，下次注册再起）。 */
        private fun ensureSweeper() {
            if (sweeper?.isAlive == true) return
            synchronized(this) {
                if (sweeper?.isAlive == true) return
                sweeper = Thread {
                    while (true) {
                        try {
                            Thread.sleep(SWEEP_INTERVAL_MS)
                        } catch (_: InterruptedException) {
                            return@Thread
                        }
                        val now = System.currentTimeMillis()
                        for (t in live) {
                            if (now - t.lastUsedAt > IDLE_TIMEOUT_MS) {
                                Log.i(TAG, "[${t.serverName}] 空闲超时，关闭进程")
                                try { t.close() } catch (_: Throwable) {}
                            }
                        }
                        if (live.isEmpty()) return@Thread
                    }
                }.apply {
                    isDaemon = true
                    name = "mcp-idle-sweeper"
                    start()
                }
            }
        }
    }

    /**
     * 子进程句柄。
     *
     * @Volatile：它被**锁外**读过两处 —— `isConnected`（双重检查锁定的
     * 第一次检查）与 `markDead` 的身份校验。少了它，重连后其他线程可能
     * 长时间读到 null（旧值），表现为「明明连上了却一直报未连接」。
     */
    @Volatile
    private var process: Process? = null
    private var writer: OutputStreamWriter? = null
    private var reader: BufferedReader? = null
    private var nextId = 1
    private val lock = Any()

    /** 最后一次收发请求的时刻（空闲超时用）。 */
    @Volatile
    private var lastUsedAt = System.currentTimeMillis()

    /** 进程死亡原因（非 null 时所有请求立刻失败，不傻等）。 */
    @Volatile
    private var deadReason: String? = null

    /** 是否已连接。 */
    val isConnected: Boolean get() = process?.isAlive == true

    /**
     * 启动进程 + initialize 握手。
     *
     * @return 成功与否
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        synchronized(lock) {
            if (isConnected) return@withContext true
            try {
                // 重连前先收尾残留的死进程 —— 直接覆盖 process 字段会漏掉
                // 旧句柄（它可能还挂着没退干净的 proot/node，句柄一丢就没人杀它了）。
                // 用 fast=true：它已经死了或半死，不值得等 3 秒。
                if (process != null) {
                    Log.i(TAG, "[$serverName] 重连前清理残留进程")
                    closeInternal(fast = true)
                }

                val spec = McpLaunch.build(runtime, commandLine, env)
                if (spec.isEmpty) {
                    Log.e(TAG, "[$serverName] 无法启动：命令行解析为空（$commandLine）")
                    return@withContext false
                }

                val pb = ProcessBuilder(spec.argv)

                if (spec.needsProotEnv && runtime != null) {
                    // ⚠️ 必须补 proot 的 env（缺了直接 fatal，见类注释）
                    runtime.applyProotEnv(pb)
                    // 工作目录与 ProotRuntime.buildProcess 保持一致
                    pb.directory(runtime.rootfsDir().parentFile ?: runtime.rootfsDir())
                } else if (spec.processEnv.isNotEmpty()) {
                    pb.environment().putAll(spec.processEnv)
                }

                // 注意：**不能**重定向 stdin 到 /dev/null —— 本传输靠它写 JSON-RPC。
                // （ProotRuntime.buildProcess 那么做是为了防 apt 卡死，场景不同。）
                pb.redirectErrorStream(false)

                val p = pb.start()
                process = p
                deadReason = null
                lastUsedAt = System.currentTimeMillis()
                writer = OutputStreamWriter(p.outputStream, Charsets.UTF_8)
                reader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))

                // stderr 转发到日志（异步）—— MCP server 的报错都在这里
                Thread {
                    try {
                        BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { r ->
                            r.lineSequence().forEach { line ->
                                Log.w(TAG, "[$serverName] $line")
                            }
                        }
                    } catch (_: Throwable) {}
                }.apply { isDaemon = true }.start()

                // 进程退出 → 让在等的请求立刻失败
                //
                // ⚠️ 必须把 p 传进去做身份校验：旧进程退出、新进程刚起来的
                // 窗口里，字段 process 已指向新进程，此时无脑 markDead 会把
                // **新进程的 reader** 关掉（表现为「重连后第一次调用必失败」）。
                Thread {
                    try {
                        p.waitFor()
                        val code = try { p.exitValue() } catch (_: Throwable) { -1 }
                        markDead("MCP 进程已退出（code $code）", expected = p)
                    } catch (_: Throwable) {}
                }.apply { isDaemon = true }.start()

                register(this)

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

                Log.i(TAG, "[$serverName] 已连接（${spec.argv.size} 个 argv，proot=${spec.needsProotEnv}）")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "[$serverName] 启动失败：${t.message}", t)
                close()
                false
            }
        }
    }

    /**
     * 标记进程死亡 —— 唤醒所有等待者（对齐 CLI 的 failPending）。
     *
     * @param expected 期望的进程实例；非 null 时若当前 [process] 已换人
     *   （旧进程的收尾线程晚到）则**不处理** —— 否则会把新进程的 reader
     *   一起关掉，表现为「重连后第一次调用必失败」。
     */
    private fun markDead(reason: String, expected: Process? = null) {
        if (expected != null && process !== expected) return
        deadReason = reason
        // 关闭流 → 阻塞中的 readLine 立刻返回 null
        try { reader?.close() } catch (_: Throwable) {}
    }

    /** 发请求并等响应（同步阻塞，调用方在 IO 线程）。 */
    private fun request(method: String, params: JSONObject): JSONObject? {
        synchronized(lock) {
            deadReason?.let {
                Log.w(TAG, "[$serverName] 进程已死（$it），跳过 $method")
                return null
            }
            val p = process ?: return null
            val w = writer ?: return null
            val r = reader ?: return null
            val id = nextId++
            lastUsedAt = System.currentTimeMillis()

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
                markDead("写请求失败：${t.message}")
                return null
            }

            // 读响应（跳过通知与非 JSON 噪音）
            val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                val line: String? = try {
                    if (r.ready()) r.readLine() else {
                        Thread.sleep(20)
                        if (p.isAlive) continue else return null
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "[$serverName] 读响应失败：${t.message}")
                    markDead("读响应失败：${t.message}")
                    return null
                }
                // null = EOF（流被关 / 进程退出）
                if (line == null) {
                    if (deadReason == null) markDead("stdout 已关闭")
                    return null
                }
                if (line.isBlank()) continue
                try {
                    val resp = JSONObject(line)
                    if (resp.optInt("id", -1) == id) {
                        lastUsedAt = System.currentTimeMillis()
                        return resp
                    }
                    // 不是我们的响应（通知 / 别人的 id）→ 继续读
                } catch (_: Throwable) {
                    // 不是 JSON（server 的日志噪音）→ 丢弃
                }
            }
            Log.w(TAG, "[$serverName] 等 $method 响应超时（${READ_TIMEOUT_MS}ms）")
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
        ensureConnected()
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
            ensureConnected()
            val params = JSONObject().apply {
                put("name", name)
                put("arguments", JSONObject(args as Map<*, *>))
            }
            val resp = request("tools/call", params)
                ?: return@withContext McpClient.CallResult(
                    false, null, deadReason ?: "无响应（进程可能已退出）",
                )
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

    /**
     * 没连上就重连 —— 空闲超时关掉、进程崩掉后，下一次调用能自愈。
     *
     * 【为什么放在这里而不是只靠 McpManager】调用方可能缓存了工具列表
     * 直接 call，不经过 connectAll()。放在出口处，任何入口都覆盖。
     */
    private suspend fun ensureConnected() {
        if (isConnected) return
        Log.i(TAG, "[$serverName] 未连接（${deadReason ?: "空闲关闭"}），尝试重连")
        connect()
    }

    /**
     * 关闭进程。
     *
     * @param fast true = 不等收尾（只 destroy + destroyForcibly 就返回）。
     *   shutdown hook 用（见 [closeAll]）；常规调用传 false，
     *   给 proot 3 秒清理窗口（它会带走 node 子进程）。
     */
    fun close(fast: Boolean = false) {
        // 取进程快照。null = 本来就没在跑（或已被别人关掉）。
        //
        // ⚠️ 这个早退不能省：`markDead(reason, expected=null)` 是**无条件**
        // 生效的（守卫只在 expected != null 时比较），此时若另一线程刚完成
        // 重连（process 已换成新进程），我们会把新连接的 deadReason 污染成
        // 「连接已关闭」，之后所有请求全部秒失败 —— 而且日志上看不出原因。
        //
        // 但**仍要 unregister**：否则实例永远留在 live 集合里，空闲扫描
        // 线程会反复 close 它、且 `live.isEmpty()` 永不为真 → 线程不退出。
        val captured = process ?: run {
            unregister(this)
            return
        }

        // 【为什么先 markDead 再拿锁】lock 会被正在等响应的 request() 持有
        // 最长 30s（读超时），直接 synchronized 进去会把调用方（空闲扫描 /
        // App 退出）堵住。先标死 + 关流，等在等的那个立刻失败并释放锁。
        markDead("连接已关闭", expected = captured)

        synchronized(lock) {
            // 身份校验：拿锁期间可能已重连出新进程，别把新的误杀/置空
            val p = process
            if (p != null && p !== captured) return
            closeInternal(fast)
        }
    }

    /**
     * 关闭的实际动作 —— **调用方必须已持有 [lock]**。
     *
     * 拆出来是为了让 [connect] 的「重连前清理残留进程」复用：
     * 那里已经站在 `synchronized(lock)` 里，再调 [close] 会：
     *   ① 嵌套 synchronized（JVM monitor 可重入，不死锁，但语义绕）
     *   ② 走 close 的早退分支 —— 此时 process 非 null 所以不会早退，
     *      但 markDead 的 expected 校验用的还是旧快照，白绕一圈。
     */
    private fun closeInternal(fast: Boolean) {
        val p = process
        try { writer?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try {
            p?.let {
                // --kill-on-exit 会让 proot 退出时带走 node 子进程；
                // 先 destroy 给它清理机会，超时再强杀。
                it.destroy()
                if (fast) {
                    // 不等收尾：直接 SIGKILL。proot 的 --kill-on-exit
                    // 与 server 的 stdin-EOF 会兜住后续清理。
                    it.destroyForcibly()
                } else if (!it.waitFor(3, TimeUnit.SECONDS)) {
                    it.destroyForcibly()
                }
            }
        } catch (_: Throwable) {}
        process = null
        writer = null
        reader = null
        unregister(this)
    }
}
