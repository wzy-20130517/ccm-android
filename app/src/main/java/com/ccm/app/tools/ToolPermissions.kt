package com.ccm.app.tools

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 权限裁决 —— 决定一个工具调用「能不能跑」。
 *
 * ══════════════════════════════════════════════════════════════
 *  裁决顺序（与 CCM `core/permissions.mjs:resolve()` 完全一致）
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 * 1. permissions.json 的 deny  → 拒绝（最高优先级，用户显式声明）
 * 2. permissions.json 的 allow → 放行
 * 3. permissions.json 的 ask   → 标记 needAsk（交给 UI 询问）
 * 4. 权限模式裁决：
 *    · bypassPermissions → 全放行
 *    · plan              → 只放行只读白名单；Bash 还要看命令内容
 *    · acceptEdits       → 放行写操作 + 只读；装饰工具仍拦
 *    · default           → 拦装饰工具，其余放行
 * ```
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么要有「装饰工具」黑名单
 * ══════════════════════════════════════════════════════════════
 *
 * 这是 CCM 上真实踩出来的教训：AI 被骂了之后会用 `Toast` 道歉、
 * 用 `TodoWrite` 写检讨、反复 `Memory` 刷同一条纪律 —— 全是**二次伤害**。
 * 这些工具（弹窗/震动/通知/朗读）在用户没点名时**默认拒绝**。
 *
 * 判断标准很简单：**用户没点名 + 任务不依赖它 = 不许调**。
 *
 * 【线程安全】读操作无状态；mode 与 cache 用 @Volatile。
 */
class ToolPermissions(private val configDir: File) {

    companion object {
        /** 装饰性工具：default / acceptEdits 模式下未点名就拒绝 */
        val DECORATIVE_TOOLS = setOf(
            "Toast", "TTS", "Notify", "Vibrate", "Battery", "Location",
            "ClipboardGet", "Screencap", "say",
        )

        /** 写操作类工具：acceptEdits 模式下自动放行 */
        val WRITE_TOOLS = setOf(
            "Write", "Edit", "MultiEdit", "ApplyPatch", "SafeRename",
            "HashlineEdit", "GitAdd", "GitCommit",
        )

        /** plan 模式允许的只读工具白名单 */
        val PLAN_ALLOWED = setOf(
            "Read", "Glob", "Grep", "HashlineRead", "HashlineGrep", "Bash",
            "WebFetch", "WebSearch", "SearchInfo", "Lookup", "GitStatus",
            "GitDiff", "GitLog", "TodoWrite", "Agent", "Skill",
            "UserInputHistory", "ViewImage", "ViewVideo", "CommandExec",
            "Test", "Diagnostics", "RepoMap", "Symbols", "CodeSearch",
            "AgentStatus", "AgentOutput", "TaskList", "TaskGet", "GetGoal",
            "phone_snapshot", "phone_screenshot", "phone_device", "phone_vd",
        )

        val MODES = listOf("default", "acceptEdits", "plan", "bypassPermissions")

        /** plan 模式下被判定为「写操作」的 Bash 命令特征 */
        private val BASH_WRITE_PATTERN = Regex(
            "\\b(rm|mv|cp|write|tee|sed\\s+-i|npm\\s+i|pip\\s+install|" +
                "git\\s+(commit|push|add)|chmod|chown|mkdir|touch|dd|truncate)\\b",
            RegexOption.IGNORE_CASE,
        )
    }

    /** 裁决结果 */
    data class Decision(
        val allowed: Boolean,
        val reason: String? = null,
        val needAsk: Boolean = false,
    ) {
        companion object {
            val ALLOW = Decision(true)
            fun deny(reason: String) = Decision(false, reason)
            fun ask(reason: String) = Decision(false, reason, needAsk = true)
        }
    }

    @Volatile
    var mode: String = "default"
        private set

    // 规则缓存（带 mtime，避免每次工具调用都读盘）
    private var rulesCache: JSONObject? = null
    private var rulesMtime: Long = -1L

    private val permFile: File get() = File(configDir, "permissions.json")

    // ── 模式 ──────────────────────────────────────────────────────

    fun setMode(newMode: String): String {
        if (newMode !in MODES) {
            return "未知模式: $newMode，可选: ${MODES.joinToString("|")}"
        }
        mode = newMode
        return "权限模式 → $newMode"
    }

    /** 从配置加载模式（启动时调用） */
    fun loadModeFrom(configJson: JSONObject?) {
        val m = configJson?.optString("permissionMode", "")?.takeIf { it.isNotEmpty() }
            ?: configJson?.optString("permission_mode", "")?.takeIf { it.isNotEmpty() }
            ?: return
        if (m in MODES) mode = m
    }

    // ── 规则 ──────────────────────────────────────────────────────

    /**
     * 加载 permissions.json 规则（带 mtime 缓存）。
     *
     * 每次工具调用都会走 resolve()，不能每次都读盘 —— 手机上 I/O 不便宜。
     */
    private fun loadRules(): JSONObject {
        return try {
            if (!permFile.exists()) {
                rulesCache = null
                rulesMtime = -1L
                return emptyRules()
            }
            val m = permFile.lastModified()
            rulesCache?.let { if (rulesMtime == m) return it }
            val parsed = JSONObject(permFile.readText())
            rulesCache = parsed
            rulesMtime = m
            parsed
        } catch (_: Throwable) {
            rulesCache ?: emptyRules()
        }
    }

    private fun emptyRules(): JSONObject = JSONObject().apply {
        put("allow", JSONArray())
        put("deny", JSONArray())
        put("ask", JSONArray())
    }

    private fun hasIn(obj: JSONObject, key: String, toolName: String): Boolean {
        val arr = obj.optJSONArray(key) ?: return false
        for (i in 0 until arr.length()) {
            if (arr.optString(i, "") == toolName) return true
        }
        return false
    }

    // ── 裁决 ──────────────────────────────────────────────────────

    /**
     * 统一权限裁决。
     *
     * @param toolName 工具名
     * @param tool 工具实例（用于读 isDestructive 等标记），可空
     * @param input 工具入参（plan 模式下 Bash 要看命令内容）
     */
    fun resolve(toolName: String, input: JSONObject? = null, tool: com.ccm.app.core.tool.Tool? = null): Decision {
        // 1) 显式规则最高优先
        val rules = loadRules()
        if (hasIn(rules, "deny", toolName)) {
            return Decision.deny("permissions.json deny: $toolName")
        }
        if (hasIn(rules, "allow", toolName)) {
            return Decision.ALLOW
        }
        if (hasIn(rules, "ask", toolName)) {
            return Decision.ask("permissions.json ask: $toolName")
        }

        // 2) 模式裁决
        when (mode) {
            "bypassPermissions" -> return Decision.ALLOW

            "plan" -> {
                if (toolName !in PLAN_ALLOWED) {
                    return Decision.deny("plan 模式禁止工具: $toolName（只允许只读工具）")
                }
                // plan 下的 Bash：只放行明显只读的命令
                if (toolName == "Bash") {
                    val cmd = input?.optString("command", "") ?: ""
                    if (BASH_WRITE_PATTERN.containsMatchIn(cmd)) {
                        return Decision.deny("plan 模式禁止写操作 Bash：$cmd")
                    }
                }
                return Decision.ALLOW
            }

            "acceptEdits" -> {
                // 自动放行写文件 + 一切只读；装饰工具仍拦
                if (toolName in DECORATIVE_TOOLS) {
                    return Decision.deny(
                        "acceptEdits 下装饰工具需用户点名或 /permissions allow $toolName"
                    )
                }
                return Decision.ALLOW
            }
        }

        // 3) default：拦装饰工具
        if (toolName in DECORATIVE_TOOLS) {
            return Decision.deny(
                "default 模式拒绝装饰工具 $toolName（用户未明确要求时）。" +
                    "需要：/permissions allow $toolName 或 /permissions mode bypassPermissions"
            )
        }

        return Decision.ALLOW
    }

    /** 规则文件路径（给 /permissions 面板展示用） */
    fun rulesFilePath(): String = permFile.absolutePath

    /** 读取规则（供 UI 展示） */
    fun dumpRules(): JSONObject = loadRules()
}
