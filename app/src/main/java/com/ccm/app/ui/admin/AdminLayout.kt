package com.ccm.app.ui.admin

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.ccm.app.ui.theme.CCMText

/**
 * 管理后台外壳 —— 对齐 `AdminLayout.tsx`（67 行）。
 *
 * ## 源码结构（桌面）
 * ```
 * <div className="flex h-screen bg-gray-50">
 *   <aside className="w-56 bg-white border-r border-gray-200 flex flex-col">
 *     <div className="p-4 border-b">管理后台</div>       ← w-56 = 224px
 *     <nav className="flex-1 py-2">7 个导航项</nav>
 *     <div className="p-3 border-t">返回主页</div>
 *   </aside>
 *   <main className="flex-1 overflow-auto"><Outlet /></main>
 * </div>
 * ```
 *
 * ## ★ 移动端行为
 * Admin **没有任何** `@media` 覆盖规则（`grep -c admin index.css` = 0）。
 * 所以移动端**仍然是桌面布局**：224px 侧栏 + 剩余内容区。
 *
 * 在 393px 视口下：侧栏占 224 × 0.92 = **206.08**，内容区只剩 **187**（减去 zoom）。
 * 这就是源码的真实行为 —— 挤，但能用（导航项是 `text-sm`，206px 够放下）。
 *
 * ⚠️ **不要"顺手"把侧栏改成抽屉** —— 那是设计改进，不是迁移。
 * 零变化要求下，必须保持 224px 固定侧栏。
 *
 * ## 导航项实测（Tailwind × 0.92）
 * `w-full flex items-center gap-3 px-4 py-2.5 text-sm` →
 * 内距 **14.72 × 9.2** · 图标间距 **11.04** · 字号 **12.88** · 图标 18 → **16.56**。
 *
 * 激活态：`bg-blue-50 text-blue-600 font-medium`（`#EFF6FF` / `#2563EB`）
 * 非激活：`text-gray-600 hover:bg-gray-50`（`#4B5563`）
 */
@Composable
fun AdminLayout(
    modifier: Modifier = Modifier,
    current: AdminPage2 = AdminPage2.DASHBOARD,
    onNavigate: (AdminPage2) -> Unit = {},
    onBackHome: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Row(modifier = modifier.fillMaxSize().background(AdminColors.gray50)) {
        // ── 侧栏（移动端保持 224px，与源码一致）──────────────────
        Column(
            modifier = Modifier
                .width(206.08.dp)                       // w-56 = 224 × 0.92
                .fillMaxSize()
                .background(AdminColors.white),
        ) {
            // 标题区：p-4 border-b
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AdminColors.white)
                    .padding(AdminSpacing.p4),
            ) {
                Text(
                    text = "管理后台",
                    style = CCMText.body18.copy(
                        fontSize = AdminText.lg,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = AdminColors.gray800,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AdminColors.gray200),
            )

            // 导航项：py-2
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = AdminSpacing.p2),
            ) {
                AdminPage2.entries.forEach { item ->
                    AdminNavItem(
                        item = item,
                        active = item == current,
                        onClick = { onNavigate(item) },
                    )
                }
            }

            // 底部「返回主页」：p-3 border-t
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(AdminColors.gray200),
            )
            Box(modifier = Modifier.padding(AdminSpacing.p3)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(7.36.dp))
                        .clickable(onClick = onBackHome)
                        .padding(horizontal = 11.04.dp, vertical = 7.36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2),
                ) {
                    ArrowLeftIcon(color = AdminColors.gray500, size = 14.72.dp)
                    Text(
                        text = "返回主页",
                        style = CCMText.body14.copy(fontSize = AdminText.sm),
                        color = AdminColors.gray500,
                    )
                }
            }
        }

        // ── 内容区 ───────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            content()
        }
    }
}

/**
 * 导航项 —— 对应源码
 * `<button className="w-full flex items-center gap-3 px-4 py-2.5 text-sm
 *   ${active ? 'bg-blue-50 text-blue-600 font-medium' : 'text-gray-600 hover:bg-gray-50'}">`
 *
 * 实测（Tailwind × 0.92）：内距 **14.72 × 9.2** · 间距 **11.04** · 字号 **12.88**。
 */
@Composable
private fun AdminNavItem(
    item: AdminPage2,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (active) AdminColors.blue50 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = AdminSpacing.p4, vertical = 9.2.dp),   // px-4 py-2.5
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AdminSpacing.gap3),
    ) {
        AdminNavIcon(
            page = item,
            color = if (active) AdminColors.blue600 else AdminColors.gray600,
            size = 16.56.dp,
        )
        Text(
            text = item.label,
            style = CCMText.body14.copy(
                fontSize = AdminText.sm,
                fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
            ),
            color = if (active) AdminColors.blue600 else AdminColors.gray600,
        )
    }
}

/**
 * 导航图标 —— 源码用 lucide（LayoutDashboard / Key / Cpu / Users / Bell / Package / Ticket）。
 * 这里用 Canvas 画简化版，避免引入整套图标库。
 */
@Composable
private fun AdminNavIcon(
    page: AdminPage2,
    color: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.38.dp.toPx()
        val s = androidx.compose.ui.graphics.drawscope.Stroke(
            width = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        when (page) {
            // LayoutDashboard：四宫格
            AdminPage2.DASHBOARD -> {
                val g = w * 0.12f
                val bw = (w - g) / 2f
                drawRect(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Size(bw, bw), style = s)
                drawRect(color, androidx.compose.ui.geometry.Offset(bw + g, 0f), androidx.compose.ui.geometry.Size(bw, bw), style = s)
                drawRect(color, androidx.compose.ui.geometry.Offset(0f, bw + g), androidx.compose.ui.geometry.Size(bw, bw), style = s)
                drawRect(color, androidx.compose.ui.geometry.Offset(bw + g, bw + g), androidx.compose.ui.geometry.Size(bw, bw), style = s)
            }
            // Key：圆 + 齿
            AdminPage2.KEYS -> {
                drawCircle(color, radius = w * 0.22f, center = androidx.compose.ui.geometry.Offset(w * 0.3f, h * 0.35f), style = s)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.45f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.85f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.68f), androidx.compose.ui.geometry.Offset(w * 0.8f, h * 0.56f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
            // Cpu：方框 + 引脚
            AdminPage2.MODELS -> {
                drawRect(color, androidx.compose.ui.geometry.Offset(w * 0.2f, h * 0.2f), androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.6f), style = s)
                listOf(0.35f, 0.5f, 0.65f).forEach { f ->
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * f, 0f), androidx.compose.ui.geometry.Offset(w * f, h * 0.2f), stroke)
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * f, h * 0.8f), androidx.compose.ui.geometry.Offset(w * f, h), stroke)
                }
            }
            // Users：两个圆头 + 肩
            AdminPage2.USERS -> {
                drawCircle(color, radius = w * 0.16f, center = androidx.compose.ui.geometry.Offset(w * 0.38f, h * 0.32f), style = s)
                drawCircle(color, radius = w * 0.13f, center = androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.36f), style = s)
                drawArc(color, 180f, 180f, false, style = s, topLeft = androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.55f), size = androidx.compose.ui.geometry.Size(w * 0.46f, h * 0.34f))
            }
            // Bell：钟形
            AdminPage2.ANNOUNCEMENTS -> {
                drawArc(color, 180f, 180f, false, style = s, topLeft = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.15f), size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.6f))
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.15f), androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.08f), stroke)
                drawCircle(color, radius = w * 0.08f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.82f), style = s)
            }
            // Package：立方体
            AdminPage2.PLANS -> {
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.5f, h * 0.08f)
                    lineTo(w * 0.9f, h * 0.3f)
                    lineTo(w * 0.9f, h * 0.72f)
                    lineTo(w * 0.5f, h * 0.94f)
                    lineTo(w * 0.1f, h * 0.72f)
                    lineTo(w * 0.1f, h * 0.3f)
                    close()
                }
                drawPath(p, color, style = s)
            }
            // Ticket：票券
            AdminPage2.REDEMPTION -> {
                drawRoundRect(color, androidx.compose.ui.geometry.Offset(w * 0.08f, h * 0.22f), androidx.compose.ui.geometry.Size(w * 0.84f, h * 0.56f), androidx.compose.ui.geometry.CornerRadius(w * 0.1f), style = s)
                drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.6f, h * 0.22f), androidx.compose.ui.geometry.Offset(w * 0.6f, h * 0.78f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

/** 返回箭头 —— 源码 lucide `ArrowLeft size={16}` → 14.72 */
@Composable
private fun ArrowLeftIcon(color: Color, size: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.38.dp.toPx()
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.85f, h * 0.5f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.22f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.15f, h * 0.5f), androidx.compose.ui.geometry.Offset(w * 0.42f, h * 0.78f), stroke, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/**
 * Admin 的 7 个页面 —— 对应 `AdminLayout.tsx` 的 `NAV` 数组。
 *
 * ⚠️ 枚举名用 `AdminPage2` 是为了避开与 `CcmRoute` 的潜在同名冲突
 * （项目里已有 `ui.shell.CcmRoute`）。改名成本低于后期排查 import 冲突。
 */
enum class AdminPage2(val label: String) {
    DASHBOARD("Dashboard"),
    KEYS("密钥池"),
    MODELS("模型管理"),
    USERS("用户管理"),
    ANNOUNCEMENTS("公告管理"),
    PLANS("套餐管理"),
    REDEMPTION("兑换码"),
}

/** 未使用但保留 —— 供后续 Admin 路由接入时用 */
@Composable
fun AdminRouteHost(
    page: AdminPage2,
    modifier: Modifier = Modifier,
) {
    var current by remember { mutableStateOf(page) }
    AdminLayout(
        modifier = modifier,
        current = current,
        onNavigate = { current = it },
    ) {
        when (current) {
            AdminPage2.DASHBOARD -> AdminDashboardPage()
            AdminPage2.KEYS -> AdminKeyPoolPage()
            AdminPage2.MODELS -> AdminModelsPage()
            AdminPage2.USERS -> AdminUsersPage()
            AdminPage2.ANNOUNCEMENTS -> AdminAnnouncementsPage()
            AdminPage2.PLANS -> AdminPlansPage()
            AdminPage2.REDEMPTION -> AdminRedemptionPage()
        }
    }
}

/** 权限验证中 —— 对应源码 `<div className="flex items-center justify-center h-screen text-gray-400">` */
@Composable
fun AdminAuthChecking(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().background(AdminColors.gray50),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "验证权限中...",
            style = CCMText.body14.copy(fontSize = AdminText.sm),
            color = AdminColors.gray400,
        )
    }
}

/** 占位（Admin 页面逐个实现后替换） */
@Composable
private fun AdminPlaceholder(title: String) {
    AdminPage {
        AdminTitle(text = title)
        Spacer(Modifier.height(AdminSpacing.p6))
        AdminEmpty()
    }
}
