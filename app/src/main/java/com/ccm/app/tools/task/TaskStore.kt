package com.ccm.app.tools.task

import com.ccm.app.tools.file.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 持久化 Task 系统 —— 多 Agent 协作的共享待办。
 *
 * 参照 Node 版 `core/tasks.mjs`（238 行）。三个核心机制逐条保留：
 *
 * ══════════════════════════════════════════════════════════════
 *  1. 双向依赖图（blocks / blockedBy 成对维护）
 * ══════════════════════════════════════════════════════════════
 *
 * `A blockedBy B` 和 `B blocks A` 是**同一条边的两个方向**。
 * 只写单边会导致 claim 检查漏判 —— 官方的关键设计，不要简化。
 *
 * ══════════════════════════════════════════════════════════════
 *  2. owner 归属（防止两个 agent 抢同一件事）
 * ══════════════════════════════════════════════════════════════
 *
 * 任务可被某个 agent 占用。已被他人占用时 claim 会被拒。
 *
 * ══════════════════════════════════════════════════════════════
 *  3. claim 四道检查（顺序不能变）
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 * ① 存在         → not_found
 * ② 未被他人占用 → already_claimed（给出占用者）
 * ③ 未完成       → already_completed
 * ④ 无未解决阻塞 → blocked（列出阻塞项）
 * ```
 *
 * ══════════════════════════════════════════════════════════════
 *  与 TodoWrite / bg-tasks 的区别（三者不重叠）
 * ══════════════════════════════════════════════════════════════
 *
 * | 系统 | 生命周期 | 用途 |
 * |---|---|---|
 * | **TodoWrite** | 当轮 | 给用户看进度，写完即弃 |
 * | **Task（本类）** | 跨轮跨重启 | 多 Agent 共享，可领取/交还/依赖 |
 * | **bg-tasks** | 进程内 | 管「正在跑的进程」（pid/stdout/kill） |
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 并发说明
 * ══════════════════════════════════════════════════════════════
 *
 * CCM 原实现**没用文件锁**，注释里写明理由：「Termux 单机场景下子 Agent 是
 * 同进程内串行调度的，真出现并发再引入 lockfile」。
 *
 * APK 侧同样如此，但**多了一层保护**：所有公开方法都 `@Synchronized`
 * （同进程内的多协程并发是真实存在的 —— 工具可被并发调度）。
 * 跨进程并发（如 UI 与 Agent 同时改）仍无锁，但 APK 是单进程架构，不存在。
 *
 * @param rootDir 任务根目录（App 存储下的 tasks/）
 */
class TaskStore(private val rootDir: File) {

    companion object {
        const val DEFAULT_LIST = "default"
        val VALID_STATUS = listOf("pending", "in_progress", "completed")
    }

    /** 一条任务 */
    data class Task(
        val id: String,
        val subject: String,
        val description: String = "",
        val activeForm: String = "",
        val status: String = "pending",
        val owner: String? = null,
        val blocks: List<String> = emptyList(),
        val blockedBy: List<String> = emptyList(),
        val comments: List<Comment> = emptyList(),
        val createdAt: String = "",
        val updatedAt: String = "",
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("subject", subject)
            put("description", description)
            put("activeForm", activeForm)
            put("status", status)
            put("owner", owner ?: JSONObject.NULL)
            put("blocks", JSONArray(blocks))
            put("blockedBy", JSONArray(blockedBy))
            put("comments", JSONArray().apply { comments.forEach { put(it.toJson()) } })
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }

        companion object {
            fun fromJson(o: JSONObject): Task = Task(
                id = o.optString("id"),
                subject = o.optString("subject"),
                description = o.optString("description"),
                activeForm = o.optString("activeForm"),
                status = o.optString("status", "pending"),
                owner = o.optString("owner").takeIf { it.isNotEmpty() && it != "null" },
                // 兼容旧数据：缺字段时补空数组（避免下游到处判空）
                blocks = o.optJSONArray("blocks").toStringList(),
                blockedBy = o.optJSONArray("blockedBy").toStringList(),
                comments = o.optJSONArray("comments")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.let { Comment.fromJson(it) }
                    }
                } ?: emptyList(),
                createdAt = o.optString("createdAt"),
                updatedAt = o.optString("updatedAt"),
            )

            private fun JSONArray?.toStringList(): List<String> {
                if (this == null) return emptyList()
                return (0 until length()).mapNotNull { optString(it, "").takeIf { s -> s.isNotEmpty() } }
            }
        }
    }

    /** 进展留痕 */
    data class Comment(val by: String, val text: String, val at: String) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("by", by); put("text", text); put("at", at)
        }

        companion object {
            fun fromJson(o: JSONObject): Comment = Comment(
                o.optString("by", "unknown"),
                o.optString("text"),
                o.optString("at"),
            )
        }
    }

    /** claim 的结果 */
    sealed class ClaimResult {
        data class Ok(val task: Task) : ClaimResult()
        data class Rejected(val reason: String, val detail: String = "") : ClaimResult()
    }

    // ── 路径 ──────────────────────────────────────────────────────

    private fun listDir(listId: String): File =
        File(rootDir, listId.replace(Regex("[^\\w.-]"), "_"))

    private fun taskFile(listId: String, id: String): File = File(listDir(listId), "$id.json")

    private fun ensureDir(listId: String): File = listDir(listId).apply { if (!exists()) mkdirs() }

    private fun now(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .format(java.util.Date())

    // ── 读写 ──────────────────────────────────────────────────────

    private fun readTask(listId: String, id: String): Task? = try {
        val f = taskFile(listId, id)
        if (f.exists()) Task.fromJson(JSONObject(f.readText())) else null
    } catch (_: Throwable) {
        null
    }

    private fun writeTask(listId: String, task: Task): Task {
        ensureDir(listId)
        AtomicFile.writeText(taskFile(listId, task.id), task.toJson().toString(2))
        return task
    }

    /** 下一个 id：扫目录取最大值 +1（不依赖单独的计数器文件） */
    private fun nextId(listId: String): String {
        val dir = listDir(listId)
        if (!dir.exists()) return "1"
        val max = dir.listFiles()
            ?.mapNotNull { Regex("^(\\d+)\\.json$").find(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            ?.maxOrNull() ?: 0
        return (max + 1).toString()
    }

    // ── 查询 ──────────────────────────────────────────────────────

    @Synchronized
    fun list(listId: String = DEFAULT_LIST): List<Task> {
        val dir = listDir(listId)
        if (!dir.exists()) return emptyList()
        return dir.listFiles()
            ?.filter { Regex("^\\d+\\.json$").matches(it.name) }
            ?.mapNotNull { readTask(listId, it.name.removeSuffix(".json")) }
            ?.sortedBy { it.id.toIntOrNull() ?: Int.MAX_VALUE }
            ?: emptyList()
    }

    @Synchronized
    fun get(listId: String, id: String): Task? = readTask(listId, id)

    /** 所有任务列表名 */
    @Synchronized
    fun listNames(): List<String> =
        rootDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()

    // ── 创建 / 更新 ────────────────────────────────────────────────

    @Synchronized
    fun create(
        listId: String = DEFAULT_LIST,
        subject: String,
        description: String = "",
        activeForm: String = "",
        blockedBy: List<String> = emptyList(),
    ): Task {
        require(subject.isNotBlank()) { "subject 必填" }
        val id = nextId(listId)
        val ts = now()
        val task = Task(
            id = id,
            subject = subject.trim(),
            description = description,
            // activeForm 对齐官方 spinner 用法：进行时描述；没给就用 subject
            activeForm = activeForm.ifBlank { subject.trim() },
            status = "pending",
            owner = null,
            createdAt = ts,
            updatedAt = ts,
        )
        writeTask(listId, task)
        // 建依赖边：blockedBy 里每个 id 的 blocks 也要加上自己（双向维护）
        blockedBy.forEach { dep -> addDependency(listId, dep, id) }
        return readTask(listId, id) ?: task
    }

    /**
     * blockerId 阻塞 blockedId。**双向写**。
     *
     * ⚠️ 只写单边会导致 claim 检查漏判 —— 这是官方的关键设计。
     */
    @Synchronized
    fun addDependency(listId: String, blockerId: String, blockedId: String): Boolean {
        if (blockerId == blockedId) return false
        val blocker = readTask(listId, blockerId) ?: return false
        val blocked = readTask(listId, blockedId) ?: return false

        if (blockedId !in blocker.blocks) {
            writeTask(listId, blocker.copy(blocks = blocker.blocks + blockedId, updatedAt = now()))
        }
        if (blockerId !in blocked.blockedBy) {
            writeTask(listId, blocked.copy(blockedBy = blocked.blockedBy + blockerId, updatedAt = now()))
        }
        return true
    }

    /**
     * 追加一条进展留痕。
     *
     * 多 Agent 交接时最需要的东西：接手的人光看 `status=in_progress`
     * 不知道前面那个人**做到哪、试过什么、卡在哪**。comments 只追加不改写。
     */
    @Synchronized
    fun addComment(listId: String, id: String, by: String, text: String): Task? {
        val task = readTask(listId, id) ?: return null
        if (text.isBlank()) return task
        val updated = task.copy(
            comments = task.comments + Comment(by.ifBlank { "unknown" }, text.trim(), now()),
            updatedAt = now(),
        )
        return writeTask(listId, updated)
    }

    @Synchronized
    fun update(
        listId: String,
        id: String,
        status: String? = null,
        owner: String? = null,
        subject: String? = null,
        description: String? = null,
        activeForm: String? = null,
    ): Task? {
        val task = readTask(listId, id) ?: return null
        if (status != null && status !in VALID_STATUS) {
            throw IllegalArgumentException("status 只能是 ${VALID_STATUS.joinToString(" / ")}")
        }
        val updated = task.copy(
            status = status ?: task.status,
            owner = owner ?: task.owner,
            subject = subject ?: task.subject,
            description = description ?: task.description,
            activeForm = activeForm ?: task.activeForm,
            updatedAt = now(),
        )
        return writeTask(listId, updated)
    }

    // ── claim（四道检查）──────────────────────────────────────────

    /**
     * 尝试领取任务。四道检查，任一不过就拒绝并说明原因。
     *
     * ⚠️ 检查顺序不能变（与官方一致）：
     * 先查存在性 → 再查占用 → 再查完成 → 最后查依赖。
     * 顺序错了会给出误导性的拒绝原因。
     */
    @Synchronized
    fun claim(listId: String, id: String, agentId: String): ClaimResult {
        val task = readTask(listId, id)
            ?: return ClaimResult.Rejected("not_found", "找不到任务 #$id（列表 $listId）")

        // ② 未被他人占用
        val owner = task.owner
        if (owner != null && owner != agentId) {
            return ClaimResult.Rejected("already_claimed", "任务 #$id 已被 $owner 领取")
        }

        // ③ 未完成
        if (task.status == "completed") {
            return ClaimResult.Rejected("already_completed", "任务 #$id 已完成")
        }

        // ④ 无未解决阻塞（pending 和 in_progress 都算未解决）
        val unresolved = list(listId).filter { it.status != "completed" }.map { it.id }.toSet()
        val blocking = task.blockedBy.filter { it in unresolved }
        if (blocking.isNotEmpty()) {
            return ClaimResult.Rejected(
                "blocked",
                "任务 #$id 被未完成的前置任务阻塞：${blocking.joinToString(", ") { "#$it" }}",
            )
        }

        val updated = writeTask(
            listId,
            task.copy(owner = agentId, status = "in_progress", updatedAt = now()),
        )
        return ClaimResult.Ok(updated)
    }

    // ── 删除 ──────────────────────────────────────────────────────

    @Synchronized
    fun delete(listId: String, id: String): Boolean {
        val task = readTask(listId, id) ?: return false
        // 清理反向边，避免留下指向已删任务的悬空依赖
        list(listId).forEach { other ->
            var dirty = false
            var blocks = other.blocks
            var blockedBy = other.blockedBy
            if (id in blocks) {
                blocks = blocks - id; dirty = true
            }
            if (id in blockedBy) {
                blockedBy = blockedBy - id; dirty = true
            }
            if (dirty) {
                writeTask(listId, other.copy(blocks = blocks, blockedBy = blockedBy, updatedAt = now()))
            }
        }
        return try {
            taskFile(listId, id).delete()
        } catch (_: Throwable) {
            false
        }
    }

    /** 清空整个列表。返回清掉的任务数。 */
    @Synchronized
    fun resetList(listId: String = DEFAULT_LIST): Int {
        val dir = listDir(listId)
        if (!dir.exists()) return 0
        val n = list(listId).size
        runCatching { dir.deleteRecursively() }
        return n
    }

    // ── 渲染 ──────────────────────────────────────────────────────

    /** 渲染成人看的清单（供 /tasks 面板与工具结果复用） */
    fun format(tasks: List<Task>, showBlocked: Boolean = true): String {
        if (tasks.isEmpty()) return "（没有任务）"
        return tasks.joinToString("\n") { t ->
            val icon = when (t.status) {
                "completed" -> "✓"
                "in_progress" -> "→"
                else -> "○"
            }
            val own = t.owner?.let { " @$it" } ?: ""
            val dep = if (showBlocked && t.blockedBy.isNotEmpty()) " ⟵${t.blockedBy.joinToString(",")}" else ""
            "$icon #${t.id} ${t.subject}$own$dep"
        }
    }
}
