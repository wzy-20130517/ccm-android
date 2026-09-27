package com.ccm.app.tools.task

import com.ccm.app.tools.file.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Team + Mailbox —— 多 Agent 协作的组织与通信层。
 *
 * 参照 Node 版 `core/teams.mjs`（223 行）。三个设计要点：
 *
 * ══════════════════════════════════════════════════════════════
 *  1. Team 与 TaskList 1:1 对应
 * ══════════════════════════════════════════════════════════════
 * teamName 直接当 [TaskStore] 的 listId 用 —— 不单独维护「团队有哪些任务」，
 * 天然同步。解散团队时清任务列表也是同一个原因（Team = TaskList，不能只删一半）。
 *
 * ══════════════════════════════════════════════════════════════
 *  2. 每个成员一个独立 inbox 文件（不是共享队列）
 * ══════════════════════════════════════════════════════════════
 * 避免并发写同一文件，也让「谁还没读消息」可查。
 *
 * ══════════════════════════════════════════════════════════════
 *  3. 消息带 read 标记而不是读完删除
 * ══════════════════════════════════════════════════════════════
 * 保留会话痕迹，便于事后复盘（`CheckMessages(all:true)` 就是靠这个）。
 *
 * ══════════════════════════════════════════════════════════════
 *  广播 `to:"*"`
 * ══════════════════════════════════════════════════════════════
 * 对齐官方 SendMessageTool：没有广播时，想让全场都听见只能对每个人发一遍 ——
 * CCM 实测某局里 4 个 Agent 各自把同一段话发 3 遍，纯浪费轮次。
 * ⚠️ 代价随人数线性增长，只在真的人人都需要时用。
 *
 * 【上限】单个 inbox 保留 200 条，超限丢最旧的 ——
 * inbox 是通信缓冲不是归档，无上限会让长任务的 JSON 涨到几 MB，每次读写都变慢。
 */
class TeamStore(private val rootDir: File) {

    companion object {
        const val MAX_INBOX_MESSAGES = 200

        /** 成员状态 */
        const val ACTIVE = "active"
        const val INACTIVE = "inactive"
    }

    data class Member(
        val name: String,
        val role: String = "",
        val status: String = ACTIVE,
        val joinedAt: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name); put("role", role); put("status", status); put("joinedAt", joinedAt)
        }

        companion object {
            fun fromJson(o: JSONObject) = Member(
                o.optString("name"),
                o.optString("role"),
                o.optString("status", ACTIVE),
                o.optString("joinedAt"),
            )
        }
    }

    data class Message(
        val from: String,
        val to: String,
        val text: String,
        val summary: String = "",
        val type: String = "text",
        val timestamp: String = "",
        val read: Boolean = false,
        /** 协议请求类型（shutdown / plan_approval），普通消息为 null */
        val request: String? = null,
        val requestId: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("from", from); put("to", to); put("text", text)
            put("summary", summary); put("type", type)
            put("timestamp", timestamp); put("read", read)
            request?.let { put("request", it) }
            requestId?.let { put("requestId", it) }
        }

        companion object {
            fun fromJson(o: JSONObject) = Message(
                from = o.optString("from"),
                to = o.optString("to"),
                text = o.optString("text"),
                summary = o.optString("summary"),
                type = o.optString("type", "text"),
                timestamp = o.optString("timestamp"),
                read = o.optBoolean("read", false),
                request = o.optString("request").takeIf { it.isNotEmpty() },
                requestId = o.optString("requestId").takeIf { it.isNotEmpty() },
            )
        }
    }

    data class TeamInfo(
        val name: String,
        val description: String = "",
        val members: List<Member> = emptyList(),
        val createdAt: String = "",
        val updatedAt: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("name", name); put("description", description)
            put("members", JSONArray().apply { members.forEach { put(it.toJson()) } })
            put("createdAt", createdAt); put("updatedAt", updatedAt)
        }

        companion object {
            fun fromJson(o: JSONObject) = TeamInfo(
                name = o.optString("name"),
                description = o.optString("description"),
                members = o.optJSONArray("members")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Member.fromJson(it) } }
                } ?: emptyList(),
                createdAt = o.optString("createdAt"),
                updatedAt = o.optString("updatedAt"),
            )
        }
    }

    // ── 路径 ──────────────────────────────────────────────────────

    private fun safeName(s: String): String =
        s.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_")

    private fun teamDir(team: String) = File(rootDir, safeName(team))
    private fun configPath(team: String) = File(teamDir(team), "config.json")
    private fun inboxDir(team: String) = File(teamDir(team), "inboxes")
    private fun inboxPath(team: String, agent: String) = File(inboxDir(team), "${safeName(agent)}.json")

    private fun now(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .format(java.util.Date())

    // ── 读写 ──────────────────────────────────────────────────────

    private fun readTeam(team: String): TeamInfo? = try {
        val f = configPath(team)
        if (f.exists()) TeamInfo.fromJson(JSONObject(f.readText())) else null
    } catch (_: Throwable) {
        null
    }

    private fun writeTeam(info: TeamInfo): TeamInfo {
        teamDir(info.name).mkdirs()
        AtomicFile.writeText(configPath(info.name), info.toJson().toString(2))
        return info
    }

    private fun readInbox(team: String, agent: String): MutableList<Message> = try {
        val f = inboxPath(team, agent)
        if (!f.exists()) mutableListOf()
        else {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i -> Message.fromJson(arr.getJSONObject(i)) }
        }
    } catch (_: Throwable) {
        mutableListOf()
    }

    private fun writeInbox(team: String, agent: String, box: List<Message>) {
        inboxDir(team).mkdirs()
        val arr = JSONArray()
        // 超限丢最旧（inbox 是缓冲不是归档）
        box.takeLast(MAX_INBOX_MESSAGES).forEach { arr.put(it.toJson()) }
        AtomicFile.writeText(inboxPath(team, agent), arr.toString(2))
    }

    // ── 团队 ──────────────────────────────────────────────────────

    @Synchronized
    fun exists(team: String): Boolean = configPath(team).exists()

    @Synchronized
    fun get(team: String): TeamInfo? = readTeam(team)

    @Synchronized
    fun list(): List<TeamInfo> =
        rootDir.listFiles()?.filter { it.isDirectory }
            ?.mapNotNull { readTeam(it.name) } ?: emptyList()

    @Synchronized
    fun create(team: String, description: String = ""): TeamInfo {
        require(team.isNotBlank()) { "team 名称必填" }
        readTeam(team)?.let { return it }   // 已存在则直接返回（幂等）
        val ts = now()
        val info = TeamInfo(
            name = safeName(team),
            description = description,
            members = emptyList(),
            createdAt = ts,
            updatedAt = ts,
        )
        inboxDir(info.name).mkdirs()
        return writeTeam(info)
    }

    /**
     * 加入团队。**幂等** —— 重复加入不报错。
     *
     * 为什么必须幂等：子 Agent 可能被重启后重新 join，
     * 报错会让它以为「进不去」而放弃协作。
     */
    @Synchronized
    fun join(team: String, agent: String, role: String = ""): TeamInfo? {
        val cfg = readTeam(team) ?: return null
        val name = safeName(agent)
        val existing = cfg.members.find { it.name == name }
        val members = if (existing != null) {
            cfg.members.map {
                if (it.name == name) it.copy(
                    role = role.ifBlank { it.role },
                    status = ACTIVE,
                ) else it
            }
        } else {
            cfg.members + Member(name, role, ACTIVE, now())
        }
        val updated = writeTeam(cfg.copy(members = members, updatedAt = now()))
        // 建空 inbox，让 send 不必先判目录是否存在
        if (!inboxPath(team, name).exists()) writeInbox(team, name, emptyList())
        return updated
    }

    @Synchronized
    fun setMemberStatus(team: String, agent: String, status: String): Boolean {
        val cfg = readTeam(team) ?: return false
        val name = safeName(agent)
        if (cfg.members.none { it.name == name }) return false
        val members = cfg.members.map { if (it.name == name) it.copy(status = status) else it }
        writeTeam(cfg.copy(members = members, updatedAt = now()))
        return true
    }

    /**
     * 解散团队。**同时清任务列表**（Team = TaskList，不能只删一半）。
     *
     * @return Pair(是否成功, 清掉的任务数)
     */
    @Synchronized
    fun disband(team: String, keepTasks: Boolean, taskStore: TaskStore): Pair<Boolean, Int> {
        if (!exists(team)) return false to 0
        val taskCount = if (keepTasks) 0 else taskStore.resetList(safeName(team))
        val ok = runCatching { teamDir(team).deleteRecursively() }.getOrDefault(false)
        return ok to taskCount
    }

    // ── 消息 ──────────────────────────────────────────────────────

    /** 投递结果 */
    sealed class SendResult {
        data class Ok(val queued: Int) : SendResult()
        data class Broadcast(val delivered: Int, val targets: List<String>) : SendResult()
        data class Failed(val reason: String, val detail: String = "") : SendResult()
    }

    /**
     * 投递消息到对方 inbox。
     *
     * @param to 接收方；`"*"` = 广播给所有 active 成员（排除自己）
     */
    @Synchronized
    fun send(
        team: String,
        from: String,
        to: String,
        text: String,
        summary: String = "",
        type: String = "text",
        request: String? = null,
        requestId: String? = null,
    ): SendResult {
        val cfg = readTeam(team) ?: return SendResult.Failed("no_team", "团队不存在：$team")

        // 广播
        if (to.trim() == "*") {
            val me = safeName(from)
            val targets = cfg.members.filter { it.name != me && it.status != INACTIVE }
            if (targets.isEmpty()) {
                return SendResult.Failed("no_peers", "团队里没有其他 active 成员")
            }
            var n = 0
            targets.forEach { m ->
                val r = send(team, from, m.name, text, summary, type, request, requestId)
                if (r is SendResult.Ok) n++
            }
            return SendResult.Broadcast(n, targets.map { it.name })
        }

        val target = safeName(to)
        if (cfg.members.none { it.name == target }) {
            return SendResult.Failed(
                "no_member",
                "「$to」不在团队里。当前成员：${cfg.members.joinToString(", ") { it.name }.ifEmpty { "(空)" }}",
            )
        }

        val box = readInbox(team, target)
        box += Message(
            from = safeName(from),
            to = target,
            text = text,
            summary = summary.take(80),
            type = type,
            timestamp = now(),
            read = false,
            request = request,
            requestId = requestId,
        )
        writeInbox(team, target, box)
        return SendResult.Ok(box.size)
    }

    /**
     * 读取 inbox。
     *
     * @param unreadOnly 只返回未读（默认 true）
     * @param peek 不标记已读（用于旁观查看）
     */
    @Synchronized
    fun readInboxMessages(
        team: String,
        agent: String,
        unreadOnly: Boolean = true,
        peek: Boolean = false,
    ): List<Message> {
        val box = readInbox(team, agent)
        val picked = if (unreadOnly) box.filter { !it.read } else box
        if (!peek && picked.isNotEmpty()) {
            val marked = box.map { if (it.read) it else it.copy(read = true) }
            writeInbox(team, agent, marked)
        }
        return picked
    }

    /** 每个成员的收件统计（谁还没读消息） */
    @Synchronized
    fun inboxCounts(team: String): Map<String, Pair<Int, Int>> {
        val dir = inboxDir(team)
        if (!dir.exists()) return emptyMap()
        return dir.listFiles()
            ?.filter { it.name.endsWith(".json") }
            ?.associate { f ->
                val agent = f.name.removeSuffix(".json")
                val box = readInbox(team, agent)
                agent to (box.size to box.count { !it.read })
            } ?: emptyMap()
    }

    /** 回复一条协议请求（把 respond 结果投回请求方 inbox） */
    @Synchronized
    fun respond(team: String, from: String, to: String, requestId: String, approved: Boolean, text: String): SendResult =
        send(
            team = team,
            from = from,
            to = to,
            text = text,
            summary = if (approved) "已批准" else "已驳回",
            type = "response",
            request = "respond:$requestId",
        )
}
