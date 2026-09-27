package com.ccm.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 对话列表页 —— 对齐 Web 的 `/chats` 路由（`ChatsPage.tsx`，495 行）。
 *
 * ## 实测数据（Playwright，393×852）
 * | 元素 | 实测值 | 说明 |
 * |---|---|---|
 * | 页面左内边距 | **34.64** | 内容 x=34.64 |
 * | `h1`「对话」 | 21.222 / 27.5886 / **fw 500** / **Spectral** 衬线 | y=57.39 |
 * | 搜索框 | w=313.72 h=46.14 / 圆角 **12** / 边框 1.08696 | y=99.72 |
 * | 搜索框内边距 | `12px 16px 12px 40px` | 左 40 给图标留位 |
 * | 搜索框字号 | **16px**（浏览器默认，防移动端缩放） | |
 * | 计数行 | `共 19 个对话` + 「选择」链接 | 蓝色 #387EE0 |
 * | 列表项 | w=343.16 / **h=69.34** / pad **16** | |
 * | 列表项分隔线 | **1.08696px rgb(218,217,212)** | 注意不是 `--border-claude` |
 * | 标题 | 13.362 / **fw 500** | |
 * | 时间 | 12.183 / textSecondary | 「最近消息：1 天前」 |
 *
 * ## ★ 三个与源码不同的实测修正
 * 1. **`h1` 字号 32px 被移动端 clamp 覆盖** → 实测 21.222（= clamp(19, 5.4vw, 28)）
 * 2. **列表项分隔线色是 `#DAD9D4`**，不是 token 里的 `--border-claude`(#E8E7E3)
 * 3. **列表项无圆角**（截图确认是通栏分隔线，不是卡片）
 *
 * ## 移动端布局特点（截图确认）
 * 页面**没有** `max-w-[800px] mx-auto px-8` 的内边距 —— 移动端 `px-4` = 16px，
 * 但实测内容 x=34.64 说明还有额外偏移。列表项宽度 343.16 与卡片同宽。
 *
 * @param chats       对话列表
 * @param totalCount  总数（显示「共 N 个对话」）
 * @param onOpenChat  点击对话
 * @param onNewChat   点「新对话」
 * @param onToggleSelect 点「选择」进入多选模式
 */
@Composable
fun ChatsScreen(
    chats: List<ChatSummaryUi>,
    modifier: Modifier = Modifier,
    totalCount: Int = chats.size,
    searchQuery: String = "",
    onSearchChange: (String) -> Unit = {},
    onOpenChat: (ChatSummaryUi) -> Unit = {},
    onNewChat: () -> Unit = {},
    onToggleSelect: () -> Unit = {},
) {
    val colors = CCMTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),      // 移动端 px-4
    ) {
        Spacer(Modifier.height(13.39.dp))       // h1 y=57.39 − 顶栏 44

        // ── 标题行 ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "对话",
                // 实测 21.222 / 27.5886 / fw500 / Spectral
                // Web 用 WebkitTextStroke: 0.5px 加粗描边 —— Compose 无等价物，忽略
                style = CCMText.body20.copy(
                    fontSize = 21.222.sp,
                    lineHeight = 27.5886.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.textMain,
            )

            // 「新对话」按钮 —— bg-claude-text text-claude-bg px-3.5 py-1.5 rounded-lg
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(7.36.dp))       // rounded-lg
                    .background(colors.textMain)
                    .clickable(onClick = onNewChat)
                    .padding(horizontal = 12.88.dp, vertical = 5.52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.36.dp),
            ) {
                // Plus 图标（lucide，strokeWidth 2.5）
                PlusIcon(tint = colors.bgMain, size = 14.72.dp, strokeWidth = 2.5f)
                Text(
                    text = "新对话",
                    style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
                    color = colors.bgMain,
                )
            }
        }

        Spacer(Modifier.height(17.33.dp))       // h1 底 82.78 → 搜索框顶 99.72

        // ── 搜索框 ────────────────────────────────────────────────────
        SearchField(
            query = searchQuery,
            onQueryChange = onSearchChange,
        )

        Spacer(Modifier.height(11.04.dp))       // mb-6

        // ── 计数行 ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 搜索时显示匹配数（totalCount 是全量，搜索中直接用会误导）
            val shownCount = if (searchQuery.isBlank()) totalCount
            else chats.count { it.title.contains(searchQuery, ignoreCase = true) }
            Text(
                text = "共 $shownCount 个对话",
                style = CCMText.body13,
                color = colors.textSecondary,
            )
            Spacer(Modifier.width(7.36.dp))
            Text(
                text = "选择",
                style = CCMText.body13,
                // 截图确认是蓝色（不是 textSecondary）
                color = colors.blueAccent,
                modifier = Modifier.clickable(onClick = onToggleSelect),
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── 列表（按搜索词过滤，2026-09-27）─────────────────────────
        //   原来直接 chats.forEach —— searchQuery 传了也白传，
        //   搜什么都显示全量列表。
        val shown = if (searchQuery.isBlank()) chats
        else chats.filter { it.title.contains(searchQuery, ignoreCase = true) }
        shown.forEach { chat ->
            ChatRow(chat = chat, onClick = { onOpenChat(chat) })
        }
        if (shown.isEmpty() && chats.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = "没有匹配「$searchQuery」的对话",
                style = CCMText.body13,
                color = colors.textSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 搜索框 —— 实测 w=313.72 / h=46.14 / 圆角 12 / pad `12px 16px 12px 40px`。
 *
 * 左侧放大镜图标（16.55），placeholder「搜索对话…」。
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = CCMTheme.colors

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.14.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        // 左侧图标（left-3 = 11.04dp）
        PainterIcon(
            R.drawable.ic_search_small,
            size = 16.55.dp,
            tint = colors.textSecondary,
            modifier = Modifier.padding(start = 11.04.dp),
        )

        Text(
            text = query.ifEmpty { "搜索对话…" },
            style = CCMText.body16.copy(fontSize = 16.sp, lineHeight = 24.sp),
            color = if (query.isEmpty()) colors.textSecondary else colors.textMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 36.8.dp, end = 14.72.dp),
        )
        // TODO(阶段4·B5): 换成真实 TextField
    }
}

/**
 * 对话列表项 —— 实测 w=343.16 / h=69.34 / pad 16 / 底部分隔线 1.08696px #DAD9D4。
 *
 * 结构：标题（13.362 / fw500）+ 时间（12.183 / textSecondary）。
 * 右侧「…」菜单在 Web 里是 `opacity-0 group-hover:opacity-100`，手机侧常显。
 */
@Composable
private fun ChatRow(chat: ChatSummaryUi, onClick: () -> Unit) {
    val colors = CCMTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = chat.title.ifBlank { "未命名" },
                // 实测 13.362 / fw500
                style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            // 「…」更多（Web 是 hover 显示，手机常显）
            PainterIcon(
                R.drawable.ic_more_horizontal,
                size = 18.4.dp,                        // size={20} × 0.92
                tint = colors.textSecondary,
            )
        }

        Spacer(Modifier.height(3.68.dp))

        Text(
            text = "最近消息：${formatTimeAgo(chat.updatedAt)}",
            // 实测 12.183
            style = CCMText.body12,
            color = colors.textSecondary,
            maxLines = 1,
        )
    }

    // 底部分隔线 —— 实测 rgb(218,217,212) = #DAD9D4（不是 --border-claude）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0xFFDAD9D4)),
    )
}

/**
 * 画 Plus 图标（lucide 的 Plus，可指定 strokeWidth）。
 *
 * Web 在「新对话」按钮里用 `strokeWidth={2.5}`（比其他处粗）。
 */
@Composable
private fun PlusIcon(tint: Color, size: androidx.compose.ui.unit.Dp, strokeWidth: Float) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = strokeWidth / 24f * w        // 24 是 lucide 的 viewBox
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w / 2, h * 0.2f),
            end = androidx.compose.ui.geometry.Offset(w / 2, h * 0.8f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.2f, h / 2),
            end = androidx.compose.ui.geometry.Offset(w * 0.8f, h / 2),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/**
 * 相对时间格式化 —— 对齐 Web 的 `formatTimeAgo`。
 *
 * Web 输出：「1 天前」「2 小时前」「刚刚」等。
 */
fun formatTimeAgo(timestampMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (timestampMillis <= 0) return "刚刚"
    val diff = now - timestampMillis
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        hours < 24 -> "$hours 小时前"
        days < 30 -> "$days 天前"
        days < 365 -> "${days / 30} 个月前"
        else -> "${days / 365} 年前"
    }
}

/**
 * 对话摘要（UI 层）—— 与 `SidebarContent.ChatSummary` 分开，
 * 因为列表页还需要 `updatedAt` 和项目归属等字段。
 */
data class ChatSummaryUi(
    val id: String,
    val title: String,
    val updatedAt: Long = 0,
    val createdAt: Long = 0,
    val projectId: String? = null,
)
