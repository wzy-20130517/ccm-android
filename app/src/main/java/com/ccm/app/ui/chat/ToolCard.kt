package com.ccm.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 工具调用聚合折叠组 —— 对齐 Web `MainContent.tsx:1186-1300`。
 *
 * ## ★ 为什么是「组」而不是「一堆卡」
 * Web 侧一条助手消息里的**所有工具调用聚合成一个折叠组**，不是单卡平铺：
 * ```
 * ▸ 组头（可点折叠）：[状态图标] Run command, Read file  [⌄]
 *   展开后：左侧 2px 竖线（border-l-2 border-claude-border）
 *     ├─ 工具卡 1：`>_` npm test                    [⌄]
 *     ├─ 工具卡 2：▤ Read a.mjs                     [⌄]
 *     └─ ✓ Done
 * ```
 * 单卡平铺会让「一轮调了 8 个工具」变成 8 个独立方块，看不出它们属于同一次思考 ——
 * 这是与 Web 最大的**结构差异**（不是数值差异）。
 *
 * ## 逐项对照（源码 → 本实现）
 * | 项 | Web | 本实现 |
 * |---|---|---|
 * | 组容器 | `rounded-lg overflow-hidden` + `!allDone` 时 `bg-black/[0.04] dark:bg-white/[0.04]` | 同（完成即透明） |
 * | 组头内距 | `px-2 py-1.5` | 7.36 / 5.52 dp |
 * | 组头图标 | 运行 `FileText 16 + animate-pulse` · 完成 `Check 16` · 出错红 `✗` | 同 |
 * | 组头文字 | `text-[14px]`，运行中 `animate-shimmer-text` | 11.39sp + ToolHeaderShimmer |
 * | 组头 chevron | `ChevronDown 14`，展开 `rotate-180`（200ms） | 12.88dp + 200ms 动画 |
 * | 展开列 | `mt-2 ml-1 pl-4 border-l-2 border-claude-border space-y-2` | 7.36 / 3.68 / 14.72 / 1.84dp 竖线 / 7.36 间距 |
 * | 组尾 | `allDone && !streaming` → `Check 14 + Done text-[13px]` | 同 |
 *
 * ## 折叠状态记忆（源码）
 * ```js
 * 展开 = msg.isToolCallsExpanded ?? (isCurrentlyStreaming || !allDone)
 * ```
 * 即：**用户没点过**时跟着流式状态走（跑完自动收起），**点过之后**就固定 ——
 * 用 `mutableStateOf<Boolean?>(null)` 表达「未点过」最贴切。
 *
 * @param cards       本轮的工具体（顺序即调用顺序）
 * @param isStreaming 对应 Web `isCurrentlyStreaming`：本轮是否仍在流式生成
 * @param isStale     对应 Web `isStale`：整轮已结束但工具仍标 running → 视为 canceled。
 *                    默认 `!isStreaming`（本轮不跑了，还标 running 的就是停在那儿的）
 */
@Composable
fun ToolCallGroup(
    cards: List<ChatToolCard>,
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    isStale: Boolean = !isStreaming,
) {
    // 隐藏工具（Web `HIDDEN_TOOL_NAMES`）不进组、不计入 summary
    val visible = remember(cards) { cards.filter { it.name !in HiddenToolNames } }
    if (visible.isEmpty()) return

    val colors = CCMTheme.colors

    // realStatus：running 且整轮已停 → canceled（不再显示 Running 动效）
    val allDone = visible.all { !it.running || isStale }
    val hasError = visible.any { it.isError }
    val summary = remember(visible) {
        visible.map { toolDisplayName(it.name) }.distinct().joinToString(", ")
    }

    // 折叠状态记忆：用户点过就固定，否则默认「流式中或未跑完时展开」
    var userToggled by remember(visible.first().id) { mutableStateOf<Boolean?>(null) }
    val expanded = userToggled ?: (isStreaming || !allDone)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 14.72.dp),                 // mb-4
    ) {
        // ── 组头（可点折叠）──────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(7.36.dp))       // rounded-lg
                .background(
                    // 只有未跑完时才有底色（跑完透明）
                    if (!allDone) {
                        if (CCMTheme.isDark) Color.White.copy(alpha = 0.04f)
                        else Color.Black.copy(alpha = 0.04f)
                    } else {
                        Color.Transparent
                    },
                )
                .clickable { userToggled = !expanded }
                .padding(horizontal = 7.36.dp, vertical = 5.52.dp),   // px-2 py-1.5
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.36.dp),    // gap-2
        ) {
            when {
                // 运行中：FileText + animate-pulse
                !allDone -> PulsingFileTextIcon(size = 14.72.dp, tint = colors.textSecondary)
                // 完成但有错：红 ✗（text-[14px]）
                hasError -> Text(
                    text = "✗",
                    style = CCMText.body14.copy(fontSize = 11.39.sp),
                    color = Color(0xFFF87171),
                )
                // 全部成功：Check
                else -> PainterIcon(R.drawable.ic_check, size = 14.72.dp, tint = colors.textSecondary)
            }

            // summary：工具名去重逗号拼接（`Run command, Read file`）
            if (!allDone) {
                // ★ 用 animate-shimmer-text（4s 窄亮带扫过），**不是**思维链的 1.6s 流光 ——
                //   两者在 Web 里是不同动画，见 [ToolHeaderShimmer] 注释里的对照表。
                ToolHeaderShimmer(
                    text = summary,
                    style = CCMText.body14,
                    modifier = Modifier.weight(1f, fill = false),
                )
            } else {
                Text(
                    text = summary,
                    style = CCMText.body14,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }

            ChevronGlyph(rotation = if (expanded) 180f else 0f, size = 12.88.dp, tint = colors.textSecondary)
        }

        // ── 展开列：左竖线内列各工具卡 ───────────────────────────
        if (expanded) {
            val railColor = colors.border                    // border-claude-border
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 7.36.dp)                  // mt-2
                    // 竖线画在 `ml-1` 处（距左边缘 3.68dp），**不是**内容区起点 ——
                    // Web 的 `border-l-2` 属于展开列自身，`pl-4` 是线内侧的内距。
                    // 所以 drawBehind 必须放在 start padding **之前**。
                    .drawBehind {
                        drawRect(
                            color = railColor,
                            topLeft = Offset(3.68.dp.toPx(), 0f),
                            size = Size(1.84.dp.toPx(), size.height),
                        )
                    }
                    .padding(start = 3.68.dp + 14.72.dp),    // ml-1 + pl-4
                verticalArrangement = Arrangement.spacedBy(7.36.dp),   // space-y-2
            ) {
                visible.forEach { card ->
                    ToolCallItem(card = card, isStale = isStale)
                }

                // 组尾：全跑完且不在流式中 → Check + Done
                if (allDone && !isStreaming) {
                    Row(
                        modifier = Modifier.padding(vertical = 3.68.dp),   // pt-1 pb-1
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),   // gap-2
                    ) {
                        PainterIcon(R.drawable.ic_check, size = 12.88.dp, tint = colors.textSecondary)
                        Text(
                            text = "Done",
                            style = CCMText.body13,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 组头摘要的扫光文字 —— 对应 Web `animate-shimmer-text`（`MainContent.tsx:849-858`）。
 *
 * ## ★ 为什么不用 AssistantThinkingChain 里的 [TextShimmer]
 * 两者在 Web 里是**两套不同的动画**，混用会明显看出节奏不对：
 * | | `animate-shimmer-text`（工具组头） | `thought-chain-text-shimmer`（思维链） |
 * |---|---|---|
 * | 周期 | **4s** linear | 1.6s linear |
 * | 渐变 | `secondary 45% → main 50% → secondary 55%`（**窄亮带，仅 10%**） | `0% → 48% → 100%`（宽渐变） |
 * | background-size | 200% | 220% |
 * | keyframes | `200% 0` → `-200% 0` | `200% center` → `0% center` |
 *
 * 观感差异：工具组头是「**快速扫过 + 长停顿**」（亮带只在 25%~50% 行程内掠过文字），
 * 思维链是「持续流动」。用 1.6s 那套会让组头一直在闪，比 Web 躁。
 *
 * ## background-position 换算
 * `position% × (容器宽 − 背景宽)`：
 * - 背景宽 = 2W → 可移动距离 = W − 2W = **−W**
 * - position `200%` → x = −W × 2.0 = **−2W**
 * - position `−200%` → x = −W × (−2.0) = **+2W**
 *
 * 亮带中心 = x + W（50% 在窗口正中）：
 * - t=0.25 → x = −W → 中心 = 0（贴着文字左缘）
 * - t=0.5  → x = 0  → 中心 = W（贴着文字右缘）
 * 即亮带在 **t ∈ [0.25, 0.5]** 掠过文字，其余 3/4 时间是静止灰字。
 */
@Composable
private fun ToolHeaderShimmer(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    var width by remember { mutableStateOf(0f) }

    // ★ 颜色在 remember 之外求值：@Composable 不能进 remember 的 calculation
    val edge = CCMTheme.colors.textSecondary
    val body = CCMTheme.colors.textMain

    val transition = rememberInfiniteTransition(label = "tool-shimmer")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "tool-shimmer-pos",
    )

    val brush = remember(width, t, edge, body) {
        if (width <= 0f) {
            SolidColor(edge)
        } else {
            // 窗口宽 2W，起点 x 从 −2W 线性走到 +2W
            val start = -2f * width + 4f * width * t
            androidx.compose.ui.graphics.Brush.linearGradient(
                colorStops = arrayOf(
                    0.45f to edge,
                    0.50f to body,
                    0.55f to edge,
                ),
                start = Offset(start, 0f),
                end = Offset(start + 2f * width, 0f),
            )
        }
    }

    Text(
        text = text,
        style = style.copy(brush = brush),
        modifier = modifier.onSizeChanged { width = it.width.toFloat() },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 组内单个工具卡 —— 对齐 Web `MainContent.tsx:1243-1295`。
 *
 * ```
 * ┌ bg-black/5 dark:bg-black/20 · rounded-lg · border-black/5 · mx-1 ┐
 * │ [>_ 或 ▤14] 预览(font-mono 12)         [+N -N] [Running...] [⌄] │
 * ├──────────────────────────────────────────────────────────────────┤
 * │ 展开：border-t + px-2 py-2                                       │
 * │   ├ Edit/MultiEdit/Write/Bash/Read → ToolDiffView                │
 * │   └ 其他 → 结果框（font-mono 12 · max-h-400 · bg-black/5）        │
 * └──────────────────────────────────────────────────────────────────┘
 * ```
 *
 * ## 与旧实现的差异（重点）
 * 1. **左侧图标不再随状态变**：Web 组内卡固定 `FileText 14`（或 Bash 的 `>_`），
 *    **没有** pulse / Check / ✗ —— 状态只体现在右侧 `Running...` / `Failed`。
 *    旧实现把「状态图标」放在左边，与 Web 不符。
 * 2. **多了 +N/-N 统计**（Edit/Write 的行数增减，`getToolStats`）。
 * 3. **展开区是 `border-t` 顶边**，不是左边竖线（竖线属于组，不属于卡）。
 */
@Composable
private fun ToolCallItem(
    card: ChatToolCard,
    isStale: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = CCMTheme.colors
    // realStatus：running 且整轮已停 → canceled（不再显示 Running 动效）
    val isRunning = card.running && !isStale
    val isError = card.isError

    // 单卡展开态（Web `tc.isExpanded ?? false`）
    var expanded by remember(card.id) { mutableStateOf(false) }

    val filePath = inputField(card.input, "file_path").ifBlank { inputField(card.input, "path") }
    val command = inputField(card.input, "command")
    val preview = buildWebStylePreview(card.name, filePath, command, card.input)
        .ifBlank { card.displayName.ifBlank { card.name } }

    val useDiff = shouldUseDiffView(card.name, card.input)
    // 有入参但 JSON 字段暂时无法解析时也必须可展开；否则 Write 等工具
    // 执行中点击无反应，进度/原始入参永远不可见。
    val expandable = card.input.isNotBlank() || card.result.isNotBlank() || card.progress.isNotBlank() || useDiff
    val stats = toolStats(card.name, card.input)

    val cardBg = if (CCMTheme.isDark) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.05f)
    val cardBorder = if (CCMTheme.isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 3.68.dp)               // mx-1
            .clip(RoundedCornerShape(7.36.dp))           // rounded-lg
            .background(cardBg)
            .border(0.92.dp, cardBorder, RoundedCornerShape(7.36.dp)),
    ) {
        // ── 头部（可点折叠）px-3 py-2 ────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = expandable) { expanded = !expanded }
                .padding(horizontal = 11.04.dp, vertical = 7.36.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左：图标 + 预览（flex items-center gap-2 overflow-hidden）
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.36.dp),
            ) {
                if (card.name == "Bash") {
                    // Bash 用 `>_` 等宽符号（Web: font-mono font-bold）
                    Text(
                        text = ">_",
                        style = CCMText.body12.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.textSecondary,
                    )
                } else {
                    // 其他工具固定 FileText 14px（**不随状态变**）
                    PainterIcon(R.drawable.ic_file_text, size = 12.88.dp, tint = colors.textSecondary)
                }

                Text(
                    text = preview,
                    // text-claude-text font-mono text-[12px] truncate
                    style = CCMText.body12.copy(fontFamily = FontFamily.Monospace),
                    color = colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 右：统计 / Running / Failed / Chevron（ml-4 gap-2 flex-shrink-0）
            Row(
                modifier = Modifier.padding(start = 14.72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.36.dp),
            ) {
                // +N/-N（Edit/Write 的行数增减；运行中不显示）
                if (stats != null && !isRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.52.dp),
                    ) {
                        if (stats.first > 0) {
                            Text(
                                text = "+${stats.first}",
                                style = CCMText.body11.copy(fontFamily = FontFamily.Monospace),
                                color = if (CCMTheme.isDark) Color(0xFF4ADE80) else Color(0xFF22C55E),
                            )
                        }
                        if (stats.second > 0) {
                            Text(
                                text = "-${stats.second}",
                                style = CCMText.body11.copy(fontFamily = FontFamily.Monospace),
                                color = if (CCMTheme.isDark) Color(0xFFF87171) else Color(0xFFEF4444),
                            )
                        }
                    }
                }

                // Running... 走 shimmer 扫光（Web 单卡里同样是 `animate-shimmer-text`）
                if (isRunning) {
                    ToolHeaderShimmer(text = "Running...", style = CCMText.body12)
                }
                if (isError) {
                    Text(
                        text = "Failed",
                        style = CCMText.body12,
                        color = Color(0xFFF87171),      // text-red-400/80
                    )
                }

                if (expandable) {
                    ChevronGlyph(
                        rotation = if (expanded) 180f else 0f,
                        size = 12.88.dp,
                        tint = colors.textSecondary,
                    )
                }
            }
        }

        // ── 展开区（px-2 py-2 + border-t）────────────────────────
        if (expandable && expanded) {
            // ★ CI #239：`CCMTheme.isDark` 是 @Composable（读 CompositionLocal），
            //   不能进 drawBehind{} 的 DrawScope lambda —— 必须在外面先取好值。
            //   （同类坑见 CLAUDE.md「@Composable 不能进 Canvas{}/remember{}」）
            val topBorderColor = if (CCMTheme.isDark) Color.White.copy(alpha = 0.05f)
            else Color.Black.copy(alpha = 0.05f)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        // border-t（1px × 0.92）
                        drawRect(
                            color = topBorderColor,
                            topLeft = Offset.Zero,
                            size = Size(size.width, 0.92.dp.toPx()),
                        )
                    }
                    .padding(horizontal = 7.36.dp, vertical = 7.36.dp),
            ) {
                when {
                    // Edit/Write/Bash/Read → diff 视图（对应 `shouldUseDiffView` 分支）
                    useDiff -> ToolDiffView(
                        toolName = card.name,
                        oldString = inputField(card.input, "old_string"),
                        newString = inputField(card.input, "new_string"),
                        filePath = filePath,
                        command = command,
                        // Bash/Read 的内容来自执行结果，不是入参
                        output = if (card.name == "Bash" || card.name == "Read") card.result else "",
                    )
                    // 其他 → 纯结果框
                    card.result.isNotBlank() || card.progress.isNotBlank() || card.input.isNotBlank() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 3.68.dp)          // px-1
                            .heightIn(max = 368.dp)                 // max-h-[400px] × 0.92
                            .clip(RoundedCornerShape(5.52.dp))      // rounded-md
                            .background(
                                if (CCMTheme.isDark) Color.Black.copy(alpha = 0.4f)
                                else Color.Black.copy(alpha = 0.05f),
                            )
                            .padding(7.36.dp),                       // p-2
                        // ★ 2026-10-01 修闪退：原来这里还有 .verticalScroll()——
                        //   外层 ChatScreen 的 Column 已经是 verticalScroll，
                        //   嵌套的内层 scroll 会被以「无限最大高度」测量 →
                        //   IllegalStateException（Vertically scrollable...）。
                        //   结果本来就截断到 2000 字，不需要内部滚动。
                    ) {
                        Text(
                            // 源码：result.length > 2000 → 截断加 ...
                            text = when {
                                card.result.isNotBlank() -> if (card.result.length > 2000) {
                                    card.result.take(2000) + "..."
                                } else card.result
                                card.progress.isNotBlank() -> card.progress
                                else -> card.input.take(2000)
                            },
                            style = CCMText.body12.copy(fontFamily = FontFamily.Monospace),
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 折叠箭头 —— 对应 lucide `ChevronDown` + `transition-transform duration-200`。
 *
 * Web 用 CSS transition 做 200ms 旋转；Compose 用 [animateFloatAsState] 等价。
 */
@Composable
private fun ChevronGlyph(rotation: Float, size: Dp, tint: Color) {
    val angle by animateFloatAsState(
        targetValue = rotation,
        animationSpec = tween(durationMillis = 200),
        label = "chevron-rotate",
    )
    PainterIcon(
        R.drawable.ic_chevron_down,
        size = size,
        tint = tint,
        modifier = Modifier.rotate(angle),
    )
}

/**
 * 运行中的 FileText 图标 —— 对应 Web `FileText size={16} className="animate-pulse"`。
 *
 * Web 的 `animate-pulse` 是 opacity 1 → 0.5 → 1（2s cubic-bezier）。
 * Compose 用 alpha 呼吸近似（900ms 往返，与既有实现一致）。
 */
@Composable
private fun PulsingFileTextIcon(size: Dp, tint: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    PainterIcon(R.drawable.ic_file_text, size = size, tint = tint.copy(alpha = alpha))
}

/**
 * 隐藏工具名 —— 对应 Web `HIDDEN_TOOL_NAMES`。
 *
 * Web 把 WebSearch/WebFetch 从**工具组**里过滤掉（它们走搜索过程条
 * `SearchProcess` 单独渲染）。APK 目前没有搜索过程条，但保持一致：
 * 混在工具组里会让「搜索」既出现在组内又出现在别处。
 */
private val HiddenToolNames = setOf("WebSearch", "WebFetch")

/**
 * 工具显示名 —— 对应 Web `getToolDisplayName`（`toolThinkingFallback.js:3-26`）。
 *
 * 用于**组头 summary**（去重逗号拼接）与思考链事件标签，不是单卡预览。
 */
private val ToolLabels = mapOf(
    "Read" to "Read file",
    "Write" to "Write file",
    "Edit" to "Edit file",
    "MultiEdit" to "Edit files",
    "Bash" to "Run command",
    "ListDir" to "List directory",
    "Search" to "Search",
    "Grep" to "Search",
    "Glob" to "Find files",
    // 持久化 Task（多 Agent 共享待办）
    "TaskCreate" to "建任务",
    "TaskList" to "看任务清单",
    "TaskGet" to "读任务详情",
    "TaskUpdate" to "更新任务",
    "TaskClaim" to "领取任务",
    "TaskDelete" to "删除任务",
    // Team 协作与通信
    "TeamCreate" to "建协作团队",
    "TeamJoin" to "加入团队",
    "SendMessage" to "发消息给队友",
    "CheckMessages" to "收队友消息",
    "TeamStatus" to "看团队状态",
    "TeamLeave" to "退出团队",
    "TeamDisband" to "解散团队",
)

/** 工具显示名（未知工具回退到原名，空名回退 `Tool`） */
internal fun toolDisplayName(name: String): String =
    ToolLabels[name] ?: name.ifBlank { "Tool" }

/**
 * 是否有 diff 视图 —— 对应 Web `shouldUseDiffView`（`ToolDiffView.tsx:380-393`）。
 *
 * Web 判断的是 `input` 对象字段；APK 的 input 是 JSON 字符串，故用 [inputField] 取值。
 */
private fun shouldUseDiffView(name: String, input: String): Boolean {
    if (input.isBlank()) return false
    return when (name) {
        "Edit", "MultiEdit" ->
            inputField(input, "old_string").isNotEmpty() || inputField(input, "new_string").isNotEmpty()
        "Write" -> inputField(input, "content").isNotEmpty()
        "Bash" -> inputField(input, "command").isNotEmpty()
        "Read" -> inputField(input, "file_path").isNotEmpty()
        else -> false
    }
}

/**
 * 行数增减统计 —— 对应 Web `getToolStats`（`ToolDiffView.tsx:402-419`）。
 *
 * - `Edit` / `MultiEdit` → (新增行数, 删除行数)
 * - `Write` → (内容行数, 0)
 * - 其他 → null（不显示）
 *
 * 注意 Web 的 `removed` 用的是 **old_string 的行数**（不是真正的 diff 删除行数），
 * 这里保持一致 —— 否则同一份改动在两端显示的数字会不同。
 */
private fun toolStats(name: String, input: String): Pair<Int, Int>? {
    if (input.isBlank()) return null
    when (name) {
        "Edit", "MultiEdit" -> {
            val oldStr = inputField(input, "old_string")
            val newStr = inputField(input, "new_string")
            if (oldStr.isEmpty() && newStr.isEmpty()) return null
            val added = if (newStr.isEmpty()) 0 else newStr.split('\n').size
            val removed = if (oldStr.isEmpty()) 0 else oldStr.split('\n').size
            return added to removed
        }
        "Write" -> {
            val content = inputField(input, "content")
            if (content.isEmpty()) return null
            return content.split('\n').size to 0
        }
    }
    return null
}

/** 工具动作标签 —— 对应 Web 的 `actionLabel` 映射表 */
private val ToolActionLabel = mapOf(
    "Read" to "Read",
    "Write" to "Write",
    "Edit" to "Edit",
    "MultiEdit" to "Edit",
    "Bash" to "",
    "Grep" to "Search",
    "Glob" to "Find",
    "ListDir" to "List",
    "Skill" to "Skill",
)

/**
 * 生成 Web 风格的预览 —— `Read a.mjs` / `Bash npm test`。
 *
 * 对应源码（`MainContent.tsx:1222-1229`）：
 * ```js
 * const shortPath = rawPath ? rawPath.split(/[/\\]/).pop() || rawPath : '';
 * const prefix = actionLabel[tc.name] ?? tc.name;
 * const fileOrCmd = shortPath || tc.input?.command || (inputStr.length > 80 ? inputStr.slice(0, 80) + '...' : inputStr);
 * const inputPreview = (prefix && fileOrCmd) ? `${prefix} ${fileOrCmd}` : (fileOrCmd || prefix || tc.name);
 * ```
 */
fun buildWebStylePreview(toolName: String, filePath: String?, command: String?, rawInput: String?): String {
    val prefix = ToolActionLabel[toolName] ?: toolName
    val shortPath = filePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: ""
    val fileOrCmd = shortPath.ifBlank {
        command?.takeIf { it.isNotBlank() } ?: rawInput?.take(80)?.let {
            if ((rawInput?.length ?: 0) > 80) "$it..." else it
        } ?: ""
    }
    return listOf(prefix, fileOrCmd).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * 从工具入参 JSON 里取一个字符串字段（ToolDiffView 用）。
 *
 * 解析失败返回空串 —— diff 组件对空字段直接 return，退化为原结果展示，
 * 不会因为一条畸形入参崩掉整个卡片。
 */
private fun inputField(json: String, key: String): String = try {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
        as? kotlinx.serialization.json.JsonObject ?: return ""
    (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
} catch (_: Throwable) {
    ""
}

/**
 * 公开版的 [inputField] —— 思考链事件合成（AssistantThinkingChain.kt）也要解析工具入参。
 *
 * 保持同一个实现，避免两处 JSON 解析行为漂移（例如对畸形入参的处理）。
 */
internal fun toolInputField(json: String, key: String): String = inputField(json, key)
