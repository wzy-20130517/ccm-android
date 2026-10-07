package com.ccm.app.core.agent

import com.ccm.app.core.api.ApiClient
import com.ccm.app.core.api.ApiTypes
import com.ccm.app.core.image.ImageProcessor
import com.ccm.app.core.image.ImageScaler
import com.ccm.app.core.session.ContentBlock
import com.ccm.app.core.trace.TraceEvents
import com.ccm.app.core.trace.TraceStore
import com.ccm.app.core.session.Message
import com.ccm.app.core.tool.Attachment
import com.ccm.app.core.tool.SubAgentSpec
import com.ccm.app.core.tool.SubAgentResult
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolRunner
import com.ccm.app.core.tool.ToolStorage
import com.ccm.app.core.tool.ToolUiCallback
import com.ccm.app.tools.phone.PhoneTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
 * ## 运行模式（deep / plan / watch）
 * 三个模式的状态在 [ModeState] 里（与模式工具共享同一实例），本类只**读**：
 * - `deepMode` → 每轮 run 开始把 maxTurns 提到 [ModeState.DEEP_MAX_TURNS]
 * - `planMode` → 每轮拼系统提示词时追加 [ModeState.PLAN_PROMPT]
 * - `watchMode` → 循环条件不再看 maxTurns，纯文本回复后注入「继续」保持循环
 *
 * **为什么状态不在本类**：模式工具（EnterDeepMode 等）在本类**之前**构造
 * （`core` 不能依赖 `tools`，工具只能拿到回调/共享对象）。所以状态的持有者
 * 是第三个对象，两边都引用它 —— 与 CLI 的 `new DeepMode()` + 闭包同构。
 *
 * ## 零 Android 依赖
 * 本类（及整个 `core/`）**不引用任何 `android.*`**，这样业务逻辑能在纯 JVM 单测里跑。
 * base64 用 `java.util.Base64`（API 26+ 可用，与 minSdk 一致），不用 `android.util.Base64`。
 */
class AgentLoop(
    private var api: ApiClient,
    /**
     * 系统提示词。
     *
     * 【2026-10-06 P1-4 修】原来传的是**字符串快照** —— 装配时算一次就固定。
     * 后果：Memory 工具写了 CLAUDE.md、/me 改了用户资料、/style 换了风格，
     * 模型**本会话内永远看不见**（要么重启要么切会话才刷新）。
     * 用户报「写了记忆但 Agent 像没看见」就是这个。
     *
     * 现在支持传**提供者函数**（对齐 CLI 的 `systemPrompt: () => getCurrentSystemPrompt()`）
     * —— 每次调用模型前现算，改动立即生效。
     * 传字符串时包装成常量提供者（向后兼容，单测/子 Agent 场景）。
     */
    private val systemPromptProvider: () -> String,
    /**
     * 工具列表**提供者**（不是快照！见类注释规则 1）。
     *
     * 用法：`AgentLoop(..., toolsProvider = { registry.list })`
     */
    private val toolsProvider: () -> List<Tool>,
    /** 最大轮次（构造值；运行期改请用 [extendMaxTurns] / [setMaxTurns]）。 */
    private val maxTurnsInit: Int = ModeState.NORMAL_MAX_TURNS,
    /** 工作目录。 */
    private val cwd: String = "/",
    /** 额外可访问目录（`/add-dir`）。 */
    private val extraDirs: List<String> = emptyList(),
    /** 权限模式。 */
    permissionModeInit: String = "default",
    /**
     * 运行模式状态（deep / plan / watch）—— **必须与模式工具用同一个实例**。
     *
     * 默认值 `ModeState()` 只保证不 NPE（单测/子 Agent 场景）。
     * **生产环境必须由 AppContainer 传入共享实例** —— 否则工具改的是另一个对象，
     * 表现为「EnterDeepMode 说进入成功了，但轮数一点没变」。
     */
    private val modes: ModeState = ModeState(),
    /** 应用存储（工具用）。 */
    private val storage: ToolStorage? = null,
    /** 只读配置快照（工具用）。 */
    private val settings: com.ccm.app.core.tool.ToolSettings? = null,
    /** 会话 id。 */
    private val sessionId: String = "",
    /** 派生子 Agent 的能力（由上层注入）。 */
    private val spawnSubAgent: (suspend (SubAgentSpec) -> SubAgentResult)? = null,
    /**
     * 识图专用客户端（2026-09-29 接线；2026-10-06 修正语义）。
     *
     * 语义（对齐 CLI index.mjs:785）：**当前 Provider 没有视觉能力**
     * （vision 未开）且配了备用识图 Provider 时由 AppContainer 传入 ——
     * 用户发图时先让备用模型生成文字描述，再把描述交给主模型，
     * 主模型不支持视觉也不会 400。识图失败回退直接带图。
     *
     * null = 不路由，此时按原行为把图直接交给当前模型
     * （当前模型有视觉时这是正确路径）。
     */
    private var visionClient: ApiClient? = null,
    /**
     * trace 目录（`null` = 不记录）。
     *
     * **强烈建议传** —— Node 版那些最难查的 bug（「只有 WebSearch 被掐死」
     * 「一次超时后全卡」「十几分钟一声不响」）全靠 trace 定位，
     * 光看现象根本猜不到。见 [TraceStore] 类注释。
     */
    private val traceDir: java.io.File? = null,
    /**
     * 图片缩放器（生产用 Android 的，单测用 JVM 的）。
     *
     * `null` = 不缩放，图片原样注入。**生产环境应该传** ——
     * 一张 4000×3000 的手机原图不缩放要 16000 token（吃掉半个上下文），
     * 且部分网关会因解码像素数超限直接拒掉请求。
     */
    private val imageScaler: ImageScaler? = null,
    /**
     * 工具执行器（**唯一入口**，见 [ToolRunner] 类注释）。
     *
     * 默认 [ToolRunner.Unset] 只保证不 NPE；**生产环境必须注入真正的实现**
     * （`tools/ToolExecutor`），否则校验/权限/hook/截断全部失效。
     */
    private val toolRunner: ToolRunner = ToolRunner.Unset,
    /** 是否开启流式。 */
    private val useStream: Boolean = true,
    /**
     * 队友消息自动送达（2026-10-06 修 P0）。
     *
     * 提示词和工具描述都承诺「队友消息自动送达，不用轮询」，但 AgentLoop
     * 原来**零实现** —— 模型按提示词不调 CheckMessages，就永久等一个
     * 永远不会来的消息（比没实现更毒：提示词在诱导踩坑）。
     *
     * 注入方式：由上层（AppContainer）传一个函数，返回「本 Agent 当前身份
     * 所在团队 + 未读消息」。AgentLoop 每轮开头调它，有未读就注入一条
     * user 消息（标注「队友消息 · 自动送达」）。
     *
     * 返回 null = 不在任何团队（多数情况，零开销）。
     */
    private val teamInboxProvider: (() -> Pair<String, List<String>>?)? = null,
) {
    /**
     * 兼容构造：直接传字符串提示词（旧签名）。
     *
     * 内部包成常量提供者 —— 老调用点（单测、子 Agent、EngineSetup）不用改。
     */
    constructor(
        api: ApiClient,
        systemPrompt: String,
        toolsProvider: () -> List<Tool>,
        maxTurnsInit: Int = ModeState.NORMAL_MAX_TURNS,
        cwd: String = "/",
        extraDirs: List<String> = emptyList(),
        permissionModeInit: String = "default",
        modes: ModeState = ModeState(),
        storage: ToolStorage? = null,
        settings: com.ccm.app.core.tool.ToolSettings? = null,
        sessionId: String = "",
        spawnSubAgent: (suspend (SubAgentSpec) -> SubAgentResult)? = null,
        visionClient: ApiClient? = null,
        traceDir: java.io.File? = null,
        imageScaler: ImageScaler? = null,
        toolRunner: ToolRunner = ToolRunner.Unset,
        useStream: Boolean = true,
        teamInboxProvider: (() -> Pair<String, List<String>>?)? = null,
    ) : this(
        api = api,
        systemPromptProvider = { systemPrompt },
        toolsProvider = toolsProvider,
        maxTurnsInit = maxTurnsInit,
        cwd = cwd,
        extraDirs = extraDirs,
        permissionModeInit = permissionModeInit,
        modes = modes,
        storage = storage,
        settings = settings,
        sessionId = sessionId,
        spawnSubAgent = spawnSubAgent,
        visionClient = visionClient,
        traceDir = traceDir,
        imageScaler = imageScaler,
        toolRunner = toolRunner,
        useStream = useStream,
        teamInboxProvider = teamInboxProvider,
    )


    /**
     * 当前最大轮次。
     *
     * ⚠️ **必须是 `@Volatile`**：续轮（[extendMaxTurns]）可能在别的协程里调
     * （子 Agent 走工具回调），而 `run()` 的 while 循环在另一个协程读它。
     * 不加的话可能读到旧值 —— 表现为「续了轮但没生效」。
     */
    @Volatile
    private var maxTurns: Int = maxTurnsInit

    /** 对话历史（协议无关的中间表示）。 */
    private val messages = mutableListOf<Message>()

    /**
     * mid-turn steering 队列（2026-10-06 加，对齐 CLI agent.mjs 的
     * `_steeringQueue`）。
     *
     * 【解决什么】用户/其他 Agent 想在**当前轮运行中**补充指令或纠偏，
     * 但不想打断正在跑的工具批次。投进来的文本会在**下一轮模型调用前**
     * 注入（作为 user 消息），不打断当前批次。
     *
     * 【谁在用】ChatSession.send 在 isRunning 时入队（原来直接丢弃）；
     * 子 Agent 的 SendMessage 投递也可走这里。
     */
    private val steeringQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** 投入一条 steering 指令（下一轮注入）。线程安全。 */
    fun pushSteering(text: String) {
        if (text.isNotBlank()) steeringQueue.add(text.trim())
    }

    /** 取走所有待注入的 steering 指令（内部用）。 */
    private fun pullSteering(): List<String> {
        if (steeringQueue.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        while (true) {
            val t = steeringQueue.poll() ?: break
            out.add(t)
        }
        return out
    }

    /** 已执行的轮次。 */
    var turnCount: Int = 0
        private set

    /** 累计 token 用量。 */
    private var totalInputTokens = 0
    private var totalOutputTokens = 0

    /**
     * 当前权限模式。
     *
     * 与 [maxTurns] 同理：UI 可能在运行中改（用户切模式），
     * 而工具执行在另一个协程读它。
     */
    @Volatile
    private var permissionMode: String = permissionModeInit

    /** 中断标志。 */
    @Volatile
    private var aborted = false

    /** 当前 trace（每轮 run 建一个）。 */
    private var trace: TraceStore? = null

    /** 续轮次数（ExtendTurns 用，最多 [MAX_EXTENSIONS] 次）。 */
    @Volatile
    private var extensionCount: Int = 0

    /** 主动中断当前 run。 */
    fun abort() {
        aborted = true
        api.cancelActiveStream()
    }

    /** 设置最大轮次（deep 模式用）。**不计数、不受闸门限制** —— 这是用户/系统行为。 */
    fun setMaxTurns(n: Int) {
        maxTurns = n.coerceAtMost(MAX_TURNS_HARD_CAP)
    }

    /**
     * 换 API 客户端（热更新 —— /key /url /model 等改完配置后由
     * [com.ccm.app.core.AppContainer.refreshApi] 调）。
     *
     * 正在跑的一轮持有旧引用跑完不受影响，下一轮自动用新实例。
     */
    fun swapClients(newApi: ApiClient, newVision: ApiClient?) {
        api = newApi
        visionClient = newVision
    }

    /**
     * 续轮（子 Agent 调 `ExtendTurns` 工具时走这里）。
     *
     * ## 三道闸门（**约束必须在持有实例的这一层做**）
     * 1. 单次 ≤ [MAX_EXTENSION_PER_CALL]（60）
     * 2. 最多 [MAX_EXTENSIONS] 次（4）
     * 3. 硬上限 [MAX_TURNS_HARD_CAP]（400）
     *
     * **为什么工具层做了这里还要做**：工具层是「模型友好」的提示，
     * 但它是**模型可绕过的**（将来加新调用路径、或模型直接调内部方法）。
     * 真正的约束必须在持有 `maxTurns` 的地方。
     *
     * ## 为什么上限是 400 不是无限
     * 子 Agent 在后台跑，用户看不见。给它无限轮次 = 可能烧光额度还不出结果。
     * 400 轮足够跑完一个复杂任务，也够用户反应过来去中止。
     *
     * @param turns 本次要追加的轮数（会被夹取到 1..60）
     * @param reason 为什么需要续（记进 trace，也给用户看）
     * @return 给模型看的结果文本
     */
    fun extendMaxTurns(turns: Int, reason: String): String {
        // 闸门 1：单次夹取
        val add = turns.coerceIn(1, MAX_EXTENSION_PER_CALL)

        // 闸门 2：次数
        if (extensionCount >= MAX_EXTENSIONS) {
            return "已达续轮次数上限（$MAX_EXTENSIONS 次）。请收尾：把已完成的、未完成的、" +
                "以及卡在哪里如实交代清楚，不要无声中断。"
        }

        // 闸门 3：硬上限
        val target = (maxTurns + add).coerceAtMost(MAX_TURNS_HARD_CAP)
        val actualAdd = target - maxTurns
        if (actualAdd <= 0) {
            return "已达轮数硬上限（$MAX_TURNS_HARD_CAP 轮），无法再续。请立刻收尾并如实交代未完成部分。"
        }

        maxTurns = target
        extensionCount++

        trace?.emit(
            TraceEvents.RUN_START,   // 复用 run_start 类型记续轮（同属「运行配置变更」）
            mapOf(
                "kind" to "extend_turns",
                "requested" to turns,
                "added" to actualAdd,
                "new_max" to target,
                "extension_count" to extensionCount,
                "reason" to reason.take(300),
            ),
        )

        return "已续 $actualAdd 轮（当前上限 $target，第 $extensionCount/$MAX_EXTENSIONS 次续轮）。" +
            "继续干活，但注意：真做完了就收尾，不要在原地打转。"
    }

    /** 当前续轮次数（UI 可显示）。 */
    val extensionsUsed: Int get() = extensionCount

    /** 设置权限模式。 */
    fun setPermissionMode(mode: String) {
        permissionMode = mode
    }

    /** 读当前历史（会话保存用）。 */
    fun getHistory(): List<Message> = messages.toList()

    /**
     * 覆盖历史（会话恢复 / /compact / /clear / /rewind 用）。
     *
     * 【2026-10-07】替换后**重估**上下文占用（对齐 CLI `agent.mjs:setHistory`）：
     * lastPromptTokens 是上一次 API 返回的 prompt_tokens（压缩前的大数字），
     * 不重估的话水位判定读旧值 → blocking 拒发 → 请求发不出去 → 值永远
     * 不更新 → 死循环（「刚 compact 过还是被拦」）。
     * 估算 4 字符 ≈ 1 token（与 Compactor 同口径），下次真实请求成功后
     * 会被准确值替换；空历史时估出 system prompt 部分，同样能归零水位。
     */
    fun setHistory(history: List<Message>) {
        messages.clear()
        messages.addAll(history)
        try {
            val est = estimateHistoryTokens()
            if (est > 0) {
                lastPromptTokens = est
                isApproxPromptTokens = true
            }
        } catch (_: Throwable) { /* 估算失败不影响设历史本身 */ }
    }

    /**
     * 按当前历史粗估 token 数（4 字符 ≈ 1 token）。
     *
     * 含 system prompt —— 它也是请求的一部分，只算历史会系统性低估。
     */
    private fun estimateHistoryTokens(): Int {
        var chars = 0
        for (m in messages) {
            for (b in m.content) {
                when (b) {
                    is ContentBlock.Text -> chars += b.text.length
                    is ContentBlock.ToolUse -> chars += b.input.toString().length
                    is ContentBlock.ToolResult -> chars += b.content.length
                    is ContentBlock.Image -> chars += 3000   // 图片粗算
                    else -> {}
                }
            }
        }
        val sp = try { systemPromptProvider().length } catch (_: Throwable) { 0 }
        return (chars + sp + 3) / 4
    }

    /** 追加一条用户消息（不触发 run）。 */
    fun addUserMessage(text: String) {
        messages += Message.user(text)
    }

    /** 累计用量（UI 状态行显示）。 */
    fun getTotalUsage(): Pair<Int, Int> = totalInputTokens to totalOutputTokens

    /**
     * 最近一次请求的真实 prompt_tokens（0 = 还没发过）。
     *
     * 【2026-10-06 P1-8】AutoCompact 的 shouldCompact 用它判断水位 ——
     * 比估算准（估算会把工具输出算错）。
     */
    @Volatile
    var lastPromptTokens: Int = 0
        private set

    /**
     * [lastPromptTokens] 是否为本地估算值（/context 显示 `~` 前缀用）。
     * 真实请求返回 usage 时由 [emitUsage] 清除。
     */
    @Volatile
    var isApproxPromptTokens: Boolean = false
        private set

    /**
     * 工具前压缩检查点回调（对齐 CLI `index.mjs` 的 `beforeToolCall`）。
     *
     * 每批工具执行前（[executeTools] 开头）调一次；是否达到压缩条件、
     * 30s 防抖都在实现方（ChatSession）判断 —— AgentLoop 只负责触发。
     * null = 未接线（单测场景）。
     */
    @Volatile
    var beforeToolCallHook: (suspend () -> Unit)? = null

    /**
     * 工具存储根目录（问题40：/compact 写备份用）。
     *
     * 返回 null = 未注入 storage（单测场景）。
     */
    fun toolStorageRoot(): java.io.File? = storage?.rootDir

    /**
     * 跑一轮完整对话。
     *
     * @param userMessage 用户输入
     * @return 事件流。**必须在协程里 collect**，否则不会执行（冷流）。
     */
    fun run(userMessage: String, imagePaths: List<String> = emptyList()): Flow<AgentEvent> = channelFlow {
        // 显式把 send 包成 emit —— 避免用 ProducerScope 扩展函数（隐式接收者在
        // 嵌套 coroutineScope/async 里容易解析到错误的作用域）
        val emit: suspend (AgentEvent) -> Unit = { ev -> send(ev) }

        // 用户消息带图（2026-09-28 第18批）：imagePaths 空时走原路径零变化。
        // 图在协程内加载（readImageAsBase64/ImageProcessor 都是 IO，
        // 放在 run 外会让 send() 阻塞主线程）。
        if (imagePaths.isEmpty()) {
            messages += Message.user(userMessage)
        } else {
            // ★ 图片识别路由（2026-09-29）：配置了独立识图 provider 时，
            //   先让识图模型把图翻译成文字描述，主模型只收文本 ——
            //   主模型不支持视觉也不 400。任何失败（超时/拒答/无 client）
            //   都回退到「直接带图」的原行为，绝不中断对话。
            val visionDesc: String? = visionClient?.let { vc ->
                try {
                    describeImagesViaVision(vc, imagePaths)
                } catch (_: Throwable) {
                    null
                }
            }

            val blocks = mutableListOf<ContentBlock>()
            if (visionDesc != null) {
                blocks += ContentBlock.Text(
                    userMessage + "\n\n[图片内容 —— 由识图模型转述]\n" + visionDesc,
                )
            } else {
                blocks += ContentBlock.Text(userMessage)
                for (path in imagePaths) {
                    val loaded = imageScaler?.let { ImageProcessor.loadOrNull(path, it) }
                    if (loaded != null) {
                        blocks += ContentBlock.Image(loaded.base64, loaded.mimeType)
                    } else {
                        val b64 = readImageAsBase64(path)
                        if (b64 != null) {
                            val mime = guessImageMime(path)
                            blocks += ContentBlock.Image(b64, mime)
                        } else {
                            blocks += ContentBlock.Text("[图片读取失败: $path]")
                        }
                    }
                }
            }
            messages += Message(Message.ROLE_USER, blocks)
        }
        turnCount = 0
        aborted = false

        // ── 模式 → 轮次上限（对齐 CLI `index.mjs`：每轮 run 前 setMaxTurns(deepMode.getMaxTurns())）──
        //
        // 【为什么每轮重算而不是在工具里改】幂等：不管状态是被工具、slash 命令
        // 还是别处改的，下一轮 run 一定拿到正确的上限。工具里改的做法要求
        // 「每个改动点都记得同步 maxTurns」，漏一处就是静默失效。
        //
        // ⚠️ **续过轮的实例不覆盖**：`ExtendTurns` 是子 Agent 的自救机制
        // （单次 +60、最多 4 次），它加的轮数不该被下一轮 run 打回原形。
        // 主 Agent 从不续轮（extensionCount 恒为 0），所以不受影响。
        if (extensionCount == 0) {
            maxTurns = if (modes.deepMode) maxOf(maxTurnsInit, ModeState.DEEP_MAX_TURNS) else maxTurnsInit
        }

        // 每轮 run 一个 trace 文件（jsonl），结束后 end()
        val tr = traceDir?.let { TraceStore(dir = it) }
        trace = tr
        tr?.emit(
            TraceEvents.RUN_START,
            mapOf(
                "kind" to "agent",
                "input" to com.ccm.app.core.trace.Redactor.preview(userMessage, 800),
                "max_turns" to maxTurns,
                "message_count_before" to (messages.size - 1),
                "tool_count" to toolsProvider().size,
                // 模式快照（排查「为什么这轮跑了 300 次」时先看这个）
                "deep_mode" to modes.deepMode,
                "plan_mode" to modes.planMode,
                "watch_mode" to modes.watchMode,
            ),
        )

        try {
            // 空响应重试计数（连续几轮都吐空 → 放弃并如实报告）
            var emptyRetries = 0
            // 输出截断重试计数（finish_reason=length，最多 3 次）
            var maxOutputRetries = 0

            // 循环条件实时读 watchMode：
            // - 关着 → 正常的「轮次上限」语义
            // - 开着 → 不受 maxTurns 限制，直到 ExitWatch 或用户打断
            //   （对齐 CLI `while (this.watchMode || this.turnCount < this.maxTurns)`）
            // 轮次提醒只发一次（对齐 CLI 的 _turnLimitWarned）
            var turnLimitWarned = false

            while (modes.watchMode || turnCount < maxTurns) {
                if (aborted) throw CancellationException("用户中断")
                turnCount++

                // ── 轮次上限提醒（2026-10-06 补，对齐 CLI agent.mjs:236-252）──
                //
                // 子 Agent 提示词写着「快到上限时会收到系统提示，用 ExtendTurns
                // 续轮」、ExtendTurns 工具描述写着「收到『距上限只剩不到 10 轮』
                // 提醒时」—— 但 APK **从不发送这个提醒**，子 Agent 永远等不到
                // 触发时机，200 轮被砍断时毫无预警，交半成品。
                // deep/watch 模式不提醒（上限本来就大/无限制）。
                if (!modes.watchMode && !modes.deepMode && !turnLimitWarned) {
                    val remaining = maxTurns - turnCount + 1
                    if (remaining in 1..10) {
                        turnLimitWarned = true
                        messages += Message(
                            role = Message.ROLE_USER,
                            hidden = true,
                            content = listOf(ContentBlock.Text(
                                "（系统提示：本轮任务已执行 $turnCount 轮，距 maxTurns 上限（$maxTurns）" +
                                    "只剩不到 10 轮。先自查最近几轮是不是在**空转**：" +
                                    "反复调用同一个工具却拿不到新信息、同一处改了又改、" +
                                    "同一个错误反复出现而没有实质推进。" +
                                    "① 不是空转、任务确实还需要更多轮才能做完 —— 立即调用 " +
                                    "EnterDeepMode（主 Agent）或 ExtendTurns（子 Agent）续轮，" +
                                    "然后继续干；不要因为快到上限就草草收尾。" +
                                    "② 是空转 —— 停下来如实收尾：卡在哪、已完成什么、还剩什么，" +
                                    "不要无声中断。不要明知在原地打转还硬撑，" +
                                    "也不要为了省轮数交未验证的半成品。）"
                            )),
                        )
                    }
                }

                // ── 队友消息自动送达（2026-10-06）────────────────────────
                // 每轮开头拉一次未读；有就作为 user 消息注入历史。
                // 提示词承诺的「标注『队友消息 · 自动送达』」就体现在这里。
                try {
                    teamInboxProvider?.invoke()?.let { (team, msgs) ->
                        if (msgs.isNotEmpty()) {
                            messages += Message(
                                role = Message.ROLE_USER,
                                content = listOf(ContentBlock.Text(
                                    "【队友消息 · 自动送达】（团队 $team）\n\n" +
                                        msgs.joinToString("\n\n")
                                )),
                            )
                        }
                    }
                } catch (_: Throwable) {}

                // ── mid-turn steering（2026-10-06）──────────────────────
                // 运行中用户/队友投进来的补充指令，在**下一轮调用前**注入，
                // 不打断当前工具批次（对齐 CLI agent.mjs:265-275）。
                try {
                    val extras = pullSteering()
                    if (extras.isNotEmpty()) {
                        messages += Message(
                            role = Message.ROLE_USER,
                            hidden = false,   // 用户可见（他知道自己说了什么）
                            content = listOf(ContentBlock.Text(
                                extras.joinToString("\n\n")
                            )),
                        )
                    }
                } catch (_: Throwable) {}

                // 每次新的 API 响应 = 新 messageId（含重试）。
                // UI 据此丢弃上一轮的半截输出（见 AgentEvent 类注释）。
                val messageId = java.util.UUID.randomUUID().toString()
                val assistant = callModel(emit, messageId) ?: break

                // 用户中断发生在流中途 → 流被 cancel，这里拿到的是**半截回复**。
                // 必须先判中断再判空响应：否则会往历史里塞一条「空响应提示语」，
                // 污染会话（用户下次打开会看到一条莫名其妙的系统消息）。
                if (aborted) throw CancellationException("用户中断")

                // ── max_output_tokens 截断 → 注入「继续」重试（2026-10-06 P1-7）──
                //
                // 对齐 CLI agent.mjs:585-596：finish_reason=length 说明回复被
                // 输出上限截断（模型没说完），此时**不能**当正常结束 ——
                // 用户看到半截回复，得手动催「继续」。
                // 注入系统提示让模型从断点续写，最多 3 次（防死循环）。
                if (assistant.finishReason == "length" && assistant.text.isNotEmpty()) {
                    maxOutputRetries++
                    if (maxOutputRetries <= 3) {
                        appendAssistantText(assistant)
                        messages += Message.user(
                            "（系统提示：上一条回复因达到 max_output_tokens 上限被截断，" +
                                "非用户打断。请直接从断点处继续输出，不要道歉、不要重复已写内容。）",
                            hidden = true,
                        )
                        emit(AgentEvent.TurnEnd(turnCount))
                        continue   // 跳过工具执行，直接下一轮 API 调用
                    }
                }
                maxOutputRetries = 0

                if (assistant.toolCalls.isEmpty()) {
                    // ── 空响应 / 占位符回复 → 重试（对齐 Node 版 `onlyPlaceholder` 处理）──
                    //
                    // 【为什么必须单独处理】历史里可能残留 `(continue)` 这类
                    // **内部占位符**（角色交替补位用），模型看到就照着学、原封不动
                    // 吐一个回来。此时 content 非空，只判 `isEmpty` 会当成正常回复
                    // —— 那一轮白掉，用户还得手动催一次。
                    if (isEmptyOrPlaceholder(assistant)) {
                        if (emptyRetries < MAX_EMPTY_RETRIES) {
                            emptyRetries++
                            trace?.emit(
                                TraceEvents.RUN_START,   // 复用（同属「运行中异常续跑」）
                                mapOf(
                                    "kind" to "empty_retry",
                                    "attempt" to emptyRetries,
                                    "text_len" to assistant.text.length,
                                    "text_preview" to assistant.text.take(80),
                                ),
                            )
                            messages += Message.user(EMPTY_RESPONSE_HINT, hidden = true)
                            continue
                        }
                        // 重试耗尽：如实告诉用户，不要静默结束（静默 = 用户以为程序卡了）
                        emit(
                            AgentEvent.Error(
                                "模型连续 $MAX_EMPTY_RETRIES 次返回空响应，已停止重试。" +
                                    "可能原因：网关返回了空内容、或模型不兼容当前协议。",
                                ToolResult.UNKNOWN,
                            )
                        )
                        break
                    }

                    // ── 正常纯文本回复 → **必须写进历史** ──
                    //
                    // 【修的是真实 bug】早期实现只调 `appendToolResults()`（有工具调用时才走），
                    // 于是**纯文本回复永远不进历史** —— 用户问「你好」，模型答「你好」，
                    // 下一轮模型看不到自己说过什么，表现为「多轮对话失忆、反复自我介绍」。
                    appendAssistantText(assistant)

                    // ── 持续模式（watch）：不结束，注入「继续」让循环保持 ──
                    //
                    // 用于盯队列/持续任务。对齐 CLI `agent.mjs`：
                    // assistant 正文**已经写进历史**，这里只追加下一轮 user 指令 ——
                    // 避免每轮重复保存同一份正文导致上下文/费用膨胀。
                    //
                    // 发 TurnEnd 让 UI 把这一轮的流式内容定型成气泡：
                    // 不切的话十轮的正文会攒成一个巨型气泡。
                    if (modes.watchMode) {
                        messages += Message.user(ModeState.WATCH_CONTINUE_PROMPT, hidden = true)
                        emit(AgentEvent.TurnEnd(turnCount))
                        continue
                    }
                    break
                }
                emptyRetries = 0

                val results = executeTools(assistant.toolCalls, emit, messageId)
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
            // trace 收尾（记录轮数与状态）
            try {
                tr?.end(mapOf("status" to "completed", "turns" to turnCount, "message_count" to messages.size))
                trace = null
            } catch (_: Throwable) {
            }
            // 保证恰好发一次 Done —— UI 靠它收尾（关 spinner、恢复输入框）。
            //
            // ⚠️ 必须包在 `NonCancellable` 里：用户中断时 channelFlow 的
            // 作用域已被取消，此时 `send()` 会**直接抛 CancellationException**
            // —— Done 永远送不出去，UI 停在「生成中」转圈，输入框也锁着。
            // 用户只能杀进程。这是「中断后界面卡死」的根因。
            withContext(NonCancellable) {
                try {
                    emit(AgentEvent.Done)
                } catch (_: Throwable) {
                    // 连 NonCancellable 都发不出去（channel 已关）→ 只能放弃，
                    // 但至少不要因为这个异常把 trace 收尾也带崩
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    // ═════════════════════════ 模型调用 ═════════════════════════

    /**
     * 调模型（流式），把流事件转成 [AgentEvent]。
     *
     * @return assistant 回复；null = 无响应（防御性返回）
     */
    private suspend fun callModel(
        emit: suspend (AgentEvent) -> Unit,
        messageId: String,
    ): AssistantTurn? {
        val textSb = StringBuilder()
        val reasoningSb = StringBuilder()
        var doneFinishReason: String? = null

        val apiMessages = buildApiMessages()
        val toolDefs = toolsProvider().map {
            ApiTypes.ToolDefinition(
                name = it.name,
                description = it.description,
                parameters = it.inputSchema,
            )
        }

        val apiStartedAt = System.currentTimeMillis()
        trace?.emit(
            TraceEvents.API_REQUEST,
            mapOf(
                "turn" to turnCount,
                "stream" to useStream,
                "message_count" to apiMessages.size,
                "tool_count" to toolDefs.size,
            ),
        )

        if (!useStream) {
            // 非流式（compact 摘要等短请求）
            val resp = api.chat(effectiveSystemPrompt(), apiMessages, toolDefs)
            trace?.emit(
                TraceEvents.API_RESPONSE,
                mapOf(
                    "turn" to turnCount,
                    "duration_ms" to (System.currentTimeMillis() - apiStartedAt),
                    "prompt_tokens" to resp.usage.promptTokens,
                    "completion_tokens" to resp.usage.completionTokens,
                    "tool_calls" to resp.toolCalls.size,
                ),
            )
            if (resp.text.isNotEmpty()) {
                textSb.append(resp.text)
                emit(AgentEvent.TextDelta(resp.text, messageId))
            }
            resp.reasoning?.takeIf { it.isNotEmpty() }?.let {
                reasoningSb.append(it)
                emit(AgentEvent.ReasoningDelta(it, messageId))
            }
            emitUsage(emit, resp.usage)
            return AssistantTurn(textSb.toString(), reasoningSb.toString(), resp.toolCalls, resp.finishReason)
        }

        // 流式
        api.stream(effectiveSystemPrompt(), apiMessages, toolDefs).collect { ev ->
            when (ev) {
                is ApiTypes.StreamEvent.Text -> {
                    textSb.append(ev.text)
                    emit(AgentEvent.TextDelta(ev.text, messageId))
                }

                is ApiTypes.StreamEvent.Reasoning -> {
                    reasoningSb.append(ev.text)
                    emit(AgentEvent.ReasoningDelta(ev.text, messageId))
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

                is ApiTypes.StreamEvent.Done -> {
                    // 【2026-10-06】记下结束原因 —— 循环里据此判断是否被截断
                    doneFinishReason = ev.finishReason
                }
            }
        }

        // 流结束 —— 此时工具参数才拼装完整
        val toolCalls = api.lastToolCalls()
        trace?.emit(
            TraceEvents.API_RESPONSE,
            mapOf(
                "turn" to turnCount,
                "duration_ms" to (System.currentTimeMillis() - apiStartedAt),
                "text_len" to textSb.length,
                "reasoning_len" to reasoningSb.length,
                "tool_calls" to toolCalls.size,
            ),
        )
        // 到此刻为止累积的正文 —— 作为每个工具的 textBefore（对齐 Web
        // web/server.mjs:2526 的 `textSoFar`）。UI 据此把正文与工具
        // 按真实顺序交错渲染，而不是把整轮文字堆到最后。
        val textSoFar = textSb.toString()
        // 同理带上思考（**本轮**的 reasoningSb）——
        // ⚠️ 注意这是单轮值：AgentLoop 每次 callModel 都新建 reasoningSb。
        // UI 侧要的是**跨轮累积**串（能跟前缀匹配），所以 ChatSession
        // 在存 ToolCard 时用的是它自己的 thinkingBuf，**不是**这个字段。
        // 这里保留是因为事件本身该携带完整信息（未来别处可能要用）。
        val thinkingSoFar = reasoningSb.toString()
        toolCalls.forEach { tc ->
            val parsed = parseArgs(tc.arguments)
            emit(
                AgentEvent.ToolStart(
                    tc.id, tc.name, parsed, formatInputPreview(tc.name, parsed),
                    textSoFar, thinkingSoFar,
                ),
            )
        }

        return AssistantTurn(textSb.toString(), reasoningSb.toString(), toolCalls, doneFinishReason)
    }

    private suspend fun emitUsage(
        emit: suspend (AgentEvent) -> Unit,
        usage: ApiTypes.TokenUsage,
    ) {
        if (usage.isEmpty) return
        totalInputTokens += usage.promptTokens
        lastPromptTokens = usage.promptTokens   // P1-8：AutoCompact 水位判断用
        isApproxPromptTokens = false             // 真实 usage 回来了，清掉 setHistory 的估算标记
        totalOutputTokens += usage.completionTokens
        emit(AgentEvent.Usage(usage.promptTokens, usage.completionTokens))
    }

    /**
     * 把会话历史转成 API 线格式（协议无关的中间形态）。
     *
     * 单块纯文本消息走 `content: "..."` 简写（省 token，也是各家 API 的常规形态）；
     * 多块或含工具的消息走数组形态。
     */
    private fun buildApiMessages(): List<JsonObject> =
        ensureAlternatingRoles(messages.map { m ->
        val singleText = m.content.singleOrNull() as? ContentBlock.Text
        buildJsonObject {
            put("role", JsonPrimitive(m.role))
            if (singleText != null) {
                // 文本清理（P1-5）：孤立 surrogate / 控制字符会让部分 API 直接 400
                put("content", JsonPrimitive(sanitizeTextForApi(singleText.text)))
            } else {
                put("content", buildJsonArray {
                    m.content.forEach { block -> add(blockToJson(block)) }
                })
            }
        }
    })

    /**
     * 确保 assistant/user 交替出现（对齐 CLI `ensureAlternatingRoles`）。
     *
     * 【2026-10-06 P1-5】OpenAI 系端点不接受相邻同 role：
     *   · 相邻两个 assistant → 插 `(continue)` 空 user 占位
     *   · 相邻两个 user（纯文本）→ 合并内容
     *   · 相邻两个 user（含结构块，如图片）→ 插 `(continue)` assistant 占位
     * 不修的话，某些网关直接 400（「相邻同 role」），对话卡死。
     *
     * 什么时候会相邻同 role：工具执行中断、图片加载失败、子 Agent 注入、
     * 系统提示注入等 —— 历史是累积出来的，不保证天然交替。
     */
    private fun ensureAlternatingRoles(msgs: List<JsonObject>): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        for (msg in msgs) {
            val last = out.lastOrNull()
            val lastRole = (last?.get("role") as? JsonPrimitive)?.content
            val role = (msg["role"] as? JsonPrimitive)?.content
            if (last != null && lastRole == role) {
                if (role == "assistant") {
                    // 相邻两个 assistant → 插空 user 占位
                    out += buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", JsonPrimitive("(continue)"))
                    }
                } else {
                    // 相邻两个 user：都是纯文本就合并，有结构块就插占位
                    val lastContent = last["content"]
                    val curContent = msg["content"]
                    if (lastContent is JsonPrimitive && curContent is JsonPrimitive) {
                        out[out.size - 1] = buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", JsonPrimitive(
                                listOf(lastContent.content, curContent.content)
                                    .filter { it.isNotBlank() }.joinToString("\n")
                            ))
                        }
                        continue
                    } else {
                        out += buildJsonObject {
                            put("role", JsonPrimitive("assistant"))
                            put("content", JsonPrimitive("(continue)"))
                        }
                    }
                }
            }
            out += msg
        }
        return out
    }

    /**
     * 发送前文本清理（对齐 CLI `sanitizeTextForApi`）。
     *
     * 【2026-10-06 P1-5】防「上下文污染导致整轮 400」：
     *   · 孤立 surrogate（未配对的 U+D800-DFFF）→ U+FFFD
     *     （来自终端乱码/截断粘贴，部分 API 直接拒）
     *   · C0 控制字符（\x00-\x08 \x0B \x0C \x0E-\x1F）与 DEL → 删
     *     （保留 \n \t \r）
     * 合法 emoji 都是合法码点，不受影响。
     */
    private fun sanitizeTextForApi(str: String): String {
        val sb = StringBuilder(str.length)
        var i = 0
        while (i < str.length) {
            val c = str[i]
            val code = c.code
            if (code in 0xD800..0xDBFF) {
                // 高代理：看下一个是不是低代理
                if (i + 1 < str.length && str[i + 1].code in 0xDC00..0xDFFF) {
                    sb.append(c).append(str[i + 1]); i += 2; continue
                }
                sb.append('\uFFFD')
            } else if (code in 0xDC00..0xDFFF) {
                sb.append('\uFFFD')
            } else if (code <= 0x08 || code == 0x0B || code == 0x0C ||
                (code in 0x0E..0x1F) || code == 0x7F
            ) {
                // 控制字符：丢弃（保留 \n \t \r）
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** 单个内容块 → 线格式 JSON。 */
    private fun blockToJson(block: ContentBlock): JsonObject = when (block) {
        is ContentBlock.Text -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(sanitizeTextForApi(block.text)))
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
        messageId: String,
    ): List<ToolExecResult> {

        // ── beforeToolCall 自动压缩检查点（对齐 CLI index.mjs:1983）────────
        //
        // 长任务单轮几十个工具，等 run 结束才压已经撞 400 了；达到压缩条件时
        // 先压完再继续工具。是否达到条件、30s 防抖都在回调里判断（ChatSession），
        // 这里只负责触发 —— 与 run 结束后的自动压缩并存，不是替换。
        try {
            beforeToolCallHook?.invoke()
        } catch (e: CancellationException) {
            throw e   // 外层取消不能被吞
        } catch (_: Throwable) {
            // 压缩失败不能把整批工具带崩（对齐 CLI beforeToolCall 的 crashLog 兜底）
        }

        val tools = toolsProvider()

        // 分区与调度交给 ToolDispatcher（那块逻辑独立可测，见其类注释）
        val indexed = toolCalls.mapIndexed { i, tc ->
            ToolDispatcher.IndexedCall(i, tc.id, tc.name, tc.arguments)
        }

        return ToolDispatcher.dispatch(indexed, tools) { call, job ->
            runOneTool(
                ApiTypes.ToolCall(call.id, call.name, call.arguments),
                tools, emit, job, messageId,
            )
        }
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
        messageId: String,
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
        val toolStartedAt = System.currentTimeMillis()

        trace?.emit(
            TraceEvents.TOOL_START,
            mapOf(
                "turn" to turnCount,
                "tool" to tc.name,
                "id" to tc.id,
                // 参数预览（Redactor 会截断 + 脱敏）
                "input" to com.ccm.app.core.trace.Redactor.preview(input.toString(), 400),
            ),
        )

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
            ui = makeUiCallback(emit, messageId, tc.id),
            spawnSubAgent = spawnSubAgent,
            storage = storage,
            settings = settings,
            sessionId = sessionId,
            // 【2026-10-06 问题40】把自己传进去 —— ExtendTurns 用它续轮
            selfLoop = this,
        )

        // 走统一执行入口（校验 → 权限 → hook → 执行 → 截断 → hook）。
        // ToolRunner 约定「永不抛异常」，但这里仍兜一层 —— 万一实现违约，
        // 不能让它把整个 Agent 循环带崩。
        val result = try {
            toolRunner.run(tool, input, ctx, sessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            ToolResult.Error(e.message ?: "工具执行异常", ToolResult.INTERNAL)
        }

        val elapsed = System.currentTimeMillis() - toolStartedAt
        // ⚠️ 记 tool_end 而不是 tool_error 是有意的：
        // 排查「工具被谁掐死」时，关键是**时序**（start/end 的时间差），
        // 光看结果文本判断不出「是自己失败还是被外部 abort」。
        trace?.emit(
            if (result.failed) TraceEvents.TOOL_ERROR else TraceEvents.TOOL_END,
            mapOf(
                "turn" to turnCount,
                "tool" to tc.name,
                "duration_ms" to elapsed,
                "is_error" to result.failed,
                "result_len" to result.textOrMessage.length,
                "category" to (result as? ToolResult.Error)?.category,
            ),
        )

        emit(AgentEvent.ToolResult(tc.id, tc.name, result.textOrMessage, result.failed))
        return ToolExecResult(tc.id, tc.name, result)
    }

    /**
     * 生成工具参数的**折叠态一行摘要**（给 UI 用）。
     *
     * 对齐 Node 版终端的显示习惯：`path="a.mjs", limit=50`。
     *
     * ## 为什么在 agent 层做而不是让 UI 做
     * 「哪些字段值得显示」是**业务判断**（每个工具不一样），属于 agent 层的信息。
     * 放 UI 层等于让 Compose 重复实现一遍，两边迟早漂移。
     *
     * ## 规则
     * 1. 常见键优先排在前面（`path` / `command` / `query` 等，见 [PREVIEW_KEY_ORDER]）
     * 2. 字符串值加引号；单值超过 [PREVIEW_VALUE_MAX] 字符截断加 `…`
     * 3. 用 `, ` 连接；整体超过 [PREVIEW_TOTAL_MAX] 字符再截断
     *
     * 空参数返回空串（UI 自己决定显示什么，如「无参数」）。
     */
    private fun formatInputPreview(toolName: String, input: JsonObject): String {
        if (input.isEmpty()) return ""

        // 按优先级排序：常见键在前，其余保持原序
        val ordered = input.entries.sortedBy { (k, _) ->
            val idx = PREVIEW_KEY_ORDER.indexOf(k)
            if (idx >= 0) idx else PREVIEW_KEY_ORDER.size
        }

        val parts = ordered.map { (key, value) ->
            val raw = when (value) {
                is JsonPrimitive -> if (value.isString) value.content else value.content
                else -> value.toString()
            }
            val shown = if (raw.length > PREVIEW_VALUE_MAX) {
                raw.take(PREVIEW_VALUE_MAX) + "…"
            } else {
                raw
            }
            // 字符串值加引号（数字/布尔不加，看起来更自然）
            val quoted = if (value is JsonPrimitive && value.isString) "\"$shown\"" else shown
            "$key=$quoted"
        }

        val joined = parts.joinToString(", ")
        return if (joined.length > PREVIEW_TOTAL_MAX) {
            joined.take(PREVIEW_TOTAL_MAX) + "…"
        } else {
            joined
        }
    }

    /** 给工具的 UI 回调（进度 → ToolProgress 事件）。 */
    private fun makeUiCallback(
        emit: suspend (AgentEvent) -> Unit,
        messageId: String,
        toolId: String,
    ): ToolUiCallback =
        object : ToolUiCallback {
            override suspend fun onProgress(text: String) {
                emit(AgentEvent.ToolProgress(id = toolId, text = text))
            }

            override suspend fun onContent(text: String) {
                emit(AgentEvent.TextDelta(text, messageId))
            }

            override suspend fun onPresent(kind: String, title: String?, caption: String?, content: String, paths: List<String>) {
                emit(AgentEvent.Present(kind, title, caption, content, paths))
            }
        }

    /** 权限判断：plan 模式只放行只读；default/acceptEdits 放行非破坏性。 */
    private fun isPermitted(tool: Tool): Boolean = when (permissionMode) {
        "bypassPermissions" -> true
        "plan" -> tool.isReadOnly
        "acceptEdits" -> !tool.isDestructive
        else -> tool.isReadOnly || !tool.isDestructive
    }

    /**
     * 本轮实际用的系统提示词 = 基础提示词 + 计划模式追加段。
     *
     * **每轮重算**（不是构造时缓存）—— 用户/AI 可能在任何时刻切模式，
     * 缓存会让切换延迟一轮生效。
     *
     * 对齐 CLI `index.mjs`：`base + planMode.getSystemPromptAddition() + ...`。
     * APK 只接了 plan 一段；CLI 的 deepMode / coordinatorMode 没有提示词追加段
     * （它们的 `getSystemPromptAddition()` 恒返回空串）。
     */
    private fun effectiveSystemPrompt(): String {
        // 现算基座（含 CLAUDE.md / 用户资料 / 输出风格的最新值）
        val base = try { systemPromptProvider() } catch (_: Throwable) { "" }
        val add = modes.planPromptAddition()
        return if (add.isEmpty()) base else base + add
    }

    // ═════════════════════════ 历史维护 ═════════════════════════

    /**
     * 判断这次回复是不是「空响应或内部占位符」。
     *
     * 两个条件**必须同时满足**才判真（对齐 Node 版的 `onlyPlaceholder`）：
     * 1. **没有工具调用** —— 有工具调用就说明模型在正常干活，文本空是正常的
     *    （很多模型调工具时正文就是空）
     * 2. 正文去掉占位符后为空
     *
     * 【为什么第 1 条不能少】否则会把「正文里恰好提到 (continue) 的回复」误杀 ——
     * 比如模型正在解释这个 bug 本身，结果被当成空响应重试，用户看到重复输出。
     */
    private fun isEmptyOrPlaceholder(turn: AssistantTurn): Boolean {
        if (turn.toolCalls.isNotEmpty()) return false
        val t = turn.text.trim()
        if (t.isEmpty()) return true
        return PLACEHOLDER_TEXTS.any { it.equals(t, ignoreCase = true) }
    }

    /** 把纯文本回复写进历史（无工具调用路径）。 */
    private fun appendAssistantText(turn: AssistantTurn) {
        val blocks = mutableListOf<ContentBlock>()
        if (turn.text.isNotEmpty()) blocks += ContentBlock.Text(turn.text)
        if (blocks.isNotEmpty()) messages += Message(Message.ROLE_ASSISTANT, blocks)
    }

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
                        // 走 ImageProcessor：超长边的图会**真正缩放**（省 token + 防网关拒收）
                        val loaded = imageScaler?.let {
                            ImageProcessor.loadOrNull(att.path, it)
                        }
                        if (loaded != null) {
                            blocks += ContentBlock.Image(loaded.base64, loaded.mimeType)
                            // 截图的缩放比例回写给 phone_tap_xy —— from_screenshot:true 的
                            // 换算依据（对齐 CLI 工具内 lastShotScale，T:1040-1050）。
                            // APK 的缩放发生在注入层而不是工具内，比例只能在这里记。
                            if (att.path.contains("phone-shots")) {
                                val fromWH = loaded.resizedFrom?.let { parseSize(it) }
                                val toWH = loaded.resizedTo?.let { parseSize(it) }
                                if (fromWH != null && toWH != null) {
                                    PhoneTools.recentShotScale =
                                        (fromWH.first.toFloat() / toWH.first) to
                                        (fromWH.second.toFloat() / toWH.second)
                                    // 对齐 CLI T:1051-1053：只说「缩放了」不够，
                                    // 还要指路 from_screenshot 让工具换算，别让模型自己乘。
                                    resizeNotes += "图已缩放 ${loaded.resizedFrom} → ${loaded.resizedTo}。" +
                                        "按图上坐标点击时用 phone_tap_xy 并设 from_screenshot:true，" +
                                        "工具会自动换算，不要自己乘。"
                                } else {
                                    // 截图但没缩放（超长边没超限）→ 记 1:1 ——
                                    // 否则模型对「刚截的图」传 from_screenshot:true 会撞上
                                    // 「还没有截图记录」的报错（CLI T:1049 同款行为）。
                                    // 非截图路径不碰这个状态，避免覆盖截图的比例。
                                    PhoneTools.recentShotScale = 1f to 1f
                                }
                            }
                            // 缩放透明化（工具自己标了 resizedFrom 也要带上）
                            loaded.resizedFrom?.let { from ->
                                resizeNotes += "图片已从 $from 缩放到 ${loaded.resizedTo}（节省 token；微小文字/细节可能受影响）"
                            } ?: att.resizedFrom?.let {
                                resizeNotes += "图片已从 $it 缩放（节省 token；微小细节可能受影响）"
                            }
                        } else {
                            // 没有 scaler 或读失败 → 退回原样读（至少让模型能看到图）
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
            blocks += ContentBlock.Text(ImageProcessor.visionUnsupportedNotice())
        }

        if (blocks.isNotEmpty()) {
            messages += Message(Message.ROLE_USER, blocks)
        }
    }

    /** 解析 "WxH" 尺寸串（resizedFrom/resizedTo 格式）。非法返回 null。 */
    private fun parseSize(s: String): Pair<Int, Int>? {
        val parts = s.split('x')
        if (parts.size != 2) return null
        val w = parts[0].trim().toIntOrNull() ?: return null
        val h = parts[1].trim().toIntOrNull() ?: return null
        if (w <= 0 || h <= 0) return null
        return w to h
    }

    /** 读图片文件并编码成 base64。失败返回 null（不抛）。 */
    /**
     * 把本地图片交给识图 provider 转成文字描述（vision 路由，2026-09-29）。
     *
     * 一轮非流式请求（`chat()` 内部按该 client 的 protocol 自动组包，
     * openai/anthropic 都走）。返回 null = 该走原路带图发。
     * 用 imageScaler 缩过的图（省 token、防网关拒收大图）。
     */
    private suspend fun describeImagesViaVision(
        client: ApiClient,
        paths: List<String>,
    ): String? {
        if (paths.isEmpty()) return null
        val imgJson = kotlinx.serialization.json.buildJsonArray {
            for (p in paths) {
                val loaded = imageScaler?.let { ImageProcessor.loadOrNull(p, it) }
                val b64 = loaded?.base64 ?: readImageAsBase64(p) ?: continue
                val mime = loaded?.mimeType ?: guessImageMime(p)
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("image_url"))
                    put("image_url", kotlinx.serialization.json.buildJsonObject {
                        put(
                            "url",
                            kotlinx.serialization.json.JsonPrimitive("data:$mime;base64,$b64"),
                        )
                    })
                })
            }
        }
        if (imgJson.size == 0) return null
        val userMsg = kotlinx.serialization.json.buildJsonObject {
            put("role", kotlinx.serialization.json.JsonPrimitive("user"))
            put("content", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("text"))
                    put(
                        "text",
                        kotlinx.serialization.json.JsonPrimitive(
                            "请描述这张图片的内容；如果图里有文字，逐字转述。只输出描述本身，不要开场白。",
                        ),
                    )
                })
                imgJson.forEach { add(it) }
            })
        }
        val resp = client.chat(
            system = "你是图片描述器，输出简洁准确的中文描述。",
            messages = listOf(userMsg),
        )
        return resp.text.trim().takeIf { it.isNotBlank() }
    }

    /** 按扩展名猜图片 MIME（readImageAsBase64 只返回 base64，这里补 mime）。 */
    private fun guessImageMime(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        else -> "image/jpeg"
    }

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
    /**
     * API 错误分类（委托 [ErrorClassifier]，分类表见其类注释）。
     *
     * 保留这个方法是为了让事件里的 category 字符串与 `AgentEvent.ERR_*` 常量对齐
     * —— 后者是给 UI 用的稳定契约，前者是内部实现。
     */
    private fun classifyApiError(e: ApiTypes.ApiException): String {
        val c = ErrorClassifier.classify(e)
        return when (c.name) {
            "context_overflow" -> AgentEvent.ERR_CONTEXT_OVERFLOW
            "stream_timeout" -> AgentEvent.ERR_STREAM_TIMEOUT
            "connect_timeout" -> AgentEvent.ERR_CONNECT_TIMEOUT
            "auth" -> AgentEvent.ERR_AUTH
            "rate_limit" -> AgentEvent.ERR_RATE_LIMIT
            "server" -> AgentEvent.ERR_SERVER
            "client_4xx" -> AgentEvent.ERR_CLIENT_4XX
            "network" -> AgentEvent.ERR_NETWORK
            else -> AgentEvent.ERR_UNKNOWN
        }
    }

    companion object {
        /** 单次续轮上限。 */
        const val MAX_EXTENSION_PER_CALL = 60

        /** 最多续轮次数。 */
        const val MAX_EXTENSIONS = 4

        /** 轮数硬上限（续轮也突破不了）。 */
        const val MAX_TURNS_HARD_CAP = 400

        /**
         * 空响应最多重试几次。
         *
         * 3 次对齐 Node 版。再多就是浪费 token —— 连续 3 次空说明是网关/模型问题，
         * 重试解决不了，该如实报告给用户。
         */
        const val MAX_EMPTY_RETRIES = 3

        /**
         * 视为「内部占位符」的文本（大小写不敏感）。
         *
         * 这些字符串是**我们自己**往历史里塞的角色交替补位，模型看到会照着学。
         * 识别出来重试，而不是当成正常回复（那一轮会白掉）。
         */
        private val PLACEHOLDER_TEXTS = setOf(
            "(continue)",
            "(empty)",
            "(no content)",
            "(空)",
        )

        /**
         * 空响应重试时注入的提示语。
         *
         * **必须说清错在哪**：笼统说「响应为空」模型不知如何改正，
         * 很可能再吐一个 `(continue)`。明确告诉它那是内部占位符、不可照抄。
         */
        private const val EMPTY_RESPONSE_HINT =
            "（系统提示）你上一条回复是空的，或者原样输出了内部占位符如 (continue)。" +
                "那些占位符是系统内部用于角色交替的标记，不是对话内容，不要照抄。" +
                "请正常回复上一条消息。"

        /** 参数预览里优先展示的键（常见且信息量大）。 */
        private val PREVIEW_KEY_ORDER = listOf(
            "path", "file_path", "command", "query", "pattern", "url",
            "content", "old_string", "new_string", "prompt",
        )

        /** 单个参数值在预览里的最大长度。 */
        private const val PREVIEW_VALUE_MAX = 40

        /** 整个预览串的最大长度。 */
        private const val PREVIEW_TOTAL_MAX = 120
    }

    /** 一次 assistant 回复的聚合结果。 */
    private data class AssistantTurn(
        val text: String,
        val reasoning: String,
        val toolCalls: List<ApiTypes.ToolCall>,
        /** 上游结束原因（`length` = 被 max_output_tokens 截断）。 */
        val finishReason: String? = null,
    )
}
