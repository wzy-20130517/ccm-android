package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 消息气泡 —— 对齐 Web `MainContent.tsx` 的消息渲染。
 *
 * ## ★ 实测发现：助手消息**没有气泡背景**
 * Playwright 实测对话页（393×852）：
 * ```
 * 助手消息块: w=393(全宽) / x=0 / y=84.5
 *            bg: rgba(0,0,0,0)   ← 透明！
 *            pad: 15.72px 16px
 *            ff: "Source Serif 4", ...   ← 衬线族
 *            fs: 13.362 / lh: 20.043 / fw: 430
 * ```
 *
 * **不是「卡片式气泡」，是「全宽文档流」**：
 * - 助手内容直接铺在页面背景上（`--bg-claude-main`）
 * - 用**衬线字体**（与 UI 的 Figtree 无衬线不同）
 * - 左右各 16px 内边距，占满整个宽度
 *
 * 这和很多人对「聊天气泡」的直觉相反，但截图确认了：
 * 助手回复看起来像一篇文档，不是圆角卡片。
 *
 * ## 用户消息（Web 侧）
 * 用户消息在 Web 里有浅色背景块（`bg-claude-hover`），右对齐、圆角。
 * 详见 [UserBubble]。
 *
 * ## fw 430 怎么处理
 * Web 用 variable font 的 430 字重，Android 系统字体没有这个档位。
 * Compose 侧取 [FontWeight.Normal]（400）—— 差异在 1px 级，diff 时容忍。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AssistantBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = CCMTheme.colors

    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 用户反馈】长按 Agent 消息会「先全文复制一次，
    //  再出选取框」—— 两个长按处理同时触发：
    //    · 外层 combinedClickable 的 onLongClick = 复制全文
    //    · 内层 SelectionContainer 的文本选择
    //
    //  去掉外层的长按复制（SelectionContainer 本身就能选+复制，
    //  且是 Android 原生的选取体验 —— 用户要的就是「选取复制」）。
    //  onClick 也一起去掉（空 lambda 没意义，还会拦截点击）。
    // ══════════════════════════════════════════════════════════════
    Box(
        modifier = modifier
            .fillMaxWidth()
            // 实测 pad: 15.72px 16px
            .padding(horizontal = 16.dp, vertical = 15.72.dp),
    ) {
        // ★ 2026-09-27：死 Text → MarkdownRenderer（690 行组件首秀）。
        // ★ 第37批：超长折叠（助手长回复同理，Web 有 Show more/less）。
        var expanded by remember(text) { mutableStateOf(false) }
        val longText = text.length > 2000   // 助手阈值放宽（正文本来就长）
        val shownText = if (longText && !expanded) text.take(2000) + "\n\n…" else text
        Column {
            MarkdownRenderer(
                content = shownText,
                modifier = Modifier.fillMaxWidth(),
            )
            if (longText) {
                Text(
                    text = if (expanded) "收起" else "展开全文（共 ${text.length} 字）",
                    style = CCMText.body12.copy(fontWeight = FontWeight.Medium),
                    color = colors.textSecondary,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { expanded = !expanded }
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                )
            }
        }
    }
}

/**
 * 用户消息气泡 —— 右对齐、浅色底、圆角。
 *
 * Web 侧用户消息用 `bg-claude-hover` 背景 + 右对齐。
 * 实测（对话页截图）：用户消息靠右，宽度自适应内容（`max-w-[85%]`）。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun UserBubble(
    text: String,
    modifier: Modifier = Modifier,
    images: List<String> = emptyList(),
    onResend: (() -> Unit)? = null,
    /** 消息时间戳（ms；0 = 不显示）。 */
    timestampMs: Long = 0L,
) {
    val colors = CCMTheme.colors

    // 【2026-10-09 最后一道防线】文本和图片都空 → 什么都不画。
    //
    // 这类气泡来自历史里的元信息消息（vision 旁路注入的「图片+缩放提示」，
    // 见 stripMetaTags 注释）。正常路径已在 loadHistory / 上面的剥离
    // 双重拦截，但这里再兜一层：无论如何都不该在屏幕上出现一个
    // 纯空白的背景块 —— 用户看到只会困惑「这是什么」。
    if (text.isBlank() && images.isEmpty()) return

    val uCtx = androidx.compose.ui.platform.LocalContext.current
    val copyUser: (String) -> Unit = { txt ->
        if (txt.isNotBlank()) {
            try {
                val cm = uCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("CCM", txt))
                android.widget.Toast.makeText(uCtx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.36.dp),
        horizontalAlignment = Alignment.End,
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        // 超长折叠（webgap #1：Web 有 Show more/less，APK 缺）——
        // 用户长文本（粘贴的日志等）会把页面撑爆，默认收起前 800 字。
        var expanded by remember(text) { mutableStateOf(false) }
        val longText = text.length > 800
        val shownText = if (longText && !expanded) text.take(800) + "…" else text

        // ══════════════════════════════════════════════════════════════
        //  【2026-10-06 用户反馈】长按用户气泡和助手气泡行为不一致
        //    · 助手气泡：SelectionContainer → 长按出**原生选取框**（拖手柄选）
        //    · 用户气泡：combinedClickable → 长按**直接全文复制**
        //
        //  要求「按助手的来改用户的」→ 去掉这里 combinedClickable 的
        //  长按复制（与助手同款处理：空 onClick 也一起去掉，免得拦截点击），
        //  正文包进 SelectionContainer（见下方 Text 处）。
        //  全文复制入口保留在按钮行的 Copy 图标。
        // ══════════════════════════════════════════════════════════════
        Box(
            modifier = Modifier
                .widthIn(max = 314.dp)                   // max-w-[85%] ≈ 393×0.8
                .clip(RoundedCornerShape(11.04.dp))      // rounded-xl
                .background(colors.hover)
                .padding(horizontal = 12.88.dp, vertical = 8.28.dp),
        ) {
            Column {
                // 附带图片缩略图（第20批 —— 原来只有 [图片] 文字，用户看不到自己发了啥）
                if (images.isNotEmpty()) {
                    // 第21批：点缩略图 → 全屏查看（每气泡独立 state，谁点谁显示）
                    var viewing by remember { mutableStateOf<String?>(null) }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = if (text.isNotBlank()) 6.dp else 0.dp),
                    ) {
                        images.forEach { path ->
                            com.ccm.app.ui.common.ThumbImage(
                                path = path,
                                modifier = Modifier
                                    .size(72.dp)
                                    .clickable { viewing = path },
                                cornerRadius = 6.dp,
                            )
                        }
                    }

                    viewing?.let { p ->
                        // 【2026-10-06 修】必须关掉「平台默认对话框宽度」——
                        // 默认 Dialog 的 usePlatformDefaultWidth=true 会把内容
                        // 限制在平台对话框宽度里，ImageViewerDialog 内部虽然
                        // 是 fillMaxSize + 深色底，却只能撑到那个宽度 →
                        // 用户看到「深色底只占屏幕中间一条、图片更小」，
                        // 感觉「点开图反而变小了」。
                        androidx.compose.ui.window.Dialog(
                            onDismissRequest = { viewing = null },
                            properties = androidx.compose.ui.window.DialogProperties(
                                usePlatformDefaultWidth = false,
                            ),
                        ) {
                            com.ccm.app.ui.common.ImageViewerDialog(
                                path = p,
                                onDismiss = { viewing = null },
                            )
                        }
                    }
                }
                if (text.isNotBlank()) {
                    // 与助手气泡同款：原生长按选取（MarkdownRenderer 里对
                    // Text 包的就是 SelectionContainer）。只包正文 Text ——
                    // 「展开」按钮有自己的 clickable，包进去会互相抢。
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(
                            text = shownText,
                            style = CCMText.body14,
                            color = colors.textMain,
                        )
                    }
                    if (longText) {
                        Text(
                            text = if (expanded) "收起" else "展开（共 ${text.length} 字）",
                            style = CCMText.body12.copy(fontWeight = FontWeight.Medium),
                            color = colors.textSecondary,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { expanded = !expanded }
                                .padding(vertical = 2.dp, horizontal = 2.dp),
                        )
                    }
                }
            }
        }
    }

        // ★ 2026-10-01 对齐 Web（MainContent.tsx:983-992）：
        //   时间戳 + 三个图标按钮**同一行**（flex items-center gap-1.5 mt-1.5 pr-1）。
        //   Web 的按钮是 RotateCcw/Pencil/Copy（14px 图标，hover 才显示）；
        //   APK 原来藏在长按文字菜单里（"重发（截断此后的内容重新跑）"一大段），
        //   与 Web 完全两个东西。
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            // 时间戳（12sp，Web text-[12px]）
            if (timestampMs > 0) {
                Text(
                    text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(timestampMs)),
                    style = CCMText.body12.copy(fontSize = 11.sp),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(end = 2.dp),
                )
            }
            // 重发（RotateCcw 14px）
            if (onResend != null) {
                PainterIcon(
                    R.drawable.ic_rotate_ccw,
                    size = 14.dp,
                    tint = colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onResend() }
                        .padding(3.dp),
                )
            }
            // 复制（Copy 14px）
            PainterIcon(
                R.drawable.ic_copy,
                size = 14.dp,
                tint = colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { copyUser(text) }
                    .padding(3.dp),
            )
        }
    }

    // ★ 2026-10-01：长按文字菜单已删 —— 改为时间戳行的图标按钮（对齐 Web）。
    //   showMenu 状态保留（长按仍弹菜单的逻辑被按钮行替代，但长按保留复制行为）。
}


/**
 * 消息列表 —— 按 [ChatBubble] 类型分发到对应气泡。
 *
 * 对齐 dev-core 的 `ChatSession.State.bubbles` 结构。
 */
@Composable
fun MessageList(
    bubbles: List<ChatBubble>,
    modifier: Modifier = Modifier,
    /** 重发某条用户消息（webgap #1；null = 不显示重发入口）。 */
    onResend: ((String) -> Unit)? = null,
    streaming: String = "",
    toolCards: List<ChatToolCard> = emptyList(),
    /** 正在流式生成的思考（State.thinking）。 */
    streamingThinking: String = "",
    /** 是否还在跑（决定思考链的 isThinking 动效）。 */
    streamingRunning: Boolean = false,
    /** 待办清单（TodoWrite 维护，渲染在消息流顶部）。 */
    todos: List<com.ccm.app.ui.common.TodoItem> = emptyList(),
    /** 点文档卡片（打开 DocumentPanel；null = 不响应，卡片仍显示）。 */
    onOpenDocument: ((com.ccm.app.core.ChatSession.DocumentEntry) -> Unit)? = null,
    /**
     * 列表状态（由 ChatScreen 传入，用于「回到底部」判定与滚动）。
     *
     * 【2026-10-11 reverseLayout 改造，照抄官方 Jetchat】
     * 之前用 `Column + verticalScroll`，内容长高会改变滚动位置和 maxValue，
     * 所有「离底部多远」的判定在流式期间都不可靠（修了 6 版仍有 bug）。
     *
     * 改用 `LazyColumn(reverseLayout = true)`：
     *   · index 0 = 视觉**底部**（最新消息）
     *   · 新内容自动出现在底部 —— **不需要任何滚动代码**
     *   · 内容长高**不影响**滚动位置（锚点在底部）
     *   · 「是否在底部」= `firstVisibleItemIndex == 0 && offset < 阈值`（精确）
     */
    listState: androidx.compose.foundation.lazy.LazyListState =
        androidx.compose.foundation.lazy.rememberLazyListState(),
) {
    androidx.compose.foundation.lazy.LazyColumn(
        state = listState,
        reverseLayout = true,
        // 【2026-10-11 修「用户消息贴屏幕最底」】
        //
        // `reverseLayout = true` 时 verticalArrangement 的默认值是
        // `Arrangement.Bottom` —— 内容**不足一屏时从底部开始堆**，
        // 于是刚发的用户消息直接贴在屏幕最底下（旧版不是这样）。
        //
        // 旧版（Column + fillMaxSize + verticalScroll）的行为是
        // **从顶部开始排**：内容不足一屏时，消息从顶部往下堆，
        // 底部留空。
        //
        // 显式指定 `Arrangement.Top` 恢复旧版行为。
        // （官方文档：reverseLayout 时 Arrangement.Top 的语义是
        //  「(top) 123### (bottom)」→「(top) 321### (bottom)」，
        //   即 item 顺序仍反转，但**堆叠起点在顶部**。）
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Top,
        modifier = modifier.fillMaxWidth(),
    ) {
        // ══════════════════════════════════════════════════════════════
        //  【声明顺序 = 从底往上的视觉顺序】（reverseLayout 的特性）
        //
        //  旧版（Column 从上到下）：todos → bubbles → streaming → waiting → Spacer
        //  这里要反过来声明（从底往上）：
        //    1. Spacer        → 视觉最底部（呼吸空间）
        //    2. waiting       → 等待态（发送后到首字之间）
        //    3. streaming     → 正在生成的内容
        //    4. bubbles 反序  → 最新消息在下、旧的在上
        //    5. todos         → 视觉最顶部（待办面板）
        // ══════════════════════════════════════════════════════════════
        // 底部呼吸空间（旧版 Column 末尾的 Spacer(12.dp)）
        item(key = "bottom-spacer") {
            androidx.compose.foundation.layout.Spacer(
                androidx.compose.ui.Modifier.height(12.dp),
            )
        }

        // ★ 2026-10-01：等待态指示器 —— 发送后到首字之间的空窗期。
        //   原来这段什么都没显示（用户以为卡死）；Web 有 Claude 星芒 sprite。
        //   条件：在跑 && 还没有任何流式内容（正文/思考都空）。
        //
        // 【reverseLayout】这个块也是列表内容 → 必须包 item{}
        // （它放最后声明 = 视觉最底部，正好是等待态该在的位置）。
        if (streamingRunning && streaming.isBlank() && streamingThinking.isBlank()) {
            item(key = "waiting") {
            // ★ 2026-10-01 用户报「没有思维链」：原来只放了个裸星芒 ——
            //   Web 的对应物是 AssistantThinkingCompactStatus（星芒 28px +
            //   斜体衬线状态文字 + 打字机逐词显现），这才是「思维链」的观感。
            //   组件早就写好了（739 行），一直零调用 —— 又是「写好了没接线」。
            // 【2026-10-06 问题17 修复】原来这里加了
            //   `.padding(horizontal = 16.dp, vertical = 12.dp)`
            // —— 但 AssistantThinkingCompactStatus 组件**内部已有**
            //   `padding(start = 10.12.dp, top = 12.88.dp)`（对齐 Web 的
            //   pl-[11px] / mt-[14px]）。两者叠加 → 左边距 26.12dp、
            //   上边距 24.88dp，比其他消息明显偏右偏下，
            //   用户报「那句正在深度思考的位置有点奇怪」。
            //
            // 现在只留外层 16dp 水平边距（与正文对齐），垂直交给组件自己。
            AssistantThinkingCompactStatus(
                event = AssistantThinkingEvent(
                    kind = ThinkingEventKind.FOCUS,
                    label = "正在深入思考，请稍候…",
                ),
                isThinking = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            }  // ← item(waiting) 闭合
        }      // ← 闭 if (streamingRunning && ...)

        // 流式部分（正在生成的内容）—— 声明顺序 = 从底往上的顺序，
        // 它在 waiting 之上、bubbles 之下（视觉上：bubbles → streaming → waiting → spacer）
        item(key = "streaming") {
            // 【2026-10-11】同 bubbles 的 item：多个平级 Composable 在
            // reverseLayout 下会从底往上堆叠 → 顺序反。包 Column 固定方向。
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
            // 流式部分（未定型）：思考 → 工具组 → 正文
            if (streaming.isNotBlank() || streamingThinking.isNotBlank()) {
            // 流式中：思考在前（先想后说），running 驱动 isThinking 动效
            if (streamingThinking.isNotBlank()) {
                AssistantThinkingChain(
                    thinking = streamingThinking,
                    isThinking = streamingRunning,
                    // 工具卡传进去合成时间线事件（Web 同款：
                    // `buildReasoningTimelineEvents(thinking, { toolCalls })`）
                    toolCards = toolCards,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            // ══════════════════════════════════════════════════════════
            //  【2026-10-11 结构对齐 Desktop/Web】
            //
            //  流式期间**正文区为空**，所有文字都在工具区 —— 对齐 Web
            //  `MainContent.tsx:1139-1141`（`_finalText = isCurrentlyStreaming ? '' : …`）：
            //  各卡的 textBefore（灰字）+ `pendingWorkText`（正在生成、还没
            //  关联到工具的那段，带 shimmer）。
            //
            //  这样用户读到的顺序就是真实输出顺序：
            //    正文1 → 工具1 → 正文2 → 工具2 → （正在写的正文3，闪动）
            //  而不是「工具全堆中间、正文全堆底部」。
            //
            //  ⚠️ thinking 非空时**同样**要走工具区 —— 思考链时间线只画
            //  「思考 → 工具步骤」的概览行（label 是一行摘要），
            //  **不显示工具前的正文**。早先这里 thinking 非空就直接
            //  `AssistantBubble(streaming)` 显示全文、工具区整个跳过 ——
            //  结果 thinking 模型永远看不到 textBefore 分段，
            //  与本任务的目标结构不符。现在两条路都渲染工具区。
            // ══════════════════════════════════════════════════════════
            if (toolCards.isNotEmpty() && toolCards.any { it.name !in HiddenToolNames }) {
                // 已消费长度 = 最后一个可见工具的 textBefore 长度；
                // 之后的都是「还没归属到任何工具」的在写正文。
                val consumed = consumedTextLength(toolCards)
                val pending = if (streaming.length > consumed) {
                    streaming.substring(consumed).trim()
                } else {
                    ""
                }
                ToolCallGroup(
                    cards = toolCards,
                    isStreaming = streamingRunning,
                    isStale = !streamingRunning,
                    pendingWorkText = pending,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else if (streaming.isNotBlank()) {
                // 没有可见工具（无工具 / 全是 WebSearch 等隐藏工具）：
                // 正文区直接显示，否则两边都空 = 正文凭空消失
                AssistantBubble(text = streaming)
            } else if (toolCards.isNotEmpty()) {
                // 没有流式文本但工具在跑（例如纯工具轮次）
                ToolCallGroup(
                    cards = toolCards,
                    isStreaming = streamingRunning,
                    isStale = !streamingRunning,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            }  // ← if (streaming.isNotBlank() || streamingThinking.isNotBlank())
            }  // ← 闭 Column
        }  // ← item {} 闭合（流式部分）

        // 【reverseLayout】数据反序：最新的在 index 0（视觉底部）。
        // 与 Jetchat 的 `_messages.add(0, msg)` 等价 —— 这里渲染时反序，
        // 不改数据源（bubbles 本身保持时间正序，其它地方还在用）。
        bubbles.asReversed().forEachIndexed { index, bubble ->
            // 【2026-10-11 修崩溃】key 必须**全局唯一** ——
            // LazyColumn 对重复 key 直接抛 IllegalArgumentException：
            //   `Key "assistant-history-0" was already used`
            //
            // 根因：历史消息的 messageId = "history-${timestamp}"，而老数据
            // 的 timestamp 可能为 0（没存时间）→ 多条消息的 key 全是
            // "assistant-history-0" → 撞车 → 点老对话进不去（闪退）。
            //
            // 修法：key 拼上 index —— 索引在列表内天然唯一，
            // 且气泡顺序稳定（追加型列表），重组时 key 不变。
            item(key = "${index}-${bubble.key}") {
            // 【2026-10-11 修「内容顺序反了」】item{} 里的多个平级 Composable
            // 在 reverseLayout 下会**从底往上堆叠** —— 视觉顺序整个反过来
            // （用户报：思维链/工具/正文的顺序变成正文/工具/思维链）。
            // 包一层 Column 显式声明垂直布局，不受 reverseLayout 影响。
            androidx.compose.foundation.layout.Column(
                modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
            ) {
            if (bubble.isUser) {
                UserBubble(
                    // <image_resize_notice> / <vision_unsupported> 是 AgentLoop
                    // 拼给**模型**的元信息，不是用户输入 —— 渲染前剥掉，
                    // 否则用户在自己气泡里看到一坨 XML 标签。
                    //
                    // 【2026-10-09】改用共享的 stripMetaTags —— 原来这里
                    // 只剥 image_resize_notice、且与 loadHistory 的判据各写
                    // 各的，导致「判断时有文本 → 生成气泡 → 渲染时剥空 →
                    // 空白气泡」。现在两处共用同一个函数，不会再错位。
                    text = com.ccm.app.core.session.stripMetaTags(bubble.text),
                    images = bubble.images,
                    onResend = onResend?.let { fn -> { fn(bubble.messageId) } },
                    timestampMs = bubble.timestamp,
                )
            } else {
                // ══════════════════════════════════════════════════════════
                //  【2026-10-06 问题27 修复】思维链与正文「割裂」的真根因：
                //
                //  APK 原来把三者拆成**三块独立渲染**：
                //    ① AssistantThinkingChain(thinking)  ← 只有思考，没工具
                //    ② ToolCallGroup(bubble.toolCards)   ← 工具单独一块
                //    ③ AssistantBubble(text)             ← 正文单独一块
                //
                //  用户看到的：
                //    [思考：我要调用 bash]
                //    （空一大段）
                //    [工具：bash echo]
                //    （空一大段）
                //    [正文：调用成功]
                //  → 完全看不出「哪次工具调用对应哪段输出」。
                //
                //  Web 的做法（`MainContent.tsx:1009`）：把 toolCalls **传进**
                //  `buildReasoningTimelineEvents`，合成**一条时间线**：
                //    [思考段] → [工具事件] → [思考段] → [工具事件] → [Done]
                //  正文在时间线**之外**（它是最终产出，不属于"思考过程"）。
                //
                //  修法：把 bubble.toolCards 传给 AssistantThinkingChain
                //  （组件内部已有合成逻辑），去掉独立的 ToolCallGroup。
                //  ⚠️ 2026-10-11 更新：**独立的 ToolCallGroup 又回来了** ——
                //  思考链时间线只画「思考 → 工具步骤」的**概览行**（一行摘要，
                //  见 buildToolStepLabel），它不显示工具前的正文（textBefore），
                //  也不显示工具卡本身。所以工具区必须独立渲染一次，
                //  否则 thinking 模型永远看不到「正文1 → 工具1 → 正文2」的分段。
                //  两者内容不重叠：时间线 = 思考过程概览；工具区 = 工具卡 + 前置正文。
                // ══════════════════════════════════════════════════════════
                // 【2026-10-06 用户反馈「思维链/工具/正文交错，排序不对」】
                // 上一版（问题27）无条件走时间线 —— 但 buildReasoningTimelineEvents
                // 在 **thinking 为空时直接返回空**（Web 同款，见 toolThinkingFallback.js:570），
                // 于是「有工具没思考」的气泡：时间线不渲染 → 工具无踪 →
                // 只剩正文 —— 观感就是工具和正文交错、时有时无。
                // Web 没这个问题是因为它在时间线之外**还有独立的 toolCalls 聚合组**
                // （MainContent.tsx:1186）；APK 问题27 把独立组删了却没有兜底。
                // 修：thinking 有 → 时间线（工具合成进去）；
                //     thinking 空但有工具 → 退回独立 ToolCallGroup（Web 的另一条路径）。
                // ══════════════════════════════════════════════════════════
                //  【2026-10-10 用户要的布局：正文1 → 工具1 → 正文2 → 工具2 …】
                //  【2026-10-11 结构改为对齐 Desktop/Web】
                //
                //  数据一直都在：每个工具卡带 textBefore（该工具前累积的正文，
                //  ChatSession 存的是跨轮全局值）。之前两种做法都不对：
                //    · 「工具全堆一起 + 正文全堆一起」—— 脱节
                //    · 「正文段/工具卡逐个平铺交错」（buildInterleavedSegments）
                //      —— 每个工具都成了独立折叠组，看不出它们属于同一次思考
                //
                //  现在（= Desktop/Web 的结构）：
                //    工具区 = **一个** ToolCallGroup，组内每张卡上方渲染
                //            它的 textBefore 增量（灰字）
                //    正文区 = 最后一个工具之后的收尾段（finalTextAfterTools）
                //  两段互补，合起来是全文。
                //
                //  thinking 非空时思考链在前（它是「思考过程」的概览，不含正文），
                //  工具区与正文区照常渲染 —— 三者不重复。
                // ══════════════════════════════════════════════════════════
                if (bubble.thinking.isNotBlank()) {
                    // 【2026-10-10 修「思维链被吃」】这里必须渲染思考链 ——
                    // 上一轮重构交错渲染时误删了 AssistantThinkingChain 调用，
                    // 只剩条件判断 + 正文 → 任务完成后（Done 定型）思考链
                    // 直接不显示（用户报「任务完成后，思维链直接被吃了」）。
                    // 流式路径（下面 550 行附近）没删到所以流式时正常 ——
                    // 这个不对称正是「流式有、完成就没」症状的来源。
                    //
                    // ⚠️ 思考链只画「思考 → 工具步骤」的概览行（一行摘要），
                    // **不显示工具前的正文** —— 所以下面还要渲染工具区（textBefore）
                    // 与正文区（收尾段），三者合起来才是完整内容。
                    AssistantThinkingChain(
                        thinking = bubble.thinking,
                        isThinking = false,
                        // 工具卡传进去 —— 合成到时间线里
                        toolCards = bubble.toolCards,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (bubble.toolCards.isNotEmpty()) {
                    // ══════════════════════════════════════════════════════
                    //  【2026-10-11 结构对齐 Desktop/Web】工具区 + 正文区，两块
                    //
                    //  工具区：**一个**折叠组，组内每张卡上方渲染它的 textBefore
                    //         （灰色小字，前缀差去重）—— 见 ToolCallGroup。
                    //  正文区：最后一个工具**之后**的收尾段（finalTextAfterTools）。
                    //
                    //  两段合起来 = 全文，不重复也不丢 —— 这正是 2026-10-10
                    //  「正文一消失」的正确修法（当时删 textBefore 渲染是错的：
                    //  正文被切走 + 工具区不显示 → 两边都看不见）。
                    // ══════════════════════════════════════════════════════
                    ToolCallGroup(
                        cards = bubble.toolCards,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    // 正文区：收尾段；无 offset / 无可见工具 → null（兜底走全文）
                    val finalText = finalTextAfterTools(
                        fullText = bubble.text,
                        cards = bubble.toolCards,
                    )
                    when {
                        finalText != null ->
                            if (finalText.isNotBlank()) AssistantBubble(text = finalText)
                        // 兜底（旧数据：textBefore 缺失）→ 正文区显示全文
                        bubble.text.isNotBlank() -> AssistantBubble(text = bubble.text)
                    }
                } else if (bubble.text.isNotBlank()) {
                    // 无工具（纯正文）
                    AssistantBubble(text = bubble.text)
                }

                // 【2026-10-11】文档卡片内联在**本条消息内**渲染（正文之后）。
                // 数据由 ChatSession.deriveDocuments 从 toolCards 推导（Write/Edit
                // 写过的、扩展名可预览的文件）。点卡片 → DocumentPanel 全屏预览。
                // 对齐 Web 的做法（MainContent.tsx:1355-1375）。
                if (bubble.documents.isNotEmpty() && onOpenDocument != null) {
                    DocumentCardList(
                        documents = bubble.documents,
                        onOpen = onOpenDocument,
                    )
                }
            }   // ← 闭 else（if (bubble.isUser) 的另一半）
            }   // ← 闭 Column（item 内部的垂直布局）
            }   // ← 闭 item(key = bubble.key)
        }       // ← 闭 forEachIndexed

        // 待办面板 —— Web 行为：清单挂在消息流顶部，随 TodoWrite 更新
        // reverseLayout 下：先声明的 item 在**视觉底部**，所以 todos 要放最后。
        // 这里用 item{} 包起来（LazyColumn 的每个子项都要是 item）。
        if (todos.isNotEmpty()) {
            item(key = "todos") {
                com.ccm.app.ui.common.TodoPanel(
                    todos = todos,
                    running = streamingRunning,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        // 【2026-10-11】流式期间**不**推导文档卡片：toolCards 里已能看到
        // Write/Edit 的调用，再叠一张卡片等于同一件事显示两遍；而且流式过程中
        // 文件还在写，卡片内容会反复变。等 Done 定型进 bubble 后一次性渲染。
    }
}

/**
 * mermaid 源码 → 可渲染 HTML（对齐 Web 的 mermaid 分支）。
 *
 * 模板要点：
 * · `<script src="mermaid.min.js">` 是**相对路径** —— 配合 loadDataWithBaseURL
 *   的 baseUrl `file:///android_asset/` 解析到打包进 APK 的 assets 脚本（离线可用）。
 * · 源码进 `class="mermaid"` 前必须 HTML 转义（mermaid 里常见 `-->`、`A["<x>"]`），
 *   浏览器解析文本节点时会反转义，mermaid 拿到的仍是原文。
 * · `securityLevel:'strict'`：禁 click/脚本交互 —— 渲染的是模型生成的源码，别放开。
 * · `startOnLoad:true`：div 就位后自动 render；语法错时 mermaid 会把错误画在图里
 *   （这是它的原生行为，比我们吞掉强 —— 模型和用户都能看到哪儿写错了）。
 */
internal fun buildMermaidHtml(source: String, runtime: String = ""): String {
    val escaped = source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<script src="mermaid.min.js"></script>
<style>
  html,body{margin:0;padding:8px;background:transparent;}
  #chart{display:flex;justify-content:center;min-height:40px;}
  #chart svg{max-width:100%;height:auto;}
</style>
$runtime
</head><body>
<div id="chart" class="mermaid">$escaped</div>
<script>
  mermaid.initialize({ startOnLoad: true, theme: 'neutral', securityLevel: 'strict' });
</script>
</body></html>"""
}

/**
 * 对话气泡（UI 层）—— 对应 dev-core `ChatSession.Bubble`。
 *
 * `messageId` 用于列表 key：同一轮内「说话 → 调工具 → 再说」会得到不同的 id，
 * 天然区分两个气泡。
 */
data class ChatBubble(
    val role: String,
    val text: String,
    val messageId: String,
    /** 该消息的思考过程（空 = 没有/历史消息）。AssistantThinkingChain 渲染。 */
    val thinking: String = "",
    /**
     * 工具后的正文起点（core 侧算，对齐 Web `toolTextEndOffset`）。
     *
     * ⚠️ **2026-10-11 起 UI 渲染不再使用它** —— 正文切分改走
     * [finalTextAfterTools]，后者按「只算可见工具」的口径现场计算。
     *
     * 原因：core 算的是 `finalTools.lastOrNull()?.textBefore?.length`（**全部**工具），
     * 而工具区只渲染可见工具（[HiddenToolNames] 之外的）——
     * 若最后一个是隐藏工具，用它做 offset 会把「最后一个可见工具 → 隐藏工具」
     * 之间的正文切走，而那段在工具区**无处显示** → 正文静默消失。
     * 这正是 2026-10-10「正文一突然消失」的同一类缺陷。
     *
     * 字段保留：core 的语义本身没错（「最后一个工具的 textBefore 长度」），
     * 只是与 UI 的过滤口径不同源。留着供其他消费方/历史数据参考，
     * **不要拿它做正文切分**。
     */
    val toolTextEndOffset: Int = 0,
    val toolCards: List<ChatToolCard> = emptyList(),
    /** 附带图片路径（用户消息；渲染缩略图，第20批）。 */
    val images: List<String> = emptyList(),
    /**
     * 该消息里被**写过**的文件（从 toolCards 推导）。
     *
     * 【2026-10-11】替代原来的 presentItems。Present 工具下线后，可视内容
     * 不再是「工具推给 UI 的一段富文本」，而是「Agent 写的文件」——
     * UI 渲染成文档卡片（[DocumentCardList]），点开全屏预览（[DocumentPanel]）。
     */
    val documents: List<com.ccm.app.core.ChatSession.DocumentEntry> = emptyList(),
) {
    val isUser: Boolean get() = role == "user"

    /**
     * 消息时间（webgap #1 时间戳）—— 从 messageId 解析（格式见 ChatSession：
     * `user-{millis}` / `history-{timestamp}` / `notice-{millis}`）。
     * 解析不出（旧格式）返回 0 → UI 不显示。
     */
    val timestamp: Long get() =
        messageId.substringAfterLast('-').toLongOrNull() ?: 0L

    /** 列表 key —— 加 role 前缀防极端情况撞号（dev-core 建议） */
    val key: String get() = "$role-$messageId"
}

/**
 * 已消费的正文长度 —— 最后一个**可见**工具卡携带的 textBefore 长度（2026-10-11 加）。
 *
 * ## 为什么是「最大长度」而不是「累加」
 * textBefore 是**累计值**（每个都包含前面所有正文），累加等于把同一段数好几遍：
 * 三个工具各带 100/200/300 字 → 累加得 600，而实际只有 300 字 →
 * `substring(600)` 把还在生成的正文整段切掉（用户看到「正文不出现」）。
 * Web 2026-10-11 踩过同一个坑（`MainContent.tsx:1165-1172` 的注释）。
 *
 * 取「最大长度」= 最后一个有 textBefore 的卡的长度 = 已消费的正文量。
 * 用 max 而不是 last：最后一个卡可能是隐藏工具（被过滤）或 textBefore 为空。
 *
 * ⚠️ 过滤口径必须与 [ToolCallGroup] 的 `visible` 一致（同一份 [HiddenToolNames]），
 * 否则 offset 会被隐藏工具推大 → 收尾正文被多切 → 正文消失。
 */
internal fun consumedTextLength(cards: List<ChatToolCard>): Int =
    cards.filter { it.name !in HiddenToolNames }
        .maxOfOrNull { it.textBefore.trim().length } ?: 0

/**
 * 正文区要显示的「收尾正文」= 最后一个**可见**工具**之后**说的那段（2026-10-11 加）。
 *
 * 对齐 Web `MainContent.tsx:1134-1143`：
 * ```
 * const hasOffset = offset && offset > 0 && offset < fullText.length;
 * const finalText = hasOffset ? fullText.slice(offset).trim() : '';
 * ```
 *
 * ## 与工具区合起来才是全文（这是「正文消失」的正确修法）
 * 工具区渲染各卡 textBefore 的**前缀差**（灰色小字，去重后等价于
 * `[0, consumed)`），正文区渲染本函数的返回值 `[consumed, end)`。
 * 两段**严格互补、不重叠** —— 既不消失也不重复。
 *
 * 2026-10-10 的「正文一消失」是只做了一半：正文区切走、工具区又不渲染
 * textBefore → 工具前的正文两边都看不见。当时的修法是「正文区显示全文」，
 * 那又走到另一个极端（正文全堆底部、与工具脱节）。正确修法是**两处都显示**。
 *
 * ## 为什么不用 core 的 `Bubble.toolTextEndOffset`
 * core 算的是 `finalTools.lastOrNull()?.textBefore?.length` —— **全部**工具，
 * 含被 [HiddenToolNames] 过滤掉的 WebSearch/WebFetch（它们不在工具区渲染）。
 * 若最后一个是隐藏工具，用它做 offset 会把「最后一个可见工具 → 隐藏工具」
 * 之间的正文切走，而那段在工具区**没有任何地方显示** → 正文静默消失。
 *
 * 所以这里按 [consumedTextLength]（只算可见工具）取 offset，
 * 与工具区**同源同口径**，互补关系恒成立。
 *
 * ## 兜底（返回 null 表示「不接管，照旧显示全文」）
 * · 无工具卡 → null（纯正文消息）
 * · 可见工具都没 textBefore（旧数据/纯工具轮次）→ null
 * · offset 越界 → null
 * · offset 恰好等于全文长度 → 空串（工具后确实没再说，正文区就不该有东西）
 */
internal fun finalTextAfterTools(
    fullText: String,
    cards: List<ChatToolCard>,
): String? {
    if (cards.isEmpty()) return null
    val text = fullText.trim()
    if (text.isEmpty()) return null
    val offset = consumedTextLength(cards)
    // 与 Web 的 hasOffset 判据同构：(0, len) 开区间。
    // 等于 0 说明可见工具没产出过正文（工具区不显示任何 textBefore）；
    // 等于 len 说明工具后无话可说（返回空串）。
    if (offset <= 0 || offset > text.length) return null
    return text.substring(offset).trim()
}

/**
 * 工具卡片数据 —— 对应 dev-core `ChatSession.ToolCard`。
 *
 * 字段语义（dev-core 确认）：
 * - [preview]：折叠态一行参数摘要，**agent 层已格式化好**（如 `path="a.mjs", limit=50`）
 * - [progress]：**覆盖式**（每次替换，不是追加）—— 进度是「当前状态」不是日志
 * - [result]：已截断的结果文本
 */
data class ChatToolCard(
    val id: String,
    val name: String,
    /** Web 风格的显示文本（如 `Read a.mjs`）。空则回退到 [name] */
    val displayName: String = "",
    val preview: String = "",
    val running: Boolean = false,
    val isError: Boolean = false,
    val progress: String = "",
    val result: String = "",
    /** 完整入参 JSON —— ToolDiffView 展开渲染用（2026-09-27 加）。 */
    val input: String = "",
    /** 这个工具调用**之前**累积的正文（对齐 Web 的 textBefore）。 */
    val textBefore: String = "",
    /**
     * 这个工具调用**之前**累积的思考（2026-10-06 加）。
     *
     * 思维链时间线用它精确排序：思考1 → 工具1 → 思考2 → 工具2 …
     * （没有它时只能用启发式猜，中文思考必然错乱）。
     */
    val thinkingBefore: String = "",
)
