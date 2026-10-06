package com.ccm.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CcmMono

/**
 * Admin · 用户管理 —— 对齐 `AdminUsers.tsx`（约 250 行）。
 *
 * ## 源码结构
 * 1. 标题「用户管理」
 * 2. 搜索栏：左侧放大镜图标 + 输入框（`pl-9 pr-3`）+ 蓝色「搜索」按钮
 * 3. 用户表格（8 列）：用户 / 角色 / 套餐 / Token 用量 / Storage / 状态 / 注册时间 / 操作
 * 4. 用户详情弹窗（点行展开）
 *
 * ## 移动端
 * - 搜索栏 `flex gap-2 mb-4`，输入框 `flex-1 max-w-md`（28rem = 448px，
 *   超过 393 视口 → 实际占满剩余宽）
 * - 表格 8 列 → 横向滚动
 *
 * 实测（Tailwind × 0.92）：
 * - 搜索框 `pl-9 pr-3 py-2` → 左内距 33.12 / 右 11.04 / 上下 7.36
 * - 放大镜 `absolute left-3` → 左 11.04，图标 16 → 14.72
 * - 角色徽章 `text-xs px-2 py-0.5 rounded-full` → 11.04 / 7.36×1.84 / 全圆角
 */
@Composable
fun AdminUsersPage(modifier: Modifier = Modifier) {
    var search by remember { mutableStateOf("") }

    AdminPage(modifier = modifier.verticalScroll(rememberScrollState())) {
        AdminTitle(text = "用户管理")
        Spacer(Modifier.height(AdminSpacing.p6))

        // ── 搜索栏 ───────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = AdminSpacing.p4),
            horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                AdminTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = "搜索邮箱、昵称、ID...",
                    modifier = Modifier.fillMaxWidth(),
                )
                // 左侧放大镜图标（源码 absolute left-3）
                Box(
                    modifier = Modifier.padding(start = 11.04.dp).align(Alignment.CenterStart),
                ) {
                    SearchIcon(color = AdminColors.gray400, size = 14.72.dp)
                }
            }
            AdminButton(
                label = "搜索",
                onClick = { },
                variant = AdminButtonVariant.PRIMARY,
            )
        }

        // ── 用户表格 ─────────────────────────────────────────────
        UserTable(
            users = listOf(
                UserRow("Jay", "3843364195@qq.com", "superadmin", "免费版", "1.2M / 5M", "128 MB", true, "Sep 20, 2026"),
                UserRow("小明", "xiaoming@qq.com", "user", "专业版", "842K / 2M", "56 MB", true, "Sep 25, 2026"),
                UserRow("测试号", "test@test.com", "user", "免费版", "12K / 100K", "3 MB", false, "Sep 26, 2026"),
            ),
        )
    }
}

/** 用户行数据 */
private data class UserRow(
    val nickname: String,
    val email: String,
    val role: String,
    val plan: String,
    val tokenUsage: String,
    val storage: String,
    val active: Boolean,
    val createdAt: String,
)

/**
 * 用户表格 —— 8 列，外层统一横向滚动（同 KeyPool 的策略）。
 *
 * 角色徽章配色（源码）：
 * - `superadmin` → `bg-red-100 text-red-600`（#FEE2E2 / #DC2626）
 * - `admin` → `bg-purple-100 text-purple-600`（#F3E8FF / #9333EA）
 * - 其他 → `bg-gray-100 text-gray-600`（#F3F4F6 / #4B5563）
 */
@Composable
private fun UserTable(users: List<UserRow>) {
    val hScroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .background(AdminColors.white)
            .border(1.dp, AdminColors.gray200, RoundedCornerShape(11.04.dp)),
    ) {
        Column(modifier = Modifier.horizontalScroll(hScroll)) {
            // 表头
            Row(
                modifier = Modifier
                    .background(AdminColors.gray50)
                    .padding(horizontal = AdminSpacing.p4),
            ) {
                UserCell("用户", 150.dp, header = true)
                UserCell("角色", 72.dp, header = true)
                UserCell("套餐", 72.dp, header = true)
                UserCell("Token 用量", 106.dp, header = true)
                UserCell("Storage", 74.dp, header = true)
                UserCell("状态", 60.dp, header = true)
                UserCell("注册时间", 106.dp, header = true)
                UserCell("操作", 82.dp, header = true)
            }
            Divider1(AdminColors.gray200)

            if (users.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 29.44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "无数据",
                        style = CCMText.body14.copy(fontSize = AdminText.sm),
                        color = AdminColors.gray400,
                    )
                }
            }

            users.forEach { u ->
                Row(modifier = Modifier.padding(horizontal = AdminSpacing.p4)) {
                    // 用户列：昵称 + 邮箱（两行）
                    Column(modifier = Modifier.width(150.dp).padding(vertical = AdminSpacing.p3)) {
                        Text(
                            text = u.nickname.ifEmpty { "-" },
                            style = CCMText.body14.copy(
                                fontSize = AdminText.sm,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = AdminColors.gray800,
                            maxLines = 1,
                        )
                        Text(
                            text = u.email,
                            style = CCMText.body12.copy(fontSize = AdminText.xs),
                            color = AdminColors.gray400,
                            maxLines = 1,
                        )
                    }
                    // 角色徽章
                    Box(modifier = Modifier.width(72.dp).padding(vertical = AdminSpacing.p3)) {
                        val (bg, fg, label) = when (u.role) {
                            "superadmin" -> Triple(
                                androidx.compose.ui.graphics.Color(0xFFFEE2E2),
                                androidx.compose.ui.graphics.Color(0xFFDC2626),
                                "超管",
                            )
                            "admin" -> Triple(
                                androidx.compose.ui.graphics.Color(0xFFF3E8FF),
                                androidx.compose.ui.graphics.Color(0xFF9333EA),
                                "管理员",
                            )
                            else -> Triple(
                                AdminColors.gray100,
                                AdminColors.gray600,
                                "用户",
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))       // rounded-full
                                .background(bg)
                                .padding(horizontal = 7.36.dp, vertical = 1.84.dp),
                        ) {
                            Text(
                                text = label,
                                style = CCMText.body12.copy(fontSize = AdminText.xs),
                                color = fg,
                            )
                        }
                    }
                    UserCell(u.plan, 72.dp)
                    UserCell(u.tokenUsage, 106.dp, size = AdminText.xs, mono = true)
                    UserCell(u.storage, 74.dp, size = AdminText.xs)
                    // 状态
                    Box(modifier = Modifier.width(60.dp).padding(vertical = AdminSpacing.p3)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p1),
                        ) {
                            AdminStatusDot(
                                color = if (u.active) AdminColors.statusHealthy else AdminColors.gray400,
                                size = 5.52.dp,
                            )
                            Text(
                                text = if (u.active) "正常" else "封禁",
                                style = CCMText.body12.copy(fontSize = AdminText.xs),
                                color = if (u.active) AdminColors.gray600 else AdminColors.red500,
                            )
                        }
                    }
                    UserCell(u.createdAt, 106.dp, size = AdminText.xs)
                    // 操作
                    Row(
                        modifier = Modifier.width(82.dp).padding(vertical = AdminSpacing.p3),
                        horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2),
                    ) {
                        Text(
                            text = "详情",
                            style = CCMText.body12.copy(fontSize = AdminText.xs),
                            color = AdminColors.blue600,
                        )
                        Text(
                            text = if (u.active) "封禁" else "解封",
                            style = CCMText.body12.copy(fontSize = AdminText.xs),
                            color = if (u.active) AdminColors.red500 else AdminColors.green500,
                        )
                    }
                }
                Divider1(AdminColors.gray100)
            }
        }
    }
}

/** 用户表单元格 */
@Composable
private fun UserCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    header: Boolean = false,
    size: androidx.compose.ui.unit.TextUnit = AdminText.sm,
    mono: Boolean = false,
) {
    Box(modifier = Modifier.width(width).padding(vertical = AdminSpacing.p3)) {
        Text(
            text = text,
            style = CCMText.body14.copy(
                fontSize = if (header) AdminText.xs else size,
                fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
                fontFamily = if (mono) CcmMono else com.ccm.app.ui.theme.CcmSans,
            ),
            // 【2026-10-06 修】原来是 if (header) gray600 else gray600 ——
            // 两个分支同值，三元无意义。
            // 修正为「表头浅、数据行深」（与 AdminModelsPage:295 一致：
            // gray500 / gray700）—— 表头是次要信息，数据行才是要读的。
            color = if (header) AdminColors.gray500 else AdminColors.gray700,
            maxLines = 1,
        )
    }
}

/** 放大镜 —— 源码 lucide `Search size={16}` → 14.72 */
@Composable
private fun SearchIcon(
    color: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.Dp,
) {
    androidx.compose.foundation.Canvas(modifier = Modifier.width(size).height(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.38.dp.toPx()
        drawCircle(
            color = color,
            radius = w * 0.32f,
            center = androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.42f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        drawLine(
            color,
            androidx.compose.ui.geometry.Offset(w * 0.65f, h * 0.65f),
            androidx.compose.ui.geometry.Offset(w * 0.9f, h * 0.9f),
            stroke,
            androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}
