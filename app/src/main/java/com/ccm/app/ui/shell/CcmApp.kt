package com.ccm.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ccm.app.ui.chat.ChatScreen
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
 * setContent { CcmApp() }
 * ```
 * 这是 dev-ui（阶段 4）与阶段 5 之间**唯一**的接口。阶段 5 不需要知道
 * 内部有哪些页面、怎么导航 —— 全在 ui/ 包内封装。
 *
 * ## 当前状态：B4
 * - ✅ 主题层（ui/theme/）
 * - ✅ 顶栏 + 侧栏抽屉（ui/common/）
 * - ✅ 首页 / 对话列表 / 项目页（ui/pages/）
 * - ⏳ 聊天主界面（B5，最难）
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
 */
@Composable
fun CcmApp() {
    // 对齐 Web：未显式设置过主题时跟随系统（localStorage.theme === "system"）
    // TODO(阶段4·B5): 接 SettingsRepository —— 用户显式选择优先于系统
    val darkTheme = isSystemInDarkTheme()

    CCMTheme(darkTheme = darkTheme) {
        AppScaffold()
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
 */
@Composable
private fun AppScaffold() {
    val colors = CCMTheme.colors

    var sidebarOpen by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf(CcmRoute.HOME) }
    // 设置是**覆盖层不是路由**（对齐 Web：showSettings 状态，location 不变）
    var showSettings by remember { mutableStateOf(false) }

    /** 切页 —— 对齐 Web：路由变化时自动收起抽屉（`App.tsx:378`） */
    fun navigate(to: CcmRoute) {
        route = to
        sidebarOpen = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgMain),
        ) {
            TitleBar(
                onToggleSidebar = { sidebarOpen = !sidebarOpen },
                // 非首页时启用「后退」（回首页）；首页时禁用（灰色 #B7B5B0）
                onNavBack = if (route != CcmRoute.HOME) ({ navigate(CcmRoute.HOME) }) else null,
                onNavForward = null,
            )

            // ── 页面内容 ──────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize()) {
                when (route) {
                    CcmRoute.HOME -> LandingScreen(
                        greeting = greetingFor("Jay"),
                        onSend = { },
                        onPickPrompt = { },
                    )

                    // 未接 session 时渲染空态。
                    // 接了 session 请用 ChatScreenConnected(session) —— 见 ui/chat/ChatScreenConnected.kt
                    // 阶段 5 装配 session 后，把这里换成：
                    //   ChatScreenConnected(session = session)
                    CcmRoute.CHAT -> ChatScreen(
                        bubbles = emptyList(),
                        onSend = { },
                    )

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

                    // TODO(阶段4·B4-c): Cowork / Scheduled 的独立页面（已有 CoworkScreen，待接）
                    // TODO(阶段4·B5-c): 聊天主界面剩余区块
                    else -> LandingScreen(
                        greeting = greetingFor("Jay"),
                        onSend = { },
                        onPickPrompt = { },
                    )
                }
            }
        }

        // ── 设置覆盖层（全屏，在抽屉之下）─────────────────────────────
        if (showSettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bgMain),
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
                    else -> CcmRoute.HOME
                }
                navigate(target)
            },
            onNewChat = { navigate(CcmRoute.HOME) },
            onCustomize = { navigate(CcmRoute.CUSTOMIZE) },
            onOpenProfile = { navigate(CcmRoute.SETTINGS) },
        )
    }
}
