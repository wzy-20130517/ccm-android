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
import com.ccm.app.core.tool.ToolStorage

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
    ): SessionAuto {
        val auto = SessionAuto(
            store = sessionStore,
            sessionId = sessionId.ifBlank { sessionStore.newSessionId() },
            historyProvider = { agentLoop.getHistory() },
            scope = scope,
        )
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
            systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
            cwd: String = "/",
            imageScaler: ImageScaler? = null,
        ): AppContainer? {
            val provider = config.currentProvider ?: return null
            val keys = provider.allKeys()
            if (keys.isEmpty()) return null

            storage.ensureDirs()

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
            val agentLoop = AgentLoop(
                api = apiClient,
                systemPrompt = systemPrompt,
                // 惰性取（不是快照）—— 后注册的工具（如 Agent 自己）也要能看见
                toolsProvider = { registry.list },
                maxTurnsInit = DEFAULT_MAX_TURNS,
                cwd = cwd,
                permissionModeInit = config.permissionMode,
                storage = AppBackedToolStorage(storage),
                settings = buildSettings(config, provider),
                sessionId = "",
                spawnSubAgent = null,   // 由上层在装配后注入（需要 Agent 工具支持）
                toolRunner = toolRunner,
                imageScaler = imageScaler,
                // trace 目录 —— 排查问题的关键设施（Node 版最难查的 bug 全靠它）
                traceDir = storage.tracesDir,
            )
            agentLoop.setHistory(initialHistory)

            return AppContainer(
                config = config,
                apiClient = apiClient,
                agentLoop = agentLoop,
                toolRegistry = registry,
                sessionStore = sessionStore,
                compactor = compactor,
                // 阈值从配置读（AppConfig 目前没有这两个字段 → 用默认 0 = 关闭）
                autoCompact = AutoCompact(maxContext = config.maxContextTokens),
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
