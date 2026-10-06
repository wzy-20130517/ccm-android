package com.ccm.app.core.tool

/**
 * 工具超时分级 —— 移植自 CLI `core/infra/tool-timeout.mjs`。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题29/40 新建】
 *
 * 用户要求「提示词复用 CLI，但不要写死值 —— 用运行时真值」。
 *
 * CLI 的提示词里有 `{{TOOL_TIMEOUT_TIERS}}` 占位符，由
 * `describeTimeoutTiers()` **从这份表算出来** —— 改表就自动改提示词，
 * 不会出现「文档说 30s、实际 60s」的漂移。
 *
 * APK 原来只有一个 `defaultTimeoutMs = 600_000`（所有工具一样），
 * 既没有分级，也没法给提示词提供真值。本文件补上。
 * ═══════════════════════════════════════════════════════════════
 *
 * ## 分级（与 CLI 完全一致）
 * | 档 | 值 | 适用 |
 * |---|---|---|
 * | FAST | 15s | 只读检索（Read/Grep/Glob…）|
 * | NORMAL | 60s | 本地写入（Write/Edit…）|
 * | NETWORK | 120s | 网络类（WebSearch/WebFetch…）|
 * | SLOW_GEN | 300s | 生图·视频 |
 * | LONG | 600s | Bash/Test/Agent/CommandExec |
 * | INTERACTIVE | 2min | 等用户回答 |
 * | WAIT | 310s | Sleep（必须覆盖它自己声明的 300s 上限）|
 *
 * 硬上限 30min。
 */
object ToolTimeouts {

    const val FAST = 15_000L
    const val NORMAL = 60_000L
    const val NETWORK = 120_000L
    const val SLOW_GEN = 300_000L
    const val LONG = 600_000L
    const val WAIT = 310_000L
    const val INTERACTIVE = 120_000L

    /** 硬上限（任何显式 timeout 都不能超过它）。 */
    const val MAX_TOOL_TIMEOUT = 1_800_000L

    /** 通用默认（没在表里的工具用这个）。 */
    const val DEFAULT_TIMEOUT = NORMAL

    /** 工具名 → 超时（毫秒）。与 CLI 表逐项对齐。 */
    private val TABLE: Map<String, Long> = mapOf(
        // ── 只读检索（FAST）──
        "Read" to FAST, "Glob" to FAST, "Grep" to FAST, "CodeSearch" to FAST,
        "HashlineRead" to FAST, "HashlineGrep" to FAST,
        "Symbols" to FAST, "RepoMap" to FAST, "Diagnostics" to FAST,
        "GitStatus" to FAST, "GitDiff" to FAST, "GitLog" to FAST,
        "ClipboardGet" to FAST, "Battery" to FAST, "QQInbox" to FAST,

        // ── 本地写入（NORMAL）──
        "Write" to NORMAL, "Edit" to NORMAL, "MultiEdit" to NORMAL,
        "HashlineEdit" to NORMAL, "ApplyPatch" to NORMAL, "SafeRename" to NORMAL,
        "GitAdd" to NORMAL, "GitCommit" to NORMAL,
        "Memory" to NORMAL, "TodoWrite" to NORMAL,
        "ViewImage" to NORMAL, "Screencap" to NORMAL,

        // ── 网络（NETWORK）──
        "WebFetch" to NETWORK, "WebSearch" to NETWORK, "LSP" to NETWORK,
        "SearchInfo" to NETWORK, "Lookup" to NETWORK, "FindImage" to NETWORK,

        // ── 长任务（LONG）──
        "Bash" to LONG, "Test" to LONG, "Agent" to LONG, "AgentWorkflow" to LONG,
        "CommandExec" to LONG,

        // ── 生成类（SLOW_GEN）──
        "ImageGen" to SLOW_GEN, "ViewVideo" to SLOW_GEN,

        // ── 交互等待 ──
        "AskUserQuestion" to INTERACTIVE,

        // ── 后台任务观察（快）──
        "BashOutput" to FAST, "KillShell" to FAST, "AgentStatus" to FAST,
        "UserInputHistory" to FAST,

        // ── 主动等待 ──
        "Sleep" to WAIT,

        // ── 模式切换（瞬时）──
        "EnterPlanMode" to FAST, "ExitPlanMode" to FAST,
        "EnterDeepMode" to FAST, "ExitDeepMode" to FAST,
        "EnterWatch" to FAST, "ExitWatch" to FAST,
        "Skill" to FAST, "Present" to FAST,

        // ── Android 装饰类（NORMAL）──
        "Toast" to NORMAL, "Notify" to NORMAL, "Vibrate" to NORMAL, "TTS" to NORMAL,
        "ClipboardSet" to NORMAL, "Share" to NORMAL, "OpenUrl" to NORMAL,
        "Location" to NORMAL,

        // ── 手机操作 ──
        "phone_snapshot" to NORMAL, "phone_click" to NORMAL, "phone_tap_xy" to NORMAL,
        "phone_type" to NORMAL, "phone_swipe" to NORMAL, "phone_key" to NORMAL,
        "phone_scroll" to NORMAL, "phone_screenshot" to NORMAL, "phone_wait" to NORMAL,
        "phone_app" to LONG, "phone_shell" to LONG, "phone_vd" to LONG,
        "phone_device" to FAST,

        // ── 任务/团队/目标 ──
        "TaskCreate" to FAST, "TaskList" to FAST, "TaskGet" to FAST,
        "TaskClaim" to FAST, "TaskUpdate" to FAST, "TaskDelete" to FAST,
        "TeamCreate" to FAST, "TeamJoin" to FAST, "TeamLeave" to FAST,
        "TeamDisband" to FAST, "TeamStatus" to FAST,
        "SendMessage" to FAST, "CheckMessages" to FAST,
        "GetGoal" to FAST, "GoalStatus" to FAST, "SetGoalBudget" to FAST,

        // ── 子 Agent 观察 ──
        "AgentStatus" to FAST, "AgentOutput" to INTERACTIVE, "AgentStop" to FAST,
        "AgentMemory" to NORMAL, "ExtendTurns" to FAST,
    )

    /** 取某工具的超时（毫秒）。 */
    fun forTool(name: String): Long = TABLE[name] ?: DEFAULT_TIMEOUT

    /**
     * 供系统提示词注入的分级摘要（一行）。
     *
     * **提示词不硬写超时值** —— 用这个函数现算，避免改表后文档变假信息。
     * 对应 CLI `describeTimeoutTiers()`。
     */
    fun describe(): String {
        fun s(ms: Long) = "${ms / 1000}s"
        return "只读检索 ${s(FAST)} / 本地写入 ${s(NORMAL)} / 网络类 ${s(NETWORK)} / " +
            "生图·视频 ${s(SLOW_GEN)} / Bash·Test·Agent ${s(LONG)} / " +
            "等用户回答 ${INTERACTIVE / 60_000}min，默认 ${s(DEFAULT_TIMEOUT)}，" +
            "硬上限 ${MAX_TOOL_TIMEOUT / 60_000}min"
    }
}
