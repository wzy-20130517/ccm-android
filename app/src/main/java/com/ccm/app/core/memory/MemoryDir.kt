package com.ccm.app.core.memory

import java.io.File

/**
 * 结构化记忆库 —— 移植自 CLI `core/infra/memdir.mjs`。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * ## 与 CLAUDE.md 的区别（重要）
 *
 * | | CLAUDE.md | 本模块（memories/） |
 * |---|---|---|
 * | 注入方式 | **每轮全量注入**系统提示词 | **按需检索**（`/mem find`）|
 * | 用途 | 项目总纲、长期约定 | 零散经验、用户反馈、参考资料 |
 * | 结构 | 自由 markdown | **frontmatter**（name/description/type）|
 * | 容量 | 有限（会顶上下文） | 无上限（不注入） |
 *
 * CLI 把这两个**分开**（`/memory` 管 CLAUDE.md，`/mem` 管结构化记忆）——
 * APK 原来把它们混为一谈（同一个命令分支），是功能缺失。
 *
 * ## 存储
 *
 * `files/memory/`（对应 CLI 的 `~/.claude-code-mobile/memory/`）
 * 每条记忆是一个 `.md` 文件，带 frontmatter：
 * ```markdown
 * ---
 * name: tone
 * description: 用户要求直接
 * type: feedback
 * ---
 *
 * 不要客套话
 * ```
 *
 * type 四种：`user`（用户信息）/ `feedback`（对做事方式的要求）/
 * `project`（项目相关）/ `reference`（参考资料）
 * ═══════════════════════════════════════════════════════════════
 */
object MemoryDir {

    /** 记忆类型。 */
    val MEMORY_TYPES = listOf("user", "feedback", "project", "reference")

    /** 入口文件名（长期总纲）。 */
    const val MEMORY_ENTRYPOINT = "MEMORY.md"

    /** 一条记忆。 */
    data class Memory(
        val path: String,
        val rel: String,
        val name: String,
        val description: String,
        val type: String,
        val body: String,
        val mtime: Long,
        val score: Double = 0.0,
    )

    /** 解析 frontmatter。 */
    fun parseFrontmatter(text: String): Pair<Map<String, String>, String> {
        val m = Regex("^---\\r?\\n([\\s\\S]*?)\\r?\\n---\\r?\\n?").find(text)
            ?: return emptyMap<String, String>() to text.trim()
        val meta = mutableMapOf<String, String>()
        m.groupValues[1].split(Regex("\\r?\\n")).forEach { line ->
            val kv = Regex("^([A-Za-z_][\\w-]*)\\s*:\\s*(.*)$").find(line.trim()) ?: return@forEach
            var v = kv.groupValues[2].trim()
            if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length - 1)
            }
            meta[kv.groupValues[1]] = v
        }
        return meta to text.substring(m.value.length).trim()
    }

    /** 生成带 frontmatter 的文件内容。 */
    fun buildMemoryFile(name: String, description: String = "", type: String = "reference", body: String = ""): String {
        val t = if (type in MEMORY_TYPES) type else "reference"
        fun esc(v: String) = v.replace(Regex("\\r?\\n"), " ").trim()
        return "---\nname: ${esc(name)}\ndescription: ${esc(description)}\ntype: $t\n---\n\n${body.trim()}\n"
    }

    /** 递归列出所有记忆（不含入口）。 */
    fun listMemories(dir: File): List<Memory> {
        if (!dir.exists()) return emptyList()
        val out = mutableListOf<Memory>()
        fun walk(d: File, isRoot: Boolean) {
            val items = try { d.listFiles() } catch (_: Throwable) { null } ?: return
            for (f in items) {
                if (f.isDirectory) { walk(f, false); continue }
                if (!f.name.endsWith(".md")) continue
                if (isRoot && f.name == MEMORY_ENTRYPOINT) continue
                val raw = try { f.readText() } catch (_: Throwable) { continue }
                val (meta, body) = parseFrontmatter(raw)
                out += Memory(
                    path = f.absolutePath,
                    rel = f.relativeTo(dir).path,
                    name = meta["name"] ?: f.name.removeSuffix(".md"),
                    description = meta["description"] ?: "",
                    type = if (meta["type"] in MEMORY_TYPES) meta["type"]!! else "reference",
                    body = body,
                    mtime = f.lastModified(),
                )
            }
        }
        walk(dir, true)
        return out.sortedByDescending { it.mtime }
    }

    /** 读入口文件。 */
    fun readEntrypoint(dir: File): String? {
        val f = File(dir, MEMORY_ENTRYPOINT)
        if (!f.exists()) return null
        return try { f.readText().trim() } catch (_: Throwable) { null }
    }

    /** 写一条记忆。 */
    fun saveMemory(dir: File, rel: String, name: String, description: String, type: String, body: String): Pair<String, String> {
        val relPath = rel.ifBlank { name }.trim().trimStart('/')
        if (relPath.isBlank()) throw IllegalArgumentException("记忆需要一个名字或路径")
        // 防目录穿越
        if (relPath.split('/', '\\').contains("..")) throw IllegalArgumentException("路径不合法")
        val fileName = if (relPath.endsWith(".md")) relPath else "$relPath.md"
        val abs = File(dir, fileName)
        abs.parentFile?.mkdirs()
        abs.writeText(buildMemoryFile(name.ifBlank { abs.name.removeSuffix(".md") }, description, type, body))
        return abs.absolutePath to fileName
    }

    /** 删一条记忆。 */
    fun deleteMemory(dir: File, rel: String): Boolean {
        val f = rel.trim()
        if (f.isBlank() || f.split('/', '\\').contains("..")) throw IllegalArgumentException("路径不合法")
        val abs = File(dir, if (f.endsWith(".md")) f else "$f.md")
        if (!abs.exists()) return false
        return abs.delete()
    }

    /**
     * 中英文分词（用于相关性打分）。
     *
     * 中文**只用两字组合（bigram）**，不收单字：
     * 单字误命中率高得离谱 —— "完全无关的量子纠缠" 里的「全」会命中"全屏"。
     * bigram 保留了中文最小语义单元。
     * 单字查询（如只打一个"图"）不会有结果，这是可接受的取舍。
     */
    private fun terms(text: String): Set<String> {
        val s = text.lowercase()
        val latin = Regex("[a-z0-9_]{2,}").findAll(s).map { it.value }.toList()
        val bi = mutableListOf<String>()
        Regex("[\\u4e00-\\u9fa5]+").findAll(s).forEach { m ->
            val run = m.value
            if (run.length == 1) { bi += run; return@forEach }
            for (i in 0 until run.length - 1) bi += run.substring(i, i + 2)
        }
        return (latin + bi).toSet()
    }

    /**
     * 按查询挑相关记忆（本地打分）。
     * 权重：name(3) > description(2) > body(1)，命中不重复计。
     */
    fun findRelevantMemories(dir: File, query: String, limit: Int = 5, minScore: Double = 2.0): List<Memory> {
        val q = terms(query)
        if (q.isEmpty()) return emptyList()
        val scored = mutableListOf<Memory>()
        for (mem in listMemories(dir)) {
            val nameT = terms(mem.name)
            val descT = terms(mem.description)
            val bodyT = terms(mem.body)
            var score = 0.0
            for (t in q) {
                score += when {
                    nameT.contains(t) -> 3.0
                    descT.contains(t) -> 2.0
                    bodyT.contains(t) -> 1.0
                    else -> 0.0
                }
            }
            // feedback 类是「用户对做事方式的要求」，同分时优先
            if (mem.type == "feedback") score += 0.5
            if (score >= minScore) scored += mem.copy(score = score)
        }
        return scored.sortedWith(compareByDescending<Memory> { it.score }.thenByDescending { it.mtime }).take(limit)
    }

    /** 渲染成可注入 prompt 的文本。 */
    fun formatMemoriesForPrompt(memories: List<Memory>): String {
        if (memories.isEmpty()) return ""
        val out = mutableListOf("<memories>")
        for (m in memories) {
            out += "<memory name=\"${m.name}\" type=\"${m.type}\">"
            if (m.description.isNotBlank()) out += m.description
            if (m.body.isNotBlank()) out += m.body
            out += "</memory>"
        }
        out += "</memories>"
        return out.joinToString("\n")
    }

    /** 给用户看的清单。 */
    fun formatMemoryList(memories: List<Memory>, dir: File): String {
        if (memories.isEmpty()) return "记忆库是空的（${dir.absolutePath}）"
        val sb = StringBuilder()
        sb.append("记忆 ${memories.size} 条  ${dir.absolutePath}\n")
        for (type in MEMORY_TYPES) {
            val items = memories.filter { it.type == type }
            if (items.isEmpty()) continue
            sb.append("\n$type:\n")
            for (m in items) {
                val score = if (m.score > 0) "  [${m.score}]" else ""
                sb.append("  ${m.rel}$score\n")
                if (m.description.isNotBlank()) sb.append("    ${m.description}\n")
            }
        }
        return sb.toString()
    }
}
