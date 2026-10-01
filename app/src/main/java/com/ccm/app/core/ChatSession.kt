package com.ccm.app.core

import com.ccm.app.core.agent.AgentEvent
import com.ccm.app.core.agent.AgentLoop
import com.ccm.app.core.provider.AppConfig
import com.ccm.app.core.session.Message
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
    fun compactNow(): String {
        if (isRunning) return "正在执行任务，等这轮结束再压缩。"
        val history = container.agentLoop.getHistory()
        if (history.size < 6) return "历史仅 ${history.size} 条，无需压缩。"
        val r = container.compactor.microCompact(history)
        if (!r.changed) return "无可回收的旧工具输出（最近的都在保护区）。"
        container.agentLoop.setHistory(r.messages)   // MicroResult.messages = 压缩后列表
        return "已压缩：回收约 ${r.reclaimedTokens} tokens（截断了旧工具输出）。"
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
        _state.value = _state.value.copy(
            bubbles = _state.value.bubbles + userBubble,
            streaming = "",
            running = true,
            toolCards = emptyList(),
            error = null,
            draft = "",   // 发出去就清空输入框（Web 行为）
        )
        markDirty()   // B3：用户消息进历史 → 待落盘

        runningJob = scope.launch {
            // imagePaths 空 = 原路径，零行为变化（第18批向后兼容点）
            collectEvents(container.agentLoop.run(text, imagePaths))
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

    /** 从历史恢复（`/resume`）。 */
    fun loadHistory(messages: List<Message>) {
        container.agentLoop.setHistory(messages)
        _state.value = State(
            bubbles = messages.map { m ->
                Bubble(
                    role = m.role,
                    text = m.text,
                    messageId = "history-${m.timestamp}",
                )
            }
        )
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
        var currentMessageId = ""
        // 思考流（与上面的正文流独立：先想后说，两个流交错）
        var thinkingBuf = ""
        var currentThinkingId = ""

        try {
            events.collect { ev ->
                when (ev) {
                    is AgentEvent.TextDelta -> {
                        // messageId 变了 → 上一轮的半截作废（重试场景，见 AgentEvent 注释）
                        if (ev.messageId != currentMessageId) {
                            currentMessageId = ev.messageId
                            streaming = ""
                        }
                        streaming += ev.text
                        _state.value = _state.value.copy(streaming = streaming, toolCards = toolCards.toList())
                    }

                    is AgentEvent.ReasoningDelta -> {
                        // ★ 2026-09-27：原来是 `暂不进状态` 直接丢弃 ——
                        //   AssistantThinkingChain 739 行组件因此永远空转。
                        //   现在与 TextDelta 同模式：messageId 变了重开一轮。
                        if (ev.messageId != currentThinkingId) {
                            currentThinkingId = ev.messageId
                            thinkingBuf = ""
                        }
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

                    is AgentEvent.ToolProgress -> {
                        val i = toolCards.indexOfLast { it.running }
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
                        // 一轮结束 → 把流式内容定型成气泡
                        if (streaming.isNotBlank()) {
                            _state.value = _state.value.copy(
                                bubbles = _state.value.bubbles + Bubble(
                                    role = Message.ROLE_ASSISTANT,
                                    text = streaming,
                                    messageId = currentMessageId,
                                    thinking = thinkingBuf,
                                ),
                                streaming = "",
                                thinking = "",   // 已定型进气泡，清流式字段
                                toolCards = toolCards.toList(),
                            )
                            streaming = ""
                            markDirty()   // B3：assistant 消息进历史 → 待落盘
                        }
                    }

                    is AgentEvent.Usage -> {
                        _state.value = _state.value.copy(
                            inputTokens = ev.inputTokens,
                            outputTokens = ev.outputTokens,
                        )
                    }

                    AgentEvent.Done -> {
                        // 收尾：把残留的流式内容定型（没收到 TurnEnd 的情况）
                        if (streaming.isNotBlank()) {
                            _state.value = _state.value.copy(
                                bubbles = _state.value.bubbles + Bubble(
                                    role = Message.ROLE_ASSISTANT,
                                    text = streaming,
                                    messageId = currentMessageId,
                                ),
                                streaming = "",
                            )
                        }
                        markDirty()   // B3：兜底（残留在流里的内容定型也落盘）
                        _state.value = _state.value.copy(running = false, toolCards = toolCards.toList())

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
            _state.value = _state.value.copy(running = false)
        } catch (e: Throwable) {
            _state.value = _state.value.copy(running = false, error = e.message ?: "未知错误")
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
    ) {
        /** 是否为空对话（UI 据此显示欢迎页）。 */
        val isEmpty: Boolean get() = bubbles.isEmpty() && streaming.isBlank()
    }

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
                imageScaler = imageScaler,
                cwd = cwd,
                sessionId = sid,
                modes = modes,
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
