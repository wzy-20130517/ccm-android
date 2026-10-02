package com.ccm.app.tools

import android.content.Context
import com.ccm.app.bridge.NativeBridge
import com.ccm.app.core.AppStorage
import com.ccm.app.core.tool.AppBackedToolStorage
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolRegistry
import com.ccm.app.core.tool.ToolSettings
import com.ccm.app.core.tool.ToolStorage
import com.ccm.app.runtime.ProotRuntime
import com.ccm.app.tools.bash.BashOutputTool
import com.ccm.app.tools.bash.BashTool
import com.ccm.app.tools.bash.GitTools
import com.ccm.app.tools.bash.KillShellTool
import com.ccm.app.tools.bash.ProotChannel
import com.ccm.app.tools.bash.TermuxChannel
import com.ccm.app.tools.file.ApplyPatchTool
import com.ccm.app.tools.file.FileTools
import com.ccm.app.tools.file.HashlineTools
import com.ccm.app.tools.file.SearchTools
import com.ccm.app.tools.file.TrashStore
import com.ccm.app.tools.file.UndoStore
import com.ccm.app.tools.dev.DevTools
import com.ccm.app.tools.dev.LspTools
import com.ccm.app.tools.net.GitHubTools
import com.ccm.app.tools.net.ImageTools
import com.ccm.app.tools.net.LookupTools
import com.ccm.app.tools.net.PresentTools
import com.ccm.app.tools.net.VisionTools
import com.ccm.app.tools.net.WebTools
import com.ccm.app.tools.phone.PhoneTools
import com.ccm.app.tools.phone.SayTool
import com.ccm.app.tools.system.CronStore
import com.ccm.app.tools.system.CronTools
import com.ccm.app.tools.system.QqTools
import com.ccm.app.tools.system.SystemTools
import com.ccm.app.tools.task.AgentTools
import com.ccm.app.tools.task.AgentWorkflowTools
import com.ccm.app.tools.task.GoalStore
import com.ccm.app.tools.task.GoalTools
import com.ccm.app.tools.task.MiscTools
import com.ccm.app.tools.task.ModeTools
import com.ccm.app.tools.task.SkillTools
import com.ccm.app.tools.task.SubAgentRegistry
import com.ccm.app.tools.task.TaskStore
import com.ccm.app.tools.task.TaskTools
import com.ccm.app.tools.task.TeamStore
import com.ccm.app.tools.task.TeamTools
import com.ccm.app.tools.task.asToolObserver
import java.io.File

/**
 * 工具装配 —— **把所有工具注册进 registry 的唯一入口**。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要这个类（它是「接线」而不是「实现」）
 * ══════════════════════════════════════════════════════════════
 *
 * 各个工具类写好了不等于能用 —— 它们需要：
 *   1. 被 `new` 出来（很多要注入依赖：TrashStore / UndoStore / NativeBridge / Context）
 *   2. 注册进 [ToolRegistry]（否则 Agent 循环的 `registry.list` 里没有它们）
 *   3. 拿到正确的工作目录与存储路径
 *
 * 这一步漏掉的话，**所有工具都是死的** —— 编译通过、单元测试通过、
 * 但 Agent 一个工具都调不到（表现为「模型说它要读文件，但没有 Read 工具」）。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 注册顺序的坑（CCM 踩过，这里已避开）
 * ══════════════════════════════════════════════════════════════
 *
 * CCM 上「子 Agent 拿不到 Agent 工具」的根因是：注册表传了**数组快照**，
 * 而 Agent 工具在那之后才注册。Kotlin 侧由 [ToolRegistry.toolProvider]
 * （闭包，每次现取）从设计上堵死了，但**本类仍要注意**：
 * 若将来加 Agent 工具，它需要的是 `toolProvider` 而不是 `registry.list`。
 *
 * ══════════════════════════════════════════════════════════════
 *  分层：哪些工具在哪个批次
 * ══════════════════════════════════════════════════════════════
 *
 * | 批次 | 内容 | 状态 |
 * |---|---|---|
 * | 批 0 | ToolExecutor / ToolPermissions / ToolHooks / ToolOutputStore | ✅ |
 * | 批 1 | 文件工具（Read/Write/Edit/MultiEdit/ApplyPatch/Glob/Grep/CodeSearch） | ✅ |
 * | 批 2 | Bash 双通道 + BashOutput + KillShell | ✅ |
 * | 批 3 | 网络工具（WebSearch/WebFetch/FindImage/ImageGen） | 部分 |
 * | 批 4 | 手机工具（13 个 phone_*） | ✅ |
 * | 系统 | 剪贴板/Toast/通知/震动/电量/定位/打开链接/分享/TTS | ✅ |
 * | 批 5 | Task/Team/Goal/Agent/Memory/TodoWrite/Sleep/say | ✅ |
 */
class ToolsBootstrap(
    private val context: Context,
    private val storage: ToolStorage,
    private val settings: ToolSettings?,
    private val bridge: NativeBridge,
    /** 当前会话 id 的取值函数（Goal 工具用）—— **必须是 getter**，见 GoalTools 注释 */
    private val getSessionId: () -> String = { "default" },
    /**
     * 子 Agent 管理器（AgentStatus/Stop/Output 用）。
     *
     * 直接传 core 层的 [com.ccm.app.core.agent.SubAgentManager]，
     * **适配器在本类内部做**（依赖方向 tools → core，不能倒挂）。
     * 不传时那三个工具会提示「观察器未接入」—— 不崩，但子 Agent 就看不见了。
     */
    private val subAgentManager: com.ccm.app.core.agent.SubAgentManager? = null,
    /** 程序内命令执行器（CommandExec 用）—— App 层注入 */
    private val commandExec: (suspend (String) -> String)? = null,
    /** 用户提问回调（AskUserQuestion 用）—— 子 Agent **不应**注入（会永久阻塞） */
    private val askUser: (suspend (String, List<String>) -> String?)? = null,
    /** QQ 推送器（QQPush 用）—— App 层注入 */
    private val qqPusher: QqTools.Pusher? = null,
    /** QQ 群消息回溯器（QQRecall 用）—— App 层注入 */
    private val qqRecaller: QqTools.Recaller? = null,
    /** GitHub PAT（GitHub* 工具用）—— /github login 设置 */
    private val githubToken: String? = null,
    /** 默认 GitHub 仓库（owner/name）—— /github repo 设置 */
    private val githubRepo: String? = null,
    /**
     * 运行模式状态（deep / plan / watch）—— **必须与 AgentLoop 用同一个实例**。
     *
     * 不传时模式工具改的是一个孤立对象，主循环读不到 ——
     * 表现为「EnterDeepMode 说成功了但轮数没变」。生产环境由 AppGraph 注入。
     */
    private val modes: com.ccm.app.core.agent.ModeState = com.ccm.app.core.agent.ModeState(),
    /**
     * 自动记忆提取器（automem）。
     *
     * 传入时 [Memory] 工具写记忆后会通知它「本轮别重复提取」；
     * `null` = 未启用 automem（工具行为不变，只是少了那个互斥通知）。
     */
    private val autoMemory: com.ccm.app.core.memory.AutoMemory? = null,
) {

    /** 装配结果（供诊断与 UI 展示） */
    data class Result(
        val registered: List<String>,
        val rejected: List<String>,
        val executor: ToolExecutor,
        val permissions: ToolPermissions,
        val hooks: ToolHooks,
        val outputStore: ToolOutputStore,
        val trashStore: TrashStore,
        val undoStore: UndoStore,
        /** 读 todos.json（audit-core #3：写路径齐全、恢复没人接 → 重启待办清空）。 */
        val loadTodos: () -> List<Triple<String, String, String>> = { emptyList() },
        /** cron 调度器（#8：AppGraph 起心跳调 schedulerTick）。 */
        val cron: com.ccm.app.tools.system.CronTools? = null,
        val bashChannel: com.ccm.app.tools.bash.BashChannel,
    )

    /** 默认工作目录（App 私有，无需运行时权限） */
    private val defaultCwd: File
        get() = File(storage.rootDir, "workspace").apply { if (!exists()) mkdirs() }

    /**
     * 装配全部工具。
     *
     * @param registry 工具注册表（由 Agent 层提供，注册完它就能用了）
     * @param bashChannel 用户选择的 Bash 通道（null = 用内置 proot）
     */
    fun install(registry: ToolRegistry, bashChannel: com.ccm.app.tools.bash.BashChannel? = null): Result {
        storage.ensureDirs()

        // ── 基础设施 ──────────────────────────────────────────────
        val trashStore = TrashStore(storage.rootDir)
        val undoStore = UndoStore(storage.undoDir)
        val outputStore = ToolOutputStore(storage.rootDir)
        val permissions = ToolPermissions(storage.rootDir)
        // ★ audit-core #1（2026-09-28）：loadModeFrom 原来零调用 —— mode 永远
        //   锁死 "default"，手改 config.json 的 permissionMode（bypassPermissions/
        //   plan）在 tools 层不生效（AppContainer 那套 AgentLoop 判定读了，但
        //   实际执行裁决的是 ToolExecutor → permissions.resolve 读的锁死值）。
        try {
            val cf = java.io.File(storage.rootDir, "config.json")
            if (cf.exists()) permissions.loadModeFrom(org.json.JSONObject(cf.readText()))
        } catch (_: Throwable) {}
        val hooks = ToolHooks(storage.rootDir)
        hooks.loadFromConfig()

        // ── Bash 通道 ─────────────────────────────────────────────
        val prootChannel = ProotChannel(context, ProotRuntime(context))
        val termuxChannel = TermuxChannel(context)
        // 默认用 proot；若用户选了 Termux，主通道换过来、proot 作兜底
        val primary: com.ccm.app.tools.bash.BashChannel = bashChannel ?: prootChannel
        val fallback: com.ccm.app.tools.bash.BashChannel? =
            if (primary === prootChannel) termuxChannel else prootChannel

        // ── 工具实例 ──────────────────────────────────────────────
        val fileTools = FileTools(trashStore, undoStore)
        val searchTools = SearchTools()
        val webTools = settings?.let { WebTools(it) }
        // ImageTools 始终创建 —— ReverseImage 不需要任何 key，
        // 若跟 FindImage/ImageGen 一起挂在 settings 下面会被误伤（没配 key 就整个消失）
        val imageTools = ImageTools(settings, defaultCwd)
        val lookupTools = LookupTools(storage.rootDir)
        // Present：把可视内容落盘 + 返回路径（APK 端无内联渲染通道，见类注释）
        val presentTools = PresentTools(storage.rootDir)
        val visionTools = VisionTools(context, storage.rootDir)
        val phoneTools = PhoneTools(context, storage.rootDir)
        val systemTools = SystemTools(bridge)

        // 批 5：任务/团队/目标/Agent
        val taskStore = TaskStore(File(storage.rootDir, "tasks"))
        val teamStore = TeamStore(File(storage.rootDir, "teams"))
        val goalStore = GoalStore(File(storage.rootDir, "goals"))
        val taskTools = TaskTools(taskStore)
        // 子 Agent 登记表 —— AgentTools 登记、TeamTools 的 wake 唤醒，**两者必须共享同一个实例**
        val subAgentRegistry = SubAgentRegistry()
        val teamTools = TeamTools(teamStore, taskStore, registry = subAgentRegistry)
        val goalTools = GoalTools(goalStore, getSessionId)
        val agentTools = AgentTools(
            getRegistry = { registry },
            subAgentRegistry = subAgentRegistry,
        )
        // AgentWorkflow：Explore → Plan → Implement → Review 四阶段串行
        val workflowTools = AgentWorkflowTools()
        // Skill：项目 skills/（按运行时 cwd）优先，用户级兜底
        val skillTools = SkillTools(
            globalDir = File(context.filesDir, "skills"),
        )
        subAgentManager?.let { agentTools.observer = it.asToolObserver() }
        val miscTools = MiscTools(
            storageRoot = storage.rootDir,
            memoryFile = File(storage.rootDir, "CLAUDE.md"),
            todoFile = { File(storage.rootDir, "todos/${getSessionId()}.json") },
            onMemoryWritten = { autoMemory?.markMainWroteMemory() },
        )

        // 模式工具（EnterPlanMode/ExitPlanMode/EnterDeepMode/ExitDeepMode/EnterWatch/ExitWatch）
        // —— 与 AgentLoop 共享同一个 modes 实例（见构造参数注释）
        val modeTools = ModeTools(modes)

        // 批 3 补全 + P1
        val devTools = DevTools(primary, trashStore, commandExec)
        // LSP：diagnostic 走 Bash 通道真实执行；hover/definition/completion 降级
        val lspTools = LspTools(primary)
        val hashlineTools = HashlineTools(trashStore, undoStore)
        val cronTools = CronTools(CronStore(File(storage.rootDir, "cron")))
        val qqTools = QqTools(qqPusher, qqRecaller)
        val ghTools = GitHubTools(githubToken, githubRepo)

        val all: List<Tool> = buildList {
            // 批 1：文件
            add(fileTools.ReadTool())
            add(fileTools.WriteTool())
            add(fileTools.EditTool())
            add(fileTools.MultiEditTool())
            add(ApplyPatchTool(trashStore, undoStore))
            add(searchTools.GlobTool())
            add(searchTools.GrepTool())
            add(searchTools.CodeSearchTool())

            // 批 2：Bash
            add(BashTool(primary, fallback))
            add(BashOutputTool())
            add(KillShellTool())

            // Git（复用 Bash 通道 —— git 只装在 rootfs/Termux 里，Android 本体没有）
            val gitTools = GitTools(primary, fallback)
            add(gitTools.GitStatusTool())
            add(gitTools.GitDiffTool())
            add(gitTools.GitLogTool())
            add(gitTools.GitAddTool())
            add(gitTools.GitCommitTool())

            // 批 3：网络（需要 settings，没配就不注册 —— 免得模型调了才发现没 key）
            webTools?.let {
                add(it.WebSearchTool())
                add(it.WebFetchTool())
            }
            // 需要 key 的两个：没配就不注册 —— 免得模型调了才发现没 key
            if (settings != null) {
                add(imageTools.FindImageTool())
                add(imageTools.ImageGenTool())
            }
            // ReverseImage 不依赖任何 key（当前是诚实降级的占位，见类注释）
            add(imageTools.ReverseImageTool())
            // SearchInfo/Lookup 不依赖任何 key（直连公开搜索源）
            add(lookupTools.SearchInfoTool())
            add(lookupTools.LookupTool())

            // 批 4：手机
            add(phoneTools.PhoneSnapshotTool())
            add(phoneTools.PhoneClickTool())
            add(phoneTools.PhoneTapXYTool())
            add(phoneTools.PhoneTypeTool())
            add(phoneTools.PhoneSwipeTool())
            add(phoneTools.PhoneKeyTool())
            add(phoneTools.PhoneScrollTool())
            add(phoneTools.PhoneScreenshotTool())
            add(phoneTools.PhoneWaitTool())
            add(phoneTools.PhoneAppTool())
            add(phoneTools.PhoneShellTool())
            add(phoneTools.PhoneVdTool())
            add(phoneTools.PhoneDeviceTool())
            add(phoneTools.PhoneHandoffTool())

            // 系统能力
            add(systemTools.ClipboardGetTool())
            add(systemTools.ClipboardSetTool())
            add(systemTools.ToastTool())
            add(systemTools.NotifyTool())
            add(systemTools.VibrateTool())
            add(systemTools.BatteryTool())
            add(systemTools.LocationTool())
            add(systemTools.OpenUrlTool())
            add(systemTools.ShareTool())
            add(systemTools.TtsTool())

            // 语音播报（与 TTS 的区别：多一个 secret 模式）
            add(SayTool(context))

            // 批 5：任务
            add(taskTools.TaskCreateTool())
            add(taskTools.TaskListTool())
            add(taskTools.TaskGetTool())
            add(taskTools.TaskClaimTool())
            add(taskTools.TaskUpdateTool())
            add(taskTools.TaskDeleteTool())

            // 批 5：团队
            add(teamTools.TeamCreateTool())
            add(teamTools.TeamJoinTool())
            add(teamTools.TeamLeaveTool())
            add(teamTools.TeamDisbandTool())
            add(teamTools.TeamStatusTool())
            add(teamTools.SendMessageTool())
            add(teamTools.CheckMessagesTool())

            // 批 5：目标（刻意没有 CreateGoal —— 目标只能用户用 /goal 设）
            add(goalTools.GetGoalTool())
            add(goalTools.GoalStatusTool())
            add(goalTools.SetGoalBudgetTool())

            // 批 5：Agent
            add(agentTools.AgentTool())
            add(agentTools.AgentStatusTool())
            add(agentTools.AgentOutputTool())
            add(agentTools.AgentStopTool())
            add(agentTools.AgentMemoryTool(File(storage.rootDir, "agent-memory")))
            add(agentTools.ExtendTurnsTool())

            // 批 5：杂项
            add(miscTools.TodoWriteTool())
            add(miscTools.SleepTool())
            add(miscTools.MemoryTool())
            add(miscTools.UserInputHistoryTool(File(storage.rootDir, "input-history.jsonl")))
            add(miscTools.AskUserQuestionTool(askUser))

            // 批 6：模式（deep / plan / watch）—— 对齐 CLI core/plan.mjs
            modeTools.all().forEach { add(it) }

            // P1：视觉
            add(visionTools.ViewImageTool())
            add(visionTools.ViewVideoTool())
            add(visionTools.ScreencapTool())

            // P1：开发辅助
            add(devTools.TestTool())
            add(devTools.DiagnosticsTool())
            add(devTools.RepoMapTool())
            add(devTools.SymbolsTool())
            add(devTools.SafeRenameTool())
            add(devTools.CommandExecTool())

            // P1：定时任务
            add(cronTools.CronCreateTool())
            add(cronTools.CronListTool())
            add(cronTools.CronDeleteTool())

            // P1：QQ
            add(qqTools.QQPushTool())
            add(qqTools.QQRecallTool())

            // P2：Hashline（行锚点验证编辑）
            add(hashlineTools.HashlineReadTool())
            add(hashlineTools.HashlineEditTool())
            add(hashlineTools.HashlineGrepTool())

            // P2：Skill / AgentWorkflow / Present / LSP
            add(skillTools.SkillTool())
            add(workflowTools.AgentWorkflowTool())
            add(presentTools.PresentTool())
            add(lspTools.LspTool())

            // P2：GitHub（未配 token 时工具会提示怎么配）
            add(ghTools.GitHubRepoTool())
            add(ghTools.GitHubIssuesTool())
            add(ghTools.GitHubIssueViewTool())
            add(ghTools.GitHubPRsTool())
            add(ghTools.GitHubPRCommentsTool())
            add(ghTools.GitHubFileTool())
            add(ghTools.GitHubCommentTool())
            add(ghTools.GitHubCreateIssueTool())
        }

        val rejected = registry.registerAll(*all.toTypedArray())

        val executor = ToolExecutor(permissions, hooks, outputStore)

        return Result(
            registered = all.map { it.name }.filter { it !in rejected },
            rejected = rejected,
            executor = executor,
            permissions = permissions,
            hooks = hooks,
            outputStore = outputStore,
            trashStore = trashStore,
            undoStore = undoStore,
            loadTodos = { miscTools.loadTodos() },
            cron = cronTools,
            bashChannel = primary,
        )
    }

    /** 便捷：从 App 层直接装配（自动桥接 AppStorage） */
    companion object {
        fun installDefault(
            context: Context,
            registry: ToolRegistry,
            appStorage: AppStorage,
            settings: ToolSettings?,
            bridge: NativeBridge,
            bashChannel: com.ccm.app.tools.bash.BashChannel? = null,
        ): Result = ToolsBootstrap(
            context = context,
            storage = AppBackedToolStorage(appStorage),
            settings = settings,
            bridge = bridge,
        ).install(registry, bashChannel)

        /**
         * 工具清单（供提示词列举与 UI 展示，不实例化）。
         *
         * ⚠️ 与 [install] 必须同步 —— 这里漏了某个工具，提示词里就不会告诉模型，
         * 模型永远想不起来用它。两处都改。
         */
        val TOOL_NAMES: List<String> = listOf(
            // 文件
            "Read", "Write", "Edit", "MultiEdit", "ApplyPatch", "Glob", "Grep", "CodeSearch",
            // Bash
            "Bash", "BashOutput", "KillShell",
            // 网络
            "WebSearch", "WebFetch", "FindImage", "ImageGen", "SearchInfo", "Lookup",
            // 手机
            "phone_snapshot", "phone_click", "phone_tap_xy", "phone_type", "phone_swipe",
            "phone_key", "phone_scroll", "phone_screenshot", "phone_wait", "phone_app",
            "phone_shell", "phone_vd", "phone_device",
            // 系统
            "ClipboardGet", "ClipboardSet", "Toast", "Notify", "Vibrate", "Battery",
            "Location", "OpenUrl", "Share", "TTS", "say",
            // 任务
            "TaskCreate", "TaskList", "TaskGet", "TaskClaim", "TaskUpdate", "TaskDelete",
            // 团队
            "TeamCreate", "TeamJoin", "TeamLeave", "TeamDisband", "TeamStatus",
            "SendMessage", "CheckMessages",
            // 目标
            "GetGoal", "GoalStatus", "SetGoalBudget",
            // Agent
            "Agent", "AgentStatus", "AgentOutput", "AgentStop", "AgentMemory", "ExtendTurns",
            // 杂项
            "TodoWrite", "Sleep", "Memory", "UserInputHistory", "AskUserQuestion",
            // 模式（deep / plan / watch）—— 对齐 CLI core/plan.mjs
            "EnterPlanMode", "ExitPlanMode", "EnterDeepMode", "ExitDeepMode",
            "EnterWatch", "ExitWatch",
            // 视觉
            "ViewImage", "ViewVideo", "Screencap",
            // 开发辅助
            "Test", "Diagnostics", "RepoMap", "Symbols", "SafeRename", "CommandExec",
            // 定时任务
            "CronCreate", "CronList", "CronDelete",
            // QQ
            "QQPush", "QQRecall",
            // Hashline
            "HashlineRead", "HashlineEdit", "HashlineGrep",
            // GitHub
            "GitHubRepo", "GitHubIssues", "GitHubIssueView", "GitHubPRs",
            "GitHubPRComments", "GitHubFile", "GitHubComment", "GitHubCreateIssue",
        )
    }
}
