package com.ccm.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 设置页专用控件集。
 *
 * 这些控件的共同点：**只在设置页出现**，且形态由移动端 CSS 覆盖规则决定，
 * 不能直接套用通用 [com.ccm.app.ui.common] 里的组件。
 *
 * 数据源：Playwright 实测 `.settings-body` 子树（见 MEASURED.md §1 换算规则）。
 * 每个控件都标注了对应的 Tailwind 类与实测屏幕值。
 *
 * ## 换算提醒
 * 实测 computed 值 **不含 zoom**，屏幕值 = 实测值 × 0.92。
 * 下面注释里给的"实测"都是已经乘过 0.92 的**屏幕值**，可直接写进 Compose。
 */

// ── 移动端实测常量（避免散落魔法数字）──────────────────────────

/** `.settings-body` 左右内距：实测 14.148 / 13.362 → 屏幕 13.02 / 12.29 */
val SettingsBodyPaddingH: Dp = 12.29.dp

/** `.settings-body` 上内距：实测 14.148 → 屏幕 13.02 */
val SettingsBodyPaddingTop: Dp = 13.02.dp

/** `.settings-body` 下内距：clamp(56,18vw,90) → 实测 70.74 → 屏幕 65.08 */
val SettingsBodyPaddingBottom: Dp = 65.08.dp

/**
 * section 之间的 `space-y-10`。
 *
 * ⚠️ **不是**桌面值 40×0.92=36.8 —— 移动端 CSS 有一条
 * `@media(max-width:767px) { .space-y-10 > * + * { margin-top: clamp(14px,5vw,26px) } }`
 * （index.css:1213）。393px 视口下 5vw = 19.65 → 屏幕 **18.08**。
 * 2026-10-01 用 Playwright 实测 `sy10Gap: 18.08` 确认（原先误用 36.8，是两倍）。
 */
val SettingsSectionGap: Dp = 18.08.dp

/** `<h3 text-[16px]>` 的 `mb-5`：实测 20 → 屏幕 18.4（个人资料/发送消息/外观三节） */
val SettingsTitleGap: Dp = 18.4.dp

/**
 * 「关于」节的 `<h3>` 用的是 `mb-3`（不是 `mb-5`）。
 * 实测 marginBottom: 12 → 屏幕 **11.04**。
 */
val SettingsTitleGapTight: Dp = 11.04.dp

/** 表单行之间的 `space-y-6`：实测 24 → 屏幕 22.08 */
val SettingsFormGap: Dp = 22.08.dp

/**
 * `<hr className="border-claude-border" />` 上下留白。
 *
 * hr 本身没有 margin，两侧留白全部来自父容器的 `space-y-10` ——
 * 移动端被覆盖成 `clamp(14px,5vw,26px)`，393px 下 = 19.65 → 屏幕 **18.08**。
 * 与 [SettingsSectionGap] 同源同值（实测 hr 的 marginTop = 19.65px）。
 */
val SettingsHrGap: Dp = 18.08.dp

/** 输入框高度：实测 38.8（含 1px 边框） */
val SettingsInputHeight: Dp = 38.8.dp

/** 输入框圆角 `rounded-md` = 6 → 屏幕 5.52 */
val SettingsInputRadius: Dp = 5.52.dp

/** 输入框边框宽：实测 1.08696 → 屏幕 1.0 */
val SettingsBorderWidth: Dp = 1.dp

/** 输入框左右内距 `px-3` = 12 → 屏幕 11.04 */
val SettingsInputPaddingH: Dp = 11.04.dp

/** 输入框上下内距 `py-2` = 8 → 屏幕 7.36 */
val SettingsInputPaddingV: Dp = 7.36.dp

/** 标签与控件之间 `mb-1.5` = 6 → 屏幕 5.52 */
val SettingsLabelGap: Dp = 5.52.dp

/** 标签字号 `text-[13px]` 移动端实测 12.183 → 屏幕 11.21 */
val SettingsLabelSize = CCMText.body13

/**
 * 输入框正文字号。
 *
 * 浏览器对 input/textarea/select 强制 16px 下限，移动端 CSS 的
 * `text-[14px]` 覆盖规则对它们不生效 → 实测仍是 16px → 屏幕 **14.72**。
 */
val SettingsInputTextSize = CCMText.body16.copy(fontSize = 14.72.sp)

// 以下常量统一用 `sp` 直接写屏幕值（CCMTheme 已把 fontScale 锁死为 1.0，
// 1.sp ≡ 1.dp，所以这里的数字可以直接和实测值对照）。

/** `<h3>` 移动端实测 13.362 → 屏幕 12.29 */
val SettingsTitleSize = 12.29.sp

/** `<h3>` 行高：实测 20.043 → 屏幕 18.44 */
val SettingsTitleLine = 18.44.sp

/** `<label>` 移动端实测 12.183 → 屏幕 11.21 */
val SettingsLabelSizeSp = 11.21.sp

/** `<label>` 行高：实测 18.2745 → 屏幕 16.81 */
val SettingsLabelLine = 16.81.sp

/**
 * `text-[14px]` 移动端被覆盖成 `clamp(11.5px, 3.15vw, 14px)`，
 * 393px 视口下 3.15vw = 12.3795 → 屏幕 **11.39**（用于「关于」行）。
 *
 * 2026-10-01 实测「当前版本」两行 span 的 fontSize 均为 12.3795 确认。
 * 原先写 11.96 是照搬了错误的中间值。
 */
val SettingsBody14Sp = 11.39.sp

/** `<select>` 移动端实测 12.3795 → 屏幕 11.39 */
val SettingsSelectTextSp = 11.39.sp

// ── 控件 ─────────────────────────────────────────────────────

/**
 * 设置页文本输入框 —— 对应源码
 * `<input className="w-full px-3 py-2 bg-claude-input border border-claude-border
 *  rounded-md text-[14px] text-claude-text focus:border-[#387ee0]">`
 *
 * 移动端实测：h 38.8 · radius 6 · bg #FFFFFF · border 1px #E8E7E3 · padding 8px 12px。
 * ⚠️ 字号实测是 **16px**（不是类名写的 14px）—— 浏览器对输入控件有 16px 下限，
 * 移动端 CSS 的 `text-[14px]` 覆盖规则对 `input` 不生效。写成 14.72 才对得上。
 */
@Composable
fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minHeight: Dp = SettingsInputHeight,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * 只读（2026-10-06 加）。
     *
     * 【为什么需要】密钥框在「掩码态」时 onValueChange 会**静默丢弃**输入
     * （防止把展示用的省略号写进配置）—— 但输入框仍可聚焦、光标闪烁、
     * 键盘能弹，用户打字毫无反应，以为 App 卡死。
     * readOnly 让输入框明确不可编辑（不聚焦、不出光标），配合 trailing
     * 的眼睛图标，用户自然知道要先点眼睛。
     */
    readOnly: Boolean = false,
) {
    val colors = CCMTheme.colors
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(minHeight)
            .clip(RoundedCornerShape(SettingsInputRadius))
            .background(colors.input)
            .border(SettingsBorderWidth, colors.border, RoundedCornerShape(SettingsInputRadius))
            .padding(horizontal = SettingsInputPaddingH),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            readOnly = readOnly,
            textStyle = SettingsInputTextSize.copy(color = colors.textMain),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                // 【2026-10-07】有 trailing 按钮时右侧留 56dp —— 原来输入
                // 长文本（如完整 PAT）会溢出到 trailing 底下，文字重叠
                //（「…SRWsX」压着「隐藏」）。
                .padding(end = if (trailing != null) 56.dp else 0.dp)
                .padding(vertical = SettingsInputPaddingV),
            decorationBox = { inner ->
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = SettingsInputTextSize,
                        color = colors.textSecondary,
                    )
                }
                inner()
            },
        )
        if (trailing != null) {
            Box(modifier = Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/**
 * 设置页下拉框（只读展示形态）—— 对应
 * `<select className="w-full px-3 py-2.5 ... appearance-none">` + 右侧 chevron。
 *
 * 移动端实测：h 37.5 · radius 6 · padding 10px 12px · 字号 12.3795（→ 11.39）。
 * ⚠️ select 的 padding 是 `py-2.5`（10px），比 input 的 `py-2` 高一点，
 * 但浏览器又给 select 应用了自己的行高，最终高度反而略矮（37.5 vs 38.8）。
 */
@Composable
fun SettingsSelect(
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    chevronRotated: Boolean = false,
) {
    val colors = CCMTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(37.5.dp)
            .clip(RoundedCornerShape(SettingsInputRadius))
            .background(colors.input)
            .border(SettingsBorderWidth, colors.border, RoundedCornerShape(SettingsInputRadius))
            .clickable(onClick = onClick)
            .padding(horizontal = SettingsInputPaddingH),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            style = CCMText.body13.copy(fontSize = SettingsSelectTextSp),
            color = colors.textMain,
            modifier = Modifier.weight(1f),
        )
        ChevronDown(
            color = colors.textSecondary,
            rotated = chevronRotated,
        )
    }
}

/**
 * 可弹出的下拉选择 —— [SettingsSelect] + 选项弹窗。
 *
 * ## 为什么要有这个
 * 设置页有 4 个下拉（工作职能 / 发送键 / 换行键 / 思考强度），
 * **全部是 `onClick = {}` 空转** —— 点了毫无反应（2026-09-27 审计）。
 * SettingsSelect 本身只有展示，菜单得使用方自己弹；之前没人写。
 *
 * 选项用 AlertDialog 全屏列表（不是锚定 Popup）：设置页是全屏层，
 * 锚定菜单在小屏上容易被截断，全屏列表最稳。
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
fun SettingsSelectMenu(
    value: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "",
    chevronRotated: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }

    SettingsSelect(
        value = value,
        onClick = { open = true },
        modifier = modifier,
        chevronRotated = chevronRotated,
    )

    if (open) {
        // ★ 2026-09-29：AlertDialog → **底部滑出面板**（用户反馈「选择类的
        //   面板被简化成弹窗」）。这里是**全部下拉**（工作职能/发送键/换行/
        //   思考强度/风格…）的共用组件，改这一处全站生效。
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { open = false },
            containerColor = CCMTheme.colors.bgMain,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                if (title.isNotBlank()) {
                    Text(
                        text = title,
                        style = CCMText.body14,
                        color = CCMTheme.colors.textMain,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
                options.forEach { opt ->
                    val selected = opt == value
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(opt)
                                open = false
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = opt,
                            style = CCMText.body13,
                            color = if (selected) CCMTheme.colors.accent
                            else CCMTheme.colors.textMain,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) Text("✓", style = CCMText.body13, color = CCMTheme.colors.accent)
                    }
                }
            }
        }
    }
}

/**
 * 下拉箭头 —— 对应源码里的内联 SVG：
 * `<svg width="10" height="6"><path d="M1 1L5 5L9 1" stroke="currentColor" strokeWidth="1.5"/></svg>`
 *
 * 实测尺寸 14.7×14.7（含 stroke 溢出），可视箭头本体 10×6 → 屏幕 9.2×5.52。
 * 职业选择那个用了 `rotate-90`（ChevronRight 旋转），所以有 [rotated] 开关。
 */
@Composable
fun ChevronDown(
    color: Color,
    modifier: Modifier = Modifier,
    rotated: Boolean = false,
    size: Dp = 13.52.dp,
) {
    androidx.compose.foundation.Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = 1.38.dp.toPx()
        // 箭头本体 10×6 居中：左右各留 1.76，上下各留 3.76
        val left = (w - 9.2.dp.toPx()) / 2f
        val right = left + 9.2.dp.toPx()
        val top = (h - 5.52.dp.toPx()) / 2f
        val bottom = top + 5.52.dp.toPx()
        val cx = w / 2f
        val cy = h / 2f
        val path = androidx.compose.ui.graphics.Path().apply {
            if (rotated) {
                // rotate-90：把「向下」转成「向右」
                moveTo(cx - 2.76.dp.toPx(), cy - 4.6.dp.toPx())
                lineTo(cx + 2.76.dp.toPx(), cy)
                lineTo(cx - 2.76.dp.toPx(), cy + 4.6.dp.toPx())
            } else {
                moveTo(left, top)
                lineTo(cx, bottom)
                lineTo(right, top)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/**
 * 开关 —— 对应源码
 * `<button className="w-10 h-6 rounded-full ${on ? 'bg-blue-600' : 'bg-[#E5E5E5]'}">
 *    <div className="absolute top-1 w-4 h-4 rounded-full bg-white ${on ? 'left-5' : 'left-1'}"/>
 *  </button>`
 *
 * 实测尺寸：`w-10 h-6` = 40×24 → 屏幕 **36.8 × 22.08**（源码未提供实测，
 * 这里按 Tailwind 类名 × 0.92 换算；track 圆角 full，knob 16×16 → 14.72）。
 * 滑块位移：`left-1`(4) → `left-5`(20)，差 16 → 屏幕 14.72。
 */
@Composable
fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackW = 36.8.dp
    val trackH = 22.08.dp
    val knob = 14.72.dp
    val inset = 3.68.dp
    Box(
        modifier = modifier
            .width(trackW)
            .height(trackH)
            .clip(CircleShape)
            .background(if (checked) Color(0xFF2563EB) else Color(0xFFE5E5E5))
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = if (checked) inset + 14.72.dp else inset)
                .size(knob)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

/**
 * 设置小节 —— 对应 `<section><h3 text-[16px] font-semibold mb-5>标题</h3>内容</section>`
 *
 * ⚠️ `text-[16px]` 在移动端被覆盖成 `clamp(12.5px, 3.4vw, 15.5px)` →
 * 实测 13.362 → 屏幕 **12.29**。所以标题**不比正文大**，这是移动端特有意象，
 * 不要"顺手"改回 16。
 *
 * @param titleGap 标题与内容的间距。默认 [SettingsTitleGap]（`mb-5`）；
 *   「关于」节源码用 `mb-3`，传 [SettingsTitleGapTight]。
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    titleGap: Dp = SettingsTitleGap,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = CCMTheme.colors
    Column(modifier = modifier) {
        Text(
            text = title,
            style = CCMText.body16.copy(
                fontSize = SettingsTitleSize,
                lineHeight = SettingsTitleLine,
                fontWeight = FontWeight.SemiBold,
            ),
            color = colors.textMain,
        )
        Spacer(Modifier.height(titleGap))
        content()
    }
}

/**
 * 标签 —— 对应 `<label block text-[13px] font-medium text-claude-textSecondary mb-1.5>`。
 * 移动端实测 12.183 → 屏幕 **11.21**。
 */
@Composable
fun SettingsLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CCMTheme.colors.textSecondary,
) {
    Text(
        text = text,
        style = CCMText.body13.copy(
            fontSize = SettingsLabelSizeSp,
            lineHeight = SettingsLabelLine,
            fontWeight = FontWeight.Medium,
        ),
        color = color,
        modifier = modifier,
    )
}

/** 标签 + 控件的竖直组合（`label` 的 `mb-1.5`） */
@Composable
fun SettingsField(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        SettingsLabel(label)
        Spacer(Modifier.height(SettingsLabelGap))
        content()
    }
}

/**
 * 设置页的水平分隔线 —— 对应 `<hr className="border-claude-border" />`。
 *
 * 桌面是 1px 实线；移动端没改它，实测 h=1、颜色 `#E8E7E3`。
 * 两侧留白由父容器的 `space-y-10` 提供（移动端 = 18.08，见 [SettingsHrGap]）。
 */
@Composable
fun SettingsDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CCMTheme.colors.border),
    )
}

/**
 * 「关于」行的键值对 —— 对应
 * `<div className="flex items-center justify-between py-2">
 *    <span text-[14px] text-claude-textSecondary>key</span>
 *    <span text-[14px] font-mono text-claude-text>value</span>
 *  </div>`
 *
 * ⚠️ 移动端 CSS 把 `.settings-body .flex.items-center.justify-between` 改成
 * `flex-direction: column`（纵向堆叠），**除非**它只有一个 button 或含 checkbox 子元素。
 * 「当前版本」这行两个 span，所以**是纵向的**。
 *
 * 实测（2026-10-01，393×852）：整行 h=56.09 · `padding: 7.36px 0` ·
 * `gap: 7.23px`（`.gap-*` 被 clamp 成 `clamp(5px,2vw,10px)`=7.86 → ×0.92）。
 */
@Composable
fun SettingsInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueMono: Boolean = true,
    gap: Dp = 7.23.dp,
) {
    val colors = CCMTheme.colors
    Column(modifier = modifier.padding(vertical = 7.36.dp)) {
        Text(
            text = label,
            style = CCMText.body14.copy(fontSize = SettingsBody14Sp),
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(gap))
        Text(
            text = value,
            style = CCMText.body14.copy(
                fontSize = SettingsBody14Sp,
                fontFamily = if (valueMono) com.ccm.app.ui.theme.CcmMono else com.ccm.app.ui.theme.CcmSans,
            ),
            color = colors.textMain,
        )
    }
}
