package com.ccm.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText

/**
 * Admin · 公告管理 —— 对齐 `AdminAnnouncements.tsx`（约 190 行）。
 *
 * ## 源码结构
 * 1. 头部：标题 + 副标题 + 刷新/添加公告
 * 2. 添加/编辑表单（展开时）：标题 / 内容（textarea rows=8）/ 「保存后立即发布」勾选
 * 3. 公告列表：每条显示标题、内容摘要、发布状态、时间、编辑/删除
 *
 * 副标题原文：
 * 「新增或发布公告后，用户下次进入页面会弹窗显示；在线用户会在轮询后自动收到。」
 *
 * ## 实测（Tailwind × 0.92）
 * - 副标题 `text-sm text-gray-500 mt-1` → 12.88 / 3.68
 * - textarea `rows={8}` → 高度按 8 行 × 行高（14px × 1.5 = 21 → 19.32）+ 上下内距 14.72
 *   ≈ **169.3**
 * - 勾选框 `inline-flex items-center gap-2 text-sm` → 间距 7.36
 */
@Composable
fun AdminAnnouncementsPage(modifier: Modifier = Modifier) {
    var showForm by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf<Int?>(null) }
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var publishNow by remember { mutableStateOf(true) }

    AdminPage(modifier = modifier.verticalScroll(rememberScrollState())) {
        // ── 头部（标题 + 副标题 + 按钮组）────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = AdminSpacing.p6),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AdminTitle(text = "公告管理")
                Spacer(Modifier.height(3.68.dp))        // mt-1
                Text(
                    text = "新增或发布公告后，用户下次进入页面会弹窗显示；" +
                        "在线用户会在轮询后自动收到。",
                    style = CCMText.body14.copy(fontSize = AdminText.sm),
                    color = AdminColors.gray500,
                )
            }
            Spacer(Modifier.width(AdminSpacing.p2))
            Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2)) {
                AdminButton(label = "刷新", onClick = { })
                AdminButton(
                    label = "添加公告",
                    onClick = { showForm = true; editId = null },
                    variant = AdminButtonVariant.PRIMARY,
                )
            }
        }

        // ── 表单 ─────────────────────────────────────────────────
        if (showForm) {
            AdminCard {
                AdminSectionTitle(text = if (editId != null) "编辑公告" else "添加公告")
                Spacer(Modifier.height(AdminSpacing.p3))
                Column(verticalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
                    AdminTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "公告标题 *",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // textarea rows={8}
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(169.3.dp)
                            .clip(RoundedCornerShape(7.36.dp))
                            .background(AdminColors.white)
                            .border(1.dp, AdminColors.gray200, RoundedCornerShape(7.36.dp))
                            .padding(horizontal = 11.04.dp, vertical = 7.36.dp),
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = content,
                            onValueChange = { content = it },
                            textStyle = CCMText.body14.copy(
                                fontSize = 14.72.sp,
                                color = AdminColors.gray800,
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(AdminColors.blue500),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (content.isEmpty()) {
                                    Text(
                                        text = "公告内容 *",
                                        style = CCMText.body14.copy(fontSize = 14.72.sp),
                                        color = AdminColors.gray400,
                                    )
                                }
                                inner()
                            },
                        )
                    }
                    // 「保存后立即发布」勾选
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2),
                    ) {
                        AdminCheckbox(
                            checked = publishNow,
                            onToggle = { publishNow = !publishNow },
                        )
                        Text(
                            text = "保存后立即发布",
                            style = CCMText.body14.copy(fontSize = AdminText.sm),
                            color = AdminColors.gray600,
                        )
                    }
                }
                Spacer(Modifier.height(AdminSpacing.p4))    // mt-4
                Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2)) {
                    AdminButton(
                        label = "保存",
                        onClick = { showForm = false; editId = null },
                        variant = AdminButtonVariant.PRIMARY,
                    )
                    AdminButton(
                        label = "取消",
                        onClick = { showForm = false; editId = null },
                    )
                }
            }
            Spacer(Modifier.height(AdminSpacing.p6))
        }

        // ── 公告列表 ─────────────────────────────────────────────
        AnnouncementList(
            items = listOf(
                AnnouncementRow(
                    id = 1,
                    title = "CCM v0.8.100 发布",
                    content = "新增 goal 完成契约、/voice 正文朗读、Hashline 工具集。",
                    published = true,
                    createdAt = "Sep 27, 2026, 09:12 AM",
                ),
                AnnouncementRow(
                    id = 2,
                    title = "维护通知",
                    content = "今晚 02:00-03:00 进行上游切换，期间可能短暂不可用。",
                    published = false,
                    createdAt = "Sep 26, 2026, 18:40 PM",
                ),
            ),
        )
    }
}

/** 公告行数据 */
private data class AnnouncementRow(
    val id: Int,
    val title: String,
    val content: String,
    val published: Boolean,
    val createdAt: String,
)

/**
 * 公告列表 —— 每条是一张卡片：
 * 标题行（标题 + 发布状态徽章）+ 内容 + 时间 + 操作（编辑/删除）。
 */
@Composable
private fun AnnouncementList(items: List<AnnouncementRow>) {
    if (items.isEmpty()) {
        AdminCard { AdminEmpty(text = "暂无公告") }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(AdminSpacing.gap3)) {
        items.forEach { a ->
            AdminCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = a.title,
                        style = CCMText.body16.copy(
                            fontSize = AdminText.base,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = AdminColors.gray800,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(AdminSpacing.p2))
                    if (a.published) {
                        AdminBadge(
                            text = "已发布",
                            bg = Color(0xFFDCFCE7),      // green-100
                            fg = Color(0xFF16A34A),      // green-600
                        )
                    } else {
                        AdminBadge(
                            text = "草稿",
                            bg = AdminColors.gray100,
                            fg = AdminColors.gray600,
                        )
                    }
                }
                Spacer(Modifier.height(AdminSpacing.p2))
                Text(
                    text = a.content,
                    style = CCMText.body14.copy(fontSize = AdminText.sm),
                    color = AdminColors.gray600,
                    maxLines = 3,
                )
                Spacer(Modifier.height(AdminSpacing.p3))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = a.createdAt,
                        style = CCMText.body12.copy(fontSize = AdminText.xs),
                        color = AdminColors.gray400,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2)) {
                        Text(
                            text = "编辑",
                            style = CCMText.body12.copy(fontSize = AdminText.xs),
                            color = AdminColors.blue600,
                        )
                        Text(
                            text = "删除",
                            style = CCMText.body12.copy(fontSize = AdminText.xs),
                            color = AdminColors.red500,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Admin 勾选框 —— 对应源码 `<input type="checkbox" className="…">`。
 *
 * 浏览器默认 checkbox 是 13×13（Chrome/Android 约 14px），
 * 这里取 **12.88**（14 × 0.92），选中色用系统蓝 `#2563EB`。
 */
@Composable
internal fun AdminCheckbox(
    checked: Boolean,
    onToggle: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 12.88.dp,
) {
    Box(
        modifier = Modifier
            .width(size)
            .height(size)
            .clip(RoundedCornerShape(2.76.dp))
            .background(if (checked) AdminColors.blue600 else AdminColors.white)
            .border(
                width = 1.dp,
                color = if (checked) AdminColors.blue600 else AdminColors.gray300,
                shape = RoundedCornerShape(2.76.dp),
            )
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            androidx.compose.foundation.Canvas(modifier = Modifier.width(size).height(size)) {
                val w = this.size.width
                val h = this.size.height
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.24f, h * 0.5f)
                    lineTo(w * 0.43f, h * 0.7f)
                    lineTo(w * 0.76f, h * 0.31f)
                }
                drawPath(
                    path = p,
                    color = Color.White,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.38.dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    ),
                )
            }
        }
    }
}
