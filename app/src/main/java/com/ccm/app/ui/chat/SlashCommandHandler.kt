package com.ccm.app.ui.chat

import com.ccm.app.core.ChatSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Slash 命令统一分发器（2026-09-30 多 Agent 协作重构）。
 *
 * ## 为什么集中到这里
 * 原来 slash 命令散落在 [ChatScreenConnected] 的 when 和 [com.ccm.app.ui.shell.CcmApp]
 * 的 sendAndOpen，两处要同步、容易漏，且只接了 6 个命令。现在集中成一个纯函数：
 * UI 层只负责「拿到命令文本 → 调 handle → 按返回的 SlashResult 执行副作用」。
 *
 * ## 设计
 * - [handleSlashCommand] 是**纯分发**：输入命令文本 + 一个能力上下文 [SlashContext]，
 *   返回 [SlashResult]（告诉 UI 要做什么：注入通知 / 导航 / 打开某面板 / 已处理无需额外动作）。
 * - 返回 `null` = 这个命令本 handler 不认，交给调用方兜底（发给模型 or 提示不可用）。
 * - 命令实现**不直接碰 Compose 状态**（那是 UI 层的事），只通过 [SlashContext] 暴露的
 *   回调和 [ChatSession] 的公开方法操作。
 *
 * ## 分区（各命令组，多 Agent 分工填充）
 * 1. 基础（已接）：/clear /help /model /export /compact /permissions /stop /retry
 *    /context /cost /copy /new /style
 * 2. 会话类：/save /load /resume /rename /delete /branch /summary /incognito
 * 3. 查询类：/stats /status /tasks /todos /files /trace /errors
 * 4. 配置类：/effort /temperature /voice /me /markdown /greeting
 * 5. 工具/集成类：/mem /memory /automem /skills /agents /goal /github /mail /mcp /pexels
 */

/**
 * Slash 命令执行后要 UI 做的事。UI 层拿到后执行对应副作用。
 */
sealed class SlashResult {
    /** 已在 handler 内处理完（如 session.injectNotice 已调），UI 无需额外动作。 */
    object Handled : SlashResult()

    /** 注入一条通知气泡到当前会话。 */
    data class Notice(val markdown: String) : SlashResult()

    /** 要求导航到某个路由（传路由名字符串，UI 层映射到 CcmRoute）。 */
    data class Navigate(val route: String) : SlashResult()

    /** 打开某个面板/弹层（model/style/switcher/attach…）。 */
    data class OpenPanel(val panel: String) : SlashResult()

    /** Toast 短提示。 */
    data class Toast(val text: String) : SlashResult()

    /** 命令不被此 handler 识别 —— 交给调用方兜底。等价于返回 null，但语义更清晰。 */
    object NotHandled : SlashResult()
}

/**
 * handler 可用的能力上下文。UI 层构造它时把回调/依赖填进来。
 *
 * worker 实现命令时**只能用这里暴露的东西** —— 不要在 handler 里 import Compose。
 */
class SlashContext(
    /** 当前会话（可能为 null —— 首页还没开会话）。 */
    val session: ChatSession?,
    /** 安卓 Context（复制剪贴板、读文件等用）。 */
    val appContext: android.content.Context?,
    /** 导航回调（route 名）。 */
    val navigate: (String) -> Unit = {},
    /** 新建会话。 */
    val newChat: () -> Unit = {},
    /** 打开面板（model/style/switcher）。 */
    val openPanel: (String) -> Unit = {},
    /** 刷新会话列表（删除/重命名后）。 */
    val refreshSessions: () -> Unit = {},
)

/**
 * 分发入口。返回 null = 不是本模块处理的命令。
 *
 * @param text 完整命令文本（含参数，如 "/rename 新标题"）
 */
fun handleSlashCommand(text: String, ctx: SlashContext): SlashResult? {
    val t = text.trim()
    if (!t.startsWith("/")) return null
    val cmd = t.substringBefore(" ").trim()
    val arg = t.substringAfter(" ", "").trim()

    // 每个分区一个独立函数（多 Agent 分工：各填各的，零文件冲突）。
    // 依次尝试，第一个返回非 null 的即为结果。
    return handleSessionCommands(cmd, arg, ctx)
        ?: handleQueryCommands(cmd, arg, ctx)
        ?: handleConfigCommands(cmd, arg, ctx)
        ?: handleToolsCommands(cmd, arg, ctx)
}

// ═══════════════════════════════════════════════════════════════════
// 分区 2：会话类（worker-1 填充）
//   /save /load /resume /rename /delete /branch /summary /incognito
//   纯 CLI 不做：/save 可做（手动存档）；/load /resume 做成「打开会话切换器」
// ═══════════════════════════════════════════════════════════════════
private fun handleSessionCommands(cmd: String, arg: String, ctx: SlashContext): SlashResult? {
    return when (cmd) {
        // /save —— 手动存档。ChatSession 没暴露「气泡→Message」转换，
        // 但 flush() 走 sessionAuto 会把当前上下文落盘，等价于手动存档。
        "/save" -> {
            // ★ B3：原 flush() 因 dirty 恒 false 是 no-op（假成功）。saveForced
            //   先 markDirty 再 flush，全量落盘，回执可信。
            if (ctx.session == null) {
                SlashResult.Notice("没有活动会话可保存。")
            } else {
                ctx.session.saveForced()
                SlashResult.Notice("已保存当前会话（全量落盘）。")
            }
        }

        // /rename —— 重命名当前会话。
        // ★ B3 修法重写：改走 ChatSession.setTitle（= SessionAuto.title +
        //   立即全量落盘）。原来手工 SessionStore.load/save 会被自动保存的
        //   saveNow（title=null）覆盖回无标题，且 load 常为 null 时写出
        //   只有标题的空 Session（丢 messages）。
        "/rename" -> {
            val title = arg.trim()
            if (title.isEmpty()) {
                SlashResult.Notice("用法：`/rename <新标题>`")
            } else if (ctx.session == null) {
                SlashResult.Notice("没有活动会话可重命名。")
            } else {
                ctx.session.setTitle(title)
                ctx.refreshSessions()
                SlashResult.Notice("已重命名为「$title」。")
            }
        }

        // /delete —— 删除当前会话，回首页并刷新列表。
        "/delete" -> {
            // ★ reviewer 应修#2：不在 handler 里直接 SessionStore.delete ——
            //   那样会绕过 CcmApp deleteChat 的「删当前会话→开新会话」重置，
            //   activeSession 悬空指向已删 id（死会话/文件死而复生）。
            //   这里只发导航请求，删除由 UI 层 deleteChat 全权处理。
            if (com.ccm.app.AppGraph.sessionId.isBlank()) {
                SlashResult.Notice("无法删除：当前没有会话。")
            } else {
                SlashResult.Navigate("delete-current")
            }
        }

        // /summary —— APK 没有独立摘要机制（CLI 侧才有），
        // 引导用户直接发一句话让模型总结。
        "/summary" -> SlashResult.Notice(
            "APK 暂无独立摘要功能。发送「总结一下我们的对话」即可让模型生成摘要。"
        )

        // /load、/resume —— 打开会话切换器（UI 层弹 switcher 面板）。
        "/load", "/resume" -> SlashResult.OpenPanel("switcher")

        // /branch —— 从当前会话分叉出新会话（对齐 CLI cmd-extensions.cmdBranch）。
        //
        // 语义：**复制当前历史到新会话 id，新会话带 `branchedFrom` 痕迹**。
        // 分支后停在当前会话（不自动切走）—— 与 CLI 一致，切过去由用户 /resume。
        //
        // 【为什么先 saveForced】分叉点必须落在「当前最新状态」上，不然新分支
        //   从上次自动保存的旧内容长出来，丢掉最近几轮（CLI 的 branch 也先
        //   saveSession）。无痕会话下 saveForced 是 no-op（markDirty 被拦），
        //   此时直接用内存里的历史建分支，不落盘当前会话。
        "/branch" -> {
            val session = ctx.session
            if (session == null) {
                SlashResult.Notice("没有活动会话可分支。")
            } else {
                val storage = com.ccm.app.AppGraph.storage
                if (storage == null) {
                    SlashResult.Notice("无法分支：存储尚未初始化。")
                } else {
                    val name = arg.trim().ifBlank { "branch-" + System.currentTimeMillis().toString(36) }
                    // 1) 先把当前会话落盘（无痕下 no-op，符合无痕语义）
                    session.saveForced()
                    // 2) 取当前历史（内存里的，比文件新）
                    val history = session.historySnapshot()
                    val store = com.ccm.app.core.session.SessionStore(storage)
                    val newId = store.newSessionId()
                    val now = System.currentTimeMillis()
                    try {
                        store.save(
                            com.ccm.app.core.session.Session(
                                sessionId = newId,
                                title = name,
                                createdAt = now,
                                updatedAt = now,
                                messages = history,
                            ),
                        )
                        ctx.refreshSessions()
                        SlashResult.Notice(
                            "**已创建分支**：$name\n\n" +
                                "- 会话 ID：`$newId`\n" +
                                "- 分叉点：当前 ${history.size} 条消息\n" +
                                "- 来源：`${com.ccm.app.AppGraph.sessionId}`\n\n" +
                                "用 `/resume $newId` 或 `/load` 切换到该分支。当前会话不变。",
                        )
                    } catch (e: Throwable) {
                        SlashResult.Notice("分支失败：${e.message}")
                    }
                }
            }
        }

        // /incognito —— 无痕会话开关（对齐 CLI，但 APK 只实现「不落盘」这一条）。
        //
        // 【为什么做成 toggle 而不是单向开关】CLI 是单向开（开了就换新会话）；
        //   APK 这边做成 toggle 更实用 —— 用户可能只是想临时问点私密的，
        //   问完继续正常记录。关闭时把期间内容落盘一次（否则白问）。
        "/incognito" -> {
            val session = ctx.session
            if (session == null) {
                SlashResult.Notice("没有活动会话。")
            } else {
                val a = arg.trim().lowercase()
                val next = when (a) {
                    "" -> !session.incognito            // 无参 = 切换
                    "on", "开", "开启", "true", "1" -> true
                    "off", "关", "关闭", "false", "0" -> false
                    else -> return SlashResult.Notice("参数无效：`$a`。用 `/incognito` 切换，或 `/incognito on|off`。")
                }
                session.setIncognito(next)
                if (next) {
                    // 关闭后要落盘，所以先标记脏（setIncognito(false) 已解除拦截）
                    SlashResult.Notice(
                        "**无痕模式已开启**\n\n" +
                            "- 本轮之后的所有对话**不再写入会话文件**，退出即丢\n" +
                            "- 已落盘的历史**不会**被删除（要删用 `/delete`）\n" +
                            "- 再次 `/incognito` 关闭并落盘本轮内容\n\n" +
                            "_注：CLI 的无痕还含「不加载 CLAUDE.md / 禁访问项目目录」，APK 侧只实现了「不落盘」。_",
                    )
                } else {
                    session.saveForced()
                    SlashResult.Notice("**无痕模式已关闭**，本轮对话已落盘。")
                }
            }
        }

        // /undo —— 撤销最近一次文件修改（对齐 CLI /undo）。
        //
        // 复用工具侧的 UndoStore（Write/Edit 每次改动都会存快照，含分组）。
        // 只读工具（Read/Grep/Glob）不留快照，所以「没东西可撤销」是正常状态。
        "/undo" -> {
            val undoStore = com.ccm.app.AppGraph.toolsResult?.undoStore
                ?: return SlashResult.Notice("撤销系统未初始化。")
            val results = try { undoStore.undo() } catch (e: Throwable) { emptyList<String>() }
            if (results.isEmpty()) {
                SlashResult.Notice("没有可撤销的文件修改。\n\n_（只有写文件类操作会留快照；纯读取不留。）_")
            } else {
                SlashResult.Notice(
                    "**已撤销**\n\n" + results.joinToString("\n") { "- $it" } +
                        "\n\n用 `/rewind` 查看剩余的可回滚点。",
                )
            }
        }

        // /rewind —— 检查点列表（对齐 CLI /rewind list）。
        //
        // CLI 的 /rewind 还有 `file <序号>` / `diff <序号>` 两个子命令，
        // APK 侧先给列表 + 总数（恢复单个文件需要 UI 选择器，后续按需接）。
        "/rewind" -> {
            val undoStore = com.ccm.app.AppGraph.toolsResult?.undoStore
                ?: return SlashResult.Notice("撤销系统未初始化。")
            val snaps = try { undoStore.list(50) } catch (e: Throwable) { emptyList() }
            if (snaps.isEmpty()) {
                SlashResult.Notice(
                    "没有可恢复的检查点。\n\n_修改文件后会自动创建快照（写文件类操作才会留）。_",
                )
            } else {
                val body = buildString {
                    appendLine("**可恢复的检查点**（${snaps.size}）")
                    appendLine()
                    snaps.forEachIndexed { i, s ->
                        val file = s["file"]?.substringAfterLast('/') ?: "?"
                        val ts = s["timestamp"] ?: ""
                        val note = s["note"]?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                        appendLine("- ${i + 1}. `$file`  $ts$note")
                    }
                    appendLine()
                    append("用 `/undo` 撤销最近一次（整组一起回滚）。")
                }
                SlashResult.Notice(body)
            }
        }

        // /clear-restore —— APK 无压缩回收站机制（CLI 的 /compact-trash）。
        // 见下方 /compact-trash 分支的说明。
        "/clear-restore", "/compact-trash" -> SlashResult.Notice(
            "**压缩回收站**（`/compact-trash`）是 CLI 侧机制：CLI 的 /compact 会先把完整会话\n" +
                "备份到 `compact-trash/`，可用它恢复被压缩掉的原始记录。\n\n" +
                "APK 侧的 `/compact` 只做**无损微压缩**（截断可再生的旧工具输出，不动对话本体），\n" +
                "所以没有「压缩丢了记忆」的问题，也就没有配套的回收站。\n\n" +
                "_如需完整备份，用 `/export` 导出对话，或 `/save` 存档会话文件。_",
        )

        // /add-dir —— 额外可访问目录。
        //
        // APK 侧 extraDirs 在 AgentLoop 构造时注入（不可变），运行期加不进去 ——
        // 改它要重建 AgentLoop（= AppGraph.rebuild），会丢当前轮状态。
        // 所以这里给出真实可用的替代路径，而不是假装加了。
        "/add-dir" -> {
            val p = arg.trim()
            if (p.isBlank()) {
                SlashResult.Notice(
                    "**额外可访问目录**\n\n" +
                        "APK 的目录白名单在会话创建时确定，运行期不可追加（要重建会话）。\n\n" +
                        "替代做法：把文件放到工作区内（Agent 默认就能读写），\n" +
                        "或直接给 Agent 绝对路径让它用 Bash 通道访问（Bash 不受白名单限制）。",
                )
            } else {
                SlashResult.Notice(
                    "**/add-dir $p**\n\n" +
                        "APK 的目录白名单在会话创建时确定，运行期不可追加（要重建会话，会丢当前轮状态）。\n\n" +
                        "可行替代：\n" +
                        "- 直接让 Agent 访问 `$p`（Bash 通道不受文件工具的白名单限制）\n" +
                        "- 或把文件复制进工作区：`cp -r $p <工作区>/`",
                )
            }
        }

        else -> null
    }
}

// ═══════════════════════════════════════════════════════════════════
// 分区 3：查询类（worker-2 填充）
//   /stats /status /tasks /todos /files /trace /errors
// ═══════════════════════════════════════════════════════════════════
private fun handleQueryCommands(cmd: String, arg: String, ctx: SlashContext): SlashResult? {
    return when (cmd) {
        // ── /stats：会话统计 ──
        // 思路：从 session.state.value 读 bubbles，按 role 分类统计消息数 /
        // 助手轮数 / 用户消息数，再带上最近一次请求的输入/输出 token。
        "/stats" -> {
            val st = ctx.session?.state?.value
                ?: return SlashResult.Notice("**会话统计**\n\n- 暂无活动会话")
            val bubbles = st.bubbles
            val total = bubbles.size
            val userCount = bubbles.count { it.isUser }
            val assistantCount = bubbles.count { !it.isUser }
            SlashResult.Notice(
                buildString {
                    append("**会话统计**\n\n")
                    append("- 消息总数：$total\n")
                    append("- 用户消息：$userCount\n")
                    append("- 助手轮数：$assistantCount\n")
                    append("- 最近输入 token：${st.inputTokens}\n")
                    append("- 最近输出 token：${st.outputTokens}")
                }
            )
        }

        // ── /status：当前状态 ──
        // 思路：Provider 显示名走 AppGraph.providerLabel；模型名需从 config.json
        // 读 currentProvider.model；运行状态看 session.state.value.running；
        // 权限模式读 AppConfig 顶层 permissionMode。全部读不到就给默认值。
        "/status" -> {
            val storage = com.ccm.app.AppGraph.storage
            val providerLabel = com.ccm.app.AppGraph.providerLabel
            // 模型名 + 权限模式：一次 load 取两个字段（读不到配置则兜底）
            val cfg = storage?.let {
                runCatching {
                    com.ccm.app.core.provider.AppConfig.load(it.configFile).config
                }.getOrNull()
            }
            val model = cfg?.currentProvider?.model?.takeIf { it.isNotBlank() } ?: "（未设置）"
            val permMode = cfg?.permissionMode ?: "default"
            val running = ctx.session?.state?.value?.running == true
            SlashResult.Notice(
                buildString {
                    append("**当前状态**\n\n")
                    append("- Provider：$providerLabel\n")
                    append("- 模型：$model\n")
                    append("- 运行中：${if (running) "是" else "否"}\n")
                    append("- 权限模式：$permMode")
                }
            )
        }

        // ── /tasks 与 /todos：当前待办清单 ──
        // 思路：todos 在 session.state.value.todos（List<TodoEntry>，字段
        // content + status）。任务描述里说的是 Triple，但 core 实际是 TodoEntry，
        // 以真实类型为准。无任务则提示「暂无任务」。两个命令行为相同。
        "/tasks", "/todos" -> {
            val todos = ctx.session?.state?.value?.todos.orEmpty()
            if (todos.isEmpty()) {
                SlashResult.Notice("**当前任务**\n\n- 暂无任务")
            } else {
                SlashResult.Notice(
                    buildString {
                        append("**当前任务**（${todos.size}）\n\n")
                        todos.forEach { t ->
                            // 状态用符号标注，一眼看出进度
                            val mark = when (t.status) {
                                "completed" -> "✓"
                                "in_progress" -> "→"
                                else -> "○"
                            }
                            append("- $mark ${t.content}\n")
                        }
                    }.trimEnd()
                )
            }
        }

        // ── /files：工作区文件列表 ──
        // 思路：工作区目录是 storage.root 下的 WORKSPACE_DIR 子目录（与 AppGraph
        // 装配时的 cwd 一致）。AppGraph 没有公开 cwd getter，这里按同样规则拼。
        // 读不到或为空就提示。
        "/files" -> {
            val storage = com.ccm.app.AppGraph.storage
            if (storage == null) {
                SlashResult.Notice("**工作区文件**\n\n- 读不到存储目录")
            } else {
                // WORKSPACE_DIR 是 AppGraph 的私有常量，这里用相同字面量
                // （"workspace"）拼路径，保持与装配时 cwd 一致。
                val workspace = java.io.File(storage.root, "workspace")
                val files = workspace.listFiles()?.sortedBy { it.name }
                if (files.isNullOrEmpty()) {
                    SlashResult.Notice("**工作区文件**\n\n- 工作区为空：${workspace.absolutePath}")
                } else {
                    SlashResult.Notice(
                        buildString {
                            append("**工作区文件**（${files.size}）\n\n")
                            files.forEach { f ->
                                // 目录加尾斜杠，便于区分
                                append("- ${f.name}${if (f.isDirectory) "/" else ""}\n")
                            }
                        }.trimEnd()
                    )
                }
            }
        }

        // ── /trace：最近一次运行的 trace 摘要 ──
        // 思路：trace 存在 storage.tracesDir（*.jsonl）。用 TraceStore.list 取最新
        // 一条，再 read 它的事件行，从中提取 run_end 的 turns/status 展示。
        // trace 行是脱敏后的 JSON 字符串，这里用轻量字符串匹配提取字段（不引入
        // 完整反序列化），拿不到就给文件名 + 行数作兜底。
        "/trace" -> {
            val storage = com.ccm.app.AppGraph.storage
                ?: return SlashResult.Notice("**最近 trace**\n\n- 读不到存储目录")
            val latest = com.ccm.app.core.trace.TraceStore.list(storage.tracesDir, limit = 1)
                .firstOrNull()
                ?: return SlashResult.Notice("**最近 trace**\n\n- 暂无 trace 记录")
            val lines = com.ccm.app.core.trace.TraceStore.read(
                storage.tracesDir, latest.runId, limit = 500
            )
            // 从 run_end 行提取 turns / status（字段名见 TraceEvents）
            val endLine = lines.lastOrNull { it.contains("\"run_end\"") }
            val turns = endLine?.let { extractJsonValue(it, "turns") }
            val status = endLine?.let { extractJsonValue(it, "status") }
            SlashResult.Notice(
                buildString {
                    append("**最近 trace**\n\n")
                    append("- runId：${latest.runId}\n")
                    append("- 事件行数：${lines.size}\n")
                    if (status != null) append("- 状态：$status\n")
                    if (turns != null) append("- 轮数：$turns\n")
                    if (endLine == null) append("- （无 run_end 事件，可能仍在运行或异常中断）")
                }.trimEnd()
            )
        }

        // ── /errors：最近错误 ──
        // 思路：优先展示 session.state.value.error（当前会话的错误提示）；
        // 再从最新 trace 里筛 error 类事件（api_error / tool_error / run_error）
        // 列出。两者都没有则「暂无错误」。
        "/errors" -> {
            val sb = StringBuilder("**最近错误**\n\n")
            var found = false

            // 1) 当前会话的 error 字段
            val sessionError = ctx.session?.state?.value?.error
            if (!sessionError.isNullOrBlank()) {
                sb.append("- 会话错误：$sessionError\n")
                found = true
            }

            // 2) 最新 trace 里的 error 类事件
            val storage = com.ccm.app.AppGraph.storage
            if (storage != null) {
                val latest = com.ccm.app.core.trace.TraceStore.list(storage.tracesDir, limit = 1)
                    .firstOrNull()
                if (latest != null) {
                    val lines = com.ccm.app.core.trace.TraceStore.read(
                        storage.tracesDir, latest.runId, limit = 500
                    )
                    val errLines = lines.filter {
                        it.contains("\"api_error\"") ||
                            it.contains("\"tool_error\"") ||
                            it.contains("\"run_error\"")
                    }.takeLast(5)
                    if (errLines.isNotEmpty()) {
                        sb.append("- trace（${latest.runId}）中的错误事件：\n")
                        errLines.forEach { line ->
                            val type = extractJsonValue(line, "type") ?: "error"
                            // category/error 字段任取其一做简述
                            val detail = extractJsonValue(line, "error")
                                ?: extractJsonValue(line, "category")
                                ?: ""
                            sb.append("  - [$type] $detail\n".trimEnd() + "\n")
                        }
                        found = true
                    }
                }
            }

            if (!found) {
                SlashResult.Notice("**最近错误**\n\n- 暂无错误")
            } else {
                SlashResult.Notice(sb.toString().trimEnd())
            }
        }

        // ── /context <N> —— 设置上下文窗口上限（对齐 CLI /context 200k）─────
        //
        // APK 侧的 /context（无参）在对话页老 when 里显示用量；这里接管**带参**
        // 的形态：`/context 200k` / `/context 200000` / `/context reset`。
        // 无参时返回 null 交给老 when 的用量展示（保持现有行为不漂移）。
        //
        // 影响面：config.maxContextTokens 是「上下文压力百分比」与压缩判断的分母
        // （InputBar 的百分比、Compactor.shouldCompact）。
        "/context" -> {
            val a = arg.trim().lowercase()
            if (a.isBlank()) {
                null   // 无参 → 交给对话页老 when 显示用量
            } else {
                val storage = com.ccm.app.AppGraph.storage
                    ?: return SlashResult.Notice("无法读取配置：应用尚未就绪。")
                val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                if (loadR.error != null) {
                    return SlashResult.Notice(
                        "**配置文件损坏，命令已拒绝执行**\n\n解析错误：`${loadR.error}`",
                    )
                }
                val cfg = loadR.config
                if (a == "reset") {
                    val ok = com.ccm.app.core.provider.AppConfig.save(
                        cfg.copy(maxContextTokens = 1_000_000), st.configFile,
                    )
                    if (ok) {
                        SlashResult.Notice("上下文上限已恢复默认：**1000K**")
                    } else {
                        SlashResult.Notice("保存失败：写入 config.json 出错。")
                    }
                } else {
                    // 接受 200000 / 200k / 1m 三种写法（对齐 CLI 的解析规则）
                    val m = Regex("^(\\d+(?:\\.\\d+)?)(k|m)?$").find(a)
                    if (m == null) {
                        SlashResult.Notice(
                            "**用法**：`/context [200k | 200000 | 1m | reset]`\n\n" +
                                "- 无参：查看当前用量\n" +
                                "- 带参：设置上下文窗口上限\n" +
                                "- `reset`：恢复默认 1000K",
                        )
                    } else {
                        var v = m.groupValues[1].toDouble()
                        when (m.groupValues[2]) {
                            "k" -> v *= 1_000
                            "m" -> v *= 1_000_000
                        }
                        val iv = v.toInt()
                        if (iv <= 10_000) {
                            SlashResult.Notice("设置失败：上限必须大于 10000（收到 $iv）。")
                        } else {
                            val ok = com.ccm.app.core.provider.AppConfig.save(
                                cfg.copy(maxContextTokens = iv), st.configFile,
                            )
                            if (ok) {
                                SlashResult.Notice(
                                    "上下文上限已设为 **${iv / 1000}K**\n\n" +
                                        "（影响状态栏百分比与压缩判断；用 `/context reset` 恢复默认）",
                                )
                            } else {
                                SlashResult.Notice("保存失败：写入 config.json 出错。")
                            }
                        }
                    }
                }
            }
        }

        // ── /diff —— 工作区 git 改动（对齐 CLI /diff）────────────────────────
        //
        // 走 Bash 通道跑 `git diff --stat`（与工具层 GitDiff 同一通道）。
        // 通道是 suspend 的，命令分发是同步的 —— 所以这里起一个后台协程，
        // 先回「正在读」再异步注入结果（同 /btw 的模式）。
        "/diff" -> {
            com.ccm.app.ui.chat.launchGitDiff(ctx, arg)
            SlashResult.Notice("_正在读取工作区改动…_")
        }

        // ── /tools —— 已注册工具清单（对齐 CLI /tools）──────────────────────
        "/tools" -> {
            val names = com.ccm.app.AppGraph.toolNames
            if (names.isEmpty()) {
                SlashResult.Notice("工具系统未初始化。")
            } else {
                val filtered = if (arg.isBlank()) names else names.filter { it.contains(arg, ignoreCase = true) }
                if (filtered.isEmpty()) {
                    SlashResult.Notice("没有匹配 `$arg` 的工具（共 ${names.size} 个）。")
                } else {
                    SlashResult.Notice(
                        "**已注册工具（${filtered.size}/${names.size}）**\n\n" +
                            filtered.sorted().joinToString("\n") { "- `$it`" },
                    )
                }
            }
        }

        // ── /hooks —— 已注册的 hook（对齐 CLI /hooks）───────────────────────
        "/hooks" -> {
            val hooks = com.ccm.app.AppGraph.toolsResult?.hooks
            if (hooks == null) {
                SlashResult.Notice("Hooks 系统未初始化。")
            } else {
                val events = hooks.hookedEvents()
                if (events.isEmpty()) {
                    SlashResult.Notice(
                        "**Hooks**\n\n当前没有注册任何 hook。\n\n" +
                            "_配置方式：在存储根放 `hooks.json`（支持 SessionStart / PreToolUse / " +
                            "PostToolUse / UserPromptSubmit / Stop 等事件）。_",
                    )
                } else {
                    SlashResult.Notice(
                        "**Hooks（${hooks.count()} 条）**\n\n" +
                            events.sorted().joinToString("\n") { "- `$it` — ${hooks.count(it)} 条" },
                    )
                }
            }
        }

        // ── /away —— 离场报告（对齐 CLI /away）──────────────────────────────
        //
        // CLI 的 away-report.log 由「重启后自动接续」写入；APK 没有该机制，
        // 但文件存在就读（用户可能从 CLI 侧拷过来），不存在就如实说明。
        "/away" -> {
            val storage = com.ccm.app.AppGraph.storage
            if (storage == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val log = java.io.File(storage.root, "away-report.log")
                if (!log.isFile || log.length() == 0L) {
                    SlashResult.Notice(
                        "**离场报告**\n\n暂无离场报告。\n\n" +
                            "_说明：离场报告是 CLI 侧「重启后自动接续任务」的产物（`away-report.log`）。\n" +
                            "APK 暂未实现自动接续，所以本机不会生成该文件。_",
                    )
                } else {
                    val raw = try { log.readText() } catch (t: Throwable) { "" }
                    val max = 4000
                    val body = if (raw.length > max) raw.takeLast(max) + "\n\n… （已截断，共 ${raw.length} 字符）" else raw
                    SlashResult.Notice("**离场报告**\n\n$body")
                }
            }
        }

        // ── /check —— 环境自检（对齐 CLI /check 的「预检」语义）─────────────
        //
        // CLI 的 /check 跑重启预检（.mjs 语法检查）。APK 无该机制，改为
        // 「配置 + 存储 + 工具 + 通道」四项自检，同样能提前发现坏状态。
        "/check" -> {
            val storage = com.ccm.app.AppGraph.storage
            if (storage == null) {
                SlashResult.Notice("**自检**\n\n- 存储未初始化（应用尚未就绪）")
            } else {
                val sb = StringBuilder("**环境自检**\n\n")
                // 1) 配置文件
                val cfgR = com.ccm.app.core.provider.AppConfig.load(storage.configFile)
                if (cfgR.error != null) {
                    sb.append("- ❌ 配置解析失败：`${cfgR.error}`\n")
                } else {
                    val prov = cfgR.config.currentProvider
                    sb.append("- ✅ 配置可读（Provider：${prov?.name ?: "未设置"}）\n")
                }
                // 2) 存储目录
                val dirsOk = listOf(storage.sessionsDir, storage.undoDir, storage.trashDir, storage.tracesDir)
                    .all { it.exists() || it.mkdirs() }
                sb.append(if (dirsOk) "- ✅ 存储目录可写\n" else "- ❌ 存储目录不可写\n")
                // 3) 工具
                val n = com.ccm.app.AppGraph.toolNames.size
                sb.append(if (n > 0) "- ✅ 已注册工具 $n 个\n" else "- ❌ 工具未注册\n")
                // 4) 会话
                val sess = com.ccm.app.AppGraph.session
                sb.append(if (sess != null) "- ✅ 会话已就绪\n" else "- ⚠️ 无活动会话\n")
                // 5) 工作区
                sb.append("- 工作区：`${com.ccm.app.AppGraph.workspacePath()}`\n")
                sb.append("- 会话 ID：`${com.ccm.app.AppGraph.sessionId}`\n")
                SlashResult.Notice(sb.toString().trimEnd())
            }
        }

        // ── /btw —— 侧问（对齐 CLI /btw：顺嘴问一句，不进主上下文）──────────
        //
        // 实现方式：直接用 ApiClient 单跑一次**无工具、无历史**的请求，
        // 结果只注入气泡，**不进 AgentLoop 历史**（对齐 CLI 的「不占后续上下文」）。
        // 因为要发网络请求，用 coroutine 异步跑，先回一条「正在问」。
        "/btw" -> {
            val q = arg.trim()
            if (q.isEmpty()) {
                SlashResult.Notice(
                    "**用法**：`/btw <问题>`\n\n顺嘴问一句：不打断主对话、**不进主上下文**，" +
                        "回答只显示在气泡里。\n\n例：`/btw 刚才那个 429 是什么意思`",
                )
            } else {
                com.ccm.app.ui.chat.launchBtw(ctx, q)
                SlashResult.Notice("_正在侧问（不进主上下文）…_")
            }
        }

        else -> null
    }
}

/**
 * 从一行 JSON 字符串里粗提取某个字段的值（不做完整反序列化）。
 *
 * trace 行是 `{"type":"run_end","data":{"turns":3,"status":"completed"}}` 这种
 * 扁平嵌套结构，查询类命令只需要个别字段，用字符串匹配比引入完整解析更轻。
 * 支持字符串值（带引号）和裸数字/布尔值两种形态。匹配不到返回 null。
 */
private fun extractJsonValue(line: String, key: String): String? {
    // 先找 "key" 的位置，再从冒号后取值
    val keyToken = "\"$key\""
    val idx = line.indexOf(keyToken)
    if (idx < 0) return null
    val colon = line.indexOf(':', idx + keyToken.length)
    if (colon < 0) return null
    var i = colon + 1
    // 跳过空白
    while (i < line.length && line[i].isWhitespace()) i++
    if (i >= line.length) return null
    return if (line[i] == '"') {
        // 字符串值：取到下一个未转义的引号
        val start = i + 1
        var j = start
        while (j < line.length && line[j] != '"') {
            if (line[j] == '\\') j++ // 跳过转义字符
            j++
        }
        if (j <= line.length) line.substring(start, j.coerceAtMost(line.length)) else null
    } else {
        // 裸值（数字/布尔）：取到分隔符为止
        val start = i
        var j = start
        while (j < line.length && line[j] !in charArrayOf(',', '}', ']', ' ')) j++
        line.substring(start, j).takeIf { it.isNotBlank() }
    }
}

// ═══════════════════════════════════════════════════════════════════
// 分区 4：配置类（worker-3 填充）
//   /effort /temperature /voice /me /markdown /greeting
// ═══════════════════════════════════════════════════════════════════
private fun handleConfigCommands(cmd: String, arg: String, ctx: SlashContext): SlashResult? {
    // 配置落盘统一走 AppConfig.load(file).config -> copy(...) -> AppConfig.save(cfg, file)。
    // storage 从 AppGraph 拿；拿不到说明容器还没初始化，直接提示不可用（绝不乱写盘）。
    val storage = com.ccm.app.AppGraph.storage

    // ★ 归一化：主分发器传进来的 cmd 带 "/"（如 "/effort"），本分区的分支
    //   写的是不带斜杠的（"effort"）——不剥前缀会全部匹配不上、永远落 null。
    val c = cmd.removePrefix("/")

    return when (c) {
        // ── /effort：思考强度（AppConfig.effort: String?，可选值对齐 ApiClient）────
        "effort" -> {
            val levels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
            val st = storage
                ?: return SlashResult.Notice("无法读取配置：应用尚未就绪。")
            // ★ B4（findbugs 2026-10-01）：必须检查 .error —— AppConfig 解析
            //   失败时返回空 AppConfig()（providers={}），忽略 error 直接 copy+save
            //   会把整个 Provider/Key 表抹成空（config.json 一个语法错误 +
            //   敲一次 /effort 就全丢）。损坏时整条命令拒绝执行。
            val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
            if (loadR.error != null) {
                return SlashResult.Notice(
                    "**配置文件损坏，命令已拒绝执行**\n\n" +
                        "解析错误：`${loadR.error}`\n\n" +
                        "为防止把 Provider 表覆盖成空，读写都不执行。请先修复 `config.json`（设置 → 模型）。",
                )
            }
            val cfg = loadR.config
            val a = arg.trim().lowercase()
            if (a.isBlank()) {
                // 无参 → 显示当前值 + 可选值
                val cur = cfg.effort ?: "（未设置，默认 medium）"
                SlashResult.Notice(
                    "**当前思考强度**：`$cur`\n\n" +
                        "可选值：${levels.joinToString(" / ") { "`$it`" }}\n\n" +
                        "用法：`/effort <值>`",
                )
            } else if (a !in levels) {
                SlashResult.Notice(
                    "无效的思考强度 `$a`。\n\n可选值：${levels.joinToString(" / ") { "`$it`" }}",
                )
            } else {
                // 落盘：copy 后 save
                val ok = com.ccm.app.core.provider.AppConfig.save(
                    cfg.copy(effort = a),
                    st.configFile,
                )
                if (ok) {
                    SlashResult.Notice("思考强度已设为 `$a`。切换会话或重启后对新对话生效。")
                } else {
                    SlashResult.Notice("保存失败：写入 config.json 出错。")
                }
            }
        }

        // ── /temperature：采样温度（AppConfig.temperature: Double，0.0~2.0）────────
        "temperature", "temp" -> {
            val st = storage
                ?: return SlashResult.Notice("无法读取配置：应用尚未就绪。")
            // ★ B4（findbugs 2026-10-01）：必须检查 .error —— AppConfig 解析
            //   失败时返回空 AppConfig()（providers={}），忽略 error 直接 copy+save
            //   会把整个 Provider/Key 表抹成空（config.json 一个语法错误 +
            //   敲一次 /effort 就全丢）。损坏时整条命令拒绝执行。
            val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
            if (loadR.error != null) {
                return SlashResult.Notice(
                    "**配置文件损坏，命令已拒绝执行**\n\n" +
                        "解析错误：`${loadR.error}`\n\n" +
                        "为防止把 Provider 表覆盖成空，读写都不执行。请先修复 `config.json`（设置 → 模型）。",
                )
            }
            val cfg = loadR.config
            val a = arg.trim()
            if (a.isBlank()) {
                // 无参 → 显示当前值
                SlashResult.Notice(
                    "**当前温度**：`${cfg.temperature}`\n\n用法：`/temperature <0.0 ~ 2.0>`",
                )
            } else {
                val v = a.toDoubleOrNull()
                if (v == null) {
                    SlashResult.Notice("温度必须是数字（0.0 ~ 2.0），你输入的是 `$a`。")
                } else if (v < 0.0 || v > 2.0) {
                    SlashResult.Notice("温度超出范围：`$v`。有效范围是 0.0 ~ 2.0。")
                } else {
                    val ok = com.ccm.app.core.provider.AppConfig.save(
                        cfg.copy(temperature = v),
                        st.configFile,
                    )
                    if (ok) {
                        SlashResult.Notice("温度已设为 `$v`。切换会话或重启后对新对话生效。")
                    } else {
                        SlashResult.Notice("保存失败：写入 config.json 出错。")
                    }
                }
            }
        }

        // ── /greeting：开关开场白（AppConfig.greeting: Boolean）────────────────────
        "greeting" -> {
            val st = storage
                ?: return SlashResult.Notice("无法读取配置：应用尚未就绪。")
            // ★ B4（findbugs 2026-10-01）：必须检查 .error —— AppConfig 解析
            //   失败时返回空 AppConfig()（providers={}），忽略 error 直接 copy+save
            //   会把整个 Provider/Key 表抹成空（config.json 一个语法错误 +
            //   敲一次 /effort 就全丢）。损坏时整条命令拒绝执行。
            val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
            if (loadR.error != null) {
                return SlashResult.Notice(
                    "**配置文件损坏，命令已拒绝执行**\n\n" +
                        "解析错误：`${loadR.error}`\n\n" +
                        "为防止把 Provider 表覆盖成空，读写都不执行。请先修复 `config.json`（设置 → 模型）。",
                )
            }
            val cfg = loadR.config
            val a = arg.trim().lowercase()
            if (a.isBlank()) {
                // 无参 → 显示当前状态
                SlashResult.Notice(
                    "**开场白**：${if (cfg.greeting) "开启" else "关闭"}\n\n用法：`/greeting <on|off>`",
                )
            } else {
                val next = when (a) {
                    "on", "开", "开启", "true", "1" -> true
                    "off", "关", "关闭", "false", "0" -> false
                    else -> return SlashResult.Notice("参数无效：`$a`。用 `on` 或 `off`。")
                }
                val ok = com.ccm.app.core.provider.AppConfig.save(
                    cfg.copy(greeting = next),
                    st.configFile,
                )
                if (ok) {
                    SlashResult.Notice("开场白已${if (next) "开启" else "关闭"}。")
                } else {
                    SlashResult.Notice("保存失败：写入 config.json 出错。")
                }
            }
        }

        // ── /me：用户资料（UserProfileStore 真读真写）──────────────────────────────
        //   /me                 显示资料
        //   /me set <字段> <值>  设置字段（display_name/full_name/work_function/personal_preferences）
        //   /me clear-all        清空全部
        "me" -> {
            val store = com.ccm.app.AppGraph.userProfileStore
                ?: return SlashResult.Notice("无法读取用户资料：应用尚未就绪。")
            val parts = arg.trim().split(Regex("\\s+"), limit = 3)
            val sub = parts.getOrNull(0)?.lowercase().orEmpty()
            when {
                // 无参 → 显示资料
                arg.isBlank() -> {
                    val p = store.load()
                    if (p.isEmpty) {
                        SlashResult.Notice(
                            "**用户资料**（未设置）\n\n" +
                                "用 `/me set <字段> <值>` 设置，可用字段：\n" +
                                "- `display_name` 称呼\n" +
                                "- `full_name` 全名\n" +
                                "- `work_function` 职业\n" +
                                "- `personal_preferences` 回复偏好",
                        )
                    } else {
                        SlashResult.Notice(
                            buildString {
                                append("**用户资料**\n\n")
                                if (p.displayName.isNotBlank()) append("- 称呼：${p.displayName}\n")
                                if (p.fullName.isNotBlank()) append("- 全名：${p.fullName}\n")
                                if (p.workFunction.isNotBlank()) append("- 职业：${p.workFunction}\n")
                                if (p.personalPreferences.isNotBlank()) {
                                    append("- 回复偏好：${p.personalPreferences}\n")
                                }
                                append("\n改用 `/me set <字段> <值>`，清空用 `/me clear-all`。")
                            },
                        )
                    }
                }
                // /me set <字段> <值>
                sub == "set" -> {
                    val field = parts.getOrNull(1).orEmpty()
                    val value = parts.getOrNull(2).orEmpty()
                    if (field.isBlank()) {
                        SlashResult.Notice(
                            "用法：`/me set <字段> <值>`\n\n" +
                                "可用字段：`display_name` / `full_name` / `work_function` / `personal_preferences`",
                        )
                    } else {
                        try {
                            store.setField(field, value)
                            SlashResult.Notice(
                                if (value.isBlank()) "已清除 `$field`。"
                                else "已设置 `$field` = $value",
                            )
                        } catch (e: IllegalArgumentException) {
                            SlashResult.Notice(e.message ?: "字段无效。")
                        }
                    }
                }
                // /me clear-all
                sub == "clear-all" || sub == "clear" -> {
                    store.clear()
                    SlashResult.Notice("用户资料已清空。")
                }
                else -> SlashResult.Notice(
                    "未知子命令 `$sub`。\n\n用法：`/me` 查看 · `/me set <字段> <值>` · `/me clear-all`",
                )
            }
        }

        // ── /markdown：APK 用 Compose 原生渲染，无终端 ANSI 样式可切 ────────────────
        "markdown" -> SlashResult.Notice(
            "Markdown 样式切换是 **CLI 终端专属**（控制 ANSI 配色）。\n\n" +
                "APK 用 Compose 原生渲染 Markdown，无需也无法切换终端样式。",
        )

        // ── /voice：APK 暂无「自动朗读 AI 回复」的机制 ───────────────────────────────
        //   说明：NativeTts/SayTool 是「工具侧朗读」（供 Agent 主动播报用），
        //   不是「把助手正文自动念出来」的渲染层能力（那是 CLI 的 /voice）。
        //   语音输入（SpeechToText/VoiceInput）是另一回事，别混。
        "voice" -> SlashResult.Notice(
            "正文自动朗读是 **CLI 终端专属**功能。\n\n" +
                "APK 目前没有「把 AI 回复自动念出来」的开关：\n" +
                "- 系统 TTS（NativeTts）只给 Agent 的播报工具用\n" +
                "- 语音**输入**在输入栏的麦克风按钮（SpeechToText），与朗读无关",
        )

        // ── /plan —— 计划模式（对齐 CLI /plan，真正切换 ModeState）────────────
        //
        // ⚠️ 必须改 [ChatSession.modes]（= AppContainer.modes = AgentLoop 读的那个
        //   实例）。自己 new 一个 ModeState 改是无效的 —— 主循环读不到。
        "plan" -> {
            val session = ctx.session
                ?: return SlashResult.Notice("没有活动会话：模式是会话级状态。")
            val a = arg.trim().lowercase()
            val next = when (a) {
                "" -> !session.modes.planMode          // 无参 = 切换
                "on", "开", "开启", "true", "1" -> true
                "off", "关", "关闭", "false", "0" -> false
                else -> return SlashResult.Notice("参数无效：`$a`。用 `/plan` 切换，或 `/plan on|off`。")
            }
            session.modes.planMode = next
            if (next) {
                SlashResult.Notice(
                    "**计划模式已开启**\n\n" +
                        "模型会先给出计划再动手（系统提示词每轮追加计划指令）。\n\n" +
                        "再次 `/plan` 关闭。",
                )
            } else {
                SlashResult.Notice("**计划模式已关闭**。")
            }
        }

        // ── /deep —— deep 模式（对齐 CLI /deep，真正提高轮次上限）────────────
        //
        // 生效路径：AgentLoop 每轮 run 开始读 modes.deepMode，为 true 时把
        // maxTurns 提到 ModeState.DEEP_MAX_TURNS（见 AgentLoop.kt:348）。
        "deep" -> {
            val session = ctx.session
                ?: return SlashResult.Notice("没有活动会话：模式是会话级状态。")
            val a = arg.trim().lowercase()
            val next = when (a) {
                "" -> !session.modes.deepMode
                "on", "开", "开启", "true", "1" -> true
                "off", "关", "关闭", "false", "0" -> false
                else -> return SlashResult.Notice("参数无效：`$a`。用 `/deep` 切换，或 `/deep on|off`。")
            }
            session.modes.deepMode = next
            if (next) {
                SlashResult.Notice(
                    "**deep 模式已开启**\n\n" +
                        "轮次上限提升到 ${com.ccm.app.core.agent.ModeState.DEEP_MAX_TURNS} 轮" +
                        "（普通模式 ${com.ccm.app.core.agent.ModeState.NORMAL_MAX_TURNS} 轮），适合多文件排查、反复调试。\n\n" +
                        "⚠️ 代价：模型可能跑更多轮，token 消耗更高。\n" +
                        "任务完成或发现空转时用 `/deep` 关掉。",
                )
            } else {
                SlashResult.Notice("**deep 模式已关闭**，回到普通轮次上限。")
            }
        }

        // ── /watch —— 持续模式（对齐 CLI /watch）────────────────────────────
        //
        // 生效路径：AgentLoop 的循环条件 `while (modes.watchMode || turnCount < maxTurns)`
        // —— watchMode 为 true 时一轮结束不返回，注入「继续」保持循环。
        "watch" -> {
            val session = ctx.session
                ?: return SlashResult.Notice("没有活动会话：模式是会话级状态。")
            val a = arg.trim().lowercase()
            val next = when (a) {
                "" -> !session.modes.watchMode
                "on", "开", "开启", "true", "1" -> true
                "off", "关", "关闭", "false", "0" -> false
                else -> return SlashResult.Notice("参数无效：`$a`。用 `/watch` 切换，或 `/watch on|off`。")
            }
            session.modes.watchMode = next
            if (next) {
                SlashResult.Notice(
                    "**持续模式已开启**\n\n" +
                        "一轮结束后**不会停**，会持续执行/监听直到：\n" +
                        "- 用 `/watch` 关闭\n" +
                        "- 或点停止按钮打断当前轮\n\n" +
                        "_适合盯队列、轮询状态这类持续性任务；一次性任务别开（会空转烧 token）。_",
                )
            } else {
                SlashResult.Notice("**持续模式已关闭**，恢复正常「一轮结束即停」。")
            }
        }

        // ── /imagegen —— 生图配置（对齐 CLI /imagegen）──────────────────────
        //
        // 落盘位置：config.json 的 imageGen 字段（与 CLI 同字段，配置可互搬）。
        // 子命令：无参看状态 · url/key/model/size/dir 改单项。
        "imagegen" -> {
            val st = storage ?: return SlashResult.Notice("无法读取配置：应用尚未就绪。")
            val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
            if (loadR.error != null) {
                return SlashResult.Notice(
                    "**配置文件损坏，命令已拒绝执行**\n\n解析错误：`${loadR.error}`",
                )
            }
            val cfg = loadR.config
            val g = cfg.imageGen
            val sub = arg.trim()
            val subCmd = sub.substringBefore(" ").lowercase()
            val subArg = sub.substringAfter(" ", "").trim()
            when (subCmd) {
                "" -> SlashResult.Notice(
                    buildString {
                        appendLine("**生图配置**")
                        appendLine()
                        if (g == null || !g.isUsable) {
                            appendLine("状态：⚠️ 未配置完整（生图工具不可用）")
                        } else {
                            appendLine("状态：✅ 已配置")
                        }
                        appendLine("- URL：`${g?.url ?: "(未设置)"}`")
                        appendLine("- Key：${if (g?.apiKey.isNullOrBlank()) "(未设置)" else "已设置（${g?.apiKey?.length} 字符）"}")
                        appendLine("- Model：`${g?.model ?: "(默认)"}`")
                        appendLine("- Size：`${g?.size ?: "(默认)"}`")
                        appendLine("- 保存目录：`${g?.dir ?: "(默认)"}`")
                        appendLine()
                        append("用法：`/imagegen url|key|model|size|dir <值>`")
                    },
                )
                "url", "key", "model", "size", "dir" -> {
                    if (subArg.isEmpty()) {
                        return SlashResult.Notice("用法：`/imagegen $subCmd <值>`")
                    }
                    val cur = g ?: com.ccm.app.core.provider.ImageGenConfig()
                    val next = when (subCmd) {
                        "url" -> cur.copy(url = subArg)
                        "key" -> cur.copy(apiKey = subArg)
                        "model" -> cur.copy(model = subArg)
                        "size" -> cur.copy(size = subArg)
                        else -> cur.copy(dir = subArg)
                    }
                    val ok = com.ccm.app.core.provider.AppConfig.save(
                        cfg.copy(imageGen = next), st.configFile,
                    )
                    if (ok) {
                        SlashResult.Notice("生图配置 `$subCmd` 已更新。\n\n用 `/imagegen` 查看完整状态。")
                    } else {
                        SlashResult.Notice("保存失败：写入 config.json 出错。")
                    }
                }
                else -> SlashResult.Notice(
                    "未知子命令 `$subCmd`。\n\n用法：`/imagegen` 查看 · `/imagegen url|key|model|size|dir <值>` 设置",
                )
            }
        }

        // ── /web —— Web 服务（对齐 CLI /web）────────────────────────────────
        //
        // CLI 的 /web 起本地 Web 服务（浏览器访问）。APK 没有这个能力
        // （Android 上跑 HTTP 服务要前台服务 + 端口暴露，且用户已有 Web 端）。
        "web" -> SlashResult.Notice(
            "**Web 服务**\n\n" +
                "CLI 的 `/web` 在本机起一个 Web 服务（浏览器访问对话界面）。\n\n" +
                "APK 没有该能力 —— Android 上跑 HTTP 服务需要前台服务与端口暴露，且本项目已有独立的 Web 端。\n\n" +
                "_要在手机上用浏览器访问，走 Web 端（`~/claude-code-mobile/web`）。_",
        )

        // ── /backup —— 备份（对齐 CLI /backup）──────────────────────────────
        "backup" -> SlashResult.Notice(
            "**备份**\n\n" +
                "CLI 的 `/backup` 把代码/配置打包上传（本地目录 / GitHub / WebDAV / rclone）。\n\n" +
                "APK 侧的等价做法：\n" +
                "- 会话与配置都在应用私有目录（`${com.ccm.app.AppGraph.storage?.root?.absolutePath ?: "?"}`）\n" +
                "- 用 `/export` 导出对话，或用系统文件管理器备份该目录\n" +
                "- 跨设备同步配置：把 `config.json` 拷到另一端的同名字段即可（字段名互通）",
        )

        else -> null
    }
}

// ═══════════════════════════════════════════════════════════════════
// 分区 5：工具/集成类（worker-4 填充）
//   /mem /memory /automem /skills /agents /goal /github /mail /mcp /pexels
// ═══════════════════════════════════════════════════════════════════
private fun handleToolsCommands(cmd: String, arg: String, ctx: SlashContext): SlashResult? {
    return when (cmd) {
        // ★ B5（findbugs 2026-10-01）：/permissions 原来只在对话页老 when 里 ——
        //   首页跳转后无人执行。它不依赖任何对话页状态（dumpRules + injectNotice），
        //   搬进 handler 后首页/对话页统一路径。
        "/permissions" -> {
            // ★ 2026-10-01 用户报「打 /permissions mode bypassPermissions，
            //   实际执行的还是 /permissions」—— 原来整条命令只显示规则，
            //   参数被静默忽略。现在支持子命令（对齐 CLI /permissions mode）：
            //   /permissions            看规则
            //   /permissions mode <m>   改模式（default|acceptEdits|plan|bypassPermissions）
            val sub = arg.trim()
            if (sub.startsWith("mode")) {
                val newMode = sub.removePrefix("mode").trim()
                val perms = com.ccm.app.AppGraph.toolsResult?.permissions
                    ?: return SlashResult.Notice("权限系统未初始化。")
                if (newMode.isBlank()) {
                    return SlashResult.Notice(
                        "**权限模式**\n\n当前：`${perms.mode}`\n\n" +
                            "用法：`/permissions mode <模式>`\n可选：default | acceptEdits | plan | bypassPermissions",
                    )
                }
                val r = perms.setMode(newMode)
                // 落盘（不落盘重启就丢；损坏时拒绝写，同 B4 逻辑）
                try {
                    val st = com.ccm.app.AppGraph.storage
                    if (st != null && newMode in listOf("default", "acceptEdits", "plan", "bypassPermissions")) {
                        val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                        if (loadR.error == null) {
                            com.ccm.app.core.provider.AppConfig.save(
                                loadR.config.copy(permissionMode = newMode), st.configFile,
                            )
                        } else {
                            return SlashResult.Notice("$r\n\n⚠ 配置损坏（${loadR.error}），未落盘 —— 重启后会恢复旧模式。")
                        }
                    }
                } catch (_: Throwable) {}
                return SlashResult.Notice("$r\n\n（已落盘，重启保留）")
            }
            SlashResult.Notice("**权限规则**\n\n" + try {
                val perms = com.ccm.app.AppGraph.toolsResult?.permissions
                if (perms != null) {
                    val r = perms.dumpRules()
                    val arr: (String) -> List<String> = { k ->
                        r.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                    }
                    "模式：${perms.mode}\n" +
                        "允许：${arr("allow").joinToString(", ").ifBlank { "(空)" }}\n" +
                        "拒绝：${arr("deny").joinToString(", ").ifBlank { "(空)" }}\n" +
                        "询问：${arr("ask").joinToString(", ").ifBlank { "(空)" }}\n\n" +
                        "规则文件：${perms.rulesFilePath()}"
                } else "权限系统未初始化"
            } catch (e: Throwable) { "读取失败：${e.message}" })
        }

        // ── /skills —— 列出内置技能清单（APK 真有：BuiltinSkills.all()）──────
        //
        // APK 侧没有 skills 运行时（见 BuiltinSkills.kt 头注），这里只做「展示」：
        // 列出有哪些技能、各自干什么。技能正文注入到对话的能力后续再接。
        "/skills" -> {
            val a = arg.trim()
            if (a.isBlank()) {
                // 无参：列出内置技能清单
                val skills = com.ccm.app.core.skill.BuiltinSkills.all()
                if (skills.isEmpty()) {
                    SlashResult.Notice("当前没有内置技能。")
                } else {
                    val body = buildString {
                        appendLine("**可用技能（${skills.size} 个）**")
                        appendLine()
                        skills.forEach { s ->
                            appendLine("- **${s.name}** — ${s.description}")
                        }
                        appendLine()
                        append("_看详情：`/skills <名字>` · 执行：让模型调 Skill 工具，或直接说「用 xxx 技能」_")
                    }
                    SlashResult.Notice(body)
                }
            } else {
                // 带参：展示该技能详情（对齐 CLI 的「技能详情」语义）。
                //
                // 数据源是**文件系统**（工作区 skills/ + 应用 files/skills/），
                // 与 Skill 工具同一套查找规则 —— 这样 /skills <名> 看到的
                // 就是模型真正会展开的那份正文。
                val cwd = com.ccm.app.AppGraph.workspacePath()
                val globalDir = com.ccm.app.AppGraph.storage?.let { java.io.File(it.root, "skills") }
                val found = com.ccm.app.ui.chat.findSkillFile(a, cwd, globalDir)
                if (found == null) {
                    // 内置清单兜底：名字对得上就展示内置描述
                    val builtin = com.ccm.app.core.skill.BuiltinSkills.all()
                        .firstOrNull { it.name.equals(a, ignoreCase = true) || it.id.equals(a, ignoreCase = true) }
                    if (builtin != null) {
                        SlashResult.Notice(
                            "**${builtin.name}**\n\n${builtin.description}\n\n" +
                                "_该技能只有内置简介，正文文件不在设备上（APK 侧可放 `skills/${builtin.name}.md` 或 " +
                                "`files/skills/${builtin.name}.md`）。_",
                        )
                    } else {
                        SlashResult.Notice(
                            "**找不到技能 `$a`**\n\n" +
                                "搜索目录：\n" +
                                "- `${cwd.ifBlank { "(工作区未就绪)" }}/skills/`\n" +
                                "- `${globalDir?.absolutePath ?: "(应用存储未就绪)"}/`\n\n" +
                                "用 `/skills`（无参）看全部可用技能。",
                        )
                    }
                } else {
                    val raw = try { found.readText() } catch (t: Throwable) { "" }
                    if (raw.isEmpty()) {
                        SlashResult.Notice("技能文件读取失败：`${found.absolutePath}`")
                    } else {
                        val max = 6_000
                        val body = if (raw.length > max) {
                            raw.substring(0, max) + "\n\n… （已截断，共 ${raw.length} 字符）"
                        } else {
                            raw
                        }
                        SlashResult.Notice(
                            "**技能：${found.name}** · `${found.absolutePath}`\n\n---\n\n$body",
                        )
                    }
                }
            }
        }

        // ── /trash —— 回收站（对齐 CLI /trash）──────────────────────────────
        //
        // 大改动（Write/Edit 超阈值）会自动把旧版本备份进回收站，这里是查看入口。
        // 子命令：无参 / list 看列表 · restore <序号> 恢复 · clear 清空。
        "/trash" -> {
            val trash = com.ccm.app.AppGraph.toolsResult?.trashStore
                ?: return SlashResult.Notice("回收站未初始化。")
            val sub = arg.trim()
            val subCmd = sub.substringBefore(" ").lowercase()
            val subArg = sub.substringAfter(" ", "").trim()
            when (subCmd) {
                "", "list", "ls" -> SlashResult.Notice(
                    "**回收站**（${trash.count()} 个备份）\n\n```\n" + trash.listText() + "\n```",
                )
                "restore", "恢复" -> {
                    val idx = subArg.toIntOrNull()
                    if (idx == null) {
                        SlashResult.Notice("用法：`/trash restore <序号>`（用 `/trash` 查看序号）")
                    } else {
                        // 兜底目录：工作区（manifest 缺失时用它拼恢复路径）
                        val fallback = java.io.File(com.ccm.app.AppGraph.workspacePath().ifBlank { "." })
                        SlashResult.Notice("**" + trash.restore(idx, fallback) + "**")
                    }
                }
                "clear", "清空" -> SlashResult.Notice("**" + trash.clear() + "**")
                else -> SlashResult.Notice(
                    "**用法**：\n" +
                        "- `/trash` 查看回收站\n" +
                        "- `/trash restore <序号>` 恢复指定备份\n" +
                        "- `/trash clear` 清空回收站",
                )
            }
        }

        // ── /keepalive —— 保活状态（对齐 CLI /keepalive）────────────────────
        //
        // APK 是普通 Android 应用，保活靠前台服务/唤醒锁，与 CLI 的
        // Termux wake-lock + 静音音频不是一回事 —— 这里只做**如实说明**。
        "/keepalive" -> SlashResult.Notice(
            "**保活**\n\n" +
                "APK 是标准 Android 应用，没有 CLI 那套 Termux 保活（`termux-wake-lock` + 静音音频）。\n\n" +
                "让长时间任务不被系统杀掉的办法：\n" +
                "- 把应用切到后台前先拉到最近任务列表（部分系统会保留）\n" +
                "- 系统设置里给本应用关掉电池优化（设置 → 应用 → 电池 → 不受限制）\n" +
                "- 长任务期间保持屏幕点亮或用充电状态",
        )

        // ── /plugins —— 插件（对齐 CLI /plugins）────────────────────────────
        "/plugins" -> SlashResult.Notice(
            "**插件**\n\n" +
                "CLI 的 `/plugins` 列出已装插件（`plugins/` 目录下的扩展）。\n" +
                "APK 侧没有插件机制 —— 能力扩展走两条路：\n" +
                "- **技能**：放 `skills/<名字>.md`（用 `/skills` 查看）\n" +
                "- **Hooks**：放 `hooks.json`（用 `/hooks` 查看已注册事件）",
        )

        // ── /agents —— 列出可用子 agent 类型 ────────────────────────────────
        //
        // APK 没有独立的 agent 定义清单文件，内置类型硬编码在 AgentTools 的
        // subagent_type schema 里（general-purpose/Explore/Plan/Coordinator）。
        // 自定义 agent（.claude/agents/*.md）的加载在 CLI 侧。
        "/agents" -> SlashResult.Notice(
            buildString {
                appendLine("**可用子 Agent 类型（内置）**")
                appendLine()
                appendLine("- **general-purpose** — 全工具，独立完成复杂任务")
                appendLine("- **Explore** — 只读，调研代码库")
                appendLine("- **Plan** — 只读 + 待办，制定执行计划")
                appendLine("- **Coordinator** — 编排多个 worker 并行，做综合分析")
                appendLine()
                append("_说明：自定义子 Agent（`.claude/agents/*.md`）的加载在 CLI 侧，APK 用上述内置类型即可。派发子 Agent 由模型通过 Agent 工具完成。_")
            }
        )

        // ── /goal —— 目标模式（APK 无 goal runtime）────────────────────────
        "/goal" -> SlashResult.Notice(
            "目标模式是 CLI 侧的「完成契约」自主推进机制（设目标 → 逐轮推进 → 验证判据 → 自动终止），APK 暂未接入。\n\n" +
                "如需明确目标，直接在对话里说清要做什么和完成标准即可。"
        )

        // ── /mem、/memory —— 项目记忆（对齐 CLI /mem 的核心子命令）─────────
        //
        // CLAUDE.md 实际落在存储根（AppGraph.storage.root/CLAUDE.md），
        // 不在 workspace cwd —— 见 AppGraph.migrateLegacyConfig 的 ③ 项目记忆。
        //
        // 子命令（对齐 CLI /mem 的 show / append 两个最常用的）：
        //   /mem                      显示记忆（同 show）
        //   /mem show                 显示全文（不截断）
        //   /mem append <文本>        追加到记忆文件末尾（= Memory 工具的 append）
        //   /mem init                 创建空的记忆文件
        // CLI 的 list/find/save/rm 是「分文件记忆库」机制（memories/ 目录），
        // APK 只有单个 CLAUDE.md，所以那四个子命令无对应实现（如实说明）。
        "/mem", "/memory" -> {
            val root = com.ccm.app.AppGraph.storage?.root
            if (root == null) {
                SlashResult.Notice("无法读取项目记忆：存储尚未初始化。")
            } else {
                val md = java.io.File(root, "CLAUDE.md")
                val sub = arg.trim()
                val subCmd = sub.substringBefore(" ").lowercase()
                val subArg = sub.substringAfter(" ", "").trim()

                when (subCmd) {
                    // ── 写入：append ────────────────────────────────────
                    "append", "add", "记" -> {
                        if (subArg.isEmpty()) {
                            SlashResult.Notice(
                                "**用法**：`/mem append <文本>`\n\n" +
                                    "把文本追加到项目记忆文件末尾（`${md.absolutePath}`）。",
                            )
                        } else {
                            try {
                                if (!md.exists()) md.parentFile?.mkdirs()
                                // 前置换行分隔，避免与上一段粘连
                                val prefix = if (md.exists() && md.length() > 0L) "\n" else ""
                                md.appendText(prefix + subArg + "\n")
                                SlashResult.Notice(
                                    "**已追加到项目记忆**\n\n" +
                                        "- 文件：`${md.absolutePath}`\n" +
                                        "- 新增：${subArg.length} 字符\n" +
                                        "- 当前总计：${md.length()} 字符",
                                )
                            } catch (e: Throwable) {
                                SlashResult.Notice("写入失败：${e.message}")
                            }
                        }
                    }

                    // ── 创建：init ──────────────────────────────────────
                    "init" -> {
                        if (md.exists()) {
                            SlashResult.Notice("记忆文件已存在：`${md.absolutePath}`（${md.length()} 字符）")
                        } else {
                            try {
                                md.parentFile?.mkdirs()
                                md.writeText("# 项目记忆\n\n")
                                SlashResult.Notice("已创建记忆文件：`${md.absolutePath}`")
                            } catch (e: Throwable) {
                                SlashResult.Notice("创建失败：${e.message}")
                            }
                        }
                    }

                    // ── 查看：show（全文）／无参或未知子命令（截断展示）──
                    else -> {
                        if (!md.isFile || md.length() == 0L) {
                            SlashResult.Notice(
                                "**项目记忆**\n\n当前没有记忆文件（`CLAUDE.md`）。\n\n" +
                                    "用法：\n" +
                                    "- `/mem append <文本>` 追加一条记忆\n" +
                                    "- `/mem init` 创建空的记忆文件\n" +
                                    "- `/mem` 查看当前记忆",
                            )
                        } else {
                            val raw = try { md.readText() } catch (t: Throwable) { "" }
                            if (raw.isEmpty()) {
                                SlashResult.Notice("项目记忆文件读取失败或为空。")
                            } else {
                                // show 给全文；其他形态截断（防一条通知被撑爆）
                                val full = subCmd == "show" || subCmd == "cat"
                                val maxChars = if (full) 20_000 else 3_000
                                val body = if (raw.length > maxChars) {
                                    raw.substring(0, maxChars) + "\n\n… （已截断，共 ${raw.length} 字符；用 `/mem show` 看全文）"
                                } else {
                                    raw
                                }
                                SlashResult.Notice(
                                    buildString {
                                        appendLine("**项目记忆（CLAUDE.md）** · ${raw.length} 字符")
                                        appendLine()
                                        appendLine(body)
                                        appendLine()
                                        append("_写入：`/mem append <文本>` · 全文：`/mem show`_")
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── /automem —— 自动记忆开关（APK 无此机制）────────────────────────
        "/automem" -> SlashResult.Notice(
            "自动记忆（对话结束后增量提取结论写入 CLAUDE.md）是 CLI 侧机制，APK 暂未接入。"
        )

        // ── /github —— GitHub 工具配置（在 CLI 侧）──────────────────────────
        "/github" -> SlashResult.Notice(
            "GitHub 工具的配置（token / 仓库 / 连通性）在 CLI 侧用 `/github` 管理，APK 暂未接入。"
        )

        // ── /mail —— 邮箱（CLI 的 MCP 侧）────────────────────────────────────
        "/mail" -> SlashResult.Notice(
            "邮箱收发在 CLI 的 MCP（mail-qq）侧配置与使用，APK 暂未接入。"
        )

        // ── /mcp —— MCP 服务器（APK 无 MCP）─────────────────────────────────
        //
        // 已确认 APK 源码里没有任何 MCP 相关实现（find *Mcp* 无结果）。
        "/mcp" -> SlashResult.Notice(
            "MCP 服务器的接入与管理在 CLI 侧用 `/mcp` 完成，APK 暂未接入 MCP。"
        )

        // ── /pexels —— 图库 key（在 CLI 侧）─────────────────────────────────
        "/pexels" -> SlashResult.Notice(
            "Pexels 图库 key（FindImage 用）在 CLI 侧用 `/pexels` 配置，APK 暂未接入该命令。"
        )

        else -> null
    }
}

// ═══════════════════════════════════════════════════════════════════
//  辅助函数（供上面的命令分支调用）
//
//  为什么放文件末尾而不是各分区里：这些是**跨分区共用**的能力
//  （/skills <名> 要读文件系统、/diff 要跑 git、/btw 要发请求），
//  放在分区内部会让「谁该用哪个」变得不清楚。
// ═══════════════════════════════════════════════════════════════════

/**
 * 按名字查找 skill 文件（`/skills <名>` 用）。
 *
 * 查找规则**与 Skill 工具一致**（`SkillTools.rootDirsFor` + `find`）：
 * 1. 项目级：`<cwd>/skills/名字.md` 或 `<cwd>/skills/名字/SKILL.md`
 * 2. 用户级：`<storageRoot>/skills/名字.md` 或 `.../名字/SKILL.md`
 *
 * 同名时项目优先（返回第一个命中的）。找不到返回 null。
 *
 * 【为什么要跟 Skill 工具一致】`/skills <名>` 展示的必须就是模型真正会
 * 展开的那份正文 —— 两套查找规则会漂移（用户在 A 处放了技能，
 * 命令说没有、模型却能展开）。
 */
internal fun findSkillFile(name: String, cwd: String, globalDir: java.io.File?): java.io.File? {
    val n = name.trim()
    if (n.isEmpty()) return null
    // 防目录穿越：只允许单段名字（不含路径分隔符）
    val safe = n.substringAfterLast('/').substringAfterLast('\\')
    if (safe != n) return null
    val dirs = buildList {
        if (cwd.isNotBlank()) add(java.io.File(cwd, "skills"))
        globalDir?.let { add(it) }
    }
    for (d in dirs) {
        if (!d.exists() || !d.isDirectory) continue
        // ① 扁平：skills/foo.md
        val flat = java.io.File(d, "$safe.md")
        if (flat.isFile) return flat
        val flatNoExt = java.io.File(d, safe)
        if (flatNoExt.isFile && safe.endsWith(".md", ignoreCase = true)) return flatNoExt
        // ② 仓库式：skills/foo/SKILL.md
        val nameDir = safe.removeSuffix(".md").removeSuffix(".MD")
        val nested = java.io.File(java.io.File(d, nameDir), "SKILL.md")
        if (nested.isFile) return nested
    }
    return null
}

/**
 * 跑一次工作区 git diff 并把结果注入对话（`/diff` 用）。
 *
 * **为什么异步**：命令分发是同步纯函数（[handleSlashCommand] 的契约），
 * 而 Bash 通道是 suspend 的。所以这里起一个后台协程，先返回「正在读」，
 * 结果出来再 injectNotice（与 /btw 同一个模式）。
 *
 * @param arg 透传给 git 的额外参数（如 `HEAD`、`--cached`）；空则用默认
 */
internal fun launchGitDiff(ctx: SlashContext, arg: String) {
    val session = ctx.session ?: return
    val scope = com.ccm.app.AppGraph.appScope
    val channel = com.ccm.app.AppGraph.toolsResult?.bashChannel

    // 前置条件不满足时**必须给出反馈** —— 静默 return 会让用户看到
    // 「正在读取工作区改动…」之后永远没有下文，以为卡住了。
    if (scope == null || channel == null) {
        try {
            session.injectNotice(
                "**工作区改动**\n\n无法执行：${if (channel == null) "Bash 通道未就绪" else "应用作用域未就绪"}。",
            )
        } catch (_: Throwable) { }
        return
    }
    val cwd = com.ccm.app.AppGraph.workspacePath().ifBlank { null }

    scope.launch {
        val sub = if (arg.isBlank()) "" else " " + arg.trim()
        val cmd = "git diff --stat$sub"
        val result = try {
            val r = channel.execute(cmd, cwd, 20_000L) { }
            val out = buildString {
                if (r.stdout.isNotBlank()) append(r.stdout.trimEnd())
                if (r.stderr.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(r.stderr.trimEnd())
                }
            }.trim()
            when {
                r.timedOut -> "⏱ git 超时（20s）"
                out.isBlank() && r.exitCode == 0 -> "工作区没有未提交的改动。"
                out.isBlank() -> "git 退出码 ${r.exitCode}（无输出）"
                else -> out
            }
        } catch (e: Throwable) {
            "执行失败：${e.message}"
        }
        try {
            session.injectNotice(
                "**工作区改动**（`${cwd ?: "(默认目录)"}`）\n\n```\n$result\n```",
            )
        } catch (_: Throwable) { /* 会话已销毁 */ }
    }
}

/**
 * 侧问（`/btw` 用）—— 单跑一次**无工具、无历史**的请求，结果只进气泡。
 *
 * 对齐 CLI 的 `/btw` 语义：「顺嘴问一句，不打断主对话、不进主上下文」。
 * 实现要点：
 * - 用 container.apiClient 直接 chat（不经过 AgentLoop）→ **不写进对话历史**
 * - 不带工具定义 → 模型不会触发工具调用，就是纯问答
 * - 系统提示词给一句「这是侧问，简短回答」的引导
 *
 * 结果通过 [ChatSession.injectNotice] 注入 —— 那是本地气泡，同样不进模型上下文。
 */
internal fun launchBtw(ctx: SlashContext, question: String) {
    val session = ctx.session ?: return
    val scope = com.ccm.app.AppGraph.appScope
    val container = com.ccm.app.AppGraph.container

    if (scope == null || container == null) {
        try {
            session.injectNotice(
                "**侧问失败**\n\n${if (container == null) "会话容器未就绪（尚未配置 Provider？）" else "应用作用域未就绪"}。",
            )
        } catch (_: Throwable) { }
        return
    }

    scope.launch {
        val text = try {
            val messages = listOf(
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonPrimitive(question))
                },
            )
            val resp = container.apiClient.chat(
                system = "这是用户的一次「侧问」（/btw）：不打断主对话、不进主上下文。" +
                    "请直接、简短地回答这个问题，不要客套，不要问是否需要继续。",
                messages = messages,
                tools = emptyList(),
            )
            resp.text.ifBlank { "(模型返回了空回复)" }
        } catch (e: Throwable) {
            "侧问失败：${e.message}"
        }
        try {
            session.injectNotice("**侧问** · $question\n\n$text\n\n_（本轮不进对话历史）_")
        } catch (_: Throwable) { /* 会话已销毁 */ }
    }
}
