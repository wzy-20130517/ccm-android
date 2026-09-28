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
import com.ccm.app.core.tool.ToolSettings
import com.ccm.app.core.AppContainer
import com.ccm.app.runtime.ProotRuntime
import com.ccm.app.tools.AndroidImageScaler
import com.ccm.app.tools.ToolsBootstrap
import kotlinx.coroutines.CoroutineScope
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

    /** 核心容器（含 AgentLoop / ApiClient / SessionStore）。 */
    @Volatile
    var container: AppContainer? = null
        private set

    /** 对话门面 —— **UI 接入 Agent 的唯一入口**。 */
    @Volatile
    var session: ChatSession? = null
        private set

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

            // ── 3. 配置 ──────────────────────────────────────────────
            val cfg = AppConfig.load(st.configFile).config
            val provider = cfg.currentProvider

            // 工具侧只读快照。没 Provider 时传 null —— 依赖 settings 的工具
            // （WebSearch / FindImage / ImageGen）会**不注册**，而不是注册了
            // 再报「没 key」。这是 ToolsBootstrap 的既定设计。
            val settings: ToolSettings? = provider?.let {
                AppContainer.buildSettings(cfg, it)
            }

            // ── 4. 工具注册（★ 这一步以前从没被调用过）──────────────
            val reg = ToolRegistry()
            val tools = ToolsBootstrap(
                context = app,
                storage = AppBackedToolStorage(st),
                settings = settings,
                bridge = NativeBridge(app, ProotRuntime(app)),
                // ★ 必须传 getter（不是字符串快照）—— GoalTools 在
                //   每次调用时现取，这样 [rebuild] 换了会话 id 它也能跟上。
                getSessionId = { sessionId },
            ).install(reg)

            toolsResult = tools
            registry = reg
            toolNames = tools.registered

            // ── 5. 会话（内部装配 ApiClient + AgentLoop）─────────────
            val cwd = File(st.root, WORKSPACE_DIR).apply { mkdirs() }.absolutePath
            val sess = ChatSession.create(
                storage = st,
                registry = reg,
                toolRunner = tools.executor,
                scope = scope,
                imageScaler = imageScaler,
                cwd = cwd,
                sessionId = sid,
            )

            if (sess == null) {
                // 工具已注册（上面成功），只是没有可用 Provider。
                // 这是**正常状态**（新装用户还没配 API），不是错误。
                initError = "尚未配置 API —— 请到「设置 → 模型」里添加一个 Provider"
                return null
            }

            session = sess
            initError = null
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
        if (id.isBlank()) return null
        if (id == sessionId && session != null) return session   // 已经在这场对话里

        return try {
            // ① 释放旧会话（flush + 停自动保存 + 断连接）
            session?.dispose()

            // ② 重建
            val cwd = File(st.root, WORKSPACE_DIR).apply { mkdirs() }.absolutePath
            val sess = ChatSession.create(
                storage = st,
                registry = reg,
                toolRunner = tools.executor,
                scope = scope,
                imageScaler = imageScaler,
                cwd = cwd,
                sessionId = id,
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

            sessionId = id
            session = sess
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
    @Synchronized
    fun rebuild(context: Context, scope: CoroutineScope): ChatSession? {
        val st = storage ?: return init(context, scope)
        val reg = registry ?: return init(context, scope)
        val runner = toolsResult?.executor ?: return init(context, scope)
        val app = context.applicationContext

        return try {
            try { session?.stop() } catch (_: Throwable) {}
            try { container?.shutdown() } catch (_: Throwable) {}

            val cwd = File(st.root, WORKSPACE_DIR).apply { mkdirs() }.absolutePath
            val sess = ChatSession.create(
                storage = st,
                registry = reg,
                toolRunner = runner,
                scope = scope,
                imageScaler = AndroidImageScaler(app.cacheDir),
                cwd = cwd,
                sessionId = sessionId.ifBlank { SessionStore(st).newSessionId() },
            )
            session = sess
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
