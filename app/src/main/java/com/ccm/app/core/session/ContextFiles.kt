package com.ccm.app.core.session

import java.io.File

/**
 * 上下文文件追踪（对齐 CLI `core/session/context-files.mjs`，官方 /files）。
 *
 * ══════════════════════════════════════════════════════════════
 *  【2026-10-06 移植】原来 APK 的 /files 是「列工作区目录」——
 *  与 CLI 的语义**完全不同**：
 *    · CLI /files   = **当前上下文里读/写过的文件**（Agent 的记忆）
 *    · APK 原来     = 工作区目录列表（文件管理器的活）
 *  用户想看「这个会话碰过哪些文件」时，APK 给不了。
 *
 *  官方在 ToolUseContext 里维护 readFileState，/files 列它的 key。
 *  CLI 独立维护一份（只记路径和元信息，不存内容 —— 存内容等于把
 *  上下文又复制一份）。
 * ══════════════════════════════════════════════════════════════
 *
 * 用法：工具执行后调 [record]，/files 命令读 [list]。
 */
class ContextFiles(
    private val cwd: String,
    private val max: Int = 500,
) {

    companion object {
        /** 会把文件内容带进上下文的工具 */
        private val READ_TOOLS = setOf(
            "Read", "HashlineRead", "ViewImage", "ReadFile", "FileRead",
        )

        /** 会改文件的工具（改动内容同样在对话里，也算） */
        private val WRITE_TOOLS = setOf(
            "Write", "Edit", "MultiEdit", "HashlineEdit", "ApplyPatch", "SafeRename",
        )
    }

    data class Entry(
        val path: String,
        /** read / write —— 写过会覆盖 read 标记（写更重要） */
        val kind: String,
        val tool: String,
        val count: Int,
        val seq: Long,
    )

    private val files = LinkedHashMap<String, Entry>()   // 保持插入序 = LRU
    private var seq = 0L

    /**
     * 记录一次工具调用（不是读/写类工具则忽略）。
     *
     * @param toolName 工具名
     * @param input 工具入参（取 file_path / path）
     */
    @Synchronized
    fun record(toolName: String, input: Map<String, Any?>?) {
        val isRead = toolName in READ_TOOLS
        val isWrite = toolName in WRITE_TOOLS
        if (!isRead && !isWrite) return
        val raw = (input?.get("file_path") ?: input?.get("path")) as? String
        if (raw.isNullOrBlank()) return
        val abs = if (File(raw).isAbsolute) raw else File(cwd, raw).absolutePath

        val prev = files[abs]
        val entry = Entry(
            path = abs,
            kind = if (isWrite) "write" else (prev?.kind ?: "read"),
            tool = toolName,
            count = (prev?.count ?: 0) + 1,
            seq = ++seq,
        )
        files.remove(abs)      // 重新插入以维持 LRU 顺序
        files[abs] = entry
        while (files.size > max) {
            val oldest = files.keys.firstOrNull() ?: break
            files.remove(oldest)
        }
    }

    /** 全部条目（按 LRU 序，最近用的在后）。 */
    @Synchronized
    fun list(): List<Entry> = files.values.toList()

    /** 清空（对应 CLI 的 `/files reset`）。 */
    @Synchronized
    fun reset() {
        files.clear()
        seq = 0
    }

    /**
     * 格式化成展示文本（对齐 CLI `formatContextFiles`）。
     * 路径转成相对 cwd 的形式（绝对路径太长，手机上换行难看）。
     */
    @Synchronized
    fun format(): String {
        if (files.isEmpty()) return "（本会话还没读过/写过文件）"
        val cwdFile = File(cwd)
        return files.values.toList().asReversed().joinToString("\n") { e ->
            val rel = try {
                cwdFile.toURI().relativize(File(e.path).toURI()).path.ifBlank { e.path }
            } catch (_: Throwable) { e.path }
            val mark = if (e.kind == "write") "✎" else "·"
            "$mark $rel${if (e.count > 1) " (${e.count}次)" else ""}"
        }
    }
}
