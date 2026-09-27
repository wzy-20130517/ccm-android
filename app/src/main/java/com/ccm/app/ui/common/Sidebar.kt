package com.ccm.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 抽屉宽度 —— 实测 **276dp**。
 *
 * Web 源码（`Sidebar.tsx:497`）：
 * ```js
 * const mobileSidebarWidth = `min(82vw, 300px)`;
 * ```
 * 本机 393 视口下 `82vw = 322.26px` → `min(322.26, 300) = 300px`
 * → × 0.92 zoom = **276dp**（实测确认 rectWidth=276）。
 *
 * > `App.tsx:622` 的 `sidebarWidth: 288` 是**桌面端**值，移动端不生效。
 */
val SidebarWidth = 276.dp

/**
 * 侧栏抽屉 —— 对齐 Web 移动端形态。
 *
 * ## 实测参数（MEASURED.md §9）
 * | 属性 | 值 |
 * |---|---|
 * | 宽度 | [SidebarWidth] = 276dp |
 * | 高度 | 满屏（Web 用 `calc(100vh / 0.92)` 补偿 zoom） |
 * | 背景 | [com.ccm.app.ui.theme.CCMColors.bgSidebar] |
 * | 右边框 | `1px` border |
 * | 阴影 | `shadow-2xl` |
 * | 折叠 | `translateX(-100%)`，**宽度保留**（为动画平滑） |
 * | 动画 | 200ms ease-in-out |
 * | 遮罩 | `bg-black/40`，在抽屉**下方**（z 55 < 60） |
 *
 * ## 文字色特例（index.css:274-291）
 * 暗色下侧栏文字是 `#BEBDB4`（暖灰），**不是** textMain（纯白）。
 * 亮色下等于 textMain。已封装在 [com.ccm.app.ui.theme.CCMColors.sidebarText]。
 *
 * @param open     是否展开
 * @param onClose  点击遮罩时回调
 * @param content  侧栏内容（B2 填充：新建对话、搜索、导航、用户区）
 */
@Composable
fun SidebarDrawer(
    open: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit = { SidebarPlaceholderContent() },
) {
    val colors = CCMTheme.colors

    // 展开进度：0 = 完全滑出，1 = 完全展开
    val progress = animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = tween(durationMillis = 200),   // Web: duration-200
        label = "sidebarProgress",
    ).value

    // 完全收起后不渲染（省性能，也避免挡住触摸）
    if (progress <= 0f) return

    // dp → px（graphicsLayer 用像素）
    val widthPx = with(LocalDensity.current) { SidebarWidth.toPx() }

    Box(modifier = modifier.fillMaxSize()) {
        // ── 遮罩（在抽屉下方）─────────────────────────────────────────
        // Web: fixed inset-0 z-[55] bg-black/40 md:hidden
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress }   // 随抽屉一起淡入
                .background(colors.scrim40)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        // ── 抽屉主体（在遮罩之上）─────────────────────────────────────
        Box(
            modifier = Modifier
                .width(SidebarWidth)
                .fillMaxHeight()
                .graphicsLayer {
                    // Web: translateX(-100%) 收起；宽度保留（为动画平滑）
                    translationX = -widthPx * (1f - progress)
                }
                .shadow(elevation = 16.dp)          // Web: shadow-2xl
                .background(colors.bgSidebar),
        ) {
            // 内容（占满，右边留 1dp 给边框，避免被内容盖住）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 1.dp),
            ) {
                content()
            }

            // 右边框 —— border-r border-claude-border
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(colors.border),
            )
        }
    }
}

/**
 * 侧栏占位内容 —— B2 会替换为真实内容。
 *
 * Web 侧栏结构（自上而下）：
 * 1. 顶部按钮组：新建对话 / 搜索 / Customize
 * 2. 导航：Chats / Projects / Artifacts
 * 3. 用户区（底部）：头像 + 昵称 + 菜单
 */
@Composable
private fun SidebarPlaceholderContent() {
    val colors = CCMTheme.colors

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "侧栏（B2 填充）",
            style = CCMText.body13,
            color = colors.sidebarText,
        )
        Spacer(Modifier.height(8.dp))
        // 占位项，用来验证文字色在明暗两套下都正确
        SidebarItem(label = "新建对话", onClick = {})
        SidebarItem(label = "搜索", onClick = {})
        SidebarItem(label = "Chats", onClick = {})
        SidebarItem(label = "Projects", onClick = {})
        SidebarItem(label = "Artifacts", onClick = {})
    }
}

/**
 * 侧栏列表项占位 —— B2 会按 Web 的真实样式重写。
 *
 * 这里先用最小实现验证 [com.ccm.app.ui.theme.CCMColors.sidebarText] 在明暗两套下都正确。
 */
@Composable
private fun SidebarItem(label: String, onClick: () -> Unit) {
    val colors = CCMTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = label, style = CCMText.body13, color = colors.sidebarText)
    }
}
