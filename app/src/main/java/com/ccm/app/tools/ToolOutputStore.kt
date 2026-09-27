package com.ccm.app.tools

import java.io.File

/**
 * 工具结果落盘与截断。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么要截断
 * ══════════════════════════════════════════════════════════════
 *
 * 模型上下文是有限资源。一次 `cat` 一个 10MB 的日志文件，
 * 如果原样塞进对话，整轮上下文直接爆掉 —— 而且模型**根本读不完**。
 *
 * 做法（对齐 CCM `core/tools.mjs:truncateResult()`）：
 * ```
 * 前 50%  ← 保留（结论通常在这里）
 * ... [内容超限，省略 N 行 / 共 M 字符，完整结果已写 <路径>] ...
 * 后 30%  ← 保留（错误信息通常在尾部）
 * ```
 * 完整结果写盘，模型需要时可以用 Read 去读指定行范围。
 *
 * ══════════════════════════════════════════════════════════════
 *  CCM 踩过的坑：结构化结果不能走字符串截断
 * ══════════════════════════════════════════════════════════════
 *
 * 原实现开头有一句「不是字符串就 `String(content)`」，于是 `phone_screenshot`
 * 这类返回 `{__type:'vision', images:[...]}` 的工具，到这里变成 `"[object Object]"`
 * —— 只有 15 个字符，**长度远小于上限，会原样返回**，连截断日志都没有。
 *
 * 后果不只是显示难看：`__type` 被抹掉后，识图旁路永远不触发 ——
 * **截图功能整个失效**，而调用方看到的是 `ok:true`，没有任何报错。
 *
 * Kotlin 侧的对应设计：[com.ccm.app.core.tool.ToolResult.Success.attachments]
 * 是**独立字段**，不参与文本截断。所以这个类只处理 `content` 字符串，
 * 附件由 Agent 循环单独走多模态通道。
 *
 * 【上限对齐】默认 30000 字符，上限 150000（与 CCM / 官方一致）。
 */
class ToolOutputStore(private val rootDir: File) {

    companion object {
        /** 默认截断阈值（字符） */
        const val DEFAULT_MAX_CHARS = 30_000

        /** 用户可配置的上限（防止配成天文数字把上下文撑爆） */
        const val UPPER_LIMIT = 150_000

        /** 落盘文件硬上限：1GB（对齐官方 2.1.265 的 tool result cap） */
        const val MAX_SAVED_BYTES = 1024L * 1024 * 1024
    }

    /** 输出目录 */
    private val outputDir: File get() = File(rootDir, "tool-output")

    /** 全局上限（可由设置覆盖） */
    @Volatile
    var maxChars: Int = DEFAULT_MAX_CHARS

    /**
     * 设置全局上限（越界值会被夹到 [1, UPPER_LIMIT]）。
     * @return 生效值
     */
    fun setMaxChars(value: Int): Int {
        maxChars = value.coerceIn(1, UPPER_LIMIT)
        return maxChars
    }

    /**
     * 按需截断。未超限时原样返回（零开销）。
     *
     * @param content 结果文本
     * @param limit 该工具自己的上限（null = 用全局）
     * @param toolName 工具名（写进落盘文件名，便于排查）
     */
    fun truncate(content: String, limit: Int? = null, toolName: String = "tool"): String {
        val cap = (limit ?: maxChars).coerceIn(1, UPPER_LIMIT)
        if (content.length <= cap) return content

        val saved = saveToDisk(content, toolName)

        val headLen = (cap * 0.5).toInt()
        val tailLen = (cap * 0.3).toInt()
        val head = content.substring(0, headLen)
        val tail = if (saved.complete) {
            content.substring(content.length - tailLen)
        } else {
            ""   // 文件被 1GB 截断时尾部不在磁盘上，保留它会误导
        }
        val omittedEnd = if (saved.complete) content.length - tailLen else content.length
        val omitted = if (omittedEnd > headLen) content.substring(headLen, omittedEnd) else ""
        val omittedLines = if (omitted.isEmpty()) 0 else omitted.count { it == '\n' } + 1

        val note = buildString {
            append("... [内容超限，省略 $omittedLines 行 / 共 ${content.length} 字符，")
            append("完整结果已写 ${saved.path}")
            if (!saved.complete) append("（超过 1GB 上限，仅前 1GB 落盘）")
            append("] ...")
        }
        return "$head\n\n$note\n\n$tail"
    }

    private data class SaveResult(val path: String, val complete: Boolean)

    private fun saveToDisk(content: String, toolName: String): SaveResult {
        val dir = outputDir.apply { if (!exists()) mkdirs() }
        val safeName = toolName.replace(Regex("[^A-Za-z0-9_\\-]"), "_")
        val file = File(dir, "tool-$safeName-${System.currentTimeMillis() % 100_000}.txt")
        return try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_SAVED_BYTES) {
                file.writeBytes(bytes.copyOfRange(0, MAX_SAVED_BYTES.toInt()))
                SaveResult(file.absolutePath, false)
            } else {
                file.writeBytes(bytes)
                SaveResult(file.absolutePath, true)
            }
        } catch (_: Throwable) {
            // 写盘失败不能反过来把工具调用搞挂 —— 返回空路径，模型仍能看到头尾摘要
            SaveResult("(写盘失败)", true)
        }
    }

    /** 清理超过 [keepDays] 天的落盘结果（避免长期占用存储） */
    fun cleanup(keepDays: Int = 7): Int {
        return try {
            val cutoff = System.currentTimeMillis() - keepDays * 24L * 3600_000L
            val files = outputDir.listFiles() ?: return 0
            var removed = 0
            files.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff && f.delete()) removed++
            }
            removed
        } catch (_: Throwable) {
            0
        }
    }

    /** 当前落盘文件数（诊断用） */
    fun fileCount(): Int = outputDir.listFiles()?.count { it.isFile } ?: 0
}
