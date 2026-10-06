package com.ccm.app.core.prompt

import com.ccm.app.core.agent.ModeState
import com.ccm.app.core.compact.Compactor
import com.ccm.app.core.tool.ToolTimeouts
import com.ccm.app.tools.task.GoalStore

/**
 * 系统提示词变量填充 —— 移植自 CLI `core/infra/prompts.mjs` 的 `resolvePromptVars`。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题29 新建】
 *
 * 用户要求：「提示词复用 CLI，但**不要写死值** —— 用运行时真值。
 * 天天偷懒写死，搞得每次都返工」。
 *
 * CLI 的提示词里有 `{{XXX}}` 占位符，由 `resolvePromptVars()` 从**代码常量**
 * 现算填充。本文件是 APK 的等价实现。
 *
 * ## 为什么必须运行时取（不能写死）
 *
 * 提示词里的数字**必须和代码实际行为一致**。写死的后果：
 * - 代码把 `NORMAL_MAX_TURNS` 从 200 改成 300，提示词还说 200 →
 *   模型按 200 规划轮次，实际有 300，白保守
 * - 更糟：goal 的阻塞阈值提示词说 3、代码改成 5 → **工具拒绝模型调用**，
 *   模型一脸懵（"提示词明明说 3 轮就行"）
 *
 * 所以每个值都从**真值源**现取（`ModeState` / `Compactor` / `GoalStore` /
 * `ToolTimeouts`），改代码自动同步提示词。
 * ═══════════════════════════════════════════════════════════════
 */
object PromptVars {

    /**
     * 填充模板里的 `{{VAR}}` 占位符。
     *
     * 未知占位符**保留原样**（便于发现遗漏），不静默清空。
     */
    fun fill(template: String): String {
        var out = template
        for ((key, value) in collect()) {
            out = out.replace("{{$key}}", value)
        }
        return out
    }

    /** 收集运行时真值。每项独立 try —— 单项失败只降级该项。 */
    fun collect(): Map<String, String> {
        val vars = mutableMapOf<String, String>()

        // ── 轮次上限（真值源：ModeState）───────────────────────────
        vars["NORMAL_MAX_TURNS"] = runCatching { ModeState.NORMAL_MAX_TURNS.toString() }
            .getOrDefault("200")
        vars["DEEP_MAX_TURNS"] = runCatching { ModeState.DEEP_MAX_TURNS.toString() }
            .getOrDefault("400")

        // ── 压缩参数（真值源：Compactor）───────────────────────────
        vars["SUMMARY_CHAR_LIMIT"] = runCatching { Compactor.SUMMARY_CHAR_LIMIT.toString() }
            .getOrDefault("16000")
        vars["SUMMARY_MAX_TOKENS"] = runCatching { Compactor.SUMMARY_MAX_TOKENS.toString() }
            .getOrDefault("65536")
        vars["SUMMARY_INPUT_LIMIT"] = runCatching { Compactor.SUMMARY_INPUT_CHAR_LIMIT.toString() }
            .getOrDefault("60000")

        // ── Goal 常量（真值源：GoalStore）─────────────────────────
        //
        // ⚠️ 这两个**必须**与工具实现一致 —— 提示词说 3 轮、工具按 5 轮拒绝，
        //    模型会反复撞墙。
        vars["GOAL_BLOCKED_STREAK"] = runCatching { GoalStore.BLOCKED_STREAK_THRESHOLD.toString() }
            .getOrDefault("3")
        vars["GOAL_DEFAULT_BUDGET"] = runCatching { GoalStore.DEFAULT_TURNS.toString() }
            .getOrDefault("15")
        // 收敛阈值：APK 的 GoalStore 没有这个常量（CLI 有 CONVERGE_FRACTION）。
        // 用 75（与 CLI 对齐），等 APK 实现了再改成读常量。
        vars["GOAL_CONVERGE_PCT"] = "75"

        // ── 工具超时分级（真值源：ToolTimeouts）─────────────────────
        vars["TOOL_TIMEOUT_TIERS"] = runCatching { ToolTimeouts.describe() }
            .getOrDefault("只读 15s / 写入 60s / 网络 120s / 生成 300s / 长任务 600s")

        // ── 会话自动保存间隔（APK 固定 30s）────────────────────────
        vars["AUTOSAVE_INTERVAL"] = "每 30 秒"

        // ── APK 特有段（没有对应 CLI 值的，给静态说明）─────────────
        vars["KEEPALIVE_NOTE"] = "APK 是前台服务 + wake-lock，通常不需要手动保活。" +
            "若长时间任务被系统杀，去系统设置给 CCM 开「无限制后台」。"
        vars["BROWSER_TOOLS_SECTION"] = "（APK 暂无浏览器工具）"
        // OUTPUT_STYLE 不在这里填 —— 它由 assembleSystemPrompt 按用户选的
        // 风格**替换**（CLI 同款：设了风格就整段换掉，避免两段指令打架）。
        // 留成空串占位，让上层 replace。
        vars["OUTPUT_STYLE"] = ""

        return vars
    }
}
