package com.ccm.app

import android.app.Activity
import android.content.Intent
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
    var progress by remember { mutableFloatStateOf(0f) }
    var runtime by remember { mutableStateOf(RuntimeState()) }

    val rootfs = remember { RootfsManager(ctx) }
    val proot = remember { ProotRuntime(ctx) }

    // 启动时检查环境
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            delay(200)
            stage = if (rootfs.isInstalled()) Stage.READY else Stage.NEED_SETUP
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
                                progress = 0.88f
                                log = "安装工具链…"
                                val tok = withContext(Dispatchers.IO) {
                                    rootfs.installToolchains(
                                        selected = selectedChains,
                                        exec = { cmd, cb ->
                                            proot.exec(cmd, "/root") { line ->
                                                scope.launch { log = line.takeLast(70) }
                                                cb(line)
                                            }
                                        },
                                        onLine = { line ->
                                            scope.launch { log = line.takeLast(70) }
                                        }
                                    )
                                }
                                if (!tok) log = "⚠️ 部分工具安装失败（可稍后在「管理工具」里重试）"

                                // ③ 装内核
                                progress = 0.96f
                                log = "安装 Node 内核…"
                                val kok = withContext(Dispatchers.IO) {
                                    rootfs.installKernel { done, total ->
                                        val pct = if (total > 0) done.toFloat() / total else 0f
                                        scope.launch { progress = 0.96f + pct * 0.04f }
                                    }
                                }
                                if (!kok) log = "⚠️ 内核安装失败（可稍后重试）"

                                progress = 1f
                                log = "✅ 完成"
                                delay(600)
                                stage = Stage.READY
                            } catch (e: Throwable) {
                                log = "❌ 出错: ${e.message}"
                                stage = Stage.NEED_SETUP
                            }
                        }
                    }
                )

                Stage.SETTING_UP -> InstallingScreen(progress = progress, log = log)

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
                    onManageToolchains = { stage = Stage.TOOLCHAIN_MANAGE }
                )

                Stage.WEBVIEW -> WebViewScreen(
                    webReady = runtime.webReady,
                    onBack = { stage = Stage.READY }
                )

                Stage.TOOLCHAIN_MANAGE -> ToolchainManageScreen(
                    installed = rootfs.installedToolchains(),
                    onBack = { stage = Stage.READY },
                    onInstall = { chains ->
                        stage = Stage.SETTING_UP
                        scope.launch {
                            try {
                                val ok = withContext(Dispatchers.IO) {
                                    rootfs.installToolchains(
                                        selected = chains,
                                        exec = { cmd, cb ->
                                            proot.exec(cmd, "/root") { line ->
                                                scope.launch { log = line.takeLast(70) }
                                                cb(line)
                                            }
                                        },
                                        onLine = { line ->
                                            scope.launch { log = line.takeLast(70) }
                                        }
                                    )
                                }
                                log = if (ok) "✅ 安装完成" else "⚠️ 部分失败"
                                delay(800)
                                stage = Stage.READY
                            } catch (e: Throwable) {
                                log = "❌ ${e.message}"
                                delay(1500)
                                stage = Stage.READY
                            }
                        }
                    }
                )
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
        Text("CCM 首次启动", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "需要安装 Linux 运行环境：\n\n" +
            "• Ubuntu 24.04 base（约 28MB）\n" +
            "• Node.js 运行时（约 50MB，apt 下载）\n" +
            "• CCM 内核（约 14MB）\n\n" +
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
    onInstall: (Set<String>) -> Unit
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
fun InstallingScreen(progress: Float, log: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("正在安装…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Text(
            log,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())
        )
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
    onManageToolchains: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())
    ) {
        Text("CCM 环境就绪", style = MaterialTheme.typography.headlineSmall)
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
            Text("CCM", style = MaterialTheme.typography.titleMedium)
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
