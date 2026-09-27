package com.ccm.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 模态弹窗框架 —— 对齐 Web 的通用模态结构。
 *
 * ## 实测数据（Playwright，注入探针测量）
 *
 * ### 遮罩
 * | 属性 | 实测值 | 说明 |
 * |---|---|---|
 * | 尺寸 | **393 × 852** | `fixed inset-0`，**满屏且不乘 zoom** |
 * | 背景 | `rgba(0,0,0,0.6)` | = `bg-black/60` = [com.ccm.app.ui.theme.CCMColors.scrim60] |
 * | z-index | 9999 | |
 * | 对齐 | `flex items-center justify-center` | 居中 |
 *
 * ### 卡片
 * | 属性 | 实测值 | 说明 |
 * |---|---|---|
 * | 宽 | **361**（= 393 − 16×2） | `max-w-md w-full mx-4` |
 * | 圆角 | **11.79px** | `rounded-2xl`，**此处未被移动端 clamp 覆盖** |
 * | 内边距 | **28** | `p-7`（未乘 zoom！遮罩层无 zoom） |
 * | 背景 | [com.ccm.app.ui.theme.CCMColors.bgMain] | `bg-claude-bg` |
 * | 边框 | `1px` [com.ccm.app.ui.theme.CCMColors.border] | |
 * | 阴影 | `rgba(0,0,0,0.25) 0 25px 50px -12px` | `shadow-2xl` |
 *
 * > ★ **注意**：模态在 `fixed` 层，**不受 `#root` 的 0.92 zoom 影响**，
 * > 所以这里的值都是**原始 CSS px**，不要再乘 0.92。
 *
 * ### 内容（实测）
 * | 元素 | 字号 | 行高 | 字重 | 颜色 |
 * |---|---|---|---|---|
 * | `h2` 标题 | 17 | 25.5 | **600** | textMain |
 * | `p` 说明 | 12.183 | 18.2745 | 400 | textSecondary（`mt-1`=4px） |
 * | 主按钮 | 12.3795 | 18.5693 | **500** | bg=textMain / fg=bgMain |
 *
 * ## backdrop-filter 的降级（B0 决策）
 * 11 处模态用了 `backdrop-blur-sm`（4px）。Compose 的 `Modifier.blur()` 只能模糊
 * **自身内容**、不能模糊背后内容；真 backdrop 需要 API 31+ 的 RenderEffect 分层。
 * 当前**降级为半透明纯色**（`bg-black/60`），因为遮罩本身已把底层压暗 60%，
 * blur 的视觉贡献 <0.1%。diff 实测后如需攻坚再上 RenderEffect。
 *
 * @param onDismiss 点击遮罩关闭（传 `null` = 不可点击遮罩关闭，如强制模态）
 * @param cardWidth 卡片宽度（默认按实测 361dp；`max-w-md` 上限 448）
 */
@Composable
fun CcmModal(
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 361.dp,
    cardPadding: Dp = 28.dp,          // p-7
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CCMTheme.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.scrim60)      // bg-black/60
            .then(
                if (onDismiss != null) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 卡片：点它不关闭（阻止冒泡）
        Box(
            modifier = Modifier
                .padding(horizontal = 16.dp)     // mx-4
                .widthIn(max = cardWidth)
                .shadow(elevation = 25.dp, shape = RoundedCornerShape(11.79.dp))
                .clip(RoundedCornerShape(11.79.dp))     // rounded-2xl（实测 11.79）
                .background(colors.bgMain)
                .border(1.dp, colors.border, RoundedCornerShape(11.79.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { /* 吞掉点击，不关闭 */ },
                )
                .padding(cardPadding),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                content()
            }
        }
    }
}

/**
 * 模态标题（`h2`）—— 实测 17 / 25.5 / 600。
 */
@Composable
fun CcmModalTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = CCMText.body16.copy(
            fontSize = 17.sp,
            lineHeight = 25.5.sp,
            fontWeight = FontWeight.SemiBold,
        ),
        color = CCMTheme.colors.textMain,
    )
}

/**
 * 模态说明文字 —— 实测 12.183 / 18.2745 / textSecondary。
 */
@Composable
fun CcmModalDescription(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = CCMText.body12.copy(fontSize = 12.183.sp, lineHeight = 18.2745.sp),
        color = CCMTheme.colors.textSecondary,
    )
}

/**
 * 模态主按钮 —— 实测 h=38.56 / 圆角 8 / padding 10×16 / 字重 500。
 *
 * Web: `w-full px-4 py-2.5 rounded-lg bg-claude-text text-claude-bg text-[14px] font-medium`
 * → 深底浅字（亮色：黑底白字；暗色：白底黑字，因为 textMain/bgMain 明暗反转）。
 */
@Composable
fun CcmPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = CCMTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) colors.textMain else colors.textMain.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = CCMText.body14.copy(
                fontSize = 12.3795.sp,
                lineHeight = 18.5693.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = colors.bgMain,
        )
    }
}

/**
 * 模态次按钮 —— Web: `border border-claude-border text-claude-text hover:bg-claude-hover`。
 */
@Composable
fun CcmSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = CCMTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(38.56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Transparent)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = CCMText.body14.copy(
                fontSize = 12.3795.sp,
                lineHeight = 18.5693.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = if (enabled) colors.textMain else colors.textSecondary,
        )
    }
}

/**
 * 模态里的提示条 —— Web: `rounded-lg bg-claude-hover/50 p-3.5`。
 *
 * @param tone 语气：中性（默认）/ 警告 / 错误
 */
@Composable
fun CcmModalNotice(
    text: String,
    modifier: Modifier = Modifier,
    tone: NoticeTone = NoticeTone.NEUTRAL,
) {
    val colors = CCMTheme.colors
    val (bg, fg, borderColor) = when (tone) {
        NoticeTone.NEUTRAL -> Triple(colors.hover.copy(alpha = 0.5f), colors.textMain, null)
        NoticeTone.WARNING -> Triple(Color(0x1AF59E0B), Color(0xFFB45309), Color(0x33F59E0B))
        NoticeTone.ERROR -> Triple(Color(0x14DC2626), Color(0xFFB91C1C), Color(0x33DC2626))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.36.dp))       // rounded-lg
            .background(bg)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, RoundedCornerShape(7.36.dp))
                else Modifier,
            )
            .padding(12.88.dp),                      // p-3.5
    ) {
        Text(
            text = text,
            style = CCMText.body12.copy(fontSize = 12.5.sp, lineHeight = 18.4.sp),
            color = fg,
        )
    }
}

/** 提示条语气 */
enum class NoticeTone { NEUTRAL, WARNING, ERROR }
