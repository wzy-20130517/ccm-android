package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.CcmMono

/**
 * Slash 命令 / Skill 建议面板 —— 对齐 `SlashCommandMenu.tsx`（129 行）。
 *
 * ## 源码结构
 * ```
 * 容器：fixed + z-9999 + overflow-y-auto + rounded-xl + border + shadow
 *       maxHeight = min(300, max(120, 上方或下方空间))
 * ├── 头部：「Slash 命令 / Skills」（px-3 py-2 text-[11px] 底边框）
 * ├── 列表：每项 `flex items-center gap-3 px-3 py-2.5`
 * │     ├── 名字 `/name`（min-w-88 · font-mono · text-[13px]）
 * │     │     skill → #8B5CF6（紫）；命令 → #4B9EFA（蓝）
 * │     ├── 描述（flex-1 truncate · text-[12px] · textSecondary）
 * │     └── passive 标记（text-[10px] amber-600）
 * └── 底部提示（sticky bottom-0，仅 >7 项或 hiddenSkills>0 时）
 *       「共 N 项 · 上下键选择，或继续输入筛选」
 * ```
 *
 * ## ★ 排序规则（源码 useMemo，别改）
 * 命令在前、skill 在后 —— **不是**按字母序，也不是按输入顺序。
 * 源码：`[...commands, ...skills]`，即两个分组各自保持原序再拼接。
 *
 * ## ★ 定位逻辑（源码 2026-09-20 修复的坑，务必保留语义）
 * 原实现用 `absolute bottom-full`（相对输入框往上展开）→ 新对话页输入框
 * 在屏幕中间偏上时，面板整个跑到视口外。
 * 现改为**按实测空间决定方向**：
 * - 上方空间 < 160 → 优先往下；但下方 < 120 时仍往上
 * - maxHeight 取上下空间较大者，clamp 到 [120, 300]
 *
 * Compose 侧用 [placement] 参数显式表达该决策结果（父组件量完尺寸后传入），
 * 因为 Compose 拿不到「输入框相对屏幕的位置」这种信息 —— 它由父布局决定。
 *
 * ## 实测尺寸（Tailwind × 0.92）
 * | 元素 | 类 | 屏幕值 |
 * |---|---|---|
 * | 圆角 | `rounded-xl` | **11.04** |
 * | 头部内距 | `px-3 py-2` | 11.04 × 7.36 |
 * | 头部字号 | `text-[11px]` | **10.12** |
 * | 项内距 | `px-3 py-2.5` | 11.04 × 9.20 |
 * | 项间距 | `gap-3` | 11.04 |
 * | 命令名 | `text-[13px]` | **11.96** |
 * | 命令名最小宽 | `min-w-[88px]` | **80.96** |
 * | 描述 | `text-[12px]` | **11.04** |
 * | passive | `text-[10px]` | **9.20** |
 * | 面板最大高 | `min(300, …)` | 300（**不乘 0.92**，fixed 层） |
 *
 * @param suggestions 全部候选项（已按父组件的过滤结果）
 * @param activeIndex 当前高亮项下标（-1 = 无）
 * @param placement   展开方向：`true` = 往输入框下方展开
 * @param hiddenSkills 未输关键词时被折叠的 skill 数（>0 时底部提示会额外说明）
 */
@Composable
fun SlashCommandMenu(
    suggestions: List<SlashSuggestion>,
    modifier: Modifier = Modifier,
    activeIndex: Int = -1,
    placement: Boolean = false,
    hiddenSkills: Int = 0,
    maxHeight: Dp = 300.dp,
    onSelect: (SlashSuggestion) -> Unit = {},
    onHover: (Int) -> Unit = {},
) {
    val colors = CCMTheme.colors

    // ★ 排序：命令在前、skill 在后（源码 useMemo 语义）
    val visible = suggestions.filter { it.kind != SlashSuggestionKind.SKILL } +
        suggestions.filter { it.kind == SlashSuggestionKind.SKILL }

    if (visible.isEmpty()) return

    val scroll = rememberScrollState()
    // 高亮项滚入视野 —— 对应源码 `el?.scrollIntoView({ block: 'nearest' })`。
    // 47 个命令塞进 300px 面板，上下键选到第 8 项以后必须自动滚动，
    // 否则高亮跑到可视区外，用户不知道选到哪了。
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0) {
            // 每项高度 ≈ 项内距 9.20×2 + 行高 ≈ 36.8；头部 25.76 + 底栏 21.16
            val itemH = 36.8f
            val target = (activeIndex * itemH).toInt()
            scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
        }
    }

    Column(
        modifier = modifier
            .heightIn(max = maxHeight)
            // ★ shadow 必须在 clip **之前** —— 排在后面时会被圆角裁掉外侧，
            //   内部留一圈深色灰环（同 LandingScreen / InputBar 的坑）。
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(11.04.dp),
                ambientColor = Color(0x24000000),
                spotColor = Color(0x24000000),
                clip = false,
            )
            .clip(RoundedCornerShape(11.04.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(11.04.dp))
            .verticalScroll(scroll),
    ) {
        // ── 头部 ──────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.input)
                .padding(horizontal = 11.04.dp, vertical = 7.36.dp),
        ) {
            Text(
                text = "Slash 命令 / Skills",
                style = CCMText.body11.copy(fontSize = 10.12.sp),
                color = colors.textSecondary,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border.copy(alpha = 0.6f)),
        )

        // ── 列表 ──────────────────────────────────────────────
        visible.forEachIndexed { index, item ->
            val isSkill = item.kind == SlashSuggestionKind.SKILL
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (index == activeIndex) colors.hover else Color.Transparent,
                    )
                    .clickable { onSelect(item) }
                    .padding(horizontal = 11.04.dp, vertical = 9.20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.04.dp),
            ) {
                // 名字：min-w-88，不换行（长名如 /compact-threshold 不被截断）
                Text(
                    text = "/${item.name}",
                    style = CCMText.body13.copy(
                        fontSize = 11.96.sp,
                        fontFamily = CcmMono,
                    ),
                    color = if (isSkill) SlashSkillPurple else SlashCommandBlue,
                    maxLines = 1,
                    modifier = Modifier.width(80.96.dp),
                )
                // 描述：flex-1 + truncate
                Text(
                    text = item.description
                        ?: if (isSkill) "Skill" else "命令",
                    style = CCMText.body12.copy(fontSize = 11.04.sp),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.passive) {
                    Text(
                        text = "passive",
                        style = CCMText.body11.copy(fontSize = 9.20.sp),
                        color = Color(0xFFD97706),      // amber-600
                        maxLines = 1,
                    )
                }
            }
        }

        // ── 底部提示（仅 >7 项 或 有折叠 skill 时）──────────────
        if (visible.size > 7 || hiddenSkills > 0) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.border.copy(alpha = 0.6f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.input)
                    .padding(horizontal = 11.04.dp, vertical = 5.52.dp),
            ) {
                Text(
                    text = buildString {
                        append("共 ${visible.size} 项 · 上下键选择")
                        if (hiddenSkills > 0) {
                            append(" · 另有 $hiddenSkills 个 skill，输入关键词可搜")
                        } else {
                            append("，或继续输入筛选")
                        }
                    },
                    style = CCMText.body11.copy(fontSize = 9.20.sp),
                    color = colors.textSecondary.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/** 候选项类别 —— 对应源码 `kind?: 'command' | 'skill'` */
enum class SlashSuggestionKind { COMMAND, SKILL }

/**
 * Slash 候选项 —— 对应源码 `interface SlashSuggestion`。
 *
 * @param passive 被动型（不会主动触发，需显式调用），面板里显示 amber 色标记
 */
data class SlashSuggestion(
    val name: String,
    val description: String? = null,
    val kind: SlashSuggestionKind = SlashSuggestionKind.COMMAND,
    val passive: Boolean = false,
)

/** skill 名字颜色 `#8B5CF6`（Tailwind violet-500） */
private val SlashSkillPurple = Color(0xFF8B5CF6)

/** 命令名字颜色 `#4B9EFA` */
private val SlashCommandBlue = Color(0xFF4B9EFA)
