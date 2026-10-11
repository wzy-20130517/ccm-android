package com.ccm.app.tools.task

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonObject

/**
 * Goal 工具组（3 个）—— 完成契约的读写与收尾。
 *
 * 参照 Node 版 `core/tools-goal.mjs`（186 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 刻意**不给** CreateGoal（CCM 的设计决策，原样保留）
 * ══════════════════════════════════════════════════════════════
 *
 * > 目标由用户用 `/goal` 设定。让模型自己造目标 = 它可以给自己发
 * > **无人监督的长跑许可**（还带预算），这是权限放大，不是功能。
 * > 模型要建议目标就用文字建议，用户敲 `/goal` 拍板。
 *
 * 所以这里只有读、收尾、追加预算三个工具 —— 没有创建。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ sessionId 必须是 getter（CCM 踩过的坑）
 * ══════════════════════════════════════════════════════════════
 *
 * CLI 里 `/new` `/resume` 会换 session，**构造时快照会让工具永远读旧会话的目标**
 * —— 这类 bug 极难查（表现为「设了目标但工具说没有」）。
 * 所以这里传的是 `() -> String` 而不是 `String`。
 *
 * @param store 目标存储
 * @param getSessionId 会话 id 的取值函数（**不要传死值**）
 */
class GoalTools(
    private val store: GoalStore,
    private val getSessionId: () -> String,
) {

    private fun sid(): String = try {
        getSessionId().ifBlank { "default" }
    } catch (_: Throwable) {
        "default"
    }

    // ══════════════════════════════════════════════════════════════
    //  GetGoal
    // ══════════════════════════════════════════════════════════════

    inner class GetGoalTool : Tool() {
        override val name = "GetGoal"
        override val description =
            "读取当前目标（完成契约）：目标、完成判据、边界、预算余量、阻塞计数。" +
                "决定「继续干 / 宣布完成 / 报阻塞」之前先看它。没有目标时返回 no_goal。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 4_000

        override val inputSchema: JsonObject = ToolSchema.noArgsSchema()

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val goal = store.get(sid())
                ?: return ToolResult.ok(
                    "{\"goal\": null}  当前没有目标。目标由用户用 /goal <描述> 设定；" +
                        "你不能自己创建目标，只能建议。",
                )
            return ToolResult.ok(store.render(goal))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GoalStatus
    // ══════════════════════════════════════════════════════════════

    inner class GoalStatusTool : Tool() {
        override val name = "GoalStatus"
        override val description =
            "设置当前目标的终态。这是目标**唯一**的正式出口。\n" +
                "- **complete**：完成判据已被**实际验证**通过（命令跑过、测试绿、grep 对上）。" +
                "只有计划 / 摘要 / 初稿 / 部分结果 → 不许用。**预算快用完不是完成的理由**" +
                "（谎报完成比超预算严重得多）。reason 里贴证据：跑了什么命令、输出是什么。\n" +
                "- **blocked**：真僵局才用 —— 缺凭据/权限、需要用户决策、外部条件不满足、同一技术故障反复失败。" +
                "同一障碍要连续 ${GoalStore.BLOCKED_STREAK_THRESHOLD} 个 goal turn 复现才允许，" +
                "未达阈值调用**会被拒绝**并告诉你还差几轮（这是设计，不是报错，继续换办法即可）。\n" +
                "  目标本身不可能 / 自相矛盾 / 不安全 → 加 `impossible:true` 当轮直接终止。\n" +
                "  **不算阻塞**：活儿大、活儿难、慢、还没验证、不确定、想要更多轮次、想找用户确认一下。\n" +
                "- **paused**：需要用户参与、暂时挂起。\n" +
                "**多数 goal turn 不该调这个工具**：还有实质工作就正常结束本轮，runtime 会自动给下一轮。"
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "status" to ToolSchema.string(
                "要设置的终态",
                enum = listOf("complete", "blocked", "paused"),
            ),
            "reason" to ToolSchema.string(
                "complete 时写完成判据如何被验证通过（贴证据）；blocked 时写具体障碍",
            ),
            "impossible" to ToolSchema.boolean(
                "仅 blocked 用：目标本身不可能/自相矛盾/不安全，跳过连续轮次阈值当轮终止",
            ),
            required = listOf("status"),
        )

        override fun validateInput(input: JsonObject): String? {
            val s = input.str("status") ?: return "status is required"
            if (s !in listOf("complete", "blocked", "paused")) return "status 非法：$s"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val status = input.str("status")!!
            val reason = input.str("reason") ?: ""
            val impossible = input.bool("impossible") == true

            val result = store.tryStatus(sid(), status, reason, impossible)
            return result.fold(
                onSuccess = { g ->
                    val msg = when (status) {
                        "complete" -> "目标已标记完成。\n判据验证：$reason\n（runtime 将不再自动续轮）"
                        "blocked" -> "目标已标记阻塞。\n障碍：$reason\n（等用户处理；用 /goal resume 可恢复）"
                        else -> "目标已挂起（paused）。\n原因：$reason"
                    }
                    ToolResult.ok(msg)
                },
                onFailure = { e ->
                    // 阻塞审计未通过 / 无目标 —— 都是「可自我修正」的提示，不是硬失败
                    ToolResult.failed(e.message ?: "设置终态失败")
                },
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SetGoalBudget
    // ══════════════════════════════════════════════════════════════

    inner class SetGoalBudgetTool : Tool() {
        override val name = "SetGoalBudget"
        override val description =
            "申请追加当前目标的预算（只能增加，不能减少）。" +
                "仅当预算即将耗尽、且剩余工作确实必要时用；并在 reason 里说清为什么原预算不够。\n" +
                "**传的是新的上限值，不是增量** —— 当前 10 轮想再要 5 轮就传 turns:15，" +
                "传 5 会因「只能增不能减」被拒。\n" +
                "想提前收工用 GoalStatus，不要用这个。"
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "reason" to ToolSchema.string("为什么需要追加（必填）"),
            "turns" to ToolSchema.integer("新的轮次预算总数（必须大于当前值）", minimum = 1),
            "time" to ToolSchema.string("新的时间预算，如 \"30m\" / \"2h\"（必须大于当前值）"),
            "tokens" to ToolSchema.string("新的 token 预算，如 \"200k\"（必须大于当前值）"),
            required = listOf("reason"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("reason").isNullOrBlank()) return "reason is required（说清为什么原预算不够）"
            if (input.int("turns") == null && input.str("time") == null && input.str("tokens") == null) {
                return "至少要给 turns / time / tokens 之一"
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val turns = input.int("turns")
            val timeMs = input.str("time")?.let { parseDuration(it) }
            val tokens = input.str("tokens")?.let { parseCount(it) }

            val result = store.addBudget(
                sessionId = sid(),
                turns = turns,
                timeMs = timeMs,
                tokens = tokens,
                reason = input.str("reason")!!,
            )

            return result.fold(
                onSuccess = { g ->
                    ToolResult.ok(
                        "预算已追加。当前：轮次 ${g.turnsUsed}/${g.turnsBudget}（剩 ${g.turnsLeft}）" +
                            if (g.tokensBudget > 0) "  token ${g.tokensUsed}/${g.tokensBudget}" else "",
                    )
                },
                onFailure = { e -> ToolResult.failed(e.message ?: "追加预算失败") },
            )
        }
    }

    // ── 解析辅助 ──────────────────────────────────────────────────

    /** 解析时长："30m" / "2h" / "90s" → 毫秒 */
    private fun parseDuration(s: String): Long? {
        val t = s.trim().lowercase()
        val m = Regex("^(\\d+)\\s*(ms|s|m|h)?$").find(t) ?: return null
        val n = m.groupValues[1].toLongOrNull() ?: return null
        return when (m.groupValues[2]) {
            "ms" -> n
            "s", "" -> n * 1000
            "m" -> n * 60_000
            "h" -> n * 3_600_000
            else -> null
        }
    }

    /** 解析数量："200k" / "1.5m" → 数值 */
    private fun parseCount(s: String): Long? {
        val t = s.trim().lowercase().replace("_", "")
        val m = Regex("^([\\d.]+)\\s*(k|m|b)?$").find(t) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return when (m.groupValues[2]) {
            "k" -> (n * 1_000).toLong()
            "m" -> (n * 1_000_000).toLong()
            "b" -> (n * 1_000_000_000).toLong()
            else -> n.toLong()
        }
    }
}
