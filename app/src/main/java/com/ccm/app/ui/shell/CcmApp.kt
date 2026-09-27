package com.ccm.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import android.content.Intent
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ccm.app.core.ChatSession
import com.ccm.app.core.session.SessionStore
import com.ccm.app.ui.chat.ChatScreen
import com.ccm.app.ui.chat.ChatScreenConnected
import com.ccm.app.ui.common.CcmNoticeBar
import com.ccm.app.ui.common.SidebarDrawer
import com.ccm.app.ui.common.TitleBar
import com.ccm.app.ui.pages.ArtifactsScreen
import com.ccm.app.ui.pages.ChatsScreen
import com.ccm.app.ui.pages.CoworkScreen
import com.ccm.app.ui.pages.CustomizeScreen
import com.ccm.app.ui.pages.LandingScreen
import com.ccm.app.ui.pages.ProjectsScreen
import com.ccm.app.ui.pages.ScheduledScreen
import com.ccm.app.ui.pages.greetingFor
import com.ccm.app.ui.settings.SettingsScreen
import com.ccm.app.ui.theme.CCMTheme

/**
 * CCM 应用根 Composable —— **阶段 5 的 MainActivity 只调这一个**。
 *
 * ## 冻结接口（CONTRACTS.md）
 * ```kotlin
 * // MainActivity.kt（阶段 5 owner）
 * setContent { CcmApp(session = graph) }
 * ```
 * 这是 dev-ui（阶段 4）与阶段 5 之间**唯一**的接口。阶段 5 不需要知道
 * 内部有哪些页面、怎么导航 —— 全在 ui/ 包内封装。
 *
 * ## 状态
 * - ✅ 主题层（ui/theme/）
 * - ✅ 顶栏 + 侧栏抽屉（ui/common/）
 * - ✅ 首页 / 对话列表 / 项目页（ui/pages/）
 * - ✅ 聊天主界面（接 [ChatSession]）
 *
 * ## ★ 关于 [session] 参数（阶段 5 接线）
 *
 * 这是本次接线**唯一的改动点**：以前 `CcmApp()` 无参，所以聊天页只能
 * 渲染 `bubbles = emptyList()` 的假空态 —— 用户看到的界面是死的。
 *
 * 现在由 `AppGraph` 装配好会话传进来：
 * ```
 * AppGraph.init() → ChatSession
 *   └── CcmApp(session)
 *         ├── 首页输入 → session.send() → 自动切到对话页
 *         └── 对话页 → ChatScreenConnected(session)  ← 真数据
 * ```
 *
 * 传 `null` 时（用户还没配 Provider）保持原来的空态渲染，不崩。
 *
 * ## 布局结构（对齐 Web 移动端）
 * ```
 * Box（根，承载抽屉浮层）
 * ├── Column
 * │   ├── TitleBar          44dp（不乘 0.92）
 * │   └── 页面内容
 * └── SidebarDrawer          浮在最上层（含遮罩）
 * ```
 * 抽屉用 `Box` 浮层而非 `ModalNavigationDrawer`，因为 Web 的实现是
 * `fixed + z-60 + translateX`，用 Box 能 1:1 复刻它的动画与层级。
 *
 * ## 主题来源（对齐 Web）
 * Web 判定顺序（`Onboarding.tsx:37-40`、`SettingsPage.tsx:135-139`）：
 * 1. 用户显式选过 dark → 用用户选择
 * 2. 否则跟随系统 `prefers-color-scheme: dark`
 *
 * 实测确认 Web 的 localStorage 默认值是 `"system"`（跟随系统）。
 *
 * @param session 已装配的会话（`null` = 还没配 API，渲染空态）
 * @param initError 装配失败的原因（`null` = 一切正常）。非空时会在页面顶部
 *   显示一条可点的提示条 —— **不能只存不显**：用户没配 API 时点哪都没反应，
 *   会以为界面坏了。见 [AppScaffold] 里的 notice。
 *   **默认从 [com.ccm.app.AppGraph.initError] 读** —— 调用方（MainActivity）
 *   漏传时也能拿到，不会退化成「静默失败」。显式传参优先。
 */
@Composable
fun CcmApp(
    session: ChatSession? = null,
    initError: String? = null,
) {
    // 对齐 Web：未显式设置过主题时跟随系统（localStorage.theme === "system"）
    // TODO(阶段4·B5): 接 SettingsRepository —— 用户显式选择优先于系统
    val darkTheme = isSystemInDarkTheme()

    // 兜底：调用方没传 initError 时从全局装配结果读。
    // 之所以要兜底：AppGraph.initError 早就存好了，但 UI 一直没显示它，
    // 于是「没配 API」表现为「点什么都没反应」—— 用户以为界面坏了。
    val effectiveError = initError ?: com.ccm.app.AppGraph.initError

    CCMTheme(darkTheme = darkTheme) {
        AppScaffold(session = session, initError = effectiveError)
    }
}

/**
 * Web 的路由 —— 对应 `App.tsx:933-953`。
 *
 * Web 用 **HashRouter**，路径形如 `#/chats`。
 * （踩坑记录：直接访问 `/chats` 会落到兜底路由渲染首页，
 *   测量/复现时必须带 `#`。）
 *
 * 移动端实际可达的路由：
 * ```
 * /            首页（MainContent）
 * /chats       对话列表
 * /customize   定制
 * /projects    项目
 * /artifacts   产物
 * /cowork      协作
 * /scheduled   计划任务
 * /chat/:id    单个对话
 * /login       登录
 * /admin 及其子页   管理后台（7 个）
 * 其他任意路径      重定向到 /（对齐 Web 的 Navigate to="/" replace）
 * ```
 */
enum class CcmRoute(val path: String) {
    HOME("/"),
    /** 单个对话 —— 路径含参数（`/chat/:id`），[path] 是前缀 */
    CHAT("/chat"),
    CHATS("/chats"),
    CUSTOMIZE("/customize"),
    SETTINGS("/settings"),
    PROJECTS("/projects"),
    ARTIFACTS("/artifacts"),
    COWORK("/cowork"),
    SCHEDULED("/scheduled"),
    LOGIN("/login"),
    ADMIN("/admin");

    companion object {
        /** 从路径解析路由（未知路径回 [HOME]，对齐 Web 的兜底重定向） */
        fun fromPath(path: String): CcmRoute {
            val clean = path.removePrefix("#").trimEnd('/').ifEmpty { "/" }
            // 精确匹配优先
            entries.firstOrNull { it.path == clean }?.let { return it }
            // 前缀匹配（处理 /chat/:id 这类带参数的路由）
            return entries.firstOrNull { it != CHAT && clean.startsWith(it.path + "/") }
                ?: if (clean.startsWith("/chat/")) CHAT else HOME
        }
    }
}

/**
 * 应用骨架：顶栏 + 页面内容 + 侧栏抽屉。
 *
 * 移动端形态（MEASURED.md §0/§5/§9）：
 * - 视口 393×852，全局 zoom 0.92（已固化进各尺寸常量）
 * - 顶栏 44dp，**不乘 0.92**
 * - 侧栏是 276dp 的抽屉，默认收起
 * - Web 在每次路由变化时自动收起抽屉（`App.tsx:378`）—— 已保留该行为
 *
 * ## 接线说明（阶段 5）
 *
 * **首页输入 → 对话页** 的流转在这里：
 * ```
 * LandingScreen.onSend(text)
 *   └── session.send(text)      ← 交给 core，Agent 开始跑
 *         └── navigate(CHAT)    ← 立刻切页，让用户看到流式输出
 * ```
 * 切页必须在 `send` 之后立刻做（而不是等第一个事件）——
 * 用户按了发送却还停在首页，会以为没反应。
 *
 * 从侧栏「新对话」进来时是 [CcmRoute.HOME]，再次发送会复用同一个 session
 * （会话历史连续）。要开新会话得等 dev-core 提供 `session.clear()` 的
 * 界面入口 —— 见 `ChatSession.clear()`，目前还没有 UI 接它。
 */
@Composable
private fun AppScaffold(session: ChatSession?, initError: String?) {
    val colors = CCMTheme.colors

    var sidebarOpen by remember { mutableStateOf(false) }

    // ★★ 2026-09-27 修「大多数按钮点不动」的真因 ★★
    //
    // 原来这里（以及下面 259/306/347 行）直接在 Composable 函数体里写：
    //     AppGraph.userProfileStore?.load()
    //     AppConfig.load(it.configFile)
    // 这两个都会**读磁盘 + 解析 JSON**，而 Compose 的重组频率极高
    // （每帧、每次状态变化、键盘弹出/收起都触发）。
    //
    // 实测后果（真机 logcat）：
    //     I/Choreographer: Skipped 39 frames! The application may be doing
    //     too much work on its main thread.
    // 39 帧 ≈ 650ms —— 主线程被磁盘 IO 堵死，触摸事件排在后面，
    // 表现就是「所有按钮都点不动」（不是回调没接，是根本没轮到处理点击）。
    //
    // 修法：用 remember 缓存，只在「依赖变化」时重读。
    //   - profileName：进程内基本不变（用户改设置后走 refreshKey 刷新）
    //   - modelName：同上
    var profileRefreshKey by remember { mutableStateOf(0) }
    val profileName = remember(profileRefreshKey) {
        com.ccm.app.AppGraph.userProfileStore?.load()?.callName?.ifBlank { null }
    }
    val modelName = remember(profileRefreshKey) {
        com.ccm.app.AppGraph.storage
            ?.let { com.ccm.app.core.provider.AppConfig.load(it.configFile).config.currentProvider?.model }
            ?.takeIf { it.isNotBlank() }
            ?: "未配置模型"
    }
    var route by remember { mutableStateOf(CcmRoute.HOME) }
    // 设置是**覆盖层不是路由**（对齐 Web：showSettings 状态，location 不变）
    var showSettings by remember { mutableStateOf(false) }

    /** 切页 —— 对齐 Web：路由变化时自动收起抽屉（`App.tsx:378`） */
    fun navigate(to: CcmRoute) {
        route = to
        sidebarOpen = false
    }

    /**
     * 发消息并切到对话页。
     *
     * 抽出来是因为首页和对话页都可能触发发送（对话页的输入框走的是
     * `ChatScreenConnected` 内部的 `session.send`，不经过这里）。
     *
     * ## ★ 不静默失败（2026-09-27 修）
     * 原来写的是 `val s = session ?: return` —— 没配 Provider 时点胶囊、
     * 按发送**全都没反应**，用户看到的是「界面坏了」而不是「你需要先配 API」。
     * 现在改成：session 为空 → 直接跳设置页（配 API 的唯一入口）。
     * 首页顶部同时会显示 [initError] 提示条（见下方 notice）。
     */
    fun sendAndOpen(text: String) {
        session?.send(text)
        // ★ 不管有没有 session 都切到对话页 —— 用户按了发送/点了胶囊，
        //   就该看到「消息已发出」的界面。没配 Provider 时对话页会显示
        //   提示条（由 initError 驱动），而不是把人踢去设置页。
        //   踩过的坑：曾经 session==null 就 showSettings=true，
        //   用户点胶囊期待发消息，结果跳设置页 —— 像是「点了乱跳」。
        navigate(CcmRoute.CHAT)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgMain)
                // ══════════════════════════════════════════════════════
                //  【2026-09-27 修「侧栏/按钮点不动」的真因】
                // ══════════════════════════════════════════════════════
                // 系统强制 edge-to-edge（dumpsys: EDGE_TO_EDGE_ENFORCED），
                // 而全项目 0 处 insets 处理 → 内容从 y=0 裸奔。
                // 实测（dumpsys window）：
                //   StatusBar 窗口 frame=[0,0][1280,152]
                //   touchableRegion=(0,0,1280,152)   ← 触摸也归它！
                //   TitleBar 高 44dp=143px，☰ 居中在 y≈19~123
                // → ☰ 及整个标题栏都落在状态栏触摸区内，
                //   用户点 ☰ 触摸被 StatusBar 窗口吃掉，CCM 收不到 ——
                //   表现为「任何地方都点不开侧栏」（副屏没状态栏，所以能开）。
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            TitleBar(
                onToggleSidebar = {
                    android.util.Log.i("CCMTap", "click menu, sidebarOpen: $sidebarOpen -> ${!sidebarOpen}")
                    sidebarOpen = !sidebarOpen
                },
                // 非首页时启用「后退」（回首页）；首页时禁用（灰色 #B7B5B0）
                onNavBack = if (route != CcmRoute.HOME) ({ navigate(CcmRoute.HOME) }) else null,
                onNavForward = null,
            )

            // ── 页面内容 ──────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                // ★ 未配置 API 的提示条（2026-09-27 加）
                //
                // 之前 initError 只存不显，用户看到的是「点哪都没反应」，
                // 而不是「你需要先配 API」。现在把它顶到所有页面之上显示，
                // 且整条可点 → 直接进设置页。
                //
                // 为什么放在 when **之外**：所有页面都该看得到它，
                // 且它只由 AppGraph 的初始化结果决定，与路由无关。
                if (initError != null) {
                    CcmNoticeBar(
                        message = initError,
                        // 点提示条才进设置；不点就不打扰（不再强制跳转）
                        onClick = { showSettings = true },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 14.72.dp, vertical = 7.36.dp),
                    )
                }

                when (route) {
                    CcmRoute.HOME -> LandingScreen(
                        greeting = greetingFor(profileName),
                        // ★ 接线：首页输入框真的能发消息了
                        onSend = { sendAndOpen(it) },
                        onPickPrompt = { sendAndOpen(it) },
                    )

                    CcmRoute.CHAT -> if (session != null) {
                        // ★ 接线：真数据。ChatScreenConnected 内部订阅
                        //   session.state（StateFlow），把 core 类型适配成 UI 类型。
                        //
                        // ★ 2026-09-27：补 title / Export / Rename（原来全是默认空转）。
                        val ctx = LocalContext.current
                        val store = AppGraph.storage?.let { SessionStore(it) }
                        var chatTitle by remember { mutableStateOf("新对话") }
                        var showRename by remember { mutableStateOf(false) }

                        LaunchedEffect(route) {
                            // 进对话页读标题（loadTitle 只读文件头 4KB）
                            chatTitle = store?.loadTitle(AppGraph.sessionId) ?: "新对话"
                        }

                        ChatScreenConnected(
                            session = session,
                            title = chatTitle,
                            onExport = {
                                // Web 的 Export 是导出 markdown；Android 用系统分享
                                val text = session.state.value.bubbles.joinToString("\n\n") { b ->
                                    (if (b.isUser) "**我**：" else "**AI**：") + b.text
                                }
                                if (text.isNotBlank()) {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, text)
                                    }
                                    ctx.startActivity(Intent.createChooser(send, "导出对话"))
                                }
                            },
                            onRename = { showRename = true },
                        )

                        if (showRename) {
                            var nameInput by remember { mutableStateOf(chatTitle) }
                            AlertDialog(
                                onDismissRequest = { showRename = false },
                                title = { Text("重命名对话") },
                                text = {
                                    BasicTextField(
                                        value = nameInput,
                                        onValueChange = { nameInput = it },
                                        textStyle = androidx.compose.ui.text.TextStyle(
                                            color = CCMTheme.colors.textMain,
                                            fontSize = androidx.compose.ui.unit.TextUnit.Unspecified,
                                        ),
                                        cursorBrush = SolidColor(CCMTheme.colors.claudeOrange),
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        val st = AppGraph.storage
                                        if (st != null) {
                                            val ss = SessionStore(st)
                                            val cur = ss.load(AppGraph.sessionId)
                                            if (cur != null) {
                                                val nt = nameInput.trim().ifBlank { null }
                                                ss.save(cur.copy(title = nt, updatedAt = System.currentTimeMillis()))
                                                chatTitle = nt ?: chatTitle
                                            }
                                        }
                                        showRename = false
                                    }) { Text("确定") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { showRename = false }) { Text("取消") }
                                },
                            )
                        }
                    } else {
                        // 没配 Provider —— 渲染空态而不是崩。
                        // 用户此时应该去设置页，这里给个能点的入口。
                        ChatScreen(
                            bubbles = emptyList(),
                            onSend = { },
                        )
                    }

                    CcmRoute.CHATS -> ChatsScreen(
                        chats = emptyList(),
                        onNewChat = { navigate(CcmRoute.HOME) },
                    )

                    CcmRoute.SETTINGS -> SettingsScreen(
                        onClose = { navigate(CcmRoute.HOME) },
                    )

                    CcmRoute.PROJECTS -> ProjectsScreen(
                        projects = emptyList(),
                        onCreate = { },
                    )

                    CcmRoute.CUSTOMIZE -> CustomizeScreen()

                    CcmRoute.ARTIFACTS -> ArtifactsScreen(
                        items = emptyList(),
                        onNewArtifact = { },
                    )

                    CcmRoute.COWORK -> CoworkScreen()

                    CcmRoute.SCHEDULED -> ScheduledScreen()

                    else -> LandingScreen(
                        greeting = greetingFor(profileName),
                        onSend = { sendAndOpen(it) },
                        onPickPrompt = { sendAndOpen(it) },
                    )
                }
            }
        }

        // ── 设置覆盖层（全屏，在抽屉之下）─────────────────────────────
        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bgMain)
                    .windowInsetsPadding(WindowInsets.statusBars),   // 顶部返回按钮同理
            ) {
                SettingsScreen(onClose = { showSettings = false })
            }
        }

        // ── 侧栏抽屉（浮层，含遮罩）───────────────────────────────────
        android.util.Log.i("CCMTap", "recompose SidebarDrawer open=$sidebarOpen route=$route showSettings=$showSettings")
        SidebarDrawer(
            open = sidebarOpen,
            onClose = { sidebarOpen = false },
            onNavigate = { key ->
                val target = when (key) {
                    "chats" -> CcmRoute.CHATS
                    "projects" -> CcmRoute.PROJECTS
                    "artifacts" -> CcmRoute.ARTIFACTS
                    else -> CcmRoute.HOME
                }
                navigate(target)
            },
            onNewChat = { navigate(CcmRoute.HOME) },
            onCustomize = { navigate(CcmRoute.CUSTOMIZE) },
            onOpenProfile = { navigate(CcmRoute.SETTINGS) },
            // ★ 2026-09-27 修「侧边栏点不动」：
            //   下面两个回调原来**根本没传**，UI 侧拿到的是默认空实现
            //   → 点「搜索」「聊天/协作/代码」胶囊完全没反应。
            onSearch = { showSettings = true },   // TODO: 真正的会话搜索页
            onPillChange = { /* TODO: 协作/代码模式路由（Web 是 /cowork 切换） */ },
            userName = profileName ?: "",
        )
    }
}
