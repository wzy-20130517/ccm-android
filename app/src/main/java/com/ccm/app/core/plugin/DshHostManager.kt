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

        private const val HEALTH_PATH = "/control/status"
    }

    private val runtime = ProotRuntime(context)

    /** 宿主部署目录（rootfs 内）。 */
    private fun hostDir(): File = File(runtime.rootfsDir(), HOST_DIR.removePrefix("/"))

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

    /** 当前状态（快速判断，不做网络健康检查） */
    fun state(): State {
        val hostDir = File(runtime.rootfsDir(), HOST_DIR.removePrefix("/"))
        if (!File(hostDir, "server.mjs").exists()) return State.NotInstalled
        if (!File(hostDir, READY_MARKER).exists()) return State.DepsMissing
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

            // 2. Node 运行时（复用 MCP 安装器：apt nodejs + npm + 修 dpkg）
            onLog("检查 Node 运行时…")
            val mcpInstaller = com.ccm.app.core.mcp.McpInstaller(context, runtime)
            if (!mcpInstaller.ensureNode { s -> onLog(s) }) {
                onLog("Node 安装失败")
                return@withContext false
            }

            // 3. 依赖（120+ 包，首次约几分钟到十几分钟，取决于网络）
            if (!File(hostDir, READY_MARKER).exists()) {
                onLog("安装插件宿主依赖（约 334MB，首次较慢）…")
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
                            "npm ci --omit=dev --no-audit --no-fund 2>&1; " +
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

            onLog("启动插件宿主…")
            // nohup 后台起，输出进日志文件方便排查
            runtime.exec(
                command = listOf(
                    "/bin/bash", "-c",
                    "cd $HOST_DIR && nohup node server.mjs > host.log 2>&1 & " +
                        "sleep 2; echo STARTED",
                ),
                workDir = "/root",
                onLine = { line ->
                    if (line.contains("STARTED")) Log.i(TAG, "host 启动指令已发出")
                },
            )

            // 等健康检查通过（最多 ~10s）
            repeat(10) {
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
            // pkill 按启动命令匹配；node server.mjs 是宿主专属命令
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
