package com.ccm.app.core

import com.ccm.app.core.agent.AgentLoop
import com.ccm.app.core.agent.ModeState
import com.ccm.app.core.agent.AgentEvent
import com.ccm.app.core.agent.SubAgentManager
import com.ccm.app.core.api.ApiClient
import com.ccm.app.core.compact.AutoCompact
import com.ccm.app.core.compact.Compactor
import com.ccm.app.core.image.ImageScaler
import com.ccm.app.core.provider.AppConfig
import com.ccm.app.core.provider.ProviderConfig
import com.ccm.app.core.session.SessionAuto
import com.ccm.app.core.session.SessionStore
import com.ccm.app.core.tool.AppBackedToolStorage
import com.ccm.app.core.tool.MapToolSettings
import com.ccm.app.core.tool.ToolRegistry
import com.ccm.app.core.tool.ToolRunner
import com.ccm.app.core.tool.SubAgentResult
import com.ccm.app.core.tool.ToolSettings

/**
 * 应用装配容器 —— **把各零件接成一条能跑的链路**。
 *
 * ## 为什么需要它
 *
 * 各模块单独可用，但没人把它们连起来，链路就是断的：
 * ```
 * AppConfig(读配置) → ApiClient(发请求) → AgentLoop(主循环) → ToolRegistry(工具)
 *                                            ↓
 *                                      SessionStore(存会话)
 * ```
 * 本类负责**唯一一次装配**，并把结果暴露给 UI。
 *
 * ## 生命周期
 * - **创建**：App 启动 / 切换 Provider 时
 * - **销毁**：调 [shutdown]（释放 OkHttp 连接池、停工具）
 * - 切换 Provider = 重建（`ApiClient` 持有 key 池和连接池，不该复用）
 *
 * ## 依赖注入的边界
 *
 * 本类**只做装配，不含业务逻辑**。它需要外部提供两样东西：
 * 1. [ToolRegistry] —— 工具注册（由 `tools/ToolsBootstrap` 填）
 * 2. [ToolRunner] —— 工具执行器（同上）
 *
 * 因为 `core` **不能依赖 `tools`**（依赖方向是 `core ← tools`），
 * 所以这两个由调用方（`MainActivity` / `CcmService`）注入。
 *
 * ## 用法
 * ```kotlin
 * // 装配（App 启动时一次）
 * val container = AppContainer.build(storage, registry, toolRunner, config)
 *
 * // 跑一轮对话
 * container.agentLoop.run("你好").collect { event -> ... }
 *
 * // 切 Provider（重建）
 * container.shutdown()
 * val newContainer = AppContainer.build(storage, registry, toolRunner, newConfig)
 * ```
 */
class AppContainer private constructor(
    /** 当前配置。 */
    val config: AppConfig,
    /** API 客户端（唯一出网点）。 */
    val apiClient: ApiClient,
    /** 识图客户端（vision 路由；null = 不路由）。 */
    val visionClient: ApiClient? = null,
    /** Agent 主循环。 */
    val agentLoop: AgentLoop,
    /** 工具注册表。 */
    val toolRegistry: ToolRegistry,
    /** 会话存档。 */
    val sessionStore: SessionStore,
    /** 上下文压缩。 */
    val compactor: Compactor,
    /**
     * 自动压缩决策（**默认关闭**，见 [AutoCompact] 类注释）。
     *
     * 用户被自动压缩搞丢过记忆，明确反感 —— 只有他显式设阈值才会启用。
     */
    val autoCompact: AutoCompact,
    /**
     * 运行模式状态（deep / plan / watch）—— 与工具层**共享同一个实例**。
     *
     * 工具改它、AgentLoop 读它。两个对象必须拿到同一份，否则
     * 「EnterDeepMode 说成功但轮数没变」。由 [build] 的 modes 参数传入。
     */
    val modes: com.ccm.app.core.agent.ModeState,
    /**
     * 自动记忆提取器（automem）。
     *
     * `null` = 未启用（构造时 storage 不可写等异常情形）。
     * 由 [ChatSession.collectEvents] 在每轮 Done 后 fire-and-forget 触发 ——
     * **不要 await 在 run 里**（会拖住 UI 收尾，且用户 Ctrl+C 会取消提取）。
     */
    val autoMemory: com.ccm.app.core.memory.AutoMemory? = null,
) {

    /**
     * 子 Agent 管理器（并发上限 + 观察窗）。
     *
     * `null` = 未启用子 Agent（需要协程作用域，见 [attachSubAgents]）。
     */
    var subAgents: SubAgentManager? = null
        private set


    /**
     * 接上子 Agent 能力。
     *
     * @param scope App 级协程作用域
     * @param spawn 实际派生函数（由上层注入，避免 core 依赖具体实现）
     */
    fun attachSubAgents(
        scope: kotlinx.coroutines.CoroutineScope,
        spawn: suspend (com.ccm.app.core.tool.SubAgentSpec, com.ccm.app.core.agent.SubAgentHandle) ->
            com.ccm.app.core.tool.SubAgentResult,
    ): SubAgentManager {
        val mgr = SubAgentManager(scope = scope, spawn = spawn)
        subAgents = mgr
        return mgr
    }

    /**
     * 跑一个子 Agent（问题40）。
     *
     * ═══════════════════════════════════════════════════════════════
     * 【2026-10-06 新建】用户报「各种的工具/命令未接入、行为降级」。
     *
     * 排查发现 `attachSubAgents` **零调用** —— 子 Agent 相关工具
     * （Agent / AgentStatus / AgentOutput / AgentStop / TeamCreate /
     * AgentWorkflow）全部报「观察器未接入」或「spawnSubAgent 未注入」。
     *
     * 本方法提供 spawn 的实现：每次派活新建一个独立 AgentLoop
     * （独立上下文 = 子 Agent 的核心价值），跑完收集文本输出。
     * ═══════════════════════════════════════════════════════════════
     *
     * ## 为什么在这里而不是单独一个类
     *
     * 子 Agent 需要的依赖（apiClient / registry / toolRunner / config /
     * modes / storage）**全是本类的私有字段** —— 放外面就要暴露一堆 getter。
     *
     * ## 简化点（如实记录）
     *
     * - **不传 spawnSubAgent** → 子 Agent 不能再派孙 Agent
     *   （递归无深度限制会失控；需要多层编排时主 Agent 自己分层）
     * - **不传 askUser** → 子 Agent 无法与用户交互（会永久阻塞）
     * - **系统提示词**：独立一段（不复用主提示词，避免子 Agent 误以为自己是主 Agent）
     *
     * @param spec 任务规格（prompt / 类型 / 名字）
     * @return 执行结果（ok=false 时 error 有原因）
     */
    suspend fun runSubAgent(spec: com.ccm.app.core.tool.SubAgentSpec): com.ccm.app.core.tool.SubAgentResult {
        val subPrompt = buildString {
            append("你是一个子 Agent（subagent），由主 Agent 派来独立完成一个子任务。\n\n")
            append("## 行为约束\n")
            append("- 你没有派生子 Agent 的能力（不要调用 Agent 工具，会报错）\n")
            append("- 你无法与用户交互（不要调用 AskUserQuestion，会永久阻塞）\n")
            append("- 完成后用一段清晰的文字总结你的结论/产出 —— 这段文字会原样返回给主 Agent\n")
            append("- 不要问「要我做吗」，直接做（信息不足时基于合理假设推进，并说明假设）\n")
            append("\n## 任务信息\n")
            append("subagent_type = ${spec.subagentType}\n")
            if (spec.description.isNotBlank()) append("description = ${spec.description}\n")
        }

        val subLoop = try {
            AgentLoop(
                api = apiClient,
                systemPrompt = subPrompt,
                // 同一个注册表（工具集一致）
                toolsProvider = { toolRegistry.list },
                maxTurnsInit = ModeState.NORMAL_MAX_TURNS,
                cwd = cwdForSubAgent,
                permissionModeInit = config.permissionMode,
                storage = storageForSubAgent,
                settings = settingsForSubAgent,
                sessionId = "sub-" + System.currentTimeMillis(),
                // 关键：不传 spawnSubAgent（子 Agent 不能再派）
                toolRunner = toolRunnerForSubAgent ?: throw IllegalStateException("子 Agent 依赖未就绪（toolRunner）"),
                imageScaler = null,
                modes = modes,
                traceDir = traceDirForSubAgent,
            )
        } catch (t: Throwable) {
            return com.ccm.app.core.tool.SubAgentResult(
                ok = false, output = "",
                error = "子 Agent 装配失败：${t.message}",
            )
        }

        val sb = StringBuilder()
        var turns = 0
        return try {
            subLoop.run(spec.prompt).collect { ev ->
                when (ev) {
                    is AgentEvent.TextDelta -> sb.append(ev.text)
                    is AgentEvent.Done -> turns = subLoop.turnCount
                    else -> {}
                }
            }
            com.ccm.app.core.tool.SubAgentResult(
                ok = true,
                output = sb.toString().trim().ifBlank { "（子 Agent 没有产出文本）" },
                turns = turns,
            )
        } catch (t: Throwable) {
            com.ccm.app.core.tool.SubAgentResult(
                ok = false,
                output = sb.toString().trim(),
                turns = turns,
                error = "子 Agent 执行失败：${t.message}",
            )
        }
    }

    // ── 子 Agent 用的依赖快照（问题40）─────────────────────────────
    //
    // build() 里的这些值都是**局部变量**（没有存成字段），
    // 所以这里用 private var 在 build 时记一份，供 runSubAgent 用。

    /** 子 Agent 用的工作目录（build 时记录）。 */
    private var cwdForSubAgent: String = "/"

    /** 子 Agent 用的工具存储（build 时记录）。 */
    private var storageForSubAgent: com.ccm.app.core.tool.ToolStorage? = null

    /** 子 Agent 用的配置快照（build 时记录）。 */
    private var settingsForSubAgent: com.ccm.app.core.tool.ToolSettings? = null

    /** 子 Agent 用的工具执行器（build 时记录）。 */
    private var toolRunnerForSubAgent: com.ccm.app.core.tool.ToolRunner? = null

    /** 子 Agent 用的 trace 目录（build 时记录）。 */
    private var traceDirForSubAgent: java.io.File? = null

    /** 记录子 Agent 依赖（由 build 调用）。 */
    private fun rememberSubAgentDeps(
        cwd: String,
        storage: com.ccm.app.core.tool.ToolStorage?,
        settings: com.ccm.app.core.tool.ToolSettings?,
        runner: com.ccm.app.core.tool.ToolRunner?,
        traceDir: java.io.File?,
    ) {
        cwdForSubAgent = cwd
        storageForSubAgent = storage
        settingsForSubAgent = settings
        toolRunnerForSubAgent = runner
        traceDirForSubAgent = traceDir
    }

    /**
     * 会话自动保存（`null` = 未启用）。
     *
     * 需要协程作用域，所以不能在这里直接建 —— 由调用方
     * [attachSessionAuto] 注入（App 级 scope）。
     */
    var sessionAuto: SessionAuto? = null
        private set

    /**
     * 接上自动保存。
     *
     * @param scope App 级协程作用域（通常是 `lifecycleScope` 或自建 scope）
     * @param sessionId 初始会话 id（空 = 新建）
     */
    fun attachSessionAuto(
        scope: kotlinx.coroutines.CoroutineScope,
        sessionId: String = "",
        /** 已有会话的标题 —— 不传则自动保存会把文件里的 title 覆盖成 null（B3 联动坑）。 */
        existingTitle: String? = null,
    ): SessionAuto {
        val auto = SessionAuto(
            store = sessionStore,
            sessionId = sessionId.ifBlank { sessionStore.newSessionId() },
            historyProvider = { agentLoop.getHistory() },
            scope = scope,
        )
        auto.title = existingTitle
        auto.start()
        sessionAuto = auto
        return auto
    }

    /** 释放资源（切 Provider 或退出前调）。 */
    fun shutdown() {
        try {
            // 先 flush 再停 —— 否则最后 30 秒的对话还在内存里
            sessionAuto?.stop()
            sessionAuto = null
        } catch (_: Throwable) {
        }
        try {
            visionClient?.shutdown()
        } catch (_: Throwable) {
        }
        try {
            apiClient.shutdown()
        } catch (_: Throwable) {
            // 关闭失败不影响退出
        }
    }

    /**
     * 用新配置重建（切 Provider / 改模型时调）。
     *
     * **为什么是重建而不是改字段**：
     * `ApiClient` 持有 key 池（有冷却状态）和 OkHttp 连接池（可能有坏连接）。
     * 改字段会让旧状态污染新 Provider —— Node 版踩过
     * 「端点 A 的结论连坐到端点 B，导致 B 的功能静默失效」。
     * 重建 = 干净状态，代价只是几毫秒。
     */
    fun rebuild(
        newConfig: AppConfig,
        storage: AppStorage,
        registry: ToolRegistry,
        toolRunner: ToolRunner,
        imageScaler: ImageScaler? = null,
    ): AppContainer? {
        shutdown()
        return build(
            storage = storage,
            registry = registry,
            toolRunner = toolRunner,
            config = newConfig,
            initialHistory = agentLoop.getHistory(),
            imageScaler = imageScaler,
        )
    }

    companion object {

        /**
         * 默认系统提示词（问题29 修复：从 assets 读完整版）。
         *
         * 【历史】原来是一句 `"你是 CCM，一个运行在 Android 上的 AI 编程助手。"`
         * —— 注释写着「阶段5 会换成从资源读的完整版」，但从来没做。
         * 后果：APK 的提示词只有 25 个字符，而 CLI 有 900 行（工具说明、
         * 场景映射表、行为准则全缺）→ 模型在 APK 里的表现远差于 CLI。
         *
         * 现在从 `assets/system-prompt.md` 读（24K 字符，CLI 提示词的
         * APK 适配版：删了输入快捷键/息屏保活/DSH 插件等 APK 不适用段）。
         *
         * ⚠️ 读失败时回退到简短版 —— 不能因为读不到资源就让 App 起不来。
         */
        private var cachedPrompt: String? = null

        private fun loadSystemPrompt(context: android.content.Context): String {
            cachedPrompt?.let { return it }
            val text = try {
                context.assets.open("system-prompt.md")
                    .bufferedReader().use { it.readText() }
            } catch (_: Throwable) {
                null
            }
            val raw = text?.takeIf { it.isNotBlank() }
                ?: "你是 CCM，一个运行在 Android 上的 AI 编程助手。"
            // 【2026-10-06 问题29】填 `{{XXX}}` 占位符 —— **从代码常量现算**，
            // 不写死（改代码自动同步提示词，不会出现"文档说 3 轮、工具按 5 轮拒绝"）。
            val result = try {
                com.ccm.app.core.prompt.PromptVars.fill(raw)
            } catch (_: Throwable) {
                raw
            }
            cachedPrompt = result
            return result
        }

        /**
         * 组装系统提示词 = 常量基底 + 用户资料（audit-core #6）。
         *
         * 设置页填的「称呼/职业/回复偏好」原来**对模型完全不可见**
         * （DEFAULT_SYSTEM_PROMPT 是死常量，profile 只有 callName 用于问候语）。
         * 这里拼进去 —— 改了资料后需重建会话（切会话/重启）才生效，
         * 设置页关闭时会自动重建（AppScaffold 的 showSettings 监听）。
         */
        private fun assembleSystemPrompt(
            storage: AppStorage,
            context: android.content.Context? = null,
        ): String = try {
            val prof = com.ccm.app.core.user.UserProfileStore(storage).load()
            // 【2026-10-06 问题29】从 assets 读完整提示词（原来是一句话常量）
            val base = context?.let { loadSystemPrompt(it) }
                ?: "你是 CCM，一个运行在 Android 上的 AI 编程助手。"
            val sb = StringBuilder(base)
            val lines = buildList {
                prof.fullName.takeIf { it.isNotBlank() }?.let { add("用户姓名：$it") }
                prof.displayName.takeIf { it.isNotBlank() }?.let { add("怎么称呼用户：$it") }
                prof.workFunction.takeIf { it.isNotBlank() }?.let { add("用户职业：$it") }
                prof.personalPreferences.takeIf { it.isNotBlank() }
                    ?.let { add("回复偏好（务必遵守）：$it") }
            }
            if (lines.isNotEmpty()) {
                sb.append("\n\n## 关于用户\n")
                lines.forEach { sb.append("- ").append(it).append('\n') }
            }
            // 输出风格（与 CLI/Web 的 outputStyle 同字段互通）
            try {
                val styleId = com.ccm.app.core.provider.AppConfig
                    .load(storage.configFile).config.outputStyle
                com.ccm.app.core.output.OutputStyles
                    .promptFor(styleId)?.let { sb.append("\n\n").append(it) }
            } catch (_: Throwable) {}
            sb.toString()
        } catch (_: Throwable) {
            context?.let { loadSystemPrompt(it) }
                ?: "你是 CCM，一个运行在 Android 上的 AI 编程助手。"
        }

        /**
         * 装配一个容器。
         *
         * @param storage 应用存储
         * @param registry 工具注册表（调用方已用 ToolsBootstrap 填好）
         * @param toolRunner 工具执行器（`tools/ToolExecutor`）
         * @param config 应用配置
         * @param initialHistory 初始历史（从会话恢复时传）
         * @param systemPrompt 系统提示词
         * @param cwd 工作目录
         * @return 装配好的容器；配置无效（没有可用 Provider）时返回 null
         */
        fun build(
            storage: AppStorage,
            registry: ToolRegistry,
            toolRunner: ToolRunner,
            config: AppConfig,
            initialHistory: List<com.ccm.app.core.session.Message> = emptyList(),
            /**
             * Android Context（问题29：读 assets/system-prompt.md 用）。
             * null = 用简短兜底提示词。
             */
            context: android.content.Context? = null,
            systemPrompt: String = assembleSystemPrompt(storage, context),
            cwd: String = "/",
            imageScaler: ImageScaler? = null,
            /**
             * 运行模式状态（deep / plan / watch）。
             *
             * **必须与 ToolsBootstrap 用同一个实例** —— 工具写、主循环读。
             * 不传时每次 build 都新建一个（单测场景），生产环境由 AppGraph 传入。
             */
            modes: com.ccm.app.core.agent.ModeState = com.ccm.app.core.agent.ModeState(),
            /**
             * 会话 id。
             *
             * **必须由调用方给**（与 [attachSessionAuto] 用同一个）——
             * 早期实现这里硬编码 `""`，而 `attachSessionAuto` 自己
             * `newSessionId()` 生成一个新的，于是**同一场对话有两个 id**：
             * - Agent 侧（工具、hooks 的 `SESSION_ID`、子 Agent 归属）看到空串
             * - 存盘侧用的是另一个 id
             * 表现为「工具里拿不到会话 id」「子 Agent 无法归属到会话」这类静默错位。
             *
             * 空串 = 由本方法生成一个（单测/无会话场景）。
             */
            sessionId: String = "",
            /**
             * 自动记忆提取器 —— **必须传进程级单例**（AppGraph.autoMemory）。
             *
             * ⚠️ 与 [modes] 同一个坑：Memory 工具（ToolsBootstrap 持有）要通知
             * 「本轮别重复提取」，而提取动作由 ChatSession 调 —— 两边必须是
             * **同一个对象**。若这里每次新建，工具的 `markMainWroteMemory()`
             * 置位的是 A 实例，提取读的是 B 实例的 flag（恒 false）→
             * **互斥静默失效**：主 Agent 刚写完记忆，automem 又提取一条重复的。
             *
             * `null` = 自己建一个（单测/无 AppGraph 场景），生产环境由 AppGraph 传。
             */
            autoMemory: com.ccm.app.core.memory.AutoMemory? = null,
        ): AppContainer? {
            val provider = config.currentProvider ?: return null
            val keys = provider.allKeys()
            if (keys.isEmpty()) return null

            storage.ensureDirs()

            val effectiveSessionId = sessionId.ifBlank { SessionStore(storage).newSessionId() }

            // ── API 客户端 ──
            val apiClient = ApiClient(
                baseUrl = provider.url,
                apiKeys = keys,
                model = provider.model,
                protocol = provider.protocolType,
                maxOutputTokens = provider.maxOutputTokens,
                temperature = provider.temperature ?: config.temperature,
                // key 池冷却状态落盘（重启后不从头撞已耗尽的 key）
                keyPoolStateFile = java.io.File(storage.root, "key-pool-state.json"),
                // 深度思考（audit-core #2：config.effort 原来零消费）
                effort = provider.effort ?: config.effort,
            )

            // ── 会话与压缩 ──
            val sessionStore = SessionStore(storage)
            val compactor = Compactor(
                Compactor.Policy(
                    // 自动压缩默认关（用户被自动压缩搞丢过记忆，明确反感）
                    enabled = false,
                )
            )

            // ── Agent 主循环 ──
            // ★ 识图路由客户端（2026-09-29）：开关开 + 配了独立 vision provider
            //   且**不是当前主 provider** 才建（是同一个就没必要路由）。
            val vp = config.visionProvider
            val visionClient: ApiClient? =
                if (config.vision == true && vp != null &&
                    config.visionProviderId != null &&
                    config.visionProviderId != config.current &&
                    vp.url.isNotBlank() && vp.allKeys().isNotEmpty()
                ) {
                    ApiClient(
                        baseUrl = vp.url,
                        apiKeys = vp.allKeys(),
                        model = vp.model,
                        protocol = vp.protocolType,
                        maxOutputTokens = vp.maxOutputTokens,
                        temperature = vp.temperature ?: 1.0,
                    )
                } else null

            val agentLoop = AgentLoop(
                api = apiClient,
                visionClient = visionClient,
                systemPrompt = systemPrompt,
                // 惰性取（不是快照）—— 后注册的工具（如 Agent 自己）也要能看见
                toolsProvider = { registry.list },
                maxTurnsInit = DEFAULT_MAX_TURNS,
                cwd = cwd,
                permissionModeInit = config.permissionMode,
                storage = AppBackedToolStorage(storage),
                settings = buildSettings(config, provider),
                sessionId = effectiveSessionId,
                // 【2026-10-06 问题40 修复】原来这里是 `null`，注释说
                // 「由上层在装配后注入」—— **但从来没注入过** →
                // Agent 工具永远报「当前环境不支持派生子 Agent（spawnSubAgent 未注入）」
                // → 整个子 Agent / 团队协作 / AgentWorkflow 功能全部不可用。
                //
                // 现在实现在这里：每次 spawn 新建一个独立的 AgentLoop
                // （独立上下文 = 子 Agent 的核心价值），跑完收集文本输出。
                //
                // ⚠️ 简化点（如实记录）：
                //   · 不注入 spawnSubAgent（子 Agent 不能再派孙 Agent —— 防递归失控）
                //   · 不注入 askUser（子 Agent 无法与用户交互，会永久阻塞）
                //   · 系统提示词用主提示词 + 一段「你是子 Agent」的说明
                spawnSubAgent = spawn@{ spec ->
                    try {
                        val subPrompt = assembleSystemPrompt(storage, context) +
                            "\n\n## 你是子 Agent\n" +
                            "你被主 Agent 派来独立完成一个子任务。\n" +
                            "· 你没有派生子 Agent 的能力（不要尝试调用 Agent 工具）\n" +
                            "· 你无法与用户交互（不要调用 AskUserQuestion）\n" +
                            "· 完成后用一段清晰的文字总结你的结论/产出\n"
                        val subLoop = AgentLoop(
                            api = apiClient,
                            systemPrompt = subPrompt,
                            toolsProvider = { registry.list },
                            maxTurnsInit = DEFAULT_MAX_TURNS,
                            cwd = cwd,
                            permissionModeInit = config.permissionMode,
                            storage = AppBackedToolStorage(storage),
                            settings = buildSettings(config, provider),
                            sessionId = effectiveSessionId + "-sub-" + System.currentTimeMillis(),
                            // 关键：不传 spawnSubAgent（子 Agent 不能再派）
                            toolRunner = toolRunner,
                            imageScaler = imageScaler,
                            modes = modes,
                            traceDir = storage.tracesDir,
                        )
                        // 跑完收集文本输出
                        val sb = StringBuilder()
                        var turns = 0
                        subLoop.run(spec.prompt).collect { ev ->
                            when (ev) {
                                is com.ccm.app.core.agent.AgentEvent.TextDelta -> sb.append(ev.text)
                                is com.ccm.app.core.agent.AgentEvent.Done -> turns = subLoop.turnCount
                                else -> {}
                            }
                        }
                        SubAgentResult(
                            ok = true,
                            output = sb.toString().trim().ifBlank { "（子 Agent 没有产出文本）" },
                            turns = turns,
                        )
                    } catch (t: Throwable) {
                        SubAgentResult(
                            ok = false,
                            output = "",
                            error = "子 Agent 执行失败：${t.message}",
                        )
                    }
                },
                toolRunner = toolRunner,
                imageScaler = imageScaler,
                modes = modes,
                // trace 目录 —— 排查问题的关键设施（Node 版最难查的 bug 全靠它）
                traceDir = storage.tracesDir,
            )
            agentLoop.setHistory(initialHistory)

            // ── 自动记忆（automem）──
            // 状态文件与记忆文件都放 storage.root（= files/），与 MiscTools 的
            // memoryFile 同一个 —— 两个模块必须指向同一份 CLAUDE.md。
            //
            // ⚠️ **优先用注入的实例**（AppGraph.autoMemory）：Memory 工具持有
            // 的是那一个，这里新建就会让「主 Agent 写了记忆 → 本轮跳过提取」
            // 的互斥失效（工具置位 A、提取读 B）。详见参数注释。
            val effectiveAutoMemory = autoMemory ?: try {
                com.ccm.app.core.memory.AutoMemory(
                    stateFile = java.io.File(storage.root, "automem.json"),
                    memoryFile = java.io.File(storage.root, "CLAUDE.md"),
                )
            } catch (_: Throwable) {
                null
            }

            val container = AppContainer(
                config = config,
                apiClient = apiClient,
                visionClient = visionClient,
                agentLoop = agentLoop,
                toolRegistry = registry,
                sessionStore = sessionStore,
                compactor = compactor,
                // 阈值从配置读（AppConfig 目前没有这两个字段 → 用默认 0 = 关闭）
                autoCompact = AutoCompact(maxContext = config.maxContextTokens),
                modes = modes,
                autoMemory = effectiveAutoMemory,
            )
            // 【2026-10-06 问题40】记下子 Agent 需要的依赖 ——
            // build() 里这些都是局部变量，runSubAgent() 够不着。
            container.rememberSubAgentDeps(
                cwd = cwd,
                storage = AppBackedToolStorage(storage),
                settings = provider?.let { buildSettings(config, it) },
                runner = toolRunner,
                traceDir = storage.tracesDir,
            )
            return container
        }

        /**
         * 由配置构造工具侧只读快照。
         *
         * 传的是**快照**不是引用 —— 工具执行期间配置不该变
         * （否则用户在设置页改 Provider 会让正在跑的请求中途换端点）。
         */
        fun buildSettings(config: AppConfig, provider: ProviderConfig): ToolSettings {
            val map = mutableMapOf<String, String?>(
                "providerBaseUrl" to provider.url,
                "providerApiKey" to (provider.allKeys().firstOrNull() ?: ""),
                "providerModel" to provider.model,
                "providerProtocol" to provider.protocol,
                "imageGenBaseUrl" to config.imageGen?.url,
                "imageGenApiKey" to config.imageGen?.apiKey,
                "imageGenModel" to config.imageGen?.model,
                // ★ 第24批：原来 map 里没有这两个键 —— 设置页填的
                //   tavilyKey / webSearch 开关全链路无处落地，存了等于没存，
                //   WebSearch 工具永远 `map["tavilyApiKey"] == null`。
                "tavilyApiKey" to config.tavilyKey,
                "webSearch" to config.webSearch?.toString(),
                // 【2026-10-06 问题40】Pexels key —— FindImage 用。
                // 原来 map 里没这个键 → 工具永远报「未配置」。
                "pexelsApiKey" to config.pexelsKey,
                "pexelsKey" to config.pexelsKey,
            )
            // 识图 Provider（vision 路由回退）
            config.visionProvider?.let { vp ->
                map["visionBaseUrl"] = vp.url
                map["visionApiKey"] = vp.allKeys().firstOrNull()
                map["visionModel"] = vp.model
            }
            return MapToolSettings(map)
        }

        /** 默认最大轮次（对齐 Node 版 NORMAL_MAX_TURNS）。 */
        const val DEFAULT_MAX_TURNS = 200
    }
}

/**
 * 便捷扩展：容器是否可用于对话（有 Provider + 有 key）。
 *
 * UI 用它在发送前做校验，避免「点了发送但没反应」。
 */
val AppContainer?.isUsable: Boolean
    get() = this != null

/** 便捷：当前 Provider 显示名（状态栏用）。 */
val AppContainer.providerLabel: String
    get() = config.currentProvider?.displayName ?: "未配置"
