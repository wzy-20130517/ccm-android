package com.ccm.app.tools.task

import com.ccm.app.core.tool.SubAgentSpec
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.core.tool.ToolSchema.strList
import kotlinx.serialization.json.JsonObject

/**
 * AgentWorkflow —— 固定的 Explore → Plan → Implement → Review 多阶段子 Agent 流程。
 *
 * 参照 Node 版 `core/agent-workflow.mjs`（205 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  与 Agent 工具的区别（什么时候用哪个）
 * ══════════════════════════════════════════════════════════════
 *
 * | | Agent | AgentWorkflow |
 * |---|---|---|
 * | 编排 | 模型自己决定派几个、什么角色 | **固定 4 阶段**，顺序不可变 |
 * | 上下文 | 每个子 Agent 独立 | 每阶段独立，**阶段结果以压缩文本传给下一阶段** |
 * | 适用 | 需要灵活分工、并行扇出 | **复杂多文件任务**，想要可审计的固定流程 |
 *
 * 关键设计：**阶段之间只传压缩后的报告**（[STAGE_LIMIT] 字符），
 * 而不是把前一阶段的完整上下文带过去 —— 否则 4 个阶段的上下文会累积爆炸。
 *
 * ══════════════════════════════════════════════════════════════
 *  工具白名单：两层保障
 * ══════════════════════════════════════════════════════════════
 *
 * Explore / Plan / Review 是**只读阶段** —— 若不禁写工具，一个「探索」阶段
 * 顺手把代码改了，后面的 Plan 就是基于已经变形的仓库做规划。
 *
 * 本工具的保障分两层：
 * 1. **硬约束（实际生效的那层）**：阶段用 [STAGE_AGENT_TYPE] 映射到专门的
 *    子 Agent 类型（Explore / Plan / code-reviewer），这些角色卡的 tools
 *    白名单在 `.claude/agents/` 里写死 —— 子 Agent 拿到手的工具列表里
 *    根本没有写工具。
 * 2. **软约束（兜底说明）**：prompt 里也写清「本阶段只允许用 X/Y/Z」，
 *    万一某个阶段映射到 general-purpose（如 Implement），至少文字上说清了边界。
 *
 * 所有阶段都**禁用 Agent / AgentWorkflow / AskUserQuestion**（写进 prompt）：
 * · 前两个：防递归拆分（Node 版注释明确写了「不要调用 AgentWorkflow 递归拆分」）
 * · 后一个：子 Agent 无法与用户交互，调了会永远阻塞
 *
 * ══════════════════════════════════════════════════════════════
 *  串行执行（与 Node 版的差异，刻意为之）
 * ══════════════════════════════════════════════════════════════
 *
 * Node 版支持 `run_in_background`（起后台任务）。Kotlin 版的 [ToolContext.spawnSubAgent]
 * 已经有 `runInBackground` 能力，但**本工具不做后台模式** —— 理由：
 * 4 个阶段有严格顺序依赖（Plan 要吃 Explore 的报告），后台跑没有收益，
 * 反而让「取结果」多一层间接。需要后台请直接用 Agent({run_in_background:true})。
 *
 * 因此 schema 里**没有** run_in_background 参数（而不是有但不生效 —— 后者更坑）。
 */
class AgentWorkflowTools {

    companion object {
        /** 阶段报告截断长度（对齐 Node 版 STAGE_LIMIT = 12000） */
        private const val STAGE_LIMIT = 12_000

        /** 默认每阶段轮次上限 */
        private const val DEFAULT_MAX_TURNS = 200

        /** 每阶段超时（毫秒）—— 传给子 Agent 的 streamWatchdogMs */
        private const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000

        /** 阶段定义 */
        private data class Stage(
            val name: String,
            val description: String,
            /** 允许的工具名；null = 全部（除禁用的） */
            val tools: Set<String>?,
            val prompt: String,
        )

        /**
         * 所有阶段都禁用的工具。
         *
         * Agent / AgentWorkflow：防递归拆分（Node 版注释：「不要调用 AgentWorkflow 递归拆分任务」）
         * AskUserQuestion：子 Agent 无法与用户交互，调了会永久阻塞
         */
        private val BLOCKED = setOf("Agent", "AgentWorkflow", "AskUserQuestion")

        private val STAGES: Map<String, Stage> = listOf(
            Stage(
                name = "Explore",
                description = "只读探索代码库，收集相关文件、符号、约束和现状",
                tools = setOf(
                    "Read", "Glob", "Grep", "CodeSearch", "RepoMap", "Symbols", "LSP",
                    "GitStatus", "GitLog", "WebFetch", "WebSearch", "SearchInfo",
                    "HashlineRead", "HashlineGrep", "BashOutput",
                ),
                prompt = "你负责 Explore 阶段。只读调查，不修改文件、不提交 git、不调用 Agent 或 AgentWorkflow。\n" +
                    "先确认任务涉及的代码路径、入口、关键符号、现有测试和约束。" +
                    "完成后返回事实密集的探索报告，保留 file_path:line_number。",
            ),
            Stage(
                name = "Plan",
                description = "根据探索结果制定可执行计划和验证步骤",
                tools = setOf(
                    "Read", "Glob", "Grep", "CodeSearch", "RepoMap", "Symbols", "LSP",
                    "GitStatus", "GitDiff", "TodoWrite", "HashlineRead", "HashlineGrep",
                    "TaskCreate", "TaskList", "TaskGet", "TaskUpdate",
                    "TeamCreate", "TeamJoin", "TeamStatus", "SendMessage", "CheckMessages",
                ),
                prompt = "你负责 Plan 阶段。只读分析，不修改文件、不提交 git、不调用 Agent 或 AgentWorkflow。\n" +
                    "根据原始任务和 Explore 报告，产出分步骤计划、涉及文件、风险、回滚点和验证命令。" +
                    "不要把计划写成已经完成。\n" +
                    "多步骤任务用 TaskCreate 把每步落成持久任务，有先后顺序的用 blockedBy 标明依赖，" +
                    "让 Implement 阶段能逐个领取。",
            ),
            Stage(
                name = "Implement",
                description = "按计划实施修改并验证结果",
                tools = null,   // 全部放开
                prompt = "你负责 Implement 阶段。根据原始任务、Explore 报告和 Plan 报告实施修改。\n" +
                    "优先编辑现有文件，遵守项目约定；改动后运行合适的语法检查或测试。" +
                    "不要调用 AgentWorkflow 递归拆分任务；遇到不确定处基于仓库证据推进，并在结果中说明。\n" +
                    "若 Plan 阶段建了 Task（先 TaskList 看一下）：开工前 TaskClaim 领取，" +
                    "做完一件立即 TaskUpdate 标 completed，不要攒到最后一起改。",
            ),
            Stage(
                name = "Review",
                description = "只读审查实现、风险和测试证据",
                tools = setOf(
                    "Read", "Glob", "Grep", "CodeSearch", "RepoMap", "Symbols", "LSP",
                    "GitStatus", "GitDiff", "GitLog", "Test", "Diagnostics",
                    "HashlineRead", "HashlineGrep", "TaskList", "TaskGet",
                    "TeamStatus", "CheckMessages", "SendMessage",
                ),
                prompt = "你负责 Review 阶段。只读审查，不修改文件、不提交 git、不调用 Agent 或 AgentWorkflow。\n" +
                    "检查实现是否满足原始任务，寻找 bug、安全问题、遗漏测试和未验证声明。" +
                    "明确区分已验证和待验证项，并给出 file_path:line_number。",
            ),
        ).associateBy { it.name }

        /** 默认阶段顺序（Node 版用 Object.keys，顺序即声明顺序） */
        private val DEFAULT_ORDER = listOf("Explore", "Plan", "Implement", "Review")

        /** 可选的子 Agent 类型名（Kotlin 版角色卡） */
        private val STAGE_AGENT_TYPE = mapOf(
            "Explore" to "Explore",
            "Plan" to "Plan",
            "Implement" to "general-purpose",
            "Review" to "code-reviewer",
        )
    }

    /**
     * 拼阶段 prompt：原始任务 + 前置阶段报告 + 当前阶段指令 + 工具约束。
     *
     * ⚠️ **工具白名单只能靠 prompt 声明，不是硬约束**。
     *
     * [SubAgentSpec]（core 的冻结契约）只有 prompt / description / subagentType /
     * runInBackground / agentName 五个字段，**没有** allowedTools / blockedTools。
     * 而子 Agent 的角色卡（`.claude/agents/` 下的 md 文件）是按 subagentType 选的，
     * 我们不能为「Explore 阶段的 general-purpose」单独定义一套工具集。
     *
     * 所以这里把白名单**写成自然语言指令**。效果弱于硬约束 ——
     * 子 Agent 理论上可以不遵守。真需要硬隔离时的做法：
     * 在 `.claude/agents/` 下定义专门的阶段角色（Explore/Plan/Review 已有内置类型），
     * 让它们的 tools 白名单在角色卡里写死。本工具已经优先用那些类型
     * （见 [STAGE_AGENT_TYPE]），所以实际执行时硬约束是生效的。
     */
    private fun stagePrompt(stage: Stage, original: String, reports: List<Pair<String, String>>): String {
        val prior = if (reports.isEmpty()) "" else
            "\n\n## 前置阶段报告\n" + reports.joinToString("\n\n") { (name, result) ->
                "### $name\n$result"
            }
        val toolRule = if (stage.tools == null) {
            "本阶段工具不限，但**不要**调用 Agent / AgentWorkflow / AskUserQuestion。"
        } else {
            "本阶段**只允许**用这些工具：${stage.tools.joinToString(", ")}。\n" +
                "其余工具（尤其写文件、Bash 写操作、git 提交）本阶段一律不要用。"
        }
        return "${stage.prompt}\n\n$toolRule\n\n## 原始任务\n$original$prior\n\n## 当前阶段\n${stage.name}\n" +
            "只返回当前阶段的实质结果，供后续阶段继续使用。"
    }

    inner class AgentWorkflowTool : Tool() {
        override val name = "AgentWorkflow"
        override val description =
            "运行 Explore → Plan → Implement → Review 的多阶段子 Agent 工作流；" +
                "每阶段独立上下文并返回阶段报告。适合复杂多文件任务。\n" +
                "与 Agent 工具的区别：AgentWorkflow 是**固定流程**（不可变顺序、阶段间只传压缩报告），" +
                "Agent 是灵活分工（模型自己决定派几个、什么角色）。\n" +
                "⚠️ 4 个阶段串行执行，整轮耗时较长；简单任务直接用 Agent 或自己做，别用这个。"
        override val isReadOnly = false
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 30_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "prompt" to ToolSchema.string("要完成的完整任务"),
            "stages" to ToolSchema.stringArray(
                "阶段列表，默认 Explore、Plan、Implement、Review（只能填这四个，顺序即执行顺序）",
            ),
            "max_turns" to ToolSchema.integer(
                "覆盖每阶段轮次上限，默认 $DEFAULT_MAX_TURNS，范围 1-300",
                minimum = 1, maximum = 300,
            ),
            "timeout_ms" to ToolSchema.integer(
                "每阶段超时（毫秒），默认 $DEFAULT_TIMEOUT_MS，最大 1800000",
                minimum = 10_000, maximum = 1_800_000,
            ),
            required = listOf("prompt"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("prompt").isNullOrBlank()) return "prompt 必须是非空字符串"
            val stages = input.strList("stages")
            if (stages != null) {
                if (stages.isEmpty()) return "stages 必须是非空数组"
                val bad = stages.filter { it !in STAGES.keys }
                if (bad.isNotEmpty()) {
                    return "stages 只能包含: ${STAGES.keys.joinToString(", ")}（收到非法值：${bad.joinToString()}）"
                }
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val spawn = ctx.spawnSubAgent
                ?: return ToolResult.Error(
                    "当前环境不支持派生子 Agent（spawnSubAgent 未注入）。" +
                        "可能是子 Agent 自己调用 —— 子 Agent 默认不能再派子 Agent。",
                    ToolResult.INTERNAL,
                )

            val original = input.str("prompt")!!
            val requested = input.strList("stages")
            val order = if (requested.isNullOrEmpty()) DEFAULT_ORDER
            else requested.distinct().filter { it in STAGES }

            val maxTurns = (input.int("max_turns") ?: DEFAULT_MAX_TURNS).coerceIn(1, 300)
            val timeoutMs = (input.int("timeout_ms") ?: DEFAULT_TIMEOUT_MS).coerceIn(10_000, 1_800_000)

            val startedAt = System.currentTimeMillis()
            val reports = mutableListOf<Pair<String, String>>()
            val summaries = mutableListOf<String>()

            for (stageName in order) {
                ctx.checkCancelled()
                val stage = STAGES[stageName] ?: continue

                val spec = SubAgentSpec(
                    prompt = stagePrompt(stage, original, reports),
                    description = "workflow: $stageName",
                    subagentType = STAGE_AGENT_TYPE[stageName] ?: "general-purpose",
                    runInBackground = false,
                    agentName = null,
                    // 【2026-10-06 接线】这两个参数原来算出来了但传不进去 ——
                    // 只塞进结果文案，模型以为生效实际没有。
                    // （SubAgentSpec 加了字段 + AppContainer/SubAgentManager 接上后，
                    //  这里才真正把值送下去。）
                    maxTurns = maxTurns,
                    timeoutMs = timeoutMs.toLong(),
                )

                val res = try {
                    spawn(spec)
                } catch (e: Throwable) {
                    return ToolResult.Error(
                        "AgentWorkflow 在 $stageName 阶段失败：${e.message}",
                        ToolResult.INTERNAL,
                    )
                }

                // 并发超限不是失败 —— 明确告诉模型「重试即可」，别让它以为这条路走不通
                if (res.rejected != null) {
                    return ToolResult.failed(
                        "AgentWorkflow 在 $stageName 阶段被拒：${res.rejected}（${res.output}）\n" +
                            "这不是错误 —— 等已有子 Agent 完成后重试即可。",
                    )
                }
                if (!res.ok) {
                    return ToolResult.failed(
                        "AgentWorkflow 在 $stageName 阶段失败：${res.error ?: res.output}",
                    )
                }

                val clipped = res.output.take(STAGE_LIMIT).ifBlank { "(阶段无文本输出)" }
                reports += stageName to clipped
                summaries += "  · $stageName  ok=${res.ok}  turns=${res.turns}  " +
                    "输出 ${res.output.length} 字符${if (res.output.length > STAGE_LIMIT) "（已截断到 $STAGE_LIMIT）" else ""}"
            }

            val durationMs = System.currentTimeMillis() - startedAt
            val body = buildString {
                append("AgentWorkflow 完成（${order.joinToString(" → ")}）\n")
                append("耗时 ${durationMs / 1000}s · 每阶段轮次上限 $maxTurns\n\n")
                append(summaries.joinToString("\n"))
                append("\n\n")
                reports.forEach { (name, result) ->
                    append("═══════════════════════════════════════\n")
                    append("## $name\n")
                    append("═══════════════════════════════════════\n")
                    append(result).append("\n\n")
                }
            }

            return ToolResult.ok(body)
        }
    }
}
