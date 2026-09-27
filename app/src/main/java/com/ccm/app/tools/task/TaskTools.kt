package com.ccm.app.tools.task

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.core.tool.ToolSchema.strList
import kotlinx.serialization.json.JsonObject

/**
 * Task 工具组（6 个）—— 持久化任务系统对 Agent 的接口。
 *
 * 参照 Node 版 `core/tools-tasks.mjs`（228 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么不合并进 TodoWrite（CCM 的设计说明，直接保留）
 * ══════════════════════════════════════════════════════════════
 *
 * TodoWrite 是「给用户看当轮进度」，写完即弃，不需要 id、依赖、归属。
 * Task 是「多 Agent 之间的共享待办」，必须能被另一个 agent 查到、领走、改状态。
 * 两者语义不同，**合并会让简单场景也被迫填 id 和依赖，反而更难用**。
 *
 * @param store 任务存储
 * @param agentId 当前 agent 的身份（claim 时用；主对话是 "main"）
 */
class TaskTools(
    private val store: TaskStore,
    private val agentId: String = "main",
) {

    /** 通用参数：列表名 */
    private fun listDesc() = "任务列表名，默认 ${TaskStore.DEFAULT_LIST}。不同项目/不同协作组用不同列表隔离"

    private fun listOf(input: JsonObject): String =
        input.str("list")?.takeIf { it.isNotBlank() } ?: TaskStore.DEFAULT_LIST

    // ══════════════════════════════════════════════════════════════
    //  TaskCreate
    // ══════════════════════════════════════════════════════════════

    inner class TaskCreateTool : Tool() {
        override val name = "TaskCreate"
        override val description =
            "创建持久化任务（跨轮、跨重启存活，可被子 Agent 领取）。" +
                "多 Agent 协作或需要跟踪依赖关系时用；单轮临时清单用 TodoWrite。"
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "subject" to ToolSchema.string("任务标题，一句话说清要做什么"),
            "description" to ToolSchema.string("可选：详细说明、验收标准、相关文件"),
            "activeForm" to ToolSchema.string("可选：进行时描述（如「修复 parser 崩溃」），用于状态栏显示"),
            "blockedBy" to ToolSchema.stringArray("可选：前置任务 id 列表，这些没完成前本任务无法被领取"),
            "list" to ToolSchema.string(listDesc()),
            required = listOf("subject"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("subject").isNullOrBlank()) return "subject is required"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val list = listOf(input)
            return try {
                val task = store.create(
                    listId = list,
                    subject = input.str("subject")!!,
                    description = input.str("description") ?: "",
                    activeForm = input.str("activeForm") ?: "",
                    blockedBy = strListSafe(input, "blockedBy"),
                )
                val dep = if (task.blockedBy.isNotEmpty()) "，被 #${task.blockedBy.joinToString(" #")} 阻塞" else ""
                ToolResult.ok("已创建任务 #${task.id}: ${task.subject}$dep")
            } catch (e: Throwable) {
                ToolResult.Error("创建失败：${e.message}", ToolResult.INVALID_INPUT)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TaskList
    // ══════════════════════════════════════════════════════════════

    inner class TaskListTool : Tool() {
        override val name = "TaskList"
        override val description =
            "列出持久化任务，看谁在做什么、哪些被阻塞。" +
                "图标含义：○待办 →进行中 ✓完成；@名字=归属 agent；⟵id=被该任务阻塞。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 4_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "status" to ToolSchema.string(
                "可选：只看某状态",
                enum = listOf("pending", "in_progress", "completed"),
            ),
            "owner" to ToolSchema.string("可选：只看某个 agent 的任务"),
            "list" to ToolSchema.string(listDesc()),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val list = listOf(input)
            var tasks = store.list(list)
            input.str("status")?.let { st -> tasks = tasks.filter { it.status == st } }
            input.str("owner")?.let { ow -> tasks = tasks.filter { it.owner == ow } }

            val head = "任务列表 [$list]（共 ${tasks.size}）"
            return ToolResult.ok("$head\n${store.format(tasks)}")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TaskGet
    // ══════════════════════════════════════════════════════════════

    inner class TaskGetTool : Tool() {
        override val name = "TaskGet"
        override val description = "读取单个任务的完整内容（含 description、依赖、归属、时间戳、进展留痕）。领取任务前先看清要求。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 4_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "id" to ToolSchema.string("任务 id（如 3）"),
            "list" to ToolSchema.string(listDesc()),
            required = listOf("id"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("id").isNullOrBlank()) "id is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val list = listOf(input)
            val id = input.str("id")!!
            val t = store.get(list, id)
                ?: return ToolResult.notFound("找不到任务 #$id（列表 $list）")

            val sb = StringBuilder()
            sb.append("任务 #${t.id}: ${t.subject}\n")
            sb.append("状态: ${t.status}")
            t.owner?.let { sb.append("  归属: @$it") }
            sb.append("\n")
            if (t.activeForm.isNotEmpty()) sb.append("进行时: ${t.activeForm}\n")
            if (t.blockedBy.isNotEmpty()) sb.append("被阻塞于: ${t.blockedBy.joinToString(", ") { "#$it" }}\n")
            if (t.blocks.isNotEmpty()) sb.append("阻塞了: ${t.blocks.joinToString(", ") { "#$it" }}\n")
            if (t.description.isNotEmpty()) sb.append("\n说明:\n${t.description}\n")
            if (t.comments.isNotEmpty()) {
                sb.append("\n进展留痕（${t.comments.size} 条）:\n")
                t.comments.forEach { c ->
                    sb.append("  [${c.at}] ${c.by}: ${c.text}\n")
                }
            }
            sb.append("\n创建: ${t.createdAt}  更新: ${t.updatedAt}")
            return ToolResult.ok(sb.toString())
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TaskClaim
    // ══════════════════════════════════════════════════════════════

    inner class TaskClaimTool : Tool() {
        override val name = "TaskClaim"
        override val description =
            "领取任务并置为 in_progress。会检查是否已被他人占用、是否已完成、" +
                "前置任务是否都完成 —— 被拒绝时会说明原因，不要硬改 owner 绕过。"
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "id" to ToolSchema.string("任务 id"),
            "agent" to ToolSchema.string("领取者标识，如 main / worker-1 / reviewer（省略则用当前身份）"),
            "list" to ToolSchema.string(listDesc()),
            required = listOf("id"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("id").isNullOrBlank()) "id is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val list = listOf(input)
            val id = input.str("id")!!
            val who = input.str("agent")?.takeIf { it.isNotBlank() } ?: agentId

            return when (val r = store.claim(list, id, who)) {
                is TaskStore.ClaimResult.Ok ->
                    ToolResult.ok("已领取任务 #${r.task.id}: ${r.task.subject}（归属 @$who，状态 in_progress）")
                is TaskStore.ClaimResult.Rejected ->
                    ToolResult.failed("领取失败（${r.reason}）：${r.detail}")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TaskUpdate
    // ══════════════════════════════════════════════════════════════

    inner class TaskUpdateTool : Tool() {
        override val name = "TaskUpdate"
        override val description =
            "更新任务：改状态（pending/in_progress/completed）、改标题说明、交还归属（owner 传空字符串）、加依赖。" +
                "**做完一件事就立刻标 completed，别攒着**。交接给别人时带 comment 写清「做到哪、试过什么、卡在哪」——" +
                "光改 status 没用，接手的人需要这些留痕。"
        override val maxResultSizeChars = 1_500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "id" to ToolSchema.string("任务 id"),
            "status" to ToolSchema.string("新状态", enum = listOf("pending", "in_progress", "completed")),
            "subject" to ToolSchema.string("新标题"),
            "description" to ToolSchema.string("新说明"),
            "activeForm" to ToolSchema.string("新进行时描述"),
            "owner" to ToolSchema.string("归属 agent；传空字符串表示交还（变回无人认领）"),
            "comment" to ToolSchema.string("可选：追加一条进展留痕（做到哪、试过什么、卡在哪）"),
            "blockedBy" to ToolSchema.stringArray("可选：追加前置任务 id（双向维护依赖边）"),
            "list" to ToolSchema.string(listDesc()),
            required = listOf("id"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("id").isNullOrBlank()) "id is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val list = listOf(input)
            val id = input.str("id")!!

            // 依赖边先加（若任务不存在会失败）
            strListSafe(input, "blockedBy").forEach { dep ->
                store.addDependency(list, dep, id)
            }

            // owner：显式传空字符串 = 交还
            val ownerRaw = input.str("owner")
            val ownerValue = when {
                ownerRaw == null -> null              // 不改
                ownerRaw.isEmpty() -> null            // 交还（清空）
                else -> ownerRaw
            }

            val updated = try {
                store.update(
                    listId = list,
                    id = id,
                    status = input.str("status"),
                    owner = ownerValue,
                    subject = input.str("subject"),
                    description = input.str("description"),
                    activeForm = input.str("activeForm"),
                )
            } catch (e: Throwable) {
                return ToolResult.invalidInput("更新失败：${e.message}")
            } ?: return ToolResult.notFound("找不到任务 #$id（列表 $list）")

            // 留痕
            input.str("comment")?.takeIf { it.isNotBlank() }?.let { c ->
                store.addComment(list, id, agentId, c)
            }

            val final = store.get(list, id) ?: updated
            val ownerStr = final.owner?.let { " @$it" } ?: "（无人认领）"
            return ToolResult.ok("已更新 #${final.id}: ${final.subject}  状态=${final.status}$ownerStr")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  TaskDelete
    // ══════════════════════════════════════════════════════════════

    inner class TaskDeleteTool : Tool() {
        override val name = "TaskDelete"
        override val description = "删除任务，同时清理其他任务里指向它的依赖边。误建或作废时用；已完成的任务建议留着做记录，不必删。"
        override val isDestructive = true
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "id" to ToolSchema.string("任务 id"),
            "list" to ToolSchema.string(listDesc()),
            required = listOf("id"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("id").isNullOrBlank()) "id is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val list = listOf(input)
            val id = input.str("id")!!
            return if (store.delete(list, id)) {
                ToolResult.ok("已删除任务 #$id（并清理了相关依赖边）")
            } else {
                ToolResult.notFound("找不到任务 #$id（列表 $list）")
            }
        }
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    /**
     * 取字符串数组参数（容忍单个字符串、字符串数组）。
     *
     * ⚠️ 不用 `ToolSchema.strList` 的原因见 LookupTools 里同名注释 ——
     * 它对「数字数组」会静默返回 null。这里 blockedBy 是字符串数组，
     * 用 strList 是对的，但为了容忍模型传单个字符串（如 `blockedBy: "3"`），
     * 包一层。
     */
    private fun strListSafe(input: JsonObject, key: String): List<String> =
        input.strList(key)
            ?: input.str(key)?.takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: emptyList()
}
