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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import com.ccm.app.ui.theme.CCMText
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ccm.app.ui.chat.ModelPickerMenu
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    //
    // 【2026-10-06 问题12 修复】原来直接读 AppGraph.initError（静态值）——
    // 用户在设置页加了 Provider 后，这个值还是「尚未配置 API」，
    // 顶部横幅**不消失**（用户报「添加一个供应商后顶部横幅仍显示尚未配置API」）。
    // 现在：initError 也随 AppGraph.session 的变化重算 ——
    // session 非 null 说明装配成功，无论 initError 说什么都不该再报警。
    // 【2026-10-06 问题42 修复·第二版】上一版写的是
    //   `initError ?: if (session != null) null else AppGraph.initError`
    // —— **`initError` 参数优先，导致修复完全失效**：
    //   MainActivity 传的是 `AppGraph.initError` 的**静态快照**（永远非 null），
    //   加完 Provider 后 session 变非空，但 initError 还在 → 横幅不消失。
    //   用户报「设置完配置后顶部横幅还是不消失，需要重启APP」。
    //
    // 正确逻辑：**session 是否存在是唯一权威** ——
    // session 非空说明装配成功，无论 initError 说什么都不该再报警。
    val effectiveError = if (com.ccm.app.AppGraph.session != null) null
        else (initError ?: com.ccm.app.AppGraph.initError)

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
    /** 市场（skill / MCP / 插件 的下载入口）—— 2026-10-06 新增。 */
    MARKET("/market"),
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun AppScaffold(session: ChatSession?, initError: String?) {

    val colors = CCMTheme.colors
    // ★ 2026-10-01 修 CI #218：slash handler 的 Context —— 原来加在 CcmApp
    //   里，但 sendAndOpen / when(hres) / /copy 全在 AppScaffold 作用域 →
    //   Unresolved 'appCtx'。使用点在哪就在哪取。
    val appCtx = LocalContext.current

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
        // ★ 2026-09-30 修「明明选了模型还显示未配置模型」：
        //   实测 config.json 里 provider 的 `model` 主字段是空串（模型名只配在
        //   `models` 数组里），旧逻辑 `currentProvider?.model?.takeIf{非空}` 直接
        //   落 null → 显示「未配置模型」（Sheet 里「当前 ·」后面也是空的，同一根因）。
        //   fallback 链：主 model → models[0]。
        com.ccm.app.AppGraph.storage
            ?.let { com.ccm.app.core.provider.AppConfig.load(it.configFile).config }
            ?.let { cfg ->
                val p = cfg.currentProvider
                val model = p?.model?.takeIf { it.isNotBlank() }
                    ?: p?.models?.firstOrNull { it.isNotBlank() }
                model?.let { "$it(${p?.id ?: cfg.current})" }
            }
            ?.takeIf { it.isNotBlank() }
            ?: "未配置模型"
    }
    // 【2026-10-06 问题21 修复】原来用 remember —— Activity 被系统回收后
    // 重建时 route 重置为 HOME，但 activeSession 由 AppGraph 恢复，
    // 表现为「显示首页样式，发消息却进的是原对话」。改用 rememberSaveable。
    var route by rememberSaveable { mutableStateOf(CcmRoute.HOME) }
    // 设置是**覆盖层不是路由**（对齐 Web：showSettings 状态，location 不变）
    var showSettings by remember { mutableStateOf(false) }
    // 【2026-10-06 问题21 修复】记录「进设置前在哪」——
    // 关闭设置时回去，而不是一律跳首页。
    // 原来 `onClose = { navigate(CcmRoute.HOME) }` 是 bug：
    // 从对话页进设置，关闭后跳首页 → 用户报「切页回来变首页样式」。
    var routeBeforeSettings by remember { mutableStateOf<CcmRoute?>(null) }

    /** 切页 —— 对齐 Web：路由变化时自动收起抽屉（`App.tsx:378`） */
    fun navigate(to: CcmRoute) {
        // 【2026-10-06 问题21】进设置页前记住当前页（关闭时回去）
        if (to == CcmRoute.SETTINGS && route != CcmRoute.SETTINGS) {
            routeBeforeSettings = route
        }
        route = to
        sidebarOpen = false
    }

    // ══════════════════════════════════════════════════════════════
    //  【2026-09-27 第2批】会话切换 + 历史列表真数据
    // ══════════════════════════════════════════════════════════════
    // activeSession：当前打开的会话。init 结果是它，侧栏/列表点进来后
    // 被 AppGraph.openSession(id) 换掉。原参数 session 只当初始值。
    //
    // 【2026-10-06 问题21 修复】原来是裸 `remember { mutableStateOf(session) }` ——
    // **只在首次组合时取 session 的值**。场景：
    //   用户在设置页加了 Provider → AppGraph.session 变非空 →
    //   MainActivity 重组（session 参数变了）→ **但 activeSession 还是 null**。
    //   此时 route 若在 CHAT，会走 `else` 分支渲染空态 ChatScreen
    //   （看起来就是「首页样式」）；发消息却走 AppGraph.session（原对话）——
    //   与用户报的「本该是对话页，却显示成了首页的样式，发条消息过去
    //   还是那个对话页」完全吻合。
    //
    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 问题21 修复·第二版】
    //
    // 上一版用 `remember(session)` —— **每次 session 参数变都重置
    // activeSession**。副作用：切页/重组时若 AppGraph.session 有变化
    // （哪怕是同一个实例被重新读），activeSession 被覆盖 →
    // 如果那一刻它是 null，就渲染空态（用户看到的「首页样式」）。
    //
    // 正确做法：**只在「从 null 变非 null」时同步** ——
    // 那是「用户在设置页加了 Provider」这个唯一需要同步的场景。
    // 其他情况（同一实例、非 null 变另一个非 null）不碰 —— 用户手动
    // 切的会话不能被冲掉。
    // ══════════════════════════════════════════════════════════════
    var activeSession by remember { mutableStateOf(session) }
    LaunchedEffect(session) {
        val g = com.ccm.app.AppGraph.session
        // 【2026-10-06 修 P0】原来是「只在 activeSession 为空时补上」——
        // 但 rebuild（设置页改配置 / 加 Provider / 换环境）会创建**新实例**
        // 并设为 AppGraph.session，此时 activeSession 非空、不更新 →
        // UI 继续用旧实例（旧 ApiClient）→「改了配置要重启才生效」。
        //
        // 现在：实例变化即同步（`!==` 判同一性，不是 equals）。
        // 为什么安全：AppGraph.session 是**权威**（rebuild/openSession 都更新它），
        // 用户手动切会话也走 openSession → 同样反映在 AppGraph.session 上。
        if (g != null && activeSession !== g) {
            activeSession = g
        }
    }
    // 【2026-10-06 删】原来这里有「问题21 诊断」的 logcat 日志
    // （每次切页/切会话打一行）。诊断已完成，且它是个 LaunchedEffect ——
    // 每切一次页就多跑一次协程，纯噪音。
    // 会话列表（侧栏最近 + 列表页共用一个数据源）
    var sessions by remember { mutableStateOf<List<SessionSummary>>(emptyList()) }
    // 列表页搜索词（受控，路由切走再回来保留）
    var chatSearch by remember { mutableStateOf("") }
    // 模型选择器弹窗
    var showModelPicker by remember { mutableStateOf(false) }
    var modelRefreshPending by remember { mutableStateOf(false) }
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
    //
    // 【2026-10-06 问题3 修复】原来跳 CHAT（对话页）—— 但新会话是空的，
    // 对话页看起来就是「一片空白 + 输入框在底部」，用户困惑。
    // 现在跳 HOME（首页）：有大标题问候语 + 居中的输入卡，
    // 语义就是「开始一个新对话」。用户发第一条消息后自动进对话页
    // （sendAndOpen 里 navigate(CHAT)）。
    val newChat: () -> Unit = {
        val st = AppGraph.storage
        if (st != null) {
            AppGraph.openSession(SessionStore(st).newSessionId())?.let { activeSession = it }
        }
        refreshSessions()
        navigate(CcmRoute.HOME)
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
        // ★ 2026-09-29 slash 全局拦截：原来拦截只在 ChatScreenConnected 的
        //   onSend 里，**首页输入斜杠命令直接 session.send 发给模型**
        //   （模型回一句「我不是这样用的」）。提到唯一入口，两端一致。
        val t = text.trim()
        if (t.startsWith("/") && images.isEmpty()) {
            // 【2026-10-06 用户反馈】slash 命令无用户气泡 ——
            // 执行前先把命令文本作为用户气泡上屏（让用户看到自己敲了什么）。
            activeSession?.injectUserEcho(t)
            // ★ 2026-09-30 统一 handler：四大分区（会话/查询/配置/工具）先过一遍，
            //   认得的直接执行；不认的才落下面的老 when（基础命令 + 兜底）。
            val hres = com.ccm.app.ui.chat.handleSlashCommand(
                t,
                com.ccm.app.ui.chat.SlashContext(
                    session = activeSession,
                    appContext = appCtx.applicationContext,
                    navigate = { r ->
                        navigate(if (r == "home") CcmRoute.HOME else CcmRoute.SETTINGS)
                    },
                    newChat = { newChat() },
                    // 【2026-10-06 问题40】goal 模式（同 ChatScreenConnected）
                    startGoal = { _, first ->
                        val gs = com.ccm.app.AppGraph.toolsResult?.goalStore
                        val sc = AppGraph.appScope
                        val sess = activeSession
                        if (gs != null && sc != null && sess != null) {
                            sc.launch {
                                try { sess.runGoal(gs, first) } catch (_: Throwable) {}
                            }
                        }
                    },
                    goalStatusText = {
                        val gs = com.ccm.app.AppGraph.toolsResult?.goalStore
                        gs?.get(AppGraph.sessionId)?.let { g -> gs.render(g) }
                    },
                    openPanel = { p ->
                        when (p) {
                            "model" -> showModelPicker = true
                            "switcher" -> {
                                // ★ B2（findbugs）：switcher sheet 在 when(route) 的
                                //   CHAT 分支内渲染 —— route=HOME 时只置 showSwitcher
                                //   不切页 = 永远看不到（点 /load 没反应）。
                                refreshSessions()
                                navigate(CcmRoute.CHAT)
                                showSwitcher = true
                            }
                            "style" -> navigate(CcmRoute.SETTINGS)
                        }
                    },
                    refreshSessions = { refreshSessions() },
                ),
            )
            if (hres != null && hres !is com.ccm.app.ui.chat.SlashResult.NotHandled) {
                when (hres) {
                    is com.ccm.app.ui.chat.SlashResult.Notice -> {
                        activeSession?.injectNotice(hres.markdown)
                        navigate(CcmRoute.CHAT)   // 看到通知要进对话页
                    }
                    is com.ccm.app.ui.chat.SlashResult.Navigate -> {
                        // ★ reviewer 应修#1：原来是空分支，首页 /delete /load
                        //   点了没反应（副作用没人执行）。
                        when (hres.route) {
                            "home" -> navigate(CcmRoute.HOME)
                            "settings" -> navigate(CcmRoute.SETTINGS)
                            "delete-current" -> {
                                // ★ 应修#2：/delete 不能只删文件 —— 走 deleteChat
                                //   才会重置 activeSession（否则悬空指向已删 id）。
                                deleteChat(com.ccm.app.AppGraph.sessionId)
                                navigate(CcmRoute.HOME)
                            }
                            else -> navigate(CcmRoute.HOME)
                        }
                    }
                    is com.ccm.app.ui.chat.SlashResult.OpenPanel -> {
                        // ★ 应修#1：补全（原来吞掉），与 openPanel 闭包同逻辑
                        when (hres.panel) {
                            "model" -> showModelPicker = true
                            "switcher" -> {
                                // ★ B2 同上：必须先切到 CHAT 才能看到 sheet
                                refreshSessions()
                                navigate(CcmRoute.CHAT)
                                showSwitcher = true
                            }
                            "style" -> navigate(CcmRoute.SETTINGS)
                        }
                    }
                    is com.ccm.app.ui.chat.SlashResult.Toast ->
                        android.widget.Toast.makeText(appCtx, hres.text, android.widget.Toast.LENGTH_SHORT).show()
                    else -> {}
                }
                return
            }
            when (t) {
                "/clear" -> {
                    activeSession?.clear()
                    navigate(CcmRoute.CHAT)
                    return
                }
                "/model" -> { showModelPicker = true; return }
                "/help" -> {
                    // ★ 应修#3：改为动态生成（原来手写 6 条且文案与实际拦截不符）
                    activeSession?.injectNotice(
                        "**可用命令（${com.ccm.app.ui.chat.COMMON_SLASH_COMMANDS.size} 个）**\n\n" +
                            com.ccm.app.ui.chat.COMMON_SLASH_COMMANDS.joinToString("\n") { (c, d) -> "- `$c` — $d" } +
                            "\n\n输入 `/` 可看候选面板。"
                    )
                    navigate(CcmRoute.CHAT)
                    return
                }
                "/compact" -> {
                    // 【2026-10-06 问题40】改用挂起版 —— 能等 PreCompact hook
                    val sess = activeSession
                    val sc = AppGraph.appScope
                    if (sess != null && sc != null) {
                        sc.launch {
                            val msg = try { sess.compactNowSuspend() } catch (t: Throwable) {
                                "压缩失败：${t.message}"
                            }
                            sess.injectNotice("**/compact**\n\n$msg")
                        }
                    } else {
                        activeSession?.injectNotice("**/compact**\n\n无会话")
                    }
                    navigate(CcmRoute.CHAT)
                    return
                }
                // ── 2026-09-30 扩充：与对话页同步 ──
                "/new" -> { newChat(); return }
                "/stop" -> { activeSession?.stop(); navigate(CcmRoute.CHAT); return }
                "/retry" -> { activeSession?.retryLast(); navigate(CcmRoute.CHAT); return }
                "/style" -> { navigate(CcmRoute.SETTINGS); return }
                "/context" -> {
                    // 2026-10-06 对齐 CLI：进度条 + 水位提示 + 估算兜底
                    val sess = activeSession
                    if (sess == null) {
                        navigate(CcmRoute.CHAT)
                    } else {
                        sess.injectNotice(sess.contextReport())
                        navigate(CcmRoute.CHAT)
                    }
                    return
                }
                "/cost" -> {
                    val st = activeSession?.state?.value
                    if (st == null) {
                        navigate(CcmRoute.CHAT)
                    } else {
                        val turns = st.bubbles.count { !it.isUser }
                        activeSession?.injectNotice(
                            "**用量**\n\n" +
                            "- 助手轮数：$turns\n" +
                            "- 最近输入 token：${st.inputTokens}\n" +
                            "- 最近输出 token：${st.outputTokens}\n\n" +
                            "_看上下文压力用 /context（有进度条和水位提示）。_"
                        )
                        navigate(CcmRoute.CHAT)
                    }
                    return
                }
                "/copy" -> {
                    val last = activeSession?.state?.value?.bubbles?.lastOrNull { !it.isUser }
                    if (last != null && last.text.isNotBlank()) {
                        try {
                            val cm = appCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("CCM", last.text))
                        } catch (_: Throwable) {}
                    }
                    navigate(CcmRoute.CHAT)
                    return
                }
                // /export /permissions 依赖对话页上下文，仍然进对话页处理
                else -> {
                    // 未支持的 slash（/config /undo…）**不发模型**，
                    // 进对话页给提示（ChatScreenConnected 的同款兜底会再拦一次，
                    // 这里提前拦省一次界面跳转的歧义）。
                    if (!setOf("/export", "/permissions").contains(t)) {
                        activeSession?.injectNotice(
                            "**${t.substringBefore(" ")} 在 APK 暂不可用**\n\n" +
                            "APK 共支持 ${com.ccm.app.ui.chat.COMMON_SLASH_COMMANDS.size} 个命令，输入 / 看候选面板。\n" +
                            when (t.substringBefore(" ")) {
                                "/config" -> "Provider 配置在 设置 → 模型。"
                                else -> "CLI 专属命令（/rewind /doctor 等）请到终端侧使用。"
                            }
                        )
                        navigate(CcmRoute.CHAT)
                        return
                    }
                    // ★ B5（findbugs 2026-10-01）：光 navigate 不执行 —— 拦截只在
                    //   onSend 触发，跳转不触发，落空输入框（点了没反应）。
                    //   · /permissions 已搬进 handler（前置分支直接执行，到不了这）
                    //   · /export 依赖对话页 onExport 回调 → 把命令写进 draft，
                    //     用户到对话页回车即执行（输入框有内容，不再是空框）
                    if (t == "/export") activeSession?.setDraft(t)
                    navigate(CcmRoute.CHAT)
                    return
                }
            }
        }
        // 【2026-10-06 问题1 修复】原来直接 `activeSession?.send(...)` ——
        // session 为 null（未配置 Provider）时**静默什么都不做**，
        // 用户按了发送毫无反应，以为 App 坏了。
        // 现在明确提示：Toast 说明原因 + 引导去设置页。
        if (activeSession == null) {
            android.widget.Toast.makeText(
                appCtx,
                "尚未配置模型 —— 请到「设置 → 模型」里添加一个 Provider",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            // 不跳页（跳设置页会让用户觉得「点了乱跳」，见下方旧注释）
            return
        }
        activeSession?.send(text, images)
        // ★ 不管有没有 session 都切到对话页 —— 用户按了发送/点了胶囊，
        //   就该看到「消息已发出」的界面。没配 Provider 时对话页会显示
        //   提示条（由 initError 驱动），而不是把人踢去设置页。
        //   踩过的坑：曾经 session==null 就 showSettings=true，
        //   用户点胶囊期待发消息，结果跳设置页 —— 像是「点了乱跳」。
        navigate(CcmRoute.CHAT)
    }

    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 问题40】AskUserQuestion 弹窗
    // ══════════════════════════════════════════════════════════════
    // 工具侧调 AppGraph.askUserBlocking() → 这里弹对话框 → 用户回答
    // → AppGraph.answerQuestion(答案)。
    val pendingQ = com.ccm.app.AppGraph.pendingQuestion.value
    if (pendingQ != null) {
        val (question, options) = pendingQ
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { com.ccm.app.AppGraph.answerQuestion(null) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(14.72.dp))
                    .clip(RoundedCornerShape(14.72.dp))
                    .background(colors.bgMain)
                    .border(1.dp, colors.border, RoundedCornerShape(14.72.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.2.dp),
            ) {
                Text(
                    "Agent 提问",
                    style = CCMText.body16.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMain,
                )
                Text(
                    question,
                    style = CCMText.body14,
                    color = colors.textMain,
                )
                if (options.isEmpty()) {
                    // 无选项 → 纯文本输入
                    var ans by remember { mutableStateOf("") }
                    androidx.compose.foundation.text.BasicTextField(
                        value = ans,
                        onValueChange = { ans = it },
                        textStyle = CCMText.body14.copy(color = colors.textMain),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.claudeOrange),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp)
                            .clip(RoundedCornerShape(7.36.dp))
                            .background(colors.input)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.2.dp, Alignment.End),
                    ) {
                        Text(
                            "跳过",
                            style = CCMText.body13,
                            color = colors.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { com.ccm.app.AppGraph.answerQuestion(null) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        Text(
                            "回答",
                            style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                            color = if (ans.isNotBlank()) Color(0xFFD97757) else colors.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = ans.isNotBlank()) {
                                    com.ccm.app.AppGraph.answerQuestion(ans.trim())
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    // ── 有选项：选项 + **自由输入框并存**（2026-10-06 修）──────
                    //
                    // 原来有选项时**没有输入框** —— 用户只能从 Agent 给的选项里挑，
                    // 想补充说明（「选 A 但顺便…」）或都不满意时无处可写。
                    // 现在：选项照常点选即答；下方始终有输入框，想自己写就写。
                    options.forEach { opt ->
                        Text(
                            opt,
                            style = CCMText.body14,
                            color = colors.textMain,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.36.dp))
                                .clickable { com.ccm.app.AppGraph.answerQuestion(opt) }
                                .background(colors.input)
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                    // 自由输入框（选填）—— 与选项并存，回车即提交
                    var freeText by remember { mutableStateOf("") }
                    androidx.compose.foundation.text.BasicTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        textStyle = CCMText.body14.copy(color = colors.textMain),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.claudeOrange),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp)
                            .clip(RoundedCornerShape(7.36.dp))
                            .background(colors.input)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.2.dp, Alignment.End),
                    ) {
                        Text(
                            "跳过",
                            style = CCMText.body13,
                            color = colors.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { com.ccm.app.AppGraph.answerQuestion(null) }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                        // 「回答」按钮：输入框有内容才亮，点了提交自由文本
                        Text(
                            "回答",
                            style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                            color = if (freeText.isNotBlank()) Color(0xFFD97757) else colors.textSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(enabled = freeText.isNotBlank()) {
                                    com.ccm.app.AppGraph.answerQuestion(freeText.trim())
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  手机操作模式选择（2026-10-06 加，对齐 CLI 的 modePrompter）
    // ══════════════════════════════════════════════════════════════
    // 工具侧（PhoneTools.modeGate）调 AppGraph.requestPhoneModeBlocking()
    // → 这里弹框 → 用户选 → AppGraph.answerPhoneMode(mode)。
    //
    // 三种选项语义（CLI tools-phone.mjs:55）：
    //   前台 —— 操作主屏，用户看得见；记偏好，以后不再问
    //   后台 —— 虚拟副屏，静默；记偏好，以后不再问
    //   这次不操作 —— idle，**不记偏好**（一次性的「别动」）
    if (com.ccm.app.AppGraph.pendingPhoneMode.value) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { com.ccm.app.AppGraph.answerPhoneMode(null) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, RoundedCornerShape(14.72.dp))
                    .clip(RoundedCornerShape(14.72.dp))
                    .background(colors.bgMain)
                    .border(1.dp, colors.border, RoundedCornerShape(14.72.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(9.2.dp),
            ) {
                Text(
                    "手机操作模式",
                    style = CCMText.body16.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textMain,
                )
                Text(
                    "Agent 要用手机工具，选择这次怎么操作（选前两项会记住，以后不再问）",
                    style = CCMText.body12,
                    color = colors.textSecondary,
                )
                listOf(
                    Triple("foreground", "前台", "操作主屏 —— 你能看到它在点什么"),
                    Triple("background", "后台", "虚拟副屏 —— 静默运行，不占你屏幕"),
                    Triple("idle", "这次不操作", "仅本次会话有效，下次还会问"),
                ).forEach { (value, title, desc) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.36.dp))
                            .clickable { com.ccm.app.AppGraph.answerPhoneMode(value) }
                            .background(colors.input)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            title,
                            style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                            color = colors.textMain,
                        )
                        Text(
                            desc,
                            style = CCMText.body11,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
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

            val pendingSession = activeSession
            LaunchedEffect(pendingSession) {
                if (pendingSession != null) {
                    pendingSession.state.collect { state ->
                        if (modelRefreshPending && !state.running) {
                            val scope = AppGraph.appScope ?: return@collect
                            AppGraph.rebuild(appCtx, scope)?.let { activeSession = it }
                            modelRefreshPending = false
                        }
                    }
                }
            }

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

            val currentEffort = remember(profileRefreshKey) {
                val cfg = AppGraph.storage?.let { com.ccm.app.core.provider.AppConfig.load(it.configFile).config }
                cfg?.currentProvider?.effort?.takeIf { it.isNotBlank() } ?: cfg?.effort?.takeIf { it.isNotBlank() } ?: "none"
            }
            val applyEffortSelection: (String) -> Unit = { level ->
                AppGraph.storage?.let { st ->
                    com.ccm.app.core.provider.ProviderStore(st).setEffort(
                        AppGraph.storage?.let { com.ccm.app.core.provider.ProviderStore(it).load().current } ?: return@let,
                        level,
                    )
                    profileRefreshKey++
                    if (activeSession?.isRunning == true) modelRefreshPending = true
                    else AppGraph.appScope?.let { scope -> AppGraph.rebuild(appCtx, scope)?.let { activeSession = it } }
                }
            }

            // 模型选择写入配置后：空闲立即重建；忙时在任务结束后应用。
            val applyModelSelection: (String, String) -> Unit = { id, model ->
                val st = AppGraph.storage
                if (st != null) {
                    val ps = com.ccm.app.core.provider.ProviderStore(st)
                    ps.setModel(id, model)
                    ps.setCurrent(id)
                    profileRefreshKey++
                    if (activeSession?.isRunning == true) {
                        modelRefreshPending = true
                        android.widget.Toast.makeText(appCtx, "当前任务结束后切换模型生效", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        AppGraph.appScope?.let { scope -> AppGraph.rebuild(appCtx, scope)?.let { activeSession = it } }
                        modelRefreshPending = false
                    }
                }
                showModelPicker = false
            }



    // ── 页面内容 ──────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                when (route) {
                    CcmRoute.HOME -> LandingScreen(
                        greeting = greetingFor(profileName),
                        // ★ 接线：首页输入框真的能发消息了（第18批带图）
                        onSend = { t, imgs -> sendAndOpen(t, imgs) },
                        onModelClick = { showModelPicker = true },
                        // 加号菜单的「管理技能」「添加连接器」→ 定制页
                        onNavigateCustomize = { navigate(CcmRoute.CUSTOMIZE) },
                        modelPickerContent = {
                            if (showModelPicker) ModelPickerMenu(
                                expanded = true,
                                items = AppGraph.storage?.let { com.ccm.app.core.provider.ProviderStore(it).list() } ?: emptyList(),
                                thinkingEnabled = currentEffort != "none",
                                effort = currentEffort,
                                onPick = applyModelSelection,
                                onThinkingChange = { applyEffortSelection(if (it) "high" else "none") },
                                onEffortChange = applyEffortSelection,
                                onDismiss = { showModelPicker = false },
                            )
                        },
                        modelLabel = modelName,   // ★ 原来没传 → 永远显示默认「未配置模型」
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
                            onAutoTitle = { generated -> chatTitle = generated },
                            modelName = modelName,
                            onModelClick = { showModelPicker = true },
                            modelPickerContent = {
                                if (showModelPicker) {
                                    ModelPickerMenu(
                                        expanded = showModelPicker,
                                        dropUp = true,
                                        items = AppGraph.storage?.let { com.ccm.app.core.provider.ProviderStore(it).list() } ?: emptyList(),
                                        thinkingEnabled = currentEffort != "none",
                                        effort = currentEffort,
                                        onPick = applyModelSelection,
                                        onThinkingChange = { applyEffortSelection(if (it) "high" else "none") },
                                        onEffortChange = applyEffortSelection,
                                        onDismiss = { showModelPicker = false },
                                    )
                                }
                            },
                            onSwitchClick = {
                                refreshSessions()   // 打开时拉最新
                                showSwitcher = true
                            },
                            onNewChat = newChat,          // /new（2026-09-30 slash 扩充）
                            onOpenStyle = {               // /style → 设置页输出风格
                                navigate(CcmRoute.SETTINGS)
                            },
                            onNavigate = { r ->           // slash handler 导航
                                // ★ B1（findbugs 2026-10-01）：原来二元 if ——
                                //   "delete-current" 落 else 跳设置页、deleteChat
                                //   一次没跑（文件没删会话没重置）。首页路径是对的，
                                //   对话页这条漏了 when 分发。
                                when (r) {
                                    "delete-current" -> {
                                        deleteChat(com.ccm.app.AppGraph.sessionId)
                                        navigate(CcmRoute.HOME)
                                    }
                                    "settings" -> navigate(CcmRoute.SETTINGS)
                                    else -> navigate(CcmRoute.HOME)
                                }
                            },
                            onExport = {
                                // Web 的 Export 是导出 markdown（第23批升级格式）：
                                // 二级标题 + 段落 + 思考折叠块，粘进任何 md 编辑器可读
                                val text = buildString {
                                    appendLine("# ${chatTitle}")
                                    appendLine()
                                    appendLine("> 导出自 CCM · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}")
                                    appendLine()
                                    session.state.value.bubbles.forEach { b ->
                                        if (b.isUser) {
                                            appendLine("## 我")
                                        } else {
                                            appendLine("## Claude")
                                            if (b.thinking.isNotBlank()) {
                                                appendLine()
                                                appendLine("<details><summary>思考过程</summary>")
                                                appendLine()
                                                appendLine(b.thinking)
                                                appendLine()
                                                appendLine("</details>")
                                            }
                                        }
                                        appendLine()
                                        appendLine(b.text)
                                        appendLine()
                                    }
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
                            // ★ 2026-09-29：AlertDialog → 底部滑出面板（选择类不该是弹窗）
                            androidx.compose.material3.ModalBottomSheet(
                                onDismissRequest = { showSwitcher = false },
                                containerColor = colors.bgMain,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 24.dp),
                                ) {
                                    Text(
                                        text = "切换对话",
                                        style = CCMText.body14.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                                        color = colors.textMain,
                                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                    )
                                    if (sessions.isEmpty()) {
                                        Text(
                                            text = "还没有其他对话",
                                            style = CCMText.body13,
                                            color = colors.textSecondary,
                                            modifier = Modifier.padding(horizontal = 20.dp),
                                        )
                                    }
                                    sessions.forEach { it2 ->
                                        val cur = it2.sessionId == AppGraph.sessionId
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    openChat(it2.sessionId)
                                                    showSwitcher = false
                                                }
                                                .padding(horizontal = 20.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = it2.displayName,
                                                style = CCMText.body13,
                                                color = if (cur) colors.accent
                                                else colors.textMain,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            if (cur) Text("✓", style = CCMText.body13, color = colors.accent)
                                        }
                                    }
                                }
                            }
                        }

                        // ── 模型选择器（2026-09-27，原来是 TODO 空转）────
                        //
                        // 数据源：ProviderStore.list()（与 CLI /config 同一份
                        // config.json）。选中 = setCurrent + **重建当前会话** ——
                        // ApiClient 是 ChatSession.create 时用当时 cfg 装配的，
                        // 不重建的话切了不生效（CLI 那边是热读，这边是快照）。
                        // 重建走 AppGraph.openSession(同 id)：dispose 会先 flush
                        // 自动保存，历史不丢。
                    } else {
                        // ══════════════════════════════════════════════════
                        //  【2026-10-06 问题21 修复·第三版】兜底恢复
                        //
                        //  到这里说明 activeSession == null。但 AppGraph.session
                        //  可能**有**会话（UI 状态没跟上）—— 用户报「显示成首页
                        //  样式，发条消息过去还是那个对话页」就是这个：
                        //  UI 拿不到 session，但底层还在用原会话。
                        //
                        //  这里主动拉一次，拉到了就恢复（LaunchedEffect 触发重组）。
                        // ══════════════════════════════════════════════════
                        LaunchedEffect(Unit) {
                            val g = com.ccm.app.AppGraph.session
                            if (g != null) activeSession = g
                        }
                        // 没配 Provider —— 渲染空态而不是崩。
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
                            onRefresh = { refreshSessions() },   // 第42批下拉刷新
                        )
                    }

                    CcmRoute.SETTINGS -> SettingsScreen(
                        // 【2026-10-06 问题21】回到「进设置前的那一页」，
                        // 而不是一律跳首页（原来那样会丢当前对话）
                        onClose = { navigate(routeBeforeSettings ?: CcmRoute.HOME) },
                    )

                    CcmRoute.PROJECTS -> {
                        // ★ 2026-10-01：项目落地为「workspace 子目录」——
                        //   原来 projects=emptyList() + 按钮只弹 Toast（死页）。
                        //   新建 = mkdir；打开 = 以该目录开会话（cwd 归项目）。
                        val projCtx = androidx.compose.ui.platform.LocalContext.current
                        var projRefresh by remember { mutableStateOf(0) }
                        var showNewProject by remember { mutableStateOf(false) }
                        var newProjName by remember { mutableStateOf("") }
                        val projects = remember(route, projRefresh) {
                            AppGraph.storage?.let { st ->
                                com.ccm.app.core.project.ProjectStore(st).list().map { pj ->
                                    com.ccm.app.ui.pages.ProjectItemUi(
                                        id = pj.id,
                                        name = pj.name,
                                        description = pj.path,
                                        chatCount = pj.fileCount,
                                    )
                                }
                            } ?: emptyList()
                        }
                        ProjectsScreen(
                            projects = projects,
                            onCreate = { showNewProject = true },
                            onOpen = { ui ->
                                // 打开项目 = 新建一个以该项目目录为 cwd 的会话
                                // （简化：先跳首页 —— cwd 切换需要 AppGraph 支持，
                                //  当前先让用户看到目录内容，会话 cwd 沿用默认）
                                android.widget.Toast.makeText(
                                    projCtx,
                                    "项目目录：${ui.description}",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            },
                        )
                        if (showNewProject) {
                            androidx.compose.material3.AlertDialog(
                                onDismissRequest = { showNewProject = false },
                                title = { Text("新建项目", style = CCMText.body16) },
                                text = {
                                    com.ccm.app.ui.settings.SettingsTextField(
                                        value = newProjName,
                                        onValueChange = { newProjName = it },
                                        placeholder = "项目名（作为目录名）",
                                    )
                                },
                                confirmButton = {
                                    androidx.compose.material3.TextButton(onClick = {
                                        val r = AppGraph.storage?.let { st ->
                                            com.ccm.app.core.project.ProjectStore(st).create(newProjName)
                                        }
                                        if (r?.isSuccess == true) {
                                            projRefresh++
                                            newProjName = ""
                                            showNewProject = false
                                        } else {
                                            android.widget.Toast.makeText(
                                                projCtx,
                                                r?.exceptionOrNull()?.message ?: "创建失败",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }) { Text("创建", style = CCMText.body13) }
                                },
                                dismissButton = {
                                    androidx.compose.material3.TextButton(onClick = { showNewProject = false }) {
                                        Text("取消", style = CCMText.body13)
                                    }
                                },
                            )
                        }
                    }

                    CcmRoute.MARKET -> {
                        com.ccm.app.ui.pages.MarketScreen(
                            onBack = { navigate(CcmRoute.HOME) },
                        )
                    }

                    CcmRoute.CUSTOMIZE -> {
                        // H4：技能列表来自两个来源 ——
                        // 1) assets 内置技能清单（播种/展示用）
                        // 2) workspace/.claude/skills/*/SKILL.md（用户放的）
                        // 这里先接 1（内置清单随 APK 走，无需文件系统权限）；
                        // 自定义技能目录扫描待 workspace 目录初始化后接入。
                        val skills = remember(route) {
                            com.ccm.app.core.skill.BuiltinSkills.all().map { sk ->
                                com.ccm.app.ui.pages.CustomizeItem(
                                    id = sk.id,
                                    name = sk.name,
                                    description = sk.description,
                                    kind = "skills",
                                )
                            } + com.ccm.app.core.skill.BuiltinSkills.connectors().map { cn ->
                                // ★ 2026-10-01：connectors tab 原来永远空（只传了 skills）
                                com.ccm.app.ui.pages.CustomizeItem(
                                    id = cn.id,
                                    name = cn.name,
                                    description = cn.description,
                                    kind = "connectors",
                                )
                            }
                        }
                        CustomizeScreen(
                            sections = skills,
                            onBack = { navigate(CcmRoute.HOME) },
                        )
                    }

                    CcmRoute.ARTIFACTS -> {
                        // ★ H2（audit-pages #2）：原来 items=emptyList() —— 灵感 tab
                        //   永远空、新建是死按钮。现接 assets 灵感库（与首页建议同源）。
                        val artCtx = androidx.compose.ui.platform.LocalContext.current
                        val artItems = remember(route) {
                            com.ccm.app.core.inspiration.InspirationLibrary.ensure(artCtx)
                                .values.map { ins ->
                                    com.ccm.app.ui.pages.ArtifactItemUi(
                                        id = ins.name,
                                        title = ins.name,
                                        description = ins.description,
                                        previewText = ins.starting_prompt.take(160),
                                        // UI 五分类 ← Web category 映射（M3 的「放松一下」由此可达）
                                        category = when (ins.category) {
                                            "learn" -> "学习"
                                            "life-hacks" -> "生活技巧"
                                            "games" -> "游戏"
                                            "creative" -> "创意"
                                            "touch-grass" -> "放松一下"
                                            else -> "创意"
                                        },
                                    )
                                }
                        }
                        ArtifactsScreen(
                            items = artItems,
                            // 「我的产物」需要服务端 artifact 存储 —— APK 无此数据源，保持空态
                            onNewArtifact = {
                                // Web 是开产物编辑器；APK 无编辑器 → 回首页从提示词开始（注释在案）
                                navigate(CcmRoute.HOME)
                            },
                            onOpenItem = { item ->
                                // 点灵感 → 用它的 starting_prompt 开新对话（有实际可用价值，
                                // 比 Web 打开只读预览更进一步）
                                com.ccm.app.core.inspiration.InspirationLibrary
                                    .ensure(artCtx)[item.id]?.starting_prompt
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { prompt -> sendAndOpen(prompt, emptyList()) }
                            },
                        )
                    }

                    CcmRoute.COWORK -> CoworkScreen(
                        modelName = modelName,
                        // H1：协作输入发进主对话（APK 无 Web 的协作任务后端 ——
                        // Web 是 onStartTask 开独立任务流，这里是诚实降级）
                        onSend = { t, imgs -> sendAndOpen(t, imgs) },
                    )

                    CcmRoute.SCHEDULED -> {
                        // 【2026-10-06 问题32】加刷新 key —— 创建任务后要重读列表
                        var cronRefresh by remember { mutableStateOf(0) }
                        val cronTasks = remember(route, cronRefresh) {
                            com.ccm.app.AppGraph.toolsResult?.cron?.list()
                                ?.map { t ->
                                    com.ccm.app.ui.pages.ScheduledTaskUi(
                                        id = t.id,
                                        // 标题：prompt 前 30 字（与 CLI /todos 的紧凑展示一致）
                                        title = t.prompt.take(30).replace("\n", " ")
                                            .ifBlank { "(空任务)" },
                                        schedule = t.cron + if (t.recurring) " · 循环" else " · 一次性",
                                    )
                                } ?: emptyList()
                        }
                        ScheduledScreen(
                            tasks = cronTasks,
                            // 【2026-10-06 问题32 修复】原来 onNewTask 跳协作页 ——
                            // 用户报「计划任务中新建任务直接跳转协作模式页」。
                            // 现在 ScheduledScreen 内部弹创建对话框（onNewTask 不再用），
                            // 这里只接 onCreateTask：真建任务 + 刷新列表。
                            onNewTask = {},
                            onCreateTask = onCreate@{ cronExpr, promptText, durable ->
                                val store = com.ccm.app.AppGraph.toolsResult?.cron ?: return@onCreate false
                                try {
                                    store.create(cronExpr, promptText, recurring = true, durable = durable)
                                    cronRefresh++   // 触发列表重读
                                    android.widget.Toast.makeText(appCtx, "已创建", android.widget.Toast.LENGTH_SHORT).show()
                                    true
                                } catch (t: Throwable) {
                                    android.widget.Toast.makeText(appCtx, "创建失败：${t.message}", android.widget.Toast.LENGTH_LONG).show()
                                    false
                                }
                            },
                        )
                    }

                    else -> LandingScreen(
                        greeting = greetingFor(profileName),
                        onSend = { t, imgs -> sendAndOpen(t, imgs) },
                        onNavigateCustomize = { navigate(CcmRoute.CUSTOMIZE) },
                        onModelClick = { showModelPicker = true },
                        modelPickerContent = {
                            if (showModelPicker) ModelPickerMenu(
                                expanded = true,
                                items = AppGraph.storage?.let { com.ccm.app.core.provider.ProviderStore(it).list() } ?: emptyList(),
                                thinkingEnabled = currentEffort != "none",
                                effort = currentEffort,
                                onPick = applyModelSelection,
                                onThinkingChange = { applyEffortSelection(if (it) "high" else "none") },
                                onEffortChange = applyEffortSelection,
                                onDismiss = { showModelPicker = false },
                            )
                        },
                        modelLabel = modelName,   // ★ 原来没传 → 永远显示默认「未配置模型」
                    )
                }
            }
        }

        // ── 设置覆盖层（全屏，在抽屉之下）─────────────────────────────
        if (showSettings) {
            // 【2026-10-06 加】拦截系统返回键 —— 原来按返回直接退出 App
            // （全屏覆盖层没有任何 BackHandler，Activity 收到返回就 finish）。
            // 用户从设置页按返回的期望是「关掉设置回对话」，不是「退出程序」。
            androidx.activity.compose.BackHandler(enabled = true) {
                showSettings = false
            }
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
        // 【2026-10-06 加】抽屉打开时拦截返回键（同设置页的理由）
        if (sidebarOpen) {
            androidx.activity.compose.BackHandler(enabled = true) {
                sidebarOpen = false
            }
        }
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
            onMarket = { navigate(CcmRoute.MARKET) },
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

        // ★ 设置页关闭时刷新 profileName/modelName —— 加/改 Provider 在
        //   设置页里发生，CcmApp 的 remember(profileRefreshKey) 不知道，
        //   不刷新的话「加了配置模型名还显示未配置」。
        //
        // 【2026-10-06 用户报·根因】只刷 UI 显示**不够** —— 顶部横幅的判据是
        // `AppGraph.session != null`（**装配结果**），而加 Provider 只写了
        // config.json，session 还是 null → 横幅不消失，要重启。
        // 状态栏却显示得出模型名（它现读 config.json）→ 出现
        // 「状态栏有模型、横幅说没配置」的自相矛盾画面。
        //
        // 现在：设置页一关就**重建 AppGraph**（覆盖所有配置路径 —— 向导、
        // 手动添加、改 key/URL 都走这里收口，不依赖各页面自己记得调）。
        // rebuild 保留当前历史，代价几毫秒；没配 Provider 时它会返回 null
        // （横幅继续显示，正确）。
        LaunchedEffect(showSettings) {
            if (!showSettings) {
                profileRefreshKey++
                try {
                    com.ccm.app.AppGraph.appScope?.let { scope ->
                        // 【2026-10-06 修 P0】接住 rebuild 的返回值并更新
                        // activeSession —— 原来丢弃返回值，于是：
                        //   ① AppGraph.session 换成新实例，但 UI 的
                        //      activeSession 还是旧的 → 对话页继续用旧
                        //      ApiClient → 「改了 key 要重启才生效」
                        //   ② 旧 session 的 SessionAuto 协程永不停止
                        //      （rebuild 只调 stop() 不碰 SessionAuto，
                        //      而兜底的 container?.shutdown() 当时是 no-op）
                        //   ③ 历史分叉：旧会话继续聊、新会话不知道 →
                        //      切换会话后最近一段对话消失
                        com.ccm.app.AppGraph.rebuild(appCtx, scope)?.let { activeSession = it }
                    }
                } catch (_: Throwable) {}
            }
        }
    }
}
