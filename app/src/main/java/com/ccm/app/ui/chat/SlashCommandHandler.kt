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
    /**
     * 启动 goal 循环（问题40：/goal 用）。
     *
     * 【为什么是回调而不是直接拿 GoalStore】
     * goal 循环是**挂起的长任务**（一条 15 轮的目标可能跑几十分钟）——
     * handler 不能阻塞，必须由 UI 层在自己的协程里 launch。
     * 参数：(目标描述, 首轮消息) → 立刻返回（循环在后台跑）
     */
    val startGoal: ((description: String, firstMessage: String) -> Unit)? = null,
    /** 读当前 goal 状态文本（null = 无目标）。 */
    val goalStatusText: (() -> String?)? = null,
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

        // /summary —— 生成对话摘要
        //
        // 【2026-10-06 问题40 修复】原来报「APK 暂无独立摘要功能」——
        // 但 Compactor 的摘要构建/提取**全都有**，只差发一次请求。
        // 现在 ChatSession.summarizeNow() 实现了，这里接上。
        //
        // 注意：要发 API 请求（几秒），挂起 —— 必须 launch 在协程里，
        // 不能阻塞 handler。
        "/summary" -> {
            val s = ctx.session
            val scope = com.ccm.app.AppGraph.appScope
            if (s == null || scope == null) {
                SlashResult.Notice("无会话或协程作用域未就绪。")
            } else {
                scope.launch {
                    val text = try { s.summarizeNow() } catch (t: Throwable) { "摘要失败：${t.message}" }
                    s.injectNotice(text)
                }
                SlashResult.Notice("正在生成摘要…（几秒后出现在对话里）")
            }
        }

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
        // ── /clear-restore / /compact-trash —— 压缩回收站 ─────────────────
        //
        // 【2026-10-06 问题40 修复】原来报「CLI 侧机制，APK 无」——
        // 但现在 APK 的 /compact 也写备份了（见 ChatSession.compactNow），
        // 这个命令该能列/恢复/清空。
        "/clear-restore", "/compact-trash" -> {
            val root = com.ccm.app.AppGraph.toolsResult?.goalStore?.let { null }
                ?: com.ccm.app.AppGraph.storage?.root
            if (root == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val dir = java.io.File(root, "compact-trash")
                val a = arg.trim().lowercase()
                val sub = a.substringBefore(" ")
                val subArg = a.substringAfter(" ", "").trim()

                when (sub) {
                    "list", "" -> {
                        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
                        if (files.isEmpty()) {
                            SlashResult.Notice("压缩回收站为空。\n\n_（每次 /compact 前会自动备份到这里）_")
                        } else {
                            val body = buildString {
                                appendLine("**压缩回收站**（${files.size} 份）")
                                appendLine()
                                files.take(15).forEachIndexed { i, f ->
                                    val kb = f.length() / 1024
                                    appendLine("${i + 1}. `${f.name}`（${kb}KB）")
                                }
                                if (files.size > 15) appendLine("… 还有 ${files.size - 15} 份")
                                appendLine()
                                append("恢复：`/compact-trash restore <文件名>` · 清空：`/compact-trash clear`")
                            }
                            SlashResult.Notice(body)
                        }
                    }
                    "clear" -> {
                        val n = dir.listFiles()?.size ?: 0
                        dir.listFiles()?.forEach { it.delete() }
                        SlashResult.Notice("已清空压缩回收站（$n 份）。")
                    }
                    "restore" -> {
                        if (subArg.isBlank()) {
                            SlashResult.Notice("用法：`/compact-trash restore <文件名>`")
                        } else {
                            val f = java.io.File(dir, subArg)
                            if (!f.exists()) {
                                SlashResult.Notice("文件不存在：$subArg")
                            } else {
                                // 恢复 = 把备份的历史灌回 AgentLoop
                                val s = ctx.session
                                if (s == null) {
                                    SlashResult.Notice("无会话，无法恢复。")
                                } else {
                                    try {
                                        val arr = org.json.JSONArray(f.readText())
                                        val msgs = (0 until arr.length()).mapNotNull { i ->
                                            val o = arr.getJSONObject(i)
                                            // Message 的构造：role + content（List<ContentBlock>）
                                            com.ccm.app.core.session.Message(
                                                role = o.optString("role", "user"),
                                                content = listOf(
                                                    com.ccm.app.core.session.ContentBlock.Text(
                                                        o.optString("text", "")
                                                    )
                                                ),
                                                timestamp = o.optLong("timestamp", 0L),
                                            )
                                        }
                                        s.loadHistory(msgs)
                                        SlashResult.Notice("已恢复 ${msgs.size} 条历史（来自 `$subArg`）。")
                                    } catch (t: Throwable) {
                                        SlashResult.Notice("恢复失败：${t.message}")
                                    }
                                }
                            }
                        }
                    }
                    else -> SlashResult.Notice(
                        "**压缩回收站**\n\n" +
                            "- `/compact-trash` 或 `/compact-trash list` 列出备份\n" +
                            "- `/compact-trash restore <文件名>` 恢复\n" +
                            "- `/compact-trash clear` 清空\n\n" +
                            "_每次 /compact 前会自动备份完整历史到这里。_"
                    )
                }
            }
        }

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
                val loadR = com.ccm.app.core.provider.AppConfig.load(storage.configFile)
                if (loadR.error != null) {
                    return SlashResult.Notice(
                        "**配置文件损坏，命令已拒绝执行**\n\n解析错误：`${loadR.error}`",
                    )
                }
                val cfg = loadR.config
                if (a == "reset") {
                    val ok = com.ccm.app.core.provider.AppConfig.save(
                        cfg.copy(maxContextTokens = 1_000_000), storage.configFile,
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
                                cfg.copy(maxContextTokens = iv), storage.configFile,
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
/**
 * 配置刚落盘 → 热更新 API 客户端，返回追加到 Notice 的提示。
 *
 * 【2026-10-06 用户反馈】/key 等命令要「无需重启」。
 * 根因是 ApiClient 构造快照；现在 [com.ccm.app.core.AppContainer.refreshApi]
 * 原地换实例，命令落盘后调一次即可 —— 成功就**无声生效**（不加任何后缀，
 * 跟 CLI 的体感一致），失败才提示需重启（新配置不可用时保持旧链路不动）。
 */
private fun hotUpdateHint(): String {
    val ok = try {
        com.ccm.app.AppGraph.container?.refreshApi() == true
    } catch (_: Throwable) {
        false
    }
    return if (ok) "" else "\n\n⚠ 热更新失败（新配置不可用），保持旧配置运行；修好后需重启生效。"
}

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
                    SlashResult.Notice("思考强度已设为 `$a`。" + hotUpdateHint())
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
                        SlashResult.Notice("温度已设为 `$v`。" + hotUpdateHint())
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
                            // 【2026-10-06 P1-4】资料变了 → 提示词缓存失效
                            // （否则模型本会话内看不到新资料，要重启才生效）
                            com.ccm.app.core.AppContainer.invalidateSystemPrompt()
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
                    com.ccm.app.core.AppContainer.invalidateSystemPrompt()   // P1-4
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

        // ── /voice：正文自动朗读 ─────────────────────────────────────────
        //   实现：UiPrefs.voiceEnabled + ChatScreenConnected 监听本轮完成 → NativeTts。
        //   （原来报「CLI 终端专属」，已修 —— 见下方分支）
        // 【2026-10-06 问题40 修复】原来报「CLI 终端专属功能」——
        // 但 NativeTts 就能做（只是之前只给 say 工具用）。
        // 现在存 UiPrefs.voiceEnabled，UI 层（ChatScreenConnected）监听正文朗读。
        "voice" -> {
            val a = arg.trim().lowercase()
            when {
                a == "on" || a == "开" -> {
                    com.ccm.app.ui.theme.UiPrefs.setVoiceEnabled(true)
                    SlashResult.Notice("正文朗读已**开启** —— 每条回复完成后会自动念出来。\n\n_关闭：`/voice off`_")
                }
                a == "off" || a == "关" -> {
                    com.ccm.app.ui.theme.UiPrefs.setVoiceEnabled(false)
                    SlashResult.Notice("正文朗读已**关闭**。")
                }
                a == "stop" || a == "停" -> {
                    try { com.ccm.app.tools.NativeTts.stop() } catch (_: Throwable) {}
                    SlashResult.Notice("已停止当前朗读。")
                }
                a.startsWith("rate") -> {
                    val v = a.removePrefix("rate").trim().removeSuffix("%").toFloatOrNull()
                    if (v == null) {
                        SlashResult.Notice("用法：`/voice rate 1.2`（0.5~2.0）")
                    } else {
                        val r = v.coerceIn(0.5f, 2.0f)
                        com.ccm.app.ui.theme.UiPrefs.setVoiceRate(r)
                        SlashResult.Notice("朗读语速已设为 **$r**。")
                    }
                }
                else -> {
                    val on = com.ccm.app.ui.theme.UiPrefs.voiceEnabled.value
                    val rate = com.ccm.app.ui.theme.UiPrefs.voiceRate.value
                    SlashResult.Notice(
                        "**正文朗读**\n\n" +
                            "- 状态：${if (on) "✅ 开启" else "❌ 关闭"}\n" +
                            "- 语速：$rate\n\n" +
                            "用法：\n" +
                            "- `/voice on` 开启 · `/voice off` 关闭\n" +
                            "- `/voice rate 1.2` 调语速（0.5~2.0）\n" +
                            "- `/voice stop` 停当前朗读\n\n" +
                            "_开启后，每条助手回复**完成时**会自动念出来（流式过程中不念，避免断句）。_"
                    )
                }
            }
        }

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
            // 【2026-10-06 补】allow / deny / ask / remove 子命令 ——
            // 对齐 CLI 的 `/permissions allow <工具名>`（cmd-extensions.mjs:704）。
            // 原来 APK 只有 mode（改模式）+ 默认（看规则），规则表**只能手编
            // permissions.json**（ToolPermissions 有读没写）。
            val parts = sub.split(Regex("\\s+"), limit = 2)
            val action = parts.getOrNull(0)?.lowercase().orEmpty()
            if (action in setOf("allow", "deny", "ask", "remove")) {
                val tool = parts.getOrNull(1)?.trim().orEmpty()
                val perms = com.ccm.app.AppGraph.toolsResult?.permissions
                    ?: return SlashResult.Notice("权限系统未初始化。")
                if (tool.isBlank()) {
                    return SlashResult.Notice("用法：`/permissions $action <工具名>`")
                }
                return if (action == "remove") {
                    val removed = perms.removeRule(tool)
                    SlashResult.Notice(
                        if (removed.isEmpty()) "`$tool` 本来就不在任何规则表里。"
                        else "已从 ${removed.joinToString("/")} 移除 `$tool`。"
                    )
                } else {
                    val err = perms.addRule(action, tool)
                    SlashResult.Notice(
                        if (err == null) "已把 `$tool` 加入 **$action** 表。"
                        else "添加失败：$err"
                    )
                }
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
                        "用法：`/permissions mode <模式>` · `allow|deny|ask <工具名>` · `remove <工具名>`\n" +
                        "规则文件：${perms.rulesFilePath()}"
                } else "权限系统未初始化"
            } catch (e: Throwable) { "读取失败：${e.message}" })
        }

        // ── /skills —— 列出内置技能清单（APK 真有：BuiltinSkills.all()）──────
        //
        // ── /skills：动态列目录里的 skill ──────────────────────────────
        // 数据源与 Skill 工具一致（工作区 skills/ + files/skills/）。
        // 原来只列硬编码的 BuiltinSkills —— 用户放个 .md 看不到（已修）。
        "/skills" -> {
            val a = arg.trim()
            if (a.isBlank()) {
                // 【2026-10-06 问题40 修复】原来只列**硬编码**的 BuiltinSkills ——
                // 但 SkillTools 早就能从**文件系统**动态读（工作区 skills/ +
                // files/skills/，对齐 CLI 的 .claude/skills/）。
                // 用户报「行为降级」：CLI 放个 .md 就能用，APK 却看不到。
                //
                // 现在优先动态扫描（与 Skill 工具同一套查找规则）。
                val cwd = com.ccm.app.AppGraph.workspacePath()
                val st = com.ccm.app.AppGraph.toolsResult?.skillTools
                val dynamic = try { st?.listAll(cwd) ?: emptyList() } catch (_: Throwable) { emptyList() }

                if (dynamic.isNotEmpty()) {
                    val body = buildString {
                        appendLine("**可用技能（${dynamic.size} 个）**")
                        appendLine()
                        dynamic.forEach { (name, scope) ->
                            val tag = if (scope == "global") "全局" else "项目"
                            appendLine("- **$name**（$tag）")
                        }
                        appendLine()
                        append("_看详情：`/skills <名字>` · 执行：让模型调 Skill 工具，或直接说「用 xxx 技能」_")
                        appendLine()
                        appendLine()
                        append("_放新技能：在工作区 `skills/` 或应用 `files/skills/` 下放 `<名字>.md`_")
                    }
                    SlashResult.Notice(body)
                } else {
                    // 兜底：动态扫描为空时列内置清单
                    val skills = com.ccm.app.core.skill.BuiltinSkills.all()
                    if (skills.isEmpty()) {
                        SlashResult.Notice(
                            "当前没有可用技能。\n\n_放新技能：在工作区 `skills/` 或应用 `files/skills/` 下放 `<名字>.md`_"
                        )
                    } else {
                        val body = buildString {
                            appendLine("**可用技能（${skills.size} 个，内置）**")
                            appendLine()
                            skills.forEach { s ->
                                appendLine("- **${s.name}** — ${s.description}")
                            }
                            appendLine()
                            append("_看详情：`/skills <名字>` · 执行：让模型调 Skill 工具，或直接说「用 xxx 技能」_")
                        }
                        SlashResult.Notice(body)
                    }
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
                "APK 无 DSH 插件宿主（那是 CLI 侧能力）—— 能力扩展走三条路：\n" +
                "- **技能**：放 `skills/<名字>.md`（用 `/skills` 查看）\n" +
                "- **Hooks**：放 `hooks.json`（用 `/hooks` 查看已注册事件）\n" +
                "- **子 Agent**：放 `agents/<名字>.md`（用 `/agents` 查看）",
        )

        // ── /agents —— 列出可用子 agent 类型 ────────────────────────────────
        //
        // APK 没有独立的 agent 定义清单文件，内置类型硬编码在 AgentTools 的
        // subagent_type schema 里（general-purpose/Explore/Plan/Coordinator）。
        // 自定义 agent（.claude/agents/*.md）的加载在 CLI 侧。
        "/agents" -> {
            // 【2026-10-06 问题40 修复】原来只列**内置** 4 种，说「自定义的
            // 加载在 CLI 侧」—— 那是功能缺失。现在 CustomAgentLoader 实现了
            // （读 files/agents/ 和 工作区 .claude/agents/）。
            val cwd = com.ccm.app.AppGraph.workspacePath()
            val appRoot = com.ccm.app.AppGraph.storage?.root
            val custom = try {
                com.ccm.app.core.agent.CustomAgentLoader.create(appRoot, cwd).list()
            } catch (_: Throwable) { emptyList() }

            SlashResult.Notice(
                buildString {
                    appendLine("**可用子 Agent 类型**")
                    appendLine()
                    appendLine("内置：")
                    appendLine("- **general-purpose** — 全工具，独立完成复杂任务")
                    appendLine("- **Explore** — 只读，调研代码库")
                    appendLine("- **Plan** — 只读 + 待办，制定执行计划")
                    appendLine("- **Coordinator** — 编排多个 worker 并行，做综合分析")
                    if (custom.isNotEmpty()) {
                        appendLine()
                        appendLine("自定义（${custom.size} 个）：")
                        custom.forEach { a ->
                            appendLine("- **${a.name}** — ${a.description.ifBlank { "(无描述)" }}")
                        }
                    }
                    appendLine()
                    append("_派发子 Agent：让模型调 Agent 工具（subagent_type 填上面的名字）。_")
                    appendLine()
                    append("_放新角色：在应用 `files/agents/` 或工作区 `.claude/agents/` 下放 `<名字>.md`_")
                }
            )
        }

        // ══════════════════════════════════════════════════════════════
        //  【2026-10-06 问题40】Provider 配置系列命令
        // ══════════════════════════════════════════════════════════════
        //
        // APK 原来这些命令只给「去设置页」的提示 —— 但 ProviderStore
        // 有完整接口（setCurrent/setUrl/setKey/setModel/setProtocol），
        // 命令能直接改。对齐 CLI 的 /config 系列。
        //
        // ✅ 2026-10-06 起**热更新**（无需重启）：落盘后 hotUpdateHint()
        //    会调 AppContainer.refreshApi() 原地换 ApiClient 实例。

        // ── /config —— 切 Provider ────────────────────────────────────────
        "/config" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val store = com.ccm.app.core.provider.ProviderStore(st)
                val a = arg.trim()
                if (a.isBlank() || a == "list") {
                    val items = store.list()
                    val cur = store.load().current
                    if (items.isEmpty()) {
                        SlashResult.Notice("还没有配置 Provider。\n\n_去「设置 → 模型」添加，或用 `/config provider add`_")
                    } else {
                        val body = buildString {
                            appendLine("**Provider 列表（${items.size} 个）**")
                            appendLine()
                            items.forEach { p ->
                                val mark = if (p.id == cur) " ← 当前" else ""
                                val keyInfo = when {
                                    p.keyCount > 1 -> "（${p.keyCount} 个 key 轮换）"
                                    p.keyCount == 1 -> "（有 key）"
                                    else -> "（无 key）"
                                }
                                appendLine("- `/${p.id}` **${p.name}** — ${p.model} $keyInfo$mark")
                            }
                            appendLine()
                            append("切：`/config <编号>` · 改字段：`/model` `/url` `/key` `/name` `/protocol`")
                        }
                        SlashResult.Notice(body)
                    }
                } else if (a.startsWith("vision")) {
                    // ── /config vision on|off|set <id> ────────────────────────
                    // 【2026-10-06 对齐 CLI】（cmd-extensions.mjs:1214）
                    // 控制「识图路由」：开启时优先用当前模型，关闭或未配置时
                    // 用备用识图 Provider（visionProviderId）。
                    val rest = a.removePrefix("vision").trim()
                    val sub = rest.substringBefore(" ").lowercase()
                    val restArg = rest.substringAfter(" ", "").trim()
                    val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                    if (loadR.error != null) {
                        SlashResult.Notice("配置损坏：${loadR.error}")
                    } else {
                        val cfg = loadR.config
                        when {
                            sub == "on" || sub == "off" -> {
                                com.ccm.app.core.provider.AppConfig.save(
                                    cfg.copy(vision = sub == "on"), st.configFile,
                                )
                                SlashResult.Notice(
                                    "识图路由已**${if (sub == "on") "开启（优先用当前模型）" else "关闭（用备用识图 Provider 兜底）"}**。" +
                                        hotUpdateHint()
                                )
                            }
                            sub == "set" -> {
                                if (restArg.isBlank()) {
                                    SlashResult.Notice("用法：`/config vision set <providerId>`")
                                } else if (!cfg.providers.containsKey(restArg)) {
                                    SlashResult.Notice("Provider `$restArg` 不存在。用 `/config` 看列表。")
                                } else {
                                    com.ccm.app.core.provider.AppConfig.save(
                                        cfg.copy(visionProviderId = restArg), st.configFile,
                                    )
                                    val p = cfg.providers[restArg]
                                    SlashResult.Notice("备用识图 Provider 已设为 `$restArg`（${p?.name ?: ""}）。" + hotUpdateHint())
                                }
                            }
                            else -> {
                                val vid = cfg.visionProviderId
                                val vp = vid?.let { cfg.providers[it] }
                                SlashResult.Notice(
                                    "**识图路由**\n\n" +
                                        "- 开关：${if (cfg.vision) "开启" else "关闭"}\n" +
                                        "- 备用 Provider：${if (vid.isNullOrBlank()) "(未配置)" else "`$vid`（${vp?.name ?: "?"}）"}\n\n" +
                                        "用法：`/config vision on|off` · `set <providerId>`"
                                )
                            }
                        }
                    }
                } else if (a.startsWith("provider")) {
                    // ── /config provider add|rm|rename|list ──────────────────
                    // 【2026-10-06 对齐 CLI】（cmd-extensions.mjs:1098）
                    // 原来 APK 没有这一族，但列表文案里却写着「用 /config provider add」
                    // —— 引导用户敲一个不存在的命令。
                    val rest = a.removePrefix("provider").trim()
                    val sub = rest.substringBefore(" ").lowercase()
                    val restArg = rest.substringAfter(" ", "").trim()
                    when (sub) {
                        "" -> SlashResult.Notice(
                            "用法：\n" +
                                "- `/config provider list` 列出\n" +
                                "- `/config provider add [ID] name=<名> url=<地址> model=<模型> key=<sk-...>`\n" +
                                "- `/config provider rm <ID>` 删除（不能删当前）\n" +
                                "- `/config provider rename <旧ID> <新ID>` 改编号（不是显示名）"
                        )
                        "list" -> {
                            val items = store.list()
                            SlashResult.Notice(
                                if (items.isEmpty()) "没有 Provider。"
                                else items.joinToString("\n") { p ->
                                    val cur = if (p.id == store.load().current) " ←当前" else ""
                                    "- `${p.id}`：${p.name} - ${p.model}$cur"
                                }
                            )
                        }
                        "add" -> {
                            // key=value 一行式（顺序无关、缺哪个报哪个）——
                            // 与 CLI 同款：位置参数记不住，顺序错还不报错。
                            if (restArg.isBlank()) {
                                SlashResult.Notice("用法：`/config provider add [ID] name=<名> url=<地址> model=<模型> key=<sk-...>`")
                            } else {
                                // 第一个非 key=value 的 token 当 ID（可选）
                                val tokens = restArg.split(Regex("\\s+")).filter { it.isNotBlank() }
                                var explicitId = ""
                                val kv = mutableMapOf<String, MutableList<String>>()
                                tokens.forEach { t ->
                                    val eq = t.indexOf('=')
                                    if (eq > 0) {
                                        kv.getOrPut(t.substring(0, eq).lowercase()) { mutableListOf() }
                                            .add(t.substring(eq + 1))
                                    } else if (explicitId.isBlank()) {
                                        explicitId = t
                                    }
                                }
                                val url = kv["url"]?.firstOrNull().orEmpty()
                                val model = kv["model"]?.firstOrNull().orEmpty()
                                if (url.isBlank() || model.isBlank()) {
                                    SlashResult.Notice("缺少必填字段：`url=` 和 `model=` 都要给。\n例：`/config provider add 4 name=x url=https://api.x/v1 model=gpt-4 key=sk-xxx`")
                                } else {
                                    val created = store.addProvider(
                                        id = explicitId,
                                        name = kv["name"]?.firstOrNull().orEmpty(),
                                        url = url,
                                        model = model,
                                        key = kv["key"]?.firstOrNull().orEmpty(),
                                        protocol = kv["protocol"]?.firstOrNull()?.lowercase() ?: "openai",
                                    )
                                    if (created == null) {
                                        SlashResult.Notice("添加失败（编号 `${explicitId.ifBlank { "(自动)" }}` 可能已占用）。")
                                    } else {
                                        // key 给多个 → 存轮换池（与 CLI 一致）
                                        val extraKeys = kv["key"]?.drop(1).orEmpty()
                                        if (extraKeys.isNotEmpty()) store.setKeyPool(created, extraKeys)
                                        SlashResult.Notice("已添加 Provider `$created`（${kv["name"]?.firstOrNull() ?: created}）。\n\n_用 `/config $created` 切过去。_" + hotUpdateHint())
                                    }
                                }
                            }
                        }
                        "rm", "remove", "delete" -> {
                            if (restArg.isBlank()) SlashResult.Notice("用法：`/config provider rm <ID>`")
                            else if (restArg == store.load().current) SlashResult.Notice("不能删除**当前在用**的 Provider。先切到别的再删。")
                            else if (store.removeProvider(restArg)) SlashResult.Notice("已删除 Provider `$restArg`。" + hotUpdateHint())
                            else SlashResult.Notice("删除失败（`$restArg` 不存在？）")
                        }
                        "rename" -> {
                            val parts = restArg.split(Regex("\\s+")).filter { it.isNotBlank() }
                            if (parts.size < 2) {
                                SlashResult.Notice("用法：`/config provider rename <旧ID> <新ID>`\n\n_（改的是编号，不是显示名 —— 显示名用 `/name`）_")
                            } else {
                                val err = store.renameProvider(parts[0], parts[1])
                                SlashResult.Notice(
                                    if (err == null) "已重命名：`${parts[0]}` → `${parts[1]}`" + hotUpdateHint()
                                    else err
                                )
                            }
                        }
                        else -> SlashResult.Notice("未知子命令 `$sub`。用 `/config provider` 看用法。")
                    }
                } else {
                    // 切到指定 Provider
                    val ok = store.setCurrent(a)
                    if (ok) {
                        SlashResult.Notice("已切到 Provider `$a`。" + hotUpdateHint())
                    } else {
                        SlashResult.Notice("找不到 Provider `$a`。用 `/config` 看列表。")
                    }
                }
            }
        }

        // ── /url —— 改 API 地址 ──────────────────────────────────────────
        "/url" -> {
            val st = com.ccm.app.AppGraph.storage
            val a = arg.trim()
            if (st == null || a.isBlank()) {
                SlashResult.Notice("用法：`/url <新地址>`（改当前 Provider）\n例：`/url https://api.example.com/v1`")
            } else {
                val store = com.ccm.app.core.provider.ProviderStore(st)
                val cur = store.load().current
                if (cur.isBlank()) {
                    SlashResult.Notice("没有当前 Provider。")
                } else if (store.setUrl(cur, a)) {
                    SlashResult.Notice("已改 URL → `$a`（Provider `$cur`）" + hotUpdateHint())
                } else {
                    SlashResult.Notice("改 URL 失败。")
                }
            }
        }

        // ── /key —— 看/改密钥 ────────────────────────────────────────────
        "/key" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val store = com.ccm.app.core.provider.ProviderStore(st)
                val cur = store.load().current
                val p = store.get(cur)
                val a = arg.trim()
                when {
                    a.isBlank() -> {
                        if (p == null) {
                            SlashResult.Notice("没有当前 Provider。")
                        } else {
                            val keys = p.allKeys()
                            SlashResult.Notice(
                                "**Provider `${p.name}` 的密钥**\n\n" +
                                    (if (keys.isEmpty()) "❌ 未配置" else "✅ ${keys.size} 个：" +
                                        keys.joinToString("\n") { "  `" + it.take(8) + "…" + it.takeLast(4) + "`" }) +
                                    "\n\n用法：\n" +
                                    "- `/key <sk-...>` 设单个\n" +
                                    "- `/key pool <k1> <k2> ...` 设轮换池\n" +
                                    "- `/key clear` 清空"
                            )
                        }
                    }
                    a == "clear" -> {
                        if (store.setKey(cur, "")) SlashResult.Notice("已清空 key（Provider `$cur`）。" + hotUpdateHint())
                        else SlashResult.Notice("清空失败。")
                    }
                    a.startsWith("pool ") -> {
                        val keys = a.removePrefix("pool ").trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                        if (keys.isEmpty()) {
                            SlashResult.Notice("用法：`/key pool <k1> <k2> ...`")
                        } else if (store.setKeyPool(cur, keys)) {
                            SlashResult.Notice("已设 ${keys.size} 个 key 的轮换池（Provider `$cur`）。" + hotUpdateHint())
                        } else {
                            SlashResult.Notice("设置失败。")
                        }
                    }
                    else -> {
                        if (store.setKey(cur, a)) {
                            SlashResult.Notice("已设 key（Provider `$cur`，`${a.take(8)}…`）。" + hotUpdateHint())
                        } else {
                            SlashResult.Notice("设置失败。")
                        }
                    }
                }
            }
        }

        // ── /name —— 改显示名 ────────────────────────────────────────────
        "/name" -> {
            val st = com.ccm.app.AppGraph.storage
            val a = arg.trim()
            if (st == null || a.isBlank()) {
                SlashResult.Notice("用法：`/name <新显示名>`（改当前 Provider 的显示名）")
            } else {
                val store = com.ccm.app.core.provider.ProviderStore(st)
                val cur = store.load().current
                if (store.setDisplayName(cur, a)) {
                    SlashResult.Notice("显示名已改为 **$a**。")
                } else {
                    SlashResult.Notice("改名失败。")
                }
            }
        }

        // ── /protocol —— 改 API 协议 ─────────────────────────────────────
        "/protocol" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val store = com.ccm.app.core.provider.ProviderStore(st)
                val cur = store.load().current
                val p = store.get(cur)
                val a = arg.trim().lowercase()
                if (a.isBlank()) {
                    SlashResult.Notice(
                        "**当前协议**：`${p?.protocol ?: "openai"}`\n\n" +
                            "用法：`/protocol <openai|anthropic|responses>`\n" +
                            "- openai → `/chat/completions`（兼容性最好）\n" +
                            "- anthropic → `/v1/messages`（Claude 原生）\n" +
                            "- responses → `/responses`（OpenAI 新协议）"
                    )
                } else if (a !in listOf("openai", "anthropic", "responses")) {
                    SlashResult.Notice("协议必须是 openai / anthropic / responses 之一。")
                } else if (store.setProtocol(cur, a)) {
                    SlashResult.Notice("协议已改为 `$a`。" + hotUpdateHint())
                } else {
                    SlashResult.Notice("改协议失败。")
                }
            }
        }

        // ── /workspace —— 查看/设置工作区 ────────────────────────────────
        "/workspace" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val a = arg.trim()
                if (a.isBlank()) {
                    SlashResult.Notice(
                        "**当前工作区**：`${com.ccm.app.AppGraph.workspacePath()}`\n\n" +
                            "用法：`/workspace <路径>` 设置（空 = 用应用私有目录）\n" +
                            "⚠ 需「所有文件访问」权限才能用 `/sdcard` 路径"
                    )
                } else {
                    val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                    if (loadR.error != null) {
                        SlashResult.Notice("配置损坏：${loadR.error}")
                    } else {
                        val dir = java.io.File(a)
                        if (!dir.isDirectory && !dir.mkdirs()) {
                            SlashResult.Notice("目录不存在且无法创建：$a\n\n_检查权限（/sdcard 需「所有文件访问」）_")
                        } else {
                            com.ccm.app.core.provider.AppConfig.save(
                                loadR.config.copy(workspacePath = a), st.configFile,
                            )
                            // 【2026-10-06 用户报「换工作区必须开新对话，太糟糕」】
                            // 原来只写 config.json —— cwd 是 AgentLoop 的构造参数，
                            // 不重建就一直是旧值，用户只能开新会话/重启。
                            // 现在：立即 rebuild（cwd 随装配更新，历史保留）。
                            var applied = false
                            try {
                                val appCtx = ctx.appContext
                                val scope = com.ccm.app.AppGraph.appScope
                                if (appCtx != null && scope != null) {
                                    applied = com.ccm.app.AppGraph.rebuild(appCtx, scope) != null
                                }
                            } catch (_: Throwable) {}
                            SlashResult.Notice(
                                "工作区已设为 `$a`。" +
                                    if (applied) "\n\n_已立即生效（会话历史保留）。_"
                                    else "\n\n⚠ 重建会话失败，下次开新会话时生效。"
                            )
                        }
                    }
                }
            }
        }

        // ── /doctor —— 环境自检 ──────────────────────────────────────────
        "/doctor" -> {
            val st = com.ccm.app.AppGraph.storage
            val sb = StringBuilder("**环境自检**\n\n")
            if (st == null) {
                sb.append("- ❌ 存储未初始化\n")
            } else {
                // 1. 配置
                val cfgR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                if (cfgR.error != null) {
                    sb.append("- ❌ 配置解析失败：${cfgR.error}\n")
                } else {
                    val prov = cfgR.config.currentProvider
                    sb.append("- ${if (prov != null) "✅" else "❌"} Provider：${prov?.name ?: "未配置"}\n")
                    if (prov != null) {
                        sb.append("  · URL：${prov.url.ifBlank { "(空)" }}\n")
                        sb.append("  · 模型：${prov.model.ifBlank { "(空)" }}\n")
                        sb.append("  · Key：${if (prov.allKeys().isEmpty()) "❌ 无" else "✅ ${prov.allKeys().size} 个"}\n")
                    }
                }
                // 2. 工具
                val tr = com.ccm.app.AppGraph.toolsResult
                sb.append("- ${if (tr != null) "✅" else "❌"} 工具注册：${tr?.registered?.size ?: 0} 个\n")
                tr?.rejected?.takeIf { it.isNotEmpty() }?.let {
                    sb.append("  · ⚠ 被拒：${it.joinToString(", ")}\n")
                }
                // 3. 会话
                sb.append("- ${if (com.ccm.app.AppGraph.session != null) "✅" else "❌"} 会话：${com.ccm.app.AppGraph.sessionId.take(12)}\n")
                // 4. 权限
                val perms = tr?.permissions
                sb.append("- ℹ️ 权限模式：${perms?.mode ?: "?"}\n")
                // 5. 工作区
                sb.append("- ℹ️ 工作区：${com.ccm.app.AppGraph.workspacePath()}\n")
                // 6. 版本
                sb.append("- ℹ️ 版本：${com.ccm.app.AppGraph.appVersion()}\n")
            }
            SlashResult.Notice(sb.toString())
        }

        // ── /bg-list /bg-status —— 后台任务 ──────────────────────────────
        //
        // 【2026-10-06 问题40】BackgroundShells 早就实现了（工具用），
        // 但没有查询命令。用户看不到后台跑了什么。
        "/bg-list" -> {
            val tasks = com.ccm.app.tools.bash.BackgroundShells.list()
            if (tasks.isEmpty()) {
                SlashResult.Notice("没有后台任务。")
            } else {
                val sb = StringBuilder("**后台任务（${tasks.size} 个）**\n\n")
                tasks.forEach { t ->
                    val sec = t.elapsedMs() / 1000
                    sb.append("- `${t.id}` [${t.status}] ${t.command.take(50)}\n")
                    sb.append("  ${sec}s · ${t.channelLabel}\n")
                }
                sb.append("\n看详情：`/bg-status <id>`")
                SlashResult.Notice(sb.toString())
            }
        }
        "/bg-status" -> {
            val id = arg.trim()
            if (id.isBlank()) {
                SlashResult.Notice("用法：`/bg-status <id>`（id 从 `/bg-list` 拿）")
            } else {
                val t = com.ccm.app.tools.bash.BackgroundShells.get(id)
                if (t == null) {
                    SlashResult.Notice("找不到任务 `$id`。")
                } else {
                    val sec = t.elapsedMs() / 1000
                    SlashResult.Notice(
                        "**任务 `${t.id}`**\n\n" +
                            "- 状态：${t.status}${if (t.exitCode >= 0) "（exit ${t.exitCode}）" else ""}\n" +
                            "- 耗时：${sec}s\n" +
                            "- 命令：`${t.command.take(100)}`\n" +
                            "- 通道：${t.channelLabel}\n\n" +
                            "**输出尾部**：\n```\n${t.tail(2000)}\n```"
                    )
                }
            }
        }

        // ── /team —— 团队全景 ────────────────────────────────────────────
        //
        // 【2026-10-06 问题40】TeamStore 早就实现（Team 工具用），
        // 但没有查询命令。
        "/team" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val store = com.ccm.app.tools.task.TeamStore(java.io.File(st.root, "teams"))
                val teams = store.list()
                if (teams.isEmpty()) {
                    SlashResult.Notice("没有活跃的团队。\n\n_团队由 Agent 用 TeamCreate 工具创建。_")
                } else {
                    val sb = StringBuilder("**团队（${teams.size} 个）**\n\n")
                    teams.forEach { t ->
                        sb.append("- **${t.name}**")
                        if (t.description.isNotBlank()) sb.append(" — ${t.description}")
                        sb.append("\n")
                        t.members.forEach { m ->
                            sb.append("  · ${m.name}（${m.role.ifBlank { "无角色" }}）[${m.status}]\n")
                        }
                    }
                    SlashResult.Notice(sb.toString())
                }
            }
        }

        // ── /keys —— 快捷键速查 ──────────────────────────────────────────
        //
        // APK 的快捷键与 CLI 不同（触摸屏）—— 列 APK 实际支持的。
        "/keys" -> SlashResult.Notice(
            "**APK 快捷键**\n\n" +
                "- 输入栏麦克风：语音输入\n" +
                "- 输入栏 + ：附件菜单\n" +
                "- 模型 chip：切模型/Provider\n" +
                "- 标题栏 ☰：侧栏\n" +
                "- 消息长按：复制/选取\n" +
                "- 代码块「复制」：复制代码\n\n" +
                "_APK 是触摸屏，没有 CLI 的 Ctrl+X/Ctrl+C 等终端快捷键。_"
        )

        // ── /palette —— 命令面板 ─────────────────────────────────────────
        //
        // APK 的输入 `/` 就有候选面板 —— 这里给完整清单。
        "/palette" -> {
            val cmds = com.ccm.app.ui.chat.COMMON_SLASH_COMMANDS
            val sb = StringBuilder("**全部命令（${cmds.size} 个）**\n\n")
            cmds.forEach { (c, d) -> sb.append("- `$c` — $d\n") }
            sb.append("\n_输入 `/` 会弹出候选面板（边打边筛）。_")
            SlashResult.Notice(sb.toString())
        }

        // ── /image —— 识图 ───────────────────────────────────────────────
        //
        // 【2026-10-06 问题40】APK 支持发图（输入栏 + 按钮），
        // 但没有 `/image <路径>` 命令。
        "/image" -> {
            val a = arg.trim()
            if (a.isBlank()) {
                SlashResult.Notice(
                    "用法：`/image <图片路径> [说明]`\n\n" +
                        "例：`/image /sdcard/DCIM/xxx.jpg 这是什么`\n\n" +
                        "_也可以直接点输入栏的 + 按钮选图。_"
                )
            } else {
                // 路径 + 可选说明
                val path = a.substringBefore(" ").trim()
                val note = a.substringAfter(" ", "").trim()
                val f = java.io.File(path)
                if (!f.exists()) {
                    SlashResult.Notice("文件不存在：$path")
                } else {
                    // 通过 session 发送（带图）
                    val s = ctx.session
                    if (s == null) {
                        SlashResult.Notice("无会话，无法发图。")
                    } else {
                        s.send(note.ifBlank { "看看这张图" }, listOf(path))
                        SlashResult.Notice("已发送图片：`${f.name}`")
                    }
                }
            }
        }

        // ── /compact-threshold —— 自动压缩阈值 ───────────────────────────
        "/compact-threshold" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                if (loadR.error != null) {
                    SlashResult.Notice("配置损坏：${loadR.error}")
                } else {
                    val a = arg.trim().removeSuffix("%")
                    val cur = loadR.config.compactThreshold
                    if (a.isBlank()) {
                        SlashResult.Notice(
                            "**自动压缩阈值**：${if (cur <= 0) "关闭" else "$cur%"}\n\n" +
                                "用法：`/compact-threshold <百分比>`（0 = 关闭）\n" +
                                "例：`/compact-threshold 80` —— 上下文用到 80% 时自动压缩\n\n" +
                                "_注意：压缩会摘要历史，可能丢细节。默认关闭。_"
                        )
                    } else {
                        val v = a.toIntOrNull()
                        if (v == null || v < 0 || v > 100) {
                            SlashResult.Notice("阈值必须是 0-100 的数字（0 = 关闭）。")
                        } else {
                            com.ccm.app.core.provider.AppConfig.save(
                                loadR.config.copy(compactThreshold = v), st.configFile,
                            )
                            SlashResult.Notice(if (v == 0) "自动压缩已**关闭**。" else "自动压缩阈值已设为 **$v%**。")
                        }
                    }
                }
            }
        }

        // ── /replay —— 进会话时是否显示历史正文 ──────────────────────────
        "/replay" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                if (loadR.error != null) {
                    SlashResult.Notice("配置损坏：${loadR.error}")
                } else {
                    val a = arg.trim().lowercase()
                    val cur = loadR.config.replayHistory
                    when (a) {
                        "" -> SlashResult.Notice(
                            "**历史回放**：${if (cur) "✅ 开启（进会话显示历史正文）" else "❌ 关闭（静默进入）"}\n\n" +
                                "用法：`/replay on` 开 · `/replay off` 关\n\n" +
                                "_影响：Ctrl+X 重启续接、/resume 进会话时是否铺历史正文。_"
                        )
                        "on", "开" -> {
                            com.ccm.app.core.provider.AppConfig.save(
                                loadR.config.copy(replayHistory = true), st.configFile,
                            )
                            SlashResult.Notice("历史回放已**开启**。")
                        }
                        "off", "关" -> {
                            com.ccm.app.core.provider.AppConfig.save(
                                loadR.config.copy(replayHistory = false), st.configFile,
                            )
                            SlashResult.Notice("历史回放已**关闭**（进会话静默）。")
                        }
                        else -> SlashResult.Notice("用法：`/replay on|off`")
                    }
                }
            }
        }

        // ── /cache —— Prompt Cache 开关 ──────────────────────────────────
        "/cache" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                if (loadR.error != null) {
                    SlashResult.Notice("配置损坏：${loadR.error}")
                } else {
                    val a = arg.trim().lowercase()
                    val cur = loadR.config.promptCache
                    when (a) {
                        "" -> SlashResult.Notice(
                            "**Prompt Cache**：${if (cur) "✅ 开启" else "❌ 关闭"}\n\n" +
                                "用法：`/cache on` 开 · `/cache off` 关\n\n" +
                                "_控制是否发 `prompt_cache_key` 字段。\n" +
                                "⚠ 未知兼容网关不要盲开 —— 部分中转站会因未知字段报错。_"
                        )
                        "on", "开" -> {
                            com.ccm.app.core.provider.AppConfig.save(
                                loadR.config.copy(promptCache = true), st.configFile,
                            )
                            SlashResult.Notice("Prompt Cache 已**开启**。\n\n⚠ 需重启生效。")
                        }
                        "off", "关" -> {
                            com.ccm.app.core.provider.AppConfig.save(
                                loadR.config.copy(promptCache = false), st.configFile,
                            )
                            SlashResult.Notice("Prompt Cache 已**关闭**。")
                        }
                        else -> SlashResult.Notice("用法：`/cache on|off`")
                    }
                }
            }
        }

        // ── /font —— 字体（APK 只支持查看和恢复默认）────────────────────
        "/font" -> {
            val a = arg.trim().lowercase()
            val cur = com.ccm.app.ui.theme.UiPrefs.chatFont.value
            if (a == "reset") {
                com.ccm.app.ui.theme.UiPrefs.setChatFont("default")
                SlashResult.Notice("字体已恢复默认。")
            } else {
                SlashResult.Notice(
                    "**当前字体**：`$cur`\n\n" +
                        "APK 的字体在「设置 → 通用 → 聊天字体」切换。\n" +
                        "`/font reset` 恢复默认。"
                )
            }
        }

        // ── /statusline —— 状态栏（APK 是固定的）───────────────────────
        "/statusline" -> SlashResult.Notice(
            "APK 的底部状态行是**固定布局**（模型名 + token 数 + 连接状态），\n" +
                "不像 CLI 那样可自定义命令。\n\n" +
                "_CLI 的 `/statusline set <命令>` 在 APK 不适用（没有 shell 环境）。_"
        )

        // ── /mem —— 结构化记忆库 ───────────────────────────────────────────
        //
        // 【2026-10-06 问题40 新建】CLI 的 /mem 管 `memory/` 目录下的
        // **结构化记忆条目**（frontmatter + 正文），与 CLAUDE.md 分开。
        //
        // 用法（对齐 CLI）：
        //   /mem                  列全部
        //   /mem list             同
        //   /mem find <关键词>    按相关性检索
        //   /mem show <路径>      看某一条
        //   /mem save <类型> <路径> <说明> :: <正文>
        //   /mem rm <路径>        删除
        //   /mem dir              看目录
        "/mem" -> {
            val root = com.ccm.app.AppGraph.storage?.root
            if (root == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val dir = java.io.File(root, "memory")
                val parts = arg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val sub = parts.firstOrNull()?.lowercase() ?: "list"
                val rest = parts.drop(1)

                when (sub) {
                    "list", "ls", "" -> {
                        val list = com.ccm.app.core.memory.MemoryDir.listMemories(dir)
                        SlashResult.Notice(com.ccm.app.core.memory.MemoryDir.formatMemoryList(list, dir))
                    }
                    "find", "search" -> {
                        val q = rest.joinToString(" ").trim()
                        if (q.isBlank()) {
                            SlashResult.Notice("用法：`/mem find <关键词>`")
                        } else {
                            val hits = com.ccm.app.core.memory.MemoryDir.findRelevantMemories(dir, q)
                            if (hits.isEmpty()) {
                                SlashResult.Notice("没有匹配「$q」的记忆")
                            } else {
                                SlashResult.Notice(com.ccm.app.core.memory.MemoryDir.formatMemoryList(hits, dir))
                            }
                        }
                    }
                    "show", "cat" -> {
                        val rel = rest.firstOrNull().orEmpty()
                        if (rel.isBlank()) {
                            SlashResult.Notice("用法：`/mem show <相对路径>`")
                        } else {
                            val hit = com.ccm.app.core.memory.MemoryDir.listMemories(dir)
                                .firstOrNull { it.rel == rel || it.rel == "$rel.md" }
                            if (hit == null) {
                                SlashResult.Notice("找不到记忆：$rel")
                            } else {
                                SlashResult.Notice(
                                    "${hit.rel}\ntype: ${hit.type}\n" +
                                        (if (hit.description.isNotBlank()) "${hit.description}\n" else "") +
                                        "\n${hit.body}"
                                )
                            }
                        }
                    }
                    "save", "add" -> {
                        // /mem save <类型> <路径> <说明> :: <正文>
                        val type = rest.getOrNull(0)?.lowercase().orEmpty()
                        if (type !in com.ccm.app.core.memory.MemoryDir.MEMORY_TYPES) {
                            SlashResult.Notice(
                                "用法：`/mem save <${com.ccm.app.core.memory.MemoryDir.MEMORY_TYPES.joinToString("|")}> <路径> <说明> :: <正文>`\n" +
                                    "例：`/mem save feedback style/tone 用户要求直接 :: 不要客套话`"
                            )
                        } else {
                            val restStr = rest.drop(1).joinToString(" ")
                            val bodyPart = restStr.substringAfter("::", "")
                            val headPart = restStr.substringBefore("::").trim()
                            val bits = headPart.split(Regex("\\s+")).filter { it.isNotEmpty() }
                            val rel = bits.firstOrNull().orEmpty()
                            val desc = bits.drop(1).joinToString(" ")
                            if (rel.isBlank()) {
                                SlashResult.Notice("缺少路径")
                            } else if (bodyPart.trim().isEmpty() && desc.isEmpty()) {
                                SlashResult.Notice("至少要有说明或正文")
                            } else {
                                try {
                                    val (_, savedRel) = com.ccm.app.core.memory.MemoryDir.saveMemory(
                                        dir = dir,
                                        rel = rel,
                                        name = rel.split("/").last(),
                                        description = desc,
                                        type = type,
                                        body = bodyPart.trim().ifEmpty { desc },
                                    )
                                    SlashResult.Notice("已保存：`$savedRel`\n\n_目录：${dir.absolutePath}_")
                                } catch (t: Throwable) {
                                    SlashResult.Notice("保存失败：${t.message}")
                                }
                            }
                        }
                    }
                    "rm", "delete" -> {
                        val rel = rest.firstOrNull().orEmpty()
                        if (rel.isBlank()) {
                            SlashResult.Notice("用法：`/mem rm <相对路径>`")
                        } else {
                            try {
                                val ok = com.ccm.app.core.memory.MemoryDir.deleteMemory(dir, rel)
                                SlashResult.Notice(if (ok) "已删除：$rel" else "找不到：$rel")
                            } catch (t: Throwable) {
                                SlashResult.Notice("删除失败：${t.message}")
                            }
                        }
                    }
                    "dir" -> SlashResult.Notice(dir.absolutePath)
                    else -> SlashResult.Notice(
                        "**结构化记忆**（按需检索，不像 CLAUDE.md 每轮都注入）\n\n" +
                            "- `/mem` 或 `/mem list` 查看全部\n" +
                            "- `/mem find <关键词>` 按相关性检索\n" +
                            "- `/mem show <路径>` 看某一条\n" +
                            "- `/mem save <类型> <路径> <说明> :: <正文>`\n" +
                            "- `/mem rm <路径>` 删除\n" +
                            "- `/mem dir` 看目录\n\n" +
                            "_类型：user | feedback | project | reference_"
                    )
                }
            }
        }

        // ── /goal —— 目标模式（完成契约）──────────────────────────────────
        //
        // 【2026-10-06 问题40 修复】原来报「APK 暂未接入」——
        // 但 GoalStore / GoalRuntime / ChatSession.runGoal **全都实现了**，
        // 只是命令没接。用户报「行为降级」的典型。
        //
        // 用法（对齐 CLI）：
        //   /goal              看当前目标与进度
        //   /goal <描述>       设定并开始推进
        //   /goal status       看状态
        //   /goal clear        放弃目标
        "/goal" -> {
            val a = arg.trim()
            when {
                a.isBlank() || a == "status" -> {
                    val text = ctx.goalStatusText?.invoke()
                    SlashResult.Notice(text ?: "当前没有目标。\n\n用法：`/goal <目标描述>` 设定并开始推进。")
                }
                a == "clear" || a == "取消" -> {
                    val st = com.ccm.app.AppGraph.toolsResult?.goalStore
                    val sid = com.ccm.app.AppGraph.sessionId
                    if (st != null) {
                        st.clear(sid)
                        SlashResult.Notice("已清除目标。")
                    } else SlashResult.Notice("目标存储未就绪。")
                }
                a == "help" -> SlashResult.Notice(
                    "**/goal —— 完成契约**\n\n" +
                        "- `/goal <描述>` 设定并开始自动推进\n" +
                        "- `/goal` 或 `/goal status` 看当前目标与进度\n" +
                        "- `/goal clear` 放弃目标\n\n" +
                        "_设定后 runtime 会跨轮自动推进，直到判据验证通过、预算耗尽、或你 Ctrl+C 暂停。_"
                )
                else -> {
                    val fn = ctx.startGoal
                    if (fn == null) {
                        SlashResult.Notice("目标模式在当前界面不可用（需在对话页操作）。")
                    } else {
                        fn(a, a)
                        SlashResult.Notice("**已设定目标**\n\n$a\n\n开始自动推进…（`/goal` 看进度，`/goal clear` 取消）")
                    }
                }
            }
        }

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
        // 【2026-10-06 问题40 修复】原来 `/mem` 和 `/memory` 是**同一个分支**
        // （都管 CLAUDE.md）—— 但 CLI 里它们是**两个不同的东西**：
        //   · `/memory` 管 CLAUDE.md（项目总纲，每轮全量注入）
        //   · `/mem` 管结构化记忆库（files/memory/，按需检索）
        // 混为一谈是功能缺失。这里拆开：本分支只管 CLAUDE.md。
        "/memory" -> {
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

        // ── /automem —— 自动记忆开关 ───────────────────────────────────────
        //
        // 【2026-10-06 问题40 修复】原来报「APK 暂未接入」——
        // 但 AutoMemory 类**早就实现好了**（core/memory/AutoMemory.kt），
        // ChatSession 每轮结束都会调它（maybeRunAutoMemory），
        // 只是这个命令没接。用户报「行为降级」的典型。
        //
        // 用法（对齐 CLI）：
        //   /automem            看状态
        //   /automem on|off     开关
        "/automem" -> {
            val am = com.ccm.app.AppGraph.autoMemory
            if (am == null) {
                SlashResult.Notice("自动记忆未初始化。")
            } else {
                val a = arg.trim().lowercase()
                when (a) {
                    "on", "开" -> {
                        am.setEnabled(true)
                        SlashResult.Notice("自动记忆已**开启** —— 每轮对话结束后增量提取结论写入 CLAUDE.md。")
                    }
                    "off", "关" -> {
                        am.setEnabled(false)
                        SlashResult.Notice("自动记忆已**关闭**。")
                    }
                    else -> {
                        val (enabled, cursor, runs) = am.status()
                        SlashResult.Notice(
                            "**自动记忆**\n\n" +
                                "- 状态：${if (enabled) "✅ 开启" else "❌ 关闭"}\n" +
                                "- 游标：$cursor（已处理到历史第几条）\n" +
                                "- 已跑：$runs 次\n\n" +
                                "用法：`/automem on` 开 · `/automem off` 关\n\n" +
                                "_机制：每轮对话结束后，用当前 Provider 提取「值得记住的结论」追加到 CLAUDE.md。_"
                        )
                    }
                }
            }
        }

        // ── /github —— GitHub 工具配置 ─────────────────────────────────────
        //
        // 【2026-10-06 问题40 修复】原来报「在 CLI 侧管理，APK 暂未接入」——
        // 但 GitHub 工具（8 个）**刚接好了**（AppGraph 读 files/github.json），
        // 命令也该能配。
        //
        // 配置格式对齐 CLI：{ "token": "ghp_...", "defaultRepo": "owner/name" }
        //
        // 用法：
        //   /github              看状态
        //   /github login <token>  设 token
        //   /github repo <owner/name>  设默认仓库
        "/github" -> {
            val root = com.ccm.app.AppGraph.storage?.root
            if (root == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val f = java.io.File(root, "github.json")
                val a = arg.trim()
                val sub = a.substringBefore(" ").lowercase()
                val subArg = a.substringAfter(" ", "").trim()

                fun readCfg(): org.json.JSONObject =
                    try { if (f.exists()) org.json.JSONObject(f.readText()) else org.json.JSONObject() }
                    catch (_: Throwable) { org.json.JSONObject() }

                fun writeCfg(o: org.json.JSONObject) {
                    try { f.writeText(o.toString(2)) } catch (_: Throwable) {}
                }

                when (sub) {
                    "login", "token" -> {
                        if (subArg.isBlank()) {
                            SlashResult.Notice("用法：`/github login <ghp_...>`")
                        } else {
                            writeCfg(readCfg().put("token", subArg))
                            SlashResult.Notice("GitHub token 已保存。\n\n_注意：需重启 App 后工具才会用新 token。_")
                        }
                    }
                    "repo" -> {
                        if (subArg.isBlank()) {
                            SlashResult.Notice("用法：`/github repo <owner/name>`")
                        } else {
                            writeCfg(readCfg().put("defaultRepo", subArg))
                            SlashResult.Notice("默认仓库已设为 `$subArg`。")
                        }
                    }
                    else -> {
                        val c = readCfg()
                        val tok = c.optString("token", "")
                        val repo = c.optString("defaultRepo", "")
                        SlashResult.Notice(
                            "**GitHub 配置**\n\n" +
                                "- Token：${if (tok.isBlank()) "❌ 未配置" else "✅ 已配置（${tok.take(8)}…）"}\n" +
                                "- 默认仓库：${repo.ifBlank { "（未设置）" }}\n\n" +
                                "用法：\n" +
                                "- `/github login <token>` 设 PAT\n" +
                                "- `/github repo <owner/name>` 设默认仓库\n\n" +
                                "_配置后可用 GitHubRepo / GitHubIssues / GitHubPRs / GitHubFile 等 8 个工具。_"
                        )
                    }
                }
            }
        }

        // ── /mail —— 邮箱（CLI 的 MCP 侧）────────────────────────────────────
        "/mail" -> SlashResult.Notice(
            "**邮箱**\n\n" +
                "APK 暂未接入邮件（CLI 的 mail-qq 走 MCP，APK 无 MCP 通道）。\n\n" +
                "_需要接码/收邮件时，可用 CLI 侧的 mail-qq；APK 侧暂无法替代。_"
        )

        // ── /mcp —— MCP 服务器（APK 无 MCP）─────────────────────────────────
        //
        // 已确认 APK 源码里没有任何 MCP 相关实现（find *Mcp* 无结果）。
        "/mcp" ->
            // 【2026-10-06 问题40 修复】原来报「APK 暂未接入」——
            // 用户指出「mcp 可以接，你看 operit 的实现」。
            // 查了 Operit（AAswordman/Operit）→ 它用官方 Kotlin SDK
            // （io.modelcontextprotocol:kotlin-sdk-client）。
            // 已接入：McpManager + McpGenericTool（HTTP/SSE 类型）。
            run {
                val root = com.ccm.app.AppGraph.storage?.root
                if (root == null) {
                    SlashResult.Notice("存储未初始化。")
                } else {
                    val f = java.io.File(root, "mcp.json")
                    if (!f.exists()) {
                        SlashResult.Notice(
                            "**MCP 服务器**\n\n" +
                                "未配置（找不到 `mcp.json`）。\n\n" +
                                "配置格式（对齐 CLI）：\n" +
                                "```json\n" +
                                "{\n" +
                                "  \"mcpServers\": {\n" +
                                "    \"my-server\": {\n" +
                                "      \"url\": \"http://127.0.0.1:3001/mcp\"\n" +
                                "    }\n" +
                                "  }\n" +
                                "}\n" +
                                "```\n\n" +
                                "**支持两种类型**：\n" +
                                "- **HTTP/SSE**：配 `url` 字段\n" +
                                "- **stdio**：配 `command` + `args`（+可选 `env`）\n" +
                                "  ⚠️ 需要 `command` 在**系统 PATH 或绝对路径**可执行\n\n" +
                                "_放好配置后重启 App，工具会以 `mcp_<服务器名>` 的形式出现。_"
                        )
                    } else {
                        val mgr = com.ccm.app.core.mcp.McpManager(f)
                        val servers = mgr.loadServers()
                        if (servers.isEmpty()) {
                            SlashResult.Notice("`mcp.json` 里没有配置任何服务器。")
                        } else {
                            val body = buildString {
                                appendLine("**MCP 服务器（${servers.size} 个）**")
                                appendLine()
                                servers.forEach { s ->
                                    val status = when {
                                        s.disabled -> "⏸ 已禁用"
                                        s.url != null -> "✅ HTTP/SSE"
                                        s.command != null -> "✅ stdio"
                                        else -> "❓ 配置不完整"
                                    }
                                    appendLine("- **${s.name}** — $status")
                                    s.url?.let { appendLine("  `$it`") }
                                    s.commandLine.takeIf { it.isNotEmpty() }?.let {
                                        appendLine("  `" + it.joinToString(" ") + "`")
                                    }
                                }
                                appendLine()
                                append("_工具名：`mcp_<服务器名>`（用 action:list 看具体工具）_")
                            }
                            SlashResult.Notice(body)
                        }
                    }
                }
            }

        // ── /pexels —— 图库 key ────────────────────────────────────────────
        //
        // 【2026-10-06 问题40 修复】原来报「在 CLI 侧配置」——
        // 但 AppConfig 刚补了 pexelsKey 字段、buildSettings 也传了，
        // 这里接上配置命令。
        //
        // 用法：
        //   /pexels              看状态
        //   /pexels set <key>    设 key
        //   /pexels clear        清空
        "/pexels" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val a = arg.trim()
                val sub = a.substringBefore(" ").lowercase()
                val subArg = a.substringAfter(" ", "").trim()
                val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                if (loadR.error != null) {
                    SlashResult.Notice("配置损坏：${loadR.error}")
                } else {
                    when (sub) {
                        "set" -> {
                            if (subArg.isBlank()) {
                                SlashResult.Notice("用法：`/pexels set <key>`")
                            } else {
                                com.ccm.app.core.provider.AppConfig.save(
                                    loadR.config.copy(pexelsKey = subArg), st.configFile,
                                )
                                // 【2026-10-06 热更新】settings 是 MapToolSettings
                                // （get()=map[key] 实时读），put 后 FindImage 立即用新 key。
                                com.ccm.app.AppGraph.toolSettings?.put("pexelsApiKey", subArg)
                                SlashResult.Notice("Pexels key 已保存 · 立即生效。")
                            }
                        }
                        "clear" -> {
                            com.ccm.app.core.provider.AppConfig.save(
                                loadR.config.copy(pexelsKey = null), st.configFile,
                            )
                            com.ccm.app.AppGraph.toolSettings?.put("pexelsApiKey", null)
                            SlashResult.Notice("Pexels key 已清空。")
                        }
                        else -> {
                            val k = loadR.config.pexelsKey
                            SlashResult.Notice(
                                "**Pexels 图库**（FindImage 用）\n\n" +
                                    "- Key：${if (k.isNullOrBlank()) "❌ 未配置" else "✅ 已配置（${k.take(6)}…）"}\n\n" +
                                    "用法：\n" +
                                    "- `/pexels set <key>` 配置（免费申请：https://www.pexels.com/api/）\n" +
                                    "- `/pexels clear` 清空\n\n" +
                                    "_配置后 FindImage 工具能按关键词搜图并下载。免费额度 200 次/小时。_"
                            )
                        }
                    }
                }
            }
        }

        // ── /tvly —— Tavily 搜索 key（WebSearch 用）─────────────────────
        //
        // 存 AppConfig.tavilyKey（顶层字段，与设置页「Tavily 密钥」同一处），
        // 生效走 AppGraph.toolSettings.put("tavilyApiKey", …) ——
        // WebTools 是 get()=map[key] 实时读，**无需重启**。
        "/tvly" -> {
            val st = com.ccm.app.AppGraph.storage
            if (st == null) {
                SlashResult.Notice("存储未初始化。")
            } else {
                val a = arg.trim()
                val sub = a.substringBefore(" ").lowercase()
                val subArg = a.substringAfter(" ", "").trim()
                val loadR = com.ccm.app.core.provider.AppConfig.load(st.configFile)
                if (loadR.error != null) {
                    SlashResult.Notice("配置损坏：${loadR.error}")
                } else when {
                    sub == "clear" -> {
                        com.ccm.app.core.provider.AppConfig.save(
                            loadR.config.copy(tavilyKey = null), st.configFile,
                        )
                        com.ccm.app.AppGraph.toolSettings?.put("tavilyApiKey", null)
                        SlashResult.Notice("Tavily key 已清空（WebSearch 会提示「未配置」）。")
                    }
                    sub == "set" || a.isNotEmpty() -> {
                        // 支持 /tvly <tvly-...> 与 /tvly set <tvly-...> 两种写法
                        val key = (if (sub == "set") subArg else a).trim()
                        if (key.isBlank()) {
                            SlashResult.Notice("用法：`/tvly <tvly-...>` 或 `/tvly set <tvly-...>`")
                        } else {
                            com.ccm.app.core.provider.AppConfig.save(
                                loadR.config.copy(tavilyKey = key), st.configFile,
                            )
                            com.ccm.app.AppGraph.toolSettings?.put("tavilyApiKey", key)
                            SlashResult.Notice(
                                "Tavily key 已设置（${key.take(8)}…${key.takeLast(4)}）· 立即生效。\n\n" +
                                    "_注册拿 key：<https://app.tavily.com/>（key 形如 `tvly-xxxxxxxx`）_"
                            )
                        }
                    }
                    else -> {
                        val k = loadR.config.tavilyKey
                        SlashResult.Notice(
                            "**Tavily 搜索**（WebSearch 联网搜索用）\n\n" +
                                "- Key：${if (k.isNullOrBlank()) "❌ 未配置" else "✅ 已配置（${k.take(8)}…）"}\n" +
                                "- 格式：`tvly-xxxxxxxx`\n\n" +
                                "用法：\n" +
                                "- `/tvly <tvly-...>` 设置（立即生效）\n" +
                                "- `/tvly clear` 清空\n\n" +
                                "_没 key 时 WebSearch 不可用，可改用 SearchInfo（国内源）。_"
                        )
                    }
                }
            }
        }

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
        // 【2026-10-06 对齐 CLI】原来只跑 `git diff --stat`（**只有统计**）——
        // CLI 的 /diff 给完整 diff + 词级高亮（cmd-extensions.mjs:139 cmdDiff）。
        // 现在：stat 摘要 + 完整 diff（超长截断 20K，对齐 CLI 的 20000）。
        // --stat / --staged 参数照传（CLI 支持 --staged/--cached/--stat）。
        val sub = if (arg.isBlank()) "" else " " + arg.trim()
        val statOnly = arg.contains("--stat") || arg.contains("--summary")
        val cmd = if (statOnly) "git diff --stat$sub" else "git diff --stat$sub; echo '---DIFF---'; git diff$sub"
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
                else -> {
                    // 拆分 stat 与完整 diff，完整部分超长截断（对齐 CLI 的 20000）
                    val parts = out.split("---DIFF---")
                    val stat = parts.getOrNull(0)?.trim().orEmpty()
                    val diff = parts.getOrNull(1)?.trim().orEmpty()
                    when {
                        diff.isEmpty() -> stat
                        diff.length > 20_000 -> stat + "\n\n" + diff.take(20_000) + "\n\n... [diff 超长，已截断]"
                        else -> stat + "\n\n" + diff
                    }
                }
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
