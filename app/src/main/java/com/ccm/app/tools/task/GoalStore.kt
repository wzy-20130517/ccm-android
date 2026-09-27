package com.ccm.app.tools.task

import com.ccm.app.tools.file.AtomicFile
import org.json.JSONObject
import java.io.File

/**
 * Goal（完成契约）—— 带终止条件的自动推进目标。
 *
 * 参照 Node 版 `core/goal.mjs`（约 300 行）+ `core/goal-runtime.mjs`。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么**不提供** CreateGoal 工具（CCM 的设计决策，原样保留）
 * ══════════════════════════════════════════════════════════════
 *
 * > 目标由用户用 `/goal` 设定。让模型自己造目标 = 它可以给自己发
 * > **无人监督的长跑许可**（还带预算），这是权限放大，不是功能。
 * > Kimi 有 TUI 能随时看住它，我们是手机终端 + 自动续轮，不给这个口子。
 * > 模型要建议目标就用文字建议，用户敲 `/goal` 拍板。
 *
 * 所以只有三个工具：GetGoal（读）/ GoalStatus（收尾出口）/ SetGoalBudget（追加预算）。
 *
 * ══════════════════════════════════════════════════════════════
 *  三者分工（用户问起时按这个答）
 * ══════════════════════════════════════════════════════════════
 *
 * | 机制 | 性质 | 是否驱动执行 |
 * |---|---|---|
 * | **TodoWrite** | 当轮临时清单，给用户看进度 | ✗ |
 * | **Task 工具组** | 多 Agent 共享的持久待办 | ✗（记录"要做什么"但不推进） |
 * | **Goal（本类）** | 带**终止条件**的契约 | ✓ 唯一会主动跨轮驱动的东西 |
 *
 * ══════════════════════════════════════════════════════════════
 *  阻塞审计（防「一遇到困难就报 blocked」）
 * ══════════════════════════════════════════════════════════════
 *
 * 同一障碍必须**连续 N 个 goal turn 复现**才允许报 blocked。
 * 未达阈值时 [GoalStore.tryBlocked] 会拒绝并告知还差几轮 ——
 * 这是设计不是报错，继续换办法即可。
 *
 * 不算阻塞的情况（明确写进工具描述）：活儿大、活儿难、慢、还没验证、
 * 不确定、想要更多轮次、想找用户确认一下。
 *
 * @param rootDir 存储根（App 存储下的 goals/）
 */
class GoalStore(private val rootDir: File) {

    companion object {
        /** 允许报 blocked 前，同一障碍需连续复现的轮数 */
        const val BLOCKED_STREAK_THRESHOLD = 3

        /** 默认预算（手机上不给无上限自动推进） */
        const val DEFAULT_TURNS = 15
    }

    /** 一个目标契约 */
    data class Goal(
        val description: String,
        /** 完成判据：怎么验证「做完了」 */
        val proof: String = "",
        /** 边界：不许碰什么 */
        val bound: String = "",
        val status: String = "active",     // active | complete | blocked | paused
        val terminalReason: String = "",
        val turnsUsed: Int = 0,
        val turnsBudget: Int = DEFAULT_TURNS,
        val startedAt: Long = 0L,
        val timeBudgetMs: Long = 0L,
        val tokensUsed: Long = 0L,
        val tokensBudget: Long = 0L,
        /** 阻塞计数：同一障碍连续复现几次 */
        val blockedStreak: Int = 0,
        /** 上次记录的阻塞原因（用于判断「同一障碍」） */
        val lastBlocker: String = "",
    ) {
        val turnsLeft: Int get() = (turnsBudget - turnsUsed).coerceAtLeast(0)

        fun toJson(): JSONObject = JSONObject().apply {
            put("description", description)
            put("proof", proof)
            put("bound", bound)
            put("status", status)
            put("terminalReason", terminalReason)
            put("turnsUsed", turnsUsed)
            put("turnsBudget", turnsBudget)
            put("startedAt", startedAt)
            put("timeBudgetMs", timeBudgetMs)
            put("tokensUsed", tokensUsed)
            put("tokensBudget", tokensBudget)
            put("blockedStreak", blockedStreak)
            put("lastBlocker", lastBlocker)
        }

        companion object {
            fun fromJson(o: JSONObject) = Goal(
                description = o.optString("description"),
                proof = o.optString("proof"),
                bound = o.optString("bound"),
                status = o.optString("status", "active"),
                terminalReason = o.optString("terminalReason"),
                turnsUsed = o.optInt("turnsUsed", 0),
                turnsBudget = o.optInt("turnsBudget", DEFAULT_TURNS),
                startedAt = o.optLong("startedAt", 0L),
                timeBudgetMs = o.optLong("timeBudgetMs", 0L),
                tokensUsed = o.optLong("tokensUsed", 0L),
                tokensBudget = o.optLong("tokensBudget", 0L),
                blockedStreak = o.optInt("blockedStreak", 0),
                lastBlocker = o.optString("lastBlocker"),
            )
        }
    }

    /** blocked 判定结果 */
    sealed class BlockCheck {
        data class Allowed(val goal: Goal) : BlockCheck()
        data class NotEnough(val streak: Int, val need: Int) : BlockCheck()
        data class NoGoal(val message: String = "当前没有目标") : BlockCheck()
    }

    private fun fileFor(sessionId: String): File =
        File(rootDir, "${sessionId.replace(Regex("[^\\w.-]"), "_")}.json")

    // ── 读写 ──────────────────────────────────────────────────────

    @Synchronized
    fun get(sessionId: String): Goal? = try {
        val f = fileFor(sessionId)
        if (f.exists()) Goal.fromJson(JSONObject(f.readText())) else null
    } catch (_: Throwable) {
        null
    }

    @Synchronized
    fun save(sessionId: String, goal: Goal): Goal {
        if (!rootDir.exists()) rootDir.mkdirs()
        AtomicFile.writeText(fileFor(sessionId), goal.toJson().toString(2))
        return goal
    }

    /** 用户设定目标（由 /goal 命令调用，**不是工具**） */
    @Synchronized
    fun set(
        sessionId: String,
        description: String,
        proof: String = "",
        bound: String = "",
        turnsBudget: Int = DEFAULT_TURNS,
        timeBudgetMs: Long = 0L,
        tokensBudget: Long = 0L,
    ): Goal = save(
        sessionId,
        Goal(
            description = description,
            proof = proof,
            bound = bound,
            status = "active",
            turnsBudget = turnsBudget.coerceAtLeast(1),
            startedAt = System.currentTimeMillis(),
            timeBudgetMs = timeBudgetMs,
            tokensBudget = tokensBudget,
        ),
    )

    /** 消耗一轮（runtime 每轮开始调） */
    @Synchronized
    fun consumeTurn(sessionId: String): Goal? {
        val g = get(sessionId) ?: return null
        if (g.status != "active") return g
        return save(sessionId, g.copy(turnsUsed = g.turnsUsed + 1))
    }

    @Synchronized
    fun addTokens(sessionId: String, tokens: Long): Goal? {
        val g = get(sessionId) ?: return null
        return save(sessionId, g.copy(tokensUsed = g.tokensUsed + tokens))
    }

    /**
     * 记录一次阻塞（同一障碍连续计数）。
     *
     * @return 更新后的 streak
     */
    @Synchronized
    fun noteBlocker(sessionId: String, reason: String): Int {
        val g = get(sessionId) ?: return 0
        // 判断是否「同一障碍」：完全相同的描述才算；换了说法就重新计数
        val same = reason.trim() == g.lastBlocker.trim() && reason.isNotBlank()
        val streak = if (same) g.blockedStreak + 1 else 1
        save(sessionId, g.copy(blockedStreak = streak, lastBlocker = reason.trim()))
        return streak
    }

    /** 清除阻塞计数（问题解决后调） */
    @Synchronized
    fun clearBlocker(sessionId: String) {
        val g = get(sessionId) ?: return
        if (g.blockedStreak == 0 && g.lastBlocker.isEmpty()) return
        save(sessionId, g.copy(blockedStreak = 0, lastBlocker = ""))
    }

    /**
     * 尝试设置终态。
     *
     * @param impossible 目标本身不可能/矛盾/不安全 → 跳过连续轮次阈值当轮终止
     */
    @Synchronized
    fun tryStatus(
        sessionId: String,
        status: String,
        reason: String,
        impossible: Boolean = false,
    ): Result<Goal> {
        val g = get(sessionId) ?: return Result.failure(IllegalStateException("当前没有目标"))

        if (status == "blocked" && !impossible) {
            // 记录本次障碍，判断是否达阈值
            val streak = noteBlocker(sessionId, reason)
            if (streak < BLOCKED_STREAK_THRESHOLD) {
                return Result.failure(
                    IllegalStateException(
                        "阻塞审计未通过：同一障碍需连续 $BLOCKED_STREAK_THRESHOLD 个 goal turn 复现才允许报 blocked，" +
                            "当前 $streak/$BLOCKED_STREAK_THRESHOLD。\n" +
                            "这通常意味着**你还没试够办法** —— 继续换思路即可，" +
                            "不用把这一轮当成失败。\n" +
                            "如果目标本身不可能/自相矛盾/不安全，用 impossible:true 当轮终止。",
                    ),
                )
            }
        }

        val updated = save(
            sessionId,
            g.copy(status = status, terminalReason = reason, turnsUsed = g.turnsUsed),
        )
        return Result.success(updated)
    }

    /** 追加预算（只增不减） */
    @Synchronized
    fun addBudget(
        sessionId: String,
        turns: Int? = null,
        timeMs: Long? = null,
        tokens: Long? = null,
        reason: String,
    ): Result<Goal> {
        val g = get(sessionId) ?: return Result.failure(IllegalStateException("当前没有目标"))
        if (reason.isBlank()) {
            return Result.failure(IllegalArgumentException("必须说明为什么原预算不够（reason 不能空）"))
        }
        turns?.let {
            if (it <= g.turnsBudget) {
                return Result.failure(
                    IllegalArgumentException(
                        "轮次只能增加不能减少：当前上限 ${g.turnsBudget}，你传的是 $it。" +
                            "想提前收工请用 GoalStatus，不要用本工具。",
                    ),
                )
            }
        }
        timeMs?.let {
            if (it <= g.timeBudgetMs) {
                return Result.failure(IllegalArgumentException("时间预算只能增加不能减少"))
            }
        }
        tokens?.let {
            if (it <= g.tokensBudget) {
                return Result.failure(IllegalArgumentException("token 预算只能增加不能减少"))
            }
        }

        return Result.success(
            save(
                sessionId,
                g.copy(
                    turnsBudget = turns ?: g.turnsBudget,
                    timeBudgetMs = timeMs ?: g.timeBudgetMs,
                    tokensBudget = tokens ?: g.tokensBudget,
                ),
            ),
        )
    }

    // ── 渲染 ──────────────────────────────────────────────────────

    /** 渲染契约（供 GetGoal 与系统提示词注入） */
    fun render(g: Goal): String {
        val sb = StringBuilder()
        sb.append("目标：${g.description}\n")
        if (g.proof.isNotBlank()) sb.append("完成判据：${g.proof}\n")
        if (g.bound.isNotBlank()) sb.append("边界（不许越界）：${g.bound}\n")
        sb.append("状态：${g.status}")
        if (g.terminalReason.isNotBlank()) sb.append("（${g.terminalReason}）")
        sb.append("\n")

        sb.append("预算：轮次 ${g.turnsUsed}/${g.turnsBudget}（剩 ${g.turnsLeft}）")
        if (g.tokensBudget > 0) sb.append("   token ${g.tokensUsed}/${g.tokensBudget}")
        if (g.timeBudgetMs > 0) {
            val elapsed = if (g.startedAt > 0) System.currentTimeMillis() - g.startedAt else 0
            sb.append("   时间 ${elapsed / 1000}s/${g.timeBudgetMs / 1000}s")
        }
        sb.append("\n")

        sb.append("阻塞计数：${g.blockedStreak}/$BLOCKED_STREAK_THRESHOLD（未达阈值不准判 blocked）\n")

        // 收敛提示（预算用掉 75% 后）
        if (g.turnsBudget > 0) {
            val ratio = g.turnsUsed.toDouble() / g.turnsBudget
            if (ratio >= 0.75 && g.status == "active") {
                sb.append("\n⚠ 预算已用 ${(ratio * 100).toInt()}% —— 进入收敛模式：" +
                    "只做目标本身，不再开可选工作、不再顺手重构。\n")
            }
        }
        return sb.toString()
    }

    /** 所有会话的目标（供 /goal list） */
    @Synchronized
    fun listAll(): List<Pair<String, Goal>> =
        rootDir.listFiles()?.filter { it.name.endsWith(".json") }
            ?.mapNotNull { f ->
                try {
                    f.name.removeSuffix(".json") to Goal.fromJson(JSONObject(f.readText()))
                } catch (_: Throwable) {
                    null
                }
            } ?: emptyList()

    @Synchronized
    fun clear(sessionId: String): Boolean = try {
        fileFor(sessionId).delete()
    } catch (_: Throwable) {
        false
    }
}
