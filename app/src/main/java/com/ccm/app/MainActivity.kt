package com.ccm.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ccm.app.runtime.RootfsManager
import com.ccm.app.tools.ScreenCapture
import com.ccm.app.ui.pages.OnboardingScreen
import com.ccm.app.ui.shell.CcmApp
import com.ccm.app.ui.theme.CCMTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/**
 * 应用入口 —— **新架构（Kotlin core）的接线点**。
 *
 * ══════════════════════════════════════════════════════════════
 *  这个文件在 2026-09-27 之前是什么样（以及为什么是错的）
 * ══════════════════════════════════════════════════════════════
 *
 * 它原本是一个 1093 行的老架构实现：
 * ```
 * Stage 状态机（CHECKING→NEED_SETUP→SETTING_UP→READY→WEBVIEW）
 *   └── READY 页点「打开」→ WebView 加载 http://127.0.0.1:3456
 *         └── 那是跑在 proot 里的 Node 内核 + React 前端
 * ```
 *
 * 而 `core/`（AgentLoop / ApiClient / ChatSession）、`tools/`（99 个工具）、
 * `ui/`（Compose 界面）三块新代码**一行都没被调用** ——
 * `ToolsBootstrap.install()` / `AppContainer.build()` / `ChatSession.create()`
 * 全是零调用点，编译通过、单测全绿，装机后跑的却是老内核。
 *
 * 用户的原话是「软件里刚进去就是过时的，什么 node 内核啥的」——
 * 说的就是这个。
 *
 * ══════════════════════════════════════════════════════════════
 *  现在的结构
 * ══════════════════════════════════════════════════════════════
 * ```
 * onCreate
 *   ├── AppGraph.init()        ← 装配 99 个工具 + AgentLoop + ChatSession
 *   └── setContent {
 *         CcmRoot(graph)       ← 本文件，只做「引导页 / 主界面」的分流
 *           ├── OnboardingScreen   首次启动：装 Linux 环境
 *           └── CcmApp()           ui/shell/CcmApp.kt，真正的应用界面
 *       }
 * ```
 *
 * ⚠️ **本文件不再定义 `CcmApp()`** —— 那是老架构的遗留。新架构的
 * `CcmApp` 在 `ui/shell/CcmApp.kt`。同名遮蔽正是这次接线最大的坑：
 * `MainActivity.kt:46` 写的 `setContent { CcmApp() }` 看着已经接了新架构，
 * 实际调的是**同包下自己定义的那个老版本**（Kotlin 同包优先于 import）。
 *
 * ══════════════════════════════════════════════════════════════
 *  保留了什么 / 删掉了什么
 * ══════════════════════════════════════════════════════════════
 *
 * **保留**（这些是对的，只是搬到了别处）：
 * - `RootfsManager` / `ProotRuntime` / `ToolchainCatalog` —— Linux 环境安装
 * - `ScreenCapture` 的 `onActivityResult` 回调 —— 仍被 `NativeBridge` 使用
 *
 * **删除**（用户明确抱怨的三件事）：
 * 1. 「Node.js 运行时（约 50MB，apt 下载）」「Claude Code Mobile 内核（约 14MB）」
 *    —— 内核已移植到 Kotlin，不再需要下载 Node 内核
 * 2. `MediaProjection` 截屏授权按钮 —— 它请求的是**录屏权限**，
 *    而 `phone_screenshot` 走的是 Shizuku 的 `latestFrame()`，两者无关。
 *    用户点「授权截图」授权了个用不上的东西，是纯粹的误导。
 * 3. `MaterialTheme` 默认紫色（那是 Operit 主题）→ 换成 `ui/theme/CCMTheme`
 *    （Claude 橙 `#D97757` + 米白 `#F8F8F6`）
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    /**
     * 应用级协程作用域。
     *
     * 【为什么不能用 lifecycleScope】
     * 对话是长任务（一轮可能跑几分钟，含多次工具调用）。
     * `lifecycleScope` 在 `onDestroy` 时取消 —— 旋转屏幕、切后台被回收、
     * 用户划掉 Activity 都会把正在跑的对话一起掐死。
     *
     * 用 `SupervisorJob` + 自建 scope，让它跟**进程**走：
     * 一个子任务失败不影响其他（Supervisor 语义），Activity 重建也不中断。
     *
     * 注意：进程真被杀掉时当然还是会断 —— 但那种情况本来就保不住。
     */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // UI 偏好（主题/字体）—— 必须在 setContent 之前：
        // 晚了首帧会先闪系统主题再跳用户主题
        com.ccm.app.ui.theme.UiPrefs.init(this)

        // phone use 依赖 CcmService 主线程 Handler 做 Shizuku 服务绑定；
        // 只靠 BootReceiver 会在正常启动时没服务，绑定失败。
        try {
            startForegroundService(Intent(this, com.ccm.app.service.CcmService::class.java))
        } catch (t: Throwable) {
            Log.w(TAG, "启动 CcmService 失败：${t.message}")
        }

        // 装配新架构（幂等 —— Activity 重建时复用同一个会话）
        val graph = AppGraph.init(applicationContext, appScope)
        Log.i(TAG, "AppGraph 装配：${if (graph != null) "成功" else "失败/无配置"}；" +
                "工具 ${AppGraph.toolNames.size} 个；${AppGraph.initError ?: "无错误"}")

        setContent {
            CcmRoot()
        }
    }

    /**
     * 截屏授权回调（`MediaProjection`）。
     *
     * ⚠️ **这条路径目前没有界面入口**（引导页里的误导性按钮已删）。
     * 保留它是因为：
     * 1. `NativeBridge` 的 `phone.screenshot` 分支仍会读 `ScreenCapture.isReady()`
     * 2. 将来若要在设置页加「备用截屏方式」（无 Shizuku 时用录屏），
     *    这就是现成的回调点
     *
     * 不要因为「看起来没人调」就删掉 —— 删了的话那个分支永远返回未授权，
     * 而且排查时会以为是 Shizuku 的问题。
     */
    @Deprecated("暂无界面入口；保留给 NativeBridge 的备用截屏路径")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ScreenCapture.REQUEST_CODE) {
            val ok = ScreenCapture.init(this, resultCode, data)
            Log.i(TAG, if (ok) "截屏授权成功" else "截屏授权失败")
        }
    }
}

/**
 * 根 Composable —— 只做「引导页 / 主界面」的分流。
 *
 * ## 分流判据
 * ```
 * rootfs 未装   → OnboardingScreen（首次启动，装 Linux 环境）
 * rootfs 已装   → CcmApp（ui/shell，真正的应用）
 * ```
 *
 * ⚠️ **不再有 `Stage.WEBVIEW`** —— 那是老架构的终点（WebView 加载 Node 内核）。
 * 新架构全部跑在 Kotlin 里，没有 WebView 环节。
 *
 * ## 为什么 rootfs 检查要放在这里而不是 AppGraph
 * 装 rootfs 是**用户可见的长时间操作**（复制 28MB / 解压 3 万文件），
 * 需要进度界面。而 `AppGraph.init` 是纯内存装配，两者节奏完全不同，
 * 混在一起会让「装配」承担 UI 职责。
 */
@Composable
fun CcmRoot() {
    val ctx = LocalContext.current
    val rootfs = remember { RootfsManager(ctx) }

    // null = 还在检查（避免闪一下引导页再跳走）
    var installed by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) {
            try { rootfs.isInstalled() } catch (t: Throwable) { false }
        }
    }

    CCMTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (installed) {
                null -> CenterBox { CircularProgressIndicator() }
                false -> OnboardingScreen(
                    onReady = { installed = true },
                )
                // 【2026-10-06 问题12】AppGraph.session 现在是 Compose State，
                // 加 Provider 后这里会自动重组（之前是普通 var，不重组）。
                // 【2026-10-06 问题12】读 sessionState（Compose State）——
                // 加 Provider 后 session 变化会触发重组，横幅随之消失。
                true -> CcmApp(session = AppGraph.sessionState.value)
            }
        }
    }
}

/** 居中容器（加载态用）。 */
@Composable
fun CenterBox(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}
