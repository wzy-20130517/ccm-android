package com.ccm.app.core

import com.ccm.app.core.agent.AgentLoop
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

        /** 默认系统提示词（阶段5 会换成从资源读的完整版）。 */
        private const val DEFAULT_SYSTEM_PROMPT = "你是 CCM，一个运行在 Android 上的 AI 编程助手。"

        /**
         * 组装系统提示词 = 常量基底 + 用户资料（audit-core #6）。
         *
         * 设置页填的「称呼/职业/回复偏好」原来**对模型完全不可见**
         * （DEFAULT_SYSTEM_PROMPT 是死常量，profile 只有 callName 用于问候语）。
         * 这里拼进去 —— 改了资料后需重建会话（切会话/重启）才生效，
         * 设置页关闭时会自动重建（AppScaffold 的 showSettings 监听）。
         */
        private fun assembleSystemPrompt(storage: AppStorage): String = try {
            val prof = com.ccm.app.core.user.UserProfileStore(storage).load()
            val sb = StringBuilder(DEFAULT_SYSTEM_PROMPT)
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
            DEFAULT_SYSTEM_PROMPT
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
            systemPrompt: String = assembleSystemPrompt(storage),
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
                effort = config.effort,
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
                spawnSubAgent = null,   // 由上层在装配后注入（需要 Agent 工具支持）
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
            val autoMemory = try {
                com.ccm.app.core.memory.AutoMemory(
                    stateFile = java.io.File(storage.root, "automem.json"),
                    memoryFile = java.io.File(storage.root, "CLAUDE.md"),
                )
            } catch (_: Throwable) {
                null
            }

            return AppContainer(
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
                autoMemory = autoMemory,
            )
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
