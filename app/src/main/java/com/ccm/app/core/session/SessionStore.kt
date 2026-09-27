package com.ccm.app.core.session

import com.ccm.app.core.AppStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * 会话存档。
 *
 * 对应 Node 版 `core/persistence.mjs` 的 `SessionStore`。
 *
 * ## 落盘格式（**必须与 Node 版兼容**）
 * ```json
 * {
 *   "sessionId": "abc-123",
 *   "title": "重构 core 层",
 *   "createdAt": 1758000000000,
 *   "updatedAt": 1758000123456,
 *   "messages": [ { "role": "user", "content": [...], "timestamp": ... } ]
 * }
 * ```
 * 用户可能把 Node 的会话文件直接搬过来，所以字段名用 `@SerialName` 显式绑定，
 * 不依赖 Kotlin 属性名自动映射。
 *
 * ## 三条安全设计（都有踩坑背景）
 *
 * ### 1. 原子写
 * 直接 `writeText` 到目标文件，写到一半被杀（App 崩溃 / 用户强杀）会留下
 * **半个 JSON** —— 下次启动解析失败，整个会话丢失。
 * 所以先写 `.tmp` 再 `rename`（rename 在同一文件系统上是原子的）。
 *
 * ### 2. 覆盖前差异检查
 * Node 版坑：`/clear` 或 resume 后用同一个 sid 写入**完全不同的内容**，
 * 旧会话被静默覆盖。这里在 messages 数量差异过大时先备份。
 *
 * ### 3. 超限自动清理
 * 会话文件会无限增长。超过 [MAX_SESSION_FILES] 时删最旧的，
 * **但永不删当前活跃会话**（正在用的那个被删了等于当场丢数据）。
 */
class SessionStore(
    private val storage: AppStorage,
    /** 最多保留多少会话文件。 */
    private val maxSessionFiles: Int = MAX_SESSION_FILES,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val dir: File get() = storage.sessionsDir

    init {
        storage.ensureDirs()
    }

    /**
     * 保存会话。
     *
     * @param session 会话数据
     */
    fun save(session: Session) {
        val target = fileFor(session.sessionId)

        // 覆盖前差异检查：内容差太多就备份旧的（防误覆盖）
        if (target.exists()) {
            try {
                val old = json.decodeFromString(Session.serializer(), target.readText())
                val diff = kotlin.math.abs(old.messages.size - session.messages.size)
                if (diff > BACKUP_DIFF_THRESHOLD) {
                    backup(target, "session-${session.sessionId}-before-overwrite")
                }
            } catch (_: Throwable) {
                // 旧文件坏了不阻塞写入（新内容总比坏文件有价值）
            }
        }

        atomicWrite(target, json.encodeToString(Session.serializer(), session))
        prune(keepId = session.sessionId)
    }

    /** 读取会话。文件不存在或损坏返回 null（**不能让启动路径崩溃**）。 */
    fun load(sessionId: String): Session? {
        val f = fileFor(sessionId)
        if (!f.exists()) return null
        return try {
            json.decodeFromString(Session.serializer(), f.readText())
        } catch (_: Throwable) {
            null
        }
    }

    /** 列出所有会话 id（按更新时间倒序，最新在前）。 */
    fun list(): List<String> = try {
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.name.removeSuffix(".json") }
            ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    /** 列出所有会话的摘要（给 /load 面板用）。 */
    fun listSummaries(): List<SessionSummary> = list().mapNotNull { sid ->
        load(sid)?.let { s ->
            SessionSummary(
                sessionId = s.sessionId,
                title = s.title,
                messageCount = s.messages.size,
                updatedAt = s.updatedAt,
            )
        }
    }

    /**
     * 按标题找会话 id（重名保护用）。
     *
     * 只读文件头部 4KB：会话 JSON 是 pretty-print，title 在很靠前的位置，
     * 没必要把每个几百 KB 的会话全文解析一遍。
     */
    fun findByTitle(title: String, excludeId: String? = null): String? {
        val want = title.trim()
        if (want.isEmpty()) return null

        for (sid in list()) {
            if (sid == excludeId) continue
            val head = try {
                readHead(fileFor(sid), 4096)
            } catch (_: Throwable) {
                continue
            }
            // 从头部文本里捞 title（正则容忍转义）
            val m = TITLE_RE.find(head) ?: continue
            val raw = m.groupValues[1]
            val t = try {
                json.parseToJsonElement(raw).let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }
            } catch (_: Throwable) {
                null
            }
            if (t != null && t.trim() == want) return sid
        }
        return null
    }

    /** 删除会话（先备份到回收站）。 */
    fun delete(sessionId: String): Boolean {
        val f = fileFor(sessionId)
        if (!f.exists()) return false
        return try {
            backup(f, "session-$sessionId-deleted")
            f.delete()
        } catch (_: Throwable) {
            false
        }
    }

    /** 生成一个新的会话 id。 */
    fun newSessionId(): String = UUID.randomUUID().toString()

    // ═════════════════════════ 内部 ═════════════════════════

    private fun fileFor(sessionId: String): File = File(dir, "$sessionId.json")

    /**
     * 原子写：先写 `.tmp`，再 rename 覆盖。
     *
     * rename 在同一文件系统上是原子操作 —— 要么看到旧文件，要么看到新文件，
     * **不会看到半个文件**。这是防「写一半被杀导致会话丢失」的关键。
     */
    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            // 某些文件系统不支持 ATOMIC_MOVE，退回普通覆盖
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** 备份文件到回收站目录（失败不抛）。 */
    private fun backup(f: File, note: String) {
        try {
            val trash = storage.trashDir.apply { mkdirs() }
            val ts = System.currentTimeMillis()
            val dest = File(trash, "$note.$ts")
            f.copyTo(dest, overwrite = false)
        } catch (_: Throwable) {
            // 备份失败不该阻塞主流程
        }
    }

    /**
     * 清理超出上限的旧会话。
     *
     * @param keepId 当前活跃会话，**永不删除**
     */
    private fun prune(keepId: String?) {
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.sortedBy { it.lastModified() }
                ?: return
            if (files.size <= maxSessionFiles) return

            val drop = files.take(files.size - maxSessionFiles)
            for (f in drop) {
                val sid = f.name.removeSuffix(".json")
                if (sid == keepId) continue      // 活跃会话永不删
                backup(f, "session-$sid-auto-prune")
                f.delete()
            }
        } catch (_: Throwable) {
            // 清理失败不阻塞保存
        }
    }

    /** 读文件头部 N 字节（避免为找 title 解析整个大文件）。 */
    private fun readHead(f: File, bytes: Int): String {
        if (!f.exists()) return ""
        return try {
            f.inputStream().use { ins ->
                val buf = ByteArray(bytes)
                val n = ins.read(buf)
                if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
            }
        } catch (_: Throwable) {
            ""
        }
    }

    companion object {
        /** 最多保留 200 个会话文件（对齐 Node 版）。 */
        const val MAX_SESSION_FILES = 200

        /** messages 数量差超过这个值就备份旧文件（防误覆盖）。 */
        private const val BACKUP_DIFF_THRESHOLD = 20

        /** 从文件头部捞 title 字段。 */
        private val TITLE_RE = Regex("\"title\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")")
    }
}

/**
 * 一个会话的完整数据（落盘格式）。
 *
 * 字段名与 Node 版 `sessions/<id>.json` 一致，用户可跨端搬移。
 */
@Serializable
data class Session(
    /** 会话 id（同时是文件名）。 */
    @kotlinx.serialization.SerialName("sessionId")
    val sessionId: String,

    /** 会话名（`/rename` 设置）。null = 未命名。 */
    val title: String? = null,

    /** 创建时间（毫秒）。 */
    @kotlinx.serialization.SerialName("createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    /** 最后更新时间（毫秒）。 */
    @kotlinx.serialization.SerialName("updatedAt")
    val updatedAt: Long = System.currentTimeMillis(),

    /** 对话历史。 */
    val messages: List<Message> = emptyList(),
)

/** 会话摘要（给列表 UI 用，不含完整消息）。 */
data class SessionSummary(
    val sessionId: String,
    val title: String?,
    val messageCount: Int,
    val updatedAt: Long,
) {
    /** 显示名：有 title 用 title，否则用 id 前 8 位。 */
    val displayName: String get() = title?.takeIf { it.isNotBlank() } ?: sessionId.take(8)
}
