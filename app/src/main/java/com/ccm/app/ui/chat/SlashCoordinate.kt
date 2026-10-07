package com.ccm.app.ui.chat

/**
 * `/coordinate`（别名 `/cowork`）—— 协调者模式（2026-10-07 对齐 CLI `CoordinatorMode`）。
 *
 * ## 语义（对齐 CLI `index.mjs` 的 case 'coordinate'）
 *
 * | 输入 | 行为 |
 * |---|---|
 * | `/coordinate` | 切换开/关 |
 * | `/coordinate on\|off` | 显式设置 |
 * | `/coordinate help` | 帮助 |
 * | `/coordinate <任务>` | **只开不关**：开启并把任务作为首轮指令执行 |
 *
 * ## APK 与 CLI 的实现差异（为什么是「历史注入」而不是系统提示词段）
 *
 * CLI 的 CoordinatorMode 通过 `getSystemPromptAddition()` 把纪律段拼进系统提示词。
 * APK 的 `AgentLoop.effectiveSystemPrompt()` 只读 `modes.planPromptAddition()`，
 * 而 AgentLoop 不在本次改动范围 —— 所以纪律改为**注入一条用户消息进历史**：
 * 模型每轮都能看到（效果接近系统提示词），代价是占少量上下文、且随会话落盘。
 * 关闭模式时把这条消息从历史里摘掉（行为对齐 CLI 的「段消失」）。
 *
 * ## 状态是进程级
 *
 * 对齐 CLI（`coordinatorMode` 是 index.mjs 顶层单例）：重启即重置。
 * 「历史里是否已注入纪律」按会话判断（`historySnapshot` 现查），防重复注入。
 *
 * ## 与 Coordinator **子 Agent 类型**别混
 *
 * - `Coordinator` 子 agent 类型 → 被 spawn 出去的某个 worker 是协调者
 * - 本模式 → **主对话本人**变成协调者（CLI 官方语义）
 * 两者可同时存在（协调者主对话再去 spawn Coordinator 子 agent 也合法）。
 */
internal object SlashCoordinate {

    /** 纪律段在历史里的识别标记（用于防重复注入 / 关闭时摘除）。 */
    private const val MARK = "【协调者模式 · 纪律】"

    /**
     * 是否已开启（进程级，对齐 CLI 单例语义）。
     *
     * `@Volatile`：命令在主线程写，读方可能在协程 —— 与 ModeState 同款理由。
     */
    @Volatile
    var enabled: Boolean = false
        private set

    /**
     * 纪律文本 —— 照 CLI `CoordinatorMode.getSystemPromptAddition()` 的骨架，
     * 精简为 APK 工具集的说法（Agent / SendMessage / AgentStop / TeamCreate 全有）。
     */
    private val DISCIPLINE: String = """
        $MARK（本消息由 /coordinate 注入，长期有效）

        你的角色变了：你是**协调者**，不是执行者。

        - **自己不写代码、不改文件、不跑长命令。** 想动手时，改成 spawn 一个 worker 去做。
        - 你只做四件事：**拆解 → 派活 → 读结果 → 汇总**。
        - 允许的例外：为了拆任务而做的少量只读侦察（看目录、确认文件存在），
          但不要发展成「我自己顺手把活干完」——那就失去了编排的意义。

        工具：
        - 用 Agent 工具 spawn worker（给每个起好 agent_name）
        - 用 SendMessage(to:<名字>, wake:true) 让跑完的 worker 带着原上下文继续干
          （要返工找原 worker，不要重新 spawn，它记得自己做过什么）
        - 派错方向用 AgentStop 中止，别干等它烧 token
        - 并行按「文件冲突」分组，不是按个数：只读任务放开并行；写同一批文件的
          任务必须串行（否则静默覆盖，最难查）

        worker 的结果是内部信号，不是对话对象 —— 不要对它们说「谢谢」「收到」。
        有新进展就替用户总结出来。worker 的结论要抽查，不要照抄。
    """.trimIndent()

    /** 入口：解析子命令并分发。 */
    fun dispatch(ctx: SlashContext, arg: String): SlashResult {
        val parts = arg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val head = parts.firstOrNull()?.lowercase()

        return when (head) {
            "help" -> help()

            "on", "开", "开启" -> setMode(ctx, on = true, task = parts.drop(1))

            "off", "关", "关闭" -> setMode(ctx, on = false, task = emptyList())

            // 无参 = toggle（对齐 CLI）；带任务 = 只开不关 + 执行首轮
            null -> setMode(ctx, on = !enabled, task = emptyList())

            else -> setMode(ctx, on = true, task = parts)
        }
    }

    private fun help() = SlashResult.Notice(
        "**协调者模式（Coordinator Mode）—— 多 Agent 编排**\n\n" +
            "- `/coordinate` — 切换开/关（当前：${if (enabled) "已开启" else "已关闭"}）\n" +
            "- `/coordinate on|off` — 显式设置\n" +
            "- `/coordinate <任务>` — 开启并把任务作为首轮指令执行\n\n" +
            "开启后主对话只做编排：拆解任务 → 派 worker 并行执行 → 抽查结果 → 汇总。\n" +
            "实际执行由子 Agent 完成（Agent / SendMessage / AgentStop / TeamCreate 工具）。\n" +
            "消耗会比单人干活多，适合「多个独立子任务可以并行」的场景；\n" +
            "单条任务自己干更快，别开。",
    )

    private fun setMode(ctx: SlashContext, on: Boolean, task: List<String>): SlashResult {
        val session = ctx.session

        if (!on) {
            enabled = false
            // 摘掉历史里的纪律消息（对齐 CLI「关闭后段消失」）——
            // 只删带 MARK 的那条，不碰其他消息。
            val removed = session?.let { s ->
                try {
                    val h = s.historySnapshot()
                    if (h.any { it.text.contains(MARK) }) {
                        s.appContainer.agentLoop.setHistory(h.filter { !it.text.contains(MARK) })
                        true
                    } else false
                } catch (_: Throwable) { false }
            } ?: false
            return SlashResult.Notice(
                "**协调者模式已关闭**：恢复正常工作方式（自己动手执行）。" +
                    if (removed) "\n\n_已从历史中移除纪律段。_" else "",
            )
        }

        enabled = true
        val taskText = task.joinToString(" ").trim()

        // 注入纪律（按会话判断，防重复）
        var injectedNow = false
        if (session != null) {
            try {
                val h = session.historySnapshot()
                if (!h.any { it.text.contains(MARK) }) {
                    if (session.isRunning) {
                        // 运行中直接改历史有并发风险（AgentLoop 正读 messages
                        // 构建请求）→ 改走 steering 队列，下一轮注入（安全）。
                        session.appContainer.agentLoop.pushSteering(DISCIPLINE)
                    } else {
                        // hidden = true：进 API 请求历史（模型看得到），但不进 UI
                        // 气泡（恢复会话时不显示，避免系统消息冒充用户发言）。
                        val discipline = com.ccm.app.core.session.Message.user(
                            DISCIPLINE, hidden = true,
                        )
                        session.appContainer.agentLoop.setHistory(h + discipline)
                    }
                    injectedNow = true
                }
            } catch (_: Throwable) { /* 注入失败不阻断开模式 */ }
        }

        // 带任务：作为首轮指令发出（只开不关，对齐 CLI）
        if (taskText.isNotEmpty()) {
            if (session == null) {
                return SlashResult.Notice(
                    "**协调者模式已开启**，但没有活动会话，任务未发出。\n\n" +
                        "进入对话页后重新发送任务即可。",
                )
            }
            session.send(taskText)
            return SlashResult.Notice(
                "**协调者模式已开启**：任务已发出 ——\n\n$taskText\n\n" +
                    "_我只做编排（拆解 → 派 worker → 汇总），不亲自改代码。_",
            )
        }

        return SlashResult.Notice(
            "**协调者模式已开启**：我只做编排（拆解 → 派 worker → 汇总），不亲自改代码。\n\n" +
                (if (injectedNow) "_纪律段已注入当前会话历史。_\n\n" else "") +
                "接下来发的任务会按这个模式执行；再次 `/coordinate` 关闭。",
        )
    }
}
