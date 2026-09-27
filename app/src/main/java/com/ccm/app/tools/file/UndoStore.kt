package com.ccm.app.tools.file

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 跨文件撤销 —— 持久化快照索引。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要「分组」
 * ══════════════════════════════════════════════════════════════
 *
 * 一次 MultiEdit / ApplyPatch 会改多个文件，用户视角是「一次操作」。
 * 若 undo 只回滚最后一个文件，就会留下一半新一半旧的**破碎状态** ——
 * 比不回滚还糟（代码根本编译不过）。
 *
 * 所以引入 `beginGroup()` / `endGroup()`：
 * ```
 * beginGroup()
 *   saveSnapshot(a.txt)   ← 记录 a 的旧内容
 *   saveSnapshot(b.txt)   ← 记录 b 的旧内容
 * endGroup()
 * ...
 * undo()  →  一次性把 a、b 都还原
 * ```
 *
 * ══════════════════════════════════════════════════════════════
 *  与 CCM 的差异
 * ══════════════════════════════════════════════════════════════
 *
 * CCM 里还有「git 检查点」模式（检测到 git 仓库就自动 commit，undo 走 `git reset`）。
 * APK 侧 **git 工具已决定后置**，所以这里只实现文件快照模式，
 * 但保留 `gitCommit` 字段位（将来接入时不用改索引格式）。
 *
 * 【上限与过期】300 条 / 7 天。CCM 原本是 50 条，实测「一轮多文件改动就能吃掉
 * 十几条，密集开发几分钟满一轮」→ 提到 300。手机磁盘对文本文件不敏感，保留同样的值。
 *
 * 【线程安全】全部 synchronized。
 */
class UndoStore(private val rootDir: File) {

    companion object {
        /** 快照条数上限 */
        const val MAX_SNAPSHOTS = 300

        /** 快照过期时间：7 天 */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        private const val TYPE_FILE = "file"
        private const val TYPE_GROUP_START = "group_start"
        private const val TYPE_GROUP_END = "group_end"
    }

    /** 一条快照记录 */
    private data class Snapshot(
        val type: String,
        val path: String?,          // 被修改的文件（绝对路径）
        val file: String?,          // 快照内容文件
        val timestamp: Long,
        val note: String = "",
        val gitCommit: String? = null,
    )

    private val indexFile: File get() = File(rootDir, "undo-index.json")
    private val snapshots = mutableListOf<Snapshot>()

    init {
        if (!rootDir.exists()) rootDir.mkdirs()
        loadIndex()
    }

    // ── 索引读写 ──────────────────────────────────────────────────

    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            val json = JSONObject(indexFile.readText())
            val arr = json.optJSONArray("snapshots") ?: return
            var changed = false
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val type = o.optString("type", TYPE_FILE)
                if (type != TYPE_FILE) {
                    snapshots += Snapshot(type, null, null, o.optLong("timestamp", 0L))
                    continue
                }
                val rawFile = o.optString("file", "")
                // 快照文件可能因目录迁移而失效 —— 找不到就丢弃该条（避免 undo 时报错）
                val resolved = resolveSnapshotFile(rawFile)
                if (resolved == null) {
                    changed = true
                    continue
                }
                if (resolved != rawFile) changed = true
                snapshots += Snapshot(
                    type = TYPE_FILE,
                    path = o.optString("path", "").takeIf { it.isNotEmpty() },
                    file = resolved,
                    timestamp = o.optLong("timestamp", 0L),
                    note = o.optString("note", ""),
                    gitCommit = o.optString("gitCommit", "").takeIf { it.isNotEmpty() },
                )
            }
            if (changed) flushIndex()
        } catch (_: Throwable) {
            snapshots.clear()   // 索引损坏 → 退化成空，不崩
        }
    }

    private fun resolveSnapshotFile(entryPath: String): String? {
        if (entryPath.isEmpty()) return null
        val f = File(entryPath)
        if (f.exists()) return entryPath
        val alt = File(rootDir, f.name)
        if (alt.exists()) return alt.absolutePath
        return null
    }

    /** 原子写索引 —— 索引半写损坏会丢掉所有回滚点 */
    private fun flushIndex() {
        try {
            val arr = JSONArray()
            snapshots.forEach { s ->
                val o = JSONObject()
                o.put("type", s.type)
                s.path?.let { o.put("path", it) }
                s.file?.let { o.put("file", it) }
                o.put("timestamp", s.timestamp)
                if (s.note.isNotEmpty()) o.put("note", s.note)
                s.gitCommit?.let { o.put("gitCommit", it) }
                arr.put(o)
            }
            val json = JSONObject()
            json.put("snapshots", arr)
            json.put("savedAt", System.currentTimeMillis())
            AtomicFile.writeText(indexFile, json.toString(), createParent = true)
        } catch (_: Throwable) {
            // 索引写失败不阻塞编辑本身
        }
    }

    // ── 保存快照 ──────────────────────────────────────────────────

    /**
     * 保存一份「修改前」的快照。
     *
     * @param file 被修改的文件
     * @param content 修改前的内容
     * @param note 本次改动的说明（来自工具的 backup_note），写进快照记录方便回溯
     */
    @Synchronized
    fun saveSnapshot(file: File, content: String, note: String = "") {
        try {
            val abs = file.absolutePath
            val safeName = abs.replace(Regex("[/\\\\]"), "_")
            val snapFile = File(rootDir, "${System.currentTimeMillis()}_$safeName")
            AtomicFile.writeText(snapFile, content, createParent = true)

            snapshots += Snapshot(
                type = TYPE_FILE,
                path = abs,
                file = snapFile.absolutePath,
                timestamp = System.currentTimeMillis(),
                note = note,
            )

            val fileCount = snapshots.count { it.type == TYPE_FILE }
            if (fileCount > MAX_SNAPSHOTS) cleanup() else flushIndex()
        } catch (_: Throwable) {
            // 快照失败不能阻塞写操作本身
        }
    }

    // ── 分组 ──────────────────────────────────────────────────────

    /** 开始一个撤销分组（多文件操作前调用） */
    @Synchronized
    fun beginGroup() {
        snapshots += Snapshot(TYPE_GROUP_START, null, null, System.currentTimeMillis())
        flushIndex()
    }

    /** 结束分组。组内没有任何文件快照时，把 group_start 也撤掉（不留空组）。 */
    @Synchronized
    fun endGroup() {
        var startIdx = -1
        for (i in snapshots.indices.reversed()) {
            if (snapshots[i].type == TYPE_GROUP_END) break
            if (snapshots[i].type == TYPE_GROUP_START) {
                startIdx = i
                break
            }
        }
        if (startIdx < 0) return
        val hasFile = snapshots.drop(startIdx + 1).any { it.type == TYPE_FILE }
        if (!hasFile) {
            snapshots.removeAt(startIdx)
        } else {
            snapshots += Snapshot(TYPE_GROUP_END, null, null, System.currentTimeMillis())
        }
        flushIndex()
    }

    // ── 撤销 ──────────────────────────────────────────────────────

    /**
     * 撤销最近一次操作。
     *
     * 优先撤销最近的**分组**（整组一起回滚，避免破碎状态）；
     * 没有分组时回滚最近一个文件快照。
     *
     * @return 每个被回滚文件的说明（供 UI 展示）
     */
    @Synchronized
    fun undo(): List<String> {
        // 情况 1：有完整分组
        var groupEndIdx = -1
        for (i in snapshots.indices.reversed()) {
            if (snapshots[i].type == TYPE_GROUP_END) {
                groupEndIdx = i
                break
            }
        }
        if (groupEndIdx >= 0) {
            var startIdx = 0
            for (i in groupEndIdx - 1 downTo 0) {
                if (snapshots[i].type == TYPE_GROUP_START) {
                    startIdx = i
                    break
                }
            }
            val files = snapshots.subList(startIdx + 1, groupEndIdx).filter { it.type == TYPE_FILE }
            val results = mutableListOf<String>()
            // 逆序回滚（后改的先还原）
            files.reversed().forEach { f ->
                if (restoreOne(f)) results += "回滚: ${f.path}"
            }
            // 移除整组（含首尾标记）
            repeat(groupEndIdx - startIdx + 1) { snapshots.removeAt(startIdx) }
            flushIndex()
            return results
        }

        // 情况 2：单文件快照（从后往前找第一个能还原的）
        for (i in snapshots.indices.reversed()) {
            val s = snapshots[i]
            if (s.type != TYPE_FILE) continue
            val ok = restoreOne(s)
            snapshots.removeAt(i)
            flushIndex()
            return if (ok) listOf("回滚: ${s.path}") else listOf("快照已失效，已移除: ${s.path}")
        }
        return emptyList()
    }

    private fun restoreOne(s: Snapshot): Boolean {
        return try {
            val snapPath = s.file ?: return false
            val target = s.path ?: return false
            val snapFile = File(snapPath)
            if (!snapFile.exists()) return false
            val content = snapFile.readText()
            AtomicFile.writeText(File(target), content, createParent = true)
            // 回滚后刷新版本记录，否则后续 Write 会被「旧版本」误拦
            FileVersionTracker.track(File(target))
            snapFile.delete()
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ── 清理 ──────────────────────────────────────────────────────

    /** 删除过期（7 天）+ 超出上限（300 条）的快照 */
    @Synchronized
    fun cleanup() {
        try {
            val now = System.currentTimeMillis()
            val expired = mutableSetOf<String>()

            snapshots.filter { it.type == TYPE_FILE }.forEach { s ->
                if (now - s.timestamp > TTL_MS) {
                    s.file?.let { expired += it }
                    File(s.file ?: "").delete()
                }
            }

            val alive = snapshots.filter { it.type == TYPE_FILE && it.file !in expired }
            if (alive.size > MAX_SNAPSHOTS) {
                alive.take(alive.size - MAX_SNAPSHOTS).forEach { s ->
                    s.file?.let { expired += it }
                    File(s.file ?: "").delete()
                }
            }
            if (expired.isEmpty()) return

            snapshots.removeAll { it.type == TYPE_FILE && it.file in expired }
            pruneEmptyGroups()
            flushIndex()
        } catch (_: Throwable) {
            // 清理失败不阻塞
        }
    }

    /** 移除没有文件内容的空分组（否则 undo 会回滚一个空组，看起来"没反应"） */
    private fun pruneEmptyGroups() {
        val keep = mutableListOf<Snapshot>()
        var i = 0
        while (i < snapshots.size) {
            val s = snapshots[i]
            if (s.type != TYPE_GROUP_START) {
                if (s.type == TYPE_GROUP_END || s.type == TYPE_FILE) keep += s
                i++
                continue
            }
            var hasFile = false
            var endIdx = -1
            for (j in i + 1 until snapshots.size) {
                if (snapshots[j].type == TYPE_GROUP_END) {
                    endIdx = j
                    break
                }
                if (snapshots[j].type == TYPE_FILE) hasFile = true
            }
            if (hasFile) keep += s else i = if (endIdx >= 0) endIdx else i
            i++
        }
        // 二次扫描：保证 start/end 配对（防止删空组后残留孤立的 end）
        val cleaned = mutableListOf<Snapshot>()
        var depth = 0
        keep.forEach { s ->
            when (s.type) {
                TYPE_GROUP_START -> {
                    depth++
                    cleaned += s
                }
                TYPE_GROUP_END -> {
                    if (depth > 0) {
                        depth--
                        cleaned += s
                    }
                }
                else -> cleaned += s
            }
        }
        snapshots.clear()
        snapshots += cleaned
    }

    // ── 查询 ──────────────────────────────────────────────────────

    /** 列出可回滚的快照（最新在前），供 /undo 面板展示 */
    @Synchronized
    fun list(maxCount: Int = MAX_SNAPSHOTS): List<Map<String, String>> =
        snapshots.filter { it.type == TYPE_FILE }
            .takeLast(maxCount)
            .reversed()
            .map { s ->
                mapOf(
                    "file" to (s.path ?: ""),
                    "snapshotPath" to (s.file ?: ""),
                    "timestamp" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                        .format(java.util.Date(s.timestamp)),
                    "note" to s.note,
                )
            }

    @Synchronized
    fun snapshotCount(): Int = snapshots.count { it.type == TYPE_FILE }

    @Synchronized
    fun rootPath(): String = rootDir.absolutePath
}
