package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
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
                        androidx.compose.ui.window.Dialog(onDismissRequest = { viewing = null }) {
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
    presentItems: List<com.ccm.app.core.ChatSession.PresentItem> = emptyList(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // 待办面板 —— Web 行为：清单挂在消息流顶部，随 TodoWrite 更新
        if (todos.isNotEmpty()) {
            com.ccm.app.ui.common.TodoPanel(
                todos = todos,
                running = streamingRunning,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }


        presentItems.forEach { item ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).background(CCMTheme.colors.input).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(item.title ?: "展示 · ${item.kind}", style = CCMText.body14.copy(fontWeight = FontWeight.Medium), color = CCMTheme.colors.textMain)
                item.caption?.let { Text(it, style = CCMText.body12, color = CCMTheme.colors.textSecondary) }
                // 【2026-10-06 Present 升级】html / svg / mermaid 都走 WebView 内联渲染。
                //   · html/svg：禁 JS（防外跳、防恶意页）
                //   · mermaid：**必须 JS** —— 用 assets/mermaid.min.js 离线渲染成
                //     流程图（原来是 else 分支把 .mmd 源码当纯文本显示 = 没升级完）
                if (item.kind in setOf("html", "svg", "mermaid") && item.content.isNotBlank()) {
                    val isMermaid = item.kind == "mermaid"
                    val preview = remember(item.kind, item.content) {
                        when {
                            item.kind == "svg" && !item.content.contains("<svg", ignoreCase = true) ->
                                "<svg xmlns=\"http://www.w3.org/2000/svg\">${item.content}</svg>"
                            isMermaid -> buildMermaidHtml(item.content)
                            else -> item.content
                        }
                    }
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        factory = { context -> WebView(context).apply {
                            settings.javaScriptEnabled = isMermaid
                            settings.domStorageEnabled = false
                            // mermaid 需要读 assets 里的脚本；html/svg 分支 JS 已关，
                            // 开着 allowFileAccess 也执行不了任何东西（双重保险靠下面的拦截）。
                            settings.allowFileAccess = isMermaid
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                                    val scheme = request.url.scheme?.lowercase()
                                    // 放行 data/about + 本包 assets（mermaid.min.js 走 file:///android_asset/）
                                    return if (scheme == "data" || scheme == "about" ||
                                        (isMermaid && scheme == "file" && request.url.toString().contains("/android_asset/"))
                                    ) null
                                    else android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                                }
                                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                    // 只拦「离页跳转」；assets/data/about 是渲染自身需要
                                    val scheme = request.url.scheme?.lowercase()
                                    return !(scheme == "file" || scheme == "data" || scheme == "about")
                                }
                            }
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        } },
                        update = { web ->
                            if (web.tag != preview) {
                                web.tag = preview
                                // baseUrl 让 <script src="mermaid.min.js"> 解析到
                                // file:///android_asset/mermaid.min.js；html/svg 无外链不受影响。
                                web.loadDataWithBaseURL("file:///android_asset/", preview, "text/html", "UTF-8", null)
                            }
                        },
                    )
                } else if (item.content.isNotBlank()) {
                    Text(item.content.take(4000), style = CCMText.body12.copy(fontFamily = FontFamily.Monospace), color = CCMTheme.colors.textSecondary)
                }
                item.paths.forEach { path ->
                    when (item.kind) {
                        "image", "images" -> com.ccm.app.ui.common.ThumbImage(path, Modifier.fillMaxWidth().height(220.dp), 8.dp)
                        "video" -> AndroidView(
                            modifier = Modifier.fillMaxWidth().height(240.dp),
                            factory = { context -> android.widget.VideoView(context).apply {
                                setVideoPath(path)
                                setMediaController(android.widget.MediaController(context).also { it.setAnchorView(this) })
                            } },
                        )
                        else -> Text(path, style = CCMText.body11.copy(fontFamily = FontFamily.Monospace), color = CCMTheme.colors.textSecondary)
                    }
                }
            }
        }

        bubbles.forEachIndexed { index, bubble ->
            if (bubble.isUser) {
                UserBubble(
                    text = bubble.text,
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
                if (bubble.thinking.isNotBlank()) {
                    AssistantThinkingChain(
                        thinking = bubble.thinking,
                        isThinking = false,
                        // ★ 关键：把工具卡传进去 —— 合成到时间线里
                        toolCards = bubble.toolCards,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                } else if (bubble.toolCards.isNotEmpty()) {
                    ToolCallGroup(
                        cards = bubble.toolCards,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                // 【2026-10-06】正文区只显示**最后一个工具之后**的那段 ——
                // 工具期间的正文已由各工具卡的 textBefore 显示，不切的话
                // 同一段文字出现两遍（工具区一遍、正文区一遍）。
                // 对齐 Web `MainContent.tsx:1136` 的 workText/finalText 切分。
                // offset 为 0 或越界 = 显示全文（兜底：没有工具，或旧数据）。
                val offset = bubble.toolTextEndOffset
                val finalBody = if (offset in 1 until bubble.text.length) {
                    bubble.text.substring(offset).trim()
                } else {
                    bubble.text
                }
                if (finalBody.isNotBlank()) AssistantBubble(text = finalBody)
            }
        }

        // ★ 2026-10-01：等待态指示器 —— 发送后到首字之间的空窗期。
        //   原来这段什么都没显示（用户以为卡死）；Web 有 Claude 星芒 sprite。
        //   条件：在跑 && 还没有任何流式内容（正文/思考都空）。
        if (streamingRunning && streaming.isBlank() && streamingThinking.isBlank()) {
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
        }

        // 流式内容（未定型）
        // ★ 顺序对齐 Web（`MainContent.tsx:1073 → 1125 → 1330`）：
        //   思考链 → 工具调用组 → 正文。
        //   原来是「思考 → 正文 → 工具」，工具组被甩到正文下面，
        //   与 Web 的「先看思考、再看它调了什么、最后读结论」叙事顺序相反。
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
            } else if (streaming.isNotBlank() && toolCards.isNotEmpty()) {
                // 【2026-10-06 同上】思考空但工具在跑 + 正文已开始：
                // 时间线渲染不了（thinking 空 → 合成返回空），必须走独立
                // 工具组，否则工具在这段流式里完全不显示（原来被 if/else if
                // 结构甩到够不着的分支）。
                ToolCallGroup(
                    cards = toolCards,
                    isStreaming = streamingRunning,
                    isStale = !streamingRunning,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            // 本轮工具调用 —— 对齐 Web：**聚合成一个折叠组**（不是单卡平铺）。
            // Web `MainContent.tsx:1186` 把一条消息的所有 toolCalls 包进一个
            // `<div className="mb-4">`，组头显示去重后的工具名摘要，展开后左竖线内列。
            if (streaming.isNotBlank()) {
                // 【2026-10-06】流式期间同样只显示「最后一个工具之后」的正文 ——
                // 工具前的正文已由工具卡渲染（textBefore），不切会重复。
                // 与 Web `MainContent.tsx:1164` 的 pendingWorkText 逻辑一致：
                //   consumedLen = 各工具 textBefore 之和（= 最后一个的，因为累计）
                //   正文区 = fullText.drop(consumedLen)
                val consumed = toolCards.lastOrNull()?.textBefore?.length ?: 0
                val pendingBody = if (consumed in 1 until streaming.length) {
                    streaming.substring(consumed).trim()
                } else {
                    streaming
                }
                if (pendingBody.isNotBlank()) AssistantBubble(text = pendingBody)
            }
        } else if (toolCards.isNotEmpty()) {
            // 没有流式文本但工具在跑（例如纯工具轮次）
            ToolCallGroup(
                cards = toolCards,
                isStreaming = streamingRunning,
                isStale = !streamingRunning,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

/**
 * mermaid 源码 → 可渲染 HTML（Present 升级，2026-10-06）。
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
private fun buildMermaidHtml(source: String): String {
    val escaped = source.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return """<!DOCTYPE html>
<html><head><meta charset="utf-8">
<script src="mermaid.min.js"></script>
<style>
  html,body{margin:0;padding:8px;background:transparent;}
  #chart{display:flex;justify-content:center;min-height:40px;}
  #chart svg{max-width:100%;height:auto;}
</style>
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
    val toolCards: List<ChatToolCard> = emptyList(),
    /** 附带图片路径（用户消息；渲染缩略图，第20批）。 */
    val images: List<String> = emptyList(),
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
)
