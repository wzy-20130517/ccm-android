package com.ccm.app.core

import com.ccm.app.core.agent.AgentEvent
import com.ccm.app.core.agent.AgentLoop
import com.ccm.app.core.compact.AutoCompact
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

    /**
     * 暴露 AppContainer（2026-10-06 加）。
     *
     * 【为什么需要】`AppGraph.container` 是个长期为 null 的属性 —— 它的
     * 设计意图是「装配完成后持有当前 AppContainer」，但 ChatSession 的
     * container 是 private，AppGraph 拿不到、赋值代码从未写出。
     * 后果（三条功能链静默全废）：
     *   1. 子 Agent 观察窗：AgentStatus/AgentOutput/AgentStop 永远报
     *      「观察器未接入」（attachSubAgents 那块因 container==null 不执行）
     *   2. /btw 永远失败（container==null 判断恒真）
     *   3. 热更新永远报失败（container?.refreshApi() 短路成 null）
     *
     * 加这个 getter 后，AppGraph 能在创建 session 时把 container 存下来。
     */
    val appContainer: AppContainer get() = container

    private val _state = MutableStateFlow(State())
    /** UI 订阅这个渲染。 */
    val state: StateFlow<State> = _state.asStateFlow()

    /** 当前正在跑的 Flow（用于取消）。 */
    private var runningJob: kotlinx.coroutines.Job? = null

    /** 是否正在跑。 */
    val isRunning: Boolean get() = runningJob?.isActive == true

    /**
     * 上次对话流水位提示的等级（同级去重：升级才再打，压回 OK 后恢复）。
     * 对齐 CLI `index.mjs` 的 `lastWaterLevel`。
     */
    @Volatile
    private var lastWaterLevel: AutoCompact.Level = AutoCompact.Level.OK

    /** beforeToolCall 压缩防抖：上次实际执行检查的时间戳。 */
    @Volatile
    private var lastToolCompactAt: Long = 0L

    init {
        // beforeToolCall 工具前压缩检查点接线（差距报告 Top#3）——
        // AgentLoop.executeTools 每批工具分发前回调到这里，条件/防抖判断在
        // maybeAutoCompactFromTool。与 run 结束后的自动压缩并存，不是替换。
        container.agentLoop.beforeToolCallHook = { maybeAutoCompactFromTool() }
    }

    /**
     * 当前上下文水位 —— blocking/error/warning/ok 判定的唯一入口。
     *
     * 每次先用当前配置刷一遍 `autoCompact.maxContext` —— 它是装配时快照，
     * 用户在 /context 或设置页改了 maxContextTokens 后不刷新会按旧值判
     * （阈值热更新是另一条待办，这里只保 maxContext 同步）。
     *
     * @param tokens 判定基准，默认取上次请求的真实 prompt_tokens；
     *   contextReport 兜底估算时会传估算值。
     */
    private fun currentWater(
        tokens: Int = container.agentLoop.lastPromptTokens,
    ): AutoCompact.WaterLevel {
        val mc = container.config.maxContextTokens
        if (mc > 0) container.autoCompact.maxContext = mc
        return container.autoCompact.waterLevel(tokens)
    }

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
    /**
     * 上下文用量报告（2026-10-06 对齐 CLI 的 `cmdContext`）。
     *
     * 【原来 APK 的 /context 只有 5 行裸数字】（消息条数/最近输入 token/
     * 最近输出 token/合计）—— 用户看不出「离上限多远、该不该压缩」。
     * CLI 的完整版有：当前上下文 token（真实值 + 估算兜底）、
     * 进度条、水位提示（充裕/过半/接近上限建议 compact）。
     *
     * 照搬 CLI 的输出结构（cmd-extensions.mjs:64-135）。
     */
    fun contextReport(): String {
        val history = container.agentLoop.getHistory()
        val msgCount = history.size
        // 当前上下文 = 最近一次 API 返回的 prompt_tokens（每次请求带全量历史）
        var curTokens = container.agentLoop.lastPromptTokens
        var estimated = container.agentLoop.isApproxPromptTokens   // setHistory 估算标记（~ 前缀）
        if (curTokens <= 0 && msgCount > 0) {
            // 兜底估算：重启恢复会话后 lastPromptTokens 还是 0，
            // 直接显示 0 会让人以为上下文空了。按字符数粗估（中英混合约 2 字符/token）。
            var chars = 0
            history.forEach { m ->
                m.content.forEach { b ->
                    when (b) {
                        is com.ccm.app.core.session.ContentBlock.Text -> chars += b.text.length
                        is com.ccm.app.core.session.ContentBlock.ToolUse -> chars += b.input.toString().length
                        is com.ccm.app.core.session.ContentBlock.ToolResult -> chars += b.content.length
                        is com.ccm.app.core.session.ContentBlock.Image -> chars += 3000   // 图片粗算
                    }
                }
            }
            curTokens = chars / 2
            estimated = curTokens > 0
        }
        val maxTokens = container.config.maxContextTokens.takeIf { it > 0 } ?: 1_000_000
        val pct = if (maxTokens > 0) (curTokens * 100 / maxTokens).coerceAtMost(100) else 0
        val filled = (pct / 5).coerceAtMost(20)
        val bar = "█".repeat(filled) + "░".repeat(20 - filled)
        // 水位口径与对话流提示统一（三层：剩 33K/20K/13K），避免 /context 一套、
        // 发消息时另一套双份提示；50% 档保留（正常范围内的长度提醒）。
        val water = currentWater(curTokens)
        val remainK = water.remainTokens.coerceAtLeast(0) / 1000
        val status = when (water.level) {
            AutoCompact.Level.BLOCKING -> "✗ 上下文已满（剩约 ${remainK}K），请先 /compact"
            AutoCompact.Level.ERROR -> "⚠ 上下文剩约 ${remainK}K —— 快到硬上限了，建议 /compact"
            AutoCompact.Level.WARNING -> "· 上下文剩约 ${remainK}K —— 接近上限时可以 /compact"
            AutoCompact.Level.OK -> when {
                curTokens > maxTokens * 0.5 -> "· 使用过半，注意长度"
                else -> "✓ 上下文充裕"
            }
        }
        val fmt = { n: Int -> "%,d".format(n) }
        return buildString {
            append("**上下文使用量**\n\n")
            append("- 消息条数：${fmt(msgCount)}\n")
            append("- 当前上下文：${if (estimated) "~" else ""}${fmt(curTokens)} tokens\n")
            append("- 上限：${fmt(maxTokens)}\n\n")
            append("`$bar` $pct%\n\n")
            append(status)
            if (estimated) append("\n\n_（~ = 估算值，发一条消息后显示真实值）_")
        }
    }

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
        // 判据：micro 回收量低于阈值（说明大头在对话本体，micro 治不了）
        // —— 用结构化字段而非文案匹配（文案改了行为不该变）。
        // 阈值 2000 tokens：低于它说明截断工具输出没解决问题，
        // 需要摘要对话本体。CLI 的同款判据是「压力仍 >70% 才升级」，
        // 这里用回收量做等价近似（压力值 APK 侧拿不到实时值）。
        val needSummary = lastMicroReclaimed < 2000
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
        if (history.size < 6) {
            // ⚠️ 也要重置字段 —— 否则 compactNowSuspend 的判据会读到
            // 上一次调用的陈旧值（历史太短时不该升级摘要）。
            lastMicroReclaimed = 0
            return "历史仅 ${history.size} 条，无需压缩。"
        }

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
        if (!r.changed) {
            lastMicroReclaimed = 0
            return "无可回收的旧工具输出（最近的都在保护区）。"
        }
        container.agentLoop.setHistory(r.messages)   // MicroResult.messages = 压缩后列表
        lastMicroReclaimed = r.reclaimedTokens
        return buildString {
            append("已压缩：回收约 ${r.reclaimedTokens} tokens（截断了旧工具输出）。")
            if (backupName != null) {
                append("\n\n_压缩前备份：`compact-trash/$backupName`（可用 `/compact-trash` 查看）_")
            }
        }
    }

    /**
     * 上次 microCompact 回收的 token 数（结构化字段，供 compactNowSuspend
     * 判断「要不要升级到真摘要」）。
     *
     * 【为什么不用文案匹配】原来判据是
     *   `result.contains("无可回收") || result.contains("回收约 0")`
     * —— 字符串匹配，文案一改就失效（改个措辞行为就变了）。
     * 用数值字段：回收量低于阈值就升级摘要。
     */
    @Volatile
    private var lastMicroReclaimed: Int = 0

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
        //
        // 【2026-10-11 修「重发后图片消失」】
        // 原来只传 `target.text` —— 图片信息（Bubble.images）丢了，
        // 重发的消息变成纯文本。用户报「选择一张图片发出去，点重新发送，图会消失」。
        //
        // 从**气泡**取图片路径（不是从历史 —— Message 结构里没存图片，
        // 图片只在 Bubble 上）。先按 messageId 精确匹配，兜底按文本匹配。
        // 过滤掉**已被清理**的图片（Android 系统随时可能清 cacheDir，
        // 7 天清理也在这里跑）。路径不存在的图直接丢弃 —— 传下去只会
        // 在发送时失败，用户看到的是「图又没了」却不知道为什么。
        val images: List<String> = (
            _state.value.bubbles.firstOrNull { it.messageId == messageId }?.images
                ?: _state.value.bubbles.lastOrNull { it.role == Message.ROLE_USER && it.text == target.text }?.images
                ?: emptyList()
            ).filter { java.io.File(it).exists() }
        send(target.text, images)
    }

    /**
     * 编辑用户消息并重发（2026-10-11 加，对齐 Web 的 `handleEditSave`）。
     *
     * ## 语义
     *
     * 与 [resendFrom] 相同（截断该消息及其之后的所有内容，重新跑），
     * 唯一区别是**用新的文本**。Web 的实现也是这样：
     * `deleteMessagesFrom` 截断 → 用 `editingContent` 重发。
     *
     * ## 图片
     *
     * 保留原消息的图片（用户编辑的通常只是文字）。Web 也一样
     * （`extractMessageAttachments(msg)` 取出原附件继续用）。
     *
     * ## 为什么不复用 resendFrom
     *
     * `resendFrom` 从**历史**里取 `target.text` —— 而编辑要用的新文本
     * 不在历史里。所以单独实现：截断逻辑相同，但文本由调用方给。
     */
    fun editAndResend(messageId: String, newText: String) {
        if (isRunning) return
        if (newText.isBlank()) return

        val history = container.agentLoop.getHistory()
        val idx = history.indexOfLast { m ->
            m.role == Message.ROLE_USER && messageId.endsWith("${m.timestamp}")
        }
        val target = if (idx >= 0) history[idx] else {
            val b = _state.value.bubbles.firstOrNull { it.messageId == messageId }
            if (b == null) return
            val i2 = history.indexOfLast { m -> m.role == Message.ROLE_USER && m.text == b.text }
            if (i2 < 0) return
            history[i2]
        }
        val cut = history.indexOf(target).takeIf { it >= 0 } ?: return

        // 取原消息的图片（编辑通常只改文字，图保留）—— 从气泡取，
        // 与 resendFrom 同一策略（Message 结构里不存图，只在 Bubble 上）。
        val images: List<String> = (
            _state.value.bubbles.firstOrNull { it.messageId == messageId }?.images
                ?: emptyList()
            ).filter { java.io.File(it).exists() }

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
        // ③ 用新文本重发
        send(newText, images)
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
        // 【2026-10-07】补 SessionEnd hook（对齐 CLI `index.mjs:4314`）——
        // 原来 APK 全项目零触发（差距报告 #18）。触发点是**本方法**：
        // 会话切换（openSession）、App 退出（AppGraph.shutdown）、rebuild
        // 三条路径都经过 dispose。异步 fire-and-forget（不阻塞释放流程，
        // fail-open；与 SessionStart 同款）。
        try {
            if (com.ccm.app.AppGraph.toolsResult?.hooks != null) {
                scope.launch {
                    try { triggerHook("SessionEnd", reason = "dispose") } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
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
    fun send(
        text: String,
        imagePaths: List<String> = emptyList(),
        /** 附带文件（非图片）—— 与 [text] 里拼的附件元信息同源，UI 用它显示文件卡。 */
        filePaths: List<String> = emptyList(),
    ) {
        // 【2026-10-11 修「仅带一张图无文本无法发送」】
        //
        // 原来只判 `text.isBlank()` 就 return —— 纯图消息被静默丢弃。
        // 而 UI 层（InputBar 的发送键 enabled）是
        // `value.isNotBlank() || attachedPaths.isNotEmpty()` —— **允许纯图**。
        // 两层判据不一致：按钮亮着、点了没反应（最糟的一种不一致）。
        //
        // 修法：有图也算有效输入。对齐 Web（那边 `runMessage` 只在
        // `!trimmed && !attachments.length` 时才拒绝）。
        if (text.isBlank() && imagePaths.isEmpty()) return
        // 【2026-10-06 mid-turn steering】运行中不再**静默丢弃** ——
        // 入 steering 队列，AgentLoop 下一轮调用前注入（不打断当前工具批次）。
        // 对齐 CLI/Web：用户长任务中想补充/纠偏时不用先打断再重说。
        if (isRunning) {
            container.agentLoop.pushSteering(text)
            // 上屏让用户看到「我说的话被收下了」（原来无任何反馈，像石沉大海）
            _state.value = _state.value.copy(
                bubbles = _state.value.bubbles + Bubble(
                    role = Message.ROLE_USER,
                    text = text,
                    messageId = "steer-${System.currentTimeMillis()}",
                    images = imagePaths,
                ),
            )
            return
        }

        // ── 三层水位守门（对齐 CLI index.mjs:6261-6297）────────────────────────
        //
        // blocking（剩 <13K）：**不发请求**，直接在对话里生成错误气泡 ——
        // 防止 413 撞墙浪费一次全量请求。warning/error：对话流插一条黄字提示
        // （不阻塞，用户自己决定要不要 /compact），同级去重 —— 升级才再打、
        // 压回 ok 后恢复（CLI 同款 lastWaterLevel 语义）。
        val water = currentWater()
        if (water.level == AutoCompact.Level.BLOCKING) {
            // 用户消息照常上屏（他得看到自己发了什么），但不进 agent 历史
            _state.value = _state.value.copy(
                bubbles = _state.value.bubbles + Bubble(
                    role = Message.ROLE_USER,
                    text = text,
                    messageId = "user-${System.currentTimeMillis()}",
                    images = imagePaths,
                ),
            )
            val remainK = water.remainTokens.coerceAtLeast(0) / 1000
            injectNotice("✗ 上下文已满（剩约 ${remainK}K），这次没有发给模型 —— 请先 /compact 再发消息")
            saveForced()
            return
        }
        if (water.level == AutoCompact.Level.ERROR || water.level == AutoCompact.Level.WARNING) {
            if (water.level != lastWaterLevel) {
                val remainK = water.remainTokens.coerceAtLeast(0) / 1000
                injectNotice(
                    if (water.level == AutoCompact.Level.ERROR) {
                        "⚠ 上下文剩约 ${remainK}K —— 快到硬上限了，建议 /compact"
                    } else {
                        "· 上下文剩约 ${remainK}K —— 接近上限时可以 /compact"
                    }
                )
            }
            lastWaterLevel = water.level
        } else {
            lastWaterLevel = AutoCompact.Level.OK   // 压回正常 → 恢复，下次升级再打
        }

        val userBubble = Bubble(
            role = Message.ROLE_USER,
            text = text,
            messageId = "user-${System.currentTimeMillis()}",
            images = imagePaths,
            files = filePaths,
        )
        // 对齐 Web 的自动命名：首条用户消息立即作为本地标题兜底，
        // 避免发送后仍显示“新对话”，并交给 SessionAuto 随本轮保存落盘。
        //
        // 【2026-10-12 修】原来直接用 text —— 而带附件时 text 里拼了
        // 「[附件: 名] 已保存到: 路径 …」（给模型看的元信息），
        // 于是**会话标题变成一串路径**。
        // 先 stripMetaTags 剥掉附件段，再取标题。
        if (container.sessionAuto?.title.isNullOrBlank()) {
            container.sessionAuto?.title = com.ccm.app.core.session
                .stripMetaTags(text)
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(48)
                .ifBlank { "新对话" }
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
            // 【2026-10-09】通知 Clawd 悬浮窗：任务开始了（切 THINKING、清上轮气泡）。
            // 放在 launch 内而不是 send 里，保证与 events 转发同协程 —— 顺序确定。
            try { com.ccm.app.core.clawd.ClawdBus.setRunning(true) } catch (_: Throwable) {}
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
            //
            // 【2026-10-11 加 finally 兜底】`setRunning(false)` 原来只在
            // AgentEvent.Done 分支里调 —— 那条路径要求**流正常走到 Done**。
            // 中断（Ctrl+C / 异常 / 流断开）时 Done 不会到 → Clawd 的
            // running 永远为 true → 悬浮窗的状态收回逻辑全部失效 →
            // **螃蟹永远停在最后一个动画上**（用户报「一直循环播放一个动画」）。
            //
            // finally 保证：不管怎么结束（正常/异常/取消），一定通知 Clawd。
            try {
                collectEvents(container.agentLoop.run(effectiveText, imagePaths))
            } finally {
                try { com.ccm.app.core.clawd.ClawdBus.setRunning(false) } catch (_: Throwable) {}
            }

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
     * beforeToolCall 检查点（差距报告 Top#3）：每批工具执行前查水位，超阈值先压。
     *
     * 与 run 结束后的 [maybeAutoCompact] **并存**（不是替换）：单轮几十个工具的
     * 长任务中途就撞 400，等 run 结束已晚。30s 防抖 —— 一次 run 里压过一次就够
     * （对齐 CLI `index.mjs` 的 `_lastAutoCompactAt`）。钩子由 AgentLoop.executeTools
     * 在每批工具分发前触发。
     */
    private suspend fun maybeAutoCompactFromTool() {
        val ac = container.autoCompact
        if (!ac.isEnabled || ac.isTripped) return          // 未启用/断路：短路（默认关闭）
        val hist = container.agentLoop.getHistory()
        val tokens = container.agentLoop.lastPromptTokens
        if (!ac.shouldCompact(hist, tokens)) return        // 没超阈值不动（不消耗防抖额度）
        val now = System.currentTimeMillis()
        if (now - lastToolCompactAt < 30_000L) return       // 30s 防抖：别每个工具都压
        lastToolCompactAt = now                            // 先占位：压缩期间的后续批次不再进来
        try {
            maybeAutoCompact()
        } catch (_: Throwable) {
            // 压缩失败不能把工具批次带崩（钩子侧兜底）
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
        // 【2026-10-11 用 mapIndexedNotNull 保证 messageId 唯一】
        //
        // 原来用 mapNotNull + `"history-${timestamp}"` —— 老会话数据的
        // timestamp 可能是 0（早期版本没存时间），于是多条消息的 messageId
        // 全变成 `history-0`：
        //   · LazyColumn 的 key 撞车（曾导致点老对话闪退，当时用
        //     `"${index}-${bubble.key}"` 绕过 —— 但那个绕法有副作用，见下）
        //   · 点击/重发/删除会命中错误的那条
        //
        // 现在拼上**历史索引**（在本次恢复内天然唯一），messageId 就唯一了。
        //
        // ⚠️ 为什么不在 LazyColumn 的 key 里拼 index：那样每次新消息插入
        //    （index 0 是最新），所有已有 item 的 index 都 +1 → key 全变 →
        //    LazyColumn 认为整个列表都是新内容 → **滚动位置丢失/漂移**。
        //    用户报的「上滑脱离跟随后界面仍被下拉」就是这个（锚定失效）。
        //    key 必须**稳定且唯一**，唯一性在这里（数据层）保证才对。
        // 【用 mapIndexed + filterNotNull，不用 mapIndexedNotNull】
        //
        // 后者要求显式类型参数 `<Message, Bubble>`，而 lambda 里是
        // `when` 表达式 + 多个 `return@` 分支 —— 实测编译报
        //   "actual type is 'kotlin.Any?', but 'kotlin.Nothing' was expected"
        // （Kotlin 在混合表达式/语句的 lambda 里推断不稳）。
        //
        // mapIndexed 返回 List<Bubble?>，再 filterNotNull 过滤 —— 语义等价，
        // 类型推断直白，不依赖任何显式标注。
        val restored: List<Bubble> = messages.mapIndexed { histIdx, message ->
            // 【2026-10-06】hidden 消息（轮次提醒/空响应提示/队友注入等
            // 系统内部消息）不进气泡 —— 它们不是用户说的话，显示出来会
            // 让人困惑（「我什么时候发过这个？」）。
            if (message.hidden) return@mapIndexed null
            when (message.role) {
                Message.ROLE_ASSISTANT -> {
                    // 【2026-10-10 修正文/工具交错】按 content 的真实顺序算 textBefore。
                    //
                    // message.content 本来就是有序的 [Text, ToolUse, Text, ToolUse, …]
                    // （AgentLoop.appendToolResults 按输出顺序写入）——
                    // 遍历时累计正文，每个 ToolUse 记下「到它为止的正文」，
                    // 就是该工具的 textBefore。UI 据此把正文/工具切成
                    // 「正文1 → 工具1 → 正文2 → 工具2 → …」交错渲染。
                    //
                    // 原来这里用 filterIsInstance 直接抽 ToolUse（丢顺序信息），
                    // textBefore 永远空 → 历史恢复后只能「工具堆一起 + 正文全文」。
                    val cards = mutableListOf<ToolCard>()
                    var accumulated = ""
                    message.content.forEach { block ->
                        when (block) {
                            is ContentBlock.Text -> accumulated += block.text
                            is ContentBlock.ToolUse -> {
                                val result = resultsById[block.id]
                                cards += ToolCard(
                                    id = block.id,
                                    name = block.name,
                                    preview = block.input.toString().take(180),
                                    input = block.input.toString(),
                                    running = result == null,
                                    result = result?.content.orEmpty(),
                                    isError = result?.isError ?: false,
                                    textBefore = accumulated,
                                )
                            }
                            else -> {}
                        }
                    }
                    // 【2026-10-11】历史消息同样解析内联 artifact —— 否则重启后
                    // 老会话里用 `<cp_artifact>` 展示的内容会退回成正文里的标签文本
                    // （用户看到一串 `<cp_artifact title="…">`，比没有还糟）。
                    val (histText, histInlineDocs) = parseInlineArtifacts(message.text)
                    // 显式 return（不用 if 表达式）—— 理由同下方用户分支
                    if (histText.isBlank() && cards.isEmpty()) {
                        return@mapIndexed null
                    }
                    return@mapIndexed Bubble(
                        role = Message.ROLE_ASSISTANT,
                        text = histText,
                        messageId = "history-$histIdx-${message.timestamp}",
                        toolCards = cards,
                        // 历史消息同样推导文档 —— 否则重启后老会话里的
                        // Write/Edit 卡片全都不见了（对齐 Web：那边是从
                        // 消息对象的 toolCalls 实时算，不依赖额外落盘字段）
                        documents = deriveDocuments(cards) + histInlineDocs,
                    )
                }
                Message.ROLE_USER -> {
                    // 【2026-10-09 修「重启 APP 后老有空白用户气泡」】
                    //
                    // 判据必须与渲染一致 —— 渲染侧（MessageBubble）会把
                    // <image_resize_notice> 这类元信息标签剥掉再显示。
                    // 原来这里只看 `text.isNotBlank()`：vision 旁路注入的
                    // 「图片 + 缩放提示」消息（role=user）文本非空 → 生成气泡
                    // → 渲染时剥空 → 空白气泡。每次截图注入一条，所以「老有」。
                    //
                    // 现在用 stripMetaTags 剥完再判 —— 剥完还有内容才生成气泡。
                    //
                    // 【2026-10-11 恢复图片】原来注释写「历史里不存路径，
                    // 图片不还原」—— 那是当时的限制，不是设计。现在
                    // `Message.imagePaths` 会随会话落盘（见 AgentLoop.run），
                    // 这里读回来重建缩略图。
                    //
                    // 只保留**文件仍存在**的路径 —— 附件有 7 天清理，
                    // 老会话里的图可能已被删。路径失效时静默丢弃（气泡
                    // 退化成纯文本，与旧行为一致，不会显示破图占位）。
                    val visible = com.ccm.app.core.session.stripMetaTags(message.text)
                    val imgs = message.imagePaths.filter { java.io.File(it).exists() }
                    // 有图但正文被剥空时**也要生成气泡** —— 只发图不打字是
                    // 常见用法，此时 stripMetaTags 的结果可能是空串。
                    //
                    // 【为什么用显式 return 而不是 if 表达式】`if (…) Bubble(…)`
                    // 无 else 分支时 Kotlin 推断为 Unit，与 when 其他分支的
                    // Bubble? 冲突 → CI 报 "actual type is Any?, but Nothing
                    // was expected"。显式 `return@mapIndexed` 让每个分支
                    // 都明确返回 Bubble?，推断不再依赖表达式类型合并。
                    if (visible.isNotBlank() || imgs.isNotEmpty()) {
                        return@mapIndexed Bubble(
                            role = Message.ROLE_USER,
                            text = visible,
                            messageId = "history-$histIdx-${message.timestamp}",
                            images = imgs,
                        )
                    }
                    return@mapIndexed null
                }
                else -> null
            }
        }.filterNotNull()
        _state.value = State(bubbles = restored)
        // Web 在首轮消息后异步生成标题；APK 先用首条用户消息做稳定本地兜底。
        //
        // 【2026-10-12 修】同 send 里的问题 —— 直接取 text 会把
        // 「[附件: 名] 已保存到: 路径」这类元信息带进标题。
        // 先 stripMetaTags 剥掉。
        if (container.sessionAuto?.title.isNullOrBlank()) {
            messages.firstOrNull { it.role == Message.ROLE_USER }
                ?.text?.let { com.ccm.app.core.session.stripMetaTags(it) }
                ?.trim()?.replace(Regex("\\s+"), " ")
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

        try {
            events.collect { ev ->
                // 【2026-10-09】转发给 Clawd 悬浮窗总线（后台运行时显示吉祥物动画）。
                // 放在最前：任何分支提前 return/continue 都不影响它收到事件。
                // 总线内部对每个事件只做一次 enum 映射 + StateFlow 赋值，开销可忽略；
                // 没有悬浮窗时（App 在前台）只是更新几个内存值，无副作用。
                try {
                    com.ccm.app.core.clawd.ClawdBus.onAgentEvent(ev)
                } catch (_: Throwable) {}
                when (ev) {
                    is AgentEvent.TextDelta -> {
                        // 新 messageId 代表模型新一次响应（工具往返或重试）。
                        // 工具往返是同一轮，前面的正文必须并入累计，不能丢。
                        //
                        // 【2026-10-09 核对】TurnEnd 分支做了：
                        //   accumulatedText += streaming; streaming = "";
                        //   state.streaming = accumulatedText
                        // 之后 streaming 局部变量是**空串**（不是 accumulatedText）。
                        // 所以这里 `accumulatedText += streaming` 加的是空串 —— 无害。
                        // 真正要防的是**新 messageId 到来时 streaming 里有未并入内容**
                        //（上一轮 API 输出到一半就被新响应顶替的罕见情况）。
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
                        //
                        // 【2026-10-06】thinkingBuf 是**整轮 run 的累积**（跨工具往返），
                        // 与 AgentLoop 每轮新建的 reasoningSb 不同 —— 只有累积值才能
                        // 跟 UI 收到的 thinking 串做前缀匹配（见 ToolStart 里的说明）。
                        // 原来这里记了个 currentThinkingId 但从未使用，已删。
                        thinkingBuf += ev.text
                        _state.value = _state.value.copy(thinking = thinkingBuf)
                    }

                    is AgentEvent.ToolStart -> {
                        toolCards += ToolCard(
                            id = ev.id, name = ev.name, preview = ev.inputPreview,
                            // 【2026-10-10 交错渲染】工具前的正文 —— 转成**全局累积值**。
                            //
                            // ev.textBefore 是**本轮**（本次 API 响应）的正文快照
                            //（AgentLoop.textSoFar，每轮新建 textSb）。但 bubble.text
                            // 是跨轮累积的全文 —— 两者坐标系不同，直接存会让 UI 的
                            // 「增量切分」算错（第二轮的工具拿到的是轮内值，减第一轮
                            // 的全局值会得到负数或错位）。
                            //
                            // 全局值 = 之前轮已定格的正文（accumulatedText）+ 本轮快照。
                            // UI 据此按 `textBefore 增量` 把正文/工具切成交错段：
                            //   正文1 → 工具1 → 正文2 → 工具2 → … → 正文N+1
                            textBefore = accumulatedText + ev.textBefore,
                            // 同理带上思考 —— 思维链的时间线靠它排序。
                            //
                            // ⚠️ 用**本类的 thinkingBuf**（跨轮累积），不是
                            // ev.thinkingBefore（AgentLoop 的单轮快照）——
                            // 只有累积值才能跟 UI 拿到的 thinking 串做前缀匹配。
                            // AgentLoop 每轮新建 reasoningSb，它的快照是「本轮思考」，
                            // 与累积串对不上（前缀匹配必失败 → 每段都当新增 → 重复）。
                            thinkingBefore = thinkingBuf,
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
                            val rawText = (accumulatedText + streaming).trim()
                            // 【2026-10-11】内联 artifact：正文里的 `<cp_artifact title=… format=…>`
                            // 抽成文档卡片，正文里剥掉标记（对齐 Desktop 的 parseInlineArtifactDisplay）。
                            val (finalText, inlineDocs) = parseInlineArtifacts(rawText)
                            val finalThinking = thinkingBuf
                            // ⚠️【2026-10-11 坐标系对齐，别删】artifact 剥离会**改变正文长度**，
                            // 所以每个工具的 textBefore 必须做**同样的剥离** ——
                            // 否则 UI 的「已消费长度」（= 最后一个可见工具的 textBefore 长度）
                            // 与 finalText 不在同一坐标系：
                            //   · offset 越界 → UI 走兜底显示全文 → 与工具区的 textBefore
                            //     **重复显示**（正文出现两遍）
                            //   · 或 offset 偏小 → 切掉一段 → **正文丢失**
                            // 两者都是静默的（不报错、不崩溃），排查成本极高。
                            //
                            // 无 artifact 时 parseInlineArtifacts 走快速路径原样返回，零副作用。
                            val finalTools = toolCards.toList().map { card ->
                                if (card.textBefore.isBlank()) card
                                else card.copy(textBefore = parseInlineArtifacts(card.textBefore).first)
                            }
                            // 【2026-10-06】工具后的正文起点（对齐 Web 的 toolTextEndOffset）：
                            // 最后一个工具的 textBefore 长度 = 工具区显示的正文总量，
                            // 正文区从这里往后显示 —— 否则工具前的正文会显示两遍。
                            // 没有工具 → 0（正文区显示全文，兜底）。
                            val textEndOffset = finalTools.lastOrNull()?.textBefore?.length ?: 0
                            val nextState = _state.value.copy(
                                bubbles = if (finalText.isNotBlank() || finalThinking.isNotBlank() || finalTools.isNotEmpty()) {
                                    _state.value.bubbles + Bubble(
                                        role = Message.ROLE_ASSISTANT,
                                        text = finalText,
                                        messageId = currentMessageId.ifBlank { "turn-${System.currentTimeMillis()}" },
                                        toolTextEndOffset = textEndOffset,
                                        thinking = finalThinking,
                                        toolCards = finalTools,
                                        // 两个来源合并：文件推导（Write/Edit）+ 内联 artifact
                                        documents = deriveDocuments(finalTools) + inlineDocs,
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

                        // 【2026-10-09】Clawd 悬浮窗：整轮真正结束 → running=false。
                        // 悬浮窗据此决定「完成后自动收回 IDLE」（见 ClawdOverlayService
                        // 的 pulse 收集：HAPPY 播几秒，running 为 false 才回 IDLE）。
                        try { com.ccm.app.core.clawd.ClawdBus.setRunning(false) } catch (_: Throwable) {}

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
            val rawPartial = (accumulatedText + streaming).trim()
            // 中断的回复同样解析内联 artifact（用户可能正好停在文档生成到一半）
            val (partialText, partialInlineDocs) = parseInlineArtifacts(rawPartial)
            // ⚠️【2026-10-11 坐标系对齐，同 Done 分支】中断路径也必须剥离
            // textBefore 里的 artifact —— 否则 UI 的「已消费长度」用的是含
            // artifact 的旧偏移，与 partialText 不同坐标系 → 正文重复或丢失。
            val partialTools = toolCards.toList().map { card ->
                if (card.textBefore.isBlank()) card
                else card.copy(textBefore = parseInlineArtifacts(card.textBefore).first)
            }
            if (partialText.isNotBlank() || thinkingBuf.isNotBlank() || toolCards.isNotEmpty()) {
                val nextState = _state.value.copy(
                    bubbles = _state.value.bubbles + Bubble(
                        role = Message.ROLE_ASSISTANT,
                        text = partialText,
                        messageId = currentMessageId.ifBlank { "turn-${System.currentTimeMillis()}" },
                        thinking = thinkingBuf,
                        toolCards = partialTools,
                        documents = partialInlineDocs,
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
    ) {
        /** 是否为空对话（UI 据此显示欢迎页）。 */
        val isEmpty: Boolean get() = bubbles.isEmpty() && streaming.isBlank()
    }

    /**
     * 从一条消息的工具调用里**推导**出的文档（对齐 Web 的 `normalizeMessageDocuments`）。
     *
     * ## 为什么是「推导」而不是「工具主动推」
     * 老设计（Present 工具）要模型把完整 SVG/HTML 源码塞进工具参数 ——
     * 费 token、手机端容易截断。新设计与 Web 一致：
     * **Agent 只管写文件**（Write / Edit），前端扫一遍 toolCards 就知道
     * 哪些文件被写过、内容是什么，渲染成可点开的卡片。
     *
     * ## 两个来源（2026-10-11 加第二个）
     * 1. **文件推导**：[deriveDocuments] 扫 toolCards 的 Write/Edit
     * 2. **内联 artifact**：消息正文里的 `<cp_artifact title="…" format="…">…</cp_artifact>`
     *    （对齐 Desktop 的 `parseInlineArtifactDisplay`）—— 模型不必落盘，
     *    直接在回复里内联一段可展开的内容。解析后正文里的标记会被剥掉。
     *
     * @property path 文件路径（卡片标题取它的文件名）。内联 artifact 无路径 → 空串
     * @property content 最终内容（Write 的原文 + 后续 Edit 的替换结果）
     * @property inlineTitle 内联 artifact 的 title 属性（空 = 文件来源，用 [filename]）
     * @property inlineFormat 内联 artifact 的 format 属性（空 = 按扩展名推断）
     */
    data class DocumentEntry(
        val path: String,
        val content: String,
        val inlineTitle: String = "",
        val inlineFormat: String = "",
    ) {
        /** 文件名（取路径最后一段，兼容 `/` 与 `\`）。 */
        val filename: String
            get() = path.split('/', '\\').lastOrNull()?.takeIf { it.isNotBlank() } ?: path

        /** 卡片显示名：内联 artifact 用 title，文件来源用文件名。 */
        val displayName: String
            get() = inlineTitle.ifBlank { filename }
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
        /**
         * 最后一个工具结束时的正文长度（2026-10-06 加，对齐 Web 的
         * `toolTextEndOffset`）。
         *
         * 【为什么需要】工具期间的正文已经在各工具卡的 textBefore 里显示过了，
         * 正文区只能显示**最后一个工具之后**的那段 —— 否则同一段正文
         * 出现两遍（工具卡里一遍、正文区一遍）。
         * Web 的判据（MainContent.tsx:1136）：
         *   workText  = fullText.slice(0, offset)   ← 工具区
         *   finalText = fullText.slice(offset)      ← 正文区
         * 0 或 ≥ text.length = 没有「工具后正文」，正文区显示全文（兜底）。
         *
         * ⚠️ **2026-10-11 起 UI 渲染不再用它** —— 正文切分改由 UI 侧的
         * `finalTextAfterTools` 现场计算（见 MessageBubble.kt）。
         *
         * 原因：这里算的是**全部**工具（含 WebSearch/WebFetch 这类
         * UI 侧 `HiddenToolNames` 过滤掉的），而工具区只渲染可见工具。
         * 若最后一个是隐藏工具，用它切分会把「最后一个可见工具 → 隐藏工具」
         * 之间的正文切走，而那段在工具区**无处显示** → 正文静默消失
         *（与 2026-10-10「正文一突然消失」同类）。
         *
         * 字段保留：语义本身没错（「最后一个工具的 textBefore 长度」），
         * 留作数据记录。**不要拿它做 UI 的正文切分**。
         */
        val toolTextEndOffset: Int = 0,
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
        /**
         * 用户消息附带的**文件**路径（非图片，2026-10-12 加）。
         *
         * 与 [images] 分开是因为两者通道不同：
         *   · images → 多模态（模型直接看图）
         *   · files  → 文本随消息（文本类读内容，其他给路径）
         *
         * 【为什么需要】原来文件信息只存在于拼进 text 的
         * 「[附件: 名] 已保存到: 路径」里 —— 渲染时被 stripMetaTags 剥掉，
         * 于是**用户气泡里完全看不到自己发了什么文件**。
         */
        val files: List<String> = emptyList(),
        /**
         * 该消息里被**写过**的文件（从 toolCards 推导，见 [deriveDocuments]）。
         *
         * 【2026-10-11】替代原来的 `presentItems`。Present 工具下线后，
         * 可视内容不再是「工具推给 UI 的一段富文本」，而是「Agent 写的文件」——
         * UI 渲染成文档卡片，点开全屏预览（[com.ccm.app.ui.chat.DocumentPanel]）。
         *
         * 对齐 Web 的 `msg.documents`（MainContent.tsx 的 normalizeMessageDocuments）。
         */
        val documents: List<DocumentEntry> = emptyList(),
    ) {
        val isUser: Boolean get() = role == Message.ROLE_USER
    }

    /** 一张工具体。 */
    data class ToolCard(
        val id: String,
        val name: String,
        /** 折叠态一行参数预览（由 agent 层格式化，见 `AgentEvent.ToolStart`）。 */
        val preview: String = "",
        /**
         * 这个工具调用**之前**累积的正文（2026-10-06 加，对齐 Web）。
         *
         * UI 把它渲染在工具卡**上方** —— 还原「正文1→工具→正文2→工具」
         * 的真实输出顺序（Web `web/server.mjs:2520` 同款机制）。
         */
        val textBefore: String = "",
        /**
         * 这个工具调用**之前**累积的思考（2026-10-06 加）。
         *
         * UI 用它还原「思考→工具」的真实交错顺序。没有它时只能拿整轮
         * 思考拼接串去猜（按空行切段 + 找英文交接句），中文思考全不匹配
         * → 交替排列 → 顺序错乱（用户截图反馈的问题）。
         */
        val thinkingBefore: String = "",
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
         * 可预览的扩展名 —— 与 Web 的 `previewExts` 逐字对齐
         * （`MainContent.tsx` 的 `normalizeMessageDocuments`）。
         *
         * 不在这个集合里的（如 `.kt` / `.mjs` / `.py`）不生成卡片 ——
         * 它们内容太长，卡片点开也是纯文本墙，没必要占位。
         */
        private val PREVIEW_EXTS = setOf(
            "md", "txt", "html", "json", "xml", "yaml", "yml", "csv",
            // 【2026-10-11 加 svg】提示词里明确教了「矢量图 / SVG 动画 → .svg」，
            // 但这里没放行 —— 模型写了 simple.svg，卡片根本不出现（用户报
            // 「并没看见 svg 显示」）。DocumentPanel 早已支持 svg 渲染
            // （走 DocumentPreview 沙箱 WebView），只是入口被这行过滤掉了。
            "svg",
        )

        /**
         * 从工具卡列表推导文档（对齐 Web 的 `normalizeMessageDocuments`）。
         *
         * ## 算法（两遍扫描，与 Web 完全一致）
         * 1. **第一遍**：收集每个路径的 Write 内容（`file_path` + `content`），
         *    用 [LinkedHashMap] 记住首次出现的顺序（决定卡片顺序）。
         * 2. **第二遍**：把后续的 Edit / MultiEdit 应用到已收集的内容上
         *    （`replace(old, new)`，对齐 JS 的 `replaceAll`）。
         * 3. 按扩展名过滤后产出 [DocumentEntry]。
         *
         * ## 为什么必须两遍
         * 模型常写成「Write 建文件 → Edit 改几处」，只扫 Write 会拿到**初始版本**
         * （用户点开看到的是没改过的内容）。反过来只扫 Edit 更糟 ——
         * 没有 Write 垫底，Edit 无处可施。
         *
         * ## 为什么只认已 Write 过的路径
         * Edit 一个**没在本条消息里 Write 过**的文件（改的是历史遗留文件）时，
         * 我们手里没有它的完整内容 —— 拿 `old_string` 反推全量是不现实的。
         * Web 的做法就是跳过（`if (current != null)`），这里保持一致：
         * 宁可少一张卡，不要给用户看残缺内容。
         *
         * ## MultiEdit 的两种入参形态都要认
         * - **`edits` 数组**（APK 自己的 schema：`{file_path, edits:[{old_string, new_string}]}`）
         * - **顶层 `old_string` / `new_string`**（Web / 其它端可能这么传）
         *
         * 只认其中一种的话，另一种形态的编辑会被静默漏掉 —— 卡片显示的还是
         * 改之前的旧内容，且没有任何报错。
         *
         * @param cards 一条消息的全部工具卡（含进行中的；只看 `input` 字段）
         */
        fun deriveDocuments(cards: List<ToolCard>): List<DocumentEntry> {
            if (cards.isEmpty()) return emptyList()

            // 路径 → 当前内容（LinkedHashMap = 保插入序，卡片按 Write 顺序排）
            val fileContents = LinkedHashMap<String, String>()

            // 第一遍：Write 的初始内容
            for (c in cards) {
                if (c.name != "Write") continue
                val fp = toolInputString(c.input, "file_path")
                if (fp.isBlank()) continue
                // content 允许是空字符串（写空文件是合法操作），但字段必须存在 ——
                // 用 hasField 判断，不能只看取值（取不到值时 "" 和「不存在」同形）
                if (!toolInputHasField(c.input, "content")) continue
                // 同一路径多次 Write = 覆盖写，后来的赢（对齐 Web 的 set）
                fileContents[fp] = toolInputString(c.input, "content")
            }
            if (fileContents.isEmpty()) return emptyList()

            // 第二遍：Edit / MultiEdit 应用到已有内容上
            for (c in cards) {
                if (c.name != "Edit" && c.name != "MultiEdit") continue
                val fp = toolInputString(c.input, "file_path")
                val current = fileContents[fp] ?: continue
                fileContents[fp] = applyEditsTo(current, c.input)
            }

            // 按扩展名过滤 + 生成条目
            val out = mutableListOf<DocumentEntry>()
            for ((fp, content) in fileContents) {
                val name = fp.split('/', '\\').lastOrNull().orEmpty()
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext !in PREVIEW_EXTS) continue
                out += DocumentEntry(path = fp, content = content)
            }
            return out
        }

        /**
         * 从消息正文里解析**内联 artifact 标记**（对齐 Desktop 的 `parseInlineArtifactDisplay`）。
         *
         * ## 协议
         * 模型在回复里直接写：
         * ```
         * <cp_artifact title="报告" format="markdown">
         * （内容）
         * </cp_artifact>
         * ```
         * 前端把它抽成一张文档卡片，正文里**不留标记**（否则用户看到一串标签）。
         *
         * ## 三种状态
         * 1. **闭合**（有 `</cp_artifact>`）→ 抽出内容，正文剥掉整段
         * 2. **未闭合**（流式到一半）→ 抽出已有内容当 preview，正文剥掉标记头
         * 3. **没有标记** → 原样返回
         *
         * ## 与 Desktop 的差异
         * Desktop 还维护「草稿态」（done=false 时显示进度条）。APK 简化：
         * 只在**定型时**解析一次（流式期间正文本身在变，草稿态会反复重排）。
         * 代价：流式中看不到「正在生成文档」的提示 —— 可以接受（正文本身可见）。
         *
         * @param text 消息正文
         * @return `(剥掉标记的正文, 解析出的文档列表)`。无标记时返回 `(text, emptyList())`
         */
        fun parseInlineArtifacts(text: String): Pair<String, List<DocumentEntry>> {
            if (!text.contains("<cp_artifact")) return text to emptyList()

            var cleaned = text
            val docs = mutableListOf<DocumentEntry>()

            // 循环处理多个标记（模型可能一次回复里放多个 artifact）
            while (true) {
                val openMatch = Regex("<cp_artifact\\s+([^>]*)>", RegexOption.IGNORE_CASE)
                    .find(cleaned) ?: break
                val attrs = openMatch.groupValues.getOrNull(1).orEmpty()

                val title = Regex("title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.getOrNull(1)?.trim().orEmpty()
                val format = Regex("format=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(attrs)?.groupValues?.getOrNull(1)?.trim()
                    ?.takeIf { it.isNotBlank() } ?: "markdown"

                val bodyStart = openMatch.range.last + 1
                val closeIdx = cleaned.indexOf("</cp_artifact>", bodyStart)

                if (closeIdx == -1) {
                    // 未闭合：抽走标记头 + 余下内容，正文只留标记之前的部分
                    val body = cleaned.substring(bodyStart).removePrefix("\n")
                    docs += DocumentEntry(
                        path = "",
                        content = body,
                        inlineTitle = title.ifBlank { "Untitled document" },
                        inlineFormat = format,
                    )
                    cleaned = cleaned.substring(0, openMatch.range.first).trim()
                    break
                }

                val body = cleaned.substring(bodyStart, closeIdx).removePrefix("\n")
                docs += DocumentEntry(
                    path = "",
                    content = body,
                    inlineTitle = title.ifBlank { "Untitled document" },
                    inlineFormat = format,
                )
                // 剥掉整段标记（before + after），继续找下一个
                cleaned = (cleaned.substring(0, openMatch.range.first) +
                    cleaned.substring(closeIdx + "</cp_artifact>".length))
                    .trim()
                    .replace(Regex("\\n{3,}"), "\n\n")
            }

            return cleaned to docs
        }

        /**
         * 把一次 Edit / MultiEdit 调用应用到内容上，返回新内容。
         *
         * 三种入参形态（按优先级）：
         * 1. `edits` 数组 —— 逐个 `{old_string, new_string}` 应用（APK 的 MultiEdit schema）
         * 2. 顶层 `old_string` / `new_string` —— 单次替换（Edit，或 Web 形态的 MultiEdit）
         * 3. 都没有 → 原样返回（不改内容，也不会崩）
         */
        private fun applyEditsTo(content: String, inputJson: String): String {
            var out = content
            val root = try {
                kotlinx.serialization.json.Json.parseToJsonElement(inputJson)
                    as? kotlinx.serialization.json.JsonObject
            } catch (_: Throwable) {
                null
            } ?: return out

            // 形态 1：edits 数组
            val edits = root["edits"] as? kotlinx.serialization.json.JsonArray
            if (edits != null) {
                for (el in edits) {
                    val obj = el as? kotlinx.serialization.json.JsonObject ?: continue
                    val oldStr = (obj["old_string"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val newStr = (obj["new_string"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    if (oldStr.isNullOrEmpty() || newStr == null) continue
                    out = out.replace(oldStr, newStr)
                }
                return out
            }

            // 形态 2：顶层 old_string / new_string
            val oldStr = (root["old_string"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            val newStr = (root["new_string"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (!oldStr.isNullOrEmpty() && newStr != null) {
                out = out.replace(oldStr, newStr)
            }
            return out
        }

        /**
         * 从工具卡 `input`（完整入参 JSON 字符串）里取一个字符串字段。
         *
         * 解析失败 / 字段不存在 / 类型不对 → 返回空串（调用方自行判断）。
         * APK 的 [ToolCard.input] 是 String 而非对象，所以必须先 parse ——
         * 对齐 Web 的 `tc.input?.file_path`（那边已经是对象）。
         */
        private fun toolInputString(input: String, key: String): String = try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(input) as? kotlinx.serialization.json.JsonObject
            (obj?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        } catch (_: Throwable) {
            ""
        }

        /**
         * 字段是否存在（区分「值为空串」与「压根没这个字段」）。
         *
         * Write 的 `content` 可能是 `""`（写空文件），Edit 的 `new_string`
         * 也可能是 `""`（删掉一段）—— 用取值判空会把这两种合法调用误杀。
         */
        private fun toolInputHasField(input: String, key: String): Boolean = try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(input) as? kotlinx.serialization.json.JsonObject
            obj?.containsKey(key) == true
        } catch (_: Throwable) {
            false
        }

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
