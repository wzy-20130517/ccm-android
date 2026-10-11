package com.ccm.app.tools.task

import kotlinx.coroutines.CancellationException

/**
 * Goal 运行时驱动器 —— 把「完成契约」变成**跨轮自动推进**。
 *
 * 对齐 CLI `core/goal-runtime.mjs` 的 `runGoalLoop`（318 行里的驱动器部分）。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么驱动器在 agent 循环**之外**
 * ══════════════════════════════════════════════════════════════
 *
 * CLI 的注释写得很清楚，这里原样保留这个设计：
 *
 * > agent 已经有 watchMode（无限续轮、**无终止条件**）。goal 的续轮语义完全不同：
 * > 它要在每轮之间做终止判定、算预算、可能改状态。塞进 agent 主循环等于让
 * > agent 同时懂两套续轮规则，以后改一个必然碰坏另一个。
 * >
 * > 所以 goal 在 `agent.run` **之外**循环：每次 run 是一个 goal turn，
 * > run 返回后由驱动器决定要不要再 run 一次。**agent 完全不知道 goal 存在**。
 *
 * APK 同样如此：本类不 import AgentLoop，而是让调用方注入 [runTurn]。
 * 好处有两个 ——
 * 1. 不依赖 Flow/协程细节，**可单测**（传一个计数用的假 runTurn 即可）
 * 2. `core` 与 `tools` 的依赖方向不变（本类在 tools，不反向依赖 core）
 *
 * ══════════════════════════════════════════════════════════════
 *  三条终止路径（与 CLI 逐条对齐）
 * ══════════════════════════════════════════════════════════════
 *
 * | 路径 | 触发 | 做什么 |
 * |---|---|---|
 * | **模型自报** | 调了 GoalStatus | 状态不再是 active → 立即结束 |
 * | **预算耗尽** | 轮次/时间/token 用完 | **多跑一轮收尾**（[buildBudgetStopPrompt]），不静默中断 |
 * | **外部叫停** | 用户发新消息 / Ctrl+C | 转 paused 保留进度，可 /goal resume |
 *
 * **为什么预算耗尽要多跑一轮**：直接停会让用户看到「跑到一半没了」，
 * 既不知道做到哪、也不知道下一步。多一轮专门写交接，是任务书第 5 条的硬要求。
 *
 * @param store 目标存储（读写契约与预算）
 * @param sessionId 当前会话（目标按会话隔离）
 * @param runTurn 跑一个 goal turn（注入：调用方接 AgentLoop.run 或 ChatSession.send）
 * @param getTokens 取累计 token（预算用；不接就恒 0，token 预算失效但不报错）
 * @param shouldStop 外部叫停（用户又输入了新消息时返回 true）
 * @param onTurn 每轮结束回调（UI 刷新进度用；参数 = 当前目标快照 + 阶段描述）
 */
class GoalRuntime(
    private val store: GoalStore,
    private val sessionId: String,
    private val runTurn: suspend (String) -> Unit,
    private val getTokens: () -> Long = { 0L },
    private val shouldStop: () -> Boolean = { false },
    private val onTurn: (GoalStore.Goal?, String) -> Unit = { _, _ -> },
) {

    /** 一次完整 goal 循环的结果。 */
    data class Outcome(
        /** 结束时的目标快照（null = 目标在跑的过程中被删了）。 */
        val goal: GoalStore.Goal?,
        /** 实际跑了几个 goal turn。 */
        val turns: Int,
        /**
         * 结束原因：
         * `complete` / `blocked` / `paused` / `budget` / `stopped` / `no_goal`
         */
        val reason: String,
    )

    /**
     * 跑完整条 goal 循环（**挂起直到目标终止**）。
     *
     * @param firstMessage 第一个 goal turn 的输入（通常是用户原话 + 契约）
     */
    suspend fun run(firstMessage: String): Outcome {
        var turns = 0
        var next = firstMessage
        var closing = false   // 已进入收尾轮：这轮跑完无论如何都结束

        while (true) {
            // ── 轮首：外部叫停 ──
            if (shouldStop()) {
                store.tryStatus(sessionId, "paused", "用户发来新消息，目标暂停（/goal resume 可继续）")
                val g = store.get(sessionId)
                onTurn(g, "stopped")
                return Outcome(g, turns, "stopped")
            }

            val before = store.get(sessionId) ?: return Outcome(null, turns, "no_goal")
            // 非 active（complete / blocked / paused）→ 不再推进
            if (before.status != "active") {
                return Outcome(before, turns, before.status)
            }

            // ── 预算检查（在跑这一轮**之前**）──
            val exhausted = exhaustedReason(before)
            if (exhausted != null && !closing) {
                // 预算到头：再给一轮，只准写交接
                closing = true
                next = buildBudgetStopPrompt(before, exhausted)
            }

            // ── 跑这一轮 ──
            val g = store.consumeTurn(sessionId) ?: return Outcome(null, turns, "no_goal")
            turns++

            try {
                runTurn(next)
            } catch (e: CancellationException) {
                // 用户 Ctrl+C —— 不算失败也不算完成：转 paused 保留进度
                store.tryStatus(sessionId, "paused", "被用户中断（/goal resume 可继续）")
                val pg = store.get(sessionId)
                onTurn(pg, "paused")
                throw e
            }

            // token 用量记账（供 token 预算）
            val tokens = try {
                getTokens()
            } catch (_: Throwable) {
                0L
            }
            if (tokens > 0) store.addTokens(sessionId, tokens)

            val after = store.get(sessionId) ?: return Outcome(null, turns, "no_goal")
            onTurn(after, "turn")

            // ── 收尾轮跑完就结束（无论模型说没说完）──
            if (closing) {
                return Outcome(after, turns, "budget")
            }

            // ── 模型自报终态 ──
            if (after.status != "active") {
                return Outcome(after, turns, after.status)
            }

            // ── 预算耗尽 → 标记进入收尾轮 ──
            val ex2 = exhaustedReason(after)
            if (ex2 != null) {
                closing = true
                next = buildBudgetStopPrompt(after, ex2)
                continue
            }

            next = buildContinuationPrompt(after)
        }
    }

    /**
     * 预算是否耗尽。返回 `"turns"` / `"time"` / `"tokens"`，未耗尽返回 null。
     *
     * 三条独立判定 —— 任何一条到头都算耗尽（对齐 CLI 的 `s.exhausted`）。
     */
    private fun exhaustedReason(g: GoalStore.Goal): String? {
        if (g.turnsUsed >= g.turnsBudget) return "turns"
        if (g.timeBudgetMs > 0 && g.startedAt > 0) {
            val elapsed = System.currentTimeMillis() - g.startedAt
            if (elapsed >= g.timeBudgetMs) return "time"
        }
        if (g.tokensBudget > 0 && g.tokensUsed >= g.tokensBudget) return "tokens"
        return null
    }

    companion object {

        /**
         * 每个 goal turn 注入的契约提醒（逐字对齐 CLI `buildGoalContract`）。
         *
         * **这段是整个功能的灵魂**：它把四要素重复摆在模型面前，并明确写清
         * 什么时候**不准**宣布完成、什么时候**不准**宣布阻塞。
         * 没有这些负向约束，模型会在第一轮写个计划就 complete。
         */
        fun buildGoalContract(g: GoalStore.Goal): String {
            val sb = StringBuilder()
            sb.append("# 当前目标（完成契约 · 非普通待办）\n")
            sb.append("目标：").append(g.description).append('\n')
            sb.append("完成判据：").append(
                g.proof.ifBlank {
                    "(未指定 —— 你必须先把它明确成一个可检查的判据，并在回复里告诉用户你采用了什么判据)"
                },
            ).append('\n')
            if (g.bound.isNotBlank()) {
                sb.append("边界（不得越界）：\n  - ").append(g.bound).append('\n')
            } else {
                sb.append("边界：未显式限定。但不得改动与目标无关的文件，不得扩大范围。\n")
            }
            sb.append("预算：").append(budgetLine(g)).append('\n')
            sb.append('\n')

            sb.append("## 本轮怎么做\n")
            sb.append("- 先对照目标和完成判据，看已完成到哪，然后挑**一个有界、有用的切片**推进，不要试图一轮做完。\n")
            sb.append("- 自查要简短。不要反复重述目标、不要探索与目标无关的解读。\n")
            sb.append("- 目标之外的东西不要动。越界比慢更严重。\n")
            sb.append('\n')

            sb.append("## 怎么结束（只有这三条路）\n")
            sb.append("- **还有实质工作** → 正常结束本轮，不要调 GoalStatus。runtime 会自动给你下一轮，你不需要请示用户。\n")
            sb.append("- **真的做完了** → 调 GoalStatus(status:\"complete\")，并在同一轮回复里写清：做了什么、完成判据如何被验证通过的（贴证据：命令、输出、测试结果）。\n")
            sb.append("- **真的卡死了** → 调 GoalStatus(status:\"blocked\", reason:\"...\"）。\n")
            sb.append('\n')

            sb.append("## 完成审计（调 complete 前必须过）\n")
            sb.append("- 逐条对照目标里的**每一项显式要求**，不是只对照你自己挑的那部分。\n")
            sb.append("- 间接证据、「应该没问题」、「看起来对」 = **未完成**。要有实际跑过的验证。\n")
            sb.append("- 只产出了计划 / 摘要 / 初稿 / 部分结果 → **未完成**，不许 complete。\n")
            sb.append("- **预算快用完不是完成的理由**。预算耗尽由 runtime 处理，不要为了收尾谎报完成。\n")
            sb.append('\n')

            sb.append("## 阻塞审计（调 blocked 前必须过）\n")
            val canBlock = g.blockedStreak >= GoalStore.BLOCKED_STREAK_THRESHOLD
            sb.append("- 第一次遇到障碍**不准**判 blocked。同一个障碍必须连续 ")
                .append(GoalStore.BLOCKED_STREAK_THRESHOLD)
                .append(" 个 goal turn 复现才算。当前该障碍已连续 ")
                .append(g.blockedStreak)
                .append(" 轮")
                .append(
                    if (canBlock) "（已达阈值，可以判 blocked）"
                    else "（未达 ${GoalStore.BLOCKED_STREAK_THRESHOLD}，继续想别的办法）",
                )
                .append("。\n")
            sb.append("- 只有这些算真阻塞：缺凭据/权限、需要用户决策、外部条件不满足、同一技术故障反复失败。\n")
            sb.append("- **不算阻塞**：活儿大、活儿难、慢、还没验证、不确定、想要更多轮次、想找用户确认一下。这些一律继续干。\n")
            sb.append("- 但如果目标本身**不可能、自相矛盾、或不安全**，当轮直接判 blocked，不要白烧预算。\n")
            sb.append('\n')

            sb.append(
                if (g.turnsBudget > 0 && g.turnsUsed.toDouble() / g.turnsBudget >= CONVERGE_FRACTION) {
                    "预算提示：已用掉 ${(g.turnsUsed * 100 / g.turnsBudget)}%，接近上限。收敛到目标本身，不要再开新的可选工作。"
                } else {
                    "预算提示：预算充裕，稳步推进即可。"
                },
            )
            return sb.toString()
        }

        /** 预算一行文本（提示词与 UI 用同一份口径）。 */
        fun budgetLine(g: GoalStore.Goal): String {
            val parts = mutableListOf("轮次 ${g.turnsUsed}/${g.turnsBudget}")
            if (g.timeBudgetMs > 0 && g.startedAt > 0) {
                val elapsed = System.currentTimeMillis() - g.startedAt
                parts += "时间 ${formatElapsed(elapsed)}/${formatElapsed(g.timeBudgetMs)}"
            } else {
                val elapsed = if (g.startedAt > 0) System.currentTimeMillis() - g.startedAt else 0
                parts += "已用时 ${formatElapsed(elapsed)}"
            }
            if (g.tokensBudget > 0) parts += "token ${g.tokensUsed}/${g.tokensBudget}"
            return parts.joinToString(" · ")
        }

        /** 自动续轮的注入文本（对齐 CLI `buildContinuationPrompt`）。 */
        fun buildContinuationPrompt(g: GoalStore.Goal): String = buildString {
            append("（goal mode 自动续轮 · 非用户发言）\n")
            append("继续推进当前目标，这是第 ").append(g.turnsUsed + 1)
                .append(" 个 goal turn（预算 ").append(g.turnsBudget).append("）。\n\n")
            append(buildGoalContract(g))
        }

        /**
         * 预算耗尽时注入的收尾指令（对齐 CLI `buildBudgetStopPrompt`）。
         *
         * 要求交接、不许静默中断 —— 这是「预算用尽」与「任务失败」的区别：
         * 前者要留下可接手的信息。
         */
        fun buildBudgetStopPrompt(g: GoalStore.Goal, exhausted: String): String {
            val which = when (exhausted) {
                "turns" -> "轮次预算（${g.turnsBudget} 轮）"
                "time" -> "时间预算（${formatElapsed(g.timeBudgetMs)}）"
                else -> "token 预算（${g.tokensBudget}）"
            }
            return buildString {
                append("（goal mode · 预算耗尽，本轮是最后一轮 · 非用户发言）\n")
                append(which).append("已用尽，目标未达成。现在**只做交接，不要再改任何文件、不要再调工具**。\n\n")
                append("目标：").append(g.description).append('\n')
                append("完成判据：").append(g.proof.ifBlank { "(未指定)" }).append("\n\n")
                append("用这四段写一份交接（简短，不要客套）：\n")
                append("1. 已完成：哪些部分真的做完了，证据是什么\n")
                append("2. 未完成：还差什么，具体到文件/函数\n")
                append("3. 下一步：如果继续，第一件该做的事是什么\n")
                append("4. 建议：追加预算继续（/goal budget），还是拆小目标重设\n")
            }
        }

        /** 毫秒 → 人类可读（`12s` / `3m20s` / `1h05m`）。 */
        fun formatElapsed(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(0)
            return when {
                totalSec < 60 -> "${totalSec}s"
                totalSec < 3600 -> "${totalSec / 60}m${(totalSec % 60).toString().padStart(2, '0')}s"
                else -> "${totalSec / 3600}h${((totalSec % 3600) / 60).toString().padStart(2, '0')}m"
            }
        }

        /** 进入收敛的预算占比（对齐 CLI `CONVERGE_FRACTION`）。 */
        const val CONVERGE_FRACTION = 0.75
    }
}
