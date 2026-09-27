package com.ccm.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ccm.app.ui.theme.CCMRadius
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * B1 基础原子组件 —— 对齐 Web 的 Tailwind 原子类组合。
 *
 * 所有数值来自 **MEASURED.md**（Playwright 实测），不是从 Tailwind 文档推算的。
 *
 * ## 一个关键坑：反向 zoom 补偿的边框
 * 实测建议按钮的边框是 **1.08696px**（不是 1px）：
 * ```
 * border-width: calc(1px / var(--mobile-zoom))   即 1 除以 0.92 = 1.08696
 * ```
 * Web 为了让 1px 边框在 0.92 zoom 后仍是视觉 1px，做了反向补偿。
 * Compose 里直接写 **1.dp** 即可（我们已经在屏幕坐标系里）。
 *
 * > ⚠️ 写 KDoc 时别让注释里出现「斜杠+星号」或「星号+斜杠」的连续两字符，
 * > Kotlin 块注释可嵌套，那会破坏注释结构。用代码块（三个反引号）包住即可。
 */

// ══ 分割线 ══════════════════════════════════════════════════════════

/**
 * 水平分割线 —— 对齐 `border-b border-claude-border`。
 *
 * Web 实测：`1px solid` [com.ccm.app.ui.theme.CCMColors.border]，
 * 反向 zoom 后视觉仍是 1px。
 */
@Composable
fun CcmDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    color: Color = CCMTheme.colors.border,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color),
    )
}

/** 垂直分割线 */
@Composable
fun CcmVDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 1.dp,
    height: Dp = 16.dp,
    color: Color = CCMTheme.colors.border,
) {
    Box(
        modifier = modifier
            .width(thickness)
            .height(height)
            .background(color),
    )
}

// ══ 徽章 / 标签 ══════════════════════════════════════════════════════

/**
 * 徽章 —— 小尺寸圆角标签，用于状态、计数、标签。
 *
 * 圆角用 [CCMRadius.base]（`rounded` = 4px × 0.92 = 3.68dp）。
 *
 * @param text  文字
 * @param bg    背景色
 * @param fg    文字色
 */
@Composable
fun CcmBadge(
    text: String,
    modifier: Modifier = Modifier,
    bg: Color = CCMTheme.colors.hover,
    fg: Color = CCMTheme.colors.textSecondary,
    style: TextStyle = CCMText.body11,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(CCMRadius.base))
            .background(bg)
            .padding(horizontal = 5.52.dp, vertical = 1.84.dp),   // px-1.5 py-0.5 → 6/2 × 0.92
    ) {
        Text(text = text, style = style, color = fg, maxLines = 1)
    }
}

// ══ 卡片 ════════════════════════════════════════════════════════════

/**
 * 卡片容器 —— 对齐 Web 的
 * `rounded-2xl border border-claude-border bg-claude-input`。
 *
 * 实测圆角：`rounded-2xl` 在移动端是 `clamp(10, 3vw, 16)` = 11.79 → **10.85dp**。
 * 用 [CCMRadius.xxl]。
 */
@Composable
fun CcmCard(
    modifier: Modifier = Modifier,
    bg: Color = CCMTheme.colors.input,
    borderColor: Color = CCMTheme.colors.border,
    radius: Dp = CCMRadius.xxl,
    showBorder: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius))
            .background(bg)
            .then(if (showBorder) Modifier.border(1.dp, borderColor, RoundedCornerShape(radius)) else Modifier),
    ) {
        content()
    }
}

// ══ 头像 ════════════════════════════════════════════════════════════

/**
 * 头像 —— 圆形 + 首字母。
 *
 * 颜色用 [com.ccm.app.ui.theme.CCMColors.avatarBg] / `avatarText`
 * （这两个 token 明暗反转：亮色深底白字，暗色浅底黑字）。
 *
 * @param initial 首字母（Web 取昵称首字符大写）
 * @param size    直径，Web 常见 28 / 32
 */
@Composable
fun CcmAvatar(
    initial: String,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val colors = CCMTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(colors.avatarBg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial.take(1).uppercase(),
            style = CCMText.body12,
            color = colors.avatarText,
        )
    }
}

// ══ 空态 / 加载态 ════════════════════════════════════════════════════

/**
 * 空态占位 —— 居中图标 + 标题 + 说明。
 *
 * Web 的空态通常是「图标（opacity-30）+ 标题（text-claude-text）+ 副文案（text-claude-textSecondary）」。
 */
@Composable
fun CcmEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    iconRes: Int? = null,
) {
    val colors = CCMTheme.colors
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.36.dp),
        ) {
            if (iconRes != null) {
                Box(modifier = Modifier.size(40.dp)) {
                    PainterIcon(iconRes, size = 40.dp, tint = colors.textSecondary)
                }
            }
            Text(text = title, style = CCMText.body14, color = colors.textMain)
            if (description != null) {
                Text(
                    text = description,
                    style = CCMText.body12,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

// ══ 列表项 ══════════════════════════════════════════════════════════

/**
 * 通用列表项 —— 图标 + 标题（+ 副标题），可点。
 *
 * 对齐 Web 侧栏项的样式：
 * `flex items-center gap-2 rounded-lg px-2 py-1.5 hover:bg-claude-hover`。
 *
 * @param title    主文字
 * @param subtitle 副文字（可空）
 * @param iconRes  左侧图标（可空）
 * @param trailing 右侧内容（可空）
 */
@Composable
fun CcmListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconRes: Int? = null,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = CCMTheme.colors
    val bg = if (selected) colors.hover else Color.Transparent

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CCMRadius.lg))
            .background(bg)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 7.36.dp, vertical = 5.52.dp),   // px-2 py-1.5
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.36.dp),    // gap-2
    ) {
        if (iconRes != null) {
            PainterIcon(iconRes, size = 16.dp, tint = colors.sidebarText)
        }
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = CCMText.body13,
                color = colors.sidebarText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = CCMText.body11,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

// ══ 胶囊按钮 ════════════════════════════════════════════════════════

/**
 * 建议胶囊按钮 —— 首页的「写作 / 学习 / 编程 / 生活 / Claude 推荐」。
 *
 * ## 实测数据（Playwright）
 * | 属性 | 值 |
 * |---|---|
 * | 高度 | `h-[32px]` × 0.92 = **29.44dp** |
 * | 圆角 | `rounded-[8px]`（无移动端覆盖）= 8 × 0.92 = **7.36dp** |
 * | 边框 | `1px` 反向补偿 → 视觉 **1dp**，色 `rgba(31,31,30,0.15)` |
 * | 内边距 | `px-[10px]` = 10 × 0.92 = **9.2dp** |
 * | 间距 | `gap-[6px]` = 6 × 0.92 = **5.52dp** |
 * | 背景 | [com.ccm.app.ui.theme.CCMColors.bgMain]（与页面同色） |
 * | 文字色 | `#373734` = [com.ccm.app.ui.theme.CCMColors.textTitle] |
 * | 字重 | **430**（非常规值，用 [androidx.compose.ui.text.font.FontWeight] 近似 400） |
 * | 图标 | `w-[18px] h-5` → 16.55 × 18.39 |
 *
 * > 字重 430 是 Web 用 variable font 的产物；Android 系统字体没有可变字重，
 * > 取 400（Normal）最接近，diff 时注意这一处。
 */
@Composable
fun CcmPillButton(
    label: String,
    iconRes: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val colors = CCMTheme.colors
    Row(
        modifier = modifier
            .height(29.44.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(colors.bgMain)
            .border(
                width = 1.dp,
                color = Color(0x261F1F1E),      // rgba(31,31,30,0.15)
                shape = RoundedCornerShape(7.36.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 9.2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.52.dp),
    ) {
        PainterIcon(iconRes, size = 16.55.dp, tint = Color(0xFF7B7974))
        Text(
            text = label,
            style = CCMText.body14,             // text-[14px] → 实测 11.39dp
            color = colors.textTitle,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 胶囊按钮之间的间距 —— 实测 group gap: 8px × 0.92 = 7.36dp */
val PillSpacing: Dp = 7.36.dp

// ══ 图标按钮 ════════════════════════════════════════════════════════

/**
 * 方形图标按钮 —— 通用的小按钮（对齐 `p-1.5 rounded-md hover:bg-claude-hover`）。
 *
 * @param size 点击区尺寸（含内边距）
 */
@Composable
fun CcmIconButton(
    iconRes: Int,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    iconSize: Dp = 16.dp,
    tint: Color = CCMTheme.colors.textSecondary,
    onClick: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(CCMRadius.md))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PainterIcon(iconRes, size = iconSize, tint = tint)
    }
}

/** 小间距（`gap-1` = 4 × 0.92） */
val Gap1: Dp = 3.68.dp
/** `gap-2` = 8 × 0.92 */
val Gap2: Dp = 7.36.dp
/** `gap-3` = 12 × 0.92 */
val Gap3: Dp = 11.04.dp
/** `gap-4` = 16 × 0.92 */
val Gap4: Dp = 14.72.dp

/** 竖直占位（常用间距） */
@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))
