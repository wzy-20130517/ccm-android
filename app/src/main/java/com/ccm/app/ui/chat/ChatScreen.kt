package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 聊天主界面容器 —— 对齐 Web `MainContent.tsx`（5537 行，全项目最难）。
 *
 * ## 拆分策略（按 recon-b §9 的区块表）
 * ```
 * ChatScreen（本文件）        容器：对话标题栏 + 消息区 + 输入栏
 * ├── ChatHeaderBar            顶栏下第二行（标题 + 下拉 + Export）
 * ├── MessageList              消息流（MessageBubble.kt）
 * │   ├── UserBubble           用户气泡（右对齐、浅底）
 * │   ├── AssistantBubble      助手正文（全宽、衬线、无背景）
 * │   └── ToolCard             工具卡片（ToolCard.kt）
 * └── InputBar                 输入栏（InputBar.kt）
 * ```
 *
 * > Web 的 MainContent 有 5537 行，直接照搬会重蹈「4085 行单文件」的覆辙。
 * > 这里按上表拆成 5 个文件，每个 < 400 行。
 *
 * ## 实测布局（Playwright，393×852，`#/chat/:id`）
 * ```
 * y=0    顶栏（44，由 AppScaffold 提供）
 * y=44   对话标题栏（h≈40：标题 + 下拉箭头 | Export 按钮）
 * y=84.5 消息区（pad 15.72×16，衬线 13.362/20.043）
 * ...    消息区可滚动
 * y=667  输入卡片（浮动，h=97.64）
 * y=765  底部状态行（"Claude 是 AI，可能会出错。请核对回复内容。"）
 * ```
 *
 * ## 对话标题栏
 * 实测：标题在左（`t` + 下拉箭头，`px-1.5 py-2 rounded-lg hover:bg-claude-hover`），
 * Export 按钮在右（77.92×40 / 圆角 8 / 描边 / `px-4`）。
 *
 * @param bubbles     已定型消息
 * @param streaming   流式中内容
 * @param toolCards   本轮工具卡片
 * @param input       输入框文本
 * @param running     是否正在跑
 * @param title       对话标题
 */
@Composable
fun ChatScreen(
    bubbles: List<ChatBubble>,
    modifier: Modifier = Modifier,
    streaming: String = "",
    toolCards: List<ChatToolCard> = emptyList(),
    input: String = "",
    running: Boolean = false,
    title: String = "新对话",
    modelName: String = "Sonnet 4.6",
    tokenCount: Int = 0,
    errorMessage: String? = null,
    onInputChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onExport: () -> Unit = {},
    onRename: () -> Unit = {},
) {
    val colors = CCMTheme.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 对话标题栏 ────────────────────────────────────────────
            ChatHeaderBar(
                title = title,
                onRename = onRename,
                onExport = onExport,
            )

            // ── 消息区（可滚动，底部留出输入栏高度）──────────────────
            Box(modifier = Modifier.weight(1f)) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    MessageList(
                        bubbles = bubbles,
                        streaming = streaming,
                        toolCards = toolCards,
                    )
                    // 底部留白：给浮动输入栏让位
                    Spacer(Modifier.height(140.dp))
                }
            }
        }

        // ── 输入栏 + 底部状态行（浮在底部）──────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        ) {
            if (errorMessage != null) {
                ErrorBanner(message = errorMessage)
                Spacer(Modifier.height(7.36.dp))
            }

            InputBar(
                value = input,
                onValueChange = onInputChange,
                onSend = onSend,
                onStop = onStop,
                running = running,
                modelName = modelName,
                tokenCount = tokenCount,
            )

            Spacer(Modifier.height(7.36.dp))

            // 底部状态行 —— 实测文案
            Text(
                text = "Claude 是 AI，可能会出错。请核对回复内容。",
                style = CCMText.body11,
                color = colors.textSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 11.04.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * 对话标题栏 —— 实测 h≈40。
 *
 * 左：标题（可点重命名）+ 下拉箭头；右：Export 按钮（77.92×40 / 圆角 8 / 描边）。
 */
@Composable
private fun ChatHeaderBar(
    title: String,
    onRename: () -> Unit,
    onExport: () -> Unit,
) {
    val colors = CCMTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.72.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左：标题 + 下拉（实测 px-1.5 py-2 rounded-lg）
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(7.36.dp))
                .clickable(onClick = onRename)
                .padding(horizontal = 5.52.dp, vertical = 7.36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.52.dp),
        ) {
            Text(
                text = title,
                style = CCMText.body13,
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 下拉箭头（表示可切换对话）
            Box(
                modifier = Modifier
                    .size(13.8.dp)
                    .clip(RoundedCornerShape(3.68.dp))
                    .background(colors.hover),
            )
        }

        // 右：Export 按钮（实测 77.92×40 / 圆角 8 / 1px 描边 / px-4 / fs 14 / fw 500）
        Box(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onExport)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Export",
                style = CCMText.body14.copy(
                    fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                ),
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * 错误横幅 —— 对齐 Web 的错误提示样式。
 *
 * Web 用红色系（`bg-red-50 dark:bg-red-900/20` + `border-red-200`）。
 */
@Composable
private fun ErrorBanner(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.72.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(Color(0x14DC2626))
            .padding(horizontal = 12.88.dp, vertical = 8.28.dp),
    ) {
        Text(
            text = message,
            style = CCMText.body12,
            color = Color(0xFFB91C1C),
        )
    }
}
