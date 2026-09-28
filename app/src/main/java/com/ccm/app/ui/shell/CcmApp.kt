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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import com.ccm.app.ui.theme.CCMText
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ccm.app.AppGraph
import com.ccm.app.core.ChatSession
import com.ccm.app.core.session.SessionStore
import com.ccm.app.core.session.SessionSummary
import com.ccm.app.ui.common.ChatSummary
import com.ccm.app.ui.pages.ChatSummaryUi
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
    // 主题：用户选择优先，auto 才跟随系统（2026-09-27 接 UiPrefs ——
    // 原来是 TODO 永远 isSystemInDarkTheme()，设置页选了白选）。
    // UiPrefs.themeMode 是 MutableState → 设置页改值这里自动重组。
    val darkTheme = when (com.ccm.app.ui.theme.UiPrefs.themeMode.value) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

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

    // ══════════════════════════════════════════════════════════════
    //  【2026-09-27 第2批】会话切换 + 历史列表真数据
    // ══════════════════════════════════════════════════════════════
    // activeSession：当前打开的会话。init 结果是它，侧栏/列表点进来后
    // 被 AppGraph.openSession(id) 换掉。原参数 session 只当初始值。
    var activeSession by remember { mutableStateOf(session) }
    // 会话列表（侧栏最近 + 列表页共用一个数据源）
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    // 列表页搜索词（受控，路由切走再回来保留）
    var chatSearch by remember { mutableStateOf("") }
    // 模型选择器弹窗
    var showModelPicker by remember { mutableStateOf(false) }
    // 对话切换弹窗（标题栏 caret → 列表选一个会话）
    var showSwitcher by remember { mutableStateOf(false) }

    fun refreshSessions() {
        sessions = AppGraph.storage
            ?.let { SessionStore(it).listSummaries() }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }
    // 重命名/删除（列表页与侧栏共用 —— 两处各写一遍是漂移温床）
    val renameChat: (String, String) -> Unit = { id, title ->
        AppGraph.storage?.let { st ->
            val ss = SessionStore(st)
            val cur = ss.load(id)
            if (cur != null) {
                ss.save(cur.copy(title = title, updatedAt = System.currentTimeMillis()))
            }
        }
        refreshSessions()
    }
    val deleteChat: (String) -> Unit = { id ->
        AppGraph.storage?.let { SessionStore(it).delete(id) }
        // 删的正是当前会话 → 切回新会话，别让 activeSession 悬空
        if (id == AppGraph.sessionId) {
            AppGraph.storage?.let { st ->
                AppGraph.openSession(SessionStore(st).newSessionId())
                    ?.let { activeSession = it }
            }
        }
        refreshSessions()
    }

    LaunchedEffect(Unit) { refreshSessions() }   // 启动先灌一次

    // 打开指定会话并进对话页；id 空/失败则留在原地
    val openChat: (String) -> Unit = { id ->
        AppGraph.openSession(id)?.let { activeSession = it }
        refreshSessions()
        navigate(CcmRoute.CHAT)
    }
    // 新建会话：openSession 对「不存在的 id」= 建空会话，天然复用
    val newChat: () -> Unit = {
        val st = AppGraph.storage
        if (st != null) {
            AppGraph.openSession(SessionStore(st).newSessionId())?.let { activeSession = it }
        }
        refreshSessions()
        navigate(CcmRoute.CHAT)
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
    fun sendAndOpen(text: String, images: List<String> = emptyList()) {
        activeSession?.send(text, images)
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
                    sidebarOpen = !sidebarOpen
                },
                // 非首页时启用「后退」（回首页）；首页时禁用（灰色 #B7B5B0）
                onNavBack = if (route != CcmRoute.HOME) ({ navigate(CcmRoute.HOME) }) else null,
                onNavForward = null,
            )

            // ★ 未配置 API 的提示条（2026-09-27 加，同日修 z-order）
            //
            // 之前 initError 只存不显，用户看到的是「点哪都没反应」，
            // 而不是「你需要先配 API」。
            //
            // ⚠️ 位置教训：最初放在内容 Box 里、`when(route)` **之前** ——
            // Box 内后画的盖先画的，LandingScreen 不透明背景把它整个遮住，
            // 表现为「提示条代码在、UI 永远看不到」。挪到 Column 层
            // （TitleBar 之后）：占空间、被所有页面共用、不依赖 z-order。
            if (initError != null) {
                CcmNoticeBar(
                    message = initError,
                    // 点提示条才进设置；不点就不打扰（不再强制跳转）
                    onClick = { showSettings = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.72.dp, vertical = 7.36.dp),
                )
            }

            // ── 页面内容 ──────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                when (route) {
                    CcmRoute.HOME -> LandingScreen(
                        greeting = greetingFor(profileName),
                        // ★ 接线：首页输入框真的能发消息了（第18批带图）
                        onSend = { t, imgs -> sendAndOpen(t, imgs) },
                        onModelClick = { showModelPicker = true },
                        // onPickPrompt 已删（第17批）：点胶囊不再直接发 label 文本，
                        // 改为展开建议面板，点建议填入输入框（Web 行为）。
                    )

                    CcmRoute.CHAT -> if (activeSession != null) {
                        // delegated var（by remember）不能隐式 smart cast，显式断言
                        val session = activeSession as ChatSession
                        // ★ 接线：真数据。ChatScreenConnected 内部订阅
                        //   session.state（StateFlow），把 core 类型适配成 UI 类型。
                        //
                        // ★ 2026-09-27：补 title / Export / Rename（原来全是默认空转）。
                        val ctx = LocalContext.current
                        val store = AppGraph.storage?.let { SessionStore(it) }
                        var chatTitle by remember { mutableStateOf("新对话") }
                        var showRename by remember { mutableStateOf(false) }

                        // key 必须含 activeSession：标题栏 caret 切会话时
                        // route 不变（都在 CHAT），只 key route 会漏刷新 ——
                        // 标题停在上一个会话的名字。
                        LaunchedEffect(route, activeSession) {
                            // 进对话页读标题（loadTitle 只读文件头 4KB）
                            chatTitle = store?.loadTitle(AppGraph.sessionId) ?: "新对话"
                        }

                        ChatScreenConnected(
                            session = session,
                            title = chatTitle,
                            modelName = modelName,
                            onModelClick = { showModelPicker = true },
                            onSwitchClick = {
                                refreshSessions()   // 打开时拉最新
                                showSwitcher = true
                            },
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

                        // ── 对话切换（2026-09-28：caret 原是死图标）──────
                        if (showSwitcher) {
                            AlertDialog(
                                onDismissRequest = { showSwitcher = false },
                                title = { Text("切换对话", style = CCMText.body14) },
                                text = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 420.dp)
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        if (sessions.isEmpty()) {
                                            Text(
                                                text = "还没有其他对话",
                                                style = CCMText.body13,
                                                color = CCMTheme.colors.textSecondary,
                                            )
                                        }
                                        sessions.forEach { it2 ->
                                            val cur = it2.sessionId == AppGraph.sessionId
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        openChat(it2.sessionId)
                                                        showSwitcher = false
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(
                                                    text = it2.displayName,
                                                    style = CCMText.body13,
                                                    color = if (cur) CCMTheme.colors.accent
                                                    else CCMTheme.colors.textMain,
                                                    modifier = Modifier.weight(1f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (cur) Text("✓", style = CCMText.body13, color = CCMTheme.colors.accent)
                                            }
                                        }
                                    }
                                },
                                confirmButton = {},
                                dismissButton = {
                                    TextButton(onClick = { showSwitcher = false }) { Text("关闭", style = CCMText.body13) }
                                },
                            )
                        }

                        // ── 模型选择器（2026-09-27，原来是 TODO 空转）────
                        //
                        // 数据源：ProviderStore.list()（与 CLI /config 同一份
                        // config.json）。选中 = setCurrent + **重建当前会话** ——
                        // ApiClient 是 ChatSession.create 时用当时 cfg 装配的，
                        // 不重建的话切了不生效（CLI 那边是热读，这边是快照）。
                        // 重建走 AppGraph.openSession(同 id)：dispose 会先 flush
                        // 自动保存，历史不丢。
                        if (showModelPicker) {
                            val pstore = AppGraph.storage
                                ?.let { com.ccm.app.core.provider.ProviderStore(it) }
                            val items = pstore?.list() ?: emptyList()
                            AlertDialog(
                                onDismissRequest = { showModelPicker = false },
                                title = { Text("选择模型") },
                                text = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .verticalScroll(rememberScrollState()),
                                    ) {
                                        if (items.isEmpty()) {
                                            Text(
                                                text = "还没有 Provider —— 到「设置 → 模型」里先加一个",
                                                style = CCMText.body13,
                                                color = CCMTheme.colors.textSecondary,
                                            )
                                        }
                                        items.forEach { it2 ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                                    .clickable(enabled = it2.enabled) {
                                                        val ok = pstore?.setCurrent(it2.id) == true
                                                        if (ok) {
                                                            profileRefreshKey++   // 刷新顶栏/首页模型名
                                                            // 重建会话让 ApiClient 吃到新配置（历史由 dispose flush 保住）
                                                            AppGraph.openSession(AppGraph.sessionId)
                                                                ?.let { activeSession = it }
                                                        }
                                                        showModelPicker = false
                                                    }
                                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = it2.name,
                                                        style = CCMText.body14,
                                                        color = if (it2.enabled) CCMTheme.colors.textMain
                                                        else CCMTheme.colors.textSecondary,
                                                    )
                                                    Text(
                                                        text = it2.model,
                                                        style = CCMText.body12,
                                                        color = CCMTheme.colors.textSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                                if (it2.isCurrent) {
                                                    Text("✓", color = CCMTheme.colors.accent, style = CCMText.body14)
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {},
                                dismissButton = {
                                    TextButton(onClick = { showModelPicker = false }) { Text("关闭") }
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

                    CcmRoute.CHATS -> {
                        // 进列表页时刷新（新建/改名/删除后回来也是新的）
                        LaunchedEffect(route) { refreshSessions() }
                        ChatsScreen(
                            chats = sessions.map {
                                ChatSummaryUi(
                                    id = it.sessionId,
                                    title = it.displayName,
                                    updatedAt = it.updatedAt,
                                )
                            },
                            searchQuery = chatSearch,
                            onSearchChange = { chatSearch = it },
                            onOpenChat = { ui -> openChat(ui.id) },
                            onNewChat = newChat,
                            // 行菜单：重命名 / 删除（公用 lambda）
                            onRenameChat = renameChat,
                            onDeleteChat = deleteChat,
                        )
                    }

                    CcmRoute.SETTINGS -> SettingsScreen(
                        onClose = { navigate(CcmRoute.HOME) },
                    )

                    CcmRoute.PROJECTS -> ProjectsScreen(
                        projects = emptyList(),
                        onCreate = { },
                    )

                    CcmRoute.CUSTOMIZE -> CustomizeScreen(onBack = { navigate(CcmRoute.HOME) })

                    CcmRoute.ARTIFACTS -> ArtifactsScreen(
                        items = emptyList(),
                        onNewArtifact = { },
                    )

                    CcmRoute.COWORK -> CoworkScreen()

                    CcmRoute.SCHEDULED -> ScheduledScreen()

                    else -> LandingScreen(
                        greeting = greetingFor(profileName),
                        onSend = { t, imgs -> sendAndOpen(t, imgs) },
                        onModelClick = { showModelPicker = true },
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
        SidebarDrawer(
            open = sidebarOpen,
            onClose = { sidebarOpen = false },
            onNavigate = { key ->
                val target = when (key) {
                    "chats" -> CcmRoute.CHATS
                    "projects" -> CcmRoute.PROJECTS
                    "artifacts" -> CcmRoute.ARTIFACTS
                    "scheduled" -> CcmRoute.SCHEDULED
                    else -> CcmRoute.HOME
                }
                navigate(target)
            },
            // 「新对话」= 真新建会话（原来只回首页 —— 旧会话还挂着）
            onNewChat = newChat,
            onCustomize = { navigate(CcmRoute.CUSTOMIZE) },
            onOpenProfile = { navigate(CcmRoute.SETTINGS) },
            // ★ 2026-09-27 修「侧边栏点不动」：
            //   下面两个回调原来**根本没传**，UI 侧拿到的是默认空实现
            //   → 点「搜索」「聊天/协作/代码」胶囊完全没反应。
            // 搜索 → 会话列表页（真搜索在那里；原来错跳设置页）
            onSearch = {
                sidebarOpen = false
                navigate(CcmRoute.CHATS)
            },
            // 胶囊路由 —— 对齐 Web（Sidebar.tsx:209-240 实测）：
            //   聊天 → / ；协作 → /cowork ；**代码在 Web 是 disabled: true**（点了没反应是预期）。
            //   Scheduled（计划任务）在 Web 走侧栏菜单项，不走胶囊。
            onPillChange = { key ->
                when (key) {
                    "协作" -> navigate(CcmRoute.COWORK)
                    else -> navigate(CcmRoute.HOME)   // "聊天" 及未知值回首页
                }
            },
            // 当前激活胶囊跟路由走（原来写死默认"聊天"，进协作页还高亮聊天）
            activePill = when (route) {
                CcmRoute.COWORK, CcmRoute.SCHEDULED -> "协作"
                else -> "聊天"
            },
            userName = profileName ?: "",
            // ★ 最近对话真数据（原来没传 → 永远空列表）
            recentChats = sessions.take(8).map {
                ChatSummary(id = it.sessionId, title = it.displayName, updatedAt = it.updatedAt)
            },
            onOpenChat = { c ->
                sidebarOpen = false
                openChat(c.id)
            },
            onRenameChat = renameChat,
            onDeleteChat = deleteChat,
        )

        // 侧栏打开时刷新一次（刚在别处新建的会话要出现）
        LaunchedEffect(sidebarOpen) { if (sidebarOpen) refreshSessions() }
    }
}
