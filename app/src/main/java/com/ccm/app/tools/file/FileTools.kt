package com.ccm.app.tools.file

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 文件工具集 —— Read / Write / Edit / MultiEdit。
 *
 * 参照 Node 版 `core/file-tools.mjs`（245 行），逐条对齐行为。
 *
 * ══════════════════════════════════════════════════════════════
 *  依赖注入（为什么不从 ctx.storage 取）
 * ══════════════════════════════════════════════════════════════
 *
 * `ToolContext.storage` 的类型（`ToolStorage`）由 dev-core 定义、目前尚未落盘。
 * 为了不阻塞进度，这些工具把 [TrashStore] / [UndoStore] 作为**构造参数**注入 ——
 * 这样：
 *   1. 现在就能写、能测（不依赖未定的接口）
 *   2. 将来 storage 定了，在装配处 `FileTools(storage.trash, storage.undo)` 一行接上
 *   3. 单元测试可以直接注入临时目录，不需要造整个 App 环境
 *
 * ══════════════════════════════════════════════════════════════
 *  并发写保护（CCM 踩过的最难查的坑）
 * ══════════════════════════════════════════════════════════════
 *
 * 场景：worker-A Read → worker-B 改并写 → worker-A 拿旧内容 Write
 * → **B 的工作静默消失**，退出码 0，无任何告警。
 *
 * 做法：Read 记录 `mtime+size`，Write/Edit 覆盖前比对，不一致就拒写。
 * 细节见 [FileVersionTracker]。
 */
class FileTools(
    private val trashStore: TrashStore,
    private val undoStore: UndoStore,
) {

    /** 解析路径：相对路径基于 ctx.cwd，并做越界检查 */
    private fun resolve(input: JsonObject, ctx: ToolContext, key: String = "file_path"): File {
        val raw = input.str(key) ?: throw IllegalArgumentException("$key is required")
        val cwd = File(ctx.cwd)
        val extras = ctx.extraDirs.map { File(it) }
        return PathGuard.resolveAllowed(cwd, extras, raw)
    }

    private fun pathError(e: Throwable): ToolResult =
        ToolResult.Error(e.message ?: "路径解析失败", ToolResult.INVALID_INPUT)

    // ══════════════════════════════════════════════════════════════
    //  Read
    // ══════════════════════════════════════════════════════════════

    inner class ReadTool : Tool() {
        override val name = "Read"
        override val description = "读取文件内容（支持指定行范围）"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 50_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("要读取的文件绝对路径或相对路径"),
            "start_line" to ToolSchema.integer("起始行号（从 1 开始，可选）", minimum = 1),
            "end_line" to ToolSchema.integer("结束行号（含，可选）", minimum = 1),
            required = listOf("file_path"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("file_path").isNullOrBlank()) return "file_path is required"
            val s = input.int("start_line")
            val e = input.int("end_line")
            if (s != null && s < 1) return "start_line must be >= 1"
            if (e != null && s != null && e < s) return "end_line must be >= start_line"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return pathError(e)
            }

            if (!file.exists()) return ToolResult.notFound("File not found: ${file.absolutePath}")
            if (file.isDirectory) return ToolResult.invalidInput("是目录不是文件：${file.absolutePath}")

            // ⚠️ 先记版本再读内容：万一读取期间文件被改，宁可记旧版本
            // （后续写被拦、可重读），也不要记新版本（那会放行「旧内容覆盖新文件」）
            FileVersionTracker.track(file)

            val content = try {
                file.readText()
            } catch (e: Throwable) {
                return ToolResult.Error("读取失败：${e.message}", ToolResult.INTERNAL)
            }

            val lines = content.split("\n")
            val start = ((input.int("start_line") ?: 1) - 1).coerceAtLeast(0)
            val end = input.int("end_line") ?: lines.size
            if (start >= lines.size) {
                return ToolResult.invalidInput("start_line ($start) 超出文件行数 (${lines.size})")
            }

            val slice = lines.subList(start, end.coerceAtMost(lines.size))
            // 行号右对齐 5 位（与 CCM 一致，模型读起来更整齐）
            return ToolResult.ok(
                slice.mapIndexed { i, line ->
                    val no = (start + i + 1).toString().padStart(5)
                    "$no| $line"
                }.joinToString("\n"),
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Write
    // ══════════════════════════════════════════════════════════════

    inner class WriteTool : Tool() {
        override val name = "Write"
        override val description = "写入文件（覆盖）"
        override val isDestructive = true
        override val maxResultSizeChars = 500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("目标文件路径"),
            "content" to ToolSchema.string("要写入的完整内容"),
            "backup_note" to ToolSchema.string("（可选）这次写入的目的，会写进备份文件名方便回溯"),
            required = listOf("file_path", "content"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("file_path").isNullOrBlank()) return "file_path is required"
            if (input["content"] == null) return "content is required"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return pathError(e)
            }
            val content = input.str("content") ?: ""
            val note = input.str("backup_note") ?: ""

            // ① 并发守卫放最前面：校验失败不产生任何副作用（不污染 undo / 回收站）
            FileVersionTracker.staleReason(file)?.let {
                return ToolResult.Error(it, ToolResult.INVALID_INPUT)
            }

            // ② 备份旧版本到回收站（大改动才备份）
            trashStore.backupBeforeOverwrite(file, content, note)

            // ③ undo 快照
            if (file.exists()) {
                try {
                    undoStore.saveSnapshot(file, file.readText(), note)
                } catch (_: Throwable) {
                    // 快照失败不阻塞写入
                }
            }

            // ④ 原子写入
            return try {
                AtomicFile.writeText(file, content)
                FileVersionTracker.track(file)   // 刷新版本，否则连续两次 Write 会被自己误拦
                ToolResult.ok("Wrote ${content.toByteArray(Charsets.UTF_8).size} bytes to ${file.absolutePath}")
            } catch (e: Throwable) {
                ToolResult.Error("写入失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Edit
    // ══════════════════════════════════════════════════════════════

    inner class EditTool : Tool() {
        override val name = "Edit"
        override val description = "精确替换文件中的字符串（old_string 必须在文件中唯一）"
        override val isDestructive = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("目标文件路径"),
            "old_string" to ToolSchema.string("要替换的原文（必须在文件中唯一匹配）"),
            "new_string" to ToolSchema.string("替换成的新文本"),
            "replace_all" to ToolSchema.boolean("替换所有出现（默认 false，要求唯一匹配）"),
            "backup_note" to ToolSchema.string("（可选）这次修改的目的"),
            required = listOf("file_path", "old_string", "new_string"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("file_path").isNullOrBlank()) return "file_path is required"
            if (input["old_string"] == null) return "old_string is required"
            if (input["new_string"] == null) return "new_string is required"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return pathError(e)
            }
            if (!file.exists()) return ToolResult.notFound("File not found: ${file.absolutePath}")

            val oldStr = input.str("old_string") ?: ""
            val newStr = input.str("new_string") ?: ""
            val replaceAll = input.bool("replace_all") ?: false
            val note = input.str("backup_note") ?: ""

            // 并发守卫
            FileVersionTracker.staleReason(file)?.let {
                return ToolResult.Error(it, ToolResult.INVALID_INPUT)
            }

            val original = try {
                file.readText()
            } catch (e: Throwable) {
                return ToolResult.Error("读取失败：${e.message}", ToolResult.INTERNAL)
            }

            // 唯一性检查（对齐 CCM：0 处或 >1 处都报错）
            val occurrences = countOccurrences(original, oldStr)
            if (occurrences == 0) {
                return ToolResult.Error(
                    "old_string 未找到（文件中不存在该文本）。请确认内容与当前文件一致。",
                    ToolResult.INVALID_INPUT,
                )
            }
            if (occurrences > 1 && !replaceAll) {
                return ToolResult.Error(
                    "old_string 不唯一（出现 $occurrences 次）。请扩大上下文使其唯一，或设 replace_all=true。",
                    ToolResult.INVALID_INPUT,
                )
            }

            // ⚠️ 用 split+join 而不是正则替换：new_string 里的 $ 不会被当特殊模式
            // （CCM 踩过：正则替换会把 $1 之类当反向引用，损坏内容）
            val updated = if (replaceAll) {
                original.split(oldStr).joinToString(newStr)
            } else {
                val idx = original.indexOf(oldStr)
                original.substring(0, idx) + newStr + original.substring(idx + oldStr.length)
            }

            trashStore.backupBeforeOverwrite(file, updated, note)
            try {
                undoStore.saveSnapshot(file, original, note)
            } catch (_: Throwable) {
            }

            return try {
                AtomicFile.writeText(file, updated)
                FileVersionTracker.track(file)
                val changed = if (replaceAll) occurrences else 1
                ToolResult.ok("已编辑 ${file.absolutePath}（替换 $changed 处）")
            } catch (e: Throwable) {
                ToolResult.Error("写入失败：${e.message}", ToolResult.INTERNAL)
            }
        }

        private fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var idx = haystack.indexOf(needle)
            while (idx >= 0) {
                count++
                idx = haystack.indexOf(needle, idx + needle.length)
            }
            return count
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MultiEdit
    // ══════════════════════════════════════════════════════════════

    inner class MultiEditTool : Tool() {
        override val name = "MultiEdit"
        override val description = "对单个文件做多处精确替换（原子：全部成功才写盘）"
        override val isDestructive = true
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("目标文件路径"),
            "edits" to EDITS_SCHEMA,
            "backup_note" to ToolSchema.string("（可选）这次修改的目的"),
            required = listOf("file_path", "edits"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("file_path").isNullOrBlank()) return "file_path is required"
            val edits = input["edits"] as? kotlinx.serialization.json.JsonArray
                ?: return "edits 必须是数组，形如 [{old_string, new_string}, ...]"
            if (edits.isEmpty()) return "edits 不能为空"
            edits.forEachIndexed { i, el ->
                val obj = el as? JsonObject ?: return "edits[$i] 不是对象"
                if (obj.str("old_string") == null) return "edits[$i].old_string is required"
                if (obj.str("new_string") == null) return "edits[$i].new_string is required"
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return pathError(e)
            }
            if (!file.exists()) return ToolResult.notFound("File not found: ${file.absolutePath}")

            val editsArr = input["edits"] as? kotlinx.serialization.json.JsonArray
                ?: return ToolResult.invalidInput("edits 必须是数组")
            if (editsArr.isEmpty()) return ToolResult.invalidInput("edits 不能为空")

            // 并发守卫放最前：校验失败不产生任何副作用
            FileVersionTracker.staleReason(file)?.let {
                return ToolResult.Error(it, ToolResult.INVALID_INPUT)
            }

            val original = try {
                file.readText()
            } catch (e: Throwable) {
                return ToolResult.Error("读取失败：${e.message}", ToolResult.INTERNAL)
            }

            // ⚠️ 全部在内存里应用，任一失败则整体不改（原子性）
            // 不做「边改边写」——否则第 3 个编辑失败时前 2 个已落盘，文件处于破碎状态
            var current = original
            val applied = mutableListOf<String>()

            for ((i, el) in editsArr.withIndex()) {
                val edit = el as? JsonObject
                    ?: return ToolResult.invalidInput("edits[$i] 不是对象")
                val oldStr = edit.str("old_string")
                    ?: return ToolResult.invalidInput("edits[$i].old_string is required")
                val newStr = edit.str("new_string")
                    ?: return ToolResult.invalidInput("edits[$i].new_string is required")
                val replaceAll = edit.bool("replace_all") ?: false

                val count = countOccurrences(current, oldStr)
                if (count == 0) {
                    return ToolResult.Error(
                        "edits[$i].old_string 未找到。前 ${applied.size} 个编辑已通过校验但**未写盘**，文件保持原样。",
                        ToolResult.INVALID_INPUT,
                    )
                }
                if (count > 1 && !replaceAll) {
                    return ToolResult.Error(
                        "edits[$i].old_string 不唯一（出现 $count 次）。请扩大上下文，或设 replace_all=true。",
                        ToolResult.INVALID_INPUT,
                    )
                }
                current = if (replaceAll) {
                    current.split(oldStr).joinToString(newStr)
                } else {
                    val idx = current.indexOf(oldStr)
                    current.substring(0, idx) + newStr + current.substring(idx + oldStr.length)
                }
                applied += "  [$i] 替换${if (replaceAll) " $count 处" else ""}"
            }

            val note = input.str("backup_note") ?: ""
            trashStore.backupBeforeOverwrite(file, current, note)
            try {
                undoStore.saveSnapshot(file, original, note)
            } catch (_: Throwable) {
            }

            return try {
                AtomicFile.writeText(file, current)
                FileVersionTracker.track(file)
                ToolResult.ok(
                    "MultiEdit 完成 ${file.absolutePath}（${editsArr.size} 个编辑）\n" +
                        applied.joinToString("\n"),
                )
            } catch (e: Throwable) {
                ToolResult.Error("写入失败：${e.message}", ToolResult.INTERNAL)
            }
        }

        private fun countOccurrences(haystack: String, needle: String): Int {
            if (needle.isEmpty()) return 0
            var count = 0
            var idx = haystack.indexOf(needle)
            while (idx >= 0) {
                count++
                idx = haystack.indexOf(needle, idx + needle.length)
            }
            return count
        }
    }
}

/**
 * edits 数组的 schema。
 *
 * ⚠️ 必须是 **array of object**（每个元素含 old_string/new_string），
 * 不是 stringArray —— 用 `ToolSchema.stringArray()` 会让模型以为要传字符串数组，
 * 直接导致参数错误。
 *
 * ⚠️ 放顶层而不是 `MultiEditTool` 的 companion object 里：
 * **`inner class` 内不允许声明 `companion object`**（Kotlin 语言限制，
 * 因为 inner class 已隐含持有外部实例，再加伴生对象语义冲突）。
 * CI 报错原文：`'Companion object' is prohibited here.`
 */
private val EDITS_SCHEMA: JsonObject = kotlinx.serialization.json.buildJsonObject {
    put("type", kotlinx.serialization.json.JsonPrimitive("array"))
    put("description", kotlinx.serialization.json.JsonPrimitive("替换操作列表，按顺序应用"))
    put(
        "items",
        kotlinx.serialization.json.buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("object"))
            put(
                "properties",
                kotlinx.serialization.json.buildJsonObject {
                    put("old_string", ToolSchema.string("要替换的原文（必须唯一匹配）"))
                    put("new_string", ToolSchema.string("替换成的新文本"))
                    put("replace_all", ToolSchema.boolean("替换所有出现（默认 false）"))
                },
            )
            put(
                "required",
                kotlinx.serialization.json.JsonArray(
                    listOf(
                        kotlinx.serialization.json.JsonPrimitive("old_string"),
                        kotlinx.serialization.json.JsonPrimitive("new_string"),
                    ),
                ),
            )
        },
    )
}
