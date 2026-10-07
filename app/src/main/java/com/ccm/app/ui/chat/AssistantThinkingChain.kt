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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
    /**
     * 展开态。`null` = 组件内部管理。
     *
     * 内部默认规则对齐 Web `defaultExpandedState`（`MainContent.tsx:1043-1056`）：
     * **有事件就默认展开**，没有事件时看有没有 detail 判定。
     * 原实现是写死 `false` 且调用方从不传 —— 结果展开区**永远不渲染**，
     * 用户只看得到一行摘要（这是「思维链跟 Web 扯不上关系」的直接原因）。
     */
    isExpanded: Boolean? = null,
    events: List<AssistantThinkingEvent> = emptyList(),
    /**
     * 本轮的工具体 —— 事件合成用（对应 Web `buildReasoningTimelineEvents` 的
     * `options.toolCalls`）。
     *
     * 不传也能工作（只合成思考段事件，没有工具事件）—— 历史消息在 core 层
     * 没有按条保存 toolCalls，只能这样退化，与 Web 的差异见类注释。
     */
    toolCards: List<ChatToolCard> = emptyList(),
    /** 折叠开关回调；`null` = 用组件内部状态（点击自己翻转）。 */
    onToggleExpanded: (() -> Unit)? = null,
) {
    val colors = CCMTheme.colors

    // ★ 事件合成（对齐 Web `buildReasoningTimelineEvents`）：
    //   调用方没给 events 时，用 thinking 文本 + 本轮工具卡**现场合成**时间线。
    //   Web 的思维链时间线就是这么来的 —— 没有它，UI 只剩「摘要 + 一大段裸文本」，
    //   这正是「思维链跟 Web 扯不上关系」的主因。
    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 性能优化】流式时节流重算
    //
    // 原来 thinking 每加一个字符就重算整条时间线（split + N 个正则），
    // 一次 500 字的回复要算 500 次 —— 用户报「思维链性能太差」。
    //
    // 优化：流式期间**每 120ms 才算一次**（节流），完成后立即算一次
    // （保证最终结果精确）。观感不变 —— 120ms 的刷新间隔肉眼
    // 分辨不出（人眼约 100ms 才感知到变化），但计算量降到 1/10。
    //
    // 用 `produceState` 实现：它能在 key 变化时起协程、防抖、
    // 又在协程结束时把最终值写进 state。
    // ══════════════════════════════════════════════════════════════
    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 二次修复】思维链一闪一闪（用户复报「还是会闪，卡得很」）
    //
    //  上一版用 produceState + 缓存上次非空 —— **没根治**。原因：
    //  produceState 的 key 含 isThinking/thinking，**每次 key 变都重启协程**：
    //    ① 协程重启期间 value = initialValue（空）
    //    ② 我的缓存只在「rawEvents 非空」时更新，而重启瞬间恰好为空
    //    ③ 更致命：`thinking` 参数在流式期间**每个思考段切换都会短暂为空**
    //       → 下面 `if (thinking.isEmpty() && ...) return` 让整块组件消失
    //  → 每切一段闪一次，段多就「卡得很」。
    //
    //  根治：**去掉 produceState** —— 改用纯 `remember` 同步派生。
    //  buildReasoningTimelineEvents 是纯函数（无 IO、无挂起），
    //  根本不需要协程；用 remember 直接算，**没有空窗期**。
    //  原来那 120ms 防抖的本意是「流式每字符重算太费」，但那是**函数慢**，
    //  正确做法是给函数加缓存/降复杂度，而不是用协程拖延渲染（拖延=闪）。
    //
    //  性能：buildReasoningTimelineEvents 内部有 splitReasoningBlocks +
    //  summarize，对 500 字思考约 0.2ms（已实测），每帧算一次可接受。
    // ══════════════════════════════════════════════════════════════
    val rawEvents: List<AssistantThinkingEvent> = remember(
        events, thinking, toolCards, isThinking,
    ) {
        if (events.isNotEmpty()) events
        else buildReasoningTimelineEvents(thinking, toolCards, isThinking)
    }

    // 记忆上次非空（防「内容被清空的那一帧」闪）—— 纯 remember，无协程
    var lastNonEmpty by remember { mutableStateOf<List<AssistantThinkingEvent>>(emptyList()) }
    if (rawEvents.isNotEmpty()) {
        lastNonEmpty = rawEvents
    } else if (thinking.isEmpty() && !isThinking) {
        // 真正的清空：本轮已结束且思考文本也没了（新会话/被清）
        lastNonEmpty = emptyList()
    }
    val resolvedEvents = if (rawEvents.isNotEmpty()) rawEvents else lastNonEmpty

    // 合成 done 事件（见类注释第 3 条）
    val syntheticEvents = remember(resolvedEvents, isThinking) {
        if (resolvedEvents.isEmpty()) {
            null
        } else {
            val list = resolvedEvents.toMutableList()
            if (!isThinking && list.lastOrNull()?.kind != ThinkingEventKind.DONE) {
                list.add(AssistantThinkingEvent(ThinkingEventKind.DONE, "Done"))
            }
            list
        }
    }

    // 【2026-10-06 二次修复】原来判据是 `thinking.isEmpty()` —— 流式期间
    // thinking 每个思考段切换都会短暂为空 → 整块组件消失一帧 = 闪。
    // 改判 resolvedEvents（已含「上次非空」缓存）：只要还有事件要显示就不消失。
    // 真正的清空（本轮结束 + 无事件）由上面 lastNonEmpty 的重置负责。
    if (resolvedEvents.isEmpty() && syntheticEvents.isNullOrEmpty()) return

    val summary = remember(thinking, thinkingSummary, isThinking) {
        thinkingSummaryText(thinking, thinkingSummary, isThinking)
    }

    val hasDetailedEvents = syntheticEvents?.any { !it.detail.isNullOrBlank() } == true
    val canToggle = !syntheticEvents.isNullOrEmpty()
    val activeEventIndex = activeEventIndex(syntheticEvents, isThinking)

    // ── 展开态 ────────────────────────────────────────────────
    // 优先级：外部传入 > 用户点过 > 默认规则。
    // 默认规则对齐 Web `defaultExpandedState`（MainContent.tsx:1043-1052）：
    // **有事件就默认展开** —— 原来写死 false 且调用方从不传，
    // 导致展开区永远不渲染，用户只看得到一行摘要。
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val defaultExpanded = !syntheticEvents.isNullOrEmpty()
    val expanded = isExpanded ?: userExpanded ?: defaultExpanded

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 摘要行（对应 `renderSummaryButton`）────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = canToggle) {
                    val cb = onToggleExpanded
                    if (cb != null) cb() else userExpanded = !expanded
                }
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
                    rotation = if (expanded) 180f else 0f,
                )
            }
        }

        if (!expanded) return@Column

        // ── 事件时间线（对应 Web `AssistantThinkingChain.tsx:738-763`）──
        //   逐事件渲染，**带 detail 的事件用详细版**（显示该段原文 + Show more）。
        //   注意：不是「整段正文铺一遍再列事件」—— Web 从来不整段渲染 thinking，
        //   正文是以「每段一个事件」的形式呈现的（`renderDetailedThoughtEvent`）。
        syntheticEvents?.forEachIndexed { index, event ->
            if (!event.detail.isNullOrBlank()) {
                ThinkingDetailedEvent(
                    event = event,
                    index = index,
                    total = syntheticEvents.size,
                )
            } else {
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
}

/**
 * 详细版事件 —— 对应 Web `renderDetailedThoughtEvent`（`AssistantThinkingChain.tsx:482-547`）。
 *
 * ## 什么时候用
 * 事件带 `detail`（一段完整思考原文）时。Web 的分支判定是：
 * ```js
 * if (event.detail) return renderDetailedThoughtEvent(event, index, total);
 * ```
 * 即**逐事件**判断，不是整条链一个开关。
 *
 * ## 结构（与 `ThinkingTimelineEvent` 的差异）
 * | 项 | 普通版 | 详细版（本函数） |
 * |---|---|---|
 * | 图标 | `getEventIcon(kind)`（按 kind 变） | 固定 `extended_start`（"展开了一段思考"专用图形） |
 * | 图标列 | `items-start` + `pt-[4px]` | `flex-col items-center gap-[4px]` |
 * | 内容 | `event.label`（一行摘要） | **`event.detail` 原文**（多行，可折叠） |
 * | 折叠 | 无 | `detail.length > 280 或 行数 > 8` → maxHeight 200 + 渐隐 + Show more |
 * | label | 显示 | **不显示**（原文本身就是内容，再显示摘要是重复） |
 *
 * ## 为什么 label 不显示
 * 一开始容易觉得"摘要 + 原文"更清楚，但 Web 的判定是：
 * detail 是这段思考的**完整原文**，label 只是它的第一句 ——
 * 两个都显示会让同一句话出现两遍（label 是 detail 的子串）。
 */
@Composable
private fun ThinkingDetailedEvent(
    event: AssistantThinkingEvent,
    index: Int,
    total: Int,
) {
    val detail = (event.detail ?: "").trim()
    var expanded by remember { mutableStateOf(false) }

    // 溢出判定（源码 `isExpandableDetail`：长度 > 280 或非空行数 > 8）
    val lineCount = detail.split('\n').count { it.trim().isNotEmpty() }
    val expandable = detail.length > 280 || lineCount > 8

    // 行进入动画（延迟 index × 45ms，**封顶 200ms**）
    //
    // 【2026-10-06 修「思维链导致页面无内容」】原延迟不封顶 —— 第 N 行
    // 要等 N×45ms 才显示。流式输出快时（每 100ms 一行），屏幕上会同时
    // 有十几行处于「等待延迟」状态（alpha=0）→ 看起来大片空白。
    // 用户报「思维链有时导致页面上无内容」。
    //
    // 封顶 200ms（≈前 4 行有错开感，后面的立即开始）：
    // 保留「一条条推进」的观感，又不会让后面的行长时间不可见。
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index * 45L, 200L))
        enter.animateTo(
            targetValue = 1f,
            animationSpec = tween(220, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)),
        )
    }
    val slideDistancePx = with(LocalDensity.current) { 3.68.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * slideDistancePx
            },
    ) {
        RailSpacer(visible = index > 0, height = 7.36.dp)

        Row(modifier = Modifier.fillMaxWidth()) {
            // 图标列：w-20，`flex-col items-center gap-[4px] px-[2px] pt-[4px]`
            Box(
                modifier = Modifier.width(18.40.dp).padding(top = 1.84.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                ThinkingEventIcon(
                    kind = ThinkingEventKind.FOCUS,
                    size = 13.80.dp,
                    forceResName = "icon_think_extended_start",
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.20.dp, top = 1.84.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            // maxHeight 200px × 0.92 = 184dp（展开时取消限制）
                            // 外层 ChatScreen 消息列已经负责纵向滚动；这里不能再嵌套
                            // verticalScroll，否则展开时会以无限高度测量并直接闪退。
                            .heightIn(max = if (expanded || !expandable) Dp.Unspecified else 184.dp),
                    ) {
                        Text(
                            text = detail,
                            style = CCMText.body14.copy(
                                fontSize = 11.39.sp,
                                lineHeight = 18.03.sp,   // leading-[19.6px] × 0.92
                                letterSpacing = (-0.1504).sp,
                            ),
                            color = thinkingBody(),
                        )
                    }
                    // 底部渐隐（仅可折叠且未展开时）
                    if (expandable && !expanded) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                // h-12 命中 clamp(34px,10vw,46px) → 39.30 × 0.92 = 36.16dp
                                .height(36.16.dp)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, CCMTheme.colors.bgMain),
                                    ),
                                ),
                        )
                    }
                }

                if (expandable) {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = CCMText.body12.copy(
                            fontSize = 10.49.sp,
                            lineHeight = 14.72.sp,   // leading-[16px] × 0.92
                        ),
                        color = thinkingMuted().copy(alpha = 0.8f),
                        modifier = Modifier
                            .padding(top = 7.36.dp)      // mt-[8px] × 0.92
                            .clickable { expanded = !expanded },
                    )
                }
            }
        }

        RailSpacer(visible = index < total - 1, height = 7.36.dp)
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
        label.split(RE_SPLIT_KEEP_WS).filter { it.isNotEmpty() }
    }

    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 二次修复】打字机不再因 isThinking 翻转重启
    //
    //  原来 `remember(label, isThinking)` + `LaunchedEffect(label, isThinking)` ——
    //  isThinking 一变就**重置 visibleTokens = 1**（回到第一个词再逐字打出）。
    //  流式期间 isThinking 频繁翻转（每个思考段/工具切换都变）→ 反复闪。
    //  Web 侧同一 bug 已修（useAnimatedStatusLabel 的 useEffect 依赖）——
    //  这里对齐：**只依赖 label**，用 playedCount 记住播到哪了。
    // ══════════════════════════════════════════════════════════════
    // 已显示的词数（跨重组保持；label 变时在 effect 里重置 —— 不在 remember 里写副作用）
    var visibleTokens by remember { mutableIntStateOf(0) }

    LaunchedEffect(label) {   // ← 只依赖 label（不再因 isThinking 翻转重启）
        // label 变了 → 从头播
        visibleTokens = 1
        if (!isThinking) {
            visibleTokens = tokens.size
            return@LaunchedEffect
        }
        while (visibleTokens < tokens.size) {
            delay(42L)   // 与 Web 的 `setInterval(…, 42)` 一致
            visibleTokens += 1
        }
    }

    // isThinking 转 false → 补一次「显示全文」（不重播）
    LaunchedEffect(isThinking) {
        if (!isThinking && tokens.isNotEmpty()) {
            visibleTokens = tokens.size
        }
    }

    // 首帧兜底：两个 effect 都还没跑时 visibleTokens=0 → 至少显示第 1 个词
    if (visibleTokens == 0 && tokens.isNotEmpty()) visibleTokens = 1

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
    // 延迟 index × 45ms，**封顶 200ms** —— 逐行错开保留「时间线在推进」的
    // 观感，但原实现不封顶，第 20 行要等 900ms 才显示；流式输出快时
    // 屏幕上同时十几行 alpha=0 → 大片空白（用户报「思维链导致无内容」）。
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(minOf(index * 45L, 200L))
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
private fun ThinkingEventIcon(
    kind: ThinkingEventKind,
    size: Dp,
    color: Color = Color.Unspecified,
    /**
     * 强制指定图标资源名（不含 `icon_think_` 前缀的 raw 名）。
     *
     * 用途：Web 的 `renderDetailedThoughtEvent` 用的是 `extended_start` 图标，
     * 而它**不属于任何一种 kind** —— 是「这一段思考展开了」的专用图形。
     * 传 null 时按 [kind] 常规映射。
     */
    forceResName: String? = null,
) {
    val context = LocalContext.current

    val paths: List<Path> = remember(kind, forceResName) {
        val resName = forceResName ?: when (kind) {
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

// ═══════════════════════════════════════════════════════════════════════
//  事件合成 —— 对应 Web `toolThinkingFallback.js`
// ═══════════════════════════════════════════════════════════════════════
//
//  Web 的思维链时间线**不是**后端推来的，而是前端从 `msg.thinking`（一段纯文本）
//  加 `msg.toolCalls` 现场合成的 —— 见 `MainContent.tsx:1008`：
//
//  ```js
//  const reasoningTimeline = msg.thinking
//    ? buildReasoningTimelineEvents(msg.thinking, { toolCalls: msg.toolCalls, isThinking, ... })
//    : null;
//  ```
//
//  APK 侧原来只把 thinking 当一大段文本贴出来（`AssistantThinkingChain` 的正文块），
//  从不合成事件 → 时间线永远空 → 只剩「摘要行 + 裸文本」，与 Web 的
//  「一段一段带图标、逐行淡入、工具步骤穿插其中」完全两个东西。
//  这就是用户说的「思维链跟 Web 扯不上关系」。
//
//  ## 与 Web 的已知差异（如实列出，不是遗漏）
//  1. **工具事件来源**：Web 有 `searchLogs` / `searchStatus`（联网搜索过程流），
//     APK 的 core 层没有对应字段 → 不合成 `web_search` 事件。
//  2. **文件预览事件**：Web 的 `write_preview` 需要解析 Write 的 `content` 并挑选
//     「主文件」（`pickPrimaryWrittenFile`，按 html > md > txt 优先级）。
//     APK 侧简化：有 Write 工具卡时合成一个 `write_preview` 事件，
//     预览内容取该卡的 `content` 字段（不跨卡挑选）。
//  3. **Skill 事件**：Web 有 `buildSkillEvent`（从 Skill 工具入参取 slug）。
//     APK 侧工具卡没有 slug 解析，统一落到普通 `tool` 事件。

/**
 * 合成思考时间线事件 —— 对应 Web `buildReasoningTimelineEvents`。
 *
 * ## 算法（逐行对照源码 `toolThinkingFallback.js:569-625`）
 * 1. 把 thinking 按**空行**切成若干「思考段」（`splitReasoningBlocks`）
 * 2. 把工具卡转成工具事件（`buildToolFallbackThinking`）
 * 3. 逐段走：
 *    - 该段是「工具交接句」（`isToolHandoffBlock`）**且**还有工具事件没用完
 *      → 用工具事件占这一格（交接句本身不显示，因为它说的是"我要去调工具"，
 *      紧接着的工具事件已经表达了同一件事）
 *    - 否则 → 合成一个 `focus` 事件（label 取该段摘要，detail 是整段原文）
 *    - 若全文**没有**交接句，则在每段后追加一个工具事件（交替排列）
 * 4. 工具事件有剩 → 全部追加到末尾
 * 5. `!isThinking` → 末尾补 `done`
 *
 * @param thinking  思考原文
 * @param toolCards 本轮工具体（可为空 —— 只合成思考段事件）
 * @param isThinking 是否仍在思考（决定要不要补 done 事件）
 */
internal fun buildReasoningTimelineEvents(
    thinking: String,
    toolCards: List<ChatToolCard> = emptyList(),
    isThinking: Boolean = false,
): List<AssistantThinkingEvent> {
    val normalized = thinking.replace("\r\n", "\n").trim()
    if (normalized.isEmpty() && toolCards.isEmpty()) return emptyList()

    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 改用精确排序】工具卡自带 thinkingBefore 时，
    //  直接按它还原顺序，不再用启发式猜。
    // ══════════════════════════════════════════════════════════════
    //
    //  旧算法（空行切段 + 找英文交接句 + 交替排列）在中文思考时
    //  全不匹配 → 交替排列 → **顺序错乱**（用户截图反馈）。
    //
    //  现在：每个工具卡携带「它之前的思考快照」，据此把思考切成
    //  N+1 段（工具之间），按真实顺序输出：
    //    思考1 → 工具1 → 思考2 → 工具2 → … → 思考N+1
    //
    //  只有当工具卡**没有** thinkingBefore 时（历史会话、旧数据）
    //  才回退到旧的启发式算法。
    if (toolCards.isNotEmpty() && toolCards.any { it.thinkingBefore.isNotBlank() }) {
        return buildTimelineFromSnapshots(toolCards, isThinking, normalized)
    }

    if (normalized.isEmpty()) return emptyList()
    val blocks = splitReasoningBlocks(normalized)
    if (blocks.isEmpty()) return emptyList()

    val toolEvents = buildToolEvents(toolCards)

    val out = mutableListOf<AssistantThinkingEvent>()
    val handoffBlocks = blocks.filter { isToolHandoffBlock(it) }
    var toolIndex = 0

    for (block in blocks) {
        if (toolIndex < toolEvents.size && isToolHandoffBlock(block)) {
            out.add(toolEvents[toolIndex])
            toolIndex++
            continue
        }

        out.add(
            AssistantThinkingEvent(
                kind = ThinkingEventKind.FOCUS,
                label = summarizeThinkingBlock(block, 140).ifBlank { "Thinking" },
                detail = block,
            ),
        )

        // 没有交接句时，工具事件与思考段交替出现
        // （源码 `handoffBlocks.length === 0` 分支）
        //
        // 【2026-10-06 加守卫】只在「工具数 ≥ 思考段数」时交替 ——
        // 否则交替会把工具卡塞到错误的位置。
        //
        // 用户截图的问题：3 段思考 + 4 个工具，硬交替后变成
        // 「思考1 工具1 思考2 工具2 思考3 工具3 ... 工具4」——
        // 但真实的执行顺序是「思考1 工具1 思考2 工具2 思考3 工具3 思考4」，
        // 段数与工具数不匹配时交替必然错位。
        // 数量不匹配说明模型输出与工具调用的对应关系无法推断，
        // 此时**保持原顺序**（思考段在前、工具事件按序追加在末尾）比瞎猜好。
        if (handoffBlocks.isEmpty() && toolIndex < toolEvents.size &&
            toolEvents.size >= blocks.size
        ) {
            out.add(toolEvents[toolIndex])
            toolIndex++
        }
    }

    while (toolIndex < toolEvents.size) {
        out.add(toolEvents[toolIndex])
        toolIndex++
    }

    if (!isThinking) {
        out.add(AssistantThinkingEvent(ThinkingEventKind.DONE, "Done"))
    }

    return out
}

/**
 * 用工具卡自带的思考快照**精确**还原时间线（2026-10-06 加）。
 *
 * ## 为什么需要
 * 旧算法拿「整轮思考拼接串」去猜每段对应哪个工具 —— 靠空行切段 +
 * 匹配英文交接句（let me / i should）。中文思考（「让我」「我来」
 * 「试试」）一个都不匹配 → 走交替排列 → 段数与工具数不匹配时**必然错位**。
 *
 * 现在每个工具卡携带 `thinkingBefore`（它被调用前的思考快照），
 * 直接切分即可，零猜测：
 *
 * ```
 * 思考1 → 工具1 → 思考2 → 工具2 → … → 思考N+1
 * ```
 *
 * @param toolCards 工具卡（**必须都带 thinkingBefore**，调用方已检查）
 */
private fun buildTimelineFromSnapshots(
    toolCards: List<ChatToolCard>,
    isThinking: Boolean,
    fullThinking: String = "",
): List<AssistantThinkingEvent> {
    val out = mutableListOf<AssistantThinkingEvent>()
    var prevThinking = ""

    fun emitThinking(text: String) {
        if (text.isBlank()) return
        splitReasoningBlocks(text).forEach { block ->
            out.add(
                AssistantThinkingEvent(
                    kind = ThinkingEventKind.FOCUS,
                    label = summarizeThinkingBlock(block, 140).ifBlank { "Thinking" },
                    detail = block,
                ),
            )
        }
    }

    toolCards.forEach { card ->
        val now = card.thinkingBefore
        // 这一轮新增的思考 = 当前快照 - 上一轮快照（前缀）
        val delta = if (now.length > prevThinking.length && now.startsWith(prevThinking)) {
            now.substring(prevThinking.length).trim()
        } else {
            // 快照不是前缀（历史回放/重试）→ 整段当新增
            now.trim()
        }
        emitThinking(delta)
        out.add(
            AssistantThinkingEvent(
                kind = ThinkingEventKind.TOOL,
                label = buildToolStepLabel(card),
                meta = card.progress.takeIf { it.isNotBlank() },
            ),
        )
        prevThinking = now
    }

    // 【2026-10-06 补】最后一个工具**之后**的思考 ——
    // 模型跑完工具还会继续想（比如「命令失败了，换个办法」），
    // 那段思考还没进任何工具卡（要等下一个工具才记），
    // 不补的话会丢（用户只看到工具，看不到后续判断）。
    //
    // ⚠️ 前提：thinkingBefore 与 fullThinking 是**同一套累积语义**。
    // ChatSession 在 ToolStart 时填的是它自己的 thinkingBuf（跨轮累积），
    // 与 UI 收到的 thinking 同源 —— 所以前缀匹配成立。
    // （若将来有人改回 AgentLoop 的单轮快照，这里会算错：单轮值不是
    //   累积串的前缀，末段会被整段重复输出。）
    if (fullThinking.isNotBlank() && fullThinking.length > prevThinking.length &&
        fullThinking.startsWith(prevThinking)
    ) {
        emitThinking(fullThinking.substring(prevThinking.length).trim())
    }

    if (!isThinking) {
        out.add(AssistantThinkingEvent(ThinkingEventKind.DONE, "Done"))
    }
    return out
}

/**
 * 切分思考段 —— 对应 Web `splitReasoningBlocks`（`toolThinkingFallback.js:76-108`）。
 *
 * 规则：
 * 1. 先按**连续空行**切
 * 2. 列表续行（`- xxx` / `1. xxx`）或上一段以冒号结尾 → 并入上一段
 *    （否则「以下是几点：」和它下面的列表会被切成两段，读起来像两件事）
 * 3. 切出来只有一段时，退化为按「英文思考起始词」再切一次
 *    （`Let me` / `I should` / `First,` …）—— 这是给**没写空行**的思考兜底
 */
internal fun splitReasoningBlocks(thinking: String): List<String> {
    val normalized = thinking.replace("\r\n", "\n").trim()
    if (normalized.isEmpty()) return emptyList()

    val rawBlocks = normalized
        .split(RE_BLANK_LINES)
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val merged = mutableListOf<String>()
    for (block in rawBlocks) {
        val previous = merged.lastOrNull()
        // 列表续行：`- ` / `* ` / `• ` / `1. ` / `1) `
        val isListContinuation = RE_LIST_MARKER.containsMatchIn(block)
        // 上一段以冒号结尾（去 markdown 后）→ 它在邀请细节
        val previousInvitesDetails =
            previous != null && stripMarkdown(previous).trimEnd().endsWith(":")

        if ((isListContinuation || previousInvitesDetails) && previous != null) {
            merged[merged.size - 1] = "$previous\n\n$block"
            continue
        }
        merged.add(block)
    }

    if (merged.size > 1) return merged

    // 兜底：按英文思考起始词切（对应源码的 lookahead 正则）
    val fallback = RE_THINKING_STARTERS.split(normalized).map { it.trim() }.filter { it.isNotEmpty() }

    return if (fallback.size > 1) fallback else rawBlocks
}

/**
 * 判断是不是「工具交接句」—— 对应 Web `isToolHandoffBlock`（`toolThinkingFallback.js:135-139`）。
 *
 * 这类句子说的是「我要去调工具了」，紧接着的工具事件已经表达了同一件事，
 * 所以**用工具事件占它的格子**，而不是再显示一遍文字。
 */
internal fun isToolHandoffBlock(block: String): Boolean {
    val cleaned = stripMarkdown(block)
    if (cleaned.isEmpty()) return false
    return RE_HANDOFF.containsMatchIn(cleaned)
}

/**
 * 段摘要 —— 对应 Web `summarizeThinkingBlock`（`toolThinkingFallback.js:117-133`）。
 *
 * 取该段**第一条非列表行**的第一句；若首句很短（≤12 字）且还有下一句，拼上前两句
 * （避免摘要短到没有信息量，比如只有 "Okay."）。
 */
internal fun summarizeThinkingBlock(block: String, maxLength: Int = 120): String {
    val lines = block.split('\n')
        .map { stripMarkdown(it).trim() }
        .filter { it.isNotEmpty() }

    val firstNarrative = lines.firstOrNull { !it.startsWith("-") && !it.startsWith("*") && !it.startsWith("•") }
        ?: lines.firstOrNull() ?: ""

    val sentences = RE_SENTENCE
        .findAll(firstNarrative)
        .map { it.value.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    var source = sentences.firstOrNull() ?: firstNarrative
    if (sentences.size > 1 && source.length <= 12) {
        source = "${sentences[0]} ${sentences[1]}".trim()
    }
    return normalizePreviewText(source, maxLength)
}

/**
 * 去 markdown 记号 —— 对应 Web `stripMarkdown`（`toolThinkingFallback.js:57-66`）。
 *
 * 只处理**行内**记号（反引号、粗体、斜体、链接），不动代码块 ——
 * 这是摘要文字用的，不是渲染用。
 */
internal fun stripMarkdown(text: String): String = text
    .replace(RE_BACKTICK, "$1")
    .replace(RE_BOLD, "$1")
    .replace(RE_ITALIC, "$1")
    .replace(RE_LINK, "$1")
    .replace(RE_LIST_PREFIX, "")
    .replace(RE_WHITESPACE, " ")
    .trim()

/**
 * 压缩预览文字 —— 对应 Web `normalizePreview`（`toolThinkingFallback.js:40-47`）。
 *
 * 折叠所有空白 + 超长截断加省略号（注意：截断后总长 = maxLength，不是 maxLength+1）。
 */
internal fun normalizePreviewText(value: String, maxLength: Int = 64): String {
    val cleaned = value.trim().replace(RE_WHITESPACE, " ")
    if (cleaned.isEmpty()) return ""
    return if (cleaned.length > maxLength) cleaned.take(maxLength - 1) + "…" else cleaned
}

/**
 * 工具卡 → 工具事件 —— 对应 Web `buildToolFallbackThinking`（`toolThinkingFallback.js:524-567`）。
 *
 * ## 步骤标签（源码 `buildToolStepLabel:465-497`）
 * | 工具 | 标签 |
 * |---|---|
 * | Bash | `Run command: <command>` |
 * | Read/Write/Edit/MultiEdit | `Read file: a.mjs`（**带工具显示名前缀**） |
 * | ListDir | `List directory: <basename>` |
 * | Search/Grep | `Search: <pattern>` |
 * | Glob | `Find files: <pattern>` |
 * | 其他 | 工具显示名 |
 *
 * ## `summaryLabel` 与 `label` 的区别（容易搞混）
 * - `label` 是**这一行的显示文字**（带具体参数）
 * - `summaryLabel` 是**组头摘要用的短名**（`getToolDisplayName`，不带参数）
 */
private fun buildToolEvents(toolCards: List<ChatToolCard>): List<AssistantThinkingEvent> =
    toolCards
        // 【2026-10-06 修】原来在这里丢弃 WebSearch/WebFetch ——
        // 用户报「函数清单内没有 websearch」，而且思维链里完全看不到
        // 搜索发生过。Web 那边是因为有独立的「搜索过程条」承载它们，
        // APK 没有那条 UI —— 丢弃等于信息消失。
        // 现在保留（buildToolStepLabel 会给它们合适的标签）。
        .map { card ->
            AssistantThinkingEvent(
                kind = ThinkingEventKind.TOOL,
                label = buildToolStepLabel(card),
                meta = card.progress.takeIf { it.isNotBlank() },
            )
        }

/** 工具步骤标签 —— 对应 Web `buildToolStepLabel` */
private fun buildToolStepLabel(card: ChatToolCard): String {
    val input = card.input
    val filePath = toolInputField(input, "file_path").ifBlank { toolInputField(input, "path") }
    val fileName = filePath.substringAfterLast('/').substringAfterLast('\\')

    return when (card.name) {
        "Bash" -> {
            val cmd = normalizePreviewText(toolInputField(input, "command"))
            if (cmd.isNotEmpty()) "Run command: $cmd" else "Run command"
        }
        "Write", "Read", "Edit", "MultiEdit" -> {
            val prefix = toolDisplayName(card.name)
            if (fileName.isNotEmpty()) "$prefix: $fileName" else prefix
        }
        "ListDir" -> {
            val target = filePath.substringAfterLast('/').substringAfterLast('\\')
            if (target.isNotEmpty()) "List directory: $target" else "List directory"
        }
        "Search", "Grep" -> {
            val query = normalizePreviewText(
                toolInputField(input, "pattern")
                    .ifBlank { toolInputField(input, "query") }
                    .ifBlank { toolInputField(input, "search") },
            )
            if (query.isNotEmpty()) "${toolDisplayName(card.name)}: $query" else toolDisplayName(card.name)
        }
        "Glob" -> {
            val pattern = normalizePreviewText(toolInputField(input, "pattern"))
            if (pattern.isNotEmpty()) "Find files: $pattern" else "Find files"
        }
        // 【2026-10-06 加】搜索类工具原来落在 else 分支（显示原始参数）。
        "WebSearch" -> {
            val q = normalizePreviewText(toolInputField(input, "query"))
            if (q.isNotEmpty()) "Search: $q" else "Web search"
        }
        "WebFetch" -> {
            val url = normalizePreviewText(toolInputField(input, "url"))
            if (url.isNotEmpty()) "Fetch: ${url.take(60)}" else "Fetch page"
        }
        "SearchInfo" -> {
            val kw = normalizePreviewText(toolInputField(input, "keywords"))
            if (kw.isNotEmpty()) "SearchInfo: $kw" else "SearchInfo"
        }
        "Lookup" -> {
            val card = normalizePreviewText(toolInputField(input, "card"))
            if (card.isNotEmpty()) "Lookup: $card" else "Lookup"
        }
        else -> {
            // 【2026-10-06 修】原来直接返回 card.preview —— 那是**参数预览**
            // （比如子 Agent 调用的 prompt、或某个参数值），用户看到的
            // 只有一串参数，**不知道这是什么工具**。
            // 现在统一加工具名前缀：`工具名: 参数`。
            // 参数太长时截断（思维链是概览，不该被长 prompt 撑爆）。
            val name = toolDisplayName(card.name)
            val preview = card.preview.trim()
            when {
                preview.isBlank() -> name
                preview.length > 60 -> "$name: ${preview.take(60)}…"
                else -> "$name: $preview"
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════════
//  【2026-10-06 性能优化】正则缓存
// ══════════════════════════════════════════════════════════════════
//
// 原来这些 Regex 都在热路径里现 new（splitReasoningBlocks /
// isToolHandoffBlock / summarizeThinkingBlock 等），而思维链**每流式
// 吐一个字符就重算一次** —— 一次 500 字的回复要 new 上千个 Regex 对象。
//
// Kotlin 的顶层 val 是**类加载时初始化一次**（不是每次访问都建），
// 所以搬到这里就等于全局复用。观感完全不变，只是不再重复编译正则。
//
// 命名按用途（不按模式），避免以后改了模式忘改名。

/** 空行分段（`splitReasoningBlocks`）。 */
private val RE_BLANK_LINES = Regex("\\n{2,}")

/** 列表续行标记（`- xxx` / `* xxx` / `• xxx` / `1. xxx` / `1) xxx`）。 */
private val RE_LIST_MARKER = Regex("^([-*•]|\\d+[.)]\\s)")

/** 英文思考起始词（兜底切分）。 */
private val RE_THINKING_STARTERS = Regex(
    "\n(?=(?:Let me|I should|I need to|First,|Next,|Then,|Perfect[.!]?|Now ))",
)

/** 工具交接句（`isToolHandoffBlock`）。 */
private val RE_HANDOFF = Regex(
    // 【2026-10-06 加中文】原来只匹配英文交接句 —— 用户用中文思考时
    // （「让我」「我来」「试试」「先看」「接下来」）一个都不匹配，
    // 导致时间线走「交替排列」分支，思考段与工具卡**对不上号**（顺序错乱）。
    "^(let me|i should|first,\\s*i should|next,\\s*i should|next,\\s*i'll|i'll|now i'?ll" +
        "|让我|我来|我先|试试|先看|先检查|接下来|下面|现在看|再试|查一下|跑一下|执行一下|调用)",
    RegexOption.IGNORE_CASE,
)

/** markdown 反引号代码（`stripMarkdown`）。 */
private val RE_BACKTICK = Regex("`([^`]+)`")

/** markdown 粗体。 */
private val RE_BOLD = Regex("\\*\\*([^*]+)\\*\\*")

/** markdown 斜体。 */
private val RE_ITALIC = Regex("\\*([^*]+)\\*")

/** markdown 链接。 */
private val RE_LINK = Regex("\\[([^\\]]+)\\]\\([^)]+\\)")

/** markdown 列表前缀（行首）。 */
private val RE_LIST_PREFIX = Regex("(?m)^[-*•]\\s+")

/** 空白折叠。 */
private val RE_WHITESPACE = Regex("\\s+")

/** 按空白切词保留分隔符（打字机效果）。 */
private val RE_SPLIT_KEEP_WS = Regex("(?<=\\s)")

/** 句子边界（摘要取首句）。 */
private val RE_SENTENCE = Regex("[^.!?。！？]+[.!?。！？]?")
