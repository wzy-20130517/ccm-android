package com.ccm.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.TitleBarBorderWidth
import com.ccm.app.ui.theme.TitleBarHeight

/**
 * 顶栏 —— 对齐 Web 的 `[data-chrome="titlebar"]`。
 *
 * ## ★ 关键：顶栏内部的值**不乘 0.92**
 * Web 给顶栏加了反向 zoom（`zoom: 1 / var(--mobile-zoom)` = 1.08696），
 * 恰好抵消全局的 0.92。所以本文件所有尺寸都是**屏幕实测值，直接当 dp 用**。
 *
 * 别把 [CCMRadius] 那些（已经乘过 0.92 的）拿进来用 —— 会小 8.7%。
 *
 * ## 实测数据（Playwright，393×852 视口）
 * | 项 | 实测值 |
 * |---|---|
 * | 顶栏高度 | **44** |
 * | 左内边距 | **4**（非 Mac；Mac 是 78 给红绿灯让位） |
 * | 右内边距 | **8**（`pr-2`） |
 * | 按钮间距 | **2**（`gap-0.5`） |
 * | 按钮尺寸 | **40 × 40** |
 * | 按钮内边距 | **8**（`p-2`） |
 * | 按钮圆角 | **6**（`rounded-md`） |
 * | 按钮文字色 | `#666666` = [CCMColors.textSecondary] |
 * | 图标尺寸 | **20 × 20**，`stroke-width: 2` |
 * | 底边 | `1px solid` [CCMColors.border] |
 *
 * ## ⚠️ 移动端与桌面端不同（App.tsx:685-720）
 * 1. **Menu 装饰按钮隐藏** —— Web 是 `hidden md:block`，`<768px` 不渲染
 *    （实测确认：`w=0, h=0`，且它外层 Tooltip 仍占一个 2px gap → 首按钮 x=6 而非 4）
 * 2. **侧栏按钮换图标** —— 移动端 `Menu size={20}`（汉堡），桌面端 `IconSidebarToggle size={24}`
 * 3. 前进/后退按钮**移动端保留**
 *
 * 本 App 锁定移动端形态，所以按 1、2 的移动端分支实现。
 *
 * @param onToggleSidebar 侧栏开关（移动端是抽屉）
 * @param onNavBack    后退；传 `null` = 禁用态（图标变灰不可点）
 * @param onNavForward 前进；传 `null` = 禁用态
 */
@Composable
fun TitleBar(
    onToggleSidebar: () -> Unit,
    onNavBack: (() -> Unit)? = null,
    onNavForward: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = CCMTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(colors.bgMain),
    ) {
        // 底部分割线
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(TitleBarBorderWidth)
                .background(colors.border),
        )

        Row(
            modifier = Modifier
                .height(TitleBarHeight)
                // 实测：padLeft 4 + 隐藏按钮遗留的 2px gap = 首个可见按钮 x=6
                .padding(start = 6.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // ── 侧栏切换（移动端：汉堡图标 20dp）────────────────────────
            TitleBarButton(onClick = onToggleSidebar) {
                Icon(
                    imageVector = SvgIcons.Menu,
                    contentDescription = "打开菜单",
                    modifier = Modifier.size(20.dp),
                    tint = colors.textSecondary,
                )
            }

            // ── 后退 ──────────────────────────────────────────────────
            TitleBarButton(
                onClick = onNavBack ?: {},
                enabled = onNavBack != null,
            ) {
                Icon(
                    imageVector = SvgIcons.ArrowLeft,
                    contentDescription = "后退",
                    modifier = Modifier.size(18.dp),
                    // App.tsx:714 可点 #73726C / :727 禁用 #B7B5B0
                    tint = if (onNavBack != null) NavIconActive else NavIconDisabled,
                )
            }

            // ── 前进 ──────────────────────────────────────────────────
            TitleBarButton(
                onClick = onNavForward ?: {},
                enabled = onNavForward != null,
            ) {
                Icon(
                    imageVector = SvgIcons.ArrowRight,
                    contentDescription = "前进",
                    modifier = Modifier.size(18.dp),
                    tint = if (onNavForward != null) NavIconActive else NavIconDisabled,
                )
            }
        }
    }
}

/** 前进/后退可点色 —— App.tsx:714 硬编码 */
private val NavIconActive = Color(0xFF73726C)

/** 前进/后退禁用色 —— App.tsx:727 硬编码 */
private val NavIconDisabled = Color(0xFFB7B5B0)

/**
 * 顶栏按钮 —— 对齐 Web 的
 * `p-2 hover:bg-black/5 dark:hover:bg-white/5 rounded-md transition-colors`。
 *
 * 实测 **40×40**，圆角 6，内边距 8。
 * （注意：40 = 8×2 + 20 + 4，多出的 4px 来自 inline `<svg>` 的基线间隙，
 * 实测值已包含，直接写 40 而不是"算"出来。）
 *
 * > 手机没有 hover。**故意不加按下背景色** —— Web 的 hover 色只在鼠标悬停时出现，
 * > 常显会改变静止态的像素（顶栏是截图 diff 会覆盖的区域）。
 * > 将来若要按下反馈，应放在 [androidx.compose.foundation.clickable] 的
 * > `indication` 里用 ripple，而不是画常驻背景。
 */
@Composable
private fun TitleBarButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
