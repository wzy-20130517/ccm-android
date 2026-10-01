package com.ccm.app.ui.chat

import com.ccm.app.core.ChatSession

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

        // /branch —— 分支机制在 CLI 侧，APK 未接入；给等价替代方案。
        "/branch" -> SlashResult.Notice(
            "分支功能在 CLI 侧，APK 可用 `/save` 后新建会话作为替代。"
        )

        // /incognito —— 无痕模式在 CLI 侧，APK 未接入。
        "/incognito" -> SlashResult.Notice(
            "无痕模式在 CLI 侧，APK 暂未接入。"
        )

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
                    append("_说明：APK 侧只展示清单，技能正文注入对话的执行能力暂未接入（在 CLI 侧用 `/技能名` 或 Skill 工具）。_")
                }
                SlashResult.Notice(body)
            }
        }

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

        // ── /mem、/memory —— 项目记忆（读 CLAUDE.md，APK 只读展示）──────────
        //
        // CLAUDE.md 实际落在存储根（AppGraph.storage.root/CLAUDE.md），
        // 不在 workspace cwd —— 见 AppGraph.migrateLegacyConfig 的 ③ 项目记忆。
        "/mem", "/memory" -> {
            val root = com.ccm.app.AppGraph.storage?.root
            if (root == null) {
                SlashResult.Notice("无法读取项目记忆：存储尚未初始化。")
            } else {
                val md = java.io.File(root, "CLAUDE.md")
                if (!md.isFile || md.length() == 0L) {
                    SlashResult.Notice(
                        "当前没有项目记忆文件（`CLAUDE.md`）。\n\n" +
                            "_说明：APK 侧 `/memory` 为只读展示，写入记忆的编辑能力在 CLI 侧（Memory 工具 / `/memory` 子命令）。_"
                    )
                } else {
                    // 只展示头部，避免超长记忆把一条通知撑爆。
                    val maxChars = 3000
                    val raw = try { md.readText() } catch (t: Throwable) { "" }
                    if (raw.isEmpty()) {
                        SlashResult.Notice("项目记忆文件读取失败或为空。")
                    } else {
                        val head = if (raw.length > maxChars) {
                            raw.substring(0, maxChars) + "\n\n… （已截断，共 ${raw.length} 字符）"
                        } else {
                            raw
                        }
                        SlashResult.Notice(
                            buildString {
                                appendLine("**项目记忆（CLAUDE.md）**")
                                appendLine()
                                appendLine(head)
                                appendLine()
                                append("_说明：APK 侧为只读展示，编辑记忆请用 CLI 侧的 Memory 工具或 `/memory` 子命令。_")
                            }
                        )
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
