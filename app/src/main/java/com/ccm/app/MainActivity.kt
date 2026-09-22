package com.ccm.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ccm.app.runtime.ProotRuntime
import com.ccm.app.runtime.RootfsManager
import com.ccm.app.service.CcmAccessibilityService
import com.ccm.app.service.CcmService
import com.ccm.app.tools.ScreenCapture
import android.media.projection.MediaProjectionManager
import android.app.Activity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
enum class Stage { CHECKING, NEED_SETUP, SETTING_UP, READY, WEBVIEW }

@Composable
fun CcmApp() {
    val ctx = LocalContext.current
    var stage by remember { mutableStateOf(Stage.CHECKING) }
    var log by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var a11yOn by remember { mutableStateOf(CcmAccessibilityService.isConnected()) }
    var nodeRunning by remember { mutableStateOf(false) }
    var captureOn by remember { mutableStateOf(ScreenCapture.isReady()) }

    val rootfs = remember { RootfsManager(ctx) }
    val proot = remember { ProotRuntime(ctx) }

    // 启动时检查环境
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            kotlinx.coroutines.delay(300)
            stage = if (rootfs.isInstalled()) Stage.READY else Stage.NEED_SETUP
        }
    }

    // 定期刷新无障碍状态（用户可能切出去开权限）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            a11yOn = CcmAccessibilityService.isConnected()
            captureOn = ScreenCapture.isReady()
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (stage) {
                Stage.CHECKING -> CenterBox { CircularProgressIndicator() }

                Stage.NEED_SETUP -> SetupScreen(
                    log = log,
                    onInstall = {
                        stage = Stage.SETTING_UP
                        // 后台跑安装
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val ok = rootfs.install { phase, done, total ->
                                    val pct = if (total > 0) done.toFloat() / total else 0f
                                    progress = when (phase) {
                                        "download" -> pct * 0.5f
                                        "extract" -> 0.5f + pct * 0.45f
                                        else -> 0.95f
                                    }
                                    log = when (phase) {
                                        "download" -> "下载 Linux 环境… ${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB"
                                        "extract" -> "解压… ${(pct * 100).toInt()}%"
                                        else -> "配置环境…"
                                    }
                                }
                                if (!ok) {
                                    log = "❌ 环境安装失败。请检查网络后重试。"
                                    stage = Stage.NEED_SETUP
                                    return@launch
                                }
                                log = "安装 Node 内核…"
                                progress = 0.96f
                                val kok = rootfs.installKernel { done, total ->
                                    val pct = if (total > 0) done.toFloat() / total else 0f
                                    progress = 0.96f + pct * 0.04f
                                }
                                log = if (kok) "✅ 完成" else "⚠️ 内核安装失败（可稍后重试）"
                                progress = 1f
                                kotlinx.coroutines.delay(500)
                                stage = Stage.READY
                            } catch (e: Throwable) {
                                log = "❌ 出错: ${e.message}"
                                stage = Stage.NEED_SETUP
                            }
                        }
                    }
                )

                Stage.SETTING_UP -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("正在安装 Linux 环境…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        log,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                    )
                }

                Stage.READY -> ReadyScreen(
                    a11yOn = a11yOn,
                    captureOn = captureOn,
                    nodeRunning = nodeRunning,
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
                        val i = Intent(ctx, CcmService::class.java)
                        ctx.startForegroundService(i)
                    },
                    onStartNode = {
                        val i = Intent(ctx, CcmService::class.java).apply {
                            action = com.ccm.app.service.ACTION_START_NODE
                        }
                        ctx.startForegroundService(i)
                        nodeRunning = true
                    },
                    onOpenWeb = { stage = Stage.WEBVIEW }
                )

                Stage.WEBVIEW -> WebViewScreen(onBack = { stage = Stage.READY })
            }
        }
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
fun SetupScreen(log: String, onInstall: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("CCM 首次启动", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            "需要安装 Linux 运行环境（Ubuntu，约 60MB）。\n" +
            "环境用于运行 AI 的工具链（Node / git / ffmpeg 等）。",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onInstall) { Text("开始安装") }
    }
}

@Composable
fun ReadyScreen(
    a11yOn: Boolean,
    captureOn: Boolean,
    nodeRunning: Boolean,
    rootfsPath: String,
    hasNode: Boolean,
    kernelInstalled: Boolean,
    onOpenA11ySettings: () -> Unit,
    onRequestCapture: () -> Unit,
    onStartService: () -> Unit,
    onStartNode: () -> Unit,
    onOpenWeb: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())
    ) {
        Text("CCM 环境就绪", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        StatusRow("Linux 环境", true, rootfsPath.takeLast(30))
        StatusRow("Node 运行时", hasNode, if (hasNode) "已安装" else "未安装（需 apt install nodejs）")
        StatusRow("Node 内核", kernelInstalled, if (kernelInstalled) "已安装" else "未安装")
        StatusRow("无障碍服务", a11yOn, if (a11yOn) "已开启" else "未开启 —— 手机操作需要它")
        StatusRow("截屏能力", captureOn, if (captureOn) "已授权" else "未授权 —— 截图需要它")
        StatusRow("核心服务", CcmService.isRunning, if (CcmService.isRunning) "运行中" else "未启动")

        Spacer(Modifier.height(24.dp))

        if (!a11yOn) {
            Button(onClick = onOpenA11ySettings, modifier = Modifier.fillMaxWidth()) {
                Text("去开启无障碍服务")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!captureOn) {
            Button(onClick = onRequestCapture, modifier = Modifier.fillMaxWidth()) {
                Text("授权截屏能力")
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!CcmService.isRunning) {
            Button(onClick = onStartService, modifier = Modifier.fillMaxWidth()) {
                Text("启动核心服务")
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = onStartNode,
            modifier = Modifier.fillMaxWidth(),
            enabled = hasNode
        ) {
            Text(if (nodeRunning) "重启 Node 内核" else "启动 Node 内核")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenWeb, modifier = Modifier.fillMaxWidth()) {
            Text("打开界面")
        }
    }
}

@Composable
fun StatusRow(label: String, ok: Boolean, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (ok) "✓" else "✗", color = if (ok) MaterialTheme.colorScheme.primary
                                          else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.width(100.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun WebViewScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Spacer(Modifier.width(8.dp))
            Text("CCM", style = MaterialTheme.typography.titleMedium)
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = true
                    webViewClient = WebViewClient()
                    // Node 服务默认端口 3456
                    loadUrl("http://127.0.0.1:3456")
                }
            }
        )
    }
}
