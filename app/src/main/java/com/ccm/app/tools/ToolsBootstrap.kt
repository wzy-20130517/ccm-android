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
import com.ccm.app.tools.net.GitHubTools
import com.ccm.app.tools.net.ImageTools
import com.ccm.app.tools.net.LookupTools
import com.ccm.app.tools.net.PresentTools
import com.ccm.app.tools.net.VisionTools
import com.ccm.app.tools.net.WebTools
import com.ccm.app.tools.phone.PhoneTools
import com.ccm.app.tools.system.CronStore
import com.ccm.app.tools.system.CronTools
import com.ccm.app.tools.system.QqTools
import com.ccm.app.tools.system.SystemTools
import com.ccm.app.tools.task.AgentTools
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
    /**
     * GitHub PAT 提供者（GitHub* 工具用）—— /github login 设置。
     *
     * 【2026-10-07 改提供者】原为构造快照，设完 token 要重启 App；
     * 现在每次工具调用现取，**立即生效**。
     */
    private val githubToken: () -> String? = { null },
    /** 默认 GitHub 仓库提供者（owner/name）—— /github repo 设置。 */
    private val githubRepo: () -> String? = { null },
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
        /** 上下文文件追踪（2026-10-06 加，/files 的数据源）。 */
        val contextFiles: com.ccm.app.core.session.ContextFiles? = null,
        val trashStore: TrashStore,
        val undoStore: UndoStore,
        /** 读 todos.json（audit-core #3：写路径齐全、恢复没人接 → 重启待办清空）。 */
        val loadTodos: () -> List<Triple<String, String, String>> = { emptyList() },
        /** cron 调度器（#8：AppGraph 起心跳调 schedulerTick）。 */
        val cron: com.ccm.app.tools.system.CronTools? = null,
        val bashChannel: com.ccm.app.tools.bash.BashChannel,
        /**
         * Agent 工具组（问题40）。
         *
         * 【为什么暴露】SubAgentManager 需要 AppContainer（在 ToolsBootstrap
         * **之后**才装配）→ observer 只能事后注入。暴露 agentTools 让 AppGraph
         * 能在拿到 manager 后设 `agentTools.observer = mgr.asToolObserver()`。
         */
        val agentTools: com.ccm.app.tools.task.AgentTools? = null,
        /**
         * 目标存储（问题40：/goal 命令用）。
         *
         * 【为什么暴露】GoalStore 在本类创建（213 行），但 /goal 命令
         * 在 SlashCommandHandler —— 需要经 AppGraph 传过去。
         * 且 ChatSession.runGoal() 也要它（goal 循环的跨轮驱动）。
         */
        val goalStore: com.ccm.app.tools.task.GoalStore? = null,
        /** Skill 工具（问题40：/skills 命令用 —— 动态列目录里的 skill）。 */
        val skillTools: com.ccm.app.tools.task.SkillTools? = null,
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

        // 【2026-10-06 问题40】注入 CommandRunner（外部 hook 用）——
        // ⚠️ 必须在 prootChannel 创建**之后**（之前会前向引用编译失败）。
        try {
            hooks.setCommandRunner(object : ToolHooks.CommandRunner {
                override suspend fun run(
                    command: String,
                    env: Map<String, String>,
                    timeoutMs: Long,
                ): String? {
                    return try {
                        val r = prootChannel.execute(
                            command = command,
                            workDir = null,
                            timeoutMs = timeoutMs,
                            onLine = {},
                        )
                        r.stdout
                    } catch (_: Throwable) { null }
                }
            })
        } catch (_: Throwable) {}
        val termuxChannel = TermuxChannel(context)
        // 默认用 proot；若用户选了 Termux，主通道换过来、proot 作兜底
        val primary: com.ccm.app.tools.bash.BashChannel = bashChannel ?: prootChannel
        val fallback: com.ccm.app.tools.bash.BashChannel? =
            if (primary === prootChannel) termuxChannel else prootChannel

        // ── 工具实例 ──────────────────────────────────────────────
        val fileTools = FileTools(trashStore, undoStore)
        val searchTools = SearchTools()
        // 始终创建（见下方注册处注释：WebSearch 没 key 时内部报明确错误，
        // 不该让工具从清单里静默消失）
        val webTools = WebTools(settings)
        // ImageTools 始终创建 —— 内部工具各自判断 key 配置
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
        // Skill：项目 skills/（按运行时 cwd）优先，用户级兜底
        // 【2026-10-06 问题40】首次启动时把**内置 skill**（assets/skills/）
        // 解压到 files/skills/ —— 项目自带的 5 个 skill 装完就能用。
        //
        // 为什么要解压而不是直接读 assets：
        //   SkillTools 按**文件路径**查找（File API），assets 里的东西
        //   不是真实文件（要 AssetManager 读）—— 改 SkillTools 支持 assets
        //   会让它的查找逻辑复杂化（两套路径体系）。解压一次更简单。
        //
        // 只做一次：用标记文件（files/skills/.unpacked）判断。
        try {
            val skillsDir = File(context.filesDir, "skills")
            val marker = File(skillsDir, ".unpacked")
            if (!marker.exists()) {
                skillsDir.mkdirs()
                val am = context.assets
                val builtin = am.list("skills") ?: emptyArray()
                for (name in builtin) {
                    val files = am.list("skills/$name") ?: continue
                    val target = File(skillsDir, name).apply { mkdirs() }
                    for (f in files) {
                        try {
                            am.open("skills/$name/$f").use { input ->
                                File(target, f).outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
                marker.writeText("1")
            }
        } catch (_: Throwable) {}

        val skillTools = SkillTools(
            globalDir = File(context.filesDir, "skills"),
        )
        subAgentManager?.let { agentTools.observer = it.asToolObserver() }
        val miscTools = MiscTools(
            storageRoot = storage.rootDir,
            memoryFile = File(storage.rootDir, "CLAUDE.md"),
            todoFile = { File(storage.rootDir, "todos/${getSessionId()}.json") },
            onMemoryWritten = {
                autoMemory?.markMainWroteMemory()
                // 【2026-10-06 P1-4】写完记忆让系统提示词缓存失效 ——
                // 下次调模型时重算，新写的 CLAUDE.md 内容立即进提示词。
                // 不失效的话：写了记忆，模型本会话内看不到（白写）。
                try { com.ccm.app.core.AppContainer.invalidateSystemPrompt() } catch (_: Throwable) {}
            },
        )

        // 模式工具（EnterPlanMode/ExitPlanMode/EnterDeepMode/ExitDeepMode/EnterWatch/ExitWatch）
        // —— 与 AgentLoop 共享同一个 modes 实例（见构造参数注释）
        val modeTools = ModeTools(modes)

        // 批 3 补全 + P1
        val devTools = DevTools(primary, trashStore, commandExec)
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

            // 批 3：网络
            //
            // 【2026-10-06 修】原来 `webTools?.let { ... }` —— settings 为 null
            // （没配 Provider）时 WebSearch/WebFetch **整个从工具清单消失**。
            // 用户现象：「函数清单内没有 websearch」—— 模型连它能做什么都不知道，
            // 自然也不会告诉用户「key 没配」。
            //
            // 正确做法：**始终注册**（WebFetch 根本不需要 key；WebSearch 没 key 时
            // 内部会返回明确的「Tavily API key 未配置，用 /tvly 设置」错误）。
            // 工具在清单里 → 模型能调 → 用户能看到真实原因，比静默消失强得多。
            //
            // 真正的「没配就别注册」只适用于**完全无法降级**的工具
            // （FindImage/ImageGen：没 key 时没有任何有意义的行为）。
            val wt = webTools ?: WebTools(null)
            add(wt.WebSearchTool())
            add(wt.WebFetchTool())
            // 需要 key 的两个：没配就不注册 —— 免得模型调了才发现没 key
            if (settings != null) {
                add(imageTools.FindImageTool())
                add(imageTools.ImageGenTool())
            }
            // SearchInfo/Lookup 不依赖任何 key（直连公开搜索源）
            add(lookupTools.SearchInfoTool())
            add(lookupTools.LookupTool())

            // DSH 插件管理（对齐 CLI DshPlugin；宿主自愈见 DshHostManager）
            add(com.ccm.app.tools.plugin.DshPluginTools(context).DshPluginTool())

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

            // P2：Skill / Present
            add(skillTools.SkillTool())
            add(presentTools.PresentTool())

            // P2：GitHub（未配 token 时工具会提示怎么配）
            add(ghTools.GitHubRepoTool())
            add(ghTools.GitHubIssuesTool())
            add(ghTools.GitHubIssueViewTool())
            add(ghTools.GitHubPRsTool())
            add(ghTools.GitHubPRCommentsTool())
            add(ghTools.GitHubFileTool())
            add(ghTools.GitHubCommentTool())
            add(ghTools.GitHubCreateIssueTool())

            // ── 【2026-10-06 问题40】MCP 工具（动态注册）─────────────
            //
            // MCP server 的工具是**运行时发现的**（连上后 listTools），
            // 不能在编译期列出来。所以这里同步读配置 + 注册**通用入口工具**，
            // 真正的工具列表由 McpGenericTool 在首次调用时懒加载。
            //
            // 配置：files/mcp.json（对齐 CLI 的 ~/.claude-code-mobile/mcp.json）
            //
            // 【2026-10-07】原来只注册 `url != null`（HTTP）的 —— stdio 的
            // 配置（mail-qq 这类 node server）连工具入口都看不到。现在
            // 用 ServerConfig.runnable 判据，两类都注册；stdio 的启动
            // 由 McpManager 经 McpLaunch 包 proot 完成（node 在 rootfs 里）。
            try {
                val mcpFile = File(storage.rootDir, "mcp.json")
                if (mcpFile.exists()) {
                    val mgr = com.ccm.app.core.mcp.McpManager(
                        configFile = mcpFile,
                        runtime = com.ccm.app.runtime.ProotRuntime(context),
                    )
                    mgr.loadServers()
                        .filter { !it.disabled && it.runnable }
                        .forEach { cfg ->
                            add(com.ccm.app.tools.mcp.McpGenericTool(mgr, cfg.name))
                        }
                }
            } catch (_: Throwable) {}
        }

        val rejected = registry.registerAll(*all.toTypedArray())

        // 【2026-10-06 对齐 CLI】上下文文件追踪 —— /files 的数据源
        // （记录本会话读/写过哪些文件，官方 readFileState 的等价物）。
        val contextFiles = com.ccm.app.core.session.ContextFiles(cwd = defaultCwd.absolutePath)
        val executor = ToolExecutor(permissions, hooks, outputStore, contextFiles = contextFiles)

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
            // 【2026-10-06 问题40】暴露给 AppGraph 事后注入 observer
            agentTools = agentTools,
            goalStore = goalStore,
            skillTools = skillTools,
            contextFiles = contextFiles,
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
            "Location", "OpenUrl", "Share", "TTS",
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
