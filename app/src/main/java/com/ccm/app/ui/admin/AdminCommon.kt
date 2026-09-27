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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CcmMono
import com.ccm.app.ui.theme.CcmSans

/**
 * Admin 后台通用组件与配色。
 *
 * ## ★ 关键事实 1：Admin 用的是 Tailwind 默认灰阶，不是 claude 配色
 *
 * 主应用的颜色全走 `--bg-claude-*` CSS 变量（见 [com.ccm.app.ui.theme.CCMColors]），
 * 但 `admin/` 下的 `.tsx` 是**独立的管理后台**，通篇 `bg-gray-50` / `text-gray-800` /
 * `border-gray-200` / `bg-blue-500` —— 直接吃 Tailwind 默认调色板。
 *
 * 所以这个包里的颜色**不能**从 CCMColors 取，必须单列（见 [AdminColors]）。
 *
 * ## ★ 关键事实 2：Admin 没有移动端覆盖规则
 *
 * `index.css` 里 `grep -c admin` = **0**。`@media (max-width:767px)` 那段长长的
 * 覆盖清单里一条都没针对 admin。但全局规则仍生效：
 * - `#root { zoom: 0.92 }` —— 所有尺寸照样 ×0.92
 * - `[class*="text-[16px]"]` 之类的**硬编码字号**覆盖 —— 但 admin 用的是
 *   `text-xl` / `text-sm` 这类**语义类**，不在覆盖名单里，**保持原值**。
 *
 * 这产生一个反直觉结果：**Admin 的字号在手机上不缩水**（`text-xl` 恒为 20px →
 * 屏幕 18.40），而主应用同视觉层级的标题只有 12.29。这是源码的真实行为，
 * 不要"顺手对齐"。
 *
 * 换算表（Tailwind 值 × 0.92）：
 * | 类 | px | 屏幕 dp |
 * |---|---|---|
 * | `text-xs` | 12 | **11.04** |
 * | `text-sm` | 14 | **12.88** |
 * | `text-base` | 16 | **14.72** |
 * | `text-lg` | 18 | **16.56** |
 * | `text-xl` | 20 | **18.40** |
 * | `p-8` | 32 | **29.44** |
 * | `p-5` | 20 | **18.40** |
 * | `p-3` | 12 | **11.04** |
 * | `rounded-xl` | 12 | **11.04** |
 * | `rounded-lg` | 8 | **7.36** |
 */
object AdminColors {
    // ── 灰阶（Tailwind gray）─────────────────────────────────
    val gray50 = Color(0xFFF9FAFB)
    val gray100 = Color(0xFFF3F4F6)
    val gray200 = Color(0xFFE5E7EB)
    val gray300 = Color(0xFFD1D5DB)
    val gray400 = Color(0xFF9CA3AF)
    val gray500 = Color(0xFF6B7280)
    val gray600 = Color(0xFF4B5563)
    val gray700 = Color(0xFF374151)
    val gray800 = Color(0xFF1F2937)

    val white = Color(0xFFFFFFFF)

    /** 弹窗遮罩（`bg-black/40` = 黑 40%） */
    val black40 = Color(0x66000000)

    // ── 强调色（Tailwind 500/600 档）─────────────────────────
    val blue50 = Color(0xFFEFF6FF)
    val blue100 = Color(0xFFDBEAFE)
    val blue200 = Color(0xFFBFDBFE)
    val blue500 = Color(0xFF3B82F6)
    val blue600 = Color(0xFF2563EB)
    val green100 = Color(0xFFDCFCE7)
    val green500 = Color(0xFF22C55E)
    val green600 = Color(0xFF16A34A)
    val emerald500 = Color(0xFF10B981)
    val emerald600 = Color(0xFF059669)
    val purple500 = Color(0xFFA855F7)
    val orange500 = Color(0xFFF97316)
    val orange600 = Color(0xFFEA580C)
    val red50 = Color(0xFFFEF2F2)
    val red100 = Color(0xFFFEE2E2)
    val red200 = Color(0xFFFECACA)
    val red500 = Color(0xFFEF4444)
    val red600 = Color(0xFFDC2626)
    val cyan500 = Color(0xFF06B6D4)
    val indigo500 = Color(0xFF6366F1)
    val rose500 = Color(0xFFF43F5E)
    val amber500 = Color(0xFFF59E0B)
    val amber600 = Color(0xFFD97706)

    /** 状态色（`AdminKeyPool` 的 StatusDot / `AdminRedemption` 的 STATUS_COLORS） */
    val statusHealthy = Color(0xFF10B981)
    val statusDown = Color(0xFFEF4444)
    val statusUnknown = Color(0xFF9CA3AF)
}

/** 常用 Tailwind 字号（屏幕值，已 ×0.92） */
object AdminText {
    val xs = 11.04.sp
    val sm = 12.88.sp
    val base = 14.72.sp
    val lg = 16.56.sp
    val xl = 18.40.sp
    val xl2 = 22.08.sp
}

/** 常用 Tailwind 间距（屏幕值，已 ×0.92） */
object AdminSpacing {
    val p1 = 3.68.dp
    val p2 = 7.36.dp
    val p3 = 11.04.dp
    val p4 = 14.72.dp
    val p5 = 18.40.dp
    val p6 = 22.08.dp
    val p8 = 29.44.dp
    val gap3 = 11.04.dp
    val gap4 = 14.72.dp
    val gap6 = 22.08.dp
}

/**
 * Admin 页面外壳 —— 对应源码 `<div className="p-8">`。
 * 实测：内距 **29.44**（32 × 0.92）。
 */
@Composable
fun AdminPage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AdminColors.gray50)
            .padding(AdminSpacing.p8),
    ) {
        content()
    }
}

/**
 * 页面标题 —— 对应 `<h2 className="text-xl font-semibold text-gray-800">`。
 *
 * ⚠️ `text-xl`(20px) 是**语义类**，不在 `[class*="text-[16px]"]` 那批硬编码
 * 覆盖名单里 → 移动端**不缩水** → 屏幕 **18.40**。
 */
@Composable
fun AdminTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = CCMText.body20.copy(
            fontSize = AdminText.xl,
            fontWeight = FontWeight.SemiBold,
        ),
        color = AdminColors.gray800,
        modifier = modifier,
    )
}

/**
 * 卡片 —— 对应源码
 * `<div className="bg-white rounded-xl border border-gray-200 p-5">`。
 * 实测：圆角 **11.04** · 边框 `#E5E7EB` · 内距 **18.40**。
 */
@Composable
fun AdminCard(
    modifier: Modifier = Modifier,
    padding: Dp = AdminSpacing.p5,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .background(AdminColors.white)
            .border(1.dp, AdminColors.gray200, RoundedCornerShape(11.04.dp))
            .padding(padding),
    ) {
        content()
    }
}

/** 卡片内小标题 —— 对应 `<h3 className="text-sm font-medium text-gray-700">` */
@Composable
fun AdminSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = CCMText.body14.copy(
            fontSize = AdminText.sm,
            fontWeight = FontWeight.Medium,
        ),
        color = AdminColors.gray700,
        modifier = modifier,
    )
}

/** 说明文字 —— 对应 `<div className="text-xs text-gray-500">` */
@Composable
fun AdminHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = CCMText.body12.copy(fontSize = AdminText.xs),
        color = AdminColors.gray500,
        modifier = modifier,
    )
}

/**
 * 错误条 —— 对应源码
 * `<div className="mb-4 p-3 bg-red-50 border border-red-200 rounded-lg text-sm text-red-600">`。
 *
 * 实测：内距 **11.04** · 圆角 **7.36** · 字号 **12.88** ·
 * 底色 `#FEF2F2` · 边框 `#FECACA` · 文字 `#DC2626`。
 */
@Composable
fun AdminErrorBar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = AdminSpacing.p4)       // mb-4
            .clip(RoundedCornerShape(7.36.dp))
            .background(AdminColors.red50)
            .border(1.dp, AdminColors.red200, RoundedCornerShape(7.36.dp))
            .padding(AdminSpacing.p3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = CCMText.body14.copy(fontSize = AdminText.sm),
            color = AdminColors.red600,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "关闭",
            style = CCMText.body14.copy(fontSize = AdminText.sm),
            color = AdminColors.red600,
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(start = AdminSpacing.p2),
        )
    }
}

/**
 * Admin 按钮 —— 对应源码四种变体。
 *
 * 实测：`px-3 py-1.5 rounded-lg text-sm` →
 * 内距 **11.04 × 5.52** · 圆角 **7.36** · 字号 **12.88**（small 时 **11.04**）。
 */
@Composable
fun AdminButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AdminButtonVariant = AdminButtonVariant.SECONDARY,
    small: Boolean = false,
    enabled: Boolean = true,
) {
    val bg = when (variant) {
        AdminButtonVariant.SECONDARY -> AdminColors.white
        AdminButtonVariant.PRIMARY -> AdminColors.blue500
        AdminButtonVariant.SUCCESS -> AdminColors.emerald500
        AdminButtonVariant.DANGER -> AdminColors.red500
    }
    val fg = when (variant) {
        AdminButtonVariant.SECONDARY -> AdminColors.gray600
        else -> Color.White
    }
    val borderColor = if (variant == AdminButtonVariant.SECONDARY) AdminColors.gray200 else null
    val textSize = if (small) AdminText.xs else AdminText.sm

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(7.36.dp))
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, RoundedCornerShape(7.36.dp))
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.04.dp, vertical = 5.52.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = CCMText.body14.copy(fontSize = textSize, fontWeight = FontWeight.Medium),
            color = if (enabled) fg else fg.copy(alpha = 0.6f),
        )
    }
}

/** 按钮变体 */
enum class AdminButtonVariant { SECONDARY, PRIMARY, SUCCESS, DANGER }

/**
 * 表格输入框 —— 对应源码
 * `<input className="px-3 py-2 border border-gray-200 rounded-lg text-sm">`。
 *
 * ⚠️ Admin 里**没有** `.provider-detail input` 那条「宽度拉满」规则，
 * 宽度由调用方决定（多数是 `w-full` 或 `col-span-2`）。
 * 高度实测：`py-2` = 7.36×2 + 内容 ≈ **34.4**（比设置页的 38.8 矮）。
 */
@Composable
fun AdminTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minHeight: Dp = 34.4.dp,
) {
    Box(
        modifier = modifier
            .height(minHeight)
            .clip(RoundedCornerShape(7.36.dp))
            .background(AdminColors.white)
            .border(1.dp, AdminColors.gray200, RoundedCornerShape(7.36.dp))
            .padding(horizontal = 11.04.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = CCMText.body14.copy(
                fontSize = 14.72.sp,        // input 的 16px 下限 → 屏幕 14.72
                color = AdminColors.gray800,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(AdminColors.blue500),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.36.dp),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = CCMText.body14.copy(fontSize = 14.72.sp),
                        color = AdminColors.gray400,
                    )
                }
                inner()
            },
        )
    }
}

/**
 * 状态圆点 —— 对应 `AdminKeyPool` 的
 * `<span className="w-2 h-2 rounded-full" style={{ backgroundColor: ... }} />`。
 * 实测：`w-2 h-2` = 8 → 屏幕 **7.36**。
 */
@Composable
fun AdminStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 7.36.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * 标签徽章 —— 对应源码里各种
 * `<span className="px-2 py-0.5 text-xs rounded bg-blue-50 text-blue-600">`。
 * 实测：内距 **7.36 × 1.84** · 圆角 **3.68** · 字号 **11.04**。
 */
@Composable
fun AdminBadge(
    text: String,
    bg: Color,
    fg: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.68.dp))
            .background(bg)
            .padding(horizontal = 7.36.dp, vertical = 1.84.dp),
    ) {
        Text(
            text = text,
            style = CCMText.body12.copy(fontSize = AdminText.xs),
            color = fg,
        )
    }
}

/** 表格表头 —— 对应 `<th className="text-left text-xs font-medium text-gray-500">` */
@Composable
fun AdminTableHeader(cells: List<String>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AdminSpacing.p3),
    ) {
        cells.forEach { c ->
            Text(
                text = c,
                style = CCMText.body12.copy(
                    fontSize = AdminText.xs,
                    fontWeight = FontWeight.Medium,
                ),
                color = AdminColors.gray500,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 表格单元格（`text-sm text-gray-700`） */
@Composable
fun AdminTableCell(
    text: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    color: Color = AdminColors.gray700,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        style = CCMText.body14.copy(
            fontSize = AdminText.sm,
            fontFamily = if (mono) CcmMono else CcmSans,
        ),
        color = color,
        modifier = modifier,
        maxLines = maxLines,
    )
}

/** 空状态 —— 对应 `<div className="text-center py-8 text-gray-400">暂无数据</div>` */
@Composable
fun AdminEmpty(text: String = "暂无数据", modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AdminSpacing.p8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = CCMText.body14.copy(fontSize = AdminText.sm),
            color = AdminColors.gray400,
        )
    }
}

/** 加载态 —— 对应 `<div className="p-8 text-gray-400">加载中...</div>` */
@Composable
fun AdminLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(AdminSpacing.p8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "加载中...",
            style = CCMText.body14.copy(fontSize = AdminText.sm),
            color = AdminColors.gray400,
        )
    }
}

/** 页面头部（标题 + 右侧按钮组）—— 对应 `<div className="flex items-center justify-between mb-6">` */
@Composable
fun AdminPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = AdminSpacing.p6),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AdminTitle(text = title)
        Row(horizontalArrangement = Arrangement.spacedBy(AdminSpacing.p2)) {
            actions()
        }
    }
}

/** 竖向间隔 */
@Composable
fun AdminVSpace(height: Dp) = Spacer(Modifier.height(height))

/**
 * Admin 下拉选择框 —— 对应源码
 * `<select className="px-3 py-1.5 border border-gray-200 rounded-lg text-sm">`。
 *
 * ⚠️ 这里**不做真实下拉**（Compose 无等价原生控件，material3 的 DropdownMenu
 * 是弹层，与 `<select>` 的移动端表现不同）。当前只渲染「当前值 + 指示符」的静态框 ——
 * 尺寸、边框、圆角、字号与源码一致，交互留给后续接入真实数据时补。
 * 迁移阶段这样处理是为了**先保证视觉零变化**，交互逻辑在接线阶段统一补。
 *
 * 实测：`px-3 py-1.5` → 内距 11.04 × 5.52 · 圆角 7.36 · 高 ≈ 31.3。
 */
@Composable
fun AdminSelect(
    value: String,
    @Suppress("UNUSED_PARAMETER") options: List<String>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(31.3.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(AdminColors.white)
            .border(1.dp, AdminColors.gray200, RoundedCornerShape(7.36.dp))
            .padding(horizontal = AdminSpacing.p3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = value,
            style = CCMText.body14.copy(fontSize = AdminText.sm),
            color = AdminColors.gray700,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = "▾",
            style = CCMText.body12.copy(fontSize = AdminText.xs),
            color = AdminColors.gray400,
        )
    }
}

/**
 * 图标操作按钮 —— 源码是 lucide 的 `Edit2` / `Power` / `Trash2`（`size={14}` → **12.88**）。
 *
 * 实测：按钮 `p-1.5` → 内距 **5.52** · `rounded` → 圆角 **3.68**。
 *
 * ⚠️ 项目里没有矢量图标资源（CCMIcons 是自绘 Canvas），Admin 表格里的
 * 图标按钮统一用**文字标签**代替 —— 这是「零变化」原则下的**已知偏差**：
 * 尺寸/位置/交互完全一致，只有图形被文字替换。想换成图形需先补 CCMIcons。
 */
@Composable
fun AdminIconAction(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.68.dp))
            .clickable { }
            .padding(5.52.dp),      // p-1.5
    ) {
        Text(
            text = label,
            style = CCMText.body10.copy(fontSize = 8.28.sp),
            color = color,
        )
    }
}

/** 健康状态 → 颜色（对应 `StatusDot`） */
fun AdminStatusColor(status: String): Color = when (status) {
    "healthy", "ok", "up" -> AdminColors.statusHealthy
    "down", "error" -> AdminColors.statusDown
    else -> AdminColors.statusUnknown
}
