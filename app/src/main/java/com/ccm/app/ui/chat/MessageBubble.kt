package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    // ★ webgap #1：长按复制（连复制 AI 回复都做不到是最低门槛的缺口）
    val copyCtx = androidx.compose.ui.platform.LocalContext.current
    val copyText: (String) -> Unit = { txt ->
        if (txt.isNotBlank()) {
            try {
                val cm = copyCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("CCM", txt))
                android.widget.Toast.makeText(copyCtx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 实测 pad: 15.72px 16px
            .padding(horizontal = 16.dp, vertical = 15.72.dp)
            .combinedClickable(
                onClick = {},
                onLongClick = { copyText(text) },
            ),   // combinedClickable = 实验 API（OptIn 见函数注解）
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
    // 长按菜单（webgap #1 扩展）：有 onResend 弹「复制 / 重发」，
    // 没有则退回直接复制（向后兼容）。
    var showMenu by remember { mutableStateOf(false) }
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

        Box(
            modifier = Modifier
                .widthIn(max = 314.dp)                   // max-w-[85%] ≈ 393×0.8
                .clip(RoundedCornerShape(11.04.dp))      // rounded-xl
                .background(colors.hover)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        if (onResend != null) showMenu = true else copyUser(text)
                    },
                )
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
                    Text(
                        text = shownText,
                        style = CCMText.body14,
                        color = colors.textMain,
                    )
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

        // 时间戳（webgap #1 最后一件）——气泡右下，格式 HH:mm；
        //   messageId 解析不出时间（历史恢复）时不显示
        if (timestampMs > 0) {
            Text(
                text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(timestampMs)),
                style = CCMText.body10,
                color = colors.textSecondary.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 2.dp, end = 4.dp),
            )
        }
    }

    // 长按菜单（复制 / 重发）—— 仅当提供 onResend 时可达
    if (showMenu && onResend != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showMenu = false },
            text = {
                Column {
                    Text(
                        text = "复制",
                        style = CCMText.body13,
                        color = colors.textMain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                copyUser(text)
                                showMenu = false
                            }
                            .padding(vertical = 11.dp),
                    )
                    Text(
                        text = "重发（截断此后的内容重新跑）",
                        style = CCMText.body13,
                        color = colors.textMain,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onResend()
                                showMenu = false
                            }
                            .padding(vertical = 11.dp),
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showMenu = false }) {
                    Text("取消", style = CCMText.body13)
                }
            },
        )
    }
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

        bubbles.forEach { bubble ->
            if (bubble.isUser) {
                UserBubble(
                    text = bubble.text,
                    images = bubble.images,
                    onResend = onResend?.let { fn -> { fn(bubble.messageId) } },
                    timestampMs = bubble.timestamp,
                )
            } else {
                // 定型消息：思考已结束（isThinking=false，组件自己合成 done 事件）
                if (bubble.thinking.isNotBlank()) {
                    AssistantThinkingChain(
                        thinking = bubble.thinking,
                        isThinking = false,
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                AssistantBubble(text = bubble.text)
            }
        }

        // 流式内容（未定型）
        if (streaming.isNotBlank() || streamingThinking.isNotBlank()) {
            // 流式中：思考在前（先想后说），running 驱动 isThinking 动效
            if (streamingThinking.isNotBlank()) {
                AssistantThinkingChain(
                    thinking = streamingThinking,
                    isThinking = streamingRunning,
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (streaming.isNotBlank()) {
                AssistantBubble(text = streaming)
            }
        }

        // 本轮工具卡片
        toolCards.forEach { card ->
            Spacer(Modifier.height(3.68.dp))
            ToolCard(card = card)
        }
    }
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
