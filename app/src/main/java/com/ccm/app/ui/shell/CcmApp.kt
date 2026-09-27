package com.ccm.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.ccm.app.ui.common.SidebarDrawer
import com.ccm.app.ui.common.TitleBar
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.CCMText

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
 * ## 当前状态：B1 骨架
 * - ✅ 主题层（ui/theme/）
 * - ✅ 顶栏（ui/common/TitleBar.kt）—— 44dp
 * - ✅ 侧栏抽屉（ui/common/Sidebar.kt）—— 276dp + 遮罩
 * - ⏳ 页面与聊天界面
 *
 * ## 布局结构（对齐 Web 移动端）
 * ```
 * Box（根，承载抽屉浮层）
 * ├── Column
 * │   ├── TitleBar          44dp（不乘 0.92）
 * │   └── 内容区             weight=1
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
 * 应用骨架：顶栏 + 内容区 + 侧栏抽屉。
 *
 * 移动端形态（MEASURED.md §0/§5/§9）：
 * - 视口 393×852，全局 zoom 0.92（已固化进各尺寸常量）
 * - 顶栏 44dp，**不乘 0.92**
 * - **没有固定侧栏** —— 是 276dp 的抽屉，默认收起
 * - Web 在每次路由变化时自动收起抽屉（`App.tsx:378`），B4 接路由时要保留这个行为
 */
@Composable
private fun AppScaffold() {
    val colors = CCMTheme.colors

    var sidebarOpen by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bgMain),
        ) {
            TitleBar(
                onToggleSidebar = { sidebarOpen = !sidebarOpen },
                onNavBack = null,      // 暂无导航历史 → 禁用态（灰 #B7B5B0）
                onNavForward = null,
            )

            // ── 内容区 ────────────────────────────────────────────────
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                // B1 占位：验证主题、顶栏、抽屉渲染正确
                // TODO(阶段4·B4): 替换为真实页面（首页 / 聊天 / 设置 …）
                Text(
                    text = "CCM",
                    style = CCMText.titleSerif,
                    color = colors.textSecondary,
                )
            }
        }

        // ── 侧栏抽屉（浮层，含遮罩）───────────────────────────────────
        SidebarDrawer(
            open = sidebarOpen,
            onClose = { sidebarOpen = false },
        )
    }
}
