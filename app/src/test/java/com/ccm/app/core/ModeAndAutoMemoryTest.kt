package com.ccm.app.core

import com.ccm.app.core.agent.AgentEvent
import com.ccm.app.core.agent.AgentLoop
import com.ccm.app.core.agent.ModeState
import com.ccm.app.core.api.ApiClient
import com.ccm.app.core.memory.AutoMemory
import com.ccm.app.core.provider.Protocol
import com.ccm.app.core.session.Message
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolRegistry
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolRunner
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.tools.task.ModeTools
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 运行模式（deep / plan / watch）与 automem 的回归测试。
 *
 * ## 为什么要单独一个文件
 * [EndToEndLinkTest] 覆盖的是「对话链路本身」（协议转换、工具回灌、重试）。
 * 这里覆盖的是**模式语义**——它们是 2026-10-01 新接的能力，且每一条都有
 * 「静默失效」的风险（不报错、不崩，只是没效果），所以必须有断言锁住：
 *
 * | 场景 | 静默失效的表现 |
 * |---|---|
 * | deep 模式 | EnterDeepMode 说成功，轮数一点没变 |
 * | watch 模式 | 开了持续模式，第一轮说完就停了 |
 * | plan 模式 | 系统提示词里没有计划模式的约束段 |
 * | 模式状态共享 | 工具写 A 对象、主循环读 B 对象 |
 * | automem | 游标不推进 → 每轮重复提取 / 永远不提取 |
 *
 * ## 测试策略
 * 与 EndToEndLinkTest 一致：MockWebServer 扮演中转站，除模型外全是生产代码。
 * 模式部分额外用**真工具**（[ModeTools]）触发状态变化，验证「工具写 → 循环读」的
 * 完整链路（而不是直接改 ModeState 字段——那测不出接线是否正确）。
 */
class ModeAndAutoMemoryTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ═══════════════════════ deep 模式 ═══════════════════════

    @Test
    fun `deep 模式 —— 进入后轮次上限提升到 DEEP_MAX_TURNS`() = runBlocking {
        // 模型永远要求调工具（永不收尾）→ 只能靠轮次上限终止。
        // maxTurnsInit 设 2：普通模式跑 2 轮就停；deep 模式能跑更多。
        repeat(6) { enqueueToolCallSse("Echo", """{"text":"x"}""") }

        val modes = ModeState()
        val loop = newLoop(listOf(EchoTool()), modes = modes, maxTurnsInit = 2)

        // ① 普通模式：2 轮就撞上限
        loop.run("go").toList()
        assertTrue("普通模式应只跑 2 轮，实际 ${loop.turnCount}", loop.turnCount <= 2)

        // ② 开 deep（模拟 EnterDeepMode 工具的效果）
        modes.deepMode = true
        loop.run("go again").toList()
        assertTrue(
            "deep 模式应突破 maxTurnsInit=2，实际跑了 ${loop.turnCount} 轮",
            loop.turnCount > 2,
        )
    }

    @Test
    fun `deep 模式工具 —— EnterDeepMode 真的改到共享状态`() = runBlocking {
        val modes = ModeState()
        val tools = ModeTools(modes)

        assertFalse("初始不该是 deep", modes.deepMode)
        tools.EnterDeepModeTool().execute(JsonObject(emptyMap()), dummyCtx())
        assertTrue("EnterDeepMode 后状态应为 true", modes.deepMode)

        tools.ExitDeepModeTool().execute(JsonObject(emptyMap()), dummyCtx())
        assertFalse("ExitDeepMode 后状态应为 false", modes.deepMode)
    }

    @Test
    fun `deep 模式 —— 退出后恢复普通上限`() = runBlocking {
        repeat(6) { enqueueToolCallSse("Echo", """{"text":"x"}""") }
        val modes = ModeState()
        val loop = newLoop(listOf(EchoTool()), modes = modes, maxTurnsInit = 2)

        modes.deepMode = true
        loop.run("a").toList()
        assertTrue("deep 下应超过 2 轮", loop.turnCount > 2)

        modes.deepMode = false
        loop.run("b").toList()
        assertTrue("退出 deep 后应恢复 2 轮上限，实际 ${loop.turnCount}", loop.turnCount <= 2)
    }

    // ═══════════════════════ plan 模式 ═══════════════════════

    @Test
    fun `plan 模式 —— 系统提示词里必须带上计划模式约束段`() = runBlocking {
        enqueueTextSse("好的，这是我的计划")

        val modes = ModeState()
        modes.planMode = true
        val loop = newLoop(emptyList(), modes = modes, maxTurnsInit = 5)
        loop.run("给我个计划").toList()

        val body = bodyOf(0)
        assertTrue(
            "请求体里应含计划模式提示词（说明 effectiveSystemPrompt 真的生效了），实际: $body",
            body.contains("计划模式已启用"),
        )
        assertTrue("应含「不要执行任何工具」约束", body.contains("不要执行任何工具"))
    }

    @Test
    fun `plan 模式关闭 —— 提示词里不该有计划模式段`() = runBlocking {
        enqueueTextSse("你好")
        val loop = newLoop(emptyList(), modes = ModeState(), maxTurnsInit = 5)
        loop.run("你好").toList()

        val body = bodyOf(0)
        assertFalse(
            "未开计划模式时不该出现该提示词，实际: $body",
            body.contains("计划模式已启用"),
        )
    }

    @Test
    fun `plan 模式工具 —— EnterPlanMode 写入共享状态`() = runBlocking {
        val modes = ModeState()
        val tools = ModeTools(modes)

        tools.EnterPlanModeTool().execute(JsonObject(emptyMap()), dummyCtx())
        assertTrue(modes.planMode)
        assertTrue(
            "ModeState 应产出提示词片段",
            modes.planPromptAddition().contains("计划模式已启用"),
        )

        tools.ExitPlanModeTool().execute(JsonObject(emptyMap()), dummyCtx())
        assertFalse(modes.planMode)
        assertEquals("退出后不该有提示词片段", "", modes.planPromptAddition())
    }

    // ═══════════════════════ watch 模式 ═══════════════════════

    @Test
    fun `watch 模式 —— 纯文本回复后不结束 继续循环`() = runBlocking {
        // 模型连续给 3 条纯文本回复。watch 模式下应全部跑完（不因第一条就停）。
        enqueueTextSse("第一轮")
        enqueueTextSse("第二轮")
        enqueueTextSse("第三轮")

        val modes = ModeState()
        val loop = newLoop(emptyList(), modes = modes, maxTurnsInit = 10)
        modes.watchMode = true

        // 让模型在第三轮后自动退出（模拟 ExitWatch 的效果）：
        // 否则会一直循环到 10 轮，把排队的响应耗尽后报错。
        val events = mutableListOf<AgentEvent>()
        // 用一个轻量协程在第二轮后关掉 watch，模拟「持续任务完成」
        val closer = Thread {
            Thread.sleep(400)
            modes.watchMode = false
        }
        closer.start()
        loop.run("开始持续任务").collect { events += it }
        closer.interrupt()

        val textEvents = events.filterIsInstance<AgentEvent.TextDelta>()
        assertTrue(
            "watch 模式下应至少产出两轮文本（第一轮不该直接结束），实际 ${textEvents.size} 条",
            textEvents.size >= 2,
        )
        assertTrue(
            "应发出 TurnEnd 把每轮定型（否则十轮正文攒成一个巨型气泡）",
            events.count { it is AgentEvent.TurnEnd } >= 1,
        )
    }

    @Test
    fun `watch 模式关闭 —— 第一轮纯文本后立刻结束`() = runBlocking {
        enqueueTextSse("只有一轮")
        val modes = ModeState()   // watchMode 默认 false
        val loop = newLoop(emptyList(), modes = modes, maxTurnsInit = 10)

        loop.run("你好").toList()
        assertEquals("普通模式下应只跑 1 轮", 1, loop.turnCount)
    }

    @Test
    fun `watch 模式工具 —— EnterWatch 与 ExitWatch 写共享状态`() = runBlocking {
        val modes = ModeState()
        val tools = ModeTools(modes)

        assertFalse(modes.watchMode)
        tools.EnterWatchTool().execute(JsonObject(emptyMap()), dummyCtx())
        assertTrue("EnterWatch 后应为 true", modes.watchMode)

        tools.ExitWatchTool().execute(JsonObject(emptyMap()), dummyCtx())
        assertFalse("ExitWatch 后应为 false", modes.watchMode)
    }

    @Test
    fun `watch 模式 —— 续跑注入的是 user 消息且不重复 assistant 正文`() = runBlocking {
        enqueueTextSse("回复一")
        enqueueTextSse("回复二")

        val modes = ModeState()
        val loop = newLoop(emptyList(), modes = modes, maxTurnsInit = 3)
        modes.watchMode = true

        val closer = Thread { Thread.sleep(300); modes.watchMode = false }
        closer.start()
        loop.run("开始").collect { }
        closer.interrupt()

        val history = loop.getHistory()
        // 每次 assistant 正文只该出现一次（不重复保存导致上下文膨胀）
        val assistantTexts = history.filter { it.role == Message.ROLE_ASSISTANT }.map { it.text }
        assertEquals(
            "assistant 正文不该重复保存，实际: $assistantTexts",
            assistantTexts.size,
            assistantTexts.distinct().size,
        )
        assertTrue(
            "应注入过持续模式指令",
            history.any { it.role == Message.ROLE_USER && it.text.contains("持续模式") },
        )
    }

    // ═══════════════════════ 模式状态共享（接线正确性）═══════════════════════

    @Test
    fun `ModeState —— 默认全关且 describe 正确`() {
        val m = ModeState()
        assertFalse(m.planMode)
        assertFalse(m.deepMode)
        assertFalse(m.watchMode)
        assertTrue("默认应报「普通」", m.describe().contains("普通"))
    }

    @Test
    fun `ModeState —— DEEP 上限必须不超过 AgentLoop 硬上限`() {
        // 【为什么锁这个】deep 设成超过 MAX_TURNS_HARD_CAP 会让
        // ExtendTurns 的闸门算出负数（target - maxTurns < 0）→ 静默失效。
        assertTrue(
            "DEEP_MAX_TURNS(${ModeState.DEEP_MAX_TURNS}) 不能超过 AgentLoop 的硬上限",
            ModeState.DEEP_MAX_TURNS <= 400,
        )
        assertTrue(
            "普通上限必须小于 deep 上限（否则 deep 毫无意义）",
            ModeState.NORMAL_MAX_TURNS < ModeState.DEEP_MAX_TURNS,
        )
    }

    // ═══════════════════════ automem ═══════════════════════

    @Test
    fun `automem —— 消息净增量不足时不提取`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "automem-test-${System.nanoTime()}")
        dir.mkdirs()
        val am = AutoMemory(File(dir, "automem.json"), File(dir, "CLAUDE.md"))

        val history = listOf(
            Message.user("你好"),
            Message.assistant("你好，有什么可以帮你"),
        )   // 只有 2 条 < MIN_DELTA_MESSAGES(4)

        val r = am.maybeExtract(history, newApiClient())
        assertNull("增量不足时不该提取，实际返回: $r", r)
        assertFalse("不该创建记忆文件", File(dir, "CLAUDE.md").exists())
    }

    @Test
    fun `automem —— 主 Agent 写过记忆则跳过提取并推进游标`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "automem-test-${System.nanoTime()}")
        dir.mkdirs()
        val am = AutoMemory(File(dir, "automem.json"), File(dir, "CLAUDE.md"))

        val history = (1..6).map { Message.user("消息 $it") }
        am.markMainWroteMemory()

        val r = am.maybeExtract(history, newApiClient())
        assertNull("主 Agent 写过记忆时应跳过提取", r)

        // 游标必须已推进 —— 否则下轮这段历史又爆增量
        val (_, cursor, _) = am.status()
        assertEquals("游标应推进到历史长度", history.size, cursor)
    }

    @Test
    fun `automem —— 开关关闭时不提取`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "automem-test-${System.nanoTime()}")
        dir.mkdirs()
        val am = AutoMemory(File(dir, "automem.json"), File(dir, "CLAUDE.md"))
        am.setEnabled(false)

        val history = (1..10).map { Message.user("消息 $it") }
        val r = am.maybeExtract(history, newApiClient())
        assertNull("开关关闭时不该提取", r)

        val (enabled, _, _) = am.status()
        assertFalse(enabled)
    }

    @Test
    fun `automem —— 状态可读回且 resetCursor 归零`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "automem-test-${System.nanoTime()}")
        dir.mkdirs()
        val am = AutoMemory(File(dir, "automem.json"), File(dir, "CLAUDE.md"))

        val (enabled, cursor, runs) = am.status()
        assertTrue("默认应启用", enabled)
        assertEquals(0, cursor)
        assertEquals(0, runs)

        am.resetCursor()
        assertEquals("resetCursor 后游标应为 0", 0, am.status().second)
    }

    @Test
    fun `automem —— 提取提示词必须同时定义正反两侧边界`() {
        // 【为什么锁这个】只写「记重要的」会让模型把报错细节、临时心态也记进去，
        // 记忆文件迅速膨胀成流水账。
        assertTrue("必须说明什么值得记", AutoMemory.EXTRACT_PROMPT.contains("值得记"))
        assertTrue("必须说明什么不值得记", AutoMemory.EXTRACT_PROMPT.contains("不值得记"))
        assertTrue("必须要求只输出 JSON", AutoMemory.EXTRACT_PROMPT.contains("只输出 JSON"))
    }

    @Test
    fun `automem —— 提取成功时写入记忆文件`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "automem-test-${System.nanoTime()}")
        dir.mkdirs()
        val memFile = File(dir, "CLAUDE.md")
        val am = AutoMemory(File(dir, "automem.json"), memFile)

        // 假网关回一个「记住」的判定
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"choices":[{"message":{"role":"assistant","content":"{\"remember\":true,\"text\":\"项目用 pnpm 不用 npm\"}"}}]}""",
                ),
        )

        val history = (1..6).map { Message.user("消息 $it") }
        val r = am.maybeExtract(history, newApiClient())

        assertNotNull("应提取到一条记忆，实际: $r", r)
        assertTrue("记忆文件应被创建", memFile.exists())
        assertTrue(
            "内容应是 bullet 形式，实际: ${memFile.readText()}",
            memFile.readText().contains("- 项目用 pnpm 不用 npm"),
        )
    }

    @Test
    fun `automem —— 模型说不用记时不动记忆文件`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "automem-test-${System.nanoTime()}")
        dir.mkdirs()
        val memFile = File(dir, "CLAUDE.md")
        val am = AutoMemory(File(dir, "automem.json"), memFile)

        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"{\"remember\":false}"}}]}"""),
        )

        val history = (1..6).map { Message.user("消息 $it") }
        val r = am.maybeExtract(history, newApiClient())

        assertNull("remember=false 时不该写入", r)
        assertFalse("不该创建记忆文件", memFile.exists())
    }

    @Test
    fun `automem —— 网关报错时静默失败不抛异常`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "automem-test-${System.nanoTime()}")
        dir.mkdirs()
        val am = AutoMemory(File(dir, "automem.json"), File(dir, "CLAUDE.md"))

        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"error":"boom"}"""))

        val history = (1..6).map { Message.user("消息 $it") }
        // 不该抛异常 —— 自动记忆绝不能影响主流程
        val r = am.maybeExtract(history, newApiClient())
        assertNull(r)
    }

    // ═══════════════════════ 测试基础设施 ═══════════════════════

    private fun newApiClient(): ApiClient = ApiClient(
        baseUrl = server.url("/v1").toString(),
        apiKeys = listOf("sk-test"),
        model = "test-model",
        protocol = Protocol.OPENAI,
        maxRetries = 1,
    )

    private fun newLoop(
        tools: List<Tool> = emptyList(),
        modes: ModeState = ModeState(),
        maxTurnsInit: Int = 10,
    ): AgentLoop {
        val registry = ToolRegistry()
        tools.forEach { registry.register(it) }
        return AgentLoop(
            api = newApiClient(),
            systemPrompt = "你是测试助手",
            toolsProvider = { registry.list },
            maxTurnsInit = maxTurnsInit,
            cwd = "/tmp",
            toolRunner = ToolRunner.Passthrough(),
            modes = modes,
        )
    }

    /** 排一条纯文本 SSE 响应。 */
    private fun enqueueTextSse(text: String) {
        val body = buildString {
            append("""data: {"choices":[{"delta":{"content":"$text"}}]}""").append("\n\n")
            append("""data: [DONE]""").append("\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(Buffer().writeUtf8(body)),
        )
    }

    /** 排一条「要求调用工具」的 SSE 响应。 */
    private fun enqueueToolCallSse(toolName: String, args: String) {
        val escaped = args.replace("\"", "\\\"")
        val body = buildString {
            append(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"$toolName"}}]}}]}""",
            ).append("\n\n")
            append(
                """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"$escaped"}}]}}]}""",
            ).append("\n\n")
            append("""data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""").append("\n\n")
            append("""data: [DONE]""").append("\n\n")
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(Buffer().writeUtf8(body)),
        )
    }

    /** 取第 [index] 次请求的 body（缓存，避免 MockWebServer 的出队语义）。 */
    private val takenBodies = mutableListOf<String>()

    private fun bodyOf(index: Int): String {
        while (takenBodies.size <= index) {
            val req = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
                ?: return ""
            takenBodies += req.body.readUtf8()
        }
        return takenBodies[index]
    }

    /** 一个永远成功的空工具（给「模型一直要求调工具」的场景用）。 */
    private class EchoTool : Tool() {
        override val name = "Echo"
        override val description = "回显"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "text" to ToolSchema.string("要回显的文本"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            ToolResult.ok("ok")
    }

    private fun dummyCtx(): ToolContext = ToolContext(
        cwd = "/tmp",
        cancelSignal = kotlinx.coroutines.Job(),
        ui = com.ccm.app.core.tool.ToolUiCallback.NoOp,
    )
}
