package com.ccm.app.core

import com.ccm.app.core.agent.AgentEvent
import com.ccm.app.core.agent.AgentLoop
import com.ccm.app.core.api.ApiClient
import com.ccm.app.core.provider.Protocol
import com.ccm.app.core.session.Message
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolRegistry
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolRunner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 端到端链路测试 —— **验证「用户输入 → 模型 → 工具 → 回灌 → 再问」真能跑通**。
 *
 * ## 为什么必须有这个测试（而不是靠装机点几下）
 *
 * 装机只能验证「App 起得来、界面画得出」。**链路是否真的接对了，
 * 只有跑一次完整对话才知道** —— 而且线上跑挂的代价是用户看到「转圈到死」。
 * 更关键的是：这条链路上有一类 bug **本地跑一次就能抓到，装机却很难定位**
 * （比如「工具调用在 OpenAI 协议下发出非法格式」—— 网关报 400，
 * 界面上只显示「HTTP 400」，看不出是消息格式问题）。
 *
 * ## 测试策略：假网关 + 真链路
 *
 * 用 [MockWebServer] 起一个**真 HTTP 服务**扮演中转站：
 * 收到请求 → 按脚本回 SSE 流。于是**除了真实模型**，其余每一环都是生产代码：
 *
 * ```
 * AgentLoop → ApiClient(真) → OkHttp(真) → HTTP(真) → 假网关
 *     ↑                                                     │
 *     └────────── 解析(真) ← SSE(真) ←───────────────────────┘
 * ```
 *
 * 这样能抓到：请求体格式错（协议转换）、SSE 解析错、工具调用拼装错、
 * 结果回灌错、轮次控制错。
 *
 * ## 覆盖的场景
 * 1. **纯文本对话**：一轮就结束（且回复必须进历史 —— 曾经这里漏了）
 * 2. **工具调用闭环**：模型要调工具 → 真执行 → 结果回灌 → 模型给最终答复
 * 3. **OpenAI 协议的请求体格式**：`tool_calls` / `role:"tool"` 必须是 OpenAI 形态
 *    （这是「工具在 OpenAI 协议下完全失效」那个 bug 的回归防护）
 * 4. **多轮历史完整**：第二轮请求里必须能看到第一轮的 assistant 回复
 * 5. **中断后 Done 仍送达**：UI 靠它收尾，丢了就永久转圈
 * 6. **流式重试**：第一次 503、第二次成功（key 池/退避逻辑）
 * 7. **空响应 / 占位符重试**
 * 8. **并发工具结果顺序**
 */
class EndToEndLinkTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        try {
            server.shutdown()
        } catch (_: Throwable) {
        }
    }

    // ═══════════════════════ 场景 1：纯文本对话 ═══════════════════════

    @Test
    fun `纯文本一轮对话 —— 回复进历史且链路完整`() = runBlocking {
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"你好"}}]}""",
            """data: {"choices":[{"delta":{"content":"，我是 CCM"}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
            "data: [DONE]",
        )

        val loop = newLoop()
        val events = loop.run("你好").toList()

        // 1) 正文按流式分片到达
        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("你好，我是 CCM", text)

        // 2) 恰好一个 Done（UI 靠它收尾）
        assertEquals(1, events.count { it is AgentEvent.Done })

        // 3) 没有错误
        assertEquals(emptyList<AgentEvent.Error>(), events.filterIsInstance<AgentEvent.Error>())

        // 4) ★ 回复进了历史 —— 这条曾经是 bug（纯文本路径漏写历史）
        val history = loop.getHistory()
        assertEquals(2, history.size)
        assertEquals(Message.ROLE_USER, history[0].role)
        assertEquals(Message.ROLE_ASSISTANT, history[1].role)
        assertTrue(
            "assistant 回复必须进历史，否则下一轮模型看不到自己说过什么",
            history[1].text.contains("我是 CCM"),
        )
    }

    // ═══════════════════════ 场景 2：工具调用闭环 ═══════════════════════

    @Test
    fun `工具调用闭环 —— 模型要工具 真执行 结果回灌 模型收尾`() = runBlocking {
        // 第 1 次请求：模型要求调 Echo 工具
        enqueueSse(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"Echo","arguments":""}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"text\":\"hi\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]",
        )
        // 第 2 次请求：模型看到工具结果后给最终答复
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"工具返回了 hi"}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            "data: [DONE]",
        )

        val loop = newLoop(tools = listOf(EchoTool()))
        val events = loop.run("echo hi").toList()

        // 1) 工具被识别并真的执行了
        val starts = events.filterIsInstance<AgentEvent.ToolStart>()
        assertEquals(1, starts.size)
        assertEquals("Echo", starts[0].name)

        val results = events.filterIsInstance<AgentEvent.ToolResult>()
        assertEquals(1, results.size)
        assertFalse("工具不该失败", results[0].isError)
        assertEquals("echo:hi", results[0].result)

        // 2) 模型拿到了工具结果并继续说话
        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("工具返回了 hi", text)

        // 3) 两次请求都发出去了
        assertEquals(2, server.requestCount)

        // 4) ★ 第 2 次请求里必须带工具结果（回灌成功）
        val second = bodyOf(1)
        assertTrue("工具结果必须回灌给模型，否则模型会重复调用工具", second.contains("echo:hi"))

        // 5) 历史完整：user → assistant(工具调用) → user(工具结果) → assistant(最终答复)
        assertEquals(4, loop.getHistory().size)
    }

    // ═══════════════════════ 场景 3：OpenAI 协议格式 ═══════════════════════

    @Test
    fun `OpenAI 协议 —— 工具调用必须用 tool_calls 字段和 role tool 消息`() = runBlocking {
        enqueueSse(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"Echo","arguments":"{\"text\":\"x\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]",
        )
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"ok"}}]}""",
            "data: [DONE]",
        )

        val loop = newLoop(tools = listOf(EchoTool()), protocol = Protocol.OPENAI)
        loop.run("go").toList()

        val second = bodyOf(1)

        // ★ 这是「OpenAI 协议下工具完全失效」那个 bug 的回归防护。
        // 早期实现把 Anthropic 形态的 tool_use/tool_result 块原样发出去，
        // OpenAI 系网关要么 400、要么静默忽略 → 模型永远学不会调工具。
        assertTrue(
            "OpenAI 协议必须用 tool_calls 字段（不能是 content 里的 tool_use 块）",
            second.contains("\"tool_calls\""),
        )
        assertTrue(
            "工具结果必须是独立的 role:\"tool\" 消息",
            second.contains("\"role\":\"tool\""),
        )
        assertTrue(
            "必须有 tool_call_id 关联",
            second.contains("\"tool_call_id\":\"call_1\""),
        )
        assertFalse(
            "不能把 Anthropic 的 tool_use 块透传给 OpenAI",
            second.contains("\"type\":\"tool_use\""),
        )
        assertFalse(
            "不能把 Anthropic 的 tool_result 块透传给 OpenAI",
            second.contains("\"type\":\"tool_result\""),
        )
    }

    // ═══════════════════════ 场景 4：多轮历史 ═══════════════════════

    @Test
    fun `多轮对话 —— 第二轮请求里能看到第一轮的回复`() = runBlocking {
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"第一答"}}]}""",
            "data: [DONE]",
        )
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"第二答"}}]}""",
            "data: [DONE]",
        )

        val loop = newLoop()
        loop.run("第一问").toList()
        loop.run("第二问").toList()

        val second = bodyOf(1)
        assertTrue("第二轮必须带上第一问", second.contains("第一问"))
        assertTrue(
            "第二轮必须带上第一答 —— 否则模型失忆（纯文本不进历史那个 bug 的表现）",
            second.contains("第一答"),
        )
        assertTrue("第二轮当然要带上第二问", second.contains("第二问"))
    }

    // ═══════════════════════ 场景 5：中断后 Done 仍送达 ═══════════════════════

    @Test
    fun `中断 —— 必须仍然发出 Done 否则 UI 永久转圈`() = runBlocking {
        // 响应头立即返回（流已建立），但 body 延迟 30 秒才发 ——
        // 模拟「模型正在输出时用户按了中断」：连接活着、但没有新数据。
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("")
                .setBodyDelay(30, java.util.concurrent.TimeUnit.SECONDS),
        )

        val loop = newLoop()
        val events = mutableListOf<AgentEvent>()
        val job = launch { loop.run("x").collect { events += it } }

        // 等请求真的发出去（流已建立）再中断
        kotlinx.coroutines.delay(800)
        loop.abort()

        // 等 Done 到达
        var waited = 0
        while (events.none { it is AgentEvent.Done } && waited < 6000) {
            kotlinx.coroutines.delay(50)
            waited += 50
        }
        job.cancel()

        assertTrue(
            "中断后仍必须发 Done —— 它是 UI 关 spinner、解锁输入框的唯一信号",
            events.any { it is AgentEvent.Done },
        )
        // 中断是用户的主动行为，不该被报成故障
        assertTrue(
            "中断不该报「网络错误」给用户看",
            events.filterIsInstance<AgentEvent.Error>().none {
                it.message.contains("网络错误")
            },
        )
    }

    // ═══════════════════════ 场景 6：流式重试 ═══════════════════════

    @Test
    fun `流式重试 —— 第一次 503 第二次成功`() = runBlocking {
        // 第一次：503（可重试）
        server.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"upstream busy"}"""),
        )
        // 第二次：正常流
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"重试成功"}}]}""",
            "data: [DONE]",
        )

        val loop = newLoop()
        val events = loop.run("hi").toList()

        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("重试成功", text)
        assertEquals("第一次失败 + 第二次成功 = 两次请求", 2, server.requestCount)
        assertEquals(
            "不该把可重试的 503 直接报成错误",
            emptyList<AgentEvent.Error>(),
            events.filterIsInstance<AgentEvent.Error>(),
        )
    }

    // ═══════════════════════ 场景 7：空响应重试 ═══════════════════════

    @Test
    fun `空响应 —— 自动重试而不是白掉一轮`() = runBlocking {
        // 第一次：空内容（模型什么都没说）
        enqueueSse("""data: {"choices":[{"delta":{},"finish_reason":"stop"}]}""", "data: [DONE]")
        // 第二次：正常回复
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"这次有内容"}}]}""",
            "data: [DONE]",
        )

        val loop = newLoop()
        val events = loop.run("hi").toList()

        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("空响应后应重试并拿到内容", "这次有内容", text)
        assertEquals(2, server.requestCount)
    }

    // ═══════════════════════ 场景 8：占位符回复 ═══════════════════════

    @Test
    fun `占位符回复 —— 视为空响应重试`() = runBlocking {
        // 模型照抄历史里的内部占位符
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"(continue)"}}]}""",
            "data: [DONE]",
        )
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"正常了"}}]}""",
            "data: [DONE]",
        )

        val loop = newLoop()
        val events = loop.run("hi").toList()

        val text = events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("正常了", text)
        assertEquals("占位符应触发重试", 2, server.requestCount)
    }

    // ═══════════════════════ 场景 9：工具结果顺序 ═══════════════════════

    @Test
    fun `多个工具并发执行 —— 结果顺序必须与请求顺序一致`() = runBlocking {
        enqueueSse(
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"Slow","arguments":"{}"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c2","type":"function","function":{"name":"Fast","arguments":"{}"}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
            "data: [DONE]",
        )
        enqueueSse(
            """data: {"choices":[{"delta":{"content":"done"}}]}""",
            "data: [DONE]",
        )

        // Slow 睡 200ms、Fast 立刻返回。若调度器不回填原顺序，结果会颠倒。
        val loop = newLoop(tools = listOf(SlowTool(), FastTool()))
        loop.run("go").toList()

        val second = bodyOf(1)
        val slowAt = second.indexOf("slow-result")
        val fastAt = second.indexOf("fast-result")
        assertTrue("两个工具结果都该回灌", slowAt >= 0 && fastAt >= 0)
        assertTrue(
            "结果顺序必须与请求顺序一致（模型靠顺序配对），实际: $second",
            slowAt < fastAt,
        )
    }

    // ═══════════════════════ 测试基础设施 ═══════════════════════

    private fun newLoop(
        tools: List<Tool> = emptyList(),
        protocol: Protocol = Protocol.OPENAI,
    ): AgentLoop {
        val registry = ToolRegistry()
        tools.forEach { registry.register(it) }
        val api = ApiClient(
            baseUrl = server.url("/v1").toString(),
            apiKeys = listOf("sk-test"),
            model = "test-model",
            protocol = protocol,
            maxRetries = 3,
        )
        return AgentLoop(
            api = api,
            systemPrompt = "你是测试助手",
            toolsProvider = { registry.list },
            maxTurnsInit = 10,
            cwd = "/tmp",
            toolRunner = ToolRunner.Passthrough(),
        )
    }

    /** 排一条 SSE 流响应（每个 chunk 之间有空行分隔，符合 SSE 规范）。 */
    private fun enqueueSse(vararg chunks: String) {
        val body = chunks.joinToString("") { "$it\n\n" }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(Buffer().writeUtf8(body)),
        )
    }

    /**
     * 取第 [index] 次请求的 body（0-based）。
     *
     * ⚠️ `MockWebServer.takeRequest()` 是**出队**语义（取走就没了），
     * 所以这里缓存已取出的请求 —— 否则同一个 index 取两次会拿到不同的请求，
     * 断言会莫名其妙地失败。
     */
    private val takenBodies = mutableListOf<String>()

    private fun bodyOf(index: Int): String {
        while (takenBodies.size <= index) {
            val req = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) ?: break
            takenBodies += req.body.readUtf8()
        }
        return takenBodies.getOrElse(index) { "" }
    }

    // ── 测试工具 ──

    /** 回显工具：`{"text":"hi"}` → `echo:hi`。 */
    private class EchoTool : Tool() {
        override val name = "Echo"
        override val description = "回显输入"
        override val inputSchema: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("text", buildJsonObject { put("type", "string") })
            })
        }
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val t = (input["text"] as? JsonPrimitive)?.content ?: ""
            return ToolResult.Success("echo:$t")
        }
    }

    /** 慢工具（睡 200ms）—— 用于验证并发结果的顺序回填。 */
    private class SlowTool : Tool() {
        override val name = "Slow"
        override val description = "慢工具"
        override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            kotlinx.coroutines.delay(200)
            return ToolResult.Success("slow-result")
        }
    }

    /** 快工具（立刻返回）。 */
    private class FastTool : Tool() {
        override val name = "Fast"
        override val description = "快工具"
        override val inputSchema: JsonObject = buildJsonObject { put("type", "object") }
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            ToolResult.Success("fast-result")
    }
}
