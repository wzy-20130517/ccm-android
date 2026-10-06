package com.ccm.app.core

import com.ccm.app.core.agent.AgentEvent
import com.ccm.app.core.agent.AgentLoop
import com.ccm.app.core.provider.AppConfig
import com.ccm.app.core.session.Message
import com.ccm.app.core.session.ContentBlock
import com.ccm.app.core.session.SessionStore
import com.ccm.app.core.tool.ToolRegistry
import com.ccm.app.core.tool.ToolRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 对话门面 —— UI 接入 Agent 的**唯一入口**。
 *
 * ## 为什么要有这一层
 *
 * `AppContainer` 负责装配（建对象），但 UI 需要的是「**跑一轮对话**」这件事。
 * 直接让 UI 操作 `AppContainer` 会带来三个问题：
 * 1. UI 得自己管协程作用域、自己收集 Flow、自己处理取消
 * 2. UI 得自己判断「现在能不能发」（正在跑的时候不该重复发）
 * 3. UI 得自己把 `AgentEvent` 转成可渲染状态（气泡列表）—— 这是**状态管理**，
 *    放 UI 层会让每个屏幕都重复实现一遍
 *
 * 所以本类把这三件事收口：
 * ```
 * UI 侧只需：
 *   session.send("你好")                    // 发消息
 *   session.state.collect { render(it) }    // 渲染状态
 *   session.stop()                          // 用户点停止
 * ```
 *
 * ## 状态模型
 *
 * [State] 是一个**不可变快照**，UI 拿它直接渲染，不需要自己拼装：
 * - [State.bubbles] 已定型的消息气泡（历史 + 已完成的本轮）
 * - [State.streaming] 正在流式输出的内容（可能为空）
 * - [State.running] 是否正在跑（UI 据此禁用发送按钮）
 * - [State.toolCards] 本轮的工具体（进行中/已完成）
 *
 * ## 与 AgentEvent 的关系
 *
 * `AgentEvent` 是**低层事件流**（每次增量一个事件），适合 UI 直接 collect 做动画。
 * [State] 是**高层状态快照**，适合 `collectAsState()` 直接渲染。
 * 两者并存：想自己做细粒度动画的用事件流，想省事的用状态流。
 *
 * @param container 已装配的容器
 * @param scope UI 的生命周期作用域（Activity/ViewModel 的 scope）
 */
class ChatSession(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(State())
    /** UI 订阅这个渲染。 */
    val state: StateFlow<State> = _state.asStateFlow()

    /** 当前正在跑的 Flow（用于取消）。 */
    private var runningJob: kotlinx.coroutines.Job? = null

    /** 是否正在跑。 */
    val isRunning: Boolean get() = runningJob?.isActive == true

    /**
     * 注入一条本地回复（不走 API、不进模型上下文）。
     *
     * slash 命令的输出用它（如 `/help` 的帮助文本）——
     * 这类内容对模型没有价值，塞进历史只会白烧 token。
     * 会 append 进 bubbles（UI 可见），下次 TurnEnd 时也会随
     * AgentLoop 历史落盘的差异被忽略（它不在 getHistory 里）。
     */
    fun injectNotice(text: String) {
        markDirty()   // B3：notice 也是气泡内容
        _state.value = _state.value.copy(
            bubbles = _state.value.bubbles + Bubble(
                role = Message.ROLE_ASSISTANT,
                text = text,
                messageId = "notice-${System.currentTimeMillis()}",
            ),
        )
    }

    /**
     * 注入一条**用户气泡**（问题：slash 命令无用户气泡）。
     *
     * 【2026-10-06 用户反馈】发送 slash 命令（如 `/help`）后，
     * 屏幕上只有命令的输出（assistant 气泡），**看不到自己敲了什么** ——
     * 用户不知道哪条命令产生了哪个结果。
     *
     * 现在 slash 命令执行前会先调这个，把命令文本作为用户气泡上屏。
     */
    fun injectUserEcho(text: String) {
        if (text.isBlank()) return
        markDirty()
        _state.value = _state.value.copy(
            bubbles = _state.value.bubbles + Bubble(
                role = Message.ROLE_USER,
                text = text,
                messageId = "echo-${System.currentTimeMillis()}",
            ),
        )
    }

    /**
     * 从 todos.json 恢复待办看板（audit-core #3，2026-09-28）。
     *
     * TodoWriteTool 写盘齐全但**没人读** —— 进程重启后看板清空，
     * todos.json 成了永久垃圾。建会话后由 AppGraph 调一次。
     * 入参是 `List<Triple<content, status, activeForm>>`（MiscTools.loadTodos 形状）。
     * 注意：只在看板为空时灌，避免覆盖正在进行的新一轮清单。
     */
    fun restoreTodos(todos: List<Triple<String, String, String>>) {
        if (todos.isEmpty()) return
        if (_state.value.todos.isNotEmpty()) return
        _state.value = _state.value.copy(
            todos = todos.map { TodoEntry(content = it.first, status = it.second) },
        )
    }

    /**
     * `/compact` 手动压缩（audit-core #7，对齐 CLI 的手动压缩纪律）。
     *
     * 第一版走 [com.ccm.app.core.compact.Compactor.microCompact]：
     * 只截断**可再生的旧工具输出**、零 API 调用、不动对话本体 ——
     * 工具输出正是上下文大头。摘要式压缩（要打一次模型）后续再接。
     *
     * ⚠️ **刻意不做自动压缩**：用户明确要求过「不主动压缩上下文」，
     *    CLI 侧的自动压缩也是关的 —— APK 延续同一纪律，只有手动 /compact。
     *
     * @return 给用户看的报告（未变化时说明原因）
     */
    /**
     * 生成对话摘要（问题40：`/summary` 命令）。
     *
     * 【原来】APK 报「暂无独立摘要功能，请手动发『总结一下』」——
     * 但 Compactor 的 `buildSummaryInput` / `summarySystemPrompt` /
     * `extractSummary` **全都有**，只差发一次请求。用户报「行为降级」。
     *
     * 挂起函数（要发 API 请求），调用方在自己的协程里跑。
     */
    suspend fun summarizeNow(): String {
        if (isRunning) return "正在执行任务，等这轮结束再摘要。"
        val history = container.agentLoop.getHistory()
        if (history.size < 4) return "历史仅 ${history.size} 条，无需摘要。"

        return try {
            val input = container.compactor.buildSummaryInput(history)
            val sys = container.compactor.summarySystemPrompt()
            // 【2026-10-06 修】原来直接把 buildSummaryInput 当 user message ——
            // **九部分结构提示词整个没用上**（buildSummaryPrompt 从未被调用）。
            // 现在拼完整指令：禁工具前言 + 九部分正文 + 对话原料。
            val prompt = container.compactor.buildSummaryPrompt(input)
            // ⚠️ apiClient.chat 要 **kotlinx.serialization** 的 JsonObject
            // （不是 org.json 的）—— 编译期类型检查抓到的。
            val userMsg = kotlinx.serialization.json.buildJsonObject {
                put("role", kotlinx.serialization.json.JsonPrimitive("user"))
                put("content", kotlinx.serialization.json.JsonPrimitive(prompt))
            }
            val resp = container.apiClient.chat(
                system = sys,
                messages = listOf(userMsg),
                // 摘要固定 medium（CLI 同款）：高 effort 下思考会把输出预算
                // 吃光，返回空摘要（CLI 的 SUMMARY_MAX_TOKENS 演化史是教训）。
                effortOverride = "medium",
            )
            val raw = resp.text
            val summary = container.compactor.extractSummary(raw)
            if (summary.isBlank()) "摘要生成失败（模型返回为空）。"
            else "**对话摘要**\n\n$summary"
        } catch (t: Throwable) {
            "摘要生成失败：${t.message}"
        }
    }

    /**
     * 压缩（挂起版 —— 问题40：能等 PreCompact hook）。
     *
     * 与 [compactNow] 的区别：那个是同步的（UI 直接调），这个是挂起的
     * （能跑 hook）。两者逻辑相同，只是 hook 触发点。
     */
    suspend fun compactNowSuspend(): String {
        // PreCompact hook（输出 DENY 可阻止压缩）
        try {
            val r = triggerHook("PreCompact")
            if (r?.deny == true) {
                return "PreCompact hook 阻止了压缩：${r.message}"
            }
        } catch (_: Throwable) {}

        val result = compactNow()

        // PostCompact hook（观察类）
        try { triggerHook("PostCompact") } catch (_: Throwable) {}

        // ── 【2026-10-06】micro 之后仍不够 → 走**真摘要压缩** ────────────
        //
        // microCompact 只截断可再生工具输出（零 API 调用，无损）。
        // 但长会话真正的问题是**对话本体太长** —— 只有摘要能救。
        // CLI 的 compact() 就是干这个的；APK 原来只有 micro 一半。
        //
        // 判据：micro 没能回收多少（说明大头在对话本体）或用户明确要摘要。
        val needSummary = result.contains("无可回收") || result.contains("回收约 0")
        if (needSummary) {
            val extra = summarizeAndReplace()
            if (extra != null) return extra
        }

        return result
    }

    /**
     * 真摘要压缩：把稳定前缀压成 [历史摘要]，保留尾部。**挂起**（要发 API）。
     *
     * 对照 CLI `compact()`：splitForSummary → 摘要请求（medium）→
     * assembleCompacted → setHistory。
     *
     * @return 给用户看的报告；null = 无可压缩内容（消息太少）
     */
    suspend fun summarizeAndReplace(): String? {
        val history = container.agentLoop.getHistory()
        val split = container.compactor.splitForSummary(history) ?: return null

        val input = container.compactor.buildSummaryInput(split.toSummarize)
        val prompt = container.compactor.buildSummaryPrompt(input)
        val sys = container.compactor.summarySystemPrompt()

        val userMsg = kotlinx.serialization.json.buildJsonObject {
            put("role", kotlinx.serialization.json.JsonPrimitive("user"))
            put("content", kotlinx.serialization.json.JsonPrimitive(prompt))
        }
        val raw = try {
            container.apiClient.chat(
                system = sys,
                messages = listOf(userMsg),
                effortOverride = "medium",   // 摘要固定 medium（防思考吃光预算）
            ).text
        } catch (t: Throwable) {
            return "摘要请求失败：${t.message}\n\n_（microCompact 的结果已保留）_"
        }

        val summary = container.compactor.formatSummary(container.compactor.extractSummary(raw))
        if (summary.isBlank()) return "摘要为空（模型未按格式返回）—— 已保留 microCompact 结果。"

        val newHistory = container.compactor.assembleCompacted(split, summary)
        container.agentLoop.setHistory(newHistory)
        return buildString {
            append("已压缩：${split.toSummarize.size} 条 → 1 段摘要，保留最近 ${split.toKeep.size} 条。")
            if (split.lostPaths.isNotEmpty()) {
                append("\n\n_已记录 ${split.lostPaths.size} 个读过的文件路径（摘要里，不用重读）。_")
            }
        }
    }

    fun compactNow(): String {
        if (isRunning) return "正在执行任务，等这轮结束再压缩。"
        val history = container.agentLoop.getHistory()
        if (history.size < 6) return "历史仅 ${history.size} 条，无需压缩。"

        // 【2026-10-06 问题40】压缩前写备份 —— 对齐 CLI 的 compact-trash 机制。
        // 用户可用 /compact-trash 恢复被压缩掉的原始记录。
        val backupName = try {
            // ToolStorage.rootDir = 应用私有存储根（files/）
            val root = container.agentLoop.toolStorageRoot()
                ?: throw IllegalStateException("no storage")
            val trashDir = java.io.File(root, "compact-trash").apply { mkdirs() }
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val f = java.io.File(trashDir, "session-$ts.json")
            val arr = org.json.JSONArray()
            history.forEach { m ->
                arr.put(org.json.JSONObject().apply {
                    put("role", m.role)
                    put("text", m.text)
                    put("timestamp", m.timestamp)
                })
            }
            f.writeText(arr.toString())
            f.name
        } catch (_: Throwable) { null }

        val r = container.compactor.microCompact(history)
        if (!r.changed) return "无可回收的旧工具输出（最近的都在保护区）。"
        container.agentLoop.setHistory(r.messages)   // MicroResult.messages = 压缩后列表
        return buildString {
            append("已压缩：回收约 ${r.reclaimedTokens} tokens（截断了旧工具输出）。")
            if (backupName != null) {
                append("\n\n_压缩前备份：`compact-trash/$backupName`（可用 `/compact-trash` 查看）_")
            }
        }
    }

    /**
     * 从某条用户消息**重发**（webgap #1：Web 的 RotateCcw 重发按钮，APK 缺）。
     *
     * 语义：截断该消息之后的所有内容（含它的旧回复），用同样的文本重跑。
     * 与 Web 一致 —— 重发是「回到那一刻再来一次」，不是追加。
     *
     * 实现上直接改 AgentLoop 历史 + 重建 UI 气泡（旧回复从两端一起消失，
     * 不会出现「界面留着旧回复、模型已经忘掉」的不一致）。
     */
    fun resendFrom(messageId: String) {
        if (isRunning) return
        val history = container.agentLoop.getHistory()
        // 找到该 messageId 对应的历史索引（Bubble.messageId 与历史条目的
        // timestamp 前缀对应 —— 见 loadHistory/collectEvents 的命名约定）
        val idx = history.indexOfLast { m ->
            m.role == Message.ROLE_USER && messageId.endsWith("${m.timestamp}")
        }
        // 兜底：按文本匹配（messageId 格式历史版本可能不同）
        val target = if (idx >= 0) history[idx] else {
            val b = _state.value.bubbles.firstOrNull { it.messageId == messageId }
            if (b == null) return
            val i2 = history.indexOfLast { m -> m.role == Message.ROLE_USER && m.text == b.text }
            if (i2 < 0) return
            history[i2]
        }
        val cut = history.indexOf(target).takeIf { it >= 0 } ?: return

        // ① 截断历史（含目标本身 —— send 会重新 append）
        container.agentLoop.setHistory(history.subList(0, cut).toList())
        // ② UI 气泡同步截断（保留目标之前的）
        val keep = _state.value.bubbles.indexOfLast { it.messageId == messageId }
            .takeIf { it >= 0 } ?: _state.value.bubbles.size
        _state.value = _state.value.copy(
            bubbles = _state.value.bubbles.take(keep),
            streaming = "",
            toolCards = emptyList(),
            error = null,
        )
        // ③ 重新发送
        send(target.text)
    }

    /**
     * 重试：重跑最后一条用户消息（错误横幅的「重试」按钮用）。
     *
     * 与 resendFrom 的区别：不截断——错误轮次的用户消息**已经进了历史**
     * （send 先 append 再跑），直接把最后一条 user 消息再跑一遍。
     * 找不到可重试的消息（空会话）时静默返回。
     */
    fun retryLast() {
        if (isRunning) return
        val history = container.agentLoop.getHistory()
        val lastUser = history.lastOrNull { it.role == Message.ROLE_USER } ?: return
        // 错误态清掉，重跑（send 会追加新 user 气泡 —— 这是预期：
        // 重试表现为「再问一次」，旧的那条和它的错误回复都留在记录里）
        _state.value = _state.value.copy(error = null)
        send(lastUser.text)
    }

    /** 更新输入框草稿（InputBar 的 onValueChange 直连这里）。 */
    fun setDraft(text: String) {
        _state.value = _state.value.copy(draft = text)
    }

    /**
     * 释放会话 —— 切到别的会话前调。
     *
     * [AppContainer.shutdown] 先 flush 自动保存再停（最后 30 秒的对话不丢），
     * 然后关底层 HTTP 连接。旧 session 不调这个，它的 SessionAuto 会
     * 跟新 session 抢同一个落盘文件。
     */
    fun dispose() {
        try {
            container.shutdown()
        } catch (_: Throwable) {
        }
        runningJob?.cancel()
        runningJob = null
    }

    /**
     * 发一条消息并跑一轮。
     *
     * 已有任务在跑时**直接忽略**（防用户连点发送）。
     * 要打断当前任务用 [stop]。
     */
    fun send(text: String, imagePaths: List<String> = emptyList()) {
        if (text.isBlank()) return
        if (isRunning) return

        val userBubble = Bubble(
            role = Message.ROLE_USER,
            text = text,
            messageId = "user-${System.currentTimeMillis()}",
            images = imagePaths,
        )
        // 对齐 Web 的自动命名：首条用户消息立即作为本地标题兜底，
        // 避免发送后仍显示“新对话”，并交给 SessionAuto 随本轮保存落盘。
        if (container.sessionAuto?.title.isNullOrBlank()) {
            container.sessionAuto?.title = text.trim()
                .replace(Regex("\\s+"), " ")
                .take(48)
        }
        _state.value = _state.value.copy(
            bubbles = _state.value.bubbles + userBubble,
            streaming = "",
            running = true,
            toolCards = emptyList(),
            error = null,
            draft = "",   // 发出去就清空输入框（Web 行为）
        )
        saveForced()   // 用户消息立即落盘，进程被回收时不丢首条输入

        runningJob = scope.launch {
            // 【2026-10-06 问题40】UserPromptSubmit hook ——
            // 输出 `INJECT: 内容` 会把额外上下文注入到用户消息。
            // 原来 APK 完全不触发这个事件（用户报「hooks 也缺了」）。
            var effectiveText = text
            try {
                val r = triggerHook("UserPromptSubmit", prompt = text)
                if (r?.inject != null) {
                    effectiveText = text + "\n\n" + r.inject
                }
            } catch (_: Throwable) {}

            // imagePaths 空 = 原路径，零行为变化（第18批向后兼容点）
            collectEvents(container.agentLoop.run(effectiveText, imagePaths))

            // ── 自动压缩（2026-10-06 P1-8 接线）────────────────────────
            //
            // AutoCompact 类早就写好了（shouldCompact / reportSuccess /
            // reportFailure / isTripped 三道闸），但**零调用** ——
            // 阈值配了也不生效，长会话照样撞 400。
            // 对齐 CLI：每轮 run 正常结束后触发（index.mjs:6343）。
            //
            // ⚠️ 默认关闭（config.compactThreshold 未设时 isEnabled=false）——
            // 用户被自动压缩搞丢过记忆，明确反感，只有他显式设阈值才启用。
            try {
                maybeAutoCompact()
            } catch (_: Throwable) {}
        }
    }

    /**
     * 自动压缩（P1-8）：run 结束后检查水位，超阈值就压。
     *
     * 连续失败 3 次后断路（AutoCompact 内部 isTripped），需手动 /compact 恢复。
     */
    private suspend fun maybeAutoCompact() {
        val ac = container.autoCompact
        val hist = container.agentLoop.getHistory()
        val tokens = container.agentLoop.lastPromptTokens
        if (!ac.shouldCompact(hist, tokens)) return

        val before = hist.size
        val msg = summarizeAndReplace() ?: return
        val after = container.agentLoop.getHistory().size
        if (after < before) {
            ac.reportSuccess()
            _state.value = _state.value.copy(
                bubbles = _state.value.bubbles + Bubble(
                    role = Message.ROLE_ASSISTANT,
                    text = "**自动压缩**\n\n$msg",
                    messageId = "autocompact-${System.currentTimeMillis()}",
                ),
            )
        } else {
            ac.reportFailure()
        }
    }

    /**
     * 打断当前任务（用户点停止）。
     *
     * 会同时取消协程和底层 HTTP 连接（见 `AgentLoop.abort()`）。
     */
    fun stop() {
        container.agentLoop.abort()
        runningJob?.cancel()
        runningJob = null
        _state.value = _state.value.copy(running = false)
    }

    /** 清空当前对话（`/clear`）。 */
    fun clear() {
        stop()
        container.agentLoop.setHistory(emptyList())
        resetAutoMemoryCursor()   // automem：游标也要归零，否则增量永远为负
        markDirty()   // B3：清空也是改动 —— 不标脏则删除不落盘，重启后旧对话复活
        _state.value = State()
    }

    /**
     * 触发 hook 事件（问题40：补齐 6 个事件）。
     *
     * 【原来】APK 只在 ToolExecutor 触发 PreToolUse/PostToolUse ——
     * SessionStart / SessionEnd / UserPromptSubmit / Stop / PreCompact
     * 全缺。用户报「hooks 也缺了」。
     *
     * 挂起（hook 要跑外部命令），调用方自行决定要不要等。
     */
    suspend fun triggerHook(
        event: String,
        prompt: String = "",
        reason: String = "",
    ): com.ccm.app.tools.ToolHooks.HookOutcome? {
        val hooks = com.ccm.app.AppGraph.toolsResult?.hooks ?: return null
        return try {
            hooks.trigger(
                event,
                com.ccm.app.tools.ToolHooks.HookContext(
                    event = event,
                    sessionId = sessionId,
                    prompt = prompt,
                    reason = reason,
                ),
            ).let { r ->
                com.ccm.app.tools.ToolHooks.HookOutcome(
                    deny = r.deny,
                    block = r.block,
                    inject = r.inject,
                    message = r.denyMessage.ifBlank { r.blockMessages.joinToString("\n") },
                )
            }
        } catch (_: Throwable) { null }
    }

    /** 从历史恢复（`/resume`）。 */
    fun loadHistory(messages: List<Message>) {
        container.agentLoop.setHistory(messages)
        val resultsById = messages
            .flatMap { it.content.filterIsInstance<ContentBlock.ToolResult>() }
            .associateBy { it.id }
        val restored = messages.mapNotNull { message ->
            // 【2026-10-06】hidden 消息（轮次提醒/空响应提示/队友注入等
            // 系统内部消息）不进气泡 —— 它们不是用户说的话，显示出来会
            // 让人困惑（「我什么时候发过这个？」）。
            if (message.hidden) return@mapNotNull null
            when (message.role) {
                Message.ROLE_ASSISTANT -> {
                    val cards = message.content.filterIsInstance<ContentBlock.ToolUse>().map { use ->
                        val result = resultsById[use.id]
                        ToolCard(
                            id = use.id,
                            name = use.name,
                            preview = use.input.toString().take(180),
                            input = use.input.toString(),
                            running = result == null,
                            result = result?.content.orEmpty(),
                            isError = result?.isError ?: false,
                        )
                    }
                    if (message.text.isBlank() && cards.isEmpty()) null else Bubble(
                        role = Message.ROLE_ASSISTANT,
                        text = message.text,
                        messageId = "history-${message.timestamp}",
                        toolCards = cards,
                    )
                }
                Message.ROLE_USER -> message.text.takeIf { it.isNotBlank() }?.let {
                    Bubble(role = Message.ROLE_USER, text = it, messageId = "history-${message.timestamp}")
                }
                else -> null
            }
        }
        _state.value = State(bubbles = restored)
        // Web 在首轮消息后异步生成标题；APK 先用首条用户消息做稳定本地兜底。
        if (container.sessionAuto?.title.isNullOrBlank()) {
            messages.firstOrNull { it.role == Message.ROLE_USER }
                ?.text?.trim()?.replace(Regex("\\s+"), " ")
                ?.take(48)?.takeIf { it.isNotBlank() }
                ?.let { container.sessionAuto?.title = it }
        }
    }

    /** 保存当前会话（退出前调）。 */
    fun flush() {
        container.sessionAuto?.flush()
    }

    /**
     * 无痕会话开关（`/incognito`）。**只读** —— 改写请用 [setIncognito]。
     *
     * ⚠️ **必须是 `val` + 私有 backing field，不能写成 `var incognito`**：
     * `var` 会让 Kotlin 生成一个 public 的 `setIncognito(Z)V`，而本类另有
     * 手写的 `fun setIncognito(on: Boolean)` —— 两者 JVM 签名完全相同，
     * 编译直接报：
     * ```
     * Platform declaration clash: The following declarations have the same
     * JVM signature (setIncognito(Z)V)
     * ```
     * （CI #236 的真实报错。注意 `private set` 也救不了 —— 私有 setter 的
     *  JVM 方法名同样是 `setIncognito`，依然撞。）
     *
     * 改成只读属性后：读走 `getIncognito()`、写走手写的 `setIncognito(Z)V`，
     * 各占一个签名，且**调用方零改动**（`session.incognito` / `session.setIncognito(x)`
     * 两个用法在 Kotlin 层面完全不变）。
     *
     * 开启后 [markDirty] 直接跳过 —— 本会话的任何改动都不再落盘，退出即丢。
     * **已落盘的历史不会因此被删除**（要删用 `/delete`）；关掉开关后从当前
     * 状态继续正常落盘。
     *
     * 说明：CLI 的无痕还有「不加载 CLAUDE.md」「禁止访问项目目录」两条语义，
     * APK 侧只实现「不落盘」这一条（另两条要动系统提示词与路径白名单，
     * 后续按需接）。开启时给出明确提示，避免用户误以为历史被删。
     */
    val incognito: Boolean get() = incognitoFlag

    /** [incognito] 的实际存储（只允许经 [setIncognito] 改）。 */
    @Volatile
    private var incognitoFlag: Boolean = false

    /**
     * 切换无痕状态。
     *
     * 开启时额外做一件事：**立即 flush 一次**，把「开启前」的内容落盘。
     *
     * 【为什么必须做】dirty 是 SessionAuto 的私有位，定时器（每 30s）只要看到
     * dirty=true 就 saveNow。若开无痕前刚改过东西（dirty=true），不清的话
     * 定时器仍会落盘一次 —— 而那次落盘会把「开启后新加的消息」一起写进去，
     * 无痕就漏了。只在 markDirty 里拦是不够的（它管不了已经置上的旧 dirty）。
     *
     * 【为什么用 flush 而不是 switchTo(sid, title)】后者会把 createdAt 重置成
     * 当前时间（会话创建时间被改写）。flush 只做「dirty 时落盘」这一件事，
     * 语义精确且无副作用：落盘后 dirty 必为 false（saveNow 里清），
     * 定时器从此无事可做。
     */
    fun setIncognito(on: Boolean) {
        incognitoFlag = on
        if (on) {
            try { container.sessionAuto?.flush() } catch (_: Throwable) { /* 落盘失败不阻塞开关 */ }
        }
    }

    /**
     * ★ B3（findbugs 2026-10-01）：标脏 —— SessionAuto.markDirty 原来**全项目
     * 零调用**，dirty 永远 false → flush()/定时保存/退出保存全是 no-op
     * （/save 假成功、切会话/杀进程丢对话）。所有消息变化点调这里。
     *
     * ⚠️ 无痕会话（[incognito]）下本方法是 no-op —— 这是无痕的**唯一实现点**，
     *   不要改成在 saveForced/flush 里判断：那些路径都经过 markDirty，
     *   在这里拦一处即可覆盖全部（漏一处就等于无痕失效）。
     */
    private fun markDirty() {
        if (incognito) return
        container.sessionAuto?.markDirty()
    }

    /** 手动保存（/save）：无视防抖立即全量落盘。 */
    fun saveForced() {
        markDirty()
        container.sessionAuto?.flush()
    }

    /**
     * 设置会话标题（/rename）。
     *
     * 必须走 SessionAuto.title 而不是手工 SessionStore.load/save ——
     * 自动保存的 saveNow 用 title=this.title 写全量，手工改的文件
     * 下一次防抖落盘就被覆盖回 null（B3 联动坑）。
     */
    fun setTitle(t: String) {
        container.sessionAuto?.title = t
        saveForced()
    }

    /**
     * 当前对话历史的快照（`/branch` 分叉用）。
     *
     * 直接用 AgentLoop 的内存历史 —— 比读会话文件新（文件是 30 秒防抖落盘的，
     * 最近几轮可能还没写进去）。返回的是不可变副本，调用方可安全持有。
     */
    fun historySnapshot(): List<Message> = container.agentLoop.getHistory()

    /**
     * 最近一轮的 token 用量（`/cost` 用）。
     *
     * 返回 (输入, 输出) —— 这是 AgentLoop 累计的**总量**，不是最近一次请求。
     */
    fun totalUsage(): Pair<Int, Int> = container.agentLoop.getTotalUsage()

    /**
     * 运行模式状态（`/plan` `/deep` `/watch` 命令读写用）。
     *
     * **必须是同一个实例**：AgentLoop 每轮 run 开始读 [ModeState.deepMode] 决定
     * maxTurns（见 `AgentLoop.kt:348`）、读 [ModeState.planMode] 拼系统提示词。
     * 本 getter 返回的正是构造 AgentLoop 时传进去的那一个（`AppContainer.modes`），
     * 所以命令改了它就**真的生效**，不是改了个孤立对象。
     */
    val modes: com.ccm.app.core.agent.ModeState get() = container.modes

    /** 当前会话 id（`/status` 展示用）。 */
    val sessionId: String get() = container.sessionAuto?.currentSessionId.orEmpty()

    // ═════════════════════════ Goal 模式（完成契约）═════════════════════════

    /**
     * 跑一条 goal 循环（跨轮自动推进，**挂起直到目标终止**）。
     *
     * ## 与 [send] 的区别
     * [send] 跑一轮就返回；本方法跑**一整条目标** —— 内部反复调 [send]，
     * 每轮之间由 [GoalRuntime] 做终止判定、算预算、可能改状态。
     * 这就是 CLI 的 `runGoalLoop`（`core/goal-runtime.mjs`）。
     *
     * ## 为什么放在 ChatSession 而不是 ui/
     * 驱动逻辑（预算判定、契约注入、收尾轮）是**core 语义**，不是界面逻辑。
     * 放在这里：可单测、可被 cron/自动化调用、UI 只需调一次。
     *
     * ## 调用方要注意
     * - **必须给 goalStore**（构造容器时没传 → 抛 IllegalStateException，
     *   而不是静默什么都不做）
     * - 本方法是**挂起**的：一条 15 轮的目标可能跑几十分钟。
     *   调用方要在自己的协程里 launch，不要阻塞主线程
     * - 用户中途 [stop] → 转 paused，可 `/goal resume` 续
     *
     * @param goalStore 目标存储（由 App 层注入，见 [AppContainer.goalStore]）
     * @param firstMessage 第一个 goal turn 的用户输入（通常是用户原话）
     * @return 结束原因与轮数
     */
    suspend fun runGoal(
        goalStore: com.ccm.app.tools.task.GoalStore,
        firstMessage: String,
    ): com.ccm.app.tools.task.GoalRuntime.Outcome {
        val sid = sessionId
        val runtime = com.ccm.app.tools.task.GoalRuntime(
            store = goalStore,
            sessionId = sid,
            // 一个 goal turn = 一次完整 send + 等它跑完
            runTurn = { msg -> sendAndAwait(msg) },
            getTokens = {
                val (i, o) = container.agentLoop.getTotalUsage()
                (i + o).toLong()
            },
            shouldStop = { isRunning },
        )
        return runtime.run(firstMessage)
    }

    /**
     * 发一条消息并**等它跑完**（[runGoal] 的每一轮用）。
     *
     * [send] 是「点火就走」（返回时协程刚起），这里等 runningJob join。
     * 复用 [send] 而不是另起一套：UI 气泡、状态更新、automem 触发
     * 全都走同一条路径，不会出现「goal 跑的消息在界面上看不见」。
     */
    private suspend fun sendAndAwait(text: String) {
        send(text)
        // send 内部若因 isRunning 忽略（理论上不会：goal 循环是串行的），
        // 这里就等不到东西 —— 加个超时兜底，避免整条 goal 卡死。
        try {
            kotlinx.coroutines.withTimeoutOrNull(GOAL_TURN_TIMEOUT_MS) {
                runningJob?.join()
            }
        } catch (_: Throwable) {
        }
    }

    // ═════════════════════════ 事件 → 状态 ═════════════════════════

    /**
     * 触发一次自动记忆提取（automem）。
     *
     * **独立协程、不 await** —— 理由见调用点注释。scope 是 ChatSession 构造时
     * 拿到的 App 级作用域（不是 runningJob），所以不会被本轮取消连带杀掉。
     *
     * 对齐 CLI `index.mjs` 的 run 结束钩子：
     * `maybeExtractMemory(agent, api, {...}).catch(() => {})`（fire-and-forget）。
     */
    private fun maybeRunAutoMemory() {
        val am = container.autoMemory ?: return
        scope.launch {
            try {
                val note = am.maybeExtract(container.agentLoop.getHistory(), container.apiClient)
                // 提取到内容时给用户一条提示（CLI 是往终端打日志，APK 用 notice 气泡）
                if (note != null) injectNotice(note)
            } catch (_: Throwable) {
                // 失败静默 —— 自动记忆绝不能影响对话
            }
        }
    }

    /**
     * 清空对话时重置 automem 游标。
     *
     * 不重置的话：cursor 停在旧历史的长度上，而新历史从 0 开始 →
     * `history.size - cursor` 长期是负数 → 永远不提取（静默失效）。
     * 对齐 CLI 注释里的「compact 后 cursor 重置」。
     */
    private fun resetAutoMemoryCursor() {
        try {
            container.autoMemory?.resetCursor()
        } catch (_: Throwable) {
        }
    }

    private suspend fun collectEvents(events: Flow<AgentEvent>) {
        val toolCards = mutableListOf<ToolCard>()
        var streaming = ""
        var accumulatedText = ""
        var currentMessageId = ""
        // 思考流（与上面的正文流独立：先想后说，两个流交错）
        var thinkingBuf = ""
        var currentThinkingId = ""

        try {
            events.collect { ev ->
                when (ev) {
                    is AgentEvent.TextDelta -> {
                        // 新 messageId 代表模型新一次响应（工具往返或重试）。
                        // 工具往返是同一轮，前面的正文必须并入累计，不能丢。
                        if (ev.messageId != currentMessageId) {
                            currentMessageId = ev.messageId
                            accumulatedText += streaming
                            streaming = ""
                        }
                        streaming += ev.text
                        _state.value = _state.value.copy(
                            streaming = accumulatedText + streaming,
                            toolCards = toolCards.toList(),
                        )
                    }

                    is AgentEvent.ReasoningDelta -> {
                        // ★ 2026-09-27：原来是 `暂不进状态` 直接丢弃 ——
                        //   AssistantThinkingChain 739 行组件因此永远空转。
                        //   现在与 TextDelta 同模式：messageId 变了重开一轮。
                        // messageId 每次模型响应都会变化，但同一轮工具循环的思维链
                        // 应连续显示；只记录最新 id，不因工具往返清空累计内容。
                        currentThinkingId = ev.messageId
                        thinkingBuf += ev.text
                        _state.value = _state.value.copy(thinking = thinkingBuf)
                    }

                    is AgentEvent.ToolStart -> {
                        toolCards += ToolCard(
                            id = ev.id, name = ev.name, preview = ev.inputPreview,
                            running = true,
                            // 完整入参（ToolDiffView 展开渲染用；原来只存 preview）
                            input = ev.input.toString(),
                        )
                        _state.value = _state.value.copy(toolCards = toolCards.toList())

                        // ★ TodoWrite 拦截（2026-09-27）：更新 State.todos，
                        //   UI 的 TodoPanel 由此拿到数据（之前零调用）。
                        if (ev.name == "TodoWrite") {
                            val arr = ev.input["todos"]
                            if (arr is kotlinx.serialization.json.JsonArray) {
                                val entries = arr.mapNotNull { el ->
                                    val obj = el as? kotlinx.serialization.json.JsonObject
                                        ?: return@mapNotNull null
                                    val c = (obj["content"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                        ?: return@mapNotNull null
                                    val st = (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                                        ?: "pending"
                                    TodoEntry(content = c, status = st)
                                }
                                _state.value = _state.value.copy(todos = entries)
                            }
                        }
                    }

                    is AgentEvent.Present -> {
                        val item = PresentItem(ev.kind, ev.title, ev.caption, ev.content, ev.paths)
                        _state.value = _state.value.copy(presentItems = _state.value.presentItems + item)
                    }

                    is AgentEvent.ToolProgress -> {
                        val i = toolCards.indexOfFirst { it.id == ev.id }
                            .takeIf { it >= 0 }
                            ?: toolCards.indexOfLast { it.running }
                        if (i >= 0) {
                            toolCards[i] = toolCards[i].copy(progress = ev.text)
                            _state.value = _state.value.copy(toolCards = toolCards.toList())
                        }
                    }

                    is AgentEvent.ToolResult -> {
                        val i = toolCards.indexOfFirst { it.id == ev.id }
                        if (i >= 0) {
                            toolCards[i] = toolCards[i].copy(running = false, isError = ev.isError, result = ev.result)
                            _state.value = _state.value.copy(toolCards = toolCards.toList())
                        }
                    }

                    is AgentEvent.Error -> {
                        _state.value = _state.value.copy(error = ev.message)
                    }

                    is AgentEvent.TurnEnd -> {
                        // 整轮中间点：累计当前已输出正文，继续在同一 timeline 展示。
                        // 不生成气泡、不合成 Done，等待最终无工具的 Done。
                        accumulatedText += streaming
                        streaming = ""
                        _state.value = _state.value.copy(
                            streaming = accumulatedText,
                            thinking = thinkingBuf,
                            toolCards = toolCards.toList(),
                        )
                    }

                    is AgentEvent.Usage -> {
                        _state.value = _state.value.copy(
                            inputTokens = ev.inputTokens,
                            outputTokens = ev.outputTokens,
                        )
                    }

                    AgentEvent.Done -> {
                        // 收尾时同时定型正文和思维链；有些 Provider 只回传 reasoning，
                        // 若只检查正文，思维链会在 Done 时被清空并永久丢失。
                        if (accumulatedText.isNotBlank() || streaming.isNotBlank() || thinkingBuf.isNotBlank() || toolCards.isNotEmpty()) {
                            val finalText = (accumulatedText + streaming).trim()
                            val finalThinking = thinkingBuf
                            val finalTools = toolCards.toList()
                            val nextState = _state.value.copy(
                                bubbles = if (finalText.isNotBlank() || finalThinking.isNotBlank() || finalTools.isNotEmpty()) {
                                    _state.value.bubbles + Bubble(
                                        role = Message.ROLE_ASSISTANT,
                                        text = finalText,
                                        messageId = currentMessageId.ifBlank { "turn-${System.currentTimeMillis()}" },
                                        thinking = finalThinking,
                                        toolCards = finalTools,
                                    )
                                } else _state.value.bubbles,
                                streaming = "",
                                thinking = "",
                                toolCards = emptyList(),
                            )
                            _state.value = nextState
                            streaming = ""
                            accumulatedText = ""
                            thinkingBuf = ""
                        }
                        saveForced()   // 每轮完成即落盘，避免切页/进程回收丢失最近一轮
                        // 【2026-10-06 问题14 修复】原来这里是
                        //   `copy(running = false, toolCards = toolCards.toList())`
                        // —— 把刚清空的 toolCards **又设回去了**。
                        // 后果：MessageList 里
                        //   · bubbles 的定型消息渲染一遍工具组（正文上方）
                        //   · state.toolCards 非空触发 `else if` 分支又渲染一遍（下方）
                        // → 用户看到「工具栏重复两排」。
                        // 工具卡已随 Bubble 定型，这里必须保持空。
                        _state.value = _state.value.copy(running = false)

                        // 【2026-10-06 问题40】Stop hook —— 输出 `BLOCK: 原因` 会
                        // 阻止 agent 结束（注入原因后继续跑一轮）。
                        //
                        // ⚠️ APK 简化：不做「续跑一轮」（那要改 AgentLoop 的循环结构），
                        //    只在 blocked 时把原因注入成一条通知，让用户知道 hook 说了什么。
                        try {
                            val r = triggerHook("Stop")
                            if (r?.block == true && r.message.isNotBlank()) {
                                injectNotice("**Stop hook 拦截**\n\n${r.message}")
                            }
                        } catch (_: Throwable) {}

                        // ★ automem（2026-10-01）：每轮正常结束后触发一次记忆提取。
                        //
                        // **fire-and-forget**（不 await）：
                        // - 提取要发一次 API 请求（几秒），await 会拖住 UI 收尾
                        // - 这里已经在 collectEvents 的 finally 前，用独立 launch 才不会
                        //   被 runningJob 的取消连带取消
                        // - 失败静默（AutoMemory 内部 catch），绝不冒泡到对话
                        //
                        // 放在 Done 而不是 try 之外：只有正常跑完的轮次才提取，
                        // 中断/报错的轮次不提取（半截对话提炼不出什么，还浪费一发）。
                        maybeRunAutoMemory()
                    }
                }
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // 【2026-10-06 问题37 修复】原来只把 running=false ——
            // **已收到的流式内容全丢了**。用户报「输出不完全的助手消息，
            // 用户再发条消息会直接把助手消息吃了」：
            //   流式输出到一半 → 用户发新消息/按停止 → CancellationException
            //   → streaming 被清空且没进 bubbles → 半截回复消失。
            //
            // 现在：取消时把已收到的内容**定型成气泡**（保留半截回复），
            // 与 Done 分支同样的逻辑（只是没有 automem 提取）。
            val partialText = (accumulatedText + streaming).trim()
            if (partialText.isNotBlank() || thinkingBuf.isNotBlank() || toolCards.isNotEmpty()) {
                val nextState = _state.value.copy(
                    bubbles = _state.value.bubbles + Bubble(
                        role = Message.ROLE_ASSISTANT,
                        text = partialText,
                        messageId = currentMessageId.ifBlank { "turn-${System.currentTimeMillis()}" },
                        thinking = thinkingBuf,
                        toolCards = toolCards.toList(),
                    ),
                    streaming = "",
                    thinking = "",
                    toolCards = emptyList(),
                    running = false,
                )
                _state.value = nextState
                try { saveForced() } catch (_: Throwable) {}
            } else {
                _state.value = _state.value.copy(running = false)
            }
        } catch (e: Throwable) {
            // 同上：报错时也保留已收到的部分（原来直接丢）
            val partialText = (accumulatedText + streaming).trim()
            if (partialText.isNotBlank() || thinkingBuf.isNotBlank() || toolCards.isNotEmpty()) {
                _state.value = _state.value.copy(
                    bubbles = _state.value.bubbles + Bubble(
                        role = Message.ROLE_ASSISTANT,
                        text = partialText,
                        messageId = currentMessageId.ifBlank { "turn-${System.currentTimeMillis()}" },
                        thinking = thinkingBuf,
                        toolCards = toolCards.toList(),
                    ),
                    streaming = "",
                    thinking = "",
                    toolCards = emptyList(),
                    running = false,
                    error = e.message ?: "未知错误",
                )
            } else {
                _state.value = _state.value.copy(running = false, error = e.message ?: "未知错误")
            }
        } finally {
            runningJob = null
        }
    }

    // ═════════════════════════ 状态模型 ═════════════════════════

    /**
     * 对话状态快照（不可变）。
     *
     * UI 直接 `collectAsState()` 渲染。
     */
    data class State(
        /** 已定型的气泡（历史 + 已完成的本轮）。 */
        val bubbles: List<Bubble> = emptyList(),
        /** 正在流式输出的内容（可能为空）。 */
        val streaming: String = "",
        /** 是否正在跑。 */
        val running: Boolean = false,
        /** 本轮的工具卡片。 */
        val toolCards: List<ToolCard> = emptyList(),
        /** 错误提示（非空时 UI 应显示）。 */
        val error: String? = null,
        /** 最近一次请求的输入 token。 */
        val inputTokens: Int = 0,
        /** 最近一次请求的输出 token。 */
        val outputTokens: Int = 0,
        /** 输入框草稿（打字内容）。放 State 里 = 配置变化/屏幕旋转不丢。 */
        val draft: String = "",
        /**
         * 正在进行的思考内容（ReasoningDelta 累积）。
         *
         * 2026-09-27 之前这里是被丢弃的（`暂不进状态`注释）——
         * UI 的 AssistantThinkingChain（739 行）因此永远无数据可渲染。
         * TurnEnd 时定型进 [Bubble.thinking] 并清空本字段。
         */
        val thinking: String = "",
        /**
         * 当前待办清单（TodoWrite 工具调用时更新，2026-09-27 加）。
         *
         * 之前 core 层没有这个字段 → UI 的 TodoPanel（写好了零调用）
         * 永远拿不到数据。拦截点在 collectEvents 的 ToolStart：
         * `name == "TodoWrite"` 时解析 input.todos。
         * 存在 todos.json（TodoWriteTool 自己落盘），进程重启可从那恢复。
         */
        val todos: List<TodoEntry> = emptyList(),
        val presentItems: List<PresentItem> = emptyList(),
    ) {
        /** 是否为空对话（UI 据此显示欢迎页）。 */
        val isEmpty: Boolean get() = bubbles.isEmpty() && streaming.isBlank()
    }

    /** Present 工具提交给聊天 UI 的富内容项。 */
    data class PresentItem(
        val kind: String,
        val title: String?,
        val caption: String?,
        val content: String,
        val paths: List<String>,
    )

    /** 一条待办（TodoWrite 的 todos 数组元素，core 侧形态）。 */
    data class TodoEntry(
        val content: String,
        val status: String = "pending",
    )

    /** 一个消息气泡。 */
    data class Bubble(
        /** `user` / `assistant`。 */
        val role: String,
        val text: String,
        val messageId: String,
        /** 该消息的思考过程（TurnEnd 时从 State.thinking 定型过来；历史恢复无此项）。 */
        val thinking: String = "",
        val toolCards: List<ToolCard> = emptyList(),
        /**
         * 该消息附带的图片本地路径（第20批）。
         * 只给 UI 渲染缩略图用 —— 模型侧的图走 Message.content，
         * 落盘历史里没有这些路径（附件 cache 7 天清理，历史恢复后图挂了
         * 显示占位即可，不影响对话）。
         */
        val images: List<String> = emptyList(),
    ) {
        val isUser: Boolean get() = role == Message.ROLE_USER
    }

    /** 一张工具体。 */
    data class ToolCard(
        val id: String,
        val name: String,
        /** 折叠态一行参数预览（由 agent 层格式化，见 `AgentEvent.ToolStart`）。 */
        val preview: String = "",
        /** 完整入参 JSON（ToolDiffView 展开渲染时按字段取；空 = 非 ToolStart 来源）。 */
        val input: String = "",
        /** 是否还在跑。 */
        val running: Boolean = false,
        val isError: Boolean = false,
        /** 进度文本（覆盖式）。 */
        val progress: String = "",
        /** 结果（已截断）。 */
        val result: String = "",
    )

    companion object {
        /**
         * 一个 goal turn 的最长等待（30 分钟）。
         *
         * 只是**兜底**：正常一轮几分钟内结束。设这么长是因为手机上跑
         * 多文件任务确实可能很久，而误杀一轮的代价（目标中途断掉）
         * 比多等一会儿大。真正该用超时保护的是 Agent 自己的 stream watchdog。
         */
        private const val GOAL_TURN_TIMEOUT_MS = 30L * 60 * 1000

        /**
         * 装配 + 建会话（UI 一行接入）。
         *
         * ```kotlin
         * val session = ChatSession.create(
         *     storage = FileAppStorage(context.filesDir),
         *     registry = registry,
         *     toolRunner = executor,
         *     scope = lifecycleScope,
         * )
         * ```
         *
         * @return 会话；配置无效（没 Provider / 没 key）时返回 null，UI 应引导去设置页
         */
        fun create(
            storage: AppStorage,
            registry: ToolRegistry,
            toolRunner: ToolRunner,
            scope: CoroutineScope,
            /** Android Context（问题29：读 assets/system-prompt.md）。null = 简短兜底。 */
            context: android.content.Context? = null,
            imageScaler: com.ccm.app.core.image.ImageScaler? = null,
            cwd: String = "/",
            /** 会话 id（恢复旧会话时传，空 = 新建）。**Agent 与存盘共用这一个**。 */
            sessionId: String = "",
            /**
             * 运行模式状态（deep / plan / watch）。
             *
             * **必须传进程级单例**（AppGraph.modes）—— 模式工具持有的是
             * ToolsBootstrap 那一刻传入的引用，这里再传一个新建的就会
             * 「工具写 A、主循环读 B」静默失效。详见 AppGraph.modes 注释。
             */
            modes: com.ccm.app.core.agent.ModeState = com.ccm.app.core.agent.ModeState(),
            /**
             * 自动记忆提取器（**必须传进程级单例** AppGraph.autoMemory）。
             *
             * 理由同 [modes]：Memory 工具持有的是 ToolsBootstrap 那一刻的实例，
             * 这里传新的就会让「主 Agent 写记忆 → 本轮跳过提取」的互斥失效。
             */
            autoMemory: com.ccm.app.core.memory.AutoMemory? = null,
        ): ChatSession? {
            val cfg = AppConfig.load(storage.configFile).config
            // 先把 id 定下来 —— Agent 侧（工具/hooks/子 Agent 归属）和存盘侧
            // 必须用同一个，否则「工具里拿不到会话 id」这类错位会静默发生
            val sid = sessionId.ifBlank { SessionStore(storage).newSessionId() }

            val container = AppContainer.build(
                storage = storage,
                registry = registry,
                toolRunner = toolRunner,
                config = cfg,
                context = context,
                imageScaler = imageScaler,
                cwd = cwd,
                sessionId = sid,
                modes = modes,
                autoMemory = autoMemory,
            ) ?: return null

            // ★ B3 联动：恢复已有会话时把文件里的 title 带给 SessionAuto ——
            //   否则 sessionAuto.title=null，第一次 markDirty 落盘就把
            //   /rename 设过的标题覆盖成 null。
            val existingTitle = try { SessionStore(storage).loadTitle(sid) } catch (_: Throwable) { null }
            container.attachSessionAuto(scope, sessionId = sid, existingTitle = existingTitle)
            return ChatSession(container, scope)
        }
    }
}
