package com.ccm.app.tools.task

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.tools.file.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 杂项工具组 —— TodoWrite / Sleep / Memory / UserInputHistory。
 *
 * 参照 Node 版 `core/agent-tools.mjs` + `core/memdir.mjs` + `core/user-profile.mjs`。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ TodoWrite 的使用纪律（用户明确反馈「agent 经常忘记更新待办」后加的）
 * ══════════════════════════════════════════════════════════════
 *
 * **什么时候用**：
 * - 复杂多步任务（≥3 个不同步骤）、多文件改动、反复调试、需要验证的改动 → 开工前就建
 * - 收到新指令后立刻拆成待办
 * - 用户一次给了多个任务（列表形式、逗号分隔、编号）
 *
 * **什么时候不用**：
 * - 单个简单任务、三步内能做完、纯对话/纯信息查询
 *
 * **状态管理（硬要求）**：
 * - **实时更新**，不要攒到最后一起改
 * - 开始做之前 → 先标 `in_progress` 再动手
 * - 做完之后 → **立刻**标 `completed`
 * - **任何时刻有且仅有 1 个 `in_progress`**
 * - 不再相关的任务 → 整个删掉
 * - 全部完成 → 传空数组清空清单（不要留一堆 completed 占屏幕）
 *
 * **完成标准**：只有真正做完才能标 completed。测试还红着、只做了一半、
 * 遇到未解决的错误 —— 都不算完成。被阻塞时保持 in_progress，
 * 并新建一条描述「需要解决什么」。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ Memory 的纪律（用户 2026-08-28 明确要求）
 * ══════════════════════════════════════════════════════════════
 *
 * **该记就直接记，不要问「要我写进 md 吗」**。每次都问等于把判断成本推给用户，
 * 请示本身就是骚扰。判断错了用户会说，但不要问。
 *
 * 满足以下任一条就直接记：
 * - 修完一个绕了多轮才定位的 bug（根因 + 为什么之前找错方向）
 * - 用户纠正了你的错误判断或错误方向
 * - 发现某个函数/模块的隐藏契约
 * - 定下项目约定（命令、路径、风格、流程）
 *
 * 反过来也别滥记：一次性琐碎操作、显而易见的常识、单轮就解决的小改动。
 * 记的时候写清「根因」和「为什么会踩」，不要只写「修了 X」。
 *
 * @param storageRoot App 存储根（记忆文件放这里）
 * @param memoryFile 项目记忆文件（CLAUDE.md 的 APK 侧等价物）
 * @param todoFile TodoWrite 的持久化位置（跨轮保持）
 */
class MiscTools(
    private val storageRoot: File,
    private val memoryFile: File,
    private val todoFile: File,
) {

    // ══════════════════════════════════════════════════════════════
    //  TodoWrite
    // ══════════════════════════════════════════════════════════════

    inner class TodoWriteTool : Tool() {
        override val name = "TodoWrite"
        override val description =
            "更新待办清单（跟踪任务进度）。\n" +
                "**复杂任务开工前就建清单**（≥3 个步骤 / 多文件改动 / 反复调试 / 需要验证的改动）——" +
                "建清单本身就是第一步。\n" +
                "状态必须实时更新：开始做之前先标 in_progress，做完立刻标 completed，" +
                "不要攒到最后一起改。\n" +
                "**任何时刻有且仅有 1 个 in_progress。**\n" +
                "只有真正做完才标 completed（测试还红着、只做一半、有未解决错误 → 都不算）。\n" +
                "全部完成后传空数组清空清单，不要留一堆 completed 占着屏幕。\n" +
                "**不要用的场景**：单个简单任务、三步内能做完、纯对话/纯信息查询。"
        override val isReadOnly = true
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "todos" to kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("完整的待办列表（每次传全量，不是增量）。空数组 = 清空清单。"))
                put(
                    "items",
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put(
                            "properties",
                            kotlinx.serialization.json.buildJsonObject {
                                put("content", ToolSchema.string("祈使句描述要做什么，如「修复登录 bug」"))
                                put(
                                    "status",
                                    ToolSchema.string(
                                        "pending=未开始 · in_progress=进行中（同时只能有一个）· completed=已完成",
                                        enum = listOf("pending", "in_progress", "completed"),
                                    ),
                                )
                                put("activeForm", ToolSchema.string("进行时描述，正在做时显示，如「正在修复登录 bug」"))
                            },
                        )
                        put(
                            "required",
                            JsonArray(
                                listOf(
                                    JsonPrimitive("content"),
                                    JsonPrimitive("status"),
                                ),
                            ),
                        )
                    },
                )
            },
            required = listOf("todos"),
        )

        override fun validateInput(input: JsonObject): String? {
            val arr = input["todos"] as? JsonArray ?: return "todos 必须是数组"
            // 校验：至多一个 in_progress
            var inProgress = 0
            arr.forEachIndexed { i, el ->
                val obj = el as? JsonObject ?: return "todos[$i] 不是对象"
                val status = (obj["status"] as? JsonPrimitive)?.content
                    ?: return "todos[$i].status is required"
                if (status !in listOf("pending", "in_progress", "completed")) {
                    return "todos[$i].status 非法：$status"
                }
                if (status == "in_progress") inProgress++
                if ((obj["content"] as? JsonPrimitive)?.content.isNullOrBlank()) {
                    return "todos[$i].content is required"
                }
            }
            if (inProgress > 1) {
                return "有 $inProgress 个 in_progress —— 任何时刻只能有 1 个（多个会让用户看不清你在做哪件事）"
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val arr = input["todos"] as? JsonArray ?: return ToolResult.invalidInput("todos 必须是数组")

            // 持久化（跨轮保持）
            try {
                val out = JSONArray()
                arr.forEach { el ->
                    val o = el as? JsonObject ?: return@forEach
                    out.put(
                        JSONObject()
                            .put("content", (o["content"] as? JsonPrimitive)?.content ?: "")
                            .put("status", (o["status"] as? JsonPrimitive)?.content ?: "pending")
                            .put("activeForm", (o["activeForm"] as? JsonPrimitive)?.content ?: ""),
                    )
                }
                AtomicFile.writeText(todoFile, out.toString(2), createParent = true)
            } catch (_: Throwable) {
                // 持久化失败不阻塞（当轮仍能用）
            }

            if (arr.isEmpty()) return ToolResult.ok("待办清单已清空")

            val sb = StringBuilder("待办清单（${arr.size} 项）:\n")
            arr.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val content = (o["content"] as? JsonPrimitive)?.content ?: ""
                val status = (o["status"] as? JsonPrimitive)?.content ?: "pending"
                val icon = when (status) {
                    "completed" -> "[✓]"
                    "in_progress" -> "[→]"
                    else -> "[ ]"
                }
                sb.append("$icon $content\n")
            }
            return ToolResult.ok(sb.toString().trimEnd())
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Sleep
    // ══════════════════════════════════════════════════════════════

    inner class SleepTool : Tool() {
        override val name = "Sleep"
        override val description =
            "暂停执行指定秒数后继续。用于等构建完成、等服务启动、等异步任务、给限流留冷却间隔。\n" +
                "【别用它做轮询】要等子 Agent 用 AgentOutput({block:true})，要等界面用 phone_wait，" +
                "要等后台命令用 BashOutput —— 那些会在条件满足时立刻返回，比盲等固定秒数快得多。\n" +
                "【上限 300 秒】需要更久说明该换成后台任务 + 事件回调。"
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 300

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "seconds" to ToolSchema.string("等待秒数，0.1 到 300"),
            "reason" to ToolSchema.string("可选：为什么要等（写进结果，方便回溯）"),
        )

        override fun validateInput(input: JsonObject): String? {
            val raw = input.str("seconds") ?: return "seconds is required"
            val v = raw.toDoubleOrNull() ?: return "seconds 必须是数字"
            if (v < 0.1 || v > 300) return "seconds 必须在 0.1 到 300 之间"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val seconds = (input.str("seconds") ?: "1").toDoubleOrNull()?.coerceIn(0.1, 300.0) ?: 1.0
            val reason = input.str("reason")

            // 分片等待，便于响应取消
            val totalMs = (seconds * 1000).toLong()
            var waited = 0L
            while (waited < totalMs) {
                ctx.checkCancelled()
                val slice = minOf(200L, totalMs - waited)
                delay(slice)
                waited += slice
            }

            return ToolResult.ok(
                "已等待 ${"%.1f".format(seconds)} 秒" + (reason?.let { "（$it）" } ?: ""),
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Memory
    // ══════════════════════════════════════════════════════════════

    inner class MemoryTool : Tool() {
        override val name = "Memory"
        override val description =
            "把重要信息写入项目记忆文件（跨会话保留）。\n" +
                "**该记就直接记，不要问「要我写进 md 吗」** —— 每次都问等于把判断成本推给用户。\n" +
                "满足任一条就记：修完一个绕了多轮才定位的 bug（根因 + 为什么之前找错方向）；" +
                "用户纠正了你的错误判断；发现某个模块的隐藏契约；定下项目约定。\n" +
                "**别记**：一次性琐碎操作、显而易见的常识、单轮就解决的小改动。\n" +
                "记的时候写清「根因」和「为什么会踩」，不要只写「修了 X」——后者对未来的自己没用。"
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "action" to ToolSchema.string(
                "append=追加内容到记忆文件 · show=查看当前内容 · init=如果不存在则创建",
                enum = listOf("append", "show", "init"),
            ),
            "text" to ToolSchema.string("要追加的文本（action=append 时必填）。会被原样写入文件末尾。"),
            required = listOf("action"),
        )

        override fun validateInput(input: JsonObject): String? {
            val action = input.str("action") ?: return "action is required"
            if (action !in listOf("append", "show", "init")) return "action 非法：$action"
            if (action == "append" && input.str("text").isNullOrBlank()) {
                return "action=append 时 text 必填"
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                when (input.str("action")) {
                    "show" -> {
                        if (!memoryFile.exists()) {
                            ToolResult.ok("（记忆文件不存在：${memoryFile.absolutePath}）")
                        } else {
                            val content = memoryFile.readText()
                            ToolResult.ok(
                                "记忆文件 ${memoryFile.absolutePath}（${content.length} 字符）:\n\n$content",
                            )
                        }
                    }

                    "init" -> {
                        if (memoryFile.exists()) {
                            ToolResult.ok("记忆文件已存在：${memoryFile.absolutePath}")
                        } else {
                            memoryFile.parentFile?.mkdirs()
                            AtomicFile.writeText(memoryFile, "# 项目记忆\n\n", createParent = true)
                            ToolResult.ok("已创建记忆文件：${memoryFile.absolutePath}")
                        }
                    }

                    else -> {
                        val text = input.str("text") ?: ""
                        try {
                            memoryFile.parentFile?.mkdirs()
                            val existing = if (memoryFile.exists()) memoryFile.readText() else ""
                            AtomicFile.writeText(memoryFile, existing + "\n" + text + "\n", createParent = true)
                            ToolResult.ok("已追加到记忆文件（现 ${memoryFile.length()} 字节）")
                        } catch (e: Throwable) {
                            ToolResult.Error("写入失败：${e.message}", ToolResult.INTERNAL)
                        }
                    }
                }
            }
    }

    // ══════════════════════════════════════════════════════════════
    //  UserInputHistory
    // ══════════════════════════════════════════════════════════════

    /**
     * 查询用户最近的输入历史。
     *
     * 【数据来源】由 Agent 层在每轮输入时追加到 [historyFile]。
     * 格式：每行一条 JSON `{"type":"message|command|shell","text":"...","at":1234567890}`
     */
    inner class UserInputHistoryTool(private val historyFile: File) : Tool() {
        override val name = "UserInputHistory"
        override val description =
            "查询用户最近的输入历史（包括 slash 命令和普通消息）。" +
                "当用户问「我刚才输入了什么」或需要回顾上下文时使用。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 6_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "count" to ToolSchema.integer("返回最近多少条（默认 20）", minimum = 1, maximum = 200),
            "type" to ToolSchema.string(
                "筛选类型：all=全部（含 shell 历史）· commands=仅 slash 命令 · messages=仅普通消息 · shell=仅 shell 命令历史",
                enum = listOf("all", "commands", "messages", "shell"),
            ),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val count = (input.int("count") ?: 20).coerceIn(1, 200)
                val filter = input.str("type") ?: "all"

                if (!historyFile.exists()) {
                    return@withContext ToolResult.ok("（还没有输入历史记录）")
                }

                val lines = try {
                    historyFile.readLines()
                } catch (e: Throwable) {
                    return@withContext ToolResult.Error("读取历史失败：${e.message}", ToolResult.INTERNAL)
                }

                val entries = lines.mapNotNull { line ->
                    try {
                        val o = JSONObject(line)
                        Triple(
                            o.optString("type", "message"),
                            o.optString("text", ""),
                            o.optLong("at", 0L),
                        )
                    } catch (_: Throwable) {
                        null
                    }
                }

                val filtered = when (filter) {
                    "commands" -> entries.filter { it.first == "command" }
                    "messages" -> entries.filter { it.first == "message" }
                    "shell" -> entries.filter { it.first == "shell" }
                    else -> entries
                }.takeLast(count)

                if (filtered.isEmpty()) {
                    return@withContext ToolResult.ok("（没有符合条件的记录）")
                }

                val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                ToolResult.ok(
                    "最近 ${filtered.size} 条输入（type=$filter）:\n" +
                        filtered.joinToString("\n") { (type, text, at) ->
                            val t = if (at > 0) fmt.format(java.util.Date(at)) else "?"
                            "[$t] ($type) $text"
                        },
                )
            }
    }

    // ══════════════════════════════════════════════════════════════
    //  AskUserQuestion
    // ══════════════════════════════════════════════════════════════

    /**
     * 向用户提问。
     *
     * ⚠️ **子 Agent 不要调用它** —— 子 Agent 无法与用户交互，
     * 调用会一直阻塞。子 Agent 遇到信息不足应基于合理假设推进，
     * 并在结果里说明假设。
     */
    inner class AskUserQuestionTool(
        private val asker: (suspend (String, List<String>) -> String?)? = null,
    ) : Tool() {
        override val name = "AskUserQuestion"
        override val description =
            "向用户提问获取信息。\n" +
                "⚠️ **子 Agent 不要调用** —— 无法与用户交互，会一直阻塞。" +
                "子 Agent 遇到信息不足应基于合理假设推进，并在结果里说明假设。"
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "question" to ToolSchema.string("要问的问题"),
            "options" to ToolSchema.stringArray("可选：预设选项（最多 4 个）"),
            required = listOf("question"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("question").isNullOrBlank()) "question is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val question = input.str("question")!!
            val options = (input["options"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?.take(4) ?: emptyList()

            val fn = asker
                ?: return ToolResult.Error(
                    "当前环境无法向用户提问（未接入 UI 回调）。请基于合理假设推进，并在结果里说明假设。",
                    ToolResult.INTERNAL,
                )

            val answer = fn(question, options)
                ?: return ToolResult.failed("用户没有回答（超时或取消）")

            return ToolResult.ok("用户回答：$answer")
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助：读取 TodoWrite 的持久化内容（供 UI 展示）
    // ══════════════════════════════════════════════════════════════

    fun loadTodos(): List<Triple<String, String, String>> = try {
        if (!todoFile.exists()) emptyList()
        else {
            val arr = JSONArray(todoFile.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Triple(
                    o.optString("content"),
                    o.optString("status", "pending"),
                    o.optString("activeForm"),
                )
            }
        }
    } catch (_: Throwable) {
        emptyList()
    }
}
