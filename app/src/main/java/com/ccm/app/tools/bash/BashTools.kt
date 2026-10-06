package com.ccm.app.tools.bash

import android.content.Context
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.runtime.ProotRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bash 执行通道抽象 —— 双通道设计的核心。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么是双通道（用户拍板的决策）
 * ══════════════════════════════════════════════════════════════
 *
 * | 通道 | 实现 | 优点 | 缺点 |
 * |---|---|---|---|
 * | **内置 proot** | [ProotChannel]（复用现有 ProotRuntime） | 开箱即用，不依赖外部 App | 首次要装 rootfs（29MB+工具链） |
 * | **外接 Termux** | [TermuxChannel]（Intent 调用） | 用户已有 Termux 环境，零安装 | 需要 Termux 装了 + `allow-external-apps=true` |
 *
 * 首次进入 App 让用户选（UI 归阶段 5），本层只提供抽象 + 探测。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ Termux Intent 的实测要点
 * ══════════════════════════════════════════════════════════════
 *
 * **必须满足三个条件**（缺一则静默失败）：
 * 1. `~/.termux/termux.properties` 里 `allow-external-apps=true`
 * 2. 调用方 APK 声明 `<uses-permission android:name="com.termux.permission.RUN_COMMAND" />`
 * 3. 用 `com.termux.RUN_COMMAND` action + `com.termux/com.termux.app.RunCommandService`
 *
 * **参数名是 `com.termux.RUN_COMMAND_*` 前缀**（不带前缀会被忽略）：
 * ```
 * com.termux.RUN_COMMAND_PATH       = /data/data/com.termux/files/usr/bin/bash
 * com.termux.RUN_COMMAND_ARGUMENTS  = String[] {"-c", "命令"}
 * com.termux.RUN_COMMAND_WORKDIR    = 工作目录
 * com.termux.RUN_COMMAND_BACKGROUND = false
 * com.termux.RUN_COMMAND_SESSION_ACTION = "0"
 * ```
 *
 * **结果回传**：`com.termux.RUN_COMMAND_PENDING_INTENT` 传一个 PendingIntent，
 * Termux 跑完通过它回传 `Bundle{result: Bundle{stdout, stderr, exitCode}}`。
 *
 * 【为什么用 PendingIntent 而不是等进程】Intent 是异步的 ——
 * startService 立刻返回，命令在 Termux 进程里跑。必须靠 PendingIntent
 * 或轮询输出文件拿结果。这里用 PendingIntent + 超时。
 */
sealed interface BashChannel {
    /** 通道名（展示用） */
    val label: String

    /** 当前是否可用 */
    suspend fun isAvailable(): Boolean

    /** 不可用时的原因（可用时返回 null） */
    suspend fun unavailableReason(): String?

    /**
     * 执行命令。
     *
     * @param command shell 命令
     * @param workDir 工作目录（通道内路径）
     * @param timeoutMs 超时
     * @param onLine 输出行回调（实时进度）
     * @return 执行结果
     */
    suspend fun execute(
        command: String,
        workDir: String?,
        timeoutMs: Long,
        onLine: (String) -> Unit,
    ): ExecResult

    data class ExecResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String = "",
        val timedOut: Boolean = false,
    ) {
        val ok: Boolean get() = exitCode == 0 && !timedOut
    }
}

/**
 * 内置 proot 通道 —— 直接复用现有 [ProotRuntime]。
 *
 * ⚠️ **不改 ProotRuntime**：那 660 行里的参数全是真机实测出来的
 * （清 LD_PRELOAD、相对路径 --rootfs=.、--link2symlink、-L、--kill-on-exit、
 * --kernel-release 格式、stdin 重定向 /dev/null…），改错一个就整个环境跑不起来。
 * 这里只做「调用 + 适配」，逻辑一行不加。
 */
class ProotChannel(
    private val context: Context,
    private val runtime: ProotRuntime = ProotRuntime(context),
) : BashChannel {

    override val label = "内置 proot"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            runtime.isReady()
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun unavailableReason(): String? = withContext(Dispatchers.IO) {
        try {
            if (!runtime.isReady()) {
                "内置环境未安装。请先在 App 首页完成「安装运行环境」，或切换到外接 Termux 通道。"
            } else {
                null
            }
        } catch (e: Throwable) {
            "内置环境检查失败：${e.message}"
        }
    }

    override suspend fun execute(
        command: String,
        workDir: String?,
        timeoutMs: Long,
        onLine: (String) -> Unit,
    ): BashChannel.ExecResult = withContext(Dispatchers.IO) {
        // ProotRuntime.execWithTimeout 是阻塞式（内部起看门狗线程），
        // 所以整个调用放到 IO 线程池，不占协程调度器。
        val sb = StringBuilder()
        val work = workDir?.takeIf { it.isNotBlank() } ?: "/root"

        // 用 bash -c 保证复杂命令（管道、重定向）能跑
        val cmd = listOf("/bin/bash", "-c", command)

        val ok = try {
            runtime.execWithTimeout(
                command = cmd,
                workDir = work,
                onLine = { line ->
                    sb.append(line).append('\n')
                    onLine(line)
                },
                timeoutMs = timeoutMs,
                idleMs = 0,   // 不启用静默超时（长任务可能长时间无输出）
            )
        } catch (e: Throwable) {
            return@withContext BashChannel.ExecResult(
                exitCode = -1,
                stdout = sb.toString(),
                stderr = "proot 执行异常：${e.message}",
            )
        }

        val output = sb.toString()
        // ProotRuntime.execWithTimeout 返回 Boolean（是否成功跑完），
        // 超时/失败时它会把原因写进输出（"❌ 命令被强制结束：..."）
        val timedOut = output.contains("命令被强制结束")

        BashChannel.ExecResult(
            exitCode = if (ok) 0 else 1,
            stdout = output,
            timedOut = timedOut,
        )
    }
}

/**
 * 外接 Termux 通道 —— 通过 `com.termux.RUN_COMMAND` Intent 调用。
 *
 * 依赖 Termux 侧 `allow-external-apps=true`（用户这台已确认开启）。
 */
class TermuxChannel(private val context: Context) : BashChannel {

    companion object {
        /**
         * RUN_COMMAND 权限名。
         *
         * 【2026-10-06 重要纠正】这个权限是 **dangerous 级**（不是 signature）——
         * Termux 的 AndroidManifest 写的是 `android:protectionLevel="dangerous"`。
         * 所以**可以运行时请求**（弹系统授权框），不需要与 Termux 同签名。
         * 实测 `pm grant com.ccm.app com.termux.permission.RUN_COMMAND` 直接成功。
         */
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        /** 检查权限是否已授予。 */
        fun hasRunCommandPermission(context: android.content.Context): Boolean =
            context.checkSelfPermission(PERMISSION_RUN_COMMAND) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

        const val TERMUX_PACKAGE = "com.termux"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val SERVICE_CLASS = "com.termux.app.RunCommandService"
        const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        /** 结果回传用的广播 action（自定义，只有我们自己监听） */
        const val ACTION_RESULT = "com.ccm.app.TERMUX_COMMAND_RESULT"

        /** Termux 里的 bash 路径 */
        private const val TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
    }

    override val label = "外接 Termux"

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            // 【2026-10-06】还要求 RUN_COMMAND 权限 —— 装了 Termux 但没授权
            // 时 isAvailable 不该报 true（否则工具注册了、一调用就失败）。
            hasRunCommandPermission()
        } catch (_: Throwable) {
            false
        }
    }

    /** 实例方法版（内部用）。 */
    private fun hasRunCommandPermission(): Boolean =
        hasRunCommandPermission(context)

    override suspend fun unavailableReason(): String? = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext "未安装 Termux。请从 F-Droid 安装 Termux，或切换到内置 proot 通道。"
        }
        // allow-external-apps 无法从外部直接读取（Termux 私有目录），
        // 只能提示用户在失败时检查
        null
    }

    override suspend fun execute(
        command: String,
        workDir: String?,
        timeoutMs: Long,
        onLine: (String) -> Unit,
    ): BashChannel.ExecResult = withContext(Dispatchers.IO) {
        // 【2026-10-06】权限前置检查 —— 比让它抛 SecurityException 再猜原因清楚得多。
        // 权限是 dangerous 级：设置 → 应用 → CCM → 权限 → 在 Termux 中运行命令
        // （或环境 tab 的授权按钮）。
        if (!hasRunCommandPermission()) {
            return@withContext BashChannel.ExecResult(
                exitCode = -1,
                stdout = "",
                stderr = "缺少权限：在 Termux 中运行命令（com.termux.permission.RUN_COMMAND）\n" +
                    "授权方式：设置 → 环境 → 「外接 Termux」行的授权按钮（弹系统框），" +
                    "或系统设置 → 应用 → CCM → 权限 → 在 Termux 中运行命令。\n" +
                    "_（这个权限是 dangerous 级，弹框授予即可，不需要与 Termux 同签名）_",
            )
        }

        // ⚠️ Termux 的 Intent 是**异步**的：startService 立即返回，命令在 Termux 进程跑。
        // 拿结果必须靠 PendingIntent 回传（官方推荐方式）。
        //
        // 【为什么用 PendingIntent 而不是自己注册 BroadcastReceiver】
        // ① 官方 API 就是这个：`com.termux.RUN_COMMAND_PENDING_INTENT`
        // ② 自己注册 receiver 在 Android 13+ 要显式指定 exported 标志，
        //    在 Android 14+ 对隐式广播限制更多 —— 而 PendingIntent 由系统投递，无此问题
        // ③ Termux 会在命令**结束**时才回传，天然是「等结果」的语义
        val resultHolder = java.util.concurrent.ArrayBlockingQueue<android.os.Bundle>(1)

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: android.content.Intent?) {
                resultHolder.offer(intent?.extras ?: android.os.Bundle())
            }
        }

        // Android 13+ 要求显式声明 receiver 可见性；低版本没有这个 API
        val registered = try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                    receiver,
                    android.content.IntentFilter(ACTION_RESULT),
                    Context.RECEIVER_NOT_EXPORTED,
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, android.content.IntentFilter(ACTION_RESULT))
            }
            true
        } catch (_: Throwable) {
            false
        }

        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            0,
            android.content.Intent(ACTION_RESULT).setPackage(context.packageName),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0),
        )

        val intent = android.content.Intent().apply {
            action = ACTION_RUN_COMMAND
            setClassName(TERMUX_PACKAGE, SERVICE_CLASS)
            putExtra(EXTRA_PATH, TERMUX_BASH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, workDir?.takeIf { it.isNotBlank() } ?: TERMUX_HOME)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_SESSION_ACTION, "0")
            putExtra(EXTRA_COMMAND_LABEL, "CCM")
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            context.startService(intent)
        } catch (e: Throwable) {
            if (registered) runCatching { context.unregisterReceiver(receiver) }
            return@withContext BashChannel.ExecResult(
                exitCode = -1,
                stdout = "",
                stderr = "无法调用 Termux：${e.message}\n" +
                    "可能原因：\n" +
                    "① Termux 未设置 allow-external-apps=true —— " +
                    "在 ~/.termux/termux.properties 里加一行 allow-external-apps=true，" +
                    "然后执行 termux-reload-settings（或重启 Termux）\n" +
                    "② **RUN_COMMAND 权限未授予** —— 报 \"without permission " +
                    "com.termux.permission.RUN_COMMAND\" 就是这个。该权限是 dangerous 级，" +
                    "去 设置 → 环境 → 「外接 Termux」行点授权（弹系统框）\n" +
                    "③ Android 8+ 对后台 startService 有限制 —— 先手动打开一次 Termux 再试\n" +
                    "④ 未安装 Termux",
            )
        }

        // 等结果（带超时）
        var result: android.os.Bundle? = null
        try {
            result = resultHolder.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
        }
        if (registered) runCatching { context.unregisterReceiver(receiver) }

        if (result == null) {
            return@withContext BashChannel.ExecResult(
                exitCode = -1,
                stdout = "",
                stderr = "Termux 命令超时（${timeoutMs}ms）或结果回传失败。\n" +
                    "提示：Termux 通道的结果回传依赖 PendingIntent，" +
                    "若持续失败请改用内置 proot 通道。",
                timedOut = true,
            )
        }

        // Termux 回传格式：Bundle{result: Bundle{stdout, stderr, exitCode}}
        val inner = result.getBundle("result") ?: result
        val stdout = inner.getString("stdout", "") ?: ""
        val stderr = inner.getString("stderr", "") ?: ""
        val exit = inner.getInt("exitCode", -1)
        stdout.split("\n").forEach { if (it.isNotEmpty()) onLine(it) }

        BashChannel.ExecResult(exitCode = exit, stdout = stdout, stderr = stderr)
    }
}

/**
 * Bash 工具 —— 双通道执行 shell 命令。
 *
 * 参照 Node 版 `core/` 里的 Bash 工具 + `tool-timeout.mjs` 的超时分级。
 *
 * ══════════════════════════════════════════════════════════════
 *  超时分级（对齐 CCM 的实测值，不要凭感觉改）
 * ══════════════════════════════════════════════════════════════
 *
 * | 类型 | 超时 | 依据 |
 * |---|---|---|
 * | 默认 | 120s | 一般命令 |
 * | 用户可传 | 最长 600s | 构建/安装 |
 *
 * ⚠️ **不要跨层叠加重试**（CCM 血泪教训）：
 * 一条命令失败重试 3 次 × 每次 120s = 6 分钟，用户看到的是「卡死」。
 * 这里**不做自动重试** —— 失败就返回，让模型决定要不要重试。
 *
 * @param channel 当前通道（由 UI 选择后注入）
 * @param fallbackChannel 备用通道（主通道不可用时自动降级，可为 null）
 */
class BashTool(
    private val channel: BashChannel,
    private val fallbackChannel: BashChannel? = null,
) : Tool() {

    override val name = "Bash"
    // 【2026-10-06 envMode】描述里写明命令跑在**哪个环境** ——
    // 两个通道的文件系统是隔离的（proot 在 App 私有目录里，
    // Termux 在 Termux 自己的 home），模型不知道就会写错路径：
    // 比如 proot 里写 /sdcard/xxx（bind 进来的是同一个物理目录，OK），
    // Termux 里写 /root/xxx（不存在，Termux 的 home 是
    // /data/data/com.termux/files/home）。按通道动态生成。
    override val description: String =
        "执行 shell 命令。支持 timeout；run_in_background:true 后台跑并返回 task_id。" +
            "当前环境：${channel.label} —— " + when (channel.label) {
                "内置 proot" ->
                    "命令跑在 App 内置的 Ubuntu（rootfs）里，工作区默认 /root 或 /mnt/ext；" +
                        "手机存储挂在 /sdcard（与 Android 是同一个物理目录，读写删改都行，" +
                        "但**不支持符号链接/硬链接/chmod** —— git 仓库、node_modules、" +
                        "解压带链接的 tar 别放上面）。装包用 apt。"
                "外接 Termux" ->
                    "命令跑在用户的 Termux 里（/data/data/com.termux/files/home），" +
                        "手机存储在 /sdcard（Termux 已授权），装包用 pkg 或 apt（Termux 自带）。" +
                        "App 私有目录（/data/data/com.ccm.app）Termux **访问不了** —— " +
                        "跨环境文件放 /sdcard 下的公共位置。"
                else -> "命令跑在 ${channel.label} 里。"
            }
    override val isReadOnly = false
    override val isDestructive = true
    override val isConcurrencySafe = false
    override val maxResultSizeChars = 30_000

    override val inputSchema: JsonObject = ToolSchema.objectSchema(
        "command" to ToolSchema.string("要执行的 shell 命令"),
        "timeout" to ToolSchema.integer("超时毫秒（默认 120000，最长 600000）", minimum = 1000, maximum = 600_000),
        "run_in_background" to ToolSchema.boolean("后台执行，立即返回 task_id"),
        "description" to ToolSchema.string("命令用途的简短说明（用于日志）"),
        required = listOf("command"),
    )

    override fun validateInput(input: JsonObject): String? =
        if (input.str("command").isNullOrBlank()) "command is required" else null

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val command = input.str("command")!!
        val timeout = (input.int("timeout") ?: 120_000).coerceIn(1_000, 600_000)

        // 后台任务：交给 BackgroundShells 管理
        if (input.bool("run_in_background") == true) {
            return BackgroundShells.start(channel, command, ctx)
        }

        // 选通道：主通道不可用则降级
        var ch = channel
        if (!ch.isAvailable()) {
            val reason = ch.unavailableReason() ?: "主通道不可用"
            val fb = fallbackChannel
            if (fb != null && fb.isAvailable()) {
                ch = fb
                ctx.ui.onProgress("主通道不可用（$reason），已切到 ${fb.label}")
            } else {
                return ToolResult.Error(
                    "Bash 通道不可用：$reason" +
                        (if (fb != null) "\n备用通道（${fb.label}）也不可用：${fb.unavailableReason() ?: "未知"}" else ""),
                    ToolResult.INTERNAL,
                )
            }
        }

        val lines = mutableListOf<String>()
        var lastProgressAt = 0L
        var result = try {
            // ⚠️ timeout 是 Int（来自 input.int），execute 要 Long —— 必须显式转换。
            ch.execute(command, ctx.cwd, timeout.toLong()) { line ->
                lines += line
                val now = System.currentTimeMillis()
                if (now - lastProgressAt > 2_000) lastProgressAt = now
            }
        } catch (e: Throwable) {
            return ToolResult.Error("执行失败：${e.message}", ToolResult.INTERNAL)
        }

        // 仅启动级故障回退。普通非零退出码/超时绝不重跑，避免写命令执行两次。
        val launchFailure = result.exitCode < 0 || result.stderr.startsWith("proot 执行异常：")
        if (launchFailure && ch === channel && fallbackChannel != null && fallbackChannel.isAvailable()) {
            ctx.ui.onProgress("内置 proot 启动失败，改用 ${fallbackChannel.label} 重试一次")
            lines.clear()
            ch = fallbackChannel
            result = try {
                ch.execute(command, ctx.cwd, timeout.toLong()) { line -> lines += line }
            } catch (e: Throwable) {
                return ToolResult.Error("主通道启动失败，备用通道也失败：${e.message}", ToolResult.INTERNAL)
            }
        }

        if (ctx.isCancelled) return ToolResult.cancelled()

        val output = buildString {
            append("通道: ${ch.label}\n")
            if (result.stdout.isNotEmpty()) append(result.stdout)
            if (result.stderr.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("--- stderr ---\n").append(result.stderr)
            }
            if (isEmpty()) append("(无输出)")
        }

        return when {
            result.timedOut -> ToolResult.failed("命令超时（${timeout / 1000}s）\n$output")
            result.exitCode != 0 -> ToolResult.failed("exit=${result.exitCode}\n$output")
            else -> ToolResult.ok(output)
        }
    }
}

/**
 * 后台 shell 任务管理 —— 对应 Node 版的 `/bg-list` `/bg-status`。
 *
 * 【为什么单独一个 object】后台任务的生命周期独立于工具调用（跨轮存活），
 * 不能塞在工具实例里。用进程内单例 + 有界输出缓冲。
 */
object BackgroundShells {

    /** 单个后台任务 */
    class Task(
        val id: String,
        val command: String,
        val channelLabel: String,
        val startedAt: Long = System.currentTimeMillis(),
    ) {
        @Volatile var status: String = "running"   // running | completed | failed | killed
        @Volatile var exitCode: Int = -1
        val output = StringBuilder()
        @Volatile var finishedAt: Long = 0L

        @Synchronized
        fun append(line: String) {
            output.append(line).append('\n')
            // 有界：保留最后 200KB，防长跑任务把内存吃光
            if (output.length > 200_000) {
                output.delete(0, output.length - 200_000)
            }
        }

        @Synchronized
        fun tail(maxChars: Int = 4_000): String {
            val s = output.toString()
            return if (s.length <= maxChars) s else "…（省略 ${s.length - maxChars} 字符）\n" + s.takeLast(maxChars)
        }

        fun elapsedMs(): Long = (if (finishedAt > 0) finishedAt else System.currentTimeMillis()) - startedAt
    }

    private val counter = AtomicInteger(0)
    private val tasks = ConcurrentHashMap<String, Task>()

    /**
     * 后台任务专用作用域。
     *
     * ⚠️ 不用 `GlobalScope` —— 它已废弃，且无法取消（任务泄漏）。
     * 这里用 `SupervisorJob` + `Dispatchers.IO`：
     * - Supervisor：一个任务失败不影响其他任务
     * - 独立作用域：不绑定调用方生命周期（工具调用返回后任务要继续跑）
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 启动后台任务 */
    suspend fun start(channel: BashChannel, command: String, ctx: ToolContext): ToolResult {
        val id = "bg-${System.currentTimeMillis() % 1_000_000}-${counter.incrementAndGet()}"
        val task = Task(id = id, command = command, channelLabel = channel.label)
        tasks[id] = task

        // 在独立作用域跑（不阻塞工具返回，也不随调用方取消而中止）
        scope.launch {
            try {
                val r = channel.execute(command, ctx.cwd, 600_000) { line ->
                    task.append(line)
                }
                task.exitCode = r.exitCode
                task.status = if (r.ok) "completed" else "failed"
            } catch (e: Throwable) {
                task.append("异常：${e.message}")
                task.status = "failed"
            } finally {
                task.finishedAt = System.currentTimeMillis()
            }
        }

        return ToolResult.ok(
            "后台任务已启动\n" +
                "task_id: $id\n" +
                "通道: ${channel.label}\n" +
                "命令: ${command.take(200)}\n\n" +
                "用 BashOutput({task_id:\"$id\"}) 读输出，KillShell({task_id:\"$id\"}) 终止。",
        )
    }

    fun get(id: String): Task? = tasks[id]

    fun list(): List<Task> = tasks.values.sortedByDescending { it.startedAt }

    fun remove(id: String): Boolean = tasks.remove(id) != null

    /** 清理已结束超过 30 分钟的任务 */
    fun cleanup() {
        val cutoff = System.currentTimeMillis() - 30 * 60_000L
        tasks.entries.removeAll { (_, t) ->
            t.finishedAt > 0 && t.finishedAt < cutoff
        }
    }
}

/**
 * BashOutput —— 读后台任务输出。
 */
class BashOutputTool : Tool() {
    override val name = "BashOutput"
    override val description = "读取后台 Bash 任务的输出（增量）。参数 task_id"
    override val isReadOnly = true
    override val isConcurrencySafe = true
    override val maxResultSizeChars = 20_000

    override val inputSchema: JsonObject = ToolSchema.objectSchema(
        "task_id" to ToolSchema.string("后台任务 id（Bash 工具 run_in_background 时返回）"),
        required = listOf("task_id"),
    )

    override fun validateInput(input: JsonObject): String? =
        if (input.str("task_id").isNullOrBlank()) "task_id is required" else null

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val id = input.str("task_id")!!
        val task = BackgroundShells.get(id)
            ?: return ToolResult.notFound("找不到后台任务：$id（可能已清理）")

        return ToolResult.ok(
            buildString {
                append("task_id: ${task.id}\n")
                append("状态: ${task.status}")
                if (task.exitCode >= 0) append("（exit=${task.exitCode}）")
                append("\n耗时: ${task.elapsedMs() / 1000}s\n")
                append("命令: ${task.command.take(200)}\n")
                append("--- 输出（尾部）---\n")
                append(task.tail())
            },
        )
    }
}

/**
 * KillShell —— 终止后台任务。
 */
class KillShellTool : Tool() {
    override val name = "KillShell"
    override val description = "终止后台 Bash 任务。"
    override val isDestructive = true
    override val isConcurrencySafe = false
    override val maxResultSizeChars = 500

    override val inputSchema: JsonObject = ToolSchema.objectSchema(
        "task_id" to ToolSchema.string("要终止的后台任务 id"),
        required = listOf("task_id"),
    )

    override fun validateInput(input: JsonObject): String? =
        if (input.str("task_id").isNullOrBlank()) "task_id is required" else null

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val id = input.str("task_id")!!
        val task = BackgroundShells.get(id)
            ?: return ToolResult.notFound("找不到后台任务：$id")

        // ⚠️ 真正的进程终止需要通道支持（proot 的 destroyForcibly / Termux 的 kill）。
        // 当前实现标记状态并移除任务；实际进程会在超时后自行结束。
        task.status = "killed"
        task.finishedAt = System.currentTimeMillis()
        BackgroundShells.remove(id)

        return ToolResult.ok("已标记终止：$id（进程会在通道超时后自行结束）")
    }
}
