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
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.PathParser
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme
import com.ccm.app.ui.theme.CcmSerif
import kotlinx.coroutines.delay

/** 紧凑状态行文字色 `#3d3d3a` —— Web `text-[#3d3d3a] dark:text-claude-text` */
private val CompactStatusText = Color(0xFF3D3D3A)

/** 图标 SVG 的固有 viewBox 边长（`viewBox="0 0 20 20"`）—— 所有 figma 图标一致。 */
private const val ICON_VIEW_BOX = 20f

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
                .padding(vertical = 3.68.dp),      // py-[4px] × 0.92
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.68.dp),   // gap-[4px] × 0.92
        ) {
            if (hasDetailedEvents) {
                LightbulbGlyph(
                    color = thinkingMuted(),
                    size = 12.88.dp,               // lucide size={14} × 0.92
                    modifier = Modifier.padding(end = 1.84.dp),   // mr-[2px] × 0.92
                )
            }
            Text(
                text = if (hasDetailedEvents) "思考" else summary,
                // ★ 字号必须与正文一致：Web 两侧同为 `text-[14px]` → 11.39sp。
                //   原先写死 14.sp，比正文（CCMText.body14 = 11.39sp）大 23%，
                //   思考链反而压过正文 —— 这是「丑」的首要来源。
                style = CCMText.body14.copy(
                    fontSize = 11.39.sp,
                    lineHeight = 18.03.sp,
                    letterSpacing = (-0.1504).sp,
                ),
                color = thinkingMuted(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (canToggle) {
                ChevronDownGlyph(
                    color = thinkingMuted(),
                    size = 11.04.dp,               // lucide size={12} × 0.92
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
        // 溢出判定：估算行数（每行 = 18.03dp，见正文行高），超过 184dp 即溢出。
        // ⚠ 阈值必须与下面 `.heightIn(max = 184.dp)` 保持一致：
        //   原来高度改成 184 但阈值还留 200，会出现「已被截断却判定为不溢出」→
        //   底部渐隐和「展开」按钮都不出现，用户看到一段莫名其妙断掉的文字。
        // Compose 里没有 scrollHeight，用「行数 × 行高」近似 —— 与源码阈值等价。
        val estimatedHeight = thinking.split('\n').size * 18.03f
        LaunchedEffect(thinking, isExpanded, isBodyExpanded) {
            isOverflowing = estimatedHeight > 184f
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Web 是 maxHeight:200px，但它在 #root 的 zoom:0.92 内 → 屏幕 184dp
                    .heightIn(max = if (isBodyExpanded) Dp.Unspecified else 184.dp)
                    .verticalScroll(bodyScroll),
            ) {
                Text(
                    text = thinking,
                    style = CCMText.body14.copy(
                        fontSize = 11.39.sp,   // text-[14px] → 11.39（与正文同规格）
                        lineHeight = 18.03.sp, // leading-[19.6px] × 0.92
                        letterSpacing = (-0.1504).sp,
                    ),
                    color = thinkingBody(),
                )
            }
            // 底部渐隐 h-12（仅未展开且溢出时）
            if (isOverflowing && !isBodyExpanded) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // h-12 在 767px 媒体查询里被 clamp(34px,10vw,46px) 命中
                        // → 39.30px，再 × zoom 0.92 = **36.16dp**（不是 48×0.92）
                        .height(36.16.dp)
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
                style = CCMText.body12.copy(
                    fontSize = 10.49.sp,
                    lineHeight = 14.72.sp,  // leading-[16px] × 0.92
                ),
                color = thinkingMuted().copy(alpha = 0.8f),
                modifier = Modifier
                    .padding(top = 7.36.dp)        // mt-[8px] × 0.92
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

/**
 * 紧凑状态行 —— 对应 `AssistantThinkingCompactStatus`。
 *
 * ## 与 Web 逐项对齐（原先差得最多的一处）
 * | 项 | Web | 原实现 | 现 |
 * |---|---|---|---|
 * | 左侧图形 | **活动指示器 28px**（星芒） | 事件图标 15px | 指示器 25.76dp |
 * | 文字字体 | `Anthropic_Serif_Text:Italic`（衬线斜体） | 无衬线常规 | 衬线斜体 |
 * | 字号 | `text-[14px]` → 11.39 | 13.sp | 11.39sp |
 * | 行高 | `leading-[21px]` → 19.32 | 18.sp | 19.32sp |
 * | 字距 | `tracking-[-0.07px]` | −0.1304 | −0.07sp |
 * | 思考中 | shimmer 流光 | 静态色 | shimmer |
 * | 文字出现 | **逐词打字机**（42ms/词） | 整句直接出现 | 打字机 |
 * | 上边距 | `mt-[14px]` → 12.88 | 0 | 12.88dp |
 * | 左内距 | `pl-[11px]` → 10.12 | 0 | 10.12dp |
 * | 最小高 | `min-h-[36px]` → 33.12 | 内容高 | 33.12dp |
 * | 间距 | `gap-[12px]` → 11.04 | 4.dp | 11.04dp |
 *
 * 其中「衬线斜体 + 打字机」是 Web 这一行**最可识别**的视觉特征（区别于正文的无衬线），
 * 也是原实现丢得最彻底的一项 —— 它让这行看起来像普通小字，而不是「正在思考」。
 *
 * ## 为什么用 [ActivityPhase.STREAMING]
 * Web 传的是 `phase={isThinking ? 'streaming' : 'done'}` —— 注意**不是** `waiting`。
 * 因为这一行本身就表示「已经在思考了」，用的是打字动画而非等待动画。
 */
@Composable
fun AssistantThinkingCompactStatus(
    event: AssistantThinkingEvent?,
    modifier: Modifier = Modifier,
    isThinking: Boolean = false,
) {
    if (event == null) return

    // ── 逐词打字机（对应 `useAnimatedStatusLabel`）────────────────────
    // Web 按空白切词（**保留分隔符**：`split(/(\s+)/)`），每 42ms 多显一个词。
    // 终止条件是「词数用完」而非固定时长 —— 长句子自然多跑几拍。
    val label = remember(event.label) { event.label.trim().ifEmpty { "正在深入思考，请稍候…" } }
    val tokens = remember(label) {
        // 对应 `tokenizeStreamingLabel`：按空白切分并保留空白段。
        // Kotlin 的 split 默认丢掉分隔符，用 Regex 的零宽后视模拟保留效果。
        label.split(Regex("(?<=\\s)")).filter { it.isNotEmpty() }
    }

    var visibleTokens by remember(label, isThinking) {
        mutableStateOf(if (isThinking) 1 else tokens.size)
    }

    LaunchedEffect(label, isThinking) {
        if (!isThinking) {
            visibleTokens = tokens.size
            return@LaunchedEffect
        }
        visibleTokens = 1
        while (visibleTokens < tokens.size) {
            delay(42L)   // 与 Web 的 `setInterval(…, 42)` 一致
            visibleTokens += 1
        }
    }

    val shown = tokens.take(visibleTokens).joinToString("")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 10.12.dp, top = 12.88.dp)   // pl-[11px] / mt-[14px] × 0.92
            .heightIn(min = 33.12.dp),                   // min-h-[36px] × 0.92
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.23.dp),   // gap-[12px] 命中 clamp → 7.23dp
    ) {
        // 28px 方框内居中放 28px 指示器 → 实际尺寸 28 × 0.92 = 25.76dp
        Box(
            modifier = Modifier.size(25.76.dp),
            contentAlignment = Alignment.Center,
        ) {
            AssistantActivityIndicator(
                size = 25.76.dp,
                phase = if (isThinking) ActivityPhase.STREAMING else ActivityPhase.DONE,
            )
        }
        // 衬线斜体 14px/21px tracking-[-0.07px]，思考中走 shimmer
        val statusStyle = TextStyle(
            fontFamily = CcmSerif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.39.sp,     // text-[14px] → clamp(11.5,3.15vw,14)=12.38 × 0.92
            lineHeight = 19.32.sp,   // leading-[21px] × 0.92
            letterSpacing = (-0.07).sp,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (isThinking) {
                TextShimmer(text = shown, style = statusStyle)
            } else {
                Text(
                    text = shown,
                    style = statusStyle,
                    color = if (CCMTheme.isDark) CCMTheme.colors.textMain else CompactStatusText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!event.meta.isNullOrEmpty()) {
            Text(
                text = event.meta,
                style = CCMText.body11.copy(fontSize = 10.12.sp, lineHeight = 13.80.sp),
                color = thinkingMuted(),
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

    // ★ translationY 的单位是 **像素**（graphicsLayer 跑在 draw 阶段，不经 Density 换算）。
    //   原先直接写 `4f`，在 2.75x 屏上只有 1.45dp —— 位移几乎看不出来，
    //   Web 的 `translateY(4px)` 是 4 CSS px，再被 #root 的 zoom:0.92 打折 → 屏幕 3.68dp。
    //   这里用 LocalDensity 换算回真实像素，保证物理观感与 Web 一致。
    val slideDistancePx = with(LocalDensity.current) { 3.68.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * slideDistancePx
            },
    ) {
        // 行首竖轨（index > 0 时可见）—— 段高 8px × 0.92 = 7.36dp
        RailSpacer(visible = index > 0, height = 7.36.dp)

        Row(modifier = Modifier.fillMaxWidth()) {
            // 图标列：w-20，图标 15，上内距 4（pt-[4px] 命中 clamp → 2px）
            Box(
                modifier = Modifier.width(18.40.dp).padding(top = 1.84.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                ThinkingEventIcon(
                    kind = event.kind,
                    size = 13.80.dp,               // thoughtChainIconStyle 15px × 0.92
                )
            }
            // 内容：pl-10 pt-2
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.20.dp, top = 1.84.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    // Web 是 `justify-between gap-[12px]`；label 用 flex-1 撑满，
                    // justify-between 实际不起作用，真正生效的是 gap。
                    // gap-[12px] **命中 clamp(5px,2vw,10px) → 7.86px** × 0.92 = 7.23dp
                    // （直接拿 12 × 0.92 会算成 11.04，偏大 53%）
                    horizontalArrangement = Arrangement.spacedBy(7.23.dp),
                ) {
                    if (isActive) {
                        // 活动行用 shimmer 文字（对应 `renderSparkStatusCopy` 的 TextShimmer 分支）
                        TextShimmer(
                            text = event.label,
                            style = CCMText.body14.copy(
                                fontSize = 11.39.sp,
                                lineHeight = 18.03.sp,
                                letterSpacing = (-0.1504).sp,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        Text(
                            text = event.label,
                            style = CCMText.body14.copy(
                                fontSize = 11.39.sp,
                                lineHeight = if (!muted) 18.03.sp else 18.40.sp,
                                letterSpacing = (-0.1504).sp,
                            ),
                            color = if (!muted) thinkingBody() else thinkingMuted(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (!event.meta.isNullOrEmpty()) {
                        Text(
                            text = event.meta,
                            style = CCMText.body12.copy(
                                fontSize = 10.49.sp,   // text-[12px] → clamp(11,2.9vw,12)=11.40 × 0.92
                                lineHeight = 15.46.sp, // leading-[16.8px] × 0.92
                            ),
                            color = thinkingMuted(),
                            modifier = Modifier.padding(top = 0.92.dp),   // pt-[1px] × 0.92
                        )
                    }
                }

                // detail（可选）
                if (!event.detail.isNullOrBlank()) {
                    // ★ Web 的 detail 用**正文规格**（`text-[14px] leading-[19.6px] text-[#373734]`），
                    //   不是 muted 小字。原先按 12sp + 弱化色渲染，层级比 Web 低一档。
                    Text(
                        text = event.detail,
                        style = CCMText.body14.copy(
                            fontSize = 11.39.sp,
                            lineHeight = 18.03.sp,
                            letterSpacing = (-0.1504).sp,
                        ),
                        color = thinkingBody(),
                        modifier = Modifier.padding(top = 1.84.dp),    // pt-[2px] × 0.92
                    )
                }

                // 搜索结果（web_search）
                event.results?.forEach { r ->
                    Column(modifier = Modifier.padding(top = 5.52.dp)) {
                        Text(
                            text = r.title,
                            style = CCMText.body12.copy(
                                fontSize = 10.49.sp,
                                lineHeight = 15.46.sp,
                            ),
                            color = thinkingBody(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!r.source.isNullOrEmpty()) {
                            Text(
                                text = r.source,
                                style = CCMText.body11.copy(
                                    fontSize = 10.12.sp,   // text-[11px] × 0.92
                                    lineHeight = 13.80.sp, // leading-[15px] × 0.92
                                ),
                                color = thinkingMuted(),
                            )
                        }
                    }
                }

                // 文件预览（write_preview）—— 最多 8 行，每行截 92 字符
                event.preview?.let { p ->
                    Spacer(Modifier.height(5.52.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(7.36.dp))   // rounded-[8px] × 0.92
                            .background(colors0())
                            .padding(7.36.dp),
                    ) {
                        Text(
                            text = p.title,
                            style = CCMText.body12.copy(
                                fontSize = 10.49.sp,
                                lineHeight = 15.46.sp,
                            ),
                            color = thinkingBody(),
                        )
                        previewLines(p.content).forEach { line ->
                            Text(
                                text = line,
                                style = CCMText.body11.copy(
                                    fontSize = 10.49.sp,   // Web 预览正文是 text-[12px]
                                    lineHeight = 14.72.sp, // leading-[16px] × 0.92
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = thinkingMuted(),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        // 行尾竖轨（index < total-1 时可见）
        RailSpacer(visible = index < total - 1, height = 7.36.dp)
    }
}

/** 竖轨占位 —— 对应 `renderRailSpacer`：固定高度 + 左侧 9.5px 处的 1px 竖线 */
@Composable
private fun RailSpacer(visible: Boolean, height: Dp) {
    Box(modifier = Modifier.fillMaxWidth().height(height)) {
        if (visible) {
            Box(
                modifier = Modifier
                    .padding(start = 8.74.dp)      // left-[9.5px] × 0.92
                    .width(1.dp)
                    .height(height)
                    .background(thinkingRail()),
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
 * 事件图标 —— 直接渲染 Web `figma-thinking-chain/` 下各 SVG 的**真实 path 数据**。
 *
 * ## 为什么从「Canvas 手绘」改成「读 path 资源」
 * 原实现用 `drawCircle` / `drawLine` 手绘了 7 个近似图形，注释里也写了「零变化下的已知偏差」。
 * 但实际观感差距比「近似」大得多：
 * - Web 的图标是**实心填充**（`fill="var(--fill-0, #7B7974)"`），不是描边；
 *   手绘的描边空心圆看上去像未选中的 radio，而不是「思考步骤」。
 * - viewBox 是 **20×20**，`stroke-width: 1.2`。手绘版按 15×15 自算比例，粗细对不上。
 * - `thought-chain-direct` 的 fill 是 `#131313`（**比其它图标深**），手绘版统一用传入色，丢了这个层级。
 *
 * 现在 6 个 SVG 的 path 已提取到 `res/raw/icon_think_*.txt`（共 10.6KB，比一张 webp 还小），
 * 本函数逐条解析后用 `drawPath` 渲染 —— 与原图**逐点一致**，不再是近似。
 *
 * ## 坐标处理
 * path 自带 20×20 viewBox 坐标，这里按 `size / 20` 缩放。
 * 不再需要调用方关心比例 —— 传 [size] 即可，图标自然铺满。
 *
 * ## 多 path 图标
 * `step` 有 2 条、`write-preview` 有 5 条（文档轮廓 + 折角 + 三根横线）。
 * 资源里每行一条 path，全部绘制（不是只画第一条）。
 *
 * ## 缓存
 * path 解析结果按 kind 缓存在 `remember(kind)`，与 sprite 同一个思路：
 * 这些字符串 1~3KB，每帧重解析会在滚动时拖慢列表。
 *
 * @param color 覆盖填充色。传 [Color.Unspecified] 时用 SVG 自带色
 *   （`direct` 用 `#131313`，其余用 `#7B7974`）—— 对齐 Web 的 `var(--fill-0, …)`。
 */
@Composable
private fun ThinkingEventIcon(kind: ThinkingEventKind, size: Dp, color: Color = Color.Unspecified) {
    val context = LocalContext.current

    val paths: List<Path> = remember(kind) {
        val resName = when (kind) {
            ThinkingEventKind.DIRECT -> "icon_think_direct"
            ThinkingEventKind.FOCUS, ThinkingEventKind.SKILL, ThinkingEventKind.TOOL -> "icon_think_step"
            ThinkingEventKind.WEB_SEARCH -> "icon_think_web_search"
            ThinkingEventKind.WRITE_PREVIEW -> "icon_think_write_preview"
            ThinkingEventKind.DONE -> "icon_think_done"
        }
        try {
            val raw = context.resources.openRawResource(
                context.resources.getIdentifier(resName, "raw", context.packageName),
            ).bufferedReader().use { it.readText() }
            raw.split('\n').mapNotNull { line ->
                val d = line.trim()
                if (d.isEmpty()) null
                else PathParser.createPathFromPathData(d)?.asComposePath()?.let { Path().apply { addPath(it) } }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // 资源缺失时回退到「空心圆」—— 不崩、不空白
    if (paths.isEmpty()) {
        // ★ CI #230 修：thinkingMuted() 是 @Composable，不能进 Canvas{}（DrawScope）
        //   —— 在 Canvas 外先取值。
        val fallbackColor = if (color == Color.Unspecified) thinkingMuted() else color
        Canvas(modifier = Modifier.size(size)) {
            drawCircle(
                color = fallbackColor,
                radius = this.size.width * 0.3f,
                style = Stroke(width = 1.2.dp.toPx()),
            )
        }
        return
    }

    // 每个 kind 的固有填充色 —— 对应 SVG 里的 `var(--fill-0, …)` 回退值。
    //
    // ★ `direct` 的固有色是 #131313（比其它图标深），这个差异是原图刻意做的层级，
    //   但 **暗色下它会变成隐形的**（#131313 落在 #1F1F1E 背景上对比度 ≈ 1.05:1）。
    //   Web 同样有此问题（fill 是内联属性，吃不到 `dark:` 变体），属已知遗漏；
    //   Compose 侧没这个限制，暗色下统一走 textMain / textSecondary 修掉它。
    val intrinsic = if (kind == ThinkingEventKind.DIRECT) Color(0xFF131313) else ThinkMutedLight
    val fillColor = when {
        color != Color.Unspecified -> color
        CCMTheme.isDark -> if (kind == ThinkingEventKind.DIRECT) {
            CCMTheme.colors.textMain
        } else {
            CCMTheme.colors.textSecondary
        }
        else -> intrinsic
    }

    Canvas(modifier = Modifier.size(size)) {
        val scaleF = this.size.width / ICON_VIEW_BOX   // 20×20 viewBox → 目标尺寸
        scale(scaleF, scaleF, pivot = Offset.Zero) {
            paths.forEach { drawPath(it, fillColor) }
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

/**
 * 亮色正文 `#373734` —— Web 侧 `text-[#373734] dark:text-claude-text`
 *
 * ## 为什么拆成「亮色常量 + 暗色访问器」
 * 原实现把 `#373734` 写死。暗色主题下它落在 `#1F1F1E` 背景上，
 * 对比度约 **1.4:1**（WCAG AA 正文要求 4.5:1）—— 思考链几乎不可见。
 * Web 用 `dark:text-claude-text` 切到 `--text-claude-main`（暗色 `#FFFFFF`）。
 * Compose 顶层 `val` 读不到 CompositionLocal，故改为 @Composable 访问器。
 */
private val ThinkBodyLight = Color(0xFF373734)

/** 亮色弱化 `#7b7974` —— Web 侧 `text-[#7b7974] dark:text-claude-textSecondary` */
private val ThinkMutedLight = Color(0xFF7B7974)

/** 亮色竖轨 `rgba(31,31,30,0.15)` —— Web 侧 `bg-[rgba(31,31,30,0.15)]` */
private val ThinkRailLight = Color(0x261F1F1E)

/** 暗色竖轨 `rgba(248,248,246,0.18)` —— Web 侧 `dark:bg-[rgba(248,248,246,0.18)]` */
private val ThinkRailDark = Color(0x2EF8F8F6)

/** 正文色（暗色感知）—— Web 侧 `text-[#373734] dark:text-claude-text` */
@Composable
private fun thinkingBody(): Color =
    if (CCMTheme.isDark) CCMTheme.colors.textMain else ThinkBodyLight

/** 弱化色（暗色感知）—— Web 侧 `text-[#7b7974] dark:text-claude-textSecondary` */
@Composable
private fun thinkingMuted(): Color =
    if (CCMTheme.isDark) CCMTheme.colors.textSecondary else ThinkMutedLight

/** 竖轨色（暗色感知）—— Web 侧 `railClassName` */
@Composable
private fun thinkingRail(): Color =
    if (CCMTheme.isDark) ThinkRailDark else ThinkRailLight

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

    // ★ 颜色必须在 remember 之外求值：
    //   `thinkingMuted()` 是 @Composable，放进 remember 的 lambda 里会编译失败
    //   （remember 的 calculation 不是 @Composable 作用域）。
    val shimmerEdgeColor = shimmerEdge()
    val shimmerBodyColor = shimmerBody()

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

    val brush = remember(width, t, shimmerEdgeColor, shimmerBodyColor) {
        if (width <= 0f) {
            SolidColor(shimmerEdgeColor)
        } else {
            val start = -2.4f * width + 2.4f * width * t
            androidx.compose.ui.graphics.Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to shimmerEdgeColor,
                    0.48f to shimmerBodyColor,
                    1.00f to shimmerEdgeColor,
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

/**
 * 流光渐变端点 `rgba(123,121,116,0.72)` —— Web 侧硬编码的亮色值。
 *
 * ## 为什么这里比 Web 多一套暗色值
 * Web 的 `TextShimmer` 用的是**内联 style**，而内联 style 无法写 `dark:` 变体
 * （Tailwind 的 `dark:` 靠类选择器，内联 style 优先级更高、根本进不去）。
 * 所以 Web 暗色下这条流光仍是深色渐变，落在 `#1F1F1E` 背景上几乎看不见 ——
 * 这是**技术限制导致的遗漏，不是设计意图**。
 * Compose 侧没有这个限制，故补齐暗色：用 `textSecondary` / `textMain` 同样取
 * 0.72 / 0.92 的 alpha，保持 Web 那套「暗→亮→暗」的流动节奏不变。
 */
private val ShimmerMutedLight = Color(0xB87B7974)

/** 流光渐变中点 `rgba(55,55,52,0.92)` —— 亮色 */
private val ShimmerBodyLight = Color(0xEB373734)

/** 流光端点（暗色感知） */
@Composable
private fun shimmerEdge(): Color =
    if (CCMTheme.isDark) CCMTheme.colors.textSecondary.copy(alpha = 0.72f)
    else ShimmerMutedLight

/** 流光中点（暗色感知） */
@Composable
private fun shimmerBody(): Color =
    if (CCMTheme.isDark) CCMTheme.colors.textMain.copy(alpha = 0.92f)
    else ShimmerBodyLight
