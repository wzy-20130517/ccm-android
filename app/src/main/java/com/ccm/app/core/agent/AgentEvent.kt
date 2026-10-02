package com.ccm.app.core.agent

import kotlinx.serialization.json.JsonObject

/**
 * Agent 循环对外输出的事件流。
 *
 * dev-core 提供，dev-ui 消费 —— UI 订阅 `AgentLoop.run()` 返回的 `Flow<AgentEvent>`
 * 来渲染对话、工具卡片、思考过程、状态行。
 *
 * ## 用法（dev-ui 视角）
 * ```
 * agentLoop.run(userMessage).collect { event ->
 *     when (event) {
 *         is AgentEvent.TextDelta -> appendToCurrentBubble(event.text)
 *         is AgentEvent.ToolStart -> addToolCard(event.name)
 *         is AgentEvent.ToolResult -> finishToolCard(event.isError)
 *         is AgentEvent.Done -> finishTurn()
 *         else -> Unit
 *     }
 * }
 * ```
 *
 * ## 契约冻结
 * 签名由 `/sdcard/Download/claude-workspace/rewrite/CONTRACTS.md` 第三节定义。
 * 改动前先发消息给 main。
 *
 * ## 事件顺序保证
 * 一次 `run()` 内：
 * 1. `TextDelta` / `ReasoningDelta` 可能交错（模型边想边说）
 * 2. `ToolStart` → （`ToolProgress`*）→ `ToolResult`，**同一 id 一定配对**
 * 3. 每个 turn 结束发 `TurnEnd`
 * 4. 最后恰好一个 [Done]（**即使中途出错也会发**，UI 靠它收尾）
 * 5. [Error] 之后流可能继续（可重试的错误会自动重试），也可能直接到 [Done]
 * 6. **[Done] 一定在最后一个 [TextDelta] 之后** —— UI 收到 Done 就可以「定型」当前气泡，
 *    不会再有增量补进来
 *
 * 注意 [ToolResult] 与 [core.tool.ToolResult] **同名不同物** —— 前者是发给 UI 的事件
 * （已转成字符串），后者是工具内部返回值。import 时注意区分。
 *
 * ## messageId 的语义（UI 必须按这个用）
 *
 * `messageId` 标识**一次 API 响应的输出**：
 * - 每次新的 API 响应开始 → **新 id**（包括：新 turn、以及**重试**）
 * - 同一次响应内连续吐的 [TextDelta] / [ReasoningDelta] → **共享同一个 id**
 *
 * ### UI 为什么需要它（不是可选项，是刚需）
 * [Error] 之后流**可能继续**（可重试的错误会自动重试）。重试时上一轮已经吐出去的
 * 半截正文怎么办？UI 只有两种朴素做法，**两种都是错的**：
 * - 不清空 → 用户看到「半截话 + 重试后的完整话」拼在一起
 * - 收到 Error 就清空 → 但 Error 不一定触发重试（可能是终止性错误），
 *   那时清空会让用户看不到失败前的输出
 *
 * 有 id 就精确了：
 * ```
 * if (event.messageId != currentId) {
 *     currentId = event.messageId
 *     discardCurrentBubble()      // 上一轮的半截输出丢掉
 *     startNewBubble()
 * }
 * append(event.text)
 * ```
 * 另外它还支持「一轮内 说话 → 调工具 → 再说」渲染成两个气泡夹一张工具卡。
 */
sealed class AgentEvent {

    /**
     * 正文增量（流式）。UI 应追加到当前气泡。
     *
     * @param messageId **标识「一次 API 响应的输出」**（见下方说明）。
     */
    data class TextDelta(val text: String, val messageId: String) : AgentEvent()

    /**
     * 思考过程增量（reasoning / thinking）。UI 通常折叠显示。
     *
     * @param messageId 同 [TextDelta.messageId]。
     */
    data class ReasoningDelta(val text: String, val messageId: String) : AgentEvent()

    /**
     * 工具调用开始。
     *
     * @param input 模型给的原始参数（未校验）。**展开详情时用**。
     * @param inputPreview 折叠态一行摘要（如 `path="a.mjs", limit=50`）。
     *
     * 为什么由 agent 层生成 [inputPreview] 而不是让 UI 自己格式化：
     * 「哪些字段值得显示」是**业务判断**，每个工具不一样；放在 UI 层等于
     * 让 Compose 重复实现一遍 agent 的信息组织逻辑，两边迟早漂移。
     */
    data class ToolStart(
        val id: String,
        val name: String,
        val input: JsonObject,
        val inputPreview: String,
    ) : AgentEvent()

    /** 工具执行中的进度（覆盖式显示，不进历史）。 */
    data class ToolProgress(val id: String, val text: String) : AgentEvent()

    /** Present 富内容展示事件。 */
    data class Present(
        val kind: String,
        val title: String?,
        val caption: String?,
        val content: String,
        val paths: List<String>,
    ) : AgentEvent()

    /** 工具执行完成。[result] 是已截断的结果文本。 */
    data class ToolResult(
        val id: String,
        val name: String,
        val result: String,
        val isError: Boolean,
    ) : AgentEvent()

    /** 一个 turn 结束（一次「模型回复 + 工具执行」循环）。 */
    data class TurnEnd(val turn: Int) : AgentEvent()

    /** token 用量（通常每个 turn 末发一次）。 */
    data class Usage(val inputTokens: Int, val outputTokens: Int) : AgentEvent()

    /** 错误。[category] 取值见 [core.tool.ToolResult] 的常量。 */
    data class Error(val message: String, val category: String) : AgentEvent()

    /** 流结束。**恰好发一次**，UI 用它收尾（关 spinner、恢复输入框）。 */
    object Done : AgentEvent()

    companion object {
        // ───────── 错误分类常量（与 core/tool/ToolResult 保持同一套字符串） ─────────
        //
        // 这里重复定义一份是因为 agent 层的错误不全来自工具（还有 API 层的
        // context_overflow / stream_timeout / connect_timeout 等）。
        // **字符串必须与 Node 版 _classifyError 的 name 字段一致**，
        // 否则历史 trace 和新日志对不上。

        const val ERR_UNKNOWN = "unknown"
        const val ERR_CONTEXT_OVERFLOW = "context_overflow"
        const val ERR_STREAM_TIMEOUT = "stream_timeout"
        const val ERR_CONNECT_TIMEOUT = "connect_timeout"
        const val ERR_AUTH = "auth"
        const val ERR_RATE_LIMIT = "rate_limit"
        const val ERR_SERVER = "server"
        const val ERR_CLIENT_4XX = "client_4xx"
        const val ERR_NETWORK = "network"
    }
}
