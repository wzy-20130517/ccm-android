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

        // 【2026-10-16 加】补上 CLI 要读的 savedAt（ISO 8601）。
        // 不写的话，CLI 的 /load 列这个会话时时间列是空的。
        //
        // ⚠️ 必须**无条件**用 updatedAt 重算，不能只在 savedAt 为空时补：
        // 会话是会被反复覆盖保存的（30 秒自动存一次），若沿用旧 savedAt，
        // 它会永远停在第一次保存的时刻，而 updatedAt 一直在前进 ——
        // 两个时间字段各说各话，CLI 和 APK 显示的时间对不上。
        val toWrite = session.copy(savedAt = toIso8601(session.updatedAt))

        // 覆盖前差异检查：内容差太多就备份旧的（防误覆盖）
        if (target.exists()) {
            try {
                val old = json.decodeFromString(Session.serializer(), target.readText())
                val diff = kotlin.math.abs(old.messages.size - toWrite.messages.size)
                if (diff > BACKUP_DIFF_THRESHOLD) {
                    backup(target, "session-${toWrite.sessionId}-before-overwrite")
                }
            } catch (_: Throwable) {
                // 旧文件坏了不阻塞写入（新内容总比坏文件有价值）
            }
        }

        atomicWrite(target, json.encodeToString(Session.serializer(), toWrite))
        prune(keepId = toWrite.sessionId)
    }

    /** 读取会话。文件不存在或损坏返回 null（**不能让启动路径崩溃**）。 */
    fun load(sessionId: String): Session? {
        val f = fileFor(sessionId)
        if (!f.exists()) return null
        return try {
            // 【2026-10-16 改】加 normalized() —— CLI 搬来的会话只有 savedAt
            // （ISO 字符串），没有 createdAt/updatedAt，kotlinx 会给它们填
            // 默认值=当前时间，导致旧会话显示成「刚刚保存」。
            // normalized() 用 savedAt 反解回填，让时间正确。
            json.decodeFromString(Session.serializer(), f.readText()).normalized()
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

    /**
     * 只读文件头 4KB 拿标题 —— 对话页顶栏显示用。
     *
     * 为什么不 [load] 全量：会话 JSON 可能几 MB（几百条消息），
     * 进对话页只为读一个标题就解析整个文件太重。
     * 复用 [findByTitle] 的头部正则路径。
     */
    fun loadTitle(sessionId: String): String? = try {
        val head = readHead(fileFor(sessionId), 4096)
        val m = TITLE_RE.find(head) ?: return null
        json.parseToJsonElement(m.groupValues[1]).let {
            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
    } catch (_: Throwable) {
        null
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
 * ## 跨端兼容（2026-10-16 修）
 *
 * 原来这里只有 `createdAt` / `updatedAt`（毫秒数），而 CLI 写的是
 * `savedAt`（ISO 8601 字符串）。后果是**两边互读都丢时间**：
 *   · APK 读 CLI 会话 → `savedAt` 被 ignoreUnknownKeys 丢弃
 *     → 会话列表里时间全变成「加载时刻」（`updatedAt` 走了默认值）
 *   · CLI 读 APK 会话 → 没有 `savedAt` → 时间列显示空白
 * 两边都不崩，但时间信息都丢 —— 而 README 还写着「格式兼容可互相搬移」。
 *
 * 修法：**双向都写、读时回填**。
 *   · 写：`savedAt` 与 `updatedAt` 同时落盘（`savedAt` 供 CLI 读）
 *   · 读：`savedAt` 存在但 `updatedAt` 缺失时，从它解析回填
 *     （这样 CLI 的会话搬进来，时间显示是正确的）
 *
 * `createdAt` / `updatedAt` 保留，因为 APK 内部（排序、相对时间显示）
 * 一直用毫秒数，改成字符串会牵连一大片 UI。
 */
@Serializable
data class Session(
    /** 会话 id（同时是文件名）。 */
    @kotlinx.serialization.SerialName("sessionId")
    val sessionId: String,

    /** 会话名（`/rename` 设置）。null = 未命名。 */
    val title: String? = null,

    /** 创建时间（毫秒）。APK 内部用。 */
    @kotlinx.serialization.SerialName("createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    /** 最后更新时间（毫秒）。APK 内部用（排序 / 相对时间显示）。 */
    @kotlinx.serialization.SerialName("updatedAt")
    val updatedAt: Long = System.currentTimeMillis(),

    /**
     * 最后保存时间（**ISO 8601 字符串**）。
     *
     * 这个字段是给 **CLI 读**的 —— Node 版 `session-auto.mjs` 写的就是它，
     * `/load` 列表按它显示时间。APK 这边不直接用它做 UI（用 updatedAt），
     * 但**必须写**，否则 CLI 读 APK 的会话时时间列是空的。
     */
    @kotlinx.serialization.SerialName("savedAt")
    val savedAt: String = "",

    /** 对话历史。 */
    val messages: List<Message> = emptyList(),
) {
    /**
     * 归一化时间字段（读取后调用）。
     *
     * 场景：会话是从 CLI 搬过来的，只有 `savedAt`（ISO 字符串），
     * 没有 `createdAt` / `updatedAt`。此时 kotlinx 会给它们填
     * **默认值 = 当前时间**，导致「三天前的会话显示成刚刚保存」。
     *
     * 这里直接用 `savedAt` 反解出毫秒数回填。
     *
     * 【为什么不用阈值判断「是不是默认值」】
     * 一开始写的是「drift > 1 天才回填」（想区分 APK 自己写的和 CLI 搬来的）。
     * 那是错的 —— 判据本身没必要：
     *   · APK 自己写时，`savedAt` 就是从 `updatedAt` 转出来的，两者**本来就一致**，
     *     回填等于赋同一个值，无害
     *   · CLI 搬来的会话，`savedAt` 是唯一权威时间，就该回填
     *   · 用阈值反而制造边界 bug：23 小时前的 CLI 会话不触发回填，
     *     显示成「刚刚」（应为「23 小时前」）
     *
     * 解析失败就保持原样（宁可时间不准，也不能让加载路径崩）。
     */
    fun normalized(): Session {
        if (savedAt.isBlank()) return this
        val ms = parseIso8601(savedAt) ?: return this
        // updatedAt 无条件用 savedAt（它才是权威的「最后保存时刻」）。
        //
        // createdAt 要小心：它可能有两个来源 ——
        //   · APK 自己写的：真实创建时间，通常 < updatedAt，**必须保留**
        //   · CLI 搬来的：根本没有这个字段，kotlinx 填了默认值=现在，
        //     于是 createdAt > updatedAt（时间倒挂）
        // 判据就用这个倒挂：`createdAt > ms` 说明它是默认值污染，才回填。
        val fixedCreatedAt = if (createdAt > ms) ms else createdAt
        return copy(createdAt = fixedCreatedAt, updatedAt = ms)
    }
}

/**
 * 解析 ISO 8601 时间串 → 毫秒。失败返回 null。
 *
 * 兼容 CLI 写的 `2026-10-16T12:06:56.780Z`（带毫秒、Z 结尾），
 * 以及没有毫秒的变体。用 `java.time` 而不是 SimpleDateFormat ——
 * 后者对 `Z` 和毫秒的处理依赖 Locale，在部分设备上会解析失败。
 */
internal fun parseIso8601(s: String): Long? = try {
    java.time.Instant.parse(s).toEpochMilli()
} catch (_: Throwable) {
    // 退一步：可能没有 Z 结尾（本地时间），按 UTC 尽力解析
    try {
        java.time.LocalDateTime.parse(s).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: Throwable) {
        null
    }
}

/** 毫秒 → CLI 用的 ISO 8601 字符串。 */
internal fun toIso8601(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).toString()

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
