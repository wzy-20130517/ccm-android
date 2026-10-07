package com.ccm.app.core.plugin

import android.content.Context
import android.util.Log
import com.ccm.app.runtime.ProotRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * DSH 插件宿主（dsh-host）在 proot 里的安装与生命周期管理。
 *
 * CCM 的插件能力来自 DSH 生态（Cordis 插件运行时）。CLI 侧在 Termux 里跑
 * Node 进程，但 APK 不能依赖 Termux —— 所以把 dsh-host 核心文件打进 APK
 * assets，首次启用时部署到 rootfs（/root/.ccm/dsh-host/），在 proot 里
 * npm ci + 启动 Node 监听 127.0.0.1:8790，APK 通过 HTTP 调控制 API。
 *
 * 体积（实测）：Node 运行时 apt 约 30MB（复用 McpInstaller.ensureNode）；
 * dsh-host 依赖 120+ 包 npm ci 后约 334MB。不能精简 —— 27/60 的包用了
 * Node 内置 API，116 个官方包是 31 个活跃插件的运行时基础。
 *
 * 控制 API（server.mjs 提供）：
 *   GET  /control/status | /control/providers | /control/bundles
 *   POST /control/load | /unload | /set-plugin | /install | /remove
 *
 * @see PluginManager（HTTP 客户端）
 */
class DshHostManager(private val context: Context) {

    companion object {
        private const val TAG = "DshHostManager"

        /** 部署目标（rootfs 内） */
        private const val HOST_DIR = "/root/.ccm/dsh-host"

        /** assets 里的源文件目录 */
        private const val ASSET_DIR = "dsh-host"

        /** 核心文件清单（与 CLI 侧 dsh-host/ 对齐；不含 node_modules） */
        private val CORE_FILES = listOf(
            "server.mjs",
            "plugin-loader.mjs",
            "services.mjs",
            "package.json",
            "package-lock.json",
        )

        /** 宿主监听地址（与 server.mjs 的 DSH_HOST_PORT 默认值一致） */
        const val BASE_URL = "http://127.0.0.1:8790"

        /** 部署完成标记（node_modules 装完才写） */
        private const val READY_MARKER = ".installed"

        /**
         * 常驻宿主进程（2026-10-07 加）。
         *
         * 【为什么必须持有进程】原来 start 用 `nohup node ... &` fire-and-forget
         * + `runtime.exec`（proot 强制 --kill-on-exit）—— exec 一返回，
         * proot 退出就把它启动的整棵进程树杀掉（nohup 只挡 SIGHUP，挡不住
         * proot 直接 kill）。真机实测：host.log 0 字节，宿主活不过 2 秒，
         * 健康检查永远失败。
         *
         * 改成 ProcessBuilder 直接持有 proot 进程（对齐 McpStdioTransport
         * 跑 MCP server 的做法）—— Process 活着 proot 就活着，node 自然常驻。
         * 放 companion：DshHostManager 每次 new，实例字段存不住进程句柄。
         */
        @Volatile
        private var hostProcess: Process? = null

        private const val HEALTH_PATH = "/control/status"
    }

    private val runtime = ProotRuntime(context)

    /** 宿主部署目录（rootfs 内）。 */
    private fun hostDir(): File = File(runtime.rootfsDir(), HOST_DIR.removePrefix("/"))

    /**
     * Node 运行时就绪：装上（ensureNode）+ 版本 ≥22。
     *
     * deploy 和 start **共用** —— state()==Ready 时会跳过 deploy 直接
     * start，检查只放 deploy 里就形同虚设（真机踩过：依赖齐了但 Node
     * 还是 18，宿主加载即崩 SyntaxError）。
     *
     * 22 的来源：宿主的 dsh-subprocess-local 用 node:util
     * .getSystemErrorMessage（22.3+ API），18 上 import 即崩。
     */
    suspend fun ensureNodeRuntime(onLog: (String) -> Unit = {}): Boolean {
        onLog("检查 Node 运行时…")
        val mcpInstaller = com.ccm.app.core.mcp.McpInstaller(context, runtime)
        if (!mcpInstaller.ensureNode { s -> onLog(s) }) {
            onLog("Node 安装失败")
            return false
        }
        val nodeMajor = probeNodeMajor()
        if (nodeMajor < 22) {
            onLog("Node $nodeMajor 过旧（宿主要求 22+），升级到 " +
                com.ccm.app.runtime.ToolchainCatalog.NODE_VERSION + "…")
            val upgraded = com.ccm.app.runtime.RootfsManager(context).installToolchains(
                selected = setOf("nodejs"),
                exec = { cmd, ln -> runtime.exec(command = cmd, onLine = ln) },
                onLine = { s -> onLog(s) },
            )
            val after = probeNodeMajor()
            if (!upgraded || after < 22) {
                onLog("Node 升级失败（当前 $after）")
                return false
            }
            onLog("Node 已升级到 v$after")
        }
        return true
    }

    /**
     * 探 rootfs 内 node 的大版本（如 18/22/24）。没装返回 0。
     * 走 `node --version`（PATH 里 /usr/local 优先于 /usr/bin）。
     */
    private fun probeNodeMajor(): Int {
        var major = 0
        try {
            runtime.execWithTimeout(
                command = listOf("/bin/bash", "-c", "node --version 2>/dev/null || echo v0"),
                workDir = "/root",
                onLine = { line ->
                    Regex("v(\\d+)").find(line.trim())?.let {
                        major = it.groupValues[1].toIntOrNull() ?: 0
                    }
                },
                timeoutMs = 15_000L,
                idleMs = 10_000L,
            )
        } catch (_: Throwable) { /* 返回 0 → 触发升级路径 */ }
        return major
    }

    sealed interface State {
        /** 未部署（assets 文件还没拷进 rootfs） */
        data object NotInstalled : State

        /** 文件已部署但依赖没装完 */
        data object DepsMissing : State

        /** 依赖齐了，进程没起 */
        data object Ready : State

        /** 进程在跑（健康检查通过） */
        data object Running : State

        data class Error(val message: String) : State
    }

    /**
     * 当前状态（快速判断，不做网络健康检查）。
     *
     * ⚠️ marker 之外**还要验证 node_modules** —— 真机实测踩过：
     * 旧版 deploy 的 npm 段有 bug（管道吞退出码），npm ci 失败也写了
     * .installed，光看 marker 会误判 Ready → 跳过依赖安装直接 start
     * → 宿主起来就崩（ERR_MODULE_NOT_FOUND）。marker 可能是假的，
     * 依赖目录不会说谎。
     */
    fun state(): State {
        val hostDir = File(runtime.rootfsDir(), HOST_DIR.removePrefix("/"))
        if (!File(hostDir, "server.mjs").exists()) return State.NotInstalled
        if (!File(hostDir, READY_MARKER).exists()) return State.DepsMissing
        if (!File(hostDir, "node_modules/@deepseek-ai/cordis").exists()) {
            // 假 marker（旧 bug 遗留）→ 按未装处理，deploy 会重跑 npm ci
            File(hostDir, READY_MARKER).delete()
            return State.DepsMissing
        }
        return State.Ready
    }

    /**
     * 部署 dsh-host（幂等）：拷 assets → ensureNode → npm ci。
     *
     * @param onLog 逐行进度（喂给设置页日志窗口）
     * @return true = 部署完成，可以启动
     */
    suspend fun deploy(onLog: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        try {
            // ── 前置：rootfs 必须已装 ─────────────────────────────
            // 首次引导页的「安装 Linux 运行环境」没走完时，rootfs 目录是
            // 空的（只有 mkdirs 沿途创建的 root/），apt/node 全不存在 ——
            // 不检查的话会白跑到 ensureNode 才失败，错误还看不出根因。
            if (!com.ccm.app.runtime.RootfsManager(context).isInstalled()) {
                onLog("Linux 运行环境未安装（rootfs 缺失）—— 先在首次启动引导页完成「安装 Linux 运行环境」，或到 设置 → 环境 安装")
                return@withContext false
            }

            val hostDir = File(runtime.rootfsDir(), HOST_DIR.removePrefix("/"))

            // 1. 拷核心文件（每次覆盖 —— APK 升级可能改了它们）
            onLog("部署 dsh-host 文件…")
            hostDir.mkdirs()
            for (name in CORE_FILES) {
                context.assets.open("$ASSET_DIR/$name").use { input ->
                    File(hostDir, name).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            Log.i(TAG, "核心文件已部署到 ${hostDir.absolutePath}")

            // 2. Node 运行时（含版本要求，见 ensureNodeRuntime）
            if (!ensureNodeRuntime(onLog)) return@withContext false

            // 3. 依赖（120+ 包，首次约几分钟到十几分钟，取决于网络）
            if (!File(hostDir, READY_MARKER).exists()) {
                onLog("安装插件宿主依赖（约 334MB，首次较慢）…")

                // ── npm 镜像（2026-10-07 真机踩坑）──────────────────────
                // rootfs 里没有 .npmrc → npm 直连 registry.npmjs.org，
                // 国内网络直接卡死（实测 70 秒零字节增长，cache 停在 11MB）。
                // CLI 侧（Termux ~/.npmrc）早就配了 npmmirror，APK 这边
                // 的 rootfs 是全新环境，没人给它配 —— 这里补上。
                // 覆盖写（幂等）：镜像地址是固定值，不怕用户改过。
                try {
                    File(runtime.rootfsDir(), "root/.npmrc").writeText(
                        "registry=https://registry.npmmirror.com\n" +
                            "fetch-retries=3\n" +
                            "fetch-retry-maxtimeout=60000\n",
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "写 .npmrc 失败（继续直连）: ${t.message}")
                }

                var npmExitCode: Int? = null
                runtime.execWithTimeout(
                    command = listOf(
                        "/bin/bash", "-c",
                        // 【2026-10-07 修】原来是 `npm ci ... 2>&1 | tail -5; echo NPM_DONE`
                        // —— 两个 bug：
                        //   1. 管道吞退出码（tail 永远 0），且 echo NPM_DONE 无条件执行
                        //      → ok 恒真 → npm ci 失败也写 .installed → 宿主起来就崩
                        //      （实测：node_modules 没有，ERR_MODULE_NOT_FOUND）
                        //   2. 真实报错被 tail -5 截断，排查无门
                        // 现在：不走管道（完整输出进 onLine）、打退出码、
                        // marker 写入前还要**验证 node_modules 真的存在**（双保险）。
                        "cd $HOST_DIR && " +
                            // --legacy-peer-deps：dsh-host 的 120+ 包之间有
                            // peer 版本冲突，npm 7+ 默认严格校验会 ERESOLVE
                            // 退出码 1（真机实测）。CLI 侧的安装命令同样带它。
                            "npm ci --omit=dev --no-audit --no-fund " +
                            "--legacy-peer-deps 2>&1; " +
                            "echo NPM_EXIT=\$?",
                    ),
                    workDir = "/root",
                    onLine = { line ->
                        line.substringAfter("NPM_EXIT=", "").trim().toIntOrNull()?.let {
                            npmExitCode = it
                        }
                        // npm 输出转给 UI（去掉 ANSI 颜色码）
                        val clean = line.replace(Regex("\\[[0-9;]*m"), "")
                        if (clean.isNotBlank()) onLog(clean.take(120))
                    },
                    timeoutMs = 30 * 60_000L,
                    idleMs = 10 * 60_000L,
                )
                if (npmExitCode == null || npmExitCode != 0) {
                    onLog("依赖安装失败（npm 退出码 $npmExitCode）")
                    return@withContext false
                }
                // 双保险：marker 只在依赖真到位时写（宿主 import 的核心包必须在）
                val coreDep = File(hostDir, "node_modules/@deepseek-ai/cordis")
                if (!coreDep.exists()) {
                    onLog("npm 退出码 0 但核心依赖缺失（${coreDep.name} 不存在）")
                    return@withContext false
                }
                File(hostDir, READY_MARKER).writeText(System.currentTimeMillis().toString())
            }

            onLog("插件宿主就绪")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "部署失败", t)
            onLog("部署失败: ${t.message}")
            false
        }
    }

    /**
     * 启动宿主进程（后台，不阻塞）。
     *
     * 用 nohup + & 让进程脱离 exec 生命周期 —— execWithTimeout 等的是命令
     * 退出，而宿主是常驻的；直接跑会一直占到超时。
     */
    suspend fun start(onLog: (String) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        try {
            when (val st = state()) {
                State.NotInstalled, State.DepsMissing -> {
                    onLog("宿主还没部署，请先执行安装")
                    return@withContext false
                }
                else -> Unit
            }

            // 已在跑就不重复起
            if (isAlive()) {
                Log.i(TAG, "宿主已在运行")
                onLog("宿主已在运行")
                return@withContext true
            }

            // 环境就绪检查（Ready 路径跳过了 deploy，Node 版本要在这里把关）
            if (!ensureNodeRuntime(onLog)) return@withContext false

            onLog("启动插件宿主…")

            // 常驻模式：ProcessBuilder 持有 proot 进程（见 hostProcess 注释）
            // —— 不能用 runtime.exec（--kill-on-exit 会把 nohup 的 node 杀掉）。
            try {
                // ⚠️ buildProotCommand 末尾写死 /usr/bin/node（给 MCP 设计，
                // 那时只有 apt 的 18）—— 绝对路径绕过 PATH，升级到
                // /usr/local/bin/node 的 24 也用不上（真机：升级同分钟
                // host.log 仍 v18 SyntaxError）。这里按 PATH 优先级换成
                // 实际要用的 node。
                val preferLocal = java.io.File(runtime.rootfsDir(), "usr/local/bin/node")
                val nodeBin = if (preferLocal.exists()) "/usr/local/bin/node" else "/usr/bin/node"
                val cmd = com.ccm.app.core.mcp.McpInstaller(context, runtime)
                    .buildProotCommand("$HOST_DIR/server.mjs")
                    .map { if (it == "/usr/bin/node") nodeBin else it }
                val pb = java.lang.ProcessBuilder(cmd)
                // ⚠️ 必须补 proot 的 env（PROOT_TMP_DIR 等）—— 缺了直接
                // fatal: can't create temporary file（真机 host.log 实测）
                runtime.applyProotEnv(pb)
                pb.redirectErrorStream(true)
                pb.redirectOutput(
                    java.lang.ProcessBuilder.Redirect.to(File(hostDir(), "host.log")),
                )
                // 旧句柄先杀干净（重复 start 会泄漏进程）
                try { hostProcess?.destroyForcibly() } catch (_: Throwable) {}
                hostProcess = pb.start()
                // 不打 pid —— java.lang.Process.pid() 在 Android 上
                // 受 API 级别限制（CI 实测 Unresolved reference）
                Log.i(TAG, "宿主进程已启动")
            } catch (t: Throwable) {
                onLog("宿主进程创建失败: ${t.message}")
                return@withContext false
            }

            // 等健康检查通过（最多 ~15s；首次加载插件要几秒）
            repeat(15) {
                if (isAlive()) {
                    onLog("宿主已启动（$BASE_URL）")
                    return@withContext true
                }
                delay(1_000)
            }
            // 启动失败把 host.log 尾部捞出来 —— 崩溃栈在里面
            // （实测崩点：ERR_MODULE_NOT_FOUND，node_modules 缺失）
            val logTail = try {
                File(hostDir(), "host.log")
                    .takeIf { it.exists() }
                    ?.readLines()
                    ?.takeLast(6)
                    ?.joinToString(" / ")
                    .orEmpty()
            } catch (_: Throwable) { "" }
            onLog("宿主未就绪${if (logTail.isNotBlank()) "：$logTail" else ""}")
            false
        } catch (t: Throwable) {
            Log.e(TAG, "启动失败", t)
            onLog("启动失败: ${t.message}")
            false
        }
    }

    /** 停止宿主进程 */
    suspend fun stop(onLog: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        try {
            // 先杀持有的常驻进程（常驻模式下 pkill 不一定找得到）
            try {
                hostProcess?.destroyForcibly()
                hostProcess = null
            } catch (_: Throwable) {}
            // 兜底：pkill 按命令匹配（进程句柄丢失/别的实例起的）
            runtime.exec(
                command = listOf("/bin/bash", "-c", "pkill -f 'node server.mjs'; echo KILLED"),
                workDir = "/root",
                onLine = {},
            )
            onLog("宿主已停止")
        } catch (t: Throwable) {
            Log.w(TAG, "停止失败", t)
        }
    }

    /** 健康检查：真发一次 HTTP 请求 */
    suspend fun isAlive(): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = java.net.URL("$BASE_URL$HEALTH_PATH").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Throwable) {
            false
        }
    }
}
