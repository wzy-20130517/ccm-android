package com.ccm.app.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Hook 系统 —— 在工具执行前后插入用户自定义逻辑。
 *
 * ══════════════════════════════════════════════════════════════
 *  与 CCM 的关键差异（必须理解，否则会照抄错）
 * ══════════════════════════════════════════════════════════════
 *
 * CCM 的 hook 本质是「执行一段 shell 命令，读它的 stdout 前缀」：
 * ```
 * hooks.json: { "PreToolUse": [{ "matcher": "Bash", "hooks": [{ "command": "./check.sh" }] }] }
 * check.sh 输出 "DENY: 危险命令"  → 工具被拦
 * ```
 *
 * **APK 里没有「随时可用的 shell」** —— Bash 通道（proot / Termux）
 * 要么没装好、要么需要用户选择，不该成为 hook 的硬依赖。
 *
 * 所以这里拆成两层：
 *
 * | 层 | 实现 | 用途 |
 * |---|---|---|
 * | **内置 hook** | Kotlin lambda，直接注册 | 安全策略、审计、埋点（推荐） |
 * | **外部命令 hook** | 通过可插拔的 [CommandRunner] | 兼容 CCM 的 hooks.json 写法 |
 *
 * [CommandRunner] 由 Bash 通道在批 2 完成后注入。**未注入时外部 hook 静默跳过**
 * （fail-open），不会因为「Bash 还没装好」就把所有工具调用卡死。
 *
 * ══════════════════════════════════════════════════════════════
 *  事件与语义（对齐 CCM `core/hooks.mjs`）
 * ══════════════════════════════════════════════════════════════
 *
 * | 事件 | 效果 |
 * |---|---|
 * | `PreToolUse` | 输出 `DENY: 原因` → 阻止工具执行 |
 * | `PostToolUse` | 观察类，无效果（可用于审计/通知） |
 * | `SessionStart` / `SessionEnd` | 观察类 |
 * | `UserPromptSubmit` | 输出 `INJECT: 内容` → 注入额外上下文 |
 * | `Stop` | 输出 `BLOCK: 原因` → 阻止 agent 结束（续跑一轮） |
 * | `PreCompact` / `PostCompact` | 观察类 |
 *
 * ══════════════════════════════════════════════════════════════
 *  fail-open vs fail-closed
 * ══════════════════════════════════════════════════════════════
 *
 * hook 崩溃/超时时默认 **fail-open**（放行）—— 通知类、统计类 hook 挂了
 * 不该挡住正常工作。
 *
 * 但安全类 hook 应该声明 `failClosed = true`：崩溃 = 无法证明放行是安全的
 * → **拒绝执行**，宁可误拦不可漏放。
 */
class ToolHooks(private val configDir: File) {

    /** 单个 hook 条目 */
    data class HookEntry(
        val matcher: String = "",          // 正则，空 = 匹配全部
        val command: String? = null,       // 外部命令（走 CommandRunner）
        val timeoutMs: Long = 5_000L,
        val failClosed: Boolean = false,
        val source: String = "hooks.json", // 来源标记（便于排查「是谁拦了我的工具」）
        val builtin: (suspend (HookContext) -> HookOutcome)? = null,  // 内置 hook
    )

    /** hook 执行上下文 */
    data class HookContext(
        val event: String,
        val tool: String = "",
        val input: JSONObject? = null,
        val output: String = "",
        val sessionId: String = "",
        val prompt: String = "",
        val reason: String = "",
    )

    /** hook 执行结果 */
    data class HookOutcome(
        val deny: Boolean = false,
        val block: Boolean = false,
        val inject: String? = null,
        val message: String = "",
        /** PreToolUse 可改写入参（对齐官方 updatedInput） */
        val updatedInput: JSONObject? = null,
    )

    /** 聚合结果 */
    data class HookResult(
        val deny: Boolean = false,
        val denyMessage: String = "",
        val block: Boolean = false,
        val blockMessages: List<String> = emptyList(),
        val inject: String? = null,
        val updatedInput: JSONObject? = null,
    ) {
        companion object {
            val EMPTY = HookResult()
        }
    }

    /**
     * 外部命令执行器 —— 由 Bash 通道注入。
     *
     * 返回 stdout；执行失败返回 null（调用方按 fail-open/fail-closed 处理）。
     */
    fun interface CommandRunner {
        suspend fun run(command: String, env: Map<String, String>, timeoutMs: Long): String?
    }

    @Volatile
    private var commandRunner: CommandRunner? = null

    /** 注入命令执行器（批 2 的 Bash 通道完成后调用） */
    fun setCommandRunner(runner: CommandRunner?) {
        commandRunner = runner
    }

    val hasCommandRunner: Boolean get() = commandRunner != null

    /** 事件 → 条目列表 */
    private val entries = mutableMapOf<String, MutableList<HookEntry>>()

    private val configFile: File get() = File(configDir, "hooks.json")

    // ── 注册 ──────────────────────────────────────────────────────

    /** 注册一个内置 hook（Kotlin 实现，推荐方式） */
    @Synchronized
    fun register(event: String, entry: HookEntry) {
        entries.getOrPut(event) { mutableListOf() }.add(entry)
    }

    /** 注销某来源的全部 hook */
    @Synchronized
    fun unregisterSource(source: String): Int {
        var removed = 0
        entries.values.forEach { list ->
            removed += list.count { it.source == source }
            list.removeAll { it.source == source }
        }
        return removed
    }

    /** 从 hooks.json 加载外部命令 hook */
    @Synchronized
    fun loadFromConfig(): Int {
        var count = 0
        try {
            if (!configFile.exists()) return 0
            val json = JSONObject(configFile.readText())
            json.keys().forEach { event ->
                val arr = json.optJSONArray(event) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val matcher = o.optString("matcher", "")
                    val hooksArr = o.optJSONArray("hooks") ?: JSONArray()
                    for (j in 0 until hooksArr.length()) {
                        val h = hooksArr.optJSONObject(j) ?: continue
                        val cmd = h.optString("command", "").takeIf { it.isNotEmpty() } ?: continue
                        register(
                            event,
                            HookEntry(
                                matcher = matcher,
                                command = cmd,
                                timeoutMs = h.optLong("timeout", 5_000L),
                                failClosed = h.optBoolean("failClosed", false),
                                source = "hooks.json",
                            ),
                        )
                        count++
                    }
                }
            }
        } catch (_: Throwable) {
            // 配置坏了不影响主流程（hook 是增强，不是必需）
        }
        return count
    }

    /** 已注册事件（供 /hooks 面板展示） */
    @Synchronized
    fun hookedEvents(): List<String> = entries.filter { it.value.isNotEmpty() }.keys.toList()

    @Synchronized
    fun count(event: String? = null): Int =
        if (event == null) entries.values.sumOf { it.size } else entries[event]?.size ?: 0

    @Synchronized
    fun clear() = entries.clear()

    // ── 触发 ──────────────────────────────────────────────────────

    /**
     * 触发事件。
     *
     * 所有匹配的 hook 都会执行（PreToolUse 的 DENY 会**短路**，后面的不再跑；
     * Stop 的 BLOCK 会**累积**，全部跑完后一起返回）。
     */
    suspend fun trigger(event: String, ctx: HookContext): HookResult {
        val list = snapshot(event)
        if (list.isEmpty()) return HookResult.EMPTY

        var updatedInput: JSONObject? = null
        val blocks = mutableListOf<String>()
        val injections = mutableListOf<String>()

        for (entry in list) {
            if (!matches(entry, ctx)) continue

            val outcome = runOne(entry, ctx)

            // PreToolUse: DENY 短路
            if (event == "PreToolUse" && outcome.deny) {
                return HookResult(deny = true, denyMessage = outcome.message.ifEmpty { "Hook denied" })
            }
            // PreToolUse: 入参改写
            if (event == "PreToolUse" && outcome.updatedInput != null) {
                updatedInput = outcome.updatedInput
            }
            // Stop: BLOCK 累积
            if (event == "Stop" && outcome.block) {
                blocks += outcome.message.ifEmpty { "Hook requested continue" }
            }
            // UserPromptSubmit: INJECT
            outcome.inject?.let { injections += it }
        }

        return HookResult(
            deny = false,
            block = blocks.isNotEmpty(),
            blockMessages = blocks,
            inject = injections.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            updatedInput = updatedInput,
        )
    }

    @Synchronized
    private fun snapshot(event: String): List<HookEntry> =
        entries[event]?.toList() ?: emptyList()

    private fun matches(entry: HookEntry, ctx: HookContext): Boolean {
        if (entry.matcher.isEmpty()) return true
        val target = ctx.tool.ifEmpty { "" }
        if (target.isEmpty()) return false
        return try {
            Regex(entry.matcher).containsMatchIn(target)
        } catch (_: Throwable) {
            false   // 正则写错 → 该 hook 不匹配（不让配置错误扩大影响）
        }
    }

    private suspend fun runOne(entry: HookEntry, ctx: HookContext): HookOutcome {
        // 内置 hook 优先
        entry.builtin?.let { fn ->
            return try {
                fn(ctx)
            } catch (_: Throwable) {
                if (entry.failClosed) {
                    HookOutcome(deny = true, message = "安全 hook 异常，已按 fail-closed 拦截")
                } else {
                    HookOutcome()
                }
            }
        }

        // 外部命令 hook
        val cmd = entry.command ?: return HookOutcome()
        val runner = commandRunner ?: return HookOutcome()   // 通道未就绪 → 静默跳过（fail-open）

        val env = buildEnv(ctx)
        val stdout = runner.run(cmd, env, entry.timeoutMs)

        if (stdout == null) {
            // 执行失败：fail-closed 才拦
            return if (entry.failClosed) {
                HookOutcome(deny = true, message = "安全 hook 执行失败，已按 fail-closed 拦截")
            } else {
                HookOutcome()
            }
        }

        val trimmed = stdout.trim()
        return when {
            ctx.event == "PreToolUse" && trimmed.startsWith("DENY:") ->
                HookOutcome(deny = true, message = trimmed.removePrefix("DENY:").trim())
            ctx.event == "Stop" && trimmed.startsWith("BLOCK:") ->
                HookOutcome(block = true, message = trimmed.removePrefix("BLOCK:").trim())
            ctx.event == "UserPromptSubmit" && trimmed.startsWith("INJECT:") ->
                HookOutcome(inject = trimmed.removePrefix("INJECT:").trim())
            else -> HookOutcome()
        }
    }

    private fun buildEnv(ctx: HookContext): Map<String, String> = mapOf(
        "EVENT" to ctx.event,
        "TOOL_NAME" to ctx.tool,
        "TOOL_INPUT" to (ctx.input?.toString() ?: "{}"),
        // 截断：hook 脚本不需要完整的大输出，环境变量太大在某些系统会报错
        "TOOL_OUTPUT" to ctx.output.take(4000),
        "SESSION_ID" to ctx.sessionId,
        "PROMPT" to ctx.prompt,
        "STOP_REASON" to ctx.reason,
    )
}
