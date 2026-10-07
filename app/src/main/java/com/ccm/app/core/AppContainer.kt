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
    var config: AppConfig,
    /**
     * API 客户端（唯一出网点）。
     *
     * 【2026-10-06 热更新】原来是 `val`（构造快照）—— /key /url /model
     * 等命令改完配置要「重启 App 才生效」。现在改 `var`，
     * 由 [refreshApi] 在命令改完后原地换新实例。
     */
    var apiClient: ApiClient,
    /** 识图客户端（vision 路由；null = 不路由）。 */
    var visionClient: ApiClient? = null,
    /**
     * 应用存储（[refreshApi] 重读 config.json 用）。
     *
     * 构造参数单独留一份 —— 实例里原有的 storage 是 ToolStorage（工具视角），
     * 拿不到 configFile。
     */
    private val appStorage: AppStorage? = null,
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
        // 同步到 companion 静态 holder（spawnSubAgent 闭包读它 —— 见 holder 注释）
        activeSubAgentManager = mgr
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

    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 用户反馈】/key 等命令要热更新（无需重启）
    //
    //  根因：ApiClient 是构造快照（baseUrl/keys/model/protocol/effort/
    //  temperature 全在构造参数里），slash 命令改完配置后没人换实例 →
    //  只能重启 App。CLI 同名命令是立即生效的，APK 这里是行为缺口。
    //
    //  修法：命令改完**落盘之后**调 [refreshApi] —— 重读磁盘配置、
    //  原地换 apiClient / visionClient / agentLoop 的引用。
    //  正在跑的那一轮用旧实例跑完（不受影响），下一轮自动用新的。
    // ══════════════════════════════════════════════════════════════

    /**
     * 热更新 API 客户端 —— **无需重启**。
     *
     * 调用前提：新配置已经落盘（[refreshApi] 自己从磁盘重读）。
     *
     * @return true = 换好了；false = 新配置不可用（没 provider / 没 key），
     *   此时**保持旧实例不动**（不能因为一次坏配置把正在用的链路弄断）。
     */
    fun refreshApi(): Boolean {
        val st = appStorage ?: return false
        // AppConfig.load 返回自定义 Result（.config / .error），不是 kotlin.Result
        val cfg = com.ccm.app.core.provider.AppConfig.load(st.configFile).config
        val provider = cfg.currentProvider ?: return false
        if (provider.allKeys().isEmpty()) return false

        val newApi = buildApiClient(st, cfg, provider) ?: return false
        val newVision = buildVisionClient(cfg)

        config = cfg
        apiClient = newApi
        visionClient = newVision
        // AgentLoop 里换掉引用 —— 正在跑的请求持有旧引用跑完即可
        agentLoop.swapClients(newApi, newVision)
        return true
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
         * 当前生效的 SubAgentManager（2026-10-06 加）。
         *
         * 【为什么放 companion 静态字段】`build()` 是静态工厂函数，
         * 里面的 spawnSubAgent 闭包**访问不到实例属性**（this 不在作用域）——
         * 而它需要在「后台派活」时拿到 manager。
         * `attachSubAgents` 装配时把 manager 存这里，闭包运行时读它。
         *
         * 单例语义成立：App 同时只有一个 AppContainer 生效（进程级单例，
         * rebuild 会换新实例，但 manager 也随之重建 —— 见 attachSubAgents 调用点）。
         */
        @Volatile
        var activeSubAgentManager: com.ccm.app.core.agent.SubAgentManager? = null

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

        /**
         * 让缓存的系统提示词失效（下次取时重算）。
         *
         * 【2026-10-06 P1-4】Memory 工具写 CLAUDE.md、/me 改资料、
         * /style 换风格之后调它 —— 否则模型本会话内永远用旧的。
         */
        fun invalidateSystemPrompt() {
            cachedPrompt = null
        }

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
        /** CLAUDE.md 全文注入上限（对齐 CLI persistence.mjs 的 35000）。 */
        private const val CLAUDE_MD_LIMIT = 35000

        /** 默认输出风格段（未设 /style 时用；与 CLI prompts.mjs 的默认一致）。 */
        private const val DEFAULT_OUTPUT_STYLE =
            "直接、简洁、中文优先。代码块、错误信息、文件名保留英文。"


        private fun assembleSystemPrompt(
            storage: AppStorage,
            context: android.content.Context? = null,
            /** 当前工作目录（注入提示词用；空 = 不注入该行）。 */
            currentCwd: String = "",
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
            // ── 当前会话信息（2026-10-06 补，对齐 CLI 的 SESSION_START_PROMPT）──
            //
            // CLI 每轮注入：日期 / 工作目录 / 平台 / 工作区实际路径。
            // APK 原来**完全没有** —— 模型不知道今天几号（「三天后提醒我」
            // 这类请求只能猜），也不知道工作区的绝对路径（写文件时容易
            // 搞错层级）。这里现算注入（assembleSystemPrompt 是提供者函数，
            // 每次调模型前重算，跨天自动更新）。
            sb.append("\n\n# 当前会话\n")
            sb.append("- 日期：")
                .append(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                    .format(java.util.Date()))
                .append('\n')
            sb.append("- 平台：Android (APK, Compose UI)\n")
            if (currentCwd.isNotBlank()) {
                sb.append("- 工作目录：").append(currentCwd).append('\n')
                sb.append("- 工作区：").append(currentCwd).append('\n')
            }

            // ── CLAUDE.md 项目记忆注入（2026-10-06 补）──────────────────
            //
            // 【原来完全没有】APK 的提示词装配只拼了 assets 模板 + 用户资料，
            // **CLAUDE.md 从不注入** —— Memory 工具写了半天，模型每轮都看不见，
            // 等于白写（用户要求检查「35000 截断和标题行逻辑是否一样」时发现）。
            //
            // 照搬 CLI `persistence.mjs` 的 readWithToc：
            //   · ≤ 35000 字符：全文注入
            //   · 超出：保留前 35000 + 尾部标题行目录（Agent 知道有什么、能去翻）
            //   · 目录排除代码块内的假标题（shell 注释 `# xxx`）
            val memFile = java.io.File(storage.root, "CLAUDE.md")
            if (memFile.exists()) {
                try {
                    val full = memFile.readText()
                    val toc = buildString {
                        append("\n\n# 项目上下文 (CLAUDE.md)\n\n")
                        if (full.length <= CLAUDE_MD_LIMIT) {
                            append(full)
                        } else {
                            append(full.take(CLAUDE_MD_LIMIT))
                            val titles = com.ccm.app.tools.task.extractHeadingLines(full.drop(CLAUDE_MD_LIMIT))
                            append("\n\n---\n\n")
                            if (titles.isNotEmpty()) {
                                append("【以下内容因超长未完整注入，这里是标题目录（共 ${titles.size} 条）。")
                                append("需要看某节正文时，用 Memory 工具的 section action 取该节")
                                append("（Memory({action:'section', title:'关键词'})），不要 Read 整个文件。】\n\n")
                                append(titles.joinToString("\n"))
                            } else {
                                append("【文件超长（${full.length} 字符），超出部分未注入且无标题可列】")
                            }
                        }
                    }
                    sb.append(toc)
                } catch (_: Throwable) {}
            }
            // 输出风格（与 CLI/Web 的 outputStyle 同字段互通）
            //
            // ⚠️ **替换而非追加**（CLI 的教训，见 prompts.mjs 的 outputStyleSection）：
            // 模板里 {{OUTPUT_STYLE}} 的位置就是风格段的位置。设了风格就整段
            // 换掉 —— 若改成 append，默认段「直接、简洁」和用户选的「详细讲解」
            // 会同时留在提示词里打架，模型无所适从。
            try {
                val styleId = com.ccm.app.core.provider.AppConfig
                    .load(storage.configFile).config.outputStyle
                val styleText = com.ccm.app.core.output.OutputStyles.promptFor(styleId)
                    ?: DEFAULT_OUTPUT_STYLE
                sb.replace(0, sb.length, sb.toString().replace("{{OUTPUT_STYLE}}", styleText))
            } catch (_: Throwable) {
                sb.replace(0, sb.length, sb.toString().replace("{{OUTPUT_STYLE}}", DEFAULT_OUTPUT_STYLE))
            }
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

            // ── API 客户端（与 refreshApi 共用同一构造，避免两处漂移）──
            val apiClient = buildApiClient(storage, config, provider) ?: return null

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
            val visionClient = buildVisionClient(config)

            // 队友消息自动送达的提供者（2026-10-06）：
            // 读「我的身份」→ 有团队就拉未读消息。TeamStore 与
            // ToolsBootstrap 用的是**同一个目录**（storage.root/teams），
            // 两个实例共享同一份文件状态，不会漂移。
            val teamStoreForInbox = com.ccm.app.tools.task.TeamStore(
                java.io.File(storage.root, "teams")
            )
            val teamInboxProvider: () -> Pair<String, List<String>>? = {
                val ident = teamStoreForInbox.myIdentity()
                if (ident == null) null
                else {
                    val (team, me) = ident
                    val unread = teamStoreForInbox.readInboxMessages(team, me, unreadOnly = true)
                    if (unread.isEmpty()) null
                    else team to unread.map { m ->
                        // 标注发送者 + 内容（对齐 CLI 的注入格式）
                        val who = m.from.ifBlank { "?" }
                        "[$who] ${m.text.ifBlank { "(空消息)" }}"
                    }
                }
            }
            val agentLoop = AgentLoop(
                api = apiClient,
                visionClient = visionClient,
                teamInboxProvider = teamInboxProvider,
                // 传**提供者**而非快照：每次调模型前现算，Memory 写完
                // CLAUDE.md / /me 改资料后立即生效（P1-4）。
                systemPromptProvider = {
                    // cwd 传进去 → 提示词里有「工作目录：<实际路径>」
                    // （每次现算，跨天日期也自动更新）
                    assembleSystemPrompt(storage, context, cwd)
                },
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
                        // ══════════════════════════════════════════════════
                        //  【2026-10-06 修】run_in_background 分流
                        // ══════════════════════════════════════════════════
                        //
                        // 原来这个闭包**无条件同步跑完**（subLoop.run().collect{}），
                        // 于是 Agent 工具的 run_in_background:true 被静默忽略 ——
                        // 工具不立即返回 task_id，而是阻塞几分钟直到子 Agent 跑完；
                        // AgentStatus 里也看不到「运行中」的它。
                        //
                        // 现在：后台模式交给 SubAgentManager.spawnAsync（它有
                        // taskId 生成、并发上限、运行中登记）。manager 未装配时
                        // 退回同步（至少功能可用，只是不后台）。
                        if (spec.runInBackground) {
                            // ⚠️ 不能用 this.subAgents —— 本函数在 **companion object**
                            // 里（build 是静态工厂），访问不到实例属性（编译报
                            // Unresolved reference）。用 companion 的静态 holder。
                            val mgr = activeSubAgentManager
                            if (mgr != null) {
                                val r = mgr.spawnAsync(spec)
                                return@spawn r
                            }
                            // manager 没装配 → 落到下面的同步路径（不 return）
                        }
                        // ══════════════════════════════════════════════════
                        //  子 Agent 提示词（2026-10-06 对照 CLI plan.mjs 补齐）
                        //
                        // CLI 的结构：systemPromptBase（主提示词全文，**含
                        // 「你的正文输出其他 Agent 看不见」那句**）+ 角色卡
                        // + 工具清单 + 轮次预算段。
                        //
                        // APK 原来只有 5 条硬编码约束、没有角色卡、没有轮次
                        // 预算说明 —— 子 Agent 不知道自己能续轮，快到上限就
                        // 交半成品；也不知道「正文没人看得见」，白写一堆给
                        // 用户看的话（队友收不到）。
                        // ══════════════════════════════════════════════════
                        val roleCard = when (spec.subagentType) {
                            "Explore" ->
                                "\n# 你的角色：探索子 Agent (Explore)\n" +
                                    "你是一个只读的探索子 Agent，用于调研代码库结构、查找文件、理解实现。\n" +
                                    "**禁止**修改任何文件、执行任何写入操作、提交 git 等。\n" +
                                    "完成后返回你的发现摘要：相关文件路径（用 file_path:line_number 格式）、" +
                                    "关键实现位置、以及简短的代码结构说明。"
                            "Plan" ->
                                "\n# 你的角色：计划子 Agent (Plan)\n" +
                                    "你是一个用于制定计划的子 Agent。先探索代码库现状，然后产出一份清晰的执行计划。\n" +
                                    "**禁止**执行任何修改操作。\n" +
                                    "返回格式：\n1. 任务概述\n2. 步骤列表（带 progress 标记）\n" +
                                    "3. 涉及的文件路径列表\n4. 潜在风险与注意事项"
                            "Coordinator" ->
                                "\n# 你的角色：协调者子 Agent (Coordinator)\n" +
                                    "你是一个多 Agent 编排者。你不直接执行任务，只做编排。\n" +
                                    "你的责任：\n1. 分析任务，拆分为可并行的子任务\n" +
                                    "2. 用 Agent 工具 spawn 多个 worker\n" +
                                    "3. 只读子任务可以并行，写同一批文件的必须串行\n" +
                                    "4. 汇总各 worker 的结果，做交叉验证（别直接采信）"
                            else ->
                                "\n# 你的角色：通用子 Agent\n" +
                                    "你是一个被主 Agent 委派的子 Agent，拥有全部工具权限。\n" +
                                    "你的任务会由主 Agent 在 prompt 中描述，请自行规划步骤、调用工具、完成任务。\n" +
                                    "完成后返回简洁的结果摘要给主 Agent，不要返回无意义的空话。"
                        }
                        // ⚠️ 用 build 的局部 cwd（cwdForSubAgent 是实例字段，
                        //    build 是 companion 静态方法，够不着 —— CI 报
                        //    Unresolved reference）
                        val subPrompt = assembleSystemPrompt(storage, context, cwd) + "\n" + roleCard +
                            "\n\n## 你是子 Agent（通用约束）\n" +
                            "你被主 Agent 派来独立完成一个子任务，运行在**后台**。\n" +
                            "· **你的正文输出其他 Agent 看不见** —— 想让主 Agent 知道任何事，" +
                            "必须写进最终总结里（那段文字会原样返回）\n" +
                            "· 你没有派生子 Agent 的能力（不要调用 Agent 工具，会报错）\n" +
                            "· 你无法与用户交互（**永远不要**调用 AskUserQuestion，会永久阻塞）\n" +
                            "· 不要问「要我做吗」，直接做；信息不足时基于合理假设推进，并说明假设\n" +
                            "· 完成后用一段清晰的文字总结结论/产出（含关键文件路径与代码位置）\n" +
                            "\n## 轮次预算\n" +
                            "你的工具轮次有上限。快到上限时会收到系统提示，届时：\n" +
                            "任务确实没做完 → 用 **ExtendTurns** 续轮（最多续 4 次、每次最多 +60、硬上限 400）；\n" +
                            "已基本完成或发现自己在原地打转 → 立刻收尾，如实交代未完成部分。\n" +
                            "**不要交「函数写好了但没接线」这类半成品**：要么做完并自测通过，" +
                            "要么在报告里写清「未完成的是什么、下一步该怎么做、有哪些已查明的前置结论」。\n"
                        val subLoop = AgentLoop(
                            api = apiClient,
                            teamInboxProvider = teamInboxProvider,
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
                appStorage = storage,
                apiClient = apiClient,
                visionClient = visionClient,
                agentLoop = agentLoop,
                toolRegistry = registry,
                sessionStore = sessionStore,
                compactor = compactor,
                // 【2026-10-06 P1-8 接线】阈值从配置读。
                // 【2026-10-07 语义统一】对齐 CLI /compact-threshold <tokens> [messages]：
                // 新字段 compactTokenLimit / compactMessageLimit 是绝对值，直接透传。
                // 旧字段 compactThreshold（百分比 0-100）仅作兼容降级 ——
                // 新字段全为 0 且旧字段 >0 时，仍按  maxContext × pct / 100 换算，
                // 老配置不至于静默失效；两者都为 0 则 isEnabled=false（默认关闭）。
                autoCompact = AutoCompact(
                    tokenLimit = when {
                        config.compactTokenLimit > 0 -> config.compactTokenLimit
                        config.compactThreshold > 0 ->
                            (config.maxContextTokens.toLong() * config.compactThreshold / 100).toInt()
                        else -> 0
                    },
                    messageLimit = config.compactMessageLimit,
                    maxContext = config.maxContextTokens,
                ),
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
        /**
         * 按配置建主 API 客户端。
         *
         * **build() 和 refreshApi() 必须共用它** —— 分两处写会漂移
         * （改了热更新路径忘了初始化路径，表现是「新装的和切配置后的行为不一致」）。
         *
         * @return null = provider 没 key（调用方自行决定兜底）
         */
        fun buildApiClient(
            storage: AppStorage,
            config: AppConfig,
            provider: ProviderConfig,
        ): ApiClient? {
            val keys = provider.allKeys()
            if (keys.isEmpty()) return null
            return ApiClient(
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
        }

        /**
         * 按配置建识图客户端（vision 路由；不满足条件返回 null）。
         * 同样由 build() 与 refreshApi() 共用。
         */
        fun buildVisionClient(config: AppConfig): ApiClient? {
            // ══════════════════════════════════════════════════════════════
            //  【2026-10-06 修·语义反了】
            //
            //  CLI 的语义（index.mjs:785 / 3880）：
            //    · vision=true  → **当前模型**自己看图（visionApi = api）
            //    · vision=false → 用**备用识图 Provider**（把图发给它）
            //  即开关表示「当前 Provider 有没有视觉能力」。
            //
            //  APK 原来是反的：vision=true 才建备用客户端去转述 ——
            //  用户开这个开关（本意「我要识图」）实际得到的是
            //  「图被发给另一个模型转述」，而不是「当前模型看图」。
            //
            //  正确条件：**vision 没开**（当前模型不能看图）**且**配了
            //  备用 Provider → 才需要备用客户端。
            // ══════════════════════════════════════════════════════════════
            val vp = config.visionProvider
            val need = config.vision != true && vp != null &&
                config.visionProviderId != null &&
                config.visionProviderId != config.current &&
                vp.url.isNotBlank() && vp.allKeys().isNotEmpty()
            if (!need || vp == null) return null
            return ApiClient(
                baseUrl = vp.url,
                apiKeys = vp.allKeys(),
                model = vp.model,
                protocol = vp.protocolType,
                maxOutputTokens = vp.maxOutputTokens,
                temperature = vp.temperature ?: 1.0,
            )
        }

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
