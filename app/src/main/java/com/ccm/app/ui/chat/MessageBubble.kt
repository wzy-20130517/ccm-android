package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
@Composable
fun AssistantBubble(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = CCMTheme.colors

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 实测 pad: 15.72px 16px
            .padding(horizontal = 16.dp, vertical = 15.72.dp),
    ) {
        // ★ 2026-09-27：原来是死 Text —— 加粗/代码块/列表全显示成
        //   星号和井号原文。MarkdownRenderer（690 行）写好了一直没人调，
        //   这里是它第一处上场。流式时的未闭合 fence 由
        //   parseMarkdown 的缓存路径处理（每帧重算，见 remember(content)）。
        MarkdownRenderer(
            content = text,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 用户消息气泡 —— 右对齐、浅色底、圆角。
 *
 * Web 侧用户消息用 `bg-claude-hover` 背景 + 右对齐。
 * 实测（对话页截图）：用户消息靠右，宽度自适应内容（`max-w-[85%]`）。
 */
@Composable
fun UserBubble(
    text: String,
    modifier: Modifier = Modifier,
    images: List<String> = emptyList(),
) {
    val colors = CCMTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.36.dp),
        horizontalArrangement = Arrangement.End,
    ) {
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
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(bottom = if (text.isNotBlank()) 6.dp else 0.dp),
                    ) {
                        images.forEach { path ->
                            com.ccm.app.ui.common.ThumbImage(
                                path = path,
                                modifier = Modifier.size(72.dp),
                                cornerRadius = 6.dp,
                            )
                        }
                    }
                }
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        style = CCMText.body14,
                        color = colors.textMain,
                    )
                }
            }
        }
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
                UserBubble(text = bubble.text, images = bubble.images)
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
