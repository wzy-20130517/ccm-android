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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)  // PullToRefreshBox（第42批）
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
    /** 重命名会话（CcmApp 负责 SessionStore 写入 + 刷新列表）。 */
    onRenameChat: (id: String, newTitle: String) -> Unit = { _, _ -> },
    /** 删除会话（SessionStore.delete 先备份到回收站）。 */
    onDeleteChat: (id: String) -> Unit = {},
    /** 下拉刷新（第42批；null = 不启用）。 */
    onRefresh: (() -> Unit)? = null,
) {
    val colors = CCMTheme.colors

    // ── 行菜单状态（2026-09-28：重命名/删除原来全缺）──────────────
    var menuFor by remember { mutableStateOf<ChatSummaryUi?>(null) }
    // ★ M4 多选（2026-09-29）：原来「选择」按钮 onToggleSelect 是外部死回调、
    //   页内没有任何多选 state —— Web 有完整 selectedChatIds 多选+批量删除。
    //   改为页内自治（同第8批行菜单的模式）。
    var selectMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var renaming by remember { mutableStateOf<ChatSummaryUi?>(null) }
    var deleting by remember { mutableStateOf<ChatSummaryUi?>(null) }
    var renameInput by remember { mutableStateOf("") }

    // ★ 第42批：下拉刷新（Web 列表有，APK 缺）。用 M3 PullToRefreshBox
    //   包裹 —— 里面仍是 verticalScroll 的 Column（非 Lazy，兼容现有结构）。
    var refreshing by remember { mutableStateOf(false) }
    val doRefresh: () -> Unit = {
        onRefresh?.invoke()
        refreshing = false   // 刷新是同步读盘，瞬时完成
    }
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { refreshing = true; doRefresh() },
        modifier = modifier.fillMaxSize().background(colors.bgMain),
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
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
                text = if (selectMode) "取消" else "选择",
                style = CCMText.body13,
                // 截图确认是蓝色（不是 textSecondary）
                color = colors.blueAccent,
                modifier = Modifier.clickable {
                    selectMode = !selectMode
                    selectedIds = emptySet()
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // ── 列表（按搜索词过滤，2026-09-27）─────────────────────────
        //   原来直接 chats.forEach —— searchQuery 传了也白传，
        //   搜什么都显示全量列表。
        val shown = if (searchQuery.isBlank()) chats
        else chats.filter { it.title.contains(searchQuery, ignoreCase = true) }
        shown.forEach { chat ->
            val isSel = selectedIds.contains(chat.id)
            ChatRow(
                chat = chat,
                onClick = {
                    if (selectMode) {
                        selectedIds = if (isSel) selectedIds - chat.id else selectedIds + chat.id
                    } else {
                        onOpenChat(chat)
                    }
                },
                onMore = { if (!selectMode) menuFor = chat },
                selectMode = selectMode,
                selected = isSel,
            )
        }

        // 多选操作栏（选中后可批量删）
        if (selectMode) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已选 ${selectedIds.size}",
                    style = CCMText.body13,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "删除",
                    style = CCMText.body13,
                    color = if (selectedIds.isEmpty()) colors.textSecondary else Color(0xFFDC2626),
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = selectedIds.isNotEmpty()) {
                            selectedIds.forEach { id -> onDeleteChat(id) }
                            selectMode = false
                            selectedIds = emptySet()
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
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

    // ── 行菜单：重命名 / 删除（2026-09-28）──────────────────────────
    menuFor?.let { target ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(target.title.ifBlank { "未命名" }, style = CCMText.body14) },
            text = {
                Column {
                    Text(
                        text = "重命名",
                        style = CCMText.body13,
                        color = colors.textMain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                renameInput = target.title
                                renaming = target
                                menuFor = null
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "删除（进回收站）",
                        style = CCMText.body13,
                        color = Color(0xFFDC2626),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                deleting = target
                                menuFor = null
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { menuFor = null }) { Text("取消", style = CCMText.body13) }
            },
        )
    }

    // 重命名输入
    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名对话", style = CCMText.body14) },
            text = {
                BasicTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = CCMText.body13.copy(color = colors.textMain),
                    cursorBrush = SolidColor(colors.claudeOrange),
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val nt = renameInput.trim()
                    if (nt.isNotEmpty()) onRenameChat(target.id, nt)
                    renaming = null
                }) { Text("确定", style = CCMText.body13) }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("取消", style = CCMText.body13) }
            },
        )
    }

    // 删除确认
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除对话", style = CCMText.body14) },
            text = {
                Text(
                    text = "「${target.title.ifBlank { "未命名" }}」将被删除（先备份到回收站，可恢复）。",
                    style = CCMText.body13,
                    color = colors.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteChat(target.id)
                    deleting = null
                }) { Text("删除", style = CCMText.body13, color = Color(0xFFDC2626)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("取消", style = CCMText.body13) }
            },
        )
    }
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

        // ★ 2026-09-28 第22批：死 Text → BasicTextField。
        //   第3批做的过滤逻辑（searchQuery 受控 + contains 匹配）一直
        //   没有输入入口 —— 搜索框本身打不了字，过滤等于白做。
        // placeholder 与原死 Text 同款叠加（value 空时画灰字）。
        if (query.isEmpty()) {
            Text(
                text = "搜索对话…",
                style = CCMText.body16.copy(fontSize = 16.sp, lineHeight = 24.sp),
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 36.8.dp, end = 14.72.dp),
            )
        }
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            textStyle = CCMText.body16.copy(
                fontSize = 16.sp,
                lineHeight = 24.sp,
                color = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.claudeOrange),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 36.8.dp, end = 14.72.dp),
        )
    }
}

/**
 * 对话列表项 —— 实测 w=343.16 / h=69.34 / pad 16 / 底部分隔线 1.08696px #DAD9D4。
 *
 * 结构：标题（13.362 / fw500）+ 时间（12.183 / textSecondary）。
 * 右侧「…」菜单在 Web 里是 `opacity-0 group-hover:opacity-100`，手机侧常显。
 */
@Composable
private fun ChatRow(
    chat: ChatSummaryUi,
    onClick: () -> Unit,
    onMore: () -> Unit = {},
    selectMode: Boolean = false,
    selected: Boolean = false,
) {
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
            // 多选模式：头部勾选圈（Web 是行前 checkbox，移动端放行首最直观）
            if (selectMode) {
                Box(
                    modifier = Modifier
                        .size(18.4.dp)
                        .padding(end = 10.dp)
                        .clip(RoundedCornerShape(9.2.dp))
                        .border(
                            1.5.dp,
                            if (selected) colors.blueAccent else colors.border,
                            RoundedCornerShape(9.2.dp),
                        )
                        .background(if (selected) colors.blueAccent else Color.Transparent),
                )
                Spacer(Modifier.width(8.dp))
            }
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
            // ★ 2026-09-28：原来没接点击 —— 图标是个死装饰。
            // 多选模式隐藏（那时行点击=勾选，行内菜单没意义）
            if (!selectMode) Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onMore)
                    .padding(4.dp),
            ) {
                PainterIcon(
                    R.drawable.ic_more_horizontal,
                    size = 18.4.dp,                    // size={20} × 0.92
                    tint = colors.textSecondary,
                )
            }
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
