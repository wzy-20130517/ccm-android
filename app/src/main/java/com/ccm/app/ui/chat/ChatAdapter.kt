package com.ccm.app.ui.chat

import com.ccm.app.core.ChatSession

/**
 * core 层 → UI 层的适配。
 *
 * ## 为什么要这层
 * UI 的数据类（[ChatBubble] / [ChatToolCard]）与 core 的
 * （`ChatSession.Bubble` / `ChatSession.ToolCard`）**字段一一对应但不该直接复用**：
 *
 * 1. **解耦**：core 改字段名不该逼 UI 跟着改（反之亦然）
 * 2. **UI 可以有派生字段**：[ChatBubble.key] 是 UI 侧的列表 key 约定
 * 3. **测试友好**：UI 预览不需要构造真实的 ChatSession
 *
 * 代价是每次状态更新要转换一次。实测开销可忽略（气泡数量级在几十，
 * 且 Compose 的 recomposition 只比对变化的部分）。
 *
 * ## 字段映射
 * | core | UI | 说明 |
 * |---|---|---|
 * | `State.bubbles` | `List<ChatBubble>` | role/text/messageId 原样 |
 * | `State.streaming` | `String` | 交给 [StreamingMarkdown] 记账 |
 * | `State.toolCards` | `List<ChatToolCard>` | 加 [ChatToolCard.displayName] |
 * | `State.error` | `String?` | 直接透传 |
 * | `State.inputTokens` | `Int` | 显示在输入栏 |
 */
object ChatAdapter {

    /** core 气泡 → UI 气泡 */
    fun toUi(bubble: ChatSession.Bubble): ChatBubble = ChatBubble(
        role = bubble.role,
        text = bubble.text,
        messageId = bubble.messageId,
    )

    /** core 工具卡片 → UI 工具卡片 */
    fun toUi(card: ChatSession.ToolCard): ChatToolCard = ChatToolCard(
        id = card.id,
        name = card.name,
        // Web 风格的显示文本：`Read a.mjs` 这种由 agent 层格式化在 preview 里，
        // 这里 displayName 留空 → UI 回退用 name
        displayName = "",
        preview = card.preview,
        running = card.running,
        isError = card.isError,
        progress = card.progress,
        result = card.result,
    )

    /** 整个 State 一次性转换（UI 主路径用这个） */
    fun toUi(state: ChatSession.State): UiChatState = UiChatState(
        bubbles = state.bubbles.map(::toUi),
        streaming = state.streaming,
        running = state.running,
        toolCards = state.toolCards.map(::toUi),
        error = state.error,
        inputTokens = state.inputTokens,
        outputTokens = state.outputTokens,
        isEmpty = state.isEmpty,
    )
}

/**
 * UI 侧的会话状态快照 —— 与 `ChatSession.State` 同构但独立。
 *
 * Compose 里用 `collectAsState()` 订阅，每次变化触发重组。
 */
data class UiChatState(
    val bubbles: List<ChatBubble> = emptyList(),
    val streaming: String = "",
    val running: Boolean = false,
    val toolCards: List<ChatToolCard> = emptyList(),
    val error: String? = null,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val isEmpty: Boolean = true,
) {
    /** 输入栏显示的 token 计数（Web 显示最近一次的 input tokens） */
    val displayTokens: Int get() = inputTokens
}
