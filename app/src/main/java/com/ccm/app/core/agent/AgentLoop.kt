package com.ccm.app.core.agent

import com.ccm.app.core.api.ApiClient
import com.ccm.app.core.api.ApiTypes
import com.ccm.app.core.session.ContentBlock
import com.ccm.app.core.session.Message
import com.ccm.app.core.tool.Attachment
import com.ccm.app.core.tool.SubAgentSpec
import com.ccm.app.core.tool.SubAgentResult
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolStorage
import com.ccm.app.core.tool.ToolUiCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent 主循环 —— 整个产品的骨架。
 *
 * 对应 Node 版 `core/agent.mjs`（1642 行）。
 *
 * ## 一次 run 做什么
 * ```
 * while (turnCount < maxTurns) {
 *     1. 调模型（流式）→ 收正文 / 思考 / 工具调用
 *     2. 有工具调用 → 执行（并发分区）→ 结果回填历史 → 回到 1
 *     3. 无工具调用 → 这一轮结束
 * }
 * ```
 *
 * ## 三条不可动摇的设计（都是 Node 版血泪教训）
 *
 * ### 1. 工具列表**惰性取**，不能是构造时的快照
 * Node 版坑：`registry.list()` 取数组快照传给 SubAgentTool，而 SubAgentTool
 * 自己是在那之后才注册的 —— 快照里永远没有 'Agent'，三层编排退化成两层。
 * 所以这里用 [toolsProvider] 函数而非 `List<Tool>`。
 *
 * ### 2. 双取消域：流清理 ≠ 工具取消
 * Node 版坑：流收尾时 `streamController.abort()` 把**仍在运行的提前启动工具**一起杀了，
 * 表现为「WebSearch 每次都 Interrupted，本地工具却全正常」（按工具耗时筛选受害者）。
 * 所以工具拿到的是**自己的** Job，不跟流的清理动作挂钩。
 *
 * ### 3. 重试只允许一层
 * Node 版坑：api 层重试 3 次 × agent 层 4 轮 = 12 次请求 / 687 秒静默卡死。
 * 这里：收到 `retriesExhausted = true` 就**不再重试**。
 *
 * ## 零 Android 依赖
 * 本类（及整个 `core/`）**不引用任何 `android.*`**，这样业务逻辑能在纯 JVM 单测里跑。
 * base64 用 `java.util.Base64`（API 26+ 可用，与 minSdk 一致），不用 `android.util.Base64`。
 */
class AgentLoop(
    private val api: ApiClient,
    /** 系统提示词。 */
    private val systemPrompt: String,
    /**
     * 工具列表**提供者**（不是快照！见类注释规则 1）。
     *
     * 用法：`AgentLoop(..., toolsProvider = { registry.list })`
     */
    private val toolsProvider: () -> List<Tool>,
    /** 最大轮次。 */
    private var maxTurns: Int = 200,
    /** 工作目录。 */
    private val cwd: String = "/",
    /** 额外可访问目录（`/add-dir`）。 */
    private val extraDirs: List<String> = emptyList(),
    /** 权限模式。 */
    private var permissionMode: String = "default",
    /** 应用存储（工具用）。 */
    private val storage: ToolStorage? = null,
    /** 只读配置快照（工具用）。 */
    private val settings: com.ccm.app.core.tool.ToolSettings? = null,
    /** 会话 id。 */
    private val sessionId: String = "",
    /** 派生子 Agent 的能力（由上层注入）。 */
    private val spawnSubAgent: (suspend (SubAgentSpec) -> SubAgentResult)? = null,
    /** 是否开启流式。 */
    private val useStream: Boolean = true,
) {

    /** 对话历史（协议无关的中间表示）。 */
    private val messages = mutableListOf<Message>()

    /** 已执行的轮次。 */
    var turnCount: Int = 0
        private set

    /** 累计 token 用量。 */
    private var totalInputTokens = 0
    private var totalOutputTokens = 0

    /** 中断标志。 */
    @Volatile
    private var aborted = false

    /** 主动中断当前 run。 */
    fun abort() {
        aborted = true
        api.cancelActiveStream()
    }

    /** 设置最大轮次（deep 模式用）。 */
    fun setMaxTurns(n: Int) {
        maxTurns = n
    }

    /** 设置权限模式。 */
    fun setPermissionMode(mode: String) {
        permissionMode = mode
    }

    /** 读当前历史（会话保存用）。 */
    fun getHistory(): List<Message> = messages.toList()

    /** 覆盖历史（会话恢复用）。 */
    fun setHistory(history: List<Message>) {
        messages.clear()
        messages.addAll(history)
    }

    /** 追加一条用户消息（不触发 run）。 */
    fun addUserMessage(text: String) {
        messages += Message.user(text)
    }

    /** 累计用量（UI 状态行显示）。 */
    fun getTotalUsage(): Pair<Int, Int> = totalInputTokens to totalOutputTokens

    /**
     * 跑一轮完整对话。
     *
     * @param userMessage 用户输入
     * @return 事件流。**必须在协程里 collect**，否则不会执行（冷流）。
     */
    fun run(userMessage: String): Flow<AgentEvent> = channelFlow {
        // 显式把 send 包成 emit —— 避免用 ProducerScope 扩展函数（隐式接收者在
        // 嵌套 coroutineScope/async 里容易解析到错误的作用域）
        val emit: suspend (AgentEvent) -> Unit = { ev -> send(ev) }

        messages += Message.user(userMessage)
        turnCount = 0
        aborted = false

        try {
            while (turnCount < maxTurns) {
                if (aborted) throw CancellationException("用户中断")
                turnCount++

                val assistant = callModel(emit) ?: break
                if (assistant.toolCalls.isEmpty()) break

                val results = executeTools(assistant.toolCalls, emit)
                appendToolResults(assistant, results)
                appendVisionFollowups(results)

                emit(AgentEvent.TurnEnd(turnCount))
            }
        } catch (e: CancellationException) {
            emit(AgentEvent.Error("已中断", ToolResult.USER_ABORT))
        } catch (e: ApiTypes.ApiException) {
            emit(AgentEvent.Error(e.message, classifyApiError(e)))
        } catch (e: Throwable) {
            emit(AgentEvent.Error(e.message ?: "未知错误", ToolResult.UNKNOWN))
        } finally {
            // 保证恰好发一次 Done —— UI 靠它收尾（关 spinner、恢复输入框）
            emit(AgentEvent.Done)
        }
    }.flowOn(Dispatchers.IO)

    // ═════════════════════════ 模型调用 ═════════════════════════

    /**
     * 调模型（流式），把流事件转成 [AgentEvent]。
     *
     * @return assistant 回复；null = 无响应（防御性返回）
     */
    private suspend fun callModel(emit: suspend (AgentEvent) -> Unit): AssistantTurn? {
        val textSb = StringBuilder()
        val reasoningSb = StringBuilder()

        val apiMessages = buildApiMessages()
        val toolDefs = toolsProvider().map {
            ApiTypes.ToolDefinition(
                name = it.name,
                description = it.description,
                parameters = it.inputSchema,
            )
        }

        if (!useStream) {
            // 非流式（compact 摘要等短请求）
            val resp = api.chat(systemPrompt, apiMessages, toolDefs)
            if (resp.text.isNotEmpty()) {
                textSb.append(resp.text)
                emit(AgentEvent.TextDelta(resp.text))
            }
            resp.reasoning?.takeIf { it.isNotEmpty() }?.let {
                reasoningSb.append(it)
                emit(AgentEvent.ReasoningDelta(it))
            }
            emitUsage(emit, resp.usage)
            return AssistantTurn(textSb.toString(), reasoningSb.toString(), resp.toolCalls)
        }

        // 流式
        api.stream(systemPrompt, apiMessages, toolDefs).collect { ev ->
            when (ev) {
                is ApiTypes.StreamEvent.Text -> {
                    textSb.append(ev.text)
                    emit(AgentEvent.TextDelta(ev.text))
                }

                is ApiTypes.StreamEvent.Reasoning -> {
                    reasoningSb.append(ev.text)
                    emit(AgentEvent.ReasoningDelta(ev.text))
                }

                // 参数是分片到达的，这里不发事件（避免 UI 显示半截 JSON），
                // 等流结束后用 api.lastToolCalls() 拿完整的再补发 ToolStart
                is ApiTypes.StreamEvent.ToolCallDelta -> Unit

                is ApiTypes.StreamEvent.Usage -> emitUsage(
                    emit,
                    ApiTypes.TokenUsage(
                        promptTokens = ev.inputTokens,
                        completionTokens = ev.outputTokens,
                        cacheReadInputTokens = ev.cacheReadTokens,
                        cacheCreationInputTokens = ev.cacheWriteTokens,
                    ),
                )

                // 单条坏数据不中断流，但报出来让排查有线索
                is ApiTypes.StreamEvent.ParseError ->
                    emit(AgentEvent.Error("流解析错误: ${ev.error}", ToolResult.UNKNOWN))

                is ApiTypes.StreamEvent.Done -> Unit
            }
        }

        // 流结束 —— 此时工具参数才拼装完整
        val toolCalls = api.lastToolCalls()
        toolCalls.forEach { tc ->
            emit(AgentEvent.ToolStart(tc.id, tc.name, parseArgs(tc.arguments)))
        }

        return AssistantTurn(textSb.toString(), reasoningSb.toString(), toolCalls)
    }

    private suspend fun emitUsage(
        emit: suspend (AgentEvent) -> Unit,
        usage: ApiTypes.TokenUsage,
    ) {
        if (usage.isEmpty) return
        totalInputTokens += usage.promptTokens
        totalOutputTokens += usage.completionTokens
        emit(AgentEvent.Usage(usage.promptTokens, usage.completionTokens))
    }

    /**
     * 把会话历史转成 API 线格式（协议无关的中间形态）。
     *
     * 单块纯文本消息走 `content: "..."` 简写（省 token，也是各家 API 的常规形态）；
     * 多块或含工具的消息走数组形态。
     */
    private fun buildApiMessages(): List<JsonObject> = messages.map { m ->
        val singleText = m.content.singleOrNull() as? ContentBlock.Text
        buildJsonObject {
            put("role", JsonPrimitive(m.role))
            if (singleText != null) {
                put("content", JsonPrimitive(singleText.text))
            } else {
                put("content", buildJsonArray {
                    m.content.forEach { block -> add(blockToJson(block)) }
                })
            }
        }
    }

    /** 单个内容块 → 线格式 JSON。 */
    private fun blockToJson(block: ContentBlock): JsonObject = when (block) {
        is ContentBlock.Text -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(block.text))
        }

        is ContentBlock.Image -> buildJsonObject {
            put("type", JsonPrimitive("image_url"))
            put("image_url", buildJsonObject {
                put("url", JsonPrimitive("data:${block.mimeType};base64,${block.base64}"))
            })
        }

        is ContentBlock.ToolUse -> buildJsonObject {
            put("type", JsonPrimitive("tool_use"))
            put("id", JsonPrimitive(block.id))
            put("name", JsonPrimitive(block.name))
            put("input", block.input)
        }

        is ContentBlock.ToolResult -> buildJsonObject {
            put("type", JsonPrimitive("tool_result"))
            put("tool_use_id", JsonPrimitive(block.id))
            put("content", JsonPrimitive(block.content))
            if (block.isError) put("is_error", JsonPrimitive(true))
        }
    }

    // ═════════════════════════ 工具执行 ═════════════════════════

    /** 一次工具执行的结果（含原始 ToolResult，供 vision 旁路取附件）。 */
    private data class ToolExecResult(
        val id: String,
        val name: String,
        val result: ToolResult,
    )

    /**
     * 执行一批工具调用。
     *
     * **并发分区**（对齐 Node 版 `_partitionToolCalls`）：
     * 连续的 `isConcurrencySafe` 工具合成一批并发跑，其余串行。
     * 这样既拿到并发收益，又保证有顺序依赖的工具不乱序。
     */
    private suspend fun executeTools(
        toolCalls: List<ApiTypes.ToolCall>,
        emit: suspend (AgentEvent) -> Unit,
    ): List<ToolExecResult> {
        val tools = toolsProvider()
        val results = mutableListOf<ToolExecResult>()

        // 分区：连续的安全工具合成一批
        val batches = mutableListOf<Pair<Boolean, MutableList<ApiTypes.ToolCall>>>()
        for (tc in toolCalls) {
            val tool = tools.find { it.name == tc.name }
            val safe = tool?.isConcurrencySafe == true
            val last = batches.lastOrNull()
            if (safe && last?.first == true) {
                last.second.add(tc)
            } else {
                batches += safe to mutableListOf(tc)
            }
        }

        for ((safe, batch) in batches) {
            if (safe) {
                // 并发批：每个工具一个子协程，全部完成才继续
                coroutineScope {
                    val jobs = batch.map { tc ->
                        async { runOneTool(tc, tools, emit, coroutineContext[Job]) }
                    }
                    results += jobs.awaitAll()
                }
            } else {
                // 串行批：一个一个来（有顺序依赖）
                for (tc in batch) {
                    results += runOneTool(tc, tools, emit, null)
                }
            }
        }
        return results
    }

    /**
     * 执行单个工具。
     *
     * @param parentJob 父 Job，作为工具的取消信号。
     *   **必须是工具自己的域**，不能传流的清理 Job（见类注释规则 2）。
     *   null = 串行路径，用当前协程的 Job。
     */
    private suspend fun runOneTool(
        tc: ApiTypes.ToolCall,
        tools: List<Tool>,
        emit: suspend (AgentEvent) -> Unit,
        parentJob: Job?,
    ): ToolExecResult {
        val tool = tools.find { it.name == tc.name }
            ?: return ToolExecResult(
                tc.id, tc.name,
                ToolResult.Error(
                    "Tool not found: ${tc.name}。可用工具：${tools.joinToString(", ") { it.name }}",
                    ToolResult.NOT_FOUND,
                ),
            )

        val input = parseArgs(tc.arguments)

        // 参数校验（返回字符串 = 错误信息）
        tool.validateInput(input)?.let { err ->
            val r = ToolResult.invalidInput("$err\n收到的参数：${input.toString().take(500)}")
            emit(AgentEvent.ToolResult(tc.id, tc.name, r.textOrMessage, true))
            return ToolExecResult(tc.id, tc.name, r)
        }

        // 权限检查
        if (!isPermitted(tool)) {
            val r = ToolResult.denied("权限模式 `$permissionMode` 下不允许执行 ${tool.name}")
            emit(AgentEvent.ToolResult(tc.id, tc.name, r.textOrMessage, true))
            return ToolExecResult(tc.id, tc.name, r)
        }

        val ctx = ToolContext(
            cwd = cwd,
            extraDirs = extraDirs,
            permissionMode = permissionMode,
            cancelSignal = parentJob ?: Job(),
            ui = makeUiCallback(emit),
            spawnSubAgent = spawnSubAgent,
            storage = storage,
            settings = settings,
            sessionId = sessionId,
        )

        val result = try {
            tool.execute(input, ctx)
        } catch (e: CancellationException) {
            ToolResult.cancelled()
        } catch (e: com.ccm.app.core.tool.ToolCancelledException) {
            ToolResult.cancelled()
        } catch (e: Throwable) {
            ToolResult.Error(e.message ?: "工具执行异常", ToolResult.INTERNAL)
        }

        emit(AgentEvent.ToolResult(tc.id, tc.name, result.textOrMessage, result.failed))
        return ToolExecResult(tc.id, tc.name, result)
    }

    /** 给工具的 UI 回调（进度 → ToolProgress 事件）。 */
    private fun makeUiCallback(emit: suspend (AgentEvent) -> Unit): ToolUiCallback =
        object : ToolUiCallback {
            override suspend fun onProgress(text: String) {
                emit(AgentEvent.ToolProgress(id = "", text = text))
            }

            override suspend fun onContent(text: String) {
                emit(AgentEvent.TextDelta(text))
            }
        }

    /** 权限判断：plan 模式只放行只读；default/acceptEdits 放行非破坏性。 */
    private fun isPermitted(tool: Tool): Boolean = when (permissionMode) {
        "bypassPermissions" -> true
        "plan" -> tool.isReadOnly
        "acceptEdits" -> !tool.isDestructive
        else -> tool.isReadOnly || !tool.isDestructive
    }

    // ═════════════════════════ 历史维护 ═════════════════════════

    /** 把 assistant 回复 + 工具结果写进历史。 */
    private fun appendToolResults(
        assistant: AssistantTurn,
        results: List<ToolExecResult>,
    ) {
        val blocks = mutableListOf<ContentBlock>()
        if (assistant.text.isNotEmpty()) blocks += ContentBlock.Text(assistant.text)
        assistant.toolCalls.forEach { tc ->
            blocks += ContentBlock.ToolUse(tc.id, tc.name, parseArgs(tc.arguments))
        }
        messages += Message(Message.ROLE_ASSISTANT, blocks)

        val resultBlocks = results.map { r ->
            ContentBlock.ToolResult(r.id, r.result.textOrMessage, r.result.failed)
        }
        if (resultBlocks.isNotEmpty()) {
            messages += Message(Message.ROLE_USER, resultBlocks)
        }
    }

    /**
     * vision 旁路注入（对齐 Node 版 `__type:'vision'` 机制）。
     *
     * 有附件的工具结果：tool_result 已存文本摘要，这里**再追加一条多模态 user 消息**
     * 装真正的图片块 —— 模型要「看见」图片，必须让它出现在 user 消息的 content 里。
     *
     * ⚠️ 两条纪律（见 [ToolResult] 类注释）：
     * 1. 端点不支持图片时**必须如实告知**，否则模型会编造画面内容
     * 2. 图片被缩放时要透明化
     */
    private fun appendVisionFollowups(results: List<ToolExecResult>) {
        val withAttachments = results.filter { r ->
            val res = r.result
            res is ToolResult.Success && res.attachments.isNotEmpty()
        }
        if (withAttachments.isEmpty()) return

        val blocks = mutableListOf<ContentBlock>()
        val resizeNotes = mutableListOf<String>()

        for (r in withAttachments) {
            val success = r.result as ToolResult.Success
            for (att in success.attachments) {
                when (att) {
                    is Attachment.ImageFile -> {
                        val b64 = readImageAsBase64(att.path)
                        if (b64 != null) {
                            blocks += ContentBlock.Image(b64, att.mimeType)
                            att.resizedFrom?.let {
                                resizeNotes += "图片已从 $it 缩放（节省 token；微小细节可能受影响）"
                            }
                        } else {
                            // 读失败要如实说，不能让模型以为图到了
                            blocks += ContentBlock.Text("[图片读取失败: ${att.path}]")
                        }
                    }

                    is Attachment.ImageBytes -> {
                        blocks += ContentBlock.Image(
                            java.util.Base64.getEncoder().encodeToString(att.bytes),
                            att.mimeType,
                        )
                        att.resizedFrom?.let {
                            resizeNotes += "图片已从 $it 缩放（节省 token；微小细节可能受影响）"
                        }
                    }

                    // 非图片附件不进多模态，只给个路径
                    is Attachment.FileLink -> blocks += ContentBlock.Text(
                        "[附件: ${att.name} @${att.path}]"
                    )
                }
            }
        }

        if (resizeNotes.isNotEmpty()) {
            blocks += ContentBlock.Text(
                "<image_resize_notice>\n${resizeNotes.joinToString("\n")}\n</image_resize_notice>"
            )
        }

        // ⚠️ 端点已知不支持图片 —— 必须如实告知，否则模型会凭上下文编造画面内容
        if (api.isVisionUnsupported) {
            blocks += ContentBlock.Text(
                "<vision_unsupported>\n当前模型不支持读图，本次图片已被丢弃，你看不到画面内容。\n" +
                    "不要再猜测或编造图里的内容；需要看图请换一个支持视觉的模型（切 Provider），" +
                    "或让用户直接用文字描述关键信息。\n</vision_unsupported>"
            )
        }

        if (blocks.isNotEmpty()) {
            messages += Message(Message.ROLE_USER, blocks)
        }
    }

    /** 读图片文件并编码成 base64。失败返回 null（不抛）。 */
    private fun readImageAsBase64(path: String): String? = try {
        val f = java.io.File(path)
        if (f.exists() && f.isFile) {
            java.util.Base64.getEncoder().encodeToString(f.readBytes())
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    // ═════════════════════════ 工具方法 ═════════════════════════

    /** 解析工具参数 JSON。非法 JSON → 空对象（让工具自己报「参数不合法」）。 */
    private fun parseArgs(raw: String): JsonObject = try {
        if (raw.isBlank()) buildJsonObject { }
        else com.ccm.app.core.api.StreamParser.DEFAULT_JSON
            .parseToJsonElement(raw) as? JsonObject ?: buildJsonObject { }
    } catch (_: Throwable) {
        buildJsonObject { }
    }

    /**
     * API 错误分类（对齐 Node 版 `_classifyError`）。
     *
     * ⚠️ **`retriesExhausted` 必须优先判断** —— 它是「下层已重试穷尽」的标记，
     * 上层再重试就是跨层叠加（Node 版 687 秒静默卡死的根因）。
     */
    private fun classifyApiError(e: ApiTypes.ApiException): String {
        if (e.retriesExhausted) return "retries_exhausted"
        val msg = e.message
        return when {
            e.statusCode == 429 -> AgentEvent.ERR_RATE_LIMIT
            e.statusCode in 500..599 -> AgentEvent.ERR_SERVER
            e.statusCode == 401 || e.statusCode == 403 -> AgentEvent.ERR_AUTH
            e.statusCode in 400..499 -> AgentEvent.ERR_CLIENT_4XX
            msg.contains("context", true) && msg.contains("length", true) -> AgentEvent.ERR_CONTEXT_OVERFLOW
            msg.contains("Stream timeout", true) -> AgentEvent.ERR_STREAM_TIMEOUT
            msg.contains("timeout", true) -> AgentEvent.ERR_CONNECT_TIMEOUT
            msg.contains("network", true) || msg.contains("fetch failed", true) -> AgentEvent.ERR_NETWORK
            else -> AgentEvent.ERR_UNKNOWN
        }
    }

    /** 一次 assistant 回复的聚合结果。 */
    private data class AssistantTurn(
        val text: String,
        val reasoning: String,
        val toolCalls: List<ApiTypes.ToolCall>,
    )
}
