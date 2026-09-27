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
        // TODO(阶段4·B5-c): 换成真实 markdown 渲染
        //   需要处理：标题/列表/代码块/表格/链接/行内代码
        //   流式时还要处理未闭合 fence（见 StreamingText.kt）
        Text(
            text = text,
            // 实测 fs 13.362 / lh 20.043，衬线族
            style = CCMText.body13.copy(
                fontFamily = FontFamily.Serif,
                fontSize = 13.362.sp,
                lineHeight = 20.043.sp,
            ),
            color = colors.textModelBody,   // 暗色下是 #EDEAE1（暖白），与 textMain 不同
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
            Text(
                text = text,
                style = CCMText.body14,
                color = colors.textMain,
            )
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
) {
    Column(modifier = modifier.fillMaxWidth()) {
        bubbles.forEach { bubble ->
            if (bubble.isUser) {
                UserBubble(text = bubble.text)
            } else {
                AssistantBubble(text = bubble.text)
            }
        }

        // 流式内容（未定型）
        if (streaming.isNotBlank()) {
            AssistantBubble(text = streaming)
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
)
