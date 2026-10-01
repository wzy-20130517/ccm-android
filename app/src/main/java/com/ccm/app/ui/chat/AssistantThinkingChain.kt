package com.ccm.app.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme
import kotlinx.coroutines.delay

/**
 * 思考链 —— 对齐 `AssistantThinkingChain.tsx`（850 行）。
 *
 * ## 结构（源码）
 * ```
 * 摘要行（button，可点展开）
 * │   ├─ Lightbulb 图标 14（仅 hasDetailedEvents 时）
 * │   ├─ 摘要文字 text-[14px] leading-[19.6px] tracking-[-0.1504px]  · #7b7974
 * │   └─ ChevronDown 12（isExpanded 时 rotate-180）
 * └─ 展开区（isExpanded 时）
 *     ├─ 正文块（thinking 全文，max-h-200 overflow-y-auto，底部渐隐 h-12）
 *     │   └─ 「展开/收起」按钮（仅 isOverflowing 时）
 *     └─ 时间线事件列表（events）
 *         └─ 每项：左侧 20 宽图标列 + 竖轨（left-9.5 w-px）
 *                右侧：label（active 时 shimmer）+ meta
 * ```
 *
 * ## ★ 关键常量（源码写死，不乘 0.92）
 * | 项 | 值 | 出处 |
 * |---|---|---|
 * | 竖轨 x | **9.5** | `left-[9.5px]` |
 * | 竖轨色 | `rgba(31,31,30,0.15)` / 暗色 `rgba(248,248,246,0.18)` | `railClassName` |
 * | 图标尺寸 | **15×15** | `thoughtChainIconStyle` |
 * | 图标列宽 | **20** | `w-[20px]` |
 * | 正文最大高 | **200** | `scrollHeight > 200` 判溢出 |
 * | 渐隐高 | **48** | `h-12` |
 * | 摘要字号 | **14** / 行高 19.6 / 字距 −0.1504 | `text-[14px]` |
 * | 事件字号 | **14**（紧凑 **13**）/ 行高 19.6（紧凑 18） | |
 * | meta 字号 | **12**（紧凑 **11**） | |
 * | 行进入动画 | 220ms cubic-bezier(0.22,1,0.36,1)，延迟 index×45ms（紧凑 40ms） | |
 *
 * ## ★ 三处「不直观但必须保留」的逻辑
 *
 * 1. **摘要文字选择**（`getThinkingSummary`）：
 *    正在思考且无 summary → 固定文案「正在深入思考，请稍候…」；
 *    否则取 thinking **最后一行非空**，超 64 字截断加 `...`；
 *    无内容时：思考中「正在思考…」/ 结束「思考完成」。
 *
 * 2. **有详细事件时摘要显示固定「思考」而非摘要文字**
 *    （源码：`hasDetailedEvents ? thoughtHeaderLabel : summary`）——
 *    因为详细事件本身就在下面展开了，摘要再显示内容会重复。
 *
 * 3. **合成 done 事件**：`!isThinking` 且最后一项不是 done 时，自动补一条
 *    `{ kind: 'done', label: 'Done' }`。这是给「思考已结束」一个视觉终点，
 *    **不是数据错误**，别以为是脏数据给过滤掉。
 *
 * ## 图标（本项目无 SVG 资源）
 * 源码用 7 个 figma 导出的 SVG。Compose 侧用 [ThinkingEventIcon] **Canvas 手绘**
 * 等效图形（各 kind 形状不同），保持 15×15 与左侧对齐 —— 这是「零变化」下的
 * 已知偏差：尺寸/位置一致，图形为近似绘制。
 */
@Composable
fun AssistantThinkingChain(
    thinking: String,
    modifier: Modifier = Modifier,
    thinkingSummary: String? = null,
    isThinking: Boolean = false,
    isExpanded: Boolean = false,
    events: List<AssistantThinkingEvent> = emptyList(),
    onToggleExpanded: () -> Unit = {},
) {
    val colors = CCMTheme.colors

    // 合成 done 事件（见类注释第 3 条）
    val syntheticEvents = remember(events, isThinking) {
        if (events.isEmpty()) {
            null
        } else {
            val list = events.toMutableList()
            if (!isThinking && list.lastOrNull()?.kind != ThinkingEventKind.DONE) {
                list.add(AssistantThinkingEvent(ThinkingEventKind.DONE, "Done"))
            }
            list
        }
    }

    if (thinking.isEmpty() && syntheticEvents.isNullOrEmpty()) return

    val summary = remember(thinking, thinkingSummary, isThinking) {
        thinkingSummaryText(thinking, thinkingSummary, isThinking)
    }

    val hasDetailedEvents = syntheticEvents?.any { !it.detail.isNullOrBlank() } == true
    val canToggle = !syntheticEvents.isNullOrEmpty()
    val activeEventIndex = activeEventIndex(syntheticEvents, isThinking)

    // 展开区正文的溢出检测（源码 `scrollHeight > 200`）
    var isOverflowing by remember { mutableStateOf(false) }
    var isBodyExpanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 摘要行 ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = canToggle, onClick = onToggleExpanded)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hasDetailedEvents) {
                LightbulbGlyph(
                    color = ThinkingMuted,
                    size = 14.dp,
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
            Text(
                text = if (hasDetailedEvents) "思考" else summary,
                style = CCMText.body14.copy(
                    fontSize = 14.sp,
                    lineHeight = 19.6.sp,
                    letterSpacing = (-0.1504).sp,
                ),
                color = ThinkingMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canToggle) {
                ChevronDownGlyph(
                    color = ThinkingMuted,
                    size = 12.dp,
                    rotation = if (isExpanded) 180f else 0f,
                )
            }
        }

        if (!isExpanded) return@Column

        // ── 正文块 ────────────────────────────────────────────
        val bodyScroll = rememberScrollState()
        // 思考中自动滚到底（源码 `el.scrollTop = el.scrollHeight`）
        LaunchedEffect(thinking, isThinking, isExpanded, isBodyExpanded) {
            if (isThinking) bodyScroll.animateScrollTo(bodyScroll.maxValue)
        }
        // 溢出判定：估算行数（每行 ≈ 19.6 高），超过 200 即溢出。
        // Compose 里没有 scrollHeight，用「行数 × 行高」近似 —— 与源码阈值等价。
        val estimatedHeight = thinking.split('\n').size * 19.6f
        LaunchedEffect(thinking, isExpanded, isBodyExpanded) {
            isOverflowing = estimatedHeight > 200f
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = if (isBodyExpanded) Dp.Unspecified else 200.dp)
                    .verticalScroll(bodyScroll),
            ) {
                Text(
                    text = thinking,
                    style = CCMText.body14.copy(
                        fontSize = 14.sp,
                        lineHeight = 19.6.sp,
                        letterSpacing = (-0.1504).sp,
                    ),
                    color = ThinkingBody,
                )
            }
            // 底部渐隐 h-12（仅未展开且溢出时）
            if (isOverflowing && !isBodyExpanded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, colors.bgMain),
                            ),
                        ),
                )
            }
        }

        if (isOverflowing) {
            Text(
                text = if (isBodyExpanded) "收起" else "展开",
                style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = ThinkingMuted.copy(alpha = 0.8f),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .clickable { isBodyExpanded = !isBodyExpanded },
            )
        }

        // ── 时间线事件列表 ────────────────────────────────────
        syntheticEvents?.forEachIndexed { index, event ->
            ThinkingTimelineEvent(
                event = event,
                index = index,
                total = syntheticEvents.size,
                isActive = isThinking &&
                    event.kind != ThinkingEventKind.DONE &&
                    index == activeEventIndex,
            )
        }
    }
}

/** 紧凑状态行 —— 对应 `AssistantThinkingCompactStatus` */
@Composable
fun AssistantThinkingCompactStatus(
    event: AssistantThinkingEvent?,
    modifier: Modifier = Modifier,
    isThinking: Boolean = false,
) {
    if (event == null) return
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ThinkingEventIcon(
            kind = event.kind,
            size = 15.dp,
            color = if (isThinking) ThinkingBody else ThinkingMuted,
        )
        Text(
            text = event.label,
            style = CCMText.body13.copy(
                fontSize = 13.sp,
                lineHeight = 18.sp,
                letterSpacing = (-0.1304).sp,
            ),
            color = if (isThinking) ThinkingBody else ThinkingMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!event.meta.isNullOrEmpty()) {
            Text(
                text = event.meta,
                style = CCMText.body11.copy(fontSize = 11.sp, lineHeight = 15.sp),
                color = ThinkingMuted,
            )
        }
    }
}

/**
 * 时间线单行 —— 对应 `renderTimelineEvent`。
 *
 * 布局：`[竖轨 9.5px] [图标列 20px（图标 15，上内距 4）] [内容 pl-10 pt-2]`
 * 竖轨通过 [renderRailSpacer] 在行首/行尾各插一段（index>0 / index<total-1 时可见）。
 */
@Composable
private fun ThinkingTimelineEvent(
    event: AssistantThinkingEvent,
    index: Int,
    total: Int,
    isActive: Boolean,
) {
    val muted = event.kind != ThinkingEventKind.DONE

    // ── 行进入动画（`thought-chain-row-enter 220ms cubic-bezier(.22,1,.36,1) both`）
    // 延迟 index × 45ms —— 逐行错开是「时间线在推进」的关键观感，
    // 去掉延迟会变成所有行同时闪入，失去「一条条发生」的语义。
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 45L)
        enter.animateTo(
            targetValue = 1f,
            animationSpec = tween(220, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 4f   // translateY(4px)
            },
    ) {
        // 行首竖轨（index > 0 时可见）
        RailSpacer(visible = index > 0, height = 8.dp)

        Row(modifier = Modifier.fillMaxWidth()) {
            // 图标列：w-20，图标 15，上内距 4
            Box(
                modifier = Modifier.width(20.dp).padding(top = 4.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                ThinkingEventIcon(
                    kind = event.kind,
                    size = 15.dp,
                    color = if (isActive) ThinkingBody else ThinkingMuted,
                )
            }
            // 内容：pl-10 pt-2
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, top = 2.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    if (isActive) {
                        // 活动行用 shimmer 文字（对应 `renderSparkStatusCopy` 的 TextShimmer 分支）
                        TextShimmer(
                            text = event.label,
                            style = CCMText.body14.copy(
                                fontSize = 14.sp,
                                lineHeight = 19.6.sp,
                                letterSpacing = (-0.1504).sp,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = event.label,
                            style = CCMText.body14.copy(
                                fontSize = 14.sp,
                                lineHeight = if (!muted) 19.6.sp else 20.sp,
                                letterSpacing = (-0.1504).sp,
                            ),
                            color = if (!muted) ThinkingBody else ThinkingMuted,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!event.meta.isNullOrEmpty()) {
                        Text(
                            text = event.meta,
                            style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.8.sp),
                            color = ThinkingMuted,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                }

                // detail（可选）
                if (!event.detail.isNullOrBlank()) {
                    Text(
                        text = event.detail,
                        style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.8.sp),
                        color = ThinkingMuted,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                // 搜索结果（web_search）
                event.results?.forEach { r ->
                    Column(modifier = Modifier.padding(top = 6.dp)) {
                        Text(
                            text = r.title,
                            style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.8.sp),
                            color = ThinkingBody,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!r.source.isNullOrEmpty()) {
                            Text(
                                text = r.source,
                                style = CCMText.body11.copy(fontSize = 11.sp, lineHeight = 15.sp),
                                color = ThinkingMuted,
                            )
                        }
                    }
                }

                // 文件预览（write_preview）—— 最多 8 行，每行截 92 字符
                event.preview?.let { p ->
                    Spacer(Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors0())
                            .padding(8.dp),
                    ) {
                        Text(
                            text = p.title,
                            style = CCMText.body12.copy(fontSize = 12.sp, lineHeight = 16.8.sp),
                            color = ThinkingBody,
                        )
                        previewLines(p.content).forEach { line ->
                            Text(
                                text = line,
                                style = CCMText.body11.copy(
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = ThinkingMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 行尾竖轨（index < total-1 时可见）
        RailSpacer(visible = index < total - 1, height = 8.dp)
    }
}

/** 竖轨占位 —— 对应 `renderRailSpacer`：固定高度 + 左侧 9.5px 处的 1px 竖线 */
@Composable
private fun RailSpacer(visible: Boolean, height: Dp) {
    Box(modifier = Modifier.fillMaxWidth().height(height)) {
        if (visible) {
            Box(
                modifier = Modifier
                    .padding(start = 9.5.dp)
                    .width(1.dp)
                    .height(height)
                    .background(ThinkingRail),
            )
        }
    }
}

/**
 * 摘要文字 —— 对应 `getThinkingSummary`。
 *
 * ⚠️ 优先级：`isThinking && !summary` → 固定文案；再 `summary`；再取最后一行。
 * 顺序反了会导致思考中显示旧摘要，看起来像卡住。
 */
internal fun thinkingSummaryText(
    thinking: String,
    thinkingSummary: String?,
    isThinking: Boolean,
): String {
    if (isThinking && thinkingSummary.isNullOrEmpty()) return "正在深入思考，请稍候…"
    if (!thinkingSummary.isNullOrEmpty()) return thinkingSummary

    val lines = thinking.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    val last = lines.lastOrNull() ?: ""
    if (last.isEmpty()) return if (isThinking) "正在思考…" else "思考完成"
    return if (last.length > 64) last.take(64) + "..." else last
}

/** 高亮事件下标 —— 对应 `getActiveEventIndex`：从后往前找第一个非 done */
private fun activeEventIndex(
    events: List<AssistantThinkingEvent>?,
    isThinking: Boolean,
): Int {
    if (!isThinking || events.isNullOrEmpty()) return -1
    for (i in events.indices.reversed()) {
        if (events[i].kind != ThinkingEventKind.DONE) return i
    }
    return -1
}

/** 预览行 —— 对应 `buildPreviewLines`：去空行、每行截 92 字符、最多 8 行 */
private fun previewLines(content: String): List<String> {
    val lines = content
        .replace("\r\n", "\n")
        .replace("\t", "  ")
        .split('\n')
        .map { it.trimEnd() }
        .filter { it.trim().isNotEmpty() }
    if (lines.isEmpty()) return listOf("Preparing preview…")
    return lines.take(8).map { if (it.length > 92) it.take(92) + "…" else it }
}

/** 思考事件类别 —— 对应源码 `AssistantThinkingEvent['kind']` */
enum class ThinkingEventKind {
    DIRECT, FOCUS, SKILL, TOOL, WEB_SEARCH, WRITE_PREVIEW, DONE,
}

/** 思考事件 —— 对应源码 `interface AssistantThinkingEvent` */
data class AssistantThinkingEvent(
    val kind: ThinkingEventKind,
    val label: String,
    val detail: String? = null,
    val meta: String? = null,
    val results: List<ThinkingSearchResult>? = null,
    val preview: ThinkingFilePreview? = null,
)

/** 搜索结果 —— 对应 `ThoughtChainSearchResult` */
data class ThinkingSearchResult(val title: String, val source: String? = null, val url: String? = null)

/** 文件预览 —— 对应 `ThoughtChainFilePreview` */
data class ThinkingFilePreview(val title: String, val format: String, val content: String)

/**
 * 事件图标 —— 源码用 7 个 figma SVG，这里 Canvas 手绘等效图形。
 *
 * 形状对应关系（尽量贴原图语义）：
 * - DIRECT：圆点 + 短线（直接回答）
 * - FOCUS / SKILL / TOOL：空心圆（步骤）
 * - WEB_SEARCH：圆 + 手柄（放大镜）
 * - WRITE_PREVIEW：方框 + 折角（文档）
 * - DONE：圆 + 对勾
 */
@Composable
private fun ThinkingEventIcon(kind: ThinkingEventKind, size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 1.38.dp.toPx(), cap = StrokeCap.Round)

        when (kind) {
            ThinkingEventKind.DIRECT -> {
                drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.35f))
                drawLine(
                    color,
                    Offset(w * 0.5f, h * 0.55f),
                    Offset(w * 0.5f, h * 0.85f),
                    stroke.width,
                    StrokeCap.Round,
                )
            }
            ThinkingEventKind.WEB_SEARCH -> {
                drawCircle(color, radius = w * 0.28f, center = Offset(w * 0.42f, h * 0.42f), style = stroke)
                drawLine(
                    color,
                    Offset(w * 0.63f, h * 0.63f),
                    Offset(w * 0.85f, h * 0.85f),
                    stroke.width,
                    StrokeCap.Round,
                )
            }
            ThinkingEventKind.WRITE_PREVIEW -> {
                val p = Path().apply {
                    moveTo(w * 0.2f, h * 0.12f)
                    lineTo(w * 0.6f, h * 0.12f)
                    lineTo(w * 0.8f, h * 0.32f)
                    lineTo(w * 0.8f, h * 0.88f)
                    lineTo(w * 0.2f, h * 0.88f)
                    close()
                }
                drawPath(p, color, style = stroke)
                drawLine(
                    color,
                    Offset(w * 0.6f, h * 0.12f),
                    Offset(w * 0.6f, h * 0.32f),
                    stroke.width,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(w * 0.6f, h * 0.32f),
                    Offset(w * 0.8f, h * 0.32f),
                    stroke.width,
                    StrokeCap.Round,
                )
            }
            ThinkingEventKind.DONE -> {
                drawCircle(color, radius = w * 0.38f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
                val p = Path().apply {
                    moveTo(w * 0.32f, h * 0.5f)
                    lineTo(w * 0.45f, h * 0.63f)
                    lineTo(w * 0.7f, h * 0.38f)
                }
                drawPath(p, color, style = stroke)
            }
            else -> {
                // FOCUS / SKILL / TOOL：空心圆（步骤点）
                drawCircle(color, radius = w * 0.3f, center = Offset(w * 0.5f, h * 0.5f), style = stroke)
            }
        }
    }
}

/** 灯泡图标 —— 对应 lucide `Lightbulb`（14px） */
@Composable
private fun LightbulbGlyph(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(color, radius = w * 0.3f, center = Offset(w * 0.5f, h * 0.42f), style = stroke)
        drawLine(color, Offset(w * 0.4f, h * 0.75f), Offset(w * 0.6f, h * 0.75f), stroke.width, StrokeCap.Round)
        drawLine(color, Offset(w * 0.43f, h * 0.88f), Offset(w * 0.57f, h * 0.88f), stroke.width, StrokeCap.Round)
    }
}

/** 下拉箭头 —— 对应 lucide `ChevronDown`（12px），[rotation] 由展开态驱动 */
@Composable
private fun ChevronDownGlyph(color: Color, size: Dp, rotation: Float) {
    Canvas(
        modifier = Modifier
            .size(size)
            .rotate(rotation),
    ) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.25f, h * 0.4f)
            lineTo(w * 0.5f, h * 0.65f)
            lineTo(w * 0.75f, h * 0.4f)
        }
        drawPath(p, color, style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 正文色 `#373734`（亮色） */
private val ThinkingBody = Color(0xFF373734)

/** 弱化色 `#7b7974` */
private val ThinkingMuted = Color(0xFF7B7974)

/** 竖轨色 `rgba(31,31,30,0.15)` */
private val ThinkingRail = Color(0x261F1F1E)

/** 预览块底色（`bg-claude-bg/60` 的近似） */
@Composable
private fun colors0(): Color = CCMTheme.colors.bgMain.copy(alpha = 0.6f)

/**
 * 文字流光 —— 对应源码 `TextShimmer`。
 *
 * ## 源码实现（必须理解才能等价移植）
 * ```css
 * background-image: linear-gradient(90deg,
 *     rgba(123,121,116,0.72) 0%, rgba(55,55,52,0.92) 48%, rgba(123,121,116,0.72) 100%);
 * background-size: 220% 100%;
 * background-clip: text;
 * animation: thought-chain-text-shimmer 1.6s linear infinite;
 * @keyframes: background-position 200% center → 0% center
 * ```
 *
 * ## 换算（为什么是 2.4W）
 * `background-position` 的百分比**不是**相对容器宽，而是相对 `容器宽 − 背景宽`：
 * - 背景宽 = 2.2W（`background-size: 220%`）
 * - 可移动距离 = W − 2.2W = **−1.2W**
 * - position 200% → x = −1.2W × 2.0 = **−2.4W**
 * - position 0%   → x = **0**
 *
 * 所以渐变窗口从 −2.4W 线性滑到 0（窗口自身宽 2.2W）。
 * 这解释了为什么**必须先量到文字宽度** —— 不知道 W 就没法定位渐变，
 * 用固定值会让长标签的流光卡在半路。
 */
@Composable
internal fun TextShimmer(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    var width by remember { mutableStateOf(0f) }

    val transition = rememberInfiniteTransition(label = "think-shimmer")
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "think-shimmer-pos",
    )

    val brush = remember(width, t) {
        if (width <= 0f) {
            SolidColor(ThinkingMuted)
        } else {
            val start = -2.4f * width + 2.4f * width * t
            androidx.compose.ui.graphics.Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to ShimmerMuted,
                    0.48f to ShimmerBody,
                    1.00f to ShimmerMuted,
                ),
                start = Offset(start, 0f),
                end = Offset(start + 2.2f * width, 0f),
            )
        }
    }

    Text(
        text = text,
        style = style.copy(brush = brush),
        modifier = modifier.onSizeChanged { width = it.width.toFloat() },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 流光渐变端点 `rgba(123,121,116,0.72)` */
private val ShimmerMuted = Color(0xB87B7974)

/** 流光渐变中点 `rgba(55,55,52,0.92)` */
private val ShimmerBody = Color(0xEB373734)
