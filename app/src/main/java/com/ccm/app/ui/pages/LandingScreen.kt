package com.ccm.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ccm.app.R
import com.ccm.app.ui.common.CcmPillButton
import com.ccm.app.ui.common.Gap2
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMRadius
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 首页（Landing）—— 对齐 Web 的 `/` 路由。
 *
 * ## 实测结构（Playwright，393×852）
 * ```
 * ├── 顶栏（44dp，由 AppScaffold 提供）
 * ├── 标题区（居中，衬线族，17.48dp）
 * │     动态文案，见 [greetingFor]
 * ├── 输入卡片（w=343.16 → 315.7dp，圆角 11.57dp）
 * │     ├── 输入行（textarea，16px 浏览器默认字号）
 * │     └── 底部行（h=29.44）
 * │           ├── + 按钮（左）
 * │           └── 模型选择器 + 麦克风（右）
 * ├── 建议胶囊（5 个，**横向可滚动**）
 * │     写作 / 学习 / 编程 / 生活 / Claude 推荐
 * └── 底部状态行
 * ```
 *
 * ## ★ 胶囊行是可滚动的（实测确认）
 * ```
 * 容器 clientWidth = 393，scrollWidth = 571，overflow-x: auto，flex-wrap: nowrap
 * ```
 * 5 个胶囊**排不下**：第 5 个「Claude 推荐」x=374.94，**超出屏幕**（393）。
 * 所以 Compose 侧必须用 `horizontalScroll`，不能假设它们在一行内放得下。
 *
 * ## 实测关键值
 * | 元素 | 实测（屏幕值） |
 * |---|---|
 * | 标题 y | 107.69（含顶栏 44） |
 * | 标题字号 | 19 × 0.92 = **17.48**，行高 23.07 |
 * | 标题色 | `#373734`（亮）/ `#d6cec3`（暗，Web 用 `dark:!text-[#d6cec3]`） |
 * | 卡片 | y=151.39 / w=343.16 / h=104.98 / 圆角 12.576 → **11.57dp** |
 * | 卡片阴影 | `rgba(0,0,0,0.04) 0px 4px 20px 0px` |
 * | 卡片底 | `--bg-claude-input`（亮 #FFFFFF / 暗 #30302E） |
 * | 输入行高 | 50.42（含 pt-4=14.72） |
 * | 底部行 | h=29.44，mt=11.04，gap=7.36 |
 * | 胶囊行 | y=271.09，h=29.44，gap=**8px**（未乘 zoom，因为容器有反向补偿） |
 *
 * @param greeting     标题文案（动态，见 [greetingFor]）
 * @param onSend       发送消息（输入框回车）
 * @param onPickPrompt 点击建议胶囊
 */
@Composable
fun LandingScreen(
    modifier: Modifier = Modifier,
    greeting: String = greetingFor(null),
    onSend: (String) -> Unit = {},
    onPickPrompt: (String) -> Unit = {},
) {
    val colors = CCMTheme.colors

    // ★ 结构对齐 Web 的三层（实测链，见 KDoc）：
    //   scroll 容器  px-2 (8) + pt-[64px]
    //     └ 内容列  max-w-[373px]（= 672 × 0.92 / 1.08696 的反向 zoom 折算）居中
    //         └ 胶囊行  mx-[-8px] + px-[8px]，抵消父级内边距后**满宽可滚**
    //   原来用 `padding(horizontal = 24.92)` 一把梭，导致胶囊行被压在 24.92 边距里、
    //   起点 x=24.92（Web 是 12.05），且可滚区域变窄、第 5 个胶囊提前被裁。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),          // Web: px-2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 373.dp)            // Web: max-w-[672px] 折算后 373
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 标题（实测 y=107.69，顶栏 40.47 → 需再留 67.22）──────────
            Spacer(Modifier.height(67.22.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.36.dp),
            ) {
                // 花朵图标（hero-star.svg，橙色 #D97757）
                PainterIcon(R.drawable.ic_hero_star, size = 22.dp, tint = colors.claudeOrange)
                Text(
                    text = greeting,
                    style = CCMText.titleSerif,
                    color = if (CCMTheme.isDark) Color(0xFFD6CEC3) else Color(0xFF373734),
                    textAlign = TextAlign.Center,
                )
            }

            // 标题底 130.77 → 卡片顶 151.39
            Spacer(Modifier.height(20.62.dp))

            // ── 输入卡片 ─────────────────────────────────────────────
            InputCard(
                onSend = onSend,
                modifier = Modifier.fillMaxWidth(),
            )

            // 卡片底 256.37 → 胶囊顶 271.09
            Spacer(Modifier.height(14.72.dp))

            // ── 建议胶囊（横向可滚动）─────────────────────────────────
            //   mx-[-8px] 抵消外层 px-2，让可滚区域回到满宽（Web 的 scrollWidth=571）
            //
            // ⚠️ 【2026-09-27 修崩溃】原来写的是 `Modifier.padding(horizontal = (-8).dp)`
            //    —— Compose 的 padding() **不接受负值**，运行时会抛
            //    IllegalArgumentException（要求 ≥ 0）。这不是编译期能发现的：
            //    check_kotlin 只查语法，CI 只编译，都要等**真机点开这一屏**才崩。
            //    实测崩溃栈：LandingScreen.kt:143 → PaddingKt.padding-VpY3zN4
            //
            //    CSS 的负 margin 在 Compose 里的等价物是 `offset`（允许负值）。
            //    offset 只影响绘制位置、不改变测量尺寸，正好符合「把可滚区域往左挪
            //    8dp 抵消父容器 padding」的意图。
            PromptPills(
                onPick = onPickPrompt,
                modifier = Modifier.offset(x = (-8).dp),
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 首页问候语 —— 对齐 `MainContent.tsx:1594`。
 *
 * Web 原文：
 * ```js
 * const general = [`有什么我可以帮你？`, `今天需要什么帮助？`, `开始工作吧，${name}`, `随时可以开始，${name}`];
 * ```
 * 另有按时间段的问候（"早安，Jay"）。这里实现时间段分支。
 *
 * @param name 用户名；`null` 时用无名字的通用文案
 */
fun greetingFor(name: String?, hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): String {
    if (name.isNullOrBlank()) {
        return if (hourOfDay in 5..11) "有什么我可以帮你？" else "今天需要什么帮助？"
    }
    return when (hourOfDay) {
        in 5..11 -> "早安，$name"
        in 12..17 -> "下午好，$name"
        in 18..22 -> "晚上好，$name"
        else -> "夜深了，$name"
    }
}

/**
 * 输入卡片 —— 对齐 Web 的 `bg-claude-input border shadow-... flex flex-col`。
 *
 * 实测：w=343.16 / h=104.98 / 圆角 **12.576px**（移动端 `rounded-[20px]` 覆盖了源码的 22）
 * → 屏幕 **11.57dp**。
 */
@Composable
private fun InputCard(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CCMTheme.colors
    // ★ 亮色下 Web 的卡片边框是**透明**的（computed: 1.08696px solid rgba(0,0,0,0)）。
    //   类名里的 `border` 只是占位，hover/focus 时才显色。画成 colors.border 会多出
    //   一圈肉眼可见的灰边 —— 这是「卡片比 Web 脏」的第一来源。
    //   暗色下 Web 覆盖成 #3a3a38，所以只有暗色才真的画。
    val borderColor = if (CCMTheme.isDark) Color(0xFF3A3A38) else Color.Transparent

    Box(
        modifier = modifier
            // ★ 顺序要紧：shadow 必须在 clip **之前**。
            //   Compose 的 modifier 链从左到右绘制，shadow 排在 clip 之后时，
            //   阴影会被圆角裁掉外侧、只在内部留下一圈深色，看着像卡片里套了个灰环
            //   （实测 9dp 宽、#C9C9C9）—— 这是「卡片比 Web 脏」的第二来源。
            //   Web 原值：`0 3.68px 18.4px rgba(0,0,0,0.04)`，4% 极淡，用 1dp 近似。
            .shadow(
                elevation = 1.dp,
                shape = RoundedCornerShape(CCMRadius.r20),
                clip = false,
            )
            .clip(RoundedCornerShape(CCMRadius.r20))     // 11.57dp
            .background(colors.input)
            .border(1.dp, borderColor, RoundedCornerShape(CCMRadius.r20)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // ★ 实测推导（屏幕值）：textarea.x(36.41) − card.x(24.92) = **11.49**
                //   垂直同理：textarea.y(161.06) − card.y(151.39) = **9.67**
                //   底部也自洽：card.bottom(256.37) − row.bottom(246.70) = 9.67 ✓
                //   （原来写 14.72，卡片因此矮了 14.4dp）
                .padding(horizontal = 11.49.dp, vertical = 9.67.dp),
        ) {
            // ── 输入区（min-h 50.42）──────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.42.dp)
                    // textarea 自身 padding-left 6px × 0.92
                    .padding(start = 5.52.dp),
            ) {
                Text(
                    text = "今天需要什么帮助？",
                    style = CCMText.body16.copy(fontSize = CCMText.body16.fontSize),
                    color = colors.textSecondary,
                )
                // TODO(阶段4·B5): 换成真实 TextField（需处理 slash 命令、多行自适应高度）
            }

            // ── 底部行（h=29.44，与输入区间距 5.82）────────────────
            //   ★ 不要再加水平 padding —— 外层 Column 已经有 11.49，
            //     实测 + 按钮 x=36.40 与 textarea x=36.41 左对齐。
            Spacer(Modifier.height(5.82.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(29.44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 左：+ 按钮（上传文件）
                PainterIcon(
                    R.drawable.ic_input_plus,
                    size = 20.dp,
                    tint = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
                )

                // 右：模型选择器 + 麦克风
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gap2),
                ) {
                    ModelChip()
                    PainterIcon(
                        R.drawable.ic_voice_mode,
                        size = 20.dp,
                        tint = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
                    )
                }
            }
        }
    }
}

/**
 * 模型选择器 —— 对齐 Web 的
 * `flex h-[32px] items-center gap-[6px] rounded-[6px] px-[10px] text-[14px]`。
 *
 * 实测：w=195.11 / h=29.44 / 圆角 6px（无移动端覆盖 → 5.52dp）
 */
@Composable
private fun ModelChip() {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .height(29.44.dp)
            .clip(RoundedCornerShape(CCMRadius.md))
            .clickable { /* TODO(B4): 打开模型选择器 */ }
            .padding(horizontal = 9.2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.52.dp),
    ) {
        Text(
            text = "Sonnet 4.6",
            style = CCMText.body14,
            color = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
            maxLines = 1,
        )
        // 下拉箭头
        PainterIcon(
            R.drawable.ic_model_caret,
            size = 12.dp,
            tint = colors.textSecondary,
        )
    }
}

/**
 * 建议胶囊行 —— 5 个标签横向排列。
 *
 * ## ★ 横向可滚动（实测）
 * 容器 `clientWidth=393 / scrollWidth=571 / overflow-x: auto / nowrap`。
 * 5 个胶囊放不下，第 5 个 x=374.94 超出屏幕。
 *
 * 实测各胶囊宽度（屏幕值）：写作 76 / 学习 77 / 编程 75.14 / 生活 97.95 / Claude 推荐 136.56
 * 间距 gap **8px**（容器有反向 zoom，未乘 0.92）。
 *
 * > Web 隐藏了滚动条（`.landing-prompt-tabs` 在 index.css 里有 `display:none` 规则）。
 */
@Composable
private fun PromptPills(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Web: `h-[32px] px-[8px]`（右侧另有 pr-[28px] 是滚动渐隐留白，
            //  在 Compose 里由 horizontalScroll 自身处理，不重复加）
            .height(29.44.dp)
            .padding(start = 7.36.dp, end = 25.76.dp)
            .horizontalScroll(rememberScrollState()),
        // Web: gap-[8px] → 8 × 0.92 = 7.36
        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PromptSection.entries.forEach { sec ->
            CcmPillButton(
                label = sec.label,
                iconRes = sec.iconRes,
                onClick = { onPick(sec.label) },
            )
        }
    }
}

/**
 * 5 个建议分组 —— 数据来自 `MainContent.tsx:99-160` 的 `LANDING_PROMPT_SECTIONS`。
 *
 * [width] 是 Web 里写死的测量宽度（点击后展开的建议列表宽度要与之匹配）。
 * 本机 393 视口下 5 个胶囊**刚好排满一行**（Web 的容器宽 361.55）。
 */
enum class PromptSection(
    val label: String,
    val iconRes: Int,
    val width: Float,
) {
    WRITE("写作", R.drawable.ic_prompt_write, 82.609f),
    LEARN("学习", R.drawable.ic_prompt_learn, 83.703f),
    CODE("编程", R.drawable.ic_prompt_code, 81.688f),
    LIFE("生活", R.drawable.ic_prompt_life, 106.484f),
    CHOICE("Claude 推荐", R.drawable.ic_prompt_choice, 148.453f),
}
