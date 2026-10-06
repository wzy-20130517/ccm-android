package com.ccm.app.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
 * @param open       是否展开
 * @param onClose    点击遮罩时回调
 * @param onNavigate 点导航项（key: chats / projects / artifacts）
 * @param onNewChat  点「新对话」
 * @param onSearch   点「搜索」
 * @param onCustomize 点「定制」
 * @param onOpenChat 点某个最近对话
 */
@Composable
fun SidebarDrawer(
    open: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigate: (String) -> Unit = {},
    onNewChat: () -> Unit = {},
    onSearch: () -> Unit = {},
    onCustomize: () -> Unit = {},
    /** 点「市场」。 */
    onMarket: () -> Unit = {},
    onOpenChat: (ChatSummary) -> Unit = {},
    /** 「…」菜单：重命名（透传给 SidebarContent）。 */
    onRenameChat: (id: String, title: String) -> Unit = { _, _ -> },
    /** 「…」菜单：删除。 */
    onDeleteChat: (id: String) -> Unit = {},
    /** 点底部用户区（Web: 打开用户菜单 → 可进设置） */
    onOpenProfile: () -> Unit = {},
    /** 点顶部胶囊导航（聊天 / 协作 / 代码）。空实现会让这三个胶囊点了没反应。 */
    onPillChange: (String) -> Unit = {},
    /** 当前激活的胶囊（由调用方根据路由决定） */
    activePill: String = "聊天",
    recentChats: List<ChatSummary> = emptyList(),
    userName: String = "",   // 空 = 未配置，调用方从 UserProfileStore 读
    userSubtitle: String = "自部署",
) {
    val colors = CCMTheme.colors

    // 展开进度：0 = 完全滑出，1 = 完全展开
    val progress = animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        // 【2026-10-06 问题9】用户反馈「滑入时动画过快」。
        // Web 是 duration-200（200ms），但 Web 上侧栏是**常驻可见**的
        // 折叠展开，而 Android 是**全屏抽屉**——同样的时长在手机上
        // 感觉「唰一下就没了」。放慢到 320ms 更从容。
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "sidebarProgress",
    ).value

    // 完全收起后不渲染（省性能，也避免挡住触摸）。
    //
    // 注：这里**不需要**额外的命中测试门控 —— `animateFloatAsState` 在
    // 收起动画结束后会收敛到精确的 0f，触发重组进入本分支。
    // 曾误判为「遮罩残留吞掉点击」，实测不成立（遮罩挂在最后的子元素位置，
    // 但 progress=0 时整个抽屉根本不挂载）。
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
                // 抽屉顶部让开状态栏：侧栏打开后顶部的
                // 「聊天/协作/代码」胶囊也在 y=0 起画，同样会被
                // StatusBar 窗口 (0,0,1280,152) 的触摸区吃掉（2026-09-27）。
                // 只给抽屉加，遮罩保持全屏 —— 点状态栏区域外要能关闭。
                .windowInsetsPadding(WindowInsets.statusBars)
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
                SidebarContent(
                    activePill = activePill,
                    recentChats = recentChats,
                    userName = userName,
                    userSubtitle = userSubtitle,
                    onNewChat = onNewChat,
                    onSearch = onSearch,
                    onCustomize = onCustomize,
                    onMarket = onMarket,
                    // ★ 2026-09-27 修：原来这里写死 `onPillChange = {}`，
                    //   侧栏顶部「聊天 / 协作 / 代码」三个胶囊点了**完全没反应**
                    //   —— 用户报的「侧边栏点不动」主要就是这三颗 + 搜索。
                    onPillChange = onPillChange,
                    onNavigate = onNavigate,
                    onOpenChat = onOpenChat,
                    onRenameChat = onRenameChat,
                    onDeleteChat = onDeleteChat,
                    onOpenProfile = onOpenProfile,
                )
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

