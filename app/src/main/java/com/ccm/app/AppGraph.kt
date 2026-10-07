package com.ccm.app

import android.content.Context
import com.ccm.app.bridge.NativeBridge
import com.ccm.app.core.AppStorage
import com.ccm.app.core.ChatSession
import com.ccm.app.core.FileAppStorage
import com.ccm.app.core.provider.AppConfig
import com.ccm.app.core.session.SessionStore
import com.ccm.app.core.tool.AppBackedToolStorage
import com.ccm.app.core.tool.ToolRegistry
import com.ccm.app.tools.task.asToolObserver
import com.ccm.app.core.tool.ToolSettings
import com.ccm.app.core.AppContainer
import com.ccm.app.tools.AndroidImageScaler
import com.ccm.app.tools.ToolsBootstrap
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * 应用装配图 —— **把新架构（Kotlin core）真正接起来的唯一入口**。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要这个类（阶段 5 的核心）
 * ══════════════════════════════════════════════════════════════
 *
 * 在它出现之前，项目里有**四处零调用**，导致 99 个工具 + AgentLoop +
 * ChatSession 全部悬空 —— 编译通过、单测全绿，装机后跑的却是老架构：
 *
 * | 组件 | 修复前 | 谁调它 |
 * |---|---|---|
 * | `ToolsBootstrap.install()` | **零调用** | 本类 [init] |
 * | `AppContainer.build()` | 只被 ChatSession.create 调，而后者也没人调 | ChatSession |
 * | `ChatSession.create()` | **零调用** | 本类 [init] |
 * | `AppContainer.attachSubAgents()` | **零调用** | 本类 [init]（尽力而为） |
 *
 * 本类把这条链一次接完：
 * ```
 * AppGraph.init(context, scope)
 *   ├── AppStorage（文件存储根）
 *   ├── NativeBridge（Android 原生能力桥）
 *   ├── ToolRegistry ← ToolsBootstrap.install()   ★ 99 个工具在这里注册
 *   ├── ChatSession.create() ← 内部装配 ApiClient / AgentLoop
 *   └── session（UI 直接用）
 * ```
 *
 * ══════════════════════════════════════════════════════════════
 *  生命周期（为什么是进程级单例）
 * ══════════════════════════════════════════════════════════════
 *
 * `ChatSession` 持有 `AgentLoop`（含 OkHttp 连接池）和会话历史。
 * 每次重组都重建会带来三个问题：
 * 1. **对话历史丢失** —— 旋转屏幕 / 进程回收重建就清空
 * 2. **连接池泄漏** —— 旧 OkHttp 实例没 shutdown，socket 越积越多
 * 3. **工具状态丢失** —— Bash 后台任务、子 Agent 全断
 *
 * 所以按**进程级**持有，由 [MainActivity] 在 `onCreate` 初始化一次。
 * Activity 重建（配置变更）时复用同一实例。
 *
 * ⚠️ **不用 Application 子类**：项目 Manifest 没声明 `android:name`，
 * 加 Application 类要改 Manifest 且影响所有进程；`object` 单例更轻，
 * 且本项目只有一个进程。
 *
 * ══════════════════════════════════════════════════════════════
 *  用法
 * ══════════════════════════════════════════════════════════════
 * ```kotlin
 * // MainActivity.onCreate
 * val graph = AppGraph.init(applicationContext, scope)
 * setContent { CcmRoot(graph) }
 *
 * // 任意地方取会话
 * AppGraph.session?.send("你好")
 * ```
 */
object AppGraph {

    /** 默认工作目录名（App 私有，无需运行时权限）。 */
    private const val WORKSPACE_DIR = "workspace"

    /**
     * 解析工作区目录（2026-10-01）。
     *
     * 语义（2026-10-06 改）：**配置为空 = 没有工作区**，返回空串。
     *
     * 原来会兜底到 files/workspace —— 用户无法表达「我不用工作区」，
     * App 还会在隐藏位置建目录写文件。现在只有显式配置且目录**已存在**
     * 时才返回路径（不自动建目录，避免写错路径时到处建目录）。
     */
    private fun resolveWorkspaceDir(st: com.ccm.app.core.AppStorage, cfg: com.ccm.app.core.provider.AppConfig): String {
        val configured = cfg.workspacePath?.trim().orEmpty()
        if (configured.isEmpty()) {
            // ══════════════════════════════════════════════════════════
            //  【2026-10-06 改】没配置 = **没有工作区**，不再兜底默认目录
            // ══════════════════════════════════════════════════════════
            //
            // 原来这里会依次尝试 envMode 外部目录 → files/workspace，
            // 永远返回一个路径 —— 用户无法表达「我不用工作区」，
            // 而且 App 会在他不知道的地方悄悄建目录、往里写文件。
            //
            // 现在的语义：**空 = 真的没有**。返回空串，由调用方处理。
            // 想用工作区就显式设（/workspace <路径> 或设置页填）。
            return ""
        }
        return try {
            val d = File(configured)
            // 【2026-10-06】原来 `d.isDirectory || d.mkdirs()` 会自动建目录 ——
            // 用户写错路径时会在他没预期的地方建目录。
            // 现在：目录必须已存在（不自动建），不存在就返回空。
            if (d.isDirectory) d.absolutePath else ""
        } catch (_: Throwable) {
            ""
        }
    }

    /**
     * 当前工作区路径（`/diff` `/files` 等命令用）。
     *
     * 与装配时传给 AgentLoop 的 cwd **同一套解析规则**（[resolveWorkspaceDir]）——
     * 之前 `/files` 直接拼 `storage.root/workspace`，配置了 workspacePath 时
     * 会指错目录。本 getter 统一出口，新命令都用它。
     *
     * 存储未初始化、或**没有配置工作区**时返回空串（调用方自行提示）。
     */
    fun workspacePath(): String {
        val st = storage ?: return ""
        val cfg = AppConfig.load(st.configFile).config
        return resolveWorkspaceDir(st, cfg)
    }

    /**
     * App 版本号（问题40：/doctor 用）。
     *
     * 从 PackageManager 读（build.gradle 的 versionName）。
     */
    fun appVersion(): String = try {
        val ctx = appContext ?: return "unknown"
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
    } catch (_: Throwable) { "unknown" }

    /**
     * 读 GitHub 配置（问题40）。
     *
     * 格式对齐 CLI 的 `~/.claude-code-mobile/github.json`：
     * ```json
     * { "token": "ghp_...", "defaultRepo": "owner/name" }
     * ```
     * APK 侧位置：`files/github.json`。
     *
     * 【为什么需要】ToolsBootstrap 的 githubToken/githubRepo 参数原来从来没传
     * → GitHubRepo/GitHubIssues/GitHubPRs/GitHubFile 等 8 个工具全部报「未配置」。
     */
    private fun readGithubConfig(): Pair<String, String?>? = try {
        val root = storage?.root ?: return null
        val f = java.io.File(root, "github.json")
        if (!f.exists()) null
        else {
            val o = org.json.JSONObject(f.readText())
            val token = o.optString("token", "").takeIf { it.isNotBlank() }
            val repo = o.optString("defaultRepo", "").takeIf { it.isNotBlank() }
            token?.let { it to repo }
        }
    } catch (_: Throwable) { null }

    // ══════════════════════════════════════════════════════════════
    //  装配结果（UI 读这些决定渲染什么）
    // ══════════════════════════════════════════════════════════════

    /** 装配是否**已尝试**过（成功或失败都置 true，避免每帧重跑必失败的装配）。 */
    @Volatile
    var initialized: Boolean = false
        private set

    /**
     * 装配失败原因。
     *
     * 非 null 时 UI 应显示错误页而不是空白页。
     * 最常见的值：「没有可用的 Provider」—— 用户还没配 API。
     */
    @Volatile
    var initError: String? = null
        private set

    /** 应用存储根（设置页/诊断用）。 */
    @Volatile
    var storage: AppStorage? = null
        private set

    /** 工具注册表（99 个工具装配后在这里）。 */
    @Volatile
    var registry: ToolRegistry? = null
        private set

    /**
     * init 时的协程作用域 —— [openSession] 重建会话时要复用它。
     * 不存的话换会话时拿不到 scope，ChatSession.create 就没法调。
     */
    var appScope: kotlinx.coroutines.CoroutineScope? = null
        private set

    /** init 时的图片缩放器 —— 同上，openSession 复用。 */
    private var imageScaler: AndroidImageScaler? = null

    /** 工具装配结果（含 executor / permissions / hooks，供诊断展示）。 */
    @Volatile
    var toolsResult: ToolsBootstrap.Result? = null
        private set

    /**
     * 工具侧只读配置（[com.ccm.app.core.tool.MapToolSettings] 持有可变 map）。
     *
     * 【/tvly 热生效】设完 Tavily key 后调
     * `toolSettings?.put("tavilyApiKey", key)` —— WebTools 是
     * `get() = map[key]` 实时读，**不重建任何对象立即生效**。
     */
    @Volatile
    var toolSettings: com.ccm.app.core.tool.ToolSettings? = null
        private set

    /** 核心容器（含 AgentLoop / ApiClient / SessionStore）。 */
    @Volatile
    var container: AppContainer? = null
        private set

    /**
     * 运行模式状态（deep / plan / watch）—— **进程级单例**。
     *
     * ══════════════════════════════════════════════════════════════
     *  ⚠️ 必须是单例，不能每次建会话新建一个
     * ══════════════════════════════════════════════════════════════
     *
     * `ToolsBootstrap.install()` **只在 [init] 里跑一次**，模式工具
     * （EnterDeepMode 等）持有的是那一刻传入的 `modes` 引用。
     * 而 [openSession] / [rebuild] 会重建 AgentLoop —— 如果那时传一个新的
     * ModeState，就是「工具写 A、主循环读 B」：
     *
     * ```
     * EnterDeepMode → modes_A.deepMode = true
     * AgentLoop     → 读 modes_B.deepMode == false → 轮数没变
     * ```
     * 表现为「工具说进入成功了，但一点效果都没有」——**静默失效**，
     * 不报错、不崩，最难查。所以这里按进程级持有，所有路径共用。
     */
    val modes: com.ccm.app.core.agent.ModeState = com.ccm.app.core.agent.ModeState()

    /**
     * 自动记忆提取器（automem）—— 同样**进程级单例**。
     *
     * 理由与 [modes] 相同：Memory 工具（ToolsBootstrap 持有）要通知它
     * 「本轮别重复提取」，而提取动作在 ChatSession 里调 —— 两边必须是同一个。
     *
     * `null` = 创建失败（存储不可写等极端情形），此时 automem 整体不生效，
     * 但对话功能不受影响。
     */
    @Volatile
    var autoMemory: com.ccm.app.core.memory.AutoMemory? = null
        private set

    // 【2026-10-06 问题12 修复】原来是个 @Volatile var —— MainActivity 里
    // `CcmApp(session = AppGraph.session)` 只在**重组时**读一次，
    // 用户在设置页加了 Provider 后 session 变了但 UI 不重组，
    // 顶部「尚未配置 API」横幅不消失（用户报的问题12）。
    //
    // 改用一个 Compose State 承载（显式 API，不用 by 委托 —— 委托要额外
    // import getValue/setValue，这个文件是纯逻辑层，不想引 Compose 依赖）。
    // 属性本身仍是普通 var，读写点不变；Compose 侧读 [sessionState] 即可。
    //
    // ⚠️ 不能用 @Volatile：它只对 backing field 生效，而下面这个属性是
    //    getter/setter 委托给 State 的（没有 backing field），Kotlin 直接报错。
    //    线程安全由 Compose 的 Snapshot 机制保证（跨线程写也是原子的）。
    private val _sessionState = androidx.compose.runtime.mutableStateOf<ChatSession?>(null)

    /** 对话门面 —— **UI 接入 Agent 的唯一入口**。 */
    var session: ChatSession?
        get() = _sessionState.value
        set(value) { _sessionState.value = value }

    /** 供 Compose 观察的 State（问题12：MainActivity 读它才会在变更时重组）。 */
    val sessionState: androidx.compose.runtime.State<ChatSession?> get() = _sessionState

    /**
     * 子 Agent 管理器（问题40）。
     *
     * 时序：ToolsBootstrap 先构造（拿不到 container），
     * SubAgentManager 后创建（需要 container）—— 所以 observer 事后注入。
     */
    @Volatile
    var pendingSubAgentManager: com.ccm.app.core.agent.SubAgentManager? = null

    /**
     * 应用 Context（问题29：读 assets 提示词用）。
     *
     * 【为什么存起来】`ChatSession.create(context=...)` 需要它，
     * 但 `openSession` / `rebuild` 这些方法**没有 context 参数** ——
     * init 时存一份，各处直接用。
     */
    @Volatile
    var appContext: android.content.Context? = null

    // ══════════════════════════════════════════════════════════════
    //  【2026-10-06 问题40】AskUserQuestion 的 UI 桥
    // ══════════════════════════════════════════════════════════════
    //
    // 原来 `askUser` 回调（ToolsBootstrap 参数）从来没传过 →
    // AskUserQuestion 工具永远报「当前环境无法向用户提问（未接入 UI 回调）」。
    //
    // 实现：一个「待回答问题」State + 一个挂起的 CompletableDeferred。
    //   工具侧调 askUserBlocking() → 设置 State（UI 弹窗）→ await 答案
    //   UI 侧（CcmApp）监听 State → 弹对话框 → 用户选 → complete Deferred

    /** 待回答的问题：问题文本 + 选项（空 = 纯文本输入）。null = 无待答。 */
    private val _pendingQuestion = androidx.compose.runtime.mutableStateOf<Pair<String, List<String>>?>(null)
    val pendingQuestion: androidx.compose.runtime.State<Pair<String, List<String>>?> get() = _pendingQuestion

    /** 等待中的答案（工具侧 await）。 */
    private var answerDeferred: kotlinx.coroutines.CompletableDeferred<String?>? = null

    /** 工具侧调用：发起提问并挂起等待。 */
    suspend fun askUserBlocking(question: String, options: List<String>): String? {
        // 已有待答问题 → 拒绝（避免嵌套）
        if (_pendingQuestion.value != null) return null
        val d = kotlinx.coroutines.CompletableDeferred<String?>()
        answerDeferred = d
        _pendingQuestion.value = question to options
        return try {
            // 超时 5 分钟（等用户操作）
            kotlinx.coroutines.withTimeoutOrNull(300_000L) { d.await() }
        } finally {
            _pendingQuestion.value = null
            answerDeferred = null
        }
    }

    /** UI 侧调用：用户回答（或取消传 null）。 */
    fun answerQuestion(answer: String?) {
        answerDeferred?.complete(answer)
    }

    // ══════════════════════════════════════════════════════════════
    //  手机操作模式选择（2026-10-06 加，对齐 CLI 的 modePrompter）
    // ══════════════════════════════════════════════════════════════
    //
    // 与 AskUserQuestion 同款桥：工具侧挂起等 → UI 弹框 → 用户选 → 唤醒。
    // 区别是这里选项固定三项（前台/后台/不操作），且**不超时**
    // —— 用户可能离开手机，超时会让「选了后台」变成「静默不操作」。

    private var modeDeferred: kotlinx.coroutines.CompletableDeferred<String?>? = null

    /** 待选模式的可见状态（UI 读它决定要不要弹框）。 */
    val pendingPhoneMode = androidx.compose.runtime.mutableStateOf<Boolean>(false)

    /**
     * 工具侧调用：请求用户选模式并挂起等待。
     *
     * @return 'foreground' | 'background' | 'idle'；null = 无法弹（无 UI）
     */
    suspend fun requestPhoneModeBlocking(): String? {
        if (pendingPhoneMode.value) return null   // 已在问 → 不嵌套
        val d = kotlinx.coroutines.CompletableDeferred<String?>()
        modeDeferred = d
        pendingPhoneMode.value = true
        return try {
            kotlinx.coroutines.withTimeoutOrNull(600_000L) { d.await() }
        } finally {
            pendingPhoneMode.value = false
            modeDeferred = null
        }
    }

    /** UI 侧调用：用户选了模式（取消传 null → 按 idle 处理）。 */
    fun answerPhoneMode(mode: String?) {
        modeDeferred?.complete(mode)
    }

    /**
     * 工具侧调用的简化入口（PhoneTools.modeGate 用）。
     *
     * 返回非 suspend 的取值 —— 因为 PhoneTools 的 modeGate 是同步函数。
     * 实现：起协程跑 requestPhoneModeBlocking 并阻塞等结果。
     *
     * ⚠️ 必须在**非主线程**调用（阻塞主线程会死锁 —— UI 弹框要主线程）。
     * PhoneTools 的工具执行都在 Dispatchers.IO 上，安全。
     */
    @Volatile
    var phoneModePrompter: (() -> String?)? = null

    /** 已注册工具名清单（供设置页展示与自检）。 */
    @Volatile
    var toolNames: List<String> = emptyList()
        private set

    /** 当前会话 id（工具/hooks/子 Agent 归属都用它）。 */
    @Volatile
    var sessionId: String = ""
        private set

    /**
     * 用户资料存储（称呼 / 职业 / 回复偏好）。
     *
     * ## 为什么放这里
     * - 对齐 Web 的 `user_profile`（localStorage）+ CCM 的 `cli-profile.json`
     * - 不属于 Provider 配置（config.json），独立文件 `user-profile.json`
     * - UI 多处要用（首页问候、侧栏、设置页），从 AppGraph 单例取最方便
     *
     * ## 用法
     * ```kotlin
     * val profile = AppGraph.userProfileStore?.load()
     * greetingFor(profile?.callName)   // 空 → 自动降级为通用问候
     * ```
     */
    @Volatile
    var userProfileStore: com.ccm.app.core.user.UserProfileStore? = null
        private set

    /**
     * 本次启动是否从老架构（proot 内 HOME）迁移了配置。
     *
     * UI 可以据此提示用户「已从旧版本导入配置」。目前只在日志里体现，
     * 但留着这个标志是因为它**解释了用户看到的现象** —— 用户升级后
     * 打开 App 发现 Provider 还在，会想知道为什么。
     */
    @Volatile
    var migrated: Boolean = false
        private set

    // ══════════════════════════════════════════════════════════════
    //  派生状态（UI 判据）
    // ══════════════════════════════════════════════════════════════

    /**
     * 是否**已配置可用 Provider**。
     *
     * UI 据此决定：显示对话界面，还是显示「去设置里配 API」的引导。
     * 注意与 [hasSession] 的区别 —— 这个只看配置，不看装配成功与否。
     */
    val hasProvider: Boolean
        get() = (storage?.let { AppConfig.load(it.configFile).config.currentProvider }) != null

    /** 是否已接上可用会话。 */
    val hasSession: Boolean get() = session != null

    /** 当前 Provider 显示名（状态栏用）。 */
    val providerLabel: String
        get() = storage
            ?.let { AppConfig.load(it.configFile).config.currentProvider?.displayName }
            ?: "未配置"

    // ══════════════════════════════════════════════════════════════
    //  装配
    // ══════════════════════════════════════════════════════════════

    /**
     * 装配整条链路。**幂等** —— 重复调用直接返回已有会话。
     *
     * 同步方法。内部各步骤都是纯内存操作（建对象 + 注册工具），
     * 不涉及网络与磁盘大 IO。唯一的重活是 `AppConfig.load`（读几 KB JSON）
     * 与 `ToolsBootstrap.install`（建 ~99 个对象），实测 < 100ms。
     *
     * **失败不抛异常** —— 原因写进 [initError]，返回 null。
     *
     * @param context 任意 Context（内部取 `applicationContext`）
     * @param scope   会话协程作用域。**必须是长生命周期作用域** ——
     *                传 Activity 的 `lifecycleScope` 会在旋转屏幕时把
     *                正在跑的对话一起取消。推荐在 Activity 里建
     *                `CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`
     *                并在 `onDestroy` 里**不要** cancel（让它跟进程走）。
     * @return 装配好的会话；配置无效（没 Provider / 没 key）时返回 null
     */
    @Synchronized
    fun init(context: Context, scope: CoroutineScope): ChatSession? {
        if (initialized) return session

        val app = context.applicationContext
        appContext = app   // 存起来供 openSession / rebuild 用
        initialized = true   // 先置位：即使抛异常也不该每帧重试

        return try {
            // ── 1. 存储根 ────────────────────────────────────────────
            val st = FileAppStorage(app.filesDir).also { it.ensureDirs() }
            storage = st

            // ── 1.2 用户资料（称呼 / 职业 / 回复偏好）────────────────
            //
            // 对齐 Web 的 user_profile + CCM 的 cli-profile.json。
            // 必须在这里初始化：UI（首页问候、侧栏）启动时就要读，
            // 不能等「用户第一次进设置页」才建。
            userProfileStore = com.ccm.app.core.user.UserProfileStore(st)

            // ── 1.5 迁移老架构配置（★ 不做的话用户已配的 Provider 全丢）──
            //
            // 老架构把配置写在 **proot 内的 HOME**（`/root/.claude-code-mobile/`），
            // 新架构读 App 私有目录（`filesDir/config.json`）。
            // 不迁移的后果：用户升级后打开 App，「没有 Provider」→ 得重新配一遍。
            // 这不只是麻烦 —— 用户可能已经忘了 key 是什么。
            migrateLegacyConfig(app, st)

            // ── 2. 会话 id（★ 必须先定下来）──────────────────────────
            //
            // 工具侧（Goal 工具、hooks 的 SESSION_ID、子 Agent 归属）和
            // 存盘侧必须用**同一个** id。ChatSession.create 支持传入，
            // 所以这里先生成、两处共用。
            //
            // 早期实现踩过：AgentLoop 硬编码 ""、SessionAuto 自己 newSessionId()
            // → 同一场对话两个 id，表现为「工具里拿不到会话 id」。
            val sid = SessionStore(st).newSessionId()
            sessionId = sid
            appScope = scope
            imageScaler = AndroidImageScaler(app.cacheDir)

            // ── 2.5 automem 提取器（★ 必须在 ToolsBootstrap 之前建）──
            //
            // Memory 工具（ToolsBootstrap 里构造）要拿到它做互斥通知，
            // 所以顺序不能反。状态文件与记忆文件都放 files/ 根。
            autoMemory = try {
                com.ccm.app.core.memory.AutoMemory(
                    stateFile = File(st.root, "automem.json"),
                    memoryFile = File(st.root, "CLAUDE.md"),
                )
            } catch (_: Throwable) {
                null
            }

            // ── 3. 配置 ──────────────────────────────────────────────
            val cfg = AppConfig.load(st.configFile).config
            val provider = cfg.currentProvider

            // 工具侧只读快照。没 Provider 时传 null —— 依赖 settings 的工具
            // （WebSearch / FindImage / ImageGen）会**不注册**，而不是注册了
            // 再报「没 key」。这是 ToolsBootstrap 的既定设计。
            val settings: ToolSettings? = provider?.let {
                AppContainer.buildSettings(cfg, it)
            }

            // ── 3.9 手机操作模式的 UI 桥（2026-10-06）──────────────
            // 工具侧（PhoneTools.modeGate）会调它请求用户选模式。
            // 用 runBlocking 起协程等 UI —— 调用方在 IO 线程，不会死锁主线程。
            phoneModePrompter = {
                try {
                    runBlocking { requestPhoneModeBlocking() }
                } catch (_: Throwable) { null }
            }

            // ── 4. 工具注册（★ 这一步以前从没被调用过）──────────────
            val reg = ToolRegistry()
            val tools = ToolsBootstrap(
                context = app,
                storage = AppBackedToolStorage(st),
                settings = settings,
                bridge = NativeBridge(app),
                // 【2026-10-06 问题40】AskUserQuestion 的 UI 桥 ——
                // 原来这个参数从来没传 → 工具永远报「未接入 UI 回调」。
                askUser = { q, opts -> askUserBlocking(q, opts) },
                // 【2026-10-06 问题40】GitHub 工具 —— 原来这两个参数从来没传
                // → GitHubRepo/GitHubIssues 等 8 个工具全部报「未配置」。
                // 配置格式对齐 CLI 的 ~/.claude-code-mobile/github.json：
                //   { "token": "ghp_...", "defaultRepo": "owner/name" }
                // 【2026-10-07】改传提供者（每次工具调用现读 github.json）——
                // /github login 设完 token 立即生效，不用重启 App
                githubToken = { readGithubConfig()?.first },
                githubRepo = { readGithubConfig()?.second },
                // 【2026-10-06 问题40】CommandExec —— 原来没传 →
                // 工具永远报「未接入（需要 App 层注入命令执行器）」。
                //
                // ⚠️ 这里只能跑**不依赖 UI 的命令**（session 相关：/clear /compact
                //    /cost /context /help 等）。依赖导航/面板的（/model /style）
                //    会返回「需要在界面上操作」的提示，而不是假装成功。
                commandExec = { cmd ->
                    try {
                        val full = if (cmd.startsWith("/")) cmd else "/$cmd"
                        val result = com.ccm.app.ui.chat.handleSlashCommand(
                            full,
                            com.ccm.app.ui.chat.SlashContext(
                                session = session,
                                appContext = app,
                                // 这三个依赖 UI —— 传空实现（命令会返回提示）
                                navigate = {},
                                newChat = {},
                                openPanel = {},
                            ),
                        )
                        // SlashResult 是 sealed class，toString() 会输出
                        // `Notice(markdown=...)` 这种内部格式 —— 提取真正的内容。
                        when (result) {
                            null -> "命令 `$full` 不被识别（或需要界面操作）。可用：/clear /compact /cost /context /help"
                            is com.ccm.app.ui.chat.SlashResult.Notice -> result.markdown
                            is com.ccm.app.ui.chat.SlashResult.Toast -> result.text
                            is com.ccm.app.ui.chat.SlashResult.Navigate -> "（命令要求跳转到 ${result.route} —— 请在界面上操作）"
                            is com.ccm.app.ui.chat.SlashResult.OpenPanel -> "（命令要求打开面板 ${result.panel} —— 请在界面上操作）"
                            else -> "命令已执行（无输出）"
                        }
                    } catch (t: Throwable) {
                        "命令执行失败：${t.message}"
                    }
                },
                // ★ 必须传 getter（不是字符串快照）—— GoalTools 在
                //   每次调用时现取，这样 [rebuild] 换了会话 id 它也能跟上。
                getSessionId = { sessionId },
                // ★ 模式状态与 automem 必须传进程级单例（见字段注释）：
                //   工具持有的是这一份引用，之后 rebuild 重建 AgentLoop
                //   也要读同一份，否则模式工具静默失效。
                modes = modes,
                autoMemory = autoMemory,
            ).install(
                reg,
                // 【2026-10-06 envMode】主通道按用户在引导页/设置里选的运行环境来：
                //   · termux → TermuxChannel（RUN_COMMAND Intent，命令跑在 Termux 里）
                //   · 其它/空 → null（ToolsBootstrap 默认用内置 proot）
                // 两个通道对象 ToolsBootstrap 都会建，选谁只是换 primary；
                // 另一个自动成为 fallback（主通道不可用时降级，见 BashTool 逻辑）。
                bashChannel = if (cfg.envMode == "termux") {
                    com.ccm.app.tools.bash.TermuxChannel(app)
                } else null,
            )

            toolsResult = tools
            toolSettings = settings
            registry = reg
            toolNames = tools.registered

            // ── 5. 会话（内部装配 ApiClient + AgentLoop）─────────────
            // ★ 2026-10-01：优先用配置的 workspacePath（与 CLI /workspace 同语义），
            //   空/不可写时落回默认 files/workspace。
            val cwd = resolveWorkspaceDir(st, cfg)
            val sess = ChatSession.create(
                storage = st,
                registry = reg,
                toolRunner = tools.executor,
                scope = scope,
                context = app,
                imageScaler = imageScaler,
                cwd = cwd,
                sessionId = sid,
                modes = modes,
                autoMemory = autoMemory,
            )

            if (sess == null) {
                // 工具已注册（上面成功），只是没有可用 Provider。
                // 这是**正常状态**（新装用户还没配 API），不是错误。
                initError = "尚未配置 API —— 请到「设置 → 模型」里添加一个 Provider"
                return null
            }

            session = sess
            // ══════════════════════════════════════════════════════════
            //  【2026-10-06 修 P0】把 container 存下来
            // ══════════════════════════════════════════════════════════
            //
            // 这个属性（声明在文件顶部）设计意图是「装配完成后持有当前
            // AppContainer」，但**从未被赋过非 null 值**（唯一赋值是
            // shutdown() 里的 = null）。后果是三条功能链静默全废：
            //   1. 子 Agent 观察窗：init 里 `if (container != null)` 守卫
            //      永不成立 → attachSubAgents 不执行 → AgentStatus/
            //      AgentOutput/AgentStop 永远报「观察器未接入」
            //   2. /btw 永远失败（container == null 判断恒真）
            //   3. 热更新永远报失败（container?.refreshApi() 短路成 null）
            container = sess?.appContainer
            initError = null
            // 待办看板恢复（audit-core #3）
            try { sess.restoreTodos(tools.loadTodos()) } catch (_: Throwable) {}

            // 【2026-10-06 问题40】SessionStart hook ——
            // 原来 APK 完全不触发这个事件。
            appScope?.launch {
                try { sess.triggerHook("SessionStart") } catch (_: Throwable) {}
            }

            // ══════════════════════════════════════════════════════════
            //  【2026-10-06 问题40】接子 Agent（原来零调用）
            // ══════════════════════════════════════════════════════════
            // `attachSubAgents` 从来没被调用过 → AgentStatus/AgentOutput/
            // AgentStop 全部报「观察器未接入」，子 Agent 派出去也看不见。
            //
            // spawn 的实现：**用当前 AppContainer 的装配**跑一个独立 AgentLoop
            // （独立上下文 = 子 Agent 的核心价值），把文本输出收集起来。
            // 与 AppContainer 里 spawnSubAgent 的实现同构 —— 两者都是
            // 「独立 loop + 收集 TextDelta」。
            appScope?.let { sc ->
                try {
                    val c = container
                    if (c != null) {
                        val mgr = c.attachSubAgents(sc) { spec, handle ->
                            // 用 AppContainer.runSubAgent（它持有全部依赖）
                            val r = c.runSubAgent(spec)
                            handle.report(turns = r.turns, outputTail = r.output.takeLast(200))
                            r
                        }
                        // 把 manager 注入 AgentTools.observer —— 否则
                        // AgentStatus/AgentOutput/AgentStop 三个工具报「观察器未接入」。
                        // （observer 是 @Volatile var，可以事后设）
                        try {
                            toolsResult?.agentTools?.observer = mgr.asToolObserver()
                        } catch (_: Throwable) {}
                        pendingSubAgentManager = mgr
                    }
                } catch (_: Throwable) {}
            }

            // ★ cron 心跳（audit-core #8：持久任务存盘但没人调度 → 永不触发）。
            //   每 30s 扫一次；目标会话在忙就跳过本轮（返回 false 不记账，
            //   下个 tick 重试，任务不会丢）。init 幂等 → 只会起一个循环。
            appScope?.launch {
                while (true) {
                    kotlinx.coroutines.delay(30_000)
                    try {
                        tools.cron?.schedulerTick { task ->
                            val s = session
                            if (s == null || s.isRunning) false
                            else { s.send(task.prompt); true }
                        }
                    } catch (_: Throwable) {}
                }
            }
            // 清理 7 天前的图片附件（发送完拷进 cache 的图不清理会无限涨）
            try { com.ccm.app.core.image.AttachmentCache.pruneOld(app) } catch (_: Throwable) {}
            sess
        } catch (t: Throwable) {
            initError = "${t::class.java.simpleName}: ${t.message}"
            null
        }
    }

    /**
     * 打开一个历史会话 —— 侧栏 / 对话列表点进来时调。
     *
     * ## 为什么不复用旧 session 实例
     * ChatSession 内部的 AgentLoop / SessionAuto 都绑定创建时的 sessionId
     * （getSessionId 是闭包）。给它们「换 id」会牵扯定时保存、Goal 工具、
     * hooks 三处的 id 一致性 —— 历史上就踩过「两个 id 对不上」的坑
     * （见 init 里「会话 id 必须先定下来」的注释）。所以**换会话 = 整个重建**，
     * 用 [ChatSession.create] 传目标 id，让所有组件从同一个 id 起步。
     *
     * ## 三步
     * 1. 旧会话 [ChatSession.dispose]：先 flush 自动保存（最后 30s 不丢），
     *    再停掉它的 SessionAuto —— 不停的话两个 auto 会抢写同一个文件。
     * 2. 重建 + [ChatSession.loadHistory]：AgentLoop 和 UI 气泡同时灌。
     * 3. 切 [sessionId]：ToolsBootstrap 的 getSessionId getter 是
     *    `{ sessionId }` 闭包，跟着这里走，工具侧自动对齐。
     *
     * @return 新会话；id 不存在或配置无效返回 null（调用方留在原页）
     */
    @Synchronized
    fun openSession(id: String): ChatSession? {
        val st = storage ?: return null
        val scope = appScope ?: return null
        val reg = registry ?: return null
        val tools = toolsResult ?: return null
        // 工作区配置（resolveWorkspaceDir 用）
        val cfg = AppConfig.load(st.configFile).config
        if (id.isBlank()) return null
        if (id == sessionId && session != null) return session   // 已经在这场对话里

        return try {
            // ① 释放旧会话（flush + 停自动保存 + 断连接）
            session?.dispose()

            // ② 先切换当前 sessionId：ToolsBootstrap 的 getter 需要在恢复 Todo 前
            // 指向目标会话，否则会从旧会话文件读取任务清单。
            sessionId = id
            // ② 重建
            // ★ 2026-10-01：优先用配置的 workspacePath（与 CLI /workspace 同语义），
            //   空/不可写时落回默认 files/workspace。
            val cwd = resolveWorkspaceDir(st, cfg)
            val sess = ChatSession.create(
                storage = st,
                registry = reg,
                toolRunner = tools.executor,
                scope = scope,
                context = appContext,
                imageScaler = imageScaler,
                cwd = cwd,
                sessionId = id,
                modes = modes,
                autoMemory = autoMemory,
            ) ?: run {
                // 没配 Provider —— 与 init 同样的降级
                initError = "尚未配置 API —— 请到「设置 → 模型」里添加一个 Provider"
                return null
            }

            // ③ 灌历史（AgentLoop 上下文 + UI 气泡）
            val saved = SessionStore(st).load(id)
            if (saved != null && saved.messages.isNotEmpty()) {
                sess.loadHistory(saved.messages)
            }

            session = sess
            // ══════════════════════════════════════════════════════════
            //  【2026-10-06 修 P0】把 container 存下来
            // ══════════════════════════════════════════════════════════
            //
            // 这个属性（声明在文件顶部）设计意图是「装配完成后持有当前
            // AppContainer」，但**从未被赋过非 null 值**（唯一赋值是
            // shutdown() 里的 = null）。后果是三条功能链静默全废：
            //   1. 子 Agent 观察窗：init 里 `if (container != null)` 守卫
            //      永不成立 → attachSubAgents 不执行 → AgentStatus/
            //      AgentOutput/AgentStop 永远报「观察器未接入」
            //   2. /btw 永远失败（container == null 判断恒真）
            //   3. 热更新永远报失败（container?.refreshApi() 短路成 null）
            container = sess?.appContainer
            try { sess.restoreTodos(tools.loadTodos()) } catch (_: Throwable) {}
            initError = null
            sess
        } catch (t: Throwable) {
            initError = "${t::class.java.simpleName}: ${t.message}"
            null
        }
    }

    /**
     * 把老架构（proot 内 Node 内核）的配置迁移到 App 私有目录。
     *
     * ══════════════════════════════════════════════════════════════
     *  为什么需要（这是升级用户最可能踩的坑）
     * ══════════════════════════════════════════════════════════════
     *
     * 老架构的 Node 内核跑在 proot 里、工作目录是 `/root/ccm`，
     * 配置在 **内核自己的工作目录**：
     * ```
     * filesDir/rootfs/root/ccm/config.json
     * ```
     * （Node 版的 `PROJECT_CONFIG_PATH` 锚定 index.mjs 所在目录，不是 homedir）
     *
     * 新架构（Kotlin core）读 App 私有目录：
     * ```
     * filesDir/config.json
     * ```
     *
     * 不迁移的话，**所有升级用户的 Provider 配置全部消失** ——
     * 打开 App 看到「尚未配置 API」，得重新填 key。用户很可能已经忘了。
     *
     * ══════════════════════════════════════════════════════════════
     *  迁移策略
     * ══════════════════════════════════════════════════════════════
     * - **只在目标不存在时迁移**（已有新配置就不覆盖 —— 用户可能已经在
     *   新版本里改过；老配置只是历史遗留）
     * - **迁移后不删源文件** —— 万一新架构有问题，用户还能回退老版本
     *   （APK 覆盖安装不删 filesDir）。占几 KB，不值得冒险删。
     * - **失败静默** —— 迁移是尽力而为的优化，失败不该让 App 起不来。
     *
     * ⚠️ **路径按实测确定**（2026-09-27 用 `run-as` 查过真机）：
     * ```
     * files/config.json                        ← 目标（新架构读这里）
     * files/rootfs/root/ccm/config.json        ← 源（老架构 Node 内核，实测存在）
     * files/rootfs/root/.claude-code-mobile/   ← 备选（Node 版 homedir 约定，实测不存在）
     * ```
     */
    private fun migrateLegacyConfig(app: android.content.Context, st: AppStorage) {
        try {
            // 源按可能性排序。实测老架构在 `rootfs/root/ccm/`（内核工作目录），
            // 但 Node 版的 `~/.claude-code-mobile/` 约定也列上 —— 两者都试成本极低，
            // 而漏掉一个就会让一部分用户的配置读不到。
            val candidates = listOf(
                File(app.filesDir, "rootfs/root/ccm/config.json"),
                File(app.filesDir, "rootfs/root/.claude-code-mobile/config.json"),
            )
            val legacyRoots = listOf(
                File(app.filesDir, "rootfs/root/ccm"),
                File(app.filesDir, "rootfs/root/.claude-code-mobile"),
            )

            // ① 配置（最关键）
            if (!st.configFile.exists()) {
                for (src in candidates) {
                    if (src.isFile && src.length() > 2) {
                        src.copyTo(st.configFile, overwrite = false)
                        migrated = true
                        android.util.Log.i("AppGraph", "已迁移老配置：${src.absolutePath}")
                        break
                    }
                }
            }

            // ② 会话历史
            val sessionsEmpty = st.sessionsDir.list()?.isEmpty() != false
            if (sessionsEmpty) {
                for (root in legacyRoots) {
                    val legacySessions = File(root, "sessions")
                    if (!legacySessions.isDirectory) continue
                    legacySessions.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.endsWith(".json")) {
                            try {
                                f.copyTo(File(st.sessionsDir, f.name), overwrite = false)
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }

            // ③ 项目记忆
            val newMemory = File(st.root, "CLAUDE.md")
            if (!newMemory.exists()) {
                for (root in legacyRoots) {
                    val legacyMemory = File(root, "CLAUDE.md")
                    if (legacyMemory.isFile && legacyMemory.length() > 0) {
                        legacyMemory.copyTo(newMemory, overwrite = false)
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            // 静默 —— 迁移失败只是「用户要重配一次」，不该让 App 起不来
            android.util.Log.w("AppGraph", "老配置迁移失败（不影响启动）：${t.message}")
        }
    }

    /**
     * 热重启会话（切换 Provider / 改配置后用）。
     *
     * 与 [init] 的区别：**保留工具注册表**（重建 99 个工具没必要），
     * 只重建 `ApiClient` + `AgentLoop`。
     *
     * 为什么必须重建而不是改字段：`ApiClient` 持有 key 池（有冷却状态）
     * 和 OkHttp 连接池（可能有坏连接）。改字段会让旧 Provider 的状态
     * 污染新的 —— Node 版踩过「端点 A 的结论连坐到端点 B」。
     */
    /**
     * 重建**工具注册表**（含 Bash 通道）—— 环境模式切换后调。
     *
     * ══════════════════════════════════════════════════════════════
     *  【2026-10-06 加】为什么需要它
     * ══════════════════════════════════════════════════════════════
     *
     * [rebuild] 只重建会话，**工具注册表整个复用**（runner = toolsResult?.executor）
     * —— 于是用户在设置里把环境从 proot 切到「外接 Termux」后，
     * BashTool 手里还是那个 ProotChannel，命令照样往 proot 里跑。
     * 用户现象：「我选了 termux 外接，bash 还是报 proot error」。
     *
     * 通道是在 ToolsBootstrap.install() 时按 cfg.envMode 定的，所以切换
     * 环境必须重跑工具装配。这个方法就是干这个的。
     *
     * 重建后需要重新装配会话（新 executor 要注入 AgentLoop）—— 调用方
     * 接着调 [rebuild] 即可（它会用新的 toolsResult.executor）。
     *
     * @return 是否成功重建（false = 尚未 init 过，调用方应先 init）
     */
    @Synchronized
    fun rebuildTools(context: Context, scope: CoroutineScope): Boolean {
        val st = storage ?: return false
        val app = context.applicationContext
        return try {
            val cfg = AppConfig.load(st.configFile).config
            val provider = cfg.currentProvider

            val settings: ToolSettings? = provider?.let {
                AppContainer.buildSettings(cfg, it)
            }

            val reg = ToolRegistry()
            val tools = ToolsBootstrap(
                context = app,
                storage = AppBackedToolStorage(st),
                settings = settings,
                bridge = NativeBridge(app),
                askUser = { q, opts -> askUserBlocking(q, opts) },
                // 【2026-10-07】改传提供者（每次工具调用现读 github.json）——
                // /github login 设完 token 立即生效，不用重启 App
                githubToken = { readGithubConfig()?.first },
                githubRepo = { readGithubConfig()?.second },
                commandExec = { cmd ->
                    try {
                        val full = if (cmd.startsWith("/")) cmd else "/$cmd"
                        val result = com.ccm.app.ui.chat.handleSlashCommand(
                            full,
                            com.ccm.app.ui.chat.SlashContext(
                                session = session,
                                appContext = app,
                                navigate = {},
                                newChat = {},
                                openPanel = {},
                            ),
                        )
                        when (result) {
                            null -> "命令 `$full` 不被识别（或需要界面操作）。可用：/clear /compact /cost /context /help"
                            is com.ccm.app.ui.chat.SlashResult.Notice -> result.markdown
                            is com.ccm.app.ui.chat.SlashResult.Toast -> result.text
                            is com.ccm.app.ui.chat.SlashResult.Navigate -> "（命令要求跳转到 ${result.route} —— 请在界面上操作）"
                            is com.ccm.app.ui.chat.SlashResult.OpenPanel -> "（命令要求打开面板 ${result.panel} —— 请在界面上操作）"
                            else -> "命令已执行（无输出）"
                        }
                    } catch (t: Throwable) {
                        "命令执行失败：${t.message}"
                    }
                },
                getSessionId = { sessionId },
                modes = modes,
                autoMemory = autoMemory,
            ).install(
                reg,
                // ★ 这里就是「环境模式 → 通道」的唯一定义点 ——
                //   与 init() 里那段必须保持一致（改一处要同步另一处）。
                bashChannel = if (cfg.envMode == "termux") {
                    com.ccm.app.tools.bash.TermuxChannel(app)
                } else null,
            )

            toolsResult = tools
            toolSettings = settings
            registry = reg
            toolNames = tools.registered

            // ══════════════════════════════════════════════════════════
            //  【2026-10-06 修】重新注入子 Agent 观察器
            // ══════════════════════════════════════════════════════════
            //
            // 每次 rebuildTools 都会 new 一套 AgentTools —— 新的实例
            // observer 是 null，于是 AgentStatus/AgentOutput/AgentStop
            // 三个工具全部返回「观察器未接入」。
            //
            // 触发路径很常见：引导页选完环境 → onReady → rebuildTools
            // → 子 Agent 工具从此失效（用户看不出来，只觉得"查不到子 Agent"）。
            //
            // 复用已有 manager（pendingSubAgentManager 是 init 时建的，
            // 它绑着 container 的 runSubAgent，重建工具不影响它）。
            try {
                val mgr = pendingSubAgentManager
                if (mgr != null) {
                    tools.agentTools?.observer = mgr.asToolObserver()
                }
            } catch (_: Throwable) {}
            true
        } catch (t: Throwable) {
            initError = "工具重建失败：${t::class.java.simpleName}: ${t.message}"
            false
        }
    }

    fun rebuild(context: Context, scope: CoroutineScope): ChatSession? {
        val st = storage ?: return init(context, scope)
        val reg = registry ?: return init(context, scope)
        val runner = toolsResult?.executor ?: return init(context, scope)
        val app = context.applicationContext
        // 工作区配置（resolveWorkspaceDir 用）
        val cfg = AppConfig.load(st.configFile).config

        return try {
            val previousSession = session
            val previousHistory = previousSession?.historySnapshot().orEmpty()
            val previousTitle = container?.sessionAuto?.title
            try { previousSession?.flush() } catch (_: Throwable) {}
            // 【2026-10-06 修 P0】用 dispose() 替代 stop() ——
            // stop() 只做 agentLoop.abort() + runningJob.cancel()，**不碰
            // SessionAuto**（那个 appScope 里的 while 循环会永远跑下去，
            // 还会跟新 session 抢同一个落盘文件）。
            // dispose() 内部调 container.shutdown()（含 SessionAuto 停止）。
            // 原来指望下面那行 container?.shutdown() 兜底，但它当时恒为
            // null（见顶部 P0），是 no-op —— 于是每次 rebuild 泄漏一个
            // 协程 + 整个旧会话对象图。
            try { previousSession?.dispose() } catch (_: Throwable) {}
            try { container?.shutdown() } catch (_: Throwable) {}

            // ★ 2026-10-01：优先用配置的 workspacePath（与 CLI /workspace 同语义），
            //   空/不可写时落回默认 files/workspace。
            val cwd = resolveWorkspaceDir(st, cfg)
            val sess = ChatSession.create(
                storage = st,
                registry = reg,
                toolRunner = runner,
                scope = scope,
                context = app,
                imageScaler = AndroidImageScaler(app.cacheDir),
                cwd = cwd,
                sessionId = sessionId.ifBlank { SessionStore(st).newSessionId() },
                modes = modes,
                autoMemory = autoMemory,
            )
            if (sess != null && previousHistory.isNotEmpty()) {
                sess.loadHistory(previousHistory)
                previousTitle?.let(sess::setTitle)
            }
            if (sess != null) {
                try { sess.restoreTodos(toolsResult?.loadTodos?.invoke().orEmpty()) } catch (_: Throwable) {}
            }
            session = sess
            // ══════════════════════════════════════════════════════════
            //  【2026-10-06 修 P0】把 container 存下来
            // ══════════════════════════════════════════════════════════
            //
            // 这个属性（声明在文件顶部）设计意图是「装配完成后持有当前
            // AppContainer」，但**从未被赋过非 null 值**（唯一赋值是
            // shutdown() 里的 = null）。后果是三条功能链静默全废：
            //   1. 子 Agent 观察窗：init 里 `if (container != null)` 守卫
            //      永不成立 → attachSubAgents 不执行 → AgentStatus/
            //      AgentOutput/AgentStop 永远报「观察器未接入」
            //   2. /btw 永远失败（container == null 判断恒真）
            //   3. 热更新永远报失败（container?.refreshApi() 短路成 null）
            container = sess?.appContainer
            initError = if (sess == null) "尚未配置 API —— 请到「设置 → 模型」里添加一个 Provider" else null
            sess
        } catch (t: Throwable) {
            initError = "${t::class.java.simpleName}: ${t.message}"
            null
        }
    }

    /** 释放资源（进程退出前调；一般不用手动调）。 */
    @Synchronized
    fun shutdown() {
        try { session?.stop() } catch (_: Throwable) {}
        try { container?.shutdown() } catch (_: Throwable) {}
        session = null
        container = null
        initialized = false
    }
}
