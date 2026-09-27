package com.ccm.app.tools.file

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 回收站 —— 大改动前自动备份旧版本。
 *
 * ══════════════════════════════════════════════════════════════
 *  备份文件命名（与 CCM 完全一致，便于用户理解）
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 * ♻原名.时间戳.hash前6位.描述
 * 例：♻agent.mjs.20260827-114141.a3f9c2.修超时逻辑
 * ```
 *
 * ══════════════════════════════════════════════════════════════
 *  CCM 踩过的两个坑（这里都已修正）
 * ══════════════════════════════════════════════════════════════
 *
 * **坑 1：manifest 没记录 → 恢复错位置**
 * 原来恢复时盲目还原到 `process.cwd()`，从别处删的文件全堆到项目根目录。
 * 修法：备份时把「备份名 → 原始绝对路径」写进 manifest，恢复优先用它。
 *
 * **坑 2：清理从不触发 + 按字母序排会删错**
 *   · `pruneTrash()` 原来只在「rm 备份」路径调，而日常绝大多数备份来自
 *     Write/Edit 的 `backupBeforeOverwrite` → 清理从不触发，实测攒到 83 个（上限 50）。
 *   · 文件名是 `♻原名.时间戳.hash`，**原名在时间戳前面**，
 *     字母序排出来 `♻edge-tts.mjs.20260827` 会排在 `♻world.js.20260812` 前面 ——
 *     取"最旧"时实际删掉的是新文件。必须按**时间戳段**排序。
 *
 * 【线程安全】所有公开方法都 synchronized —— 工具可能并行执行。
 */
class TrashStore(private val rootDir: File) {

    companion object {
        /** 备份文件名前缀 */
        const val PREFIX = "♻"

        /** 回收站文件数上限，超出删最旧的 */
        const val MAX_TRASH_FILES = 200

        /** 修改量超过这个字符数才备份（小改动不值得占空间） */
        const val MIN_DIFF_TO_BACKUP = 200

        /** 匹配 ♻原名.时间戳.hash.描述（描述可空） */
        private val NAME_RE = Regex("^♻(.+)\\.(\\d{8}-\\d{6})\\.([a-f0-9]{6})(?:\\.(.*))?$")

        /** 从文件名里抠时间戳段（20260827-114141） */
        private val TIME_RE = Regex("\\.(\\d{8}-\\d{6})\\.")
    }

    /** 回收站条目 */
    data class Entry(
        val index: Int,          // 1-based，用于 restore
        val trashName: String,   // 回收站里的文件名
        val originalName: String,
        val originalPath: String?,
        val timestamp: String,
        val hash: String,
        val description: String,
        val sizeBytes: Long,
    )

    private val trashDir: File get() = File(rootDir, "trash")
    private val manifestFile: File get() = File(rootDir, "trash-manifest.json")

    // ── manifest ──────────────────────────────────────────────────

    private fun loadManifest(): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            if (!manifestFile.exists()) return map
            val json = JSONObject(manifestFile.readText())
            json.keys().forEach { k -> map[k] = json.optString(k, "") }
        } catch (_: Throwable) {
            // manifest 损坏不阻塞主流程 —— 退化成「恢复时按原名放当前目录」
        }
        return map
    }

    private fun saveManifest(manifest: Map<String, String>) {
        try {
            val json = JSONObject()
            manifest.forEach { (k, v) -> json.put(k, v) }
            ensureDir()
            AtomicFile.writeText(manifestFile, json.toString())
        } catch (_: Throwable) {
            // 写不了 manifest 也要让备份本身成功（备份比记录重要）
        }
    }

    private fun ensureDir() {
        if (!trashDir.exists()) trashDir.mkdirs()
    }

    // ── 命名 ──────────────────────────────────────────────────────

    private fun shortHash(content: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(content.toByteArray(Charsets.UTF_8))
        return digest.take(3).joinToString("") { "%02x".format(it) }  // 6 个 hex 字符
    }

    private fun timestampStr(): String {
        val d = java.util.Calendar.getInstance()
        val p = { n: Int -> String.format("%02d", n) }
        return "${d.get(java.util.Calendar.YEAR)}${p(d.get(java.util.Calendar.MONTH) + 1)}" +
            "${p(d.get(java.util.Calendar.DAY_OF_MONTH))}-" +
            "${p(d.get(java.util.Calendar.HOUR_OF_DAY))}${p(d.get(java.util.Calendar.MINUTE))}" +
            "${p(d.get(java.util.Calendar.SECOND))}"
    }

    private fun sanitizeDesc(desc: String?): String {
        if (desc.isNullOrBlank()) return ""
        val cleaned = desc.trim()
            .replace(Regex("[/\\\\:*?\"<>|\\n\\r\\t]"), "_")
            .replace(Regex("\\s+"), "_")
            .take(40)
        return if (cleaned.isNotEmpty()) ".$cleaned" else ""
    }

    private fun makeTrashName(file: File, content: String, desc: String): String =
        "$PREFIX${file.name}.${timestampStr()}.${shortHash(content)}${sanitizeDesc(desc)}"

    /**
     * 计算两段文本的差异字符数（行级近似）。
     *
     * 不做真正的 LCS（太慢），用「滑动窗口找最近匹配行」近似。
     * 用途只是「判断改动够不够大、值不值得备份」，不需要精确。
     */
    fun diffSize(oldStr: String, newStr: String): Int {
        if (oldStr == newStr) return 0
        val oldLines = oldStr.split("\n")
        val newLines = newStr.split("\n")
        var diff = 0
        var i = 0
        var j = 0
        while (i < oldLines.size && j < newLines.size) {
            if (oldLines[i] == newLines[j]) {
                i++; j++
            } else {
                var found = false
                val limit = minOf(i + 5, oldLines.size)
                for (k in i until limit) {
                    if (oldLines[k] == newLines[j]) {
                        diff += oldLines.subList(i, k).joinToString("\n").length
                        i = k + 1
                        found = true
                        break
                    }
                }
                if (!found) {
                    diff += newLines[j].length
                    j++
                }
            }
        }
        if (i < oldLines.size) diff += oldLines.subList(i, oldLines.size).joinToString("\n").length
        if (j < newLines.size) diff += newLines.subList(j, newLines.size).joinToString("\n").length
        return diff
    }

    // ── 备份 ──────────────────────────────────────────────────────

    /** 备份整个文件（rm / 删除场景用，总是备份）。返回备份文件，失败/源不存在返回 null。 */
    @Synchronized
    fun backup(file: File, desc: String = ""): File? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val content = file.readText()
            writeBackup(file, content, desc)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 备份即将被覆盖的文件（Write/Edit 前调用）。
     *
     * 只在修改量超过 [MIN_DIFF_TO_BACKUP] 时备份 —— 改个错别字也备份会把
     * 回收站塞满，反而淹没了真正重要的版本。
     */
    @Synchronized
    fun backupBeforeOverwrite(file: File, newContent: String, desc: String = ""): File? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val oldContent = file.readText()
            if (oldContent == newContent) return null
            if (diffSize(oldContent, newContent) < MIN_DIFF_TO_BACKUP) return null
            writeBackup(file, oldContent, desc)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeBackup(file: File, content: String, desc: String): File {
        ensureDir()
        val trashName = makeTrashName(file, content, desc)
        val trashFile = File(trashDir, trashName)
        AtomicFile.writeText(trashFile, content, createParent = true)

        // ⚠️ 必须记 manifest：否则恢复时找不到原始位置（CCM 踩过的坑 1）
        val manifest = loadManifest()
        manifest[trashName] = file.absolutePath
        saveManifest(manifest)

        // ⚠️ 必须在这里也调清理：日常备份都走这条路（CCM 踩过的坑 2）
        prune()
        return trashFile
    }

    /**
     * 清理超出上限的备份，删最旧的。
     *
     * ⚠️ 排序必须按**时间戳段**，不能按文件名字母序 ——
     * 原名在时间戳前面，字母序会把「新的」排到「旧的」前面，删错文件。
     */
    @Synchronized
    fun prune() {
        try {
            ensureDir()
            val files = trashDir.listFiles()?.filter { it.name.startsWith(PREFIX) } ?: return
            if (files.size <= MAX_TRASH_FILES) return

            val sorted = files.sortedBy { trashTimeKey(it.name) }
            val drop = sorted.take(sorted.size - MAX_TRASH_FILES)
            val manifest = loadManifest()
            drop.forEach { f ->
                runCatching { f.delete() }
                manifest.remove(f.name)
            }
            saveManifest(manifest)
        } catch (_: Throwable) {
            // 清理失败不阻塞主流程
        }
    }

    /** 取文件名里的时间戳段（20260827-114141）。取不到返回 0（当最旧处理，优先清）。 */
    private fun trashTimeKey(name: String): Long {
        val m = TIME_RE.find(name) ?: return 0L
        return m.groupValues[1].replace("-", "").toLongOrNull() ?: 0L
    }

    // ── 查询 / 恢复 ────────────────────────────────────────────────

    /** 列出回收站（按时间倒序，最新在前 —— 与 restore 的序号一致） */
    @Synchronized
    fun list(): List<Entry> {
        ensureDir()
        val files = trashDir.listFiles()?.filter { it.name.startsWith(PREFIX) } ?: return emptyList()
        val manifest = loadManifest()
        return files
            .sortedByDescending { trashTimeKey(it.name) }
            .mapIndexed { i, f ->
                val m = NAME_RE.find(f.name)
                val ts = m?.groupValues?.get(2) ?: "?"
                Entry(
                    index = i + 1,
                    trashName = f.name,
                    originalName = m?.groupValues?.get(1) ?: f.name.removePrefix(PREFIX),
                    originalPath = manifest[f.name],
                    timestamp = formatTimestamp(ts),
                    hash = m?.groupValues?.get(3) ?: "?",
                    description = m?.groupValues?.get(4)?.takeIf { it.isNotBlank() } ?: "",
                    sizeBytes = f.length(),
                )
            }
    }

    private fun formatTimestamp(ts: String): String {
        if (ts.length < 15) return ts
        return "${ts.substring(0, 4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)} " +
            "${ts.substring(9, 11)}:${ts.substring(11, 13)}:${ts.substring(13, 15)}"
    }

    /** 供 /trash 面板展示的文本列表 */
    @Synchronized
    fun listText(): String {
        val entries = list()
        if (entries.isEmpty()) return "(回收站为空)"
        return buildString {
            appendLine("回收站内容:")
            entries.forEach { e ->
                val desc = if (e.description.isNotEmpty()) "  「${e.description}」" else ""
                appendLine("  ${e.index}. ${e.originalName}  ${e.timestamp}  ${e.sizeBytes}B  #${e.hash}$desc")
            }
            appendLine()
            append("恢复: /trash restore <序号>")
        }
    }

    /**
     * 恢复指定序号的备份。
     *
     * 优先按 manifest 的原始路径恢复；manifest 缺失则退回 [fallbackDir]。
     * 目标已存在时先把它自己备份一遍（否则恢复 = 又一次静默覆盖）。
     */
    @Synchronized
    fun restore(index: Int, fallbackDir: File): String {
        val entries = list()
        if (index < 1 || index > entries.size) return "序号无效，用 /trash 查看列表"
        val entry = entries[index - 1]
        val trashFile = File(trashDir, entry.trashName)
        if (!trashFile.exists()) return "备份文件已不存在：${entry.trashName}"

        val dest = entry.originalPath?.let { File(it) }
            ?: File(fallbackDir, entry.originalName)

        var note = ""
        if (dest.exists()) {
            backup(dest, "恢复前备份")
            note = "\n  注意: ${entry.originalName} 已存在，已备份当前版本"
        }
        dest.parentFile?.mkdirs()
        return try {
            trashFile.copyTo(dest, overwrite = true)
            "已恢复 ${entry.originalName} (${dest.length()}B)$note"
        } catch (e: Throwable) {
            "恢复失败：${e.message}"
        }
    }

    /** 清空回收站 */
    @Synchronized
    fun clear(): String {
        ensureDir()
        val files = trashDir.listFiles()?.filter { it.name.startsWith(PREFIX) } ?: return "回收站已是空的"
        if (files.isEmpty()) return "回收站已是空的"
        var count = 0
        files.forEach { if (runCatching { it.delete() }.getOrDefault(false)) count++ }
        saveManifest(emptyMap())
        return "已清空回收站 ($count 个文件)"
    }

    /** 当前备份数量（诊断用） */
    @Synchronized
    fun count(): Int = trashDir.listFiles()?.count { it.name.startsWith(PREFIX) } ?: 0
}
