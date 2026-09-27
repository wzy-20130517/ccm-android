package com.ccm.app.tools.task

import java.util.concurrent.ConcurrentHashMap

/**
 * 子 Agent 实例登记表 —— 支撑 `SendMessage(wake:true)` 的「唤醒续跑」。
 *
 * 参照 Node 版 `core/plan.mjs` 的 `keptAgents`（Map<name, {agent, type, lastRunAt}>）。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 为什么不能照搬 Node 版（Kotlin 架构不同，语义要对齐）
 * ══════════════════════════════════════════════════════════════
 *
 * **Node 版**：`resumeSubagent` 手里攥着**活的 Agent 对象**（`keptAgents.get(name).agent`），
 * 直接 `agent.run(text)` 就往原上下文里追加一轮 —— 它读过的文件、推理过程全在。
 *
 * **Kotlin 版**：`ToolContext.spawnSubAgent` 的类型是
 * `suspend (SubAgentSpec) -> SubAgentResult` —— **一个无状态闭包**，
 * spec 进去、结果出来，拿不回中间的 Agent 实例。而 core 是冻结契约
 * （且 core 层不依赖 tools 层），改签名要动 core，不在本层权限内。
 *
 * **所以本表记的是「重建上下文所需的最小信息」，不是 Agent 对象本身**：
 * - 原始 spec（prompt / type / name）
 * - 之前几轮的「指令 + 结果」对
 *
 * 唤醒时把历史拼成一段新的 prompt 重新 spawn。**这是降级，不是等价替换**：
 *
 * | | Node 版（活对象） | Kotlin 版（重放历史） |
 * |---|---|---|
 * | 记得任务背景 | ✅ | ✅（在 prompt 里） |
 * | 记得读过的文件内容 | ✅ | ❌ 只有结论，要重新读 |
 * | token 成本 | 低（增量） | 高（每轮重放全部历史） |
 * | 能读到「自己上次的想法」 | ✅ | ❌（推理过程不落盘） |
 *
 * **如实写在工具返回里**，让模型知道「唤醒 ≠ 完美续跑」，必要时它会自己
 * 在指令里补充上下文。不写的话模型会以为对方还记得细节，结果对不上。
 *
 * ══════════════════════════════════════════════════════════════
 *  什么时候该用 wake、什么时候该重新 spawn
 * ══════════════════════════════════════════════════════════════
 *
 * · **wake**：同一角色的后续工作（返工、追问、补做一部分）—— 结论和上下文能复用
 * · **重新 spawn**：换了目标、换了角色、或上一轮结论已被推翻 —— 历史反而是干扰
 *
 * ══════════════════════════════════════════════════════════════
 *  线程安全
 * ══════════════════════════════════════════════════════════════
 *
 * 用 [ConcurrentHashMap]：多个子 Agent 可能同时唤醒不同名字，
 * 而主 Agent 的 AgentStatus 又可能在读。**不用普通 HashMap + 自己加锁** ——
 * 那要小心锁范围，容易在「读的时候被写」上出错。
 */
class SubAgentRegistry {

    companion object {
        /** 单个 agent 保留的历史轮数（超了丢最旧的） */
        private const val MAX_HISTORY = 8

        /** 历史文本总长上限（防 prompt 膨胀） */
        private const val MAX_HISTORY_CHARS = 24_000

        /** 最多记住多少个 agent（防无限增长） */
        private const val MAX_AGENTS = 50
    }

    /** 一次「指令 → 结果」 */
    data class Round(val instruction: String, val result: String, val at: Long)

    /** 一个可唤醒的 agent */
    data class Entry(
        val name: String,
        /** 原始 prompt（首轮任务） */
        val originalPrompt: String,
        val agentType: String,
        val description: String,
        /** 后续轮次（不含首轮） */
        val rounds: List<Round>,
        val lastRunAt: Long,
        val lastTaskId: String?,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /**
     * 登记一个子 Agent（spawn 后调用）。
     *
     * 同名会**覆盖**（对齐 Node 版 `keptAgents.set`）——
     * 同一个角色重派时，旧实例的上下文本来就该作废。
     * 这也是「一个角色一个名字」纪律的由来：名字复用 = 上下文接力。
     */
    fun register(
        name: String,
        originalPrompt: String,
        agentType: String,
        description: String,
        taskId: String?,
    ) {
        if (name.isBlank()) return
        prune()
        entries[name] = Entry(
            name = name,
            originalPrompt = originalPrompt,
            agentType = agentType,
            description = description,
            rounds = emptyList(),
            lastRunAt = System.currentTimeMillis(),
            lastTaskId = taskId,
        )
    }

    /** 记一轮「指令 → 结果」（唤醒跑完后调用）。 */
    fun appendRound(name: String, instruction: String, result: String) {
        val cur = entries[name] ?: return
        val newRounds = (cur.rounds + Round(instruction, result, System.currentTimeMillis()))
            .takeLast(MAX_HISTORY)
        entries[name] = cur.copy(rounds = newRounds, lastRunAt = System.currentTimeMillis())
    }

    fun get(name: String): Entry? = entries[name]

    fun exists(name: String): Boolean = entries.containsKey(name)

    /** 所有可唤醒的名字（给错误提示用）。 */
    fun names(): List<String> = entries.keys.sorted()

    /**
     * 拼「续跑 prompt」—— 把历史压成一段文本重新喂进去。
     *
     * ⚠️ **明确告诉对方这是续跑、不是新任务**，并如实说明「细节可能已丢失」。
     * 不说明的话，它会把自己当成全新实例，可能把已完成的工作重做一遍。
     */
    fun buildResumePrompt(name: String, newInstruction: String): String? {
        val e = entries[name] ?: return null

        val sb = StringBuilder()
        sb.append("【续跑 · 你之前做过这个任务】\n")
        sb.append("下面是你的原始任务和历史进展（由主 Agent 转述，细节可能不全，")
        sb.append("缺什么请用工具自己查，**不要凭记忆编造**）。\n\n")
        sb.append("## 原始任务\n").append(e.originalPrompt.take(MAX_HISTORY_CHARS)).append("\n")

        if (e.rounds.isNotEmpty()) {
            sb.append("\n## 历史进展\n")
            var used = 0
            // 从最近的往前取，超预算就停 —— 近期进展比早期结论更相关
            for (r in e.rounds.reversed()) {
                val block = "\n### 指令\n${r.instruction}\n### 结果\n${r.result}\n"
                if (used + block.length > MAX_HISTORY_CHARS) break
                used += block.length
                sb.insert(sb.indexOf("\n## 历史进展\n") + 8, block)
            }
        }

        sb.append("\n## 本次新指令\n").append(newInstruction).append("\n")
        sb.append("\n请基于以上背景执行本次指令，只返回本次的实质结果。")
        return sb.toString()
    }

    /** 清理最旧的（超过 [MAX_AGENTS] 时）。 */
    private fun prune() {
        if (entries.size < MAX_AGENTS) return
        entries.entries.sortedBy { it.value.lastRunAt }
            .take(entries.size - MAX_AGENTS + 1)
            .forEach { entries.remove(it.key) }
    }
}
