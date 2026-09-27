package com.ccm.app.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * ## 当前状态：骨架（B0）
 * 主题层已完成（ui/theme/），页面层待建。这里渲染一个可验证的最小骨架：
 * - 主题生效（背景 = `--bg-claude-main`）
 * - 居中占位文字（用实测的衬线标题样式）
 *
 * ## 后续填充顺序（B1 → B9，见 recon-b §5）
 * 1. `common/` 静态原子 → 用来校准 diff 工具链
 * 2. `common/` 基础组件
 * 3. `common/` 表单与弹窗
 * 4. `chat/` 聊天界面（MainContent 5537 行拆 8~12 块）
 * 5. `settings/` `pages/`
 *
 * ## 主题来源（对齐 Web）
 * Web 的判定顺序（`Onboarding.tsx:37-40`、`SettingsPage.tsx:135-139`）：
 * 1. 用户显式选过 dark → 用用户选择
 * 2. 否则跟随系统 `prefers-color-scheme: dark`
 *
 * 所以初始值用 [isSystemInDarkTheme]（对应第 2 条），等设置页做完后
 * 接上持久化偏好（DataStore）实现第 1 条。
 */
@Composable
fun CcmApp() {
    // 对齐 Web：未显式设置过主题时跟随系统
    // TODO(阶段4·B5): 接 SettingsRepository —— 用户显式选择优先于系统
    val darkTheme = isSystemInDarkTheme()

    CCMTheme(darkTheme = darkTheme) {
        AppScaffold()
    }
}

/**
 * 应用骨架：内容区。
 *
 * 移动端形态（MEASURED.md §0/§5）：
 * - 视口 393×852，全局 zoom 0.92（已固化进各尺寸常量）
 * - 顶栏 44dp，**不乘 0.92**（Web 有反向 zoom 抵消）
 * - **没有固定侧栏** —— `<768px` 时 `useIsMobile()` 为 true，
 *   `App.tsx:378` 会在每次路由变化时自动收起抽屉
 */
@Composable
private fun AppScaffold() {
    val colors = CCMTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.bgMain),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "CCM",
            style = CCMText.titleSerif,
            color = colors.textSecondary,
        )

        // TODO(阶段4·B4): TitleBar() —— 44dp，bg=bgMain，底边 1dp border
        // TODO(阶段4·B4): Sidebar 抽屉 —— ModalNavigationDrawer
        //                 宽度 = 288 × 0.92 = 264.96dp（Web App.tsx:622 的 tuned value）
        // TODO(阶段4·B4): ChatScreen / SettingsScreen / 各页面路由
    }
}
