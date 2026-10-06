package com.ccm.app.ui.common

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ccm.app.R
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 侧栏内容 —— 对齐 Web `Sidebar.tsx` 的移动端形态。
 *
 * ## 实测结构（Playwright，侧栏展开态）
 * ```
 * ├── 胶囊导航（pill-nav）     y=51.5,  h=35.88
 * │     聊天(激活) / 协作 / 代码
 * ├── 功能项
 * │     新对话   y=98.42,  h=22.05
 * │     搜索     y=124.13, h=28.48
 * │     定制     y=156.27, h=28.48
 * ├── 导航项（h-8）
 * │     对话  y=199.47
 * │     项目  y=229.81
 * │     产物  y=260.16
 * ├── 分组标题「最近对话」+ 列表（可滚动）
 * │     每项：左侧空心圆 + 标题 + 右侧「…」更多
 * └── 底部用户区（头像 + 名字 + 副标题 + 折叠箭头）
 *       头像 y=801.44, 33.11×33.11
 * ```
 *
 * ## 实测关键值（屏幕值）
 * | 元素 | 值 |
 * |---|---|
 * | 左右内边距 | **8.27**（`px-2` = 9 × 0.92，实测 8.27） |
 * | 胶囊高 | 35.88 / 圆角 10 / pad 0×8 / gap 8 / 图标 18.4 |
 * | 胶囊激活色 | `#373734`（textTitle） |
 * | 胶囊未激活色 | `#5F5B56`（暖灰，非 textSecondary） |
 * | 功能项高 | 22.05（新对话）/ 28.48（搜索、定制） |
 * | 功能项圆角 | 8 / pad 2×0 / gap 8 |
 * | 导航项高 | **29.44**（h-8 × 0.92）/ 圆角 6 / pad 0×8 / gap 12 |
 * | 导航项色 | `#373734` |
 * | 列表项图标 | 13.7×13.7（空心圆，未选中） |
 * | 更多按钮 | 18.38×18.38 / pad 2 / 圆角 4 / 图标 14.7 |
 * | 侧栏右边框 | 1.08696px（反向补偿）→ Compose 用 1dp |
 *
 * @param onNewChat   点「新对话」
 * @param onSearch    点「搜索」
 * @param onCustomize 点「定制」
 * @param onNavigate  点导航项（参数为导航 key）
 * @param onOpenChat  点某个最近对话
 * @param onOpenProfile 点底部用户区
 */
@Composable
fun SidebarContent(
    modifier: Modifier = Modifier,
    activePill: String = "聊天",
    recentChats: List<ChatSummary> = emptyList(),
    userName: String = "",   // 空 = 未配置，调用方从 UserProfileStore 读
    userSubtitle: String = "自部署",
    onNewChat: () -> Unit = {},
    onSearch: () -> Unit = {},
    onCustomize: () -> Unit = {},
    /** 点「市场」（问题40：skill/MCP/插件 的下载入口）。 */
    onMarket: () -> Unit = {},
    onPillChange: (String) -> Unit = {},
    onNavigate: (String) -> Unit = {},
    onOpenChat: (ChatSummary) -> Unit = {},
    /** 「…」菜单：重命名（2026-09-28，对齐列表页）。 */
    onRenameChat: (id: String, title: String) -> Unit = { _, _ -> },
    /** 「…」菜单：删除（SessionStore 先备份）。 */
    onDeleteChat: (id: String) -> Unit = {},
    onOpenProfile: () -> Unit = {},
) {
    val colors = CCMTheme.colors

    // 「…」菜单状态（2026-09-28）
    var menuFor by remember { mutableStateOf<ChatSummary?>(null) }
    var renaming by remember { mutableStateOf<ChatSummary?>(null) }
    var deleting by remember { mutableStateOf<ChatSummary?>(null) }
    var renameInput by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        // ── 顶部胶囊导航（pt: 51.5 − 顶栏44 ≈ 7.5）────────────────────
        Spacer(Modifier.height(7.5.dp))
        PillNavRow(
            active = activePill,
            onSelect = onPillChange,
        )

        Spacer(Modifier.height(11.04.dp))

        // ── 功能项 ────────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 8.27.dp)) {
            SidebarActionRow(
                iconRes = R.drawable.ic_new_chat,
                label = "新对话",
                iconSize = 18.4.dp,
                rowHeight = 22.05.dp,
                onClick = onNewChat,
            )
            SidebarActionRow(
                iconRes = R.drawable.ic_search,
                label = "搜索",
                iconSize = 14.7.dp,
                rowHeight = 28.48.dp,
                onClick = onSearch,
            )
            SidebarActionRow(
                iconRes = R.drawable.ic_customize,
                label = "定制",
                iconSize = 22.1.dp,
                rowHeight = 28.48.dp,
                onClick = onCustomize,
            )
            // 【2026-10-06 问题40】市场 —— skill / MCP / 插件的下载入口。
            // 独立成项而不是塞进定制页的 tab 条（那里已有 3 个 tab，太挤）。
            SidebarActionRow(
                iconRes = R.drawable.ic_market,
                label = "市场",
                iconSize = 22.1.dp,
                rowHeight = 28.48.dp,
                onClick = onMarket,
            )
        }

        Spacer(Modifier.height(11.04.dp))

        // ── 导航项（对话 / 项目 / 产物）───────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 8.27.dp)) {
            SidebarNavRow(R.drawable.ic_chats, "对话", onClick = { onNavigate("chats") })
            SidebarNavRow(R.drawable.ic_projects, "项目", onClick = { onNavigate("projects") })
            SidebarNavRow(R.drawable.ic_artifacts, "产物", onClick = { onNavigate("artifacts") })
            // 计划任务 —— Web Sidebar.tsx:755 有这个入口，CCM 之前漏了，
            // CcmRoute.SCHEDULED 页面存在却没有任何入口能到。
            SidebarNavRow(R.drawable.ic_list_checks, "计划任务", onClick = { onNavigate("scheduled") })
        }

        Spacer(Modifier.height(11.04.dp))

        // ── 最近对话（分组标题 + 可滚动列表）──────────────────────────
        Text(
            text = "最近对话",
            style = CCMText.body11,
            color = colors.textSecondary,
            modifier = Modifier.padding(start = 16.55.dp, bottom = 4.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 8.27.dp),
        ) {
            recentChats.forEach { chat ->
                RecentChatRow(
                    chat = chat,
                    onClick = { onOpenChat(chat) },
                    onMore = { menuFor = chat },
                )
            }
        }

        // ── 底部用户区 ────────────────────────────────────────────────
        UserRow(
            userName = userName,
            subtitle = userSubtitle,
            onClick = onOpenProfile,
        )
    }

    // ── 行菜单：重命名 / 删除（与列表页同款交互）─────────────────────
    menuFor?.let { target ->
        AlertDialog(
            onDismissRequest = { menuFor = null },
            title = { Text(target.title, style = CCMText.body14) },
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

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除对话", style = CCMText.body14) },
            text = {
                Text(
                    text = "「${target.title}」将被删除（先备份到回收站，可恢复）。",
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

/**
 * 胶囊导航行 —— 实测 h=35.88 / 圆角 10 / 间距 8。
 *
 * 激活态有背景色（截图里「聊天」是浅灰底），未激活透明。
 * 图标用 lucide 的 ChatBubble / Users / Code。
 */
@Composable
private fun PillNavRow(active: String, onSelect: (String) -> Unit) {
    val colors = CCMTheme.colors
    // 图标来自 Sidebar.tsx:210-241 的 sideMode*Icon
    // 聊天：assets/sidebar-exact/chats.svg（20×20）
    // 协作：figma-exports/sidebar-icons/cowork-icon.svg（19×18，opacity 0.58）
    // 代码：figma-exports/sidebar-icons/code-icon.svg（18×18，disabled）
    val items = listOf(
        Triple("聊天", R.drawable.ic_chat_bubble, 20.dp),
        Triple("协作", R.drawable.ic_users, 19.dp),
        Triple("代码", R.drawable.ic_code_small, 18.dp),
    )

    Row(
        modifier = Modifier.padding(horizontal = 8.27.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (label, icon, iconSize) ->
            val isActive = label == active
            // 协作/代码的图标是 Figma 导出的多色轮廓，不能 tint（会丢失色差）
            val tintable = label == "聊天"
            Row(
                modifier = Modifier
                    .height(35.88.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isActive) colors.hover else Color.Transparent)
                    .clickable { onSelect(label) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PainterIcon(
                    icon,
                    size = iconSize,
                    tint = when {
                        !tintable -> Color.Unspecified
                        isActive -> Color(0xFF373734)
                        else -> Color(0xFF5F5B56)
                    },
                )
                // 激活时显示文字（实测：激活胶囊 w=65.95 含文字，未激活 w=33.11 仅图标）
                if (isActive) {
                    Text(
                        text = label,
                        style = CCMText.body13,
                        color = Color(0xFF373734),
                    )
                }
            }
        }
    }
}

/**
 * 功能项行 —— 「新对话 / 搜索 / 定制」。
 *
 * 实测：w=258.47（= 276 − 8.27×2 − 边框）/ 圆角 8 / pad 2×0 / gap 8。
 */
@Composable
private fun SidebarActionRow(
    iconRes: Int,
    label: String,
    iconSize: Dp,
    rowHeight: Dp,
    onClick: () -> Unit,
) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(iconSize), contentAlignment = Alignment.Center) {
            PainterIcon(iconRes, size = iconSize, tint = colors.textMain)
        }
        Text(
            text = label,
            style = CCMText.body13,
            color = colors.textMain,
            maxLines = 1,
        )
    }
}

/**
 * 导航项行 —— 「对话 / 项目 / 产物」。
 *
 * 实测：h=29.44 / 圆角 6 / pad 0×8 / gap 12 / 图标 13.7 / 色 #373734。
 */
@Composable
private fun SidebarNavRow(iconRes: Int, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(29.44.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PainterIcon(iconRes, size = 13.7.dp, tint = Color(0xFF373734))
        Text(
            text = label,
            style = CCMText.body13,
            color = Color(0xFF373734),
            maxLines = 1,
        )
    }
}

/**
 * 最近对话项 —— 左侧空心圆 + 标题 + 右侧「…」。
 *
 * 截图显示：未选中的对话左侧是一个**空心小圆**（13.7×13.7），
 * 右侧 hover 时才显示「…」按钮（实测 opacity-0，手机侧改为常显）。
 */
@Composable
private fun RecentChatRow(chat: ChatSummary, onClick: () -> Unit, onMore: () -> Unit = {}) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(33.09.dp)                      // 实测行高（列表项间距 33.09）
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 空心圆（对话未激活标记）
        Box(
            modifier = Modifier
                .size(13.7.dp)
                .clip(RoundedCornerShape(6.85.dp))
                .background(Color.Transparent)
                .then(
                    Modifier.padding(0.dp),
                ),
        ) {
            // 画一个空心圆：外圈描边
            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color(0xFFB7B5B0),
                    radius = size.minDimension / 2f - 1f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Text(
            text = chat.title,
            style = CCMText.body13,
            color = Color(0xFF373734),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        // 「…」更多操作（实测 18.38×18.38）—— 手机无 hover，常显
        // ★ 2026-09-28：原来没接点击（死装饰），长按整行也开菜单。
        Box(
            modifier = Modifier
                .size(18.38.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center,
        ) {
            PainterIcon(
                R.drawable.ic_more_horizontal,
                size = 14.7.dp,
                tint = colors.textSecondary,
            )
        }
    }
}

/**
 * 底部用户区 —— 头像 + 名字 + 副标题 + 上箭头。
 *
 * 实测：头像 33.11×33.11，x=18.39，y=801.44（贴近底部）。
 */
@Composable
private fun UserRow(userName: String, subtitle: String, onClick: () -> Unit) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 18.39.dp, end = 14.72.dp, top = 8.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.2.dp),
    ) {
        // 对齐 Web Sidebar.tsx:1002 —— display_name → full_name → nickname → 'User'
        // userName 为空时取 'U' 做头像字母，显示文本用「未设置」
        val displayName = userName.ifBlank { "未设置" }
        val initial = userName.ifBlank { "U" }.take(1).uppercase()
        CcmAvatar(initial = initial, size = 33.11.dp)

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName,
                style = CCMText.body13,
                color = colors.textMain,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = CCMText.body11,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }

        // 上箭头（展开菜单）—— Web 用 ChevronUp，这里把 chevron-down 转 180°
        PainterIcon(
            R.drawable.ic_chevron_down,
            size = 12.88.dp,
            tint = colors.textSecondary,
            modifier = Modifier.rotate(180f),
        )
    }
}

/**
 * 对话摘要 —— 侧栏列表用。
 */
data class ChatSummary(
    val id: String,
    val title: String,
    val updatedAt: Long = 0,
)
