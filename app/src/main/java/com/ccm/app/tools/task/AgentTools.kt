package com.ccm.app.tools.task

import com.ccm.app.core.tool.SubAgentSpec
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonObject

/**
 * Agent 工具组 —— 派子 Agent、看状态、等结果、中止。
 *
 * 参照 Node 版 `core/tools-agent-status.mjs`（230 行）+ `core/plan.mjs` 的
 * SubAgent 调度。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 并发上限的语义（CCM 特意不抛异常）
 * ══════════════════════════════════════════════════════════════
 *
 * 超限时返回 `rejected:"concurrency_limit"` —— **这不是错误、任务也没失败**。
 * 抛异常会让子 Agent 误判「这条路走不通」直接放弃，所以这里明确区分：
 *
 * ```
 * rejected != null  →  没跑（等名额或改串行）
 * ok == false       →  跑了但失败
 * ok == true        →  成功
 * ```
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 等子 Agent 的正确方式：AgentOutput(block:true)
 * ══════════════════════════════════════════════════════════════
 *
 * **不要写 `Bash sleep` + 反复 `AgentStatus`** —— 那样每轮都白烧一次模型调用。
 * `AgentOutput({block:true, timeout})` 内部轮询、对调用方只是一次调用。
 * 超时返回不代表失败，可以再等一次。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 唤醒 vs 重新 spawn
 * ══════════════════════════════════════════════════════════════
 *
 * 要 worker 返工、追问细节、补做一部分时，用
 * `SendMessage(to:"worker-1", wake:true, text:"...")` **复用它原有上下文**
 * —— 它还记得自己之前做了什么，不用重讲背景。
 * 重新 spawn 等于丢掉全部上下文，白烧一遍探索成本。
 *
 * @param getRegistry 工具注册表取值函数（Agent 工具要把它传给子 Agent ——
 *   **必须惰性取**，见下方注释）
 */
class AgentTools(
    private val getRegistry: () -> com.ccm.app.core.tool.ToolRegistry?,
    /**
     * 取当前 AgentLoop（问题40：ExtendTurns 用）。
     *
     * 【为什么需要】ExtendTurns 是「给自己续轮」—— 要改**当前正在跑的
     * AgentLoop** 的 maxTurns。但 APK 的工具是全局注册的（ToolsBootstrap
     * 构造一次），拿不到「当前是哪个 loop」。
     *
     * 所以由 AppGraph 提供一个 getter：子 Agent 场景返回子 loop，
     * 主 Agent 场景返回主 loop。null = 取不到（工具报错）。
     */
    private val getAgentLoop: () -> com.ccm.app.core.agent.AgentLoop? = { null },
    /**
     * 子 Agent 登记表 —— 支撑 `SendMessage(wake:true)` 的唤醒续跑。
     *
     * spawn 成功后把「名字 + 原始 prompt + 类型」记进去，之后就能按名唤醒。
     * `null` = 未接入（wake 会明确报「未接入」，而不是假装成功）。
     *
     * ⚠️ **只在给了 agent_name 时登记** —— 没名字的没法被唤醒
     * （对齐 Node 版：`keptAgents.set(name, ...)` 里的 name 就是 agent_name）。
     */
    private val subAgentRegistry: SubAgentRegistry? = null,
) {

    /**
     * 后台子 Agent 的观察窗 —— 由 Agent 层注入。
     *
     * ⚠️ 必须是普通 interface 而不是 `fun interface` ——
     * 它有 4 个抽象方法，而 SAM 转换只允许**恰好一个**
     * （CI 报错原文：`Functional interface must have exactly one abstract function`）。
     *
     * 若未注入（如单测），AgentStatus/Stop/Output 会返回「无运行中的子 Agent」。
     */
    interface SubAgentObserver {
        /** 列出所有子 Agent 的快照 */
        suspend fun list(): List<Snapshot>

        /** 按 task_id 查一个 */
        suspend fun get(taskId: String): Snapshot?

        /** 中止一个 */
        suspend fun stop(taskId: String, reason: String): Boolean

        /** 阻塞等待（block=true）或立即返回快照 */
        suspend fun output(taskId: String, block: Boolean, timeoutSec: Int): Snapshot?
    }

    /** 子 Agent 状态快照 */
    data class Snapshot(
        val taskId: String,
        val agentName: String? = null,
        val agentType: String? = null,
        val description: String = "",
        val status: String = "running",   // pending|running|completed|failed|killed
        val turns: Int = 0,
        val durationMs: Long = 0L,
        val outputPreview: String = "",
        val resultPreview: String = "",
        val error: String? = null,
    )

    @Volatile
    var observer: SubAgentObserver? = null

    // ══════════════════════════════════════════════════════════════
    //  Agent
    // ══════════════════════════════════════════════════════════════

    inner class AgentTool : Tool() {
        override val name = "Agent"
        override val description =
            "创建一个子 agent 独立完成任务。子 agent 有独立的上下文窗口，" +
                "适合处理需要多步骤的独立子任务（避免污染主对话上下文）。\n" +
                "**并行按「资源冲突」分组，不是按个数**：只读任务（调研/搜索/读代码）放开并行；" +
                "**写同一批文件的任务必须串行** —— 否则两个 worker 各自读到旧内容再写回，" +
                "后写的静默覆盖前面的改动，不报错但工作丢失，最难查。\n" +
                "派活前想清楚每个 worker 会碰哪些文件，并在它的 prompt 里写明「只准改 X、别碰 Y」。\n" +
                "**并发上限 24**（全局，含递归派生的下级）。超限时返回 rejected:concurrency_limit ——" +
                "**这不是错误、任务也没失败**：等已有子 Agent 完成后重试、改成串行、或缩减本层扇出。"
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "prompt" to ToolSchema.string("给子 agent 的完整任务指令。应该是自包含的 prompt，子 agent 会以此作为首轮 user message"),
            "description" to ToolSchema.string("对任务的简短描述（3-5 个词），仅做标识用，不影响执行"),
            "subagent_type" to ToolSchema.string(
                "子 agent 类型（builtin: general-purpose/Explore/Plan/Coordinator，或 .claude/agents 自定义名）。默认 general-purpose",
            ),
            "run_in_background" to ToolSchema.boolean("是否在后台运行。true=立即返回 placeholder，主 agent 可继续做别的事"),
            "agent_name" to ToolSchema.string(
                "给这个子 agent 起个名字（如 worker-1 / reviewer）。" +
                    "之后可用 SendMessage(to:该名字, wake:true) 唤醒它继续干活，复用它原有的上下文",
            ),
            required = listOf("prompt"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("prompt").isNullOrBlank()) "prompt is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val spawn = ctx.spawnSubAgent
                ?: return ToolResult.Error(
                    "当前环境不支持派生子 Agent（spawnSubAgent 未注入）。" +
                        "可能是子 Agent 自己调用 —— 子 Agent 默认不能再派子 Agent。",
                    ToolResult.INTERNAL,
                )

            val spec = SubAgentSpec(
                prompt = input.str("prompt")!!,
                description = input.str("description") ?: "",
                subagentType = input.str("subagent_type") ?: "general-purpose",
                runInBackground = input.bool("run_in_background") == true,
                agentName = input.str("agent_name"),
            )

            return try {
                val r = spawn(spec)

                // 登记到可唤醒名单（仅当给了 agent_name —— 没名字的唤不了）
                // 放在 rejected 检查**之前**：并发超限时也登记，
                // 这样等名额释放后可以直接 wake 它，不用重新构造 spec
                spec.agentName?.takeIf { it.isNotBlank() }?.let { nm ->
                    subAgentRegistry?.register(
                        name = nm,
                        originalPrompt = spec.prompt,
                        agentType = spec.subagentType,
                        description = spec.description,
                        taskId = r.taskId,
                    )
                }

                // ⚠️ 并发上限拒绝：**不是失败**，要给「重试」的指引
                if (r.rejected != null) {
                    return ToolResult.ok(
                        buildString {
                            append("子 Agent 未启动（rejected=${r.rejected}）。\n")
                            append("**这不是错误、任务也没失败** —— 只是并发名额满了。\n")
                            append("可选做法：① 等已有子 Agent 完成后重试 ② 改成串行 ③ 缩减本层扇出。\n")
                            r.error?.let { append("详情：$it\n") }
                        },
                    )
                }

                if (!r.ok) {
                    return ToolResult.failed("子 Agent 执行失败：${r.error ?: "(无错误信息)"}")
                }

                ToolResult.ok(
                    buildString {
                        if (r.taskId != null) {
                            append("子 Agent 已启动（后台）。\n")
                            append("task_id: ${r.taskId}\n")
                            input.str("agent_name")?.let { append("agent_name: $it\n") }
                            append("\n用 AgentOutput({task_id:\"${r.taskId}\", block:true}) 等它完成" +
                                "（**不要用 Bash sleep + 反复 AgentStatus**，那样每轮白烧一次模型调用）。\n")
                            append("用 AgentStatus 看实时状态，AgentStop 中止。")
                        } else {
                            append("子 Agent 已完成。\n\n")
                            append(r.output.take(8000))
                        }
                    },
                )
            } catch (e: Throwable) {
                ToolResult.Error("派生子 Agent 异常：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  AgentStatus
    // ══════════════════════════════════════════════════════════════

    inner class AgentStatusTool : Tool() {
        override val name = "AgentStatus"
        override val description =
            "查看由 Agent 或 AgentWorkflow 启动的后台子 Agent 状态、耗时、turn、最近输出和最终结果。" +
                "不要用 BashOutput 轮询子 Agent；子 Agent 完成与否以这里的状态为准。\n" +
                "**注意**：(no output) 不等于完成 —— 只有 completed/failed/killed 才是终态。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 6_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "task_id" to ToolSchema.string("可选。指定后台子 Agent task_id；省略则列出全部子 Agent。"),
            "include_traces" to ToolSchema.boolean("是否附带最近 Agent trace 摘要；默认 false"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val obs = observer
                ?: return ToolResult.ok("（当前没有运行中的子 Agent —— 观察器未接入）")

            val taskId = input.str("task_id")
            if (taskId != null) {
                val s = obs.get(taskId)
                    ?: return ToolResult.notFound("找不到子 Agent：$taskId")
                return ToolResult.ok(renderOne(s, detail = true))
            }

            val all = obs.list()
            if (all.isEmpty()) return ToolResult.ok("（没有子 Agent）")
            return ToolResult.ok(
                "共 ${all.size} 个子 Agent：\n\n" +
                    all.joinToString("\n\n") { renderOne(it, detail = false) },
            )
        }

        private fun renderOne(s: Snapshot, detail: Boolean): String {
            val icon = when (s.status) {
                "pending" -> "○ 排队中"
                "running" -> "→ 运行中"
                "completed" -> "✓ 已完成"
                "failed" -> "✗ 失败"
                "killed" -> "⊘ 已中止"
                else -> s.status
            }
            val bits = mutableListOf(icon, "${s.durationMs / 1000}s")
            if (s.turns > 0) bits += "${s.turns} turns" else if (s.status == "running") bits += "尚未完成首轮"

            val nameTag = s.agentName?.let { "「$it」" } ?: ""
            val typeTag = s.agentType?.let { " [$it]" } ?: ""
            val head = "子 Agent ${s.taskId}$nameTag$typeTag\n  ${bits.joinToString(" · ")}\n  任务: ${s.description.ifEmpty { "(无描述)" }}"

            if (!detail) return head

            val parts = mutableListOf(head)
            if (s.status == "failed" || s.status == "killed") {
                parts += "\n【${if (s.status == "failed") "失败原因" else "中止原因"}】\n${s.error ?: s.resultPreview.ifEmpty { "(未记录原因)" }}"
            }
            parts += "\n最近输出:\n${s.outputPreview.ifEmpty { "(暂无输出)" }}"
            if (s.status == "completed") {
                parts += "\n最终结果:\n${s.resultPreview.ifEmpty { "(空)" }}"
            } else if (s.status == "running" || s.status == "pending") {
                parts += "\n(仍在运行，尚无最终结果)"
            }
            // 按状态给出下一步该干什么，而不是让调用方自己猜
            val advice = when (s.status) {
                "running" -> "仍在跑。别急着重复查询 —— 用 AgentOutput({block:true}) 等它，或用 AgentStop 中止。"
                "pending" -> "排队中（可能撞上并发上限）。等前面的完成即可。"
                "failed" -> "已失败。若给过 agent_name，可用 SendMessage(to:该名, wake:true) 唤醒它带上下文重试。"
                "killed" -> "已被中止。同样可用 SendMessage(wake:true) 唤醒重来。"
                "completed" -> "已完成。要它返工/补做，用 SendMessage(to:agent_name, wake:true) 而不是重新 spawn。"
                else -> null
            }
            advice?.let { parts += "\n→ $it" }
            return parts.joinToString("\n")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  AgentOutput
    // ══════════════════════════════════════════════════════════════

    inner class AgentOutputTool : Tool() {
        override val name = "AgentOutput"
        override val description =
            "取后台子 Agent 的输出。\n" +
                "【block:true】阻塞等到它结束或产出新内容才返回 —— **这是等子 Agent 的正确方式**，" +
                "别再用 Bash sleep + 反复 AgentStatus 轮询，那样每轮都白烧一次模型调用。\n" +
                "配合 timeout 设上限（秒，默认 120，最大 600）。**超时不算失败，任务还在跑，可以再等一次**。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 8_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "task_id" to ToolSchema.string("后台子 Agent 的 task_id"),
            "block" to ToolSchema.boolean("true = 阻塞等待它结束（推荐）；false = 立刻返回当前快照"),
            "timeout" to ToolSchema.integer("阻塞最长等多少秒，默认 120，上限 600", minimum = 1, maximum = 600),
            required = listOf("task_id"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("task_id").isNullOrBlank()) "task_id is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val obs = observer
                ?: return ToolResult.ok("（观察器未接入，无法读取子 Agent 输出）")

            val taskId = input.str("task_id")!!
            val block = input.bool("block") == true
            val timeout = (input.int("timeout") ?: 120).coerceIn(1, 600)

            val s = obs.output(taskId, block, timeout)
                ?: return ToolResult.notFound("找不到子 Agent：$taskId")

            val sb = StringBuilder()
            sb.append("子 Agent ${s.taskId}")
            s.agentName?.let { sb.append("「$it」") }
            sb.append("\n状态: ${s.status}  耗时: ${s.durationMs / 1000}s  轮次: ${s.turns}\n")
            sb.append("任务: ${s.description}\n\n")

            if (s.status == "completed") {
                sb.append("--- 最终结果 ---\n")
                sb.append(s.resultPreview.ifEmpty { "(空)" })
            } else if (s.status == "failed" || s.status == "killed") {
                sb.append("--- ${if (s.status == "failed") "失败" else "中止"} ---\n")
                sb.append(s.error ?: "(未记录原因)")
                sb.append("\n\n--- 最后输出 ---\n")
                sb.append(s.outputPreview.ifEmpty { "(无)" })
            } else {
                sb.append("(仍在运行 —— **超时/无输出都不代表失败**，可以再调一次等它)\n\n")
                sb.append("--- 最近输出 ---\n")
                sb.append(s.outputPreview.ifEmpty { "(暂无输出)" })
            }
            return ToolResult.ok(sb.toString())
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  AgentStop
    // ══════════════════════════════════════════════════════════════

    inner class AgentStopTool : Tool() {
        override val name = "AgentStop"
        override val description =
            "中止一个正在后台运行的子 Agent（按 task_id）。" +
                "用于：发现派错了方向、需求变了、或它明显跑偏在浪费时间 —— 不用干等它跑完。\n" +
                "中止后它占的并发名额**立刻释放**。" +
                "若创建时给了 agent_name，中止后仍可用 SendMessage(to:该名字, wake:true) 唤醒它带着已有上下文重新来一遍。\n" +
                "注意：这个工具只管子 Agent；Bash 后台任务用 KillShell。"
        override val isDestructive = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "task_id" to ToolSchema.string("要中止的后台子 Agent task_id（从 Agent 工具返回值或 AgentStatus 获取）"),
            "reason" to ToolSchema.string("可选：为什么中止，会记进任务状态方便回溯"),
            required = listOf("task_id"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("task_id").isNullOrBlank()) "task_id is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val obs = observer ?: return ToolResult.ok("（观察器未接入，无法中止）")
            val taskId = input.str("task_id")!!
            val reason = input.str("reason") ?: ""

            return if (obs.stop(taskId, reason)) {
                ToolResult.ok("已请求中止：$taskId" + (if (reason.isNotBlank()) "（原因：$reason）" else ""))
            } else {
                ToolResult.notFound("找不到子 Agent：$taskId（可能已结束）")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  AgentMemory
    // ══════════════════════════════════════════════════════════════

    /**
     * 子 Agent 的长期记忆（按类型分池，跨会话）。
     *
     * 【什么时候写】解决了一个绕了很久的问题、发现某个模块的隐藏契约、
     * 踩了一个下次还会踩的坑、或摸清了项目某处的约定。
     *
     * 【别写什么】一次性的琐碎操作、显而易见的常识、这次任务特有的细节。
     * 记忆是给「未来的同类」看的，不是工作日志。
     *
     * 【作用域】project（默认，跟着仓库走）· user（跨项目的个人经验）· local（本机私有）
     */
    inner class AgentMemoryTool(
        private val memoryRoot: java.io.File,
    ) : Tool() {
        override val name = "AgentMemory"
        override val description =
            "读写你自己这一类 Agent 的长期记忆（跨会话保留）。\n" +
                "【什么时候写】你解决了一个绕了很久的问题、发现某个模块的隐藏契约、" +
                "踩了一个下次还会踩的坑、或摸清了项目某处的约定 —— 写下来，下次同类 Agent 就不用重新踩。\n" +
                "【什么时候读】开工前先 read 一次，看看以前的自己留了什么。\n" +
                "【别写什么】一次性的琐碎操作、显而易见的常识、这次任务特有的细节。" +
                "记忆是给「未来的同类」看的，不是工作日志。\n" +
                "作用域：project（默认，跟着仓库走）· user（跨项目的个人经验）· local（本机私有，不进版本控制）。"
        override val maxResultSizeChars = 6_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "action" to ToolSchema.string(
                "read=读取记忆；write=追加一条；list=列出所有有记忆的 Agent 类型；clear=清空（需指定 scope）",
                enum = listOf("read", "write", "list", "clear"),
            ),
            "text" to ToolSchema.string("write 时必填：要记住的内容。写清「根因」和「为什么会踩」，别只写「修了X」"),
            "type" to ToolSchema.string("可选：Agent 类型（默认用你自己的类型）"),
            "scope" to ToolSchema.string(
                "可选：作用域，默认 project",
                enum = listOf("project", "user", "local"),
            ),
            required = listOf("action"),
        )

        override fun validateInput(input: JsonObject): String? {
            val a = input.str("action") ?: return "action is required"
            if (a !in listOf("read", "write", "list", "clear")) return "action 非法：$a"
            if (a == "write" && input.str("text").isNullOrBlank()) return "write 时 text 必填"
            return null
        }

        private fun fileFor(scope: String, type: String): java.io.File =
            java.io.File(java.io.File(memoryRoot, scope), "$type.md")

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val action = input.str("action")!!
            val scope = input.str("scope")?.takeIf { it in listOf("project", "user", "local") } ?: "project"
            val type = input.str("type")?.takeIf { it.isNotBlank() } ?: "general-purpose"

            return try {
                when (action) {
                    "read" -> {
                        val f = fileFor(scope, type)
                        if (!f.exists()) {
                            ToolResult.ok("（$scope 作用域下 $type 类型还没有记忆）")
                        } else {
                            ToolResult.ok("记忆 [$scope/$type]（${f.length()} 字节）:\n\n${f.readText()}")
                        }
                    }

                    "list" -> {
                        val dirs = listOf("project", "user", "local")
                        val sb = StringBuilder()
                        dirs.forEach { sc ->
                            val dir = java.io.File(memoryRoot, sc)
                            val files = dir.listFiles()?.filter { it.name.endsWith(".md") } ?: emptyList()
                            if (files.isNotEmpty()) {
                                sb.append("[$sc]\n")
                                files.forEach { sb.append("  ${it.name.removeSuffix(".md")}（${it.length()} 字节）\n") }
                            }
                        }
                        ToolResult.ok(sb.toString().ifEmpty { "（还没有任何记忆）" })
                    }

                    "clear" -> {
                        val f = fileFor(scope, type)
                        if (f.exists()) {
                            f.delete()
                            ToolResult.ok("已清空 [$scope/$type] 的记忆")
                        } else {
                            ToolResult.ok("（本来就没有记忆）")
                        }
                    }

                    else -> {
                        val text = input.str("text")!!
                        val f = fileFor(scope, type)
                        f.parentFile?.mkdirs()
                        val existing = if (f.exists()) f.readText() else "# $type 记忆（$scope）\n"
                        com.ccm.app.tools.file.AtomicFile.writeText(
                            f,
                            existing.trimEnd() + "\n\n" + text.trim() + "\n",
                            createParent = true,
                        )
                        ToolResult.ok("已记入 [$scope/$type]（现 ${f.length()} 字节）")
                    }
                }
            } catch (e: Throwable) {
                ToolResult.Error("记忆操作失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  ExtendTurns（子 Agent 续轮）
    // ══════════════════════════════════════════════════════════════

    /**
     * 给自己追加工具轮次。
     *
     * 【为什么需要它】CCM 的教训：子 Agent 的 maxTurns 来自角色卡、构造时写死，
     * 之后**没有机制可改** —— 主 Agent 在 prompt 里写「你有 1000 轮」是**在骗它**，
     * 子 Agent 信了之后大手大脚干活，然后照样被砍断交半成品。
     *
     * 【三道闸门】单次 ≤60 · 最多续 4 次 · 硬上限 400。
     */
    inner class ExtendTurnsTool(
        private val extend: (suspend (Int, String) -> String)? = null,
    ) : Tool() {
        override val name = "ExtendTurns"
        override val description =
            "给自己追加工具轮次（**仅当任务确实没做完、且还在有效推进时用**）。" +
                "单次最多 +60 轮，最多续 4 次，总上限 400 轮。" +
                "收到「距上限只剩不到 10 轮」提醒且工作未完成时，先判断：" +
                "是真的还需要更多轮，还是自己在原地打转？前者续，后者立刻收尾交还。"
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "reason" to ToolSchema.string("为什么还需要更多轮：已完成什么、还剩什么、预计几轮能收尾"),
            "turns" to ToolSchema.integer("要追加多少轮（1-60），省略则用 60", minimum = 1, maximum = 60),
            required = listOf("reason"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("reason").isNullOrBlank()) return "reason is required"
            val t = input.int("turns")
            if (t != null && (t < 1 || t > 60)) return "turns 必须在 1-60 之间"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val turns = (input.int("turns") ?: 60).coerceIn(1, 60)
            val reason = input.str("reason")!!

            // 【2026-10-06 问题40 修复】原来优先用 `extend` 回调 ——
            // 但那个回调从来没被注入过 → 永远报「当前环境不支持续轮」。
            //
            // 现在用 `ctx.selfLoop`（AgentLoop 构造 ctx 时传的自己）——
            // 这是**最准确**的来源：主 Agent 调就改主 loop，子 Agent 调
            // 就改子 loop，不会串。
            (ctx.selfLoop as? com.ccm.app.core.agent.AgentLoop)?.let { loop ->
                return try {
                    ToolResult.ok(loop.extendMaxTurns(turns, reason))
                } catch (e: Throwable) {
                    ToolResult.failed("续轮失败：${e.message}")
                }
            }

            // 兜底：老路径（extend 回调）
            val fn = extend
                ?: return ToolResult.Error(
                    "当前环境不支持续轮（取不到当前 AgentLoop，extend 回调也未注入）。" +
                        "请收尾并如实交代未完成部分。",
                    ToolResult.INTERNAL,
                )
            return try {
                ToolResult.ok(fn(turns, reason))
            } catch (e: Throwable) {
                ToolResult.failed("续轮失败：${e.message}")
            }
        }
    }
}
