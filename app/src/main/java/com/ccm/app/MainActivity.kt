package com.ccm.app

import android.app.Activity
import android.content.Intent
import java.io.File
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ccm.app.runtime.ProotRuntime
import com.ccm.app.runtime.ToolchainCatalog
import com.ccm.app.runtime.RootfsManager
import com.ccm.app.service.CcmAccessibilityService
import com.ccm.app.service.CcmService
import com.ccm.app.tools.ScreenCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CcmApp() }
    }

    /** 截屏授权回调 */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCapture.REQUEST_CODE) {
            val ok = ScreenCapture.init(this, resultCode, data)
            Log.i("MainActivity", if (ok) "截屏授权成功" else "截屏授权失败")
        }
    }
}

/** 界面状态 */
enum class Stage { CHECKING, NEED_SETUP, TOOLCHAIN_PICK, SETTING_UP, READY, WEBVIEW, TOOLCHAIN_MANAGE }

/** 运行状态（供 UI 显示） */
data class RuntimeState(
    val a11yOn: Boolean = false,
    val captureOn: Boolean = false,
    val serviceRunning: Boolean = false,
    val nodeRunning: Boolean = false,
    val webReady: Boolean = false,
)

@Composable
fun CcmApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf(Stage.CHECKING) }
    // 用户勾选的工具链
    var selectedChains by remember { mutableStateOf(ToolchainCatalog.defaultSelection()) }
    var log by remember { mutableStateOf("") }
    /**
     * 工具链安装失败标志。
     *
     * 【为什么需要】安装失败时不能像原来那样「覆盖日志 + 自动跳页」——
     * 用户根本看不到是哪一步错了。现在失败时停在本页，
     * 由这个标志控制「返回」按钮的出现（用户主动走）。
     */
    var toolchainFailed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var runtime by remember { mutableStateOf(RuntimeState()) }
    // proot 自检结果。null=未跑或通过；非 null=诊断文本。
    var prootCheckError by remember { mutableStateOf<String?>(null) }
    // 工具链实测结果。null=正在验证；非 null=已验证的集合。
    // 打开「管理工具」时异步跑（见 ToolchainManageScreen 的调用点）。
    var verifiedChains by remember { mutableStateOf<Set<String>?>(null) }
    // 自检只在「rootfs 已装 + 还没跑过」时执行一次（探针要起进程，别每帧跑）
    var prootChecked by remember { mutableStateOf(false) }

    val rootfs = remember { RootfsManager(ctx) }
    val proot = remember { ProotRuntime(ctx) }

    // 启动时检查环境
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            delay(200)
            stage = if (rootfs.isInstalled()) Stage.READY else Stage.NEED_SETUP
        }
    }

    // proot 自检：rootfs 装好后跑一次。
    //
    // 【为什么值得单独一段逻辑】proot 起不来时的报错极具误导性
    // （"Function not implemented" 看着像 rootfs 里的二进制坏了，实际是
    //  proot 自己的 loader 找不到）。在这里跑一次探针，把真实诊断提前显示，
    // 用户就不用等到点「安装工具链」失败了才知道。
    LaunchedEffect(stage) {
        if (stage != Stage.READY || prootChecked) return@LaunchedEffect
        prootChecked = true
        prootCheckError = withContext(Dispatchers.IO) {
            try { proot.selfCheck() } catch (t: Throwable) { "自检异常：${t.message}" }
        }
    }

    // 定期刷新运行状态（无障碍/截屏/服务/Node）
    LaunchedEffect(Unit) {
        while (true) {
            val webOk = withContext(Dispatchers.IO) { pingWeb() }
            runtime = runtime.copy(
                a11yOn = CcmAccessibilityService.isConnected(),
                captureOn = ScreenCapture.isReady(),
                serviceRunning = CcmService.isRunning,
                webReady = webOk,
                // Node 进程是否活着：用 web 端口是否能连来推断
                nodeRunning = webOk,
            )
            delay(2000)
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (stage) {
                Stage.CHECKING -> CenterBox { CircularProgressIndicator() }

                Stage.NEED_SETUP -> SetupScreen(
                    onInstall = {
                        stage = Stage.TOOLCHAIN_PICK
                    }
                )

                Stage.TOOLCHAIN_PICK -> ToolchainPickerScreen(
                    selected = selectedChains,
                    onToggle = { id ->
                        selectedChains = if (id in selectedChains) selectedChains - id
                                         else selectedChains + id
                    },
                    onConfirm = {
                        stage = Stage.SETTING_UP
                        scope.launch {
                            try {
                                // ① 下载 + 解压 rootfs
                                val ok = withContext(Dispatchers.IO) {
                                    rootfs.install { phase, done, total ->
                                        val pct = if (total > 0) done.toFloat() / total else 0f
                                        // ⚠️ 回调在 IO 线程，改 state 必须回主线程
                                        scope.launch {
                                            progress = when (phase) {
                                                "connecting" -> 0.01f      // 给一点点进度，别停在 0
                                                "download" -> 0.02f + pct * 0.53f
                                                "extract" -> 0.55f + pct * 0.35f
                                                "retry" -> progress        // 保持当前进度
                                                else -> 0.90f
                                            }
                                            log = when (phase) {
                                                "connecting" ->
                                                    "正在连接服务器…（首次下载约 28MB，请耐心等待）"
                                                "download" -> {
                                                    val mb = done / 1024 / 1024
                                                    val totalMb = total / 1024 / 1024
                                                    if (totalMb > 0) "下载 Linux 环境… ${mb}MB / ${totalMb}MB（${(pct * 100).toInt()}%）"
                                                    else "下载 Linux 环境… ${mb}MB"
                                                }
                                                "extract" ->
                                                    "解压中… ${(pct * 100).toInt()}%（约需 1~2 分钟）"
                                                "retry" ->
                                                    "下载中断，正在重试（第 ${done}/${total} 轮）…"
                                                else -> "配置环境…"
                                            }
                                        }
                                    }
                                }
                                if (!ok) {
                                    log = "❌ 环境安装失败。请检查网络后重试。"
                                    stage = Stage.NEED_SETUP
                                    return@launch
                                }

                                // ② 装工具链（用户勾选的那些，含 Node）
                                //
                                // 【2026-09-23 修】原来每行日志都 `log = line.takeLast(70)`，
                                // 屏幕只留最后一行 —— apt 在几百行里打的错误信息全被冲掉，
                                // 用户只看到「完成」，根本不知道哪个包失败了。
                                //
                                // 【2026-09-24 二次修：不再截断行数】
                                //
                                // 之前保留「最近 8 行」（后改 30 行），出发点是别让
                                // StringBuilder 无限涨。但配合「日志区自动滚到底」，
                                // 结果是**只能看到最新几行，往上翻是空的** ——
                                // 用户反馈「日志显示根本不完整，吐一步前面都没了」。
                                //
                                // 正确做法：**日志全留**，让显示区的滚动条负责历史。
                                // 内存代价可控：几百行 × 每行 ~100 字符 ≈ 几十 KB。
                                var toolLog = StringBuilder()
                                val appendLog: (String) -> Unit = { line ->
                                    scope.launch {
                                        toolLog.append(line).append('\n')
                                        log = toolLog.toString().trimEnd()
                                    }
                                }
                                progress = 0.88f
                                log = "安装工具链…"
                                val tok = withContext(Dispatchers.IO) {
                                    rootfs.installToolchains(
                                        selected = selectedChains,
                                        exec = { cmd, cb ->
                                            proot.exec(cmd, "/root") { line ->
                                                appendLog(line)
                                                cb(line)
                                            }
                                        },
                                        onLine = appendLog
                                    )
                                }

                                // ③ 装内核
                                progress = 0.96f
                                appendLog("")
                                appendLog("安装 Node 内核…")
                                val kok = withContext(Dispatchers.IO) {
                                    rootfs.installKernel { done, total ->
                                        val pct = if (total > 0) done.toFloat() / total else 0f
                                        scope.launch { progress = 0.96f + pct * 0.04f }
                                    }
                                }

                                progress = 1f
                                // 【2026-09-23】失败要说清楚失败在哪，别一律「✅ 完成」。
                                // 用户反馈过：「勾选了几个包，只装了 linux，其他根本没下，
                                // 重新进入又要下一遍」—— 就是因为这里没报失败。
                                // 【2026-09-24 修】原来无论成败都覆盖 log 再自动跳走：
                                //     log = when { ... }        ← 覆盖掉几百行日志
                                //     delay(1200); stage = READY ← 想截图都来不及
                                // 用户反馈「弹了『部分失败』后就清屏，且自动退出日志页」。
                                //
                                // 失败时最需要日志（要截图反馈），偏偏那时候把页面收走。
                                // 现在：成功照旧自动走；失败停住，保留完整日志，
                                // 底部出「返回」按钮，用户主动走。
                                val allOk = tok && kok
                                if (allOk) {
                                    log = "✅ 全部完成"
                                    delay(1200)
                                    stage = Stage.READY
                                } else {
                                    val summary = when {
                                        !tok && !kok -> "❌ 工具链和内核都没装成功"
                                        !tok -> "⚠️ 工具链没装全"
                                        else -> "⚠️ Node 内核没装上"
                                    }
                                    toolLog.append('\n').append(summary)
                                    if (!tok) toolLog.append('\n').append("   已安装的不会重复安装，可稍后重试补装缺失项。")
                                    if (!kok) toolLog.append('\n').append("   可在主界面点「更新 Node 内核」重试。")
                                    log = toolLog.toString().trimEnd()
                                    toolchainFailed = true   // 停在本页，等用户看完
                                }
                            } catch (e: Throwable) {
                                log = "❌ 出错: ${e.message}\n\n（日志已保留在上方）"
                                toolchainFailed = true
                            }
                        }
                    }
                )

                Stage.SETTING_UP -> InstallingScreen(
                    progress = progress,
                    log = log,
                    failed = toolchainFailed,
                    onBack = {
                        // 用户看完日志主动返回 —— 顺便清掉失败标志，下次安装重新开始
                        toolchainFailed = false
                        stage = Stage.READY
                    },
                )

                Stage.READY -> ReadyScreen(
                    runtime = runtime,
                    rootfsPath = rootfs.rootfsPath.absolutePath,
                    hasNode = proot.hasNode(),
                    kernelInstalled = rootfs.isKernelInstalled(),
                    onOpenA11ySettings = {
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onRequestCapture = {
                        try {
                            val mgr = ctx.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE)
                                    as MediaProjectionManager
                            (ctx as? Activity)?.startActivityForResult(
                                mgr.createScreenCaptureIntent(), ScreenCapture.REQUEST_CODE
                            )
                        } catch (t: Throwable) {
                            Log.e("MainActivity", "请求截屏失败", t)
                        }
                    },
                    onStartService = {
                        try {
                            ctx.startForegroundService(Intent(ctx, CcmService::class.java))
                            log = ""
                        } catch (t: Throwable) {
                            Log.e("MainActivity", "启动服务失败", t)
                        }
                    },
                    onStartNode = {
                        try {
                            ctx.startForegroundService(
                                Intent(ctx, CcmService::class.java).apply {
                                    action = com.ccm.app.service.ACTION_START_NODE
                                }
                            )
                        } catch (t: Throwable) {
                            Log.e("MainActivity", "启动 Node 失败", t)
                        }
                    },
                    onOpenWeb = { stage = Stage.WEBVIEW },
                    onManageToolchains = { stage = Stage.TOOLCHAIN_MANAGE },
                    prootCheckError = prootCheckError,
                    onUpdateKernel = {
                        // 复用安装流水线的进度界面：先删旧内核包让 installKernel 重新下载
                        stage = Stage.SETTING_UP
                        scope.launch {
                            try {
                                progress = 0.05f
                                log = "更新 Node 内核…"
                                File(ctx.filesDir, "kernel.tar.gz").delete()
                                val ok = withContext(Dispatchers.IO) {
                                    rootfs.installKernel { done, total ->
                                        val pct = if (total > 0) done.toFloat() / total else 0f
                                        scope.launch {
                                            progress = 0.1f + pct * 0.85f
                                            log = "下载内核… ${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB"
                                        }
                                    }
                                }
                                log = if (ok) {
                                    progress = 1f
                                    "✅ 内核已更新。点「重启 Node 内核」让它生效。"
                                } else {
                                    "❌ 内核更新失败。检查网络后重试。"
                                }
                            } catch (t: Throwable) {
                                log = "❌ 内核更新出错：${t.message}"
                            }
                            stage = Stage.READY
                        }
                    }
                )

                Stage.WEBVIEW -> WebViewScreen(
                    webReady = runtime.webReady,
                    onBack = { stage = Stage.READY }
                )

                Stage.TOOLCHAIN_MANAGE -> {
                    // 【实测验证】打开界面时异步跑一次 —— 记录文件可能不准
                    // （中途失败/用户手删/rootfs 部分损坏），见 verifyInstalledToolchains 的说明。
                    // 验证期间 verifiedChains 为 null，界面显示加载态。
                    LaunchedEffect(stage) {
                        if (verifiedChains == null) {
                            val v = withContext(Dispatchers.IO) {
                                try {
                                    rootfs.verifyInstalledToolchains(
                                        exec = { cmd, cb -> proot.exec(cmd, "/root", cb) }
                                    )
                                } catch (t: Throwable) {
                                    Log.w("MainActivity", "工具链验证失败，退回记录值", t)
                                    rootfs.installedToolchains()
                                }
                            }
                            verifiedChains = v
                        }
                    }
                    ToolchainManageScreen(
                        installed = verifiedChains ?: rootfs.installedToolchains(),
                        verifying = verifiedChains == null,
                        onBack = { stage = Stage.READY },
                        onInstall = { chains ->
                            stage = Stage.SETTING_UP
                            scope.launch {
                                // 【2026-09-24 修】这里原来是
                                //     scope.launch { log = line.takeLast(70) }
                                // 的重复写法，有两个毛病：
                                //   ① `log = ` 是**覆盖**不是追加 —— 屏幕只显示最后一行
                                //   ② takeLast(70) 把行截断，apt 的报错（常带 URL 和包名）
                                //      被砍掉一半，配上「覆盖」行为，用户几乎看不到有效信息
                                //
                                // 这与「首次安装」那条路径（约 192 行）原来犯的是同一个错，
                                // 那边 2026-09-23 修过（改成保留最近 N 行），但**这条路径漏了**
                                // ——「管理工具」里重装工具链走的就是这里。
                                //
                                // 现在两边统一：**日志全留**，不截断行数。
                                // 显示区自动滚到底负责「看最新」，滚动条负责「翻历史」。
                                var toolLog = StringBuilder()
                                val appendLog: (String) -> Unit = { line ->
                                    scope.launch {
                                        toolLog.append(line).append('\n')
                                        log = toolLog.toString().trimEnd()
                                    }
                                }
                                try {
                                    val ok = withContext(Dispatchers.IO) {
                                        rootfs.installToolchains(
                                            selected = chains,
                                            exec = { cmd, cb ->
                                                proot.exec(cmd, "/root") { line ->
                                                    appendLog(line)
                                                    cb(line)
                                                }
                                            },
                                            onLine = appendLog
                                        )
                                    }
                                    // 【2026-09-24 修】原来是：
                                    //     log = if (ok) "✅ 安装完成" else "⚠️ 部分失败"
                                    //     delay(800); stage = Stage.READY
                                    // 两个问题叠加，用户根本看不到日志：
                                    //   ① `log = ` 把累积的几百行日志**整个覆盖**掉
                                    //   ② 800ms 后自动切页面 —— 想截图都来不及
                                    // 用户反馈：「弹了『部分失败』后就清屏，且自动退出日志页」。
                                    //
                                    // 现在：
                                    //   · 成功 → 覆盖成一行「✅ 安装完成」，1.2 秒后回主界面（正常）
                                    //   · 失败 → **保留完整日志**，停在本页等用户看完，
                                    //            底部出现「返回」按钮（用户主动走）
                                    if (ok) {
                                        log = "✅ 安装完成"
                                        delay(1200)
                                        stage = Stage.READY
                                    } else {
                                        toolLog.append('\n').append("⚠️ 部分工具未安装完成。")
                                        toolLog.append('\n').append("   已安装的不会重复安装，可稍后重试补装缺失项。")
                                        log = toolLog.toString().trimEnd()
                                        toolchainFailed = true   // 触发底部的「返回」按钮
                                    }
                                } catch (e: Throwable) {
                                    toolLog.append('\n').append("❌ 出错: ${e.message}")
                                    log = toolLog.toString().trimEnd()
                                    toolchainFailed = true
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 探测 Web 服务是否就绪 */
private fun pingWeb(): Boolean {
    return try {
        val conn = (URL("http://127.0.0.1:3456/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 1200
            readTimeout = 1200
            requestMethod = "GET"
        }
        val code = conn.responseCode
        conn.disconnect()
        code in 200..499
    } catch (t: Throwable) {
        false
    }
}

@Composable
fun CenterBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) { content() }
}

@Composable
fun SetupScreen(onInstall: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Claude Code Mobile 首次启动", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "需要安装 Linux 运行环境：\n\n" +
            "• Ubuntu 24.04 base（约 28MB）\n" +
            "• Node.js 运行时（约 50MB，apt 下载）\n" +
            "• Claude Code Mobile 内核（约 14MB）\n\n" +
            "环境用于运行 AI 的工具链（Node / git / curl 等）。\n" +
            "首次安装约 3~8 分钟，取决于网速。",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onInstall) { Text("开始安装") }
    }
}

/**
 * 工具链选择界面。
 *
 * 【设计】
 * - 分组展示（基础 / 语言 / 工具），每组一行
 * - 必装的（Node）显示为禁用勾选状态
 * - 底部实时显示预估总大小
 * - 依赖关系：勾了 cmake 会自动带上 build（在 resolveSelection 里处理）
 */
@Composable
fun ToolchainPickerScreen(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit
) {
    val totalMB = ToolchainCatalog.estimatedSizeMB(selected)
    val chains = ToolchainCatalog.resolveSelection(selected)

    Column(modifier = Modifier.fillMaxSize()) {
        // 标题栏
        Column(modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 8.dp)) {
            Text("选择要安装的工具", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                "已选 ${chains.size} 组，约 ${totalMB}MB。" +
                "不选也能用（AI 核心功能不受影响），以后可以随时在设置里加装。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 列表
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(ToolchainCatalog.ALL) { tc ->
                val checked = tc.id in selected
                val locked = tc.id == "nodejs"   // Node 是内核必需，不能取消
                ToolchainRow(
                    toolchain = tc,
                    checked = checked,
                    locked = locked,
                    onToggle = { if (!locked) onToggle(tc.id) }
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        // 底部按钮
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { onConfirm() },
                modifier = Modifier.weight(1f)
            ) { Text("用默认的") }

            Button(
                onClick = { onConfirm() },
                modifier = Modifier.weight(1f)
            ) { Text("开始安装") }
        }
    }
}

@Composable
fun ToolchainRow(
    toolchain: ToolchainCatalog.Toolchain,
    checked: Boolean,
    locked: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = { if (!locked) onToggle() },
        color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = checked || locked,
                onCheckedChange = { if (!locked) onToggle() },
                enabled = !locked
            )
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(toolchain.name, style = MaterialTheme.typography.bodyLarge)
                    if (locked) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "(必需)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    toolchain.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${toolchain.sizeMB}MB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 工具链管理界面 —— 已装的可查看，未装的可以补装。
 *
 * 与首次安装时的选择界面区别：
 * - 这里显示哪些已装（已装的默认勾选，可以取消但不会卸载）
 * - 支持「全选/全不选」快捷操作
 */
@Composable
fun ToolchainManageScreen(
    installed: Set<String>,
    onBack: () -> Unit,
    onInstall: (Set<String>) -> Unit,
    /** true = 正在实测验证（记录文件可能不准，见 RootfsManager.verifyInstalledToolchains）*/
    verifying: Boolean = false,
) {
    var selected by remember { mutableStateOf(installed) }
    val toInstall = selected - installed
    val totalMB = if (toInstall.isEmpty()) 0 else ToolchainCatalog.estimatedSizeMB(toInstall)

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            Text("管理工具链", style = MaterialTheme.typography.titleLarge)
            if (verifying) {
                Spacer(Modifier.width(10.dp))
                // 转圈 + 说明：为什么要等
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "实测检查中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        // 验证期间提醒用户：这里显示的「已安装」是实测结果，不是记录文件
        if (verifying) {
            Text(
                "正在逐个跑命令确认哪些真的装上了（约 5 秒）——\n" +
                "记录文件可能不准（中途失败/手动删除/rootfs 损坏）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
            )
        }

        Text(
            if (installed.isEmpty()) "还没有安装任何工具链"
            else "已安装 ${installed.size} 组：${installed.joinToString("、")}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(ToolchainCatalog.ALL) { tc ->
                val isInstalled = tc.id in installed
                val checked = tc.id in selected
                Surface(
                    onClick = {
                        selected = if (checked) selected - tc.id else selected + tc.id
                    },
                    color = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = {
                            selected = if (checked) selected - tc.id else selected + tc.id
                        })
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tc.name, style = MaterialTheme.typography.bodyLarge)
                                if (isInstalled) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        "已装",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                tc.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            "${tc.sizeMB}MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = { selected = ToolchainCatalog.ALL.map { it.id }.toSet() },
                modifier = Modifier.weight(1f)
            ) { Text("全选") }

            Button(
                onClick = { onInstall(toInstall) },
                modifier = Modifier.weight(1.5f),
                enabled = toInstall.isNotEmpty()
            ) {
                Text(if (toInstall.isEmpty()) "没有新工具" else "安装 ${toInstall.size} 组（${totalMB}MB）")
            }
        }
    }
}

@Composable
/**
 * 安装进度页。
 *
 * @param failed 安装失败时置 true —— 页面**停住不自动跳走**，
 *               底部出现「返回」按钮，让用户能看完日志再走。
 *
 * 【为什么需要这个参数】原来是「装完 delay(800) 就 stage = READY」，
 * 失败时也一样 —— 用户反馈「弹了『部分失败』后就清屏，且自动退出日志页」，
 * 想看日志截图都来不及。失败时最需要日志，偏偏那时候把页面收走了。
 */
fun InstallingScreen(
    progress: Float,
    log: String,
    failed: Boolean = false,
    onBack: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    // 【2026-09-24 加自动滚底】
    // 原来日志区固定 200dp 上限且不自动滚动 —— 安装时日志一直在涨，
    // 用户看到的是最早的几行，最新进度（也就是最关键的错误信息）看不到。
    // 出问题时想截图反馈，截到的还是开头那几行。
    //
    // 现在每次日志变化就滚到底（LaunchedEffect(log) 是正确做法：
    // 直接在组合里调 scrollTo 会触发重组循环）。
    //
    // 【2026-09-24 二次修：别在用户翻历史时把他拽回底部】
    // 无条件滚底有个副作用：用户往上翻看错误 → 新日志一来就被拉回底部，
    // 根本读不完。现在只在「用户本来就在底部附近」时才跟随。
    var autoFollow by remember { mutableStateOf(true) }
    LaunchedEffect(log) {
        try {
            if (autoFollow) scrollState.scrollTo(scrollState.maxValue)
        } catch (_: Throwable) {}
    }
    // 监听滚动位置：离开底部就停止跟随，回到底部（或拉不动）就恢复
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        val fromBottom = scrollState.maxValue - scrollState.value
        autoFollow = fromBottom < 40
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (failed) "⚠️ 安装未完成" else "正在安装…",
                style = MaterialTheme.typography.titleMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            // 【复制日志】按钮：出错时用户能一键复制完整日志发给开发者。
            // 手选长文本在手机上极难（日志区还会自动滚动）。
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(log))
                Toast.makeText(context, "日志已复制", Toast.LENGTH_SHORT).show()
            }) {
                Text("复制日志", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                log.ifEmpty { "（等待输出…）" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(8.dp)
            )
        }

        // 【2026-09-24 加】失败时才出现的「返回」按钮。
        //
        // 原来失败和成功一样 delay 一下自动跳走 —— 用户反馈
        //「弹了『部分失败』后就清屏，且自动退出日志页」，
        // 而失败时恰恰最需要日志（要截图反馈）。
        //
        // 现在失败就停住，用户看完/复制完日志，自己点返回。
        if (failed) {
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("返回")
            }
        }
    }
}

@Composable
fun ReadyScreen(
    runtime: RuntimeState,
    rootfsPath: String,
    hasNode: Boolean,
    kernelInstalled: Boolean,
    onOpenA11ySettings: () -> Unit,
    onRequestCapture: () -> Unit,
    onStartService: () -> Unit,
    onStartNode: () -> Unit,
    onOpenWeb: () -> Unit,
    onManageToolchains: () -> Unit = {},
    onUpdateKernel: () -> Unit = {},
    /** proot 自检结果：null=正常，否则是诊断文本（见 ProotRuntime.selfCheck） */
    prootCheckError: String? = null
) {
    var showNodeLog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())
    ) {
        Text("Claude Code Mobile 环境就绪", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        StatusRow("Linux 环境", true, rootfsPath.takeLast(28))
        StatusRow("Node 运行时", hasNode, if (hasNode) "已安装" else "未安装")
        StatusRow("Node 内核", kernelInstalled, if (kernelInstalled) "已安装" else "未安装")
        StatusRow("无障碍服务", runtime.a11yOn,
            if (runtime.a11yOn) "已开启" else "未开启 —— 手机操作需要它")
        StatusRow("截屏能力", runtime.captureOn,
            if (runtime.captureOn) "已授权" else "未授权 —— 截图需要它")
        StatusRow("核心服务", runtime.serviceRunning,
            if (runtime.serviceRunning) "运行中" else "未启动")
        StatusRow("Node 服务", runtime.webReady,
            if (runtime.webReady) "运行中 :3456" else "未启动")
        // proot 自检：这一行能把「工具链装不上」的真实原因暴露出来
        StatusRow("proot 运行时", prootCheckError == null,
            if (prootCheckError == null) "自检通过" else "自检失败 —— 见下方")

        // 【查看内核日志】入口。
        // Node 启动失败的原因都在它的 stdout/stderr 里（模块缺失/语法错误/端口占用），
        // 而原来这些只进 logcat —— 用户在手机上根本看不到（logcat 门槛太高）。
        // ⚠️ 不加 nodeRunning 条件 —— 失败时正是最需要看日志的时候。
        // （我第一版写成 if (runtime.nodeRunning) 了，那等于「能跑的时候才给看日志」，
        //  恰恰把最有价值的场景挡在外面）
        val nodeLog = CcmService.nodeLogText
        TextButton(onClick = { showNodeLog = !showNodeLog }) {
            Text(
                when {
                    showNodeLog -> "收起内核日志"
                    nodeLog.isEmpty() -> "查看内核日志（暂无）"
                    else -> "查看内核日志（${nodeLog.lines().size} 行）"
                }
            )
        }
        if (showNodeLog) {
            val logText = CcmService.nodeLogText
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    logText.ifEmpty { "（暂无日志）" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp)
                )
            }
        }

        if (prootCheckError != null) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("proot 自检失败", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        prootCheckError,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        if (!runtime.a11yOn) {
            Button(onClick = onOpenA11ySettings, modifier = Modifier.fillMaxWidth()) {
                Text("去开启无障碍服务")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!runtime.captureOn) {
            Button(onClick = onRequestCapture, modifier = Modifier.fillMaxWidth()) {
                Text("授权截屏能力")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!runtime.serviceRunning) {
            Button(onClick = onStartService, modifier = Modifier.fillMaxWidth()) {
                Text("启动核心服务")
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = onStartNode,
            modifier = Modifier.fillMaxWidth(),
            enabled = hasNode && kernelInstalled
        ) {
            Text(if (runtime.webReady) "重启 Node 内核" else "启动 Node 内核")
        }
        Spacer(Modifier.height(8.dp))
        // 【2026-09-23 加】内核是**首次安装时**才下载的（见 installKernel），
        // 之后改内核包不会自动生效 —— 每次都得重装 APK 才能拿到新的 web/dist。
        // 开发期这太难受，所以给一个显式入口。
        // ⚠ 更新会覆盖 /root/ccm，用户如果在里面手改过东西会丢。
        OutlinedButton(
            onClick = onUpdateKernel,
            modifier = Modifier.fillMaxWidth(),
            enabled = hasNode
        ) {
            Text("更新 Node 内核（覆盖 /root/ccm）")
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpenWeb,
            modifier = Modifier.fillMaxWidth(),
            enabled = runtime.webReady
        ) {
            Text(if (runtime.webReady) "打开界面" else "等 Node 启动后可打开")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onManageToolchains,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("管理工具链")
        }
    }
}

@Composable
fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.width(100.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun WebViewScreen(webReady: Boolean, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            Text("Claude Code Mobile", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (!webReady) {
                Text("服务未就绪", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
        if (!webReady) {
            CenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("等待 Node 服务启动…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.domStorageEnabled = true
                        webViewClient = WebViewClient()
                        loadUrl("http://127.0.0.1:3456")
                    }
                }
            )
        }
    }
}
