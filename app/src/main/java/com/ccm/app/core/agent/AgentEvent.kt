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
 *
 * 注意 [ToolResult] 与 [core.tool.ToolResult] **同名不同物** —— 前者是发给 UI 的事件
 * （已转成字符串），后者是工具内部返回值。import 时注意区分。
 */
sealed class AgentEvent {

    /** 正文增量（流式）。UI 应追加到当前气泡。 */
    data class TextDelta(val text: String) : AgentEvent()

    /** 思考过程增量（reasoning / thinking）。UI 通常折叠显示。 */
    data class ReasoningDelta(val text: String) : AgentEvent()

    /** 工具调用开始。[input] 是模型给的原始参数（未校验）。 */
    data class ToolStart(val id: String, val name: String, val input: JsonObject) : AgentEvent()

    /** 工具执行中的进度（覆盖式显示，不进历史）。 */
    data class ToolProgress(val id: String, val text: String) : AgentEvent()

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
