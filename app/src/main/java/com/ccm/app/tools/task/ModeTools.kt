package com.ccm.app.tools.task

import com.ccm.app.core.agent.ModeState
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import kotlinx.serialization.json.JsonObject

/**
 * 模式工具组（6 个）—— 计划 / deep / 持续三个模式的进出。
 *
 * 对齐 CLI `core/plan.mjs` 的 `EnterPlanModeTool` / `ExitPlanModeTool` /
 * `EnterDeepModeTool` / `ExitDeepModeTool` / `EnterWatchTool` / `ExitWatchTool`。
 *
 * ## APK 原本一个都没有
 * 三个模式的**状态**和**循环语义**本来就不存在（`AgentLoop` 里既没有
 * watchMode 也没有 deepMode 字段），所以这 6 个工具不是「补齐工具壳子」，
 * 而是把整条模式链路接起来的一部分。见 [ModeState] 的说明。
 *
 * ## 每个工具写的是哪个状态
 * | 工具 | 写 | 主循环怎么读 |
 * |---|---|---|
 * | EnterPlanMode / ExitPlanMode | `ModeState.planMode` | 每轮拼系统提示词时追加 [ModeState.PLAN_PROMPT] |
 * | EnterDeepMode / ExitDeepMode | `ModeState.deepMode` | 每轮 run 开始时 `maxTurns = DEEP/NORMAL` |
 * | EnterWatch / ExitWatch | `ModeState.watchMode` | 循环条件 `watchMode \|\| turnCount < maxTurns` |
 *
 * ## 为什么工具里不直接改 AgentLoop
 * 工具在 AgentLoop **之前**构造（`core` 不能依赖 `tools`，所以工具只能拿到
 * 一个 interface/回调）。共享状态放在 [ModeState] 里，两边都引用它 ——
 * 与 CLI 的 `new DeepMode()` + `() => deepMode.enable()` 闭包是同一套结构。
 *
 * @param modes 共享的模式状态（与 AgentLoop 同一个实例）
 */
class ModeTools(private val modes: ModeState) {

    // ═════════════════════════ 计划模式 ═════════════════════════

    inner class EnterPlanModeTool : Tool() {
        override val name = "EnterPlanMode"
        override val description = "进入计划模式"
        override val inputSchema: JsonObject = ToolSchema.noArgsSchema()

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            modes.planMode = true
            return ToolResult.ok("已进入计划模式")
        }
    }

    inner class ExitPlanModeTool : Tool() {
        override val name = "ExitPlanMode"
        override val description = "退出计划模式"
        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "execute_immediately" to ToolSchema.boolean(
                "true = 退出后立刻按计划开工（CLI 参数，APK 语义相同：只是退出计划模式）",
            ),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            modes.planMode = false
            return ToolResult.ok("已退出计划模式")
        }
    }

    // ═════════════════════════ deep 模式 ═════════════════════════

    inner class EnterDeepModeTool : Tool() {
        override val name = "EnterDeepMode"
        override val description =
            "进入 deep 模式（maxTurns 提升至 ${ModeState.DEEP_MAX_TURNS}，用于复杂多步骤任务）。" +
                "适用于：任务确实复杂（多文件改动、反复迭代调试），" +
                "或接近轮数上限但确认没有空转、还需要更多轮才能做完时（自救，不用等用户）。"
        override val inputSchema: JsonObject = ToolSchema.noArgsSchema()

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            modes.deepMode = true
            return ToolResult.ok("已进入 deep 模式，maxTurns 提升至 ${ModeState.DEEP_MAX_TURNS}")
        }
    }

    inner class ExitDeepModeTool : Tool() {
        override val name = "ExitDeepMode"
        override val description = "退出 deep 模式，恢复默认 maxTurns"
        override val inputSchema: JsonObject = ToolSchema.noArgsSchema()

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            modes.deepMode = false
            return ToolResult.ok("已退出 deep 模式，maxTurns 恢复 ${ModeState.NORMAL_MAX_TURNS}")
        }
    }

    // ═════════════════════════ 持续模式（watch）═════════════════════════

    inner class EnterWatchTool : Tool() {
        override val name = "EnterWatch"
        override val description =
            "进入持续模式（watch）：本轮结束后不主动停止回复，持续执行/监听，" +
                "直到 ExitWatch 或用户打断（Ctrl+C）。" +
                "仅在任务确实是「持续性」的（盯队列、轮询状态、持续下歌等）才用；一次性任务不要开。"
        override val inputSchema: JsonObject = ToolSchema.noArgsSchema()

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            modes.watchMode = true
            return ToolResult.ok("已进入持续模式：本轮结束后将继续执行/监听，不会主动停。用 ExitWatch 或让用户 Ctrl+C 退出。")
        }
    }

    inner class ExitWatchTool : Tool() {
        override val name = "ExitWatch"
        override val description = "退出持续模式（watch），恢复正常「一轮结束即停」。持续任务已完成或不再有时调用。"
        override val inputSchema: JsonObject = ToolSchema.noArgsSchema()

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            modes.watchMode = false
            return ToolResult.ok("已退出持续模式：本轮结束后将正常停止回复。")
        }
    }

    /** 供 ToolsBootstrap 一次性注册（顺序与 CLI 一致）。 */
    fun all(): List<Tool> = listOf(
        EnterPlanModeTool(),
        ExitPlanModeTool(),
        EnterDeepModeTool(),
        ExitDeepModeTool(),
        EnterWatchTool(),
        ExitWatchTool(),
    )
}
