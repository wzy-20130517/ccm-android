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
    fun send(text: String) {
        if (text.isBlank()) return
        if (isRunning) return

        val userBubble = Bubble(
            role = Message.ROLE_USER,
            text = text,
            messageId = "user-${System.currentTimeMillis()}",
        )
        _state.value = _state.value.copy(
            bubbles = _state.value.bubbles + userBubble,
            streaming = "",
            running = true,
            toolCards = emptyList(),
            error = null,
            draft = "",   // 发出去就清空输入框（Web 行为）
        )

        runningJob = scope.launch {
            collectEvents(container.agentLoop.run(text))
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

    // ═════════════════════════ 事件 → 状态 ═════════════════════════

    private suspend fun collectEvents(events: Flow<AgentEvent>) {
        val toolCards = mutableListOf<ToolCard>()
        var streaming = ""
        var currentMessageId = ""

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
                        // 思考内容暂不进状态（UI 需要时用事件流自己接）
                    }

                    is AgentEvent.ToolStart -> {
                        toolCards += ToolCard(id = ev.id, name = ev.name, preview = ev.inputPreview, running = true)
                        _state.value = _state.value.copy(toolCards = toolCards.toList())
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
                                ),
                                streaming = "",
                                toolCards = toolCards.toList(),
                            )
                            streaming = ""
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
                        _state.value = _state.value.copy(running = false, toolCards = toolCards.toList())
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
    ) {
        /** 是否为空对话（UI 据此显示欢迎页）。 */
        val isEmpty: Boolean get() = bubbles.isEmpty() && streaming.isBlank()
    }

    /** 一个消息气泡。 */
    data class Bubble(
        /** `user` / `assistant`。 */
        val role: String,
        val text: String,
        val messageId: String,
    ) {
        val isUser: Boolean get() = role == Message.ROLE_USER
    }

    /** 一张工具体。 */
    data class ToolCard(
        val id: String,
        val name: String,
        /** 折叠态一行参数预览（由 agent 层格式化，见 `AgentEvent.ToolStart`）。 */
        val preview: String = "",
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
            ) ?: return null

            container.attachSessionAuto(scope, sessionId = sid)
            return ChatSession(container, scope)
        }
    }
}
