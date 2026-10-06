package com.ccm.app.tools.task

import com.ccm.app.core.tool.SubAgentSpec
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonObject

/**
 * Team 工具组（7 个）—— 多 Agent 协作的组织与通信。
 *
 * 参照 Node 版 `core/tools-teams.mjs`（285 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 最重要的一条纪律（写在最前面）
 * ══════════════════════════════════════════════════════════════
 *
 * **你的正文输出其他 Agent 看不见。** 想让队友知道任何事，
 * 只能用 [SendMessageTool] 发过去 —— 说给用户听的话不会进队友的上下文。
 * 这是 CCM 上反复踩的坑（新 Agent 以为队友能「看到」自己的输出）。
 *
 * ══════════════════════════════════════════════════════════════
 *  消息自动送达（不要写轮询循环）
 * ══════════════════════════════════════════════════════════════
 *
 * 队友发来的消息会在**下一轮自动出现在对话里**（由 Agent 循环注入）。
 * 所以**不要写 `sleep + CheckMessages` 的等待循环** —— 那纯属浪费轮次。
 * [CheckMessagesTool] 只在想主动查历史（`all:true`）或旁观查看（`peek:true`）时才用。
 *
 * @param store 团队存储
 * @param agentId 当前身份
 * @param registry 子 Agent 登记表（`SendMessage(wake:true)` 用）。
 *   `null` = 未接入 → wake 会给出明确提示（而不是假装成功）
 */
class TeamTools(
    private val store: TeamStore,
    private val taskStore: TaskStore,
    private val agentId: String = "main",
    private val registry: SubAgentRegistry? = null,
) {

    // ══════════════════════════════════════════════════════════════
    //  TeamCreate
    // ══════════════════════════════════════════════════════════════

    inner class TeamCreateTool : Tool() {
        override val name = "TeamCreate"
        override val description =
            "创建协作团队。团队名同时是任务列表名 —— 建完用 TaskCreate（list 填团队名）派活。" +
                "需要多个子 Agent 分工并互相沟通时用；单个 Agent 干活不需要建团队。"
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "team" to ToolSchema.string("团队名（同时也是任务列表名，Task 工具的 list 参数填同一个值）"),
            "description" to ToolSchema.string("可选：这个团队要完成什么"),
            required = listOf("team"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("team").isNullOrBlank()) "team is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val name = input.str("team")!!
            val existed = store.exists(name)
            val info = store.create(name, input.str("description") ?: "")
            return ToolResult.ok(
                if (existed) "团队「${info.name}」已存在（成员 ${info.members.size} 人）"
                else "已创建团队「${info.name}」。下一步：各 Agent 用 TeamJoin 报到，" +
                    "然后用 TaskCreate（list=\"${info.name}\"）派活。",
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TeamJoin
    // ══════════════════════════════════════════════════════════════

    inner class TeamJoinTool : Tool() {
        override val name = "TeamJoin"
        override val description =
            "以某个身份加入团队，之后才能收发消息。同一身份重复加入不会报错（幂等）。" +
                "**子 Agent 开工第一步就该 join。**"
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "team" to ToolSchema.string("团队名"),
            "agent" to ToolSchema.string("自己的身份标识，如 planner / worker-1 / reviewer"),
            "role" to ToolSchema.string("可选：职责说明，如「负责 core/ 目录重构」"),
            required = listOf("team", "agent"),
        )

        override fun validateInput(input: JsonObject): String? = when {
            input.str("team").isNullOrBlank() -> "team is required"
            input.str("agent").isNullOrBlank() -> "agent is required"
            else -> null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val team = input.str("team")!!
            val agent = input.str("agent")!!
            val info = store.join(team, agent, input.str("role") ?: "")
                ?: return ToolResult.notFound("团队不存在：$team（先用 TeamCreate 建）")
            // 记下「我是谁」—— 队友消息自动送达靠它找 inbox（见 setMyIdentity 注释）
            store.setMyIdentity(team, agent)

            val others = info.members.filter { it.name != agent }
                .joinToString(", ") { it.name } .ifEmpty { "(暂无)" }
            return ToolResult.ok(
                "已加入团队「${info.name}」身份=$agent" +
                    (input.str("role")?.let { "  职责=$it" } ?: "") +
                    "\n当前队友: $others",
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SendMessage
    // ══════════════════════════════════════════════════════════════

    inner class SendMessageTool : Tool() {
        override val name = "SendMessage"
        override val description =
            "给队友发消息。\n" +
                "【重要】你的正文输出（说给用户听的话）**其他 Agent 看不见**——" +
                "想让队友知道任何事，只能靠这个工具发过去。\n" +
                "【to=\"*\"】= 广播给所有队友（自动排除自己和已退出的人）。" +
                "想让全场都知道的事用它，别对每个人发一遍；但代价随人数线性增长，只在人人都需要时用。\n" +
                "【wake:true】唤醒一个**已跑完的子 Agent**（按 agent_name），把 text 当新指令接着干。\n" +
                "**默认后台**：立刻返回 task_id，用 AgentStatus/AgentOutput 查进度——" +
                "子 Agent 动辄跑几分钟，同步等会撞工具超时。\n" +
                "只在「就一句追问、几秒能答完」时才加 wait:true 同步等。"
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "team" to ToolSchema.string("团队名"),
            "from" to ToolSchema.string("自己的身份（省略则用当前身份）"),
            "to" to ToolSchema.string("接收方身份；填 \"*\" = 广播给所有队友"),
            "text" to ToolSchema.string(
                "完整消息内容。写清上下文——对方看不到你的对话历史，也看不到你的正文输出",
            ),
            "summary" to ToolSchema.string("可选：一行摘要（10 字内），给人看预览"),
            "wake" to ToolSchema.boolean(
                "true = 唤醒 to 指定的子 Agent（按 agent_name）继续干活，**复用它原有的上下文**。" +
                    "用于让 worker 返工/追问/补做一部分，比重新 spawn 省一次探索成本。默认后台跑。",
            ),
            "wait" to ToolSchema.boolean(
                "配合 wake 用。true = 同步等它跑完再返回（**只适合几秒的短追问**）；" +
                    "默认 false = 后台跑，立刻返回 task_id，之后用 AgentOutput({block:true}) 等结果。",
            ),
            "request" to ToolSchema.string(
                "可选：发一条**需要对方答复**的协议请求。" +
                    "shutdown=请求对方收工（对方同意即结束）；" +
                    "plan_approval=请求对方审批你的方案（对方可驳回并要求修改）。" +
                    "收到请求的一方必须用 respond 回复。",
                enum = listOf("shutdown", "plan_approval"),
            ),
            required = listOf("team", "to", "text"),
        )

        override fun validateInput(input: JsonObject): String? = when {
            input.str("team").isNullOrBlank() -> "team is required"
            input.str("to").isNullOrBlank() -> "to is required"
            input.str("text").isNullOrBlank() -> "text is required"
            else -> null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val team = input.str("team")!!
            val from = input.str("from")?.takeIf { it.isNotBlank() } ?: agentId
            val to = input.str("to")!!
            val text = input.str("text")!!
            val summary = input.str("summary") ?: ""
            val request = input.str("request")

            // ── wake 分支：唤醒已跑完的子 Agent 继续干活 ─────────────
            //
            // 与 inbox 投递是**两条不同的路径**：
            // · inbox：消息进对方信箱，等它自己来收（团队异步通信）
            // · wake ：把新指令直接喂进它原有上下文，立刻开跑
            if (input.bool("wake") == true) {
                return wakeSubAgent(to, text, input.bool("wait") == true, ctx)
            }

            // 协议请求需要 requestId（对方 respond 时回填）
            val requestId = request?.let { "req-${System.currentTimeMillis() % 1_000_000}" }

            return when (val r = store.send(team, from, to, text, summary, "text", request, requestId)) {
                is TeamStore.SendResult.Ok ->
                    ToolResult.ok("已发送给 $to（inbox 现有 ${r.queued} 条）" +
                        (requestId?.let { "\nrequest_id: $it（对方需用 respond:$it 答复）" } ?: ""))

                is TeamStore.SendResult.Broadcast ->
                    ToolResult.ok("已广播给 ${r.delivered} 人：${r.targets.joinToString(", ")}")

                is TeamStore.SendResult.Failed ->
                    ToolResult.failed("发送失败（${r.reason}）：${r.detail}")
            }
        }

        /**
         * 唤醒一个子 Agent 继续干活。
         *
         * ══════════════════════════════════════════════════════
         *  ⚠️ 与 Node 版的语义差异（必读，别以为能完美续跑）
         * ══════════════════════════════════════════════════════
         *
         * Node 版攥着**活的 Agent 对象**（`keptAgents.get(name).agent`），
         * `agent.run(text)` 直接往原上下文追加一轮 —— 它读过的文件、推理过程都在。
         *
         * Kotlin 版拿不到那个对象：`ctx.spawnSubAgent` 的类型是
         * `suspend (SubAgentSpec) -> SubAgentResult`，**一个无状态闭包** ——
         * spec 进去、结果出来，中间的 Agent 实例取不回。core 是冻结契约
         * （且 core 层不依赖 tools 层），改签名不在本层权限内。
         *
         * 所以这里用 [SubAgentRegistry] **重放历史**：把「原始任务 + 历轮指令/结果」
         * 拼成新 prompt 重新 spawn。**如实写在返回里**，不假装等价 ——
         * 否则模型会以为对方还记得细节，后续对不上。
         *
         * ══════════════════════════════════════════════════════
         *  后台 vs 同步（对齐 Node 版 2026-09-27 的改进）
         * ══════════════════════════════════════════════════════
         *
         * **默认后台**。原实现只有同步 await 到底，而子 Agent 动辄跑几分钟，
         * 必然撞工具 60s 超时 —— 更糟的是超时后它**还在后台跑**，
         * 但调用方拿不到 task_id、无法查进度，只能翻 trace 猜死活。
         *
         * 后台模式复用现有 spawn（`runInBackground = true`）→ 立刻拿 task_id，
         * 之后用 AgentStatus / AgentOutput 查。
         */
        private suspend fun wakeSubAgent(
            name: String,
            text: String,
            wait: Boolean,
            ctx: ToolContext,
        ): ToolResult {
            val reg = registry
                ?: return ToolResult.Error(
                    "唤醒功能未接入（SubAgentRegistry 未注入）。" +
                        "请用 Agent 工具重新派一个（会丢原上下文，记得在 prompt 里补背景）。",
                    ToolResult.INTERNAL,
                )

            val spawn = ctx.spawnSubAgent
                ?: return ToolResult.Error(
                    "当前环境不支持派生子 Agent（spawnSubAgent 未注入）——" +
                        "子 Agent 自己不能唤醒别的子 Agent。",
                    ToolResult.INTERNAL,
                )

            val entry = reg.get(name)
                ?: return ToolResult.failed(
                    "[$name] 不在可唤醒名单里。\n" +
                        "可唤醒的：${reg.names().ifEmpty { "(无 —— 还没有用 agent_name 派过子 Agent)" }}\n" +
                        "注意：只有 spawn 时**给了 agent_name** 的子 Agent 才能被唤醒；" +
                        "进程重启后名单会清空（原实例已不存在）。",
                )

            // 拼续跑 prompt（原始任务 + 历轮进展 + 新指令）
            val resumePrompt = reg.buildResumePrompt(name, text)
                ?: return ToolResult.failed("构建续跑上下文失败：[$name] 已不存在")

            val spec = SubAgentSpec(
                prompt = resumePrompt,
                description = "续跑: $name",
                subagentType = entry.agentType,
                // ★ 关键：后台跑（除非显式 wait:true）
                runInBackground = !wait,
                agentName = name,
            )

            val r = try {
                spawn(spec)
            } catch (e: Throwable) {
                return ToolResult.Error("唤醒 [$name] 失败：${e.message}", ToolResult.INTERNAL)
            }

            // 并发超限**不是失败** —— 要明确告诉模型「重试即可」，别让它放弃
            if (r.rejected != null) {
                return ToolResult.ok(
                    "唤醒 [$name] 未排上号（rejected=${r.rejected}）。\n" +
                        "**这不是错误、任务也没失败** —— 只是并发名额满了。\n" +
                        "等已有子 Agent 完成后重试，或改成串行。",
                )
            }

            if (!r.ok) {
                return ToolResult.failed("唤醒 [$name] 执行失败：${r.error ?: r.output.take(500)}")
            }

            // 记一轮历史，供下次唤醒时重放
            reg.appendRound(name, text, r.output.take(8_000))

            return if (!wait && r.taskId != null) {
                ToolResult.ok(
                    "已唤醒 [$name]（后台续跑）。\n" +
                        "task_id: ${r.taskId}\n\n" +
                        "⚠️ **这不是完美续跑**：它拿到的是一份「历史摘要」（原始任务 + 历轮结论），" +
                        "**不记得自己读过哪些文件的具体内容、也不记得当时的推理过程**。\n" +
                        "所以新指令里要把关键约束写清楚；缺的细节它会自己重新查。\n\n" +
                        "用 AgentOutput({task_id:\"${r.taskId}\", block:true}) 等结果" +
                        "（**不要用 Bash sleep + 反复 AgentStatus**，那样每轮白烧一次模型调用）。",
                )
            } else {
                ToolResult.ok(
                    "已唤醒 [$name]（同步完成，turns=${r.turns}）。\n\n" + r.output.take(8_000),
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  CheckMessages
    // ══════════════════════════════════════════════════════════════

    inner class CheckMessagesTool : Tool() {
        override val name = "CheckMessages"
        override val description =
            "查看发给自己的消息。\n" +
                "【多数情况你不需要调它】TeamJoin 之后，队友发来的新消息会在下一轮**自动出现**在对话里。" +
                "所以不要写 sleep + CheckMessages 的等待循环。\n" +
                "只在这些时候用：想回顾已读历史（all:true）、或想查看但不标记已读（peek:true）。"
        override val isReadOnly = true
        override val maxResultSizeChars = 4_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "team" to ToolSchema.string("团队名"),
            "agent" to ToolSchema.string("自己的身份（省略则用当前身份）"),
            "all" to ToolSchema.boolean("true 时连已读的一起返回（回顾历史用）"),
            "peek" to ToolSchema.boolean("true 时不标记已读（旁观查看用）"),
            required = listOf("team"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("team").isNullOrBlank()) "team is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val team = input.str("team")!!
            val me = input.str("agent")?.takeIf { it.isNotBlank() } ?: agentId
            val all = input.bool("all") == true
            val peek = input.bool("peek") == true

            val msgs = store.readInboxMessages(team, me, unreadOnly = !all, peek = peek)
            if (msgs.isEmpty()) {
                return ToolResult.ok(if (all) "（inbox 为空）" else "（没有未读消息）")
            }

            val sb = StringBuilder()
            sb.append("${if (all) "全部" else "未读"}消息 ${msgs.size} 条" +
                (if (peek) "（peek，未标记已读）" else "") + "：\n\n")
            msgs.forEach { m ->
                val req = m.request?.let { "  【协议请求: $it${m.requestId?.let { id -> " / $id" } ?: ""}】" } ?: ""
                sb.append("[${m.timestamp}] ${m.from} → ${m.to}$req\n")
                if (m.summary.isNotEmpty()) sb.append("  摘要: ${m.summary}\n")
                sb.append("  ${m.text}\n\n")
            }
            return ToolResult.ok(sb.toString())
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TeamStatus
    // ══════════════════════════════════════════════════════════════

    inner class TeamStatusTool : Tool() {
        override val name = "TeamStatus"
        override val description =
            "查看团队全景：成员及职责、各人持有的任务数、未读消息数、任务进度统计。" +
                "派活前先看谁闲着，收尾前确认没人卡住。"
        override val isReadOnly = true
        override val maxResultSizeChars = 4_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "team" to ToolSchema.string("团队名；省略则列出所有团队"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val team = input.str("team")

            if (team.isNullOrBlank()) {
                val all = store.list()
                if (all.isEmpty()) return ToolResult.ok("（还没有任何团队）")
                return ToolResult.ok(
                    "共 ${all.size} 个团队：\n" + all.joinToString("\n") { t ->
                        val tasks = taskStore.list(t.name)
                        val done = tasks.count { it.status == "completed" }
                        "- ${t.name}（成员 ${t.members.size}，任务 ${tasks.size}，完成 $done）" +
                            if (t.description.isNotEmpty()) "  ${t.description}" else ""
                    },
                )
            }

            val info = store.get(team) ?: return ToolResult.notFound("团队不存在：$team")
            val tasks = taskStore.list(info.name)
            val counts = store.inboxCounts(info.name)

            val sb = StringBuilder()
            sb.append("团队「${info.name}」")
            if (info.description.isNotEmpty()) sb.append(" —— ${info.description}")
            sb.append("\n\n成员（${info.members.size}）:\n")
            if (info.members.isEmpty()) {
                sb.append("  （还没有人加入）\n")
            } else {
                info.members.forEach { m ->
                    val owned = tasks.count { it.owner == m.name && it.status != "completed" }
                    val (total, unread) = counts[m.name] ?: (0 to 0)
                    val status = if (m.status == "inactive") "（已退出）" else ""
                    sb.append("  ${m.name}$status")
                    if (m.role.isNotEmpty()) sb.append(" — ${m.role}")
                    sb.append("  持有任务 $owned  收件箱 $total（未读 $unread）\n")
                }
            }

            sb.append("\n任务进度（列表「${info.name}」）:\n")
            if (tasks.isEmpty()) {
                sb.append("  （还没有任务）\n")
            } else {
                val pending = tasks.count { it.status == "pending" }
                val doing = tasks.count { it.status == "in_progress" }
                val done = tasks.count { it.status == "completed" }
                sb.append("  共 ${tasks.size}：○待办 $pending / →进行中 $doing / ✓完成 $done\n")
                val unowned = tasks.filter { it.owner == null && it.status != "completed" }
                if (unowned.isNotEmpty()) {
                    sb.append("  未认领：${unowned.joinToString(", ") { "#${it.id}" }}\n")
                }
            }

            return ToolResult.ok(sb.toString())
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TeamLeave
    // ══════════════════════════════════════════════════════════════

    inner class TeamLeaveTool : Tool() {
        override val name = "TeamLeave"
        override val description =
            "标记自己退出协作（状态转 inactive，不删除消息记录）。" +
                "子 Agent 完成分工、交付完成后调用，让主 Agent 知道你收工了。"
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "team" to ToolSchema.string("团队名"),
            "agent" to ToolSchema.string("自己的身份（省略则用当前身份）"),
            required = listOf("team"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("team").isNullOrBlank()) "team is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val team = input.str("team")!!
            val me = input.str("agent")?.takeIf { it.isNotBlank() } ?: agentId
            return if (store.setMemberStatus(team, me, TeamStore.INACTIVE)) {
                ToolResult.ok("$me 已退出团队「$team」（记录保留，可复盘）")
            } else {
                ToolResult.notFound("找不到成员 $me（团队 $team）")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TeamDisband
    // ══════════════════════════════════════════════════════════════

    inner class TeamDisbandTool : Tool() {
        override val name = "TeamDisband"
        override val description =
            "解散团队，同时清空同名任务列表（Team 与 TaskList 是一体的）。" +
                "协作彻底结束、结论已汇总后才调用；想保留任务记录就设 keepTasks。"
        override val isDestructive = true
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "team" to ToolSchema.string("要解散的团队名"),
            "keepTasks" to ToolSchema.boolean("true 时保留任务列表，只解散团队"),
            required = listOf("team"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("team").isNullOrBlank()) "team is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val team = input.str("team")!!
            val keep = input.bool("keepTasks") == true
            val (ok, taskCount) = store.disband(team, keep, taskStore)
            return if (ok) {
                ToolResult.ok(
                    "已解散团队「$team」" +
                        if (keep) "（任务列表已保留）" else "（同时清空 $taskCount 个任务）",
                )
            } else {
                ToolResult.notFound("团队不存在：$team")
            }
        }
    }
}
