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
 *
 * ══════════════════════════════════════════════════════════════
 *  【2026-10-11 加】执行通道（外接 Termux 模式）
 * ══════════════════════════════════════════════════════════════
 *
 * 用户选「外接 Termux」后，Bash 命令跑在 Termux 进程里，而文件工具用
 * `java.io.File` —— **两者不是同一个文件系统视图**：
 *
 * ```
 * Bash : cd /data/data/com.termux/files/home && ls       → Termux 的 home
 * Write: File("/data/data/com.termux/files/home/a.txt")  → Permission denied
 * ```
 *
 * 于是出现「Bash 读不到 Write 写的文件」这种死循环。
 *
 * 修法：构造时注入主通道（[ChannelFs]），`envMode == "termux"` 时
 * **全部读写都过通道**，让 Termux 自己去动它自己的文件。
 *
 * ⚠️ **proot 模式零改动**：proot 的 rootfs 与 Android 侧路径不通，但
 * `/sdcard` 两边共享，用 [File] API 更直接。所以 [channel] 为 null 时
 * 完全走原有路径，行为与改动前逐字节一致。
 *
 * @param channel 主执行通道。null = 用 Android [File] API（proot / 未选环境）
 */
class FileTools(
    private val trashStore: TrashStore,
    private val undoStore: UndoStore,
    private val channel: ChannelFs? = null,
) {

    /** 是否走通道（Termux 模式） */
    private val viaChannel: Boolean get() = channel != null

    /** 解析路径：相对路径基于 ctx.cwd，并做越界检查（Android 模式） */
    private fun resolve(input: JsonObject, ctx: ToolContext, key: String = "file_path"): File {
        val raw = input.str(key) ?: throw IllegalArgumentException("$key is required")
        val cwd = File(ctx.cwd)
        val extras = ctx.extraDirs.map { File(it) }
        return PathGuard.resolveAllowed(cwd, extras, raw)
    }

    /**
     * 通道模式的路径解析。
     *
     * 语义（与 CLI 对齐）：
     * · **绝对路径** → 原样交给 Termux 解析（`/sdcard/...` 两边都通）
     * · **相对路径** → 基于 Termux 的 `$HOME`（通道的默认 workDir）
     *
     * ⚠️ **不使用 ctx.cwd** —— 它是 App 私有目录下的路径，Termux 侧看不见，
     * 拿来当基准只会让 `cat a.txt` 变成 `cat /data/data/com.ccm.app/.../a.txt`（不存在）。
     */
    private fun resolvePath(input: JsonObject, key: String = "file_path"): String {
        val raw = input.str(key) ?: throw IllegalArgumentException("$key is required")
        return raw.trim()
    }

    private fun pathError(e: Throwable): ToolResult =
        ToolResult.Error(e.message ?: "路径解析失败", ToolResult.INVALID_INPUT)

    /** 通道不可用时的统一报错文案。 */
    private fun channelError(detail: String): ToolResult =
        ToolResult.Error(
            "Termux 通道执行失败：$detail\n" +
                "当前环境是「外接 Termux」，文件读写必须由 Termux 执行。\n" +
                "排查：① Termux 是否在运行 ② 是否已授权「在 Termux 中运行命令」",
            ToolResult.INTERNAL,
        )

    // ══════════════════════════════════════════════════════════════
    //  通道模式实现（外接 Termux）
    // ══════════════════════════════════════════════════════════════
    //
    //  下面这些方法是四个工具在 `envMode == "termux"` 时走的路径。
    //  与 Android 侧实现**逐条对齐语义**（唯一性检查、原子写、并发守卫、
    //  回收站 / undo 快照），只是把「读写文件」换成「让 Termux 读写」。
    //
    //  代价是**每次操作至少一次 Binder 往返**（Intent → Termux → PendingIntent），
    //  比 `File.readText()` 慢。所以：
    //    · 能合并的命令就合并（read 同时带回 size/mtime，省一次 stat）
    //    · 备份 / 快照仍走本地磁盘（内容已经在内存里，不必再往返）

    /** 通道模式下的写前状态。 */
    private sealed interface PreRead {
        /** 文件不存在（新建场景） */
        data object Missing : PreRead

        /** 存在且已读出内容 */
        data class Present(val content: String, val mtimeMs: Long, val size: Long) : PreRead

        /** 读不了 —— 调用方直接返回这个结果 */
        data class Reject(val result: ToolResult) : PreRead
    }

    /**
     * 通道模式：读旧内容 + 并发守卫。
     *
     * 合并成一次往返（[ChannelFs.read] 顺带带回 size / mtime）。
     */
    private suspend fun preReadForWrite(ch: ChannelFs, path: String): PreRead =
        when (val r = ch.read(path)) {
            is ChannelFs.ReadResult.NotFound -> PreRead.Missing

            is ChannelFs.ReadResult.IsDirectory ->
                PreRead.Reject(ToolResult.invalidInput("是目录不是文件：$path"))

            is ChannelFs.ReadResult.TooLarge -> PreRead.Reject(
                ToolResult.Error(
                    "文件过大（${r.size} bytes，通道单次读取上限 ${r.limit}）。\n" +
                        "通道模式的覆盖写需要先读出旧内容（备份 + 快照），大文件走不了这条路。\n" +
                        "替代方案：用 Bash 工具在 Termux 侧直接处理（如 `sed -i`）。",
                    ToolResult.INVALID_INPUT,
                ),
            )

            is ChannelFs.ReadResult.Failed -> PreRead.Reject(channelError(r.message))

            is ChannelFs.ReadResult.Ok -> {
                val key = ch.canonicalKey(path)
                FileVersionTracker.staleReasonRemote(key, r.mtimeMs, r.size, exists = true)?.let {
                    return PreRead.Reject(ToolResult.Error(it, ToolResult.INVALID_INPUT))
                }
                PreRead.Present(r.content, r.mtimeMs, r.size)
            }
        }

    /** 通道模式：备份到回收站 + undo 快照（纯本地写盘，不发命令）。 */
    private fun backupAndSnapshot(path: String, oldContent: String, newContent: String, note: String) {
        trashStore.backupBeforeOverwriteAt(path, oldContent, newContent, note)
        try {
            undoStore.saveSnapshotAt(path, oldContent, note)
        } catch (_: Throwable) {
            // 快照失败不阻塞写入
        }
    }

    /** 通道模式：写入并把通道结果转成 [ToolResult]。 */
    private suspend fun writeViaChannel(ch: ChannelFs, path: String, content: String): ToolResult =
        when (val w = ch.write(path, content)) {
            is ChannelFs.WriteResult.Ok -> {
                // 刷新版本，否则连续两次写会被自己误拦
                FileVersionTracker.trackRemote(ch.canonicalKey(path), w.mtimeMs, w.bytes)
                ToolResult.ok("Wrote ${w.bytes} bytes to ${ch.canonicalKey(path)}")
            }

            is ChannelFs.WriteResult.TooLarge -> ToolResult.Error(
                "内容过大（${w.size} bytes，通道单次写入上限 ${w.limit}）。\n" +
                    "请分多次写：先 Write 前一段，再用 Edit 追加其余部分。",
                ToolResult.INVALID_INPUT,
            )

            is ChannelFs.WriteResult.Failed -> channelError(w.message)
        }

    /** 通道模式：Read 工具主体。 */
    private suspend fun readViaChannel(input: JsonObject): ToolResult {
        val ch = channel ?: return channelError("通道未注入")
        val path = try {
            resolvePath(input)
        } catch (e: Throwable) {
            return pathError(e)
        }
        val startLine = input.int("start_line")
        val endLine = input.int("end_line")

        return when (val r = ch.read(path, startLine, endLine)) {
            is ChannelFs.ReadResult.NotFound -> ToolResult.notFound("File not found: $path")
            is ChannelFs.ReadResult.IsDirectory -> ToolResult.invalidInput("是目录不是文件：$path")

            is ChannelFs.ReadResult.TooLarge -> ToolResult.Error(
                "文件过大（${r.size} bytes，通道单次读取上限 ${r.limit}）。\n" +
                    "请用 start_line / end_line 分段读，或先用 Grep 定位到目标行。",
                ToolResult.INVALID_INPUT,
            )

            is ChannelFs.ReadResult.Failed -> channelError(r.message)

            is ChannelFs.ReadResult.Ok -> {
                // ⚠️ 先记版本再切片：记的是**整个文件**的 size/mtime，
                // 与后续写时的比对基准一致（切片后行数变了，不能拿切片算）
                FileVersionTracker.trackRemote(ch.canonicalKey(path), r.mtimeMs, r.size)

                // 0 字节文件：内容为空串，与 Android 侧 `"".split("\n")` = [""] 对齐，
                // 走下面同一条编号逻辑（不特判，避免两条路径的输出格式漂移）
                val lines = r.content.split("\n")

                // ⚠️ 分段读的偏移：`sed -n 'N,Mp'` 的输出**从第 N 行开始**，
                // 所以内容里的第 1 行对应文件的第 N 行 —— 行号要加 (N-1)。
                // 一开始这里减成了「从 0 开始」，会把切片前几行又跳掉、行号也错。
                val isRanged = startLine != null && r.size > 0L

                if (isRanged && r.content.isEmpty()) {
                    // sed 什么都没输出 = 请求的范围超出文件末尾
                    return ToolResult.invalidInput(
                        "start_line ($startLine) 超出文件末尾（该范围没有内容）",
                    )
                }

                val baseLine = if (isRanged) startLine!! - 1 else 0
                val start = 0
                val end = if (isRanged && endLine != null) {
                    endLine - startLine!! + 1
                } else {
                    endLine ?: lines.size
                }

                // 与 Android 侧一致的越界检查（只在没走 sed 切片的路径上需要）
                if (!isRanged && start >= lines.size) {
                    return ToolResult.invalidInput(
                        "start_line (${(startLine ?: 1) - 1}) 超出文件行数 (${lines.size})",
                    )
                }

                val slice = lines.subList(start, end.coerceAtMost(lines.size))
                ToolResult.ok(
                    slice.mapIndexed { i, line ->
                        val no = (baseLine + start + i + 1).toString().padStart(5)
                        "$no| $line"
                    }.joinToString("\n"),
                )
            }
        }
    }

    /** 通道模式：Write 工具主体。 */
    private suspend fun writeViaChannelTool(input: JsonObject, content: String, note: String): ToolResult {
        val ch = channel ?: return channelError("通道未注入")
        val path = try {
            resolvePath(input)
        } catch (e: Throwable) {
            return pathError(e)
        }

        return when (val pre = preReadForWrite(ch, path)) {
            is PreRead.Reject -> pre.result

            is PreRead.Missing -> writeViaChannel(ch, path, content)

            is PreRead.Present -> {
                // 内容完全相同 → 不产生副作用（与 Android 侧 backupBeforeOverwrite 的语义一致）
                if (pre.content == content) return ToolResult.ok("内容未变化：${ch.canonicalKey(path)}")
                backupAndSnapshot(path, pre.content, content, note)
                writeViaChannel(ch, path, content)
            }
        }
    }

    /** 通道模式：Edit / MultiEdit 的公共骨架（读 → 改 → 写）。 */
    private sealed interface EditOutcome {
        /** 改好了：新内容 + 给模型看的结果文案 */
        data class Done(val updated: String, val message: String) : EditOutcome

        /** 改不了（唯一性检查失败等），直接把结果返回给模型 */
        data class Failed(val result: ToolResult) : EditOutcome
    }

    private suspend fun editViaChannel(
        path: String,
        ch: ChannelFs,
        note: String,
        transform: (String) -> EditOutcome,
    ): ToolResult {
        val pre = preReadForWrite(ch, path)
        return when (pre) {
            is PreRead.Reject -> pre.result
            is PreRead.Missing -> ToolResult.notFound("File not found: $path")
            is PreRead.Present -> {
                when (val outcome = transform(pre.content)) {
                    is EditOutcome.Failed -> outcome.result
                    is EditOutcome.Done -> {
                        backupAndSnapshot(path, pre.content, outcome.updated, note)
                        when (val w = ch.write(path, outcome.updated)) {
                            is ChannelFs.WriteResult.Ok -> {
                                FileVersionTracker.trackRemote(ch.canonicalKey(path), w.mtimeMs, w.bytes)
                                ToolResult.ok(outcome.message)
                            }

                            is ChannelFs.WriteResult.TooLarge -> ToolResult.Error(
                                "修改后内容过大（${w.size} bytes，上限 ${w.limit}）。",
                                ToolResult.INVALID_INPUT,
                            )

                            is ChannelFs.WriteResult.Failed -> channelError(w.message)
                        }
                    }
                }
            }
        }
    }

    /** 统计 [needle] 在 [haystack] 里出现的次数（字面量匹配，非正则）。 */
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

    /**
     * 唯一替换（Edit / MultiEdit 的核心逻辑）。
     *
     * ⚠️ 用 split+join 而不是正则替换：new_string 里的 `$` 不会被当特殊模式
     * （CCM 踩过：正则替换会把 `$1` 之类当反向引用，损坏内容）。
     */
    private fun applyReplace(original: String, oldStr: String, newStr: String, replaceAll: Boolean): String =
        if (replaceAll) {
            original.split(oldStr).joinToString(newStr)
        } else {
            val idx = original.indexOf(oldStr)
            original.substring(0, idx) + newStr + original.substring(idx + oldStr.length)
        }

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
            // ── 通道模式（外接 Termux）──────────────────────────────
            if (viaChannel) return readViaChannel(input)

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
            val content = input.str("content") ?: ""
            val note = input.str("backup_note") ?: ""

            // ── 通道模式（外接 Termux）──────────────────────────────
            if (viaChannel) return writeViaChannelTool(input, content, note)

            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return pathError(e)
            }

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
            val oldStr = input.str("old_string") ?: ""
            val newStr = input.str("new_string") ?: ""
            val replaceAll = input.bool("replace_all") ?: false
            val note = input.str("backup_note") ?: ""

            // ── 通道模式（外接 Termux）──────────────────────────────
            if (viaChannel) {
                val ch = channel ?: return channelError("通道未注入")
                val path = try {
                    resolvePath(input)
                } catch (e: Throwable) {
                    return pathError(e)
                }
                return editViaChannel(path, ch, note) { original ->
                    // 唯一性检查（对齐 CCM：0 处或 >1 处都报错）
                    val occurrences = countOccurrences(original, oldStr)
                    if (occurrences == 0) {
                        EditOutcome.Failed(
                            ToolResult.Error(
                                "old_string 未找到（文件中不存在该文本）。请确认内容与当前文件一致。",
                                ToolResult.INVALID_INPUT,
                            ),
                        )
                    } else if (occurrences > 1 && !replaceAll) {
                        EditOutcome.Failed(
                            ToolResult.Error(
                                "old_string 不唯一（出现 $occurrences 次）。请扩大上下文使其唯一，或设 replace_all=true。",
                                ToolResult.INVALID_INPUT,
                            ),
                        )
                    } else {
                        val changed = if (replaceAll) occurrences else 1
                        EditOutcome.Done(
                            applyReplace(original, oldStr, newStr, replaceAll),
                            "已编辑 ${ch.canonicalKey(path)}（替换 $changed 处）",
                        )
                    }
                }
            }

            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return pathError(e)
            }
            if (!file.exists()) return ToolResult.notFound("File not found: ${file.absolutePath}")

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
            val updated = applyReplace(original, oldStr, newStr, replaceAll)

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
            val editsArr = input["edits"] as? kotlinx.serialization.json.JsonArray
                ?: return ToolResult.invalidInput("edits 必须是数组")
            if (editsArr.isEmpty()) return ToolResult.invalidInput("edits 不能为空")
            val note = input.str("backup_note") ?: ""

            // ── 通道模式（外接 Termux）──────────────────────────────
            if (viaChannel) {
                val ch = channel ?: return channelError("通道未注入")
                val path = try {
                    resolvePath(input)
                } catch (e: Throwable) {
                    return pathError(e)
                }
                return editViaChannel(path, ch, note) { original ->
                    // ⚠️ 全部在内存里应用，任一失败则整体不改（原子性）
                    // 不做「边改边写」——否则第 3 个编辑失败时前 2 个已落盘，文件处于破碎状态
                    var current = original
                    val applied = mutableListOf<String>()
                    var failure: ToolResult? = null

                    for ((i, el) in editsArr.withIndex()) {
                        val edit = el as? JsonObject
                        if (edit == null) {
                            failure = ToolResult.invalidInput("edits[$i] 不是对象"); break
                        }
                        val oldStr = edit.str("old_string")
                        if (oldStr == null) {
                            failure = ToolResult.invalidInput("edits[$i].old_string is required"); break
                        }
                        val newStr = edit.str("new_string")
                        if (newStr == null) {
                            failure = ToolResult.invalidInput("edits[$i].new_string is required"); break
                        }
                        val replaceAll = edit.bool("replace_all") ?: false

                        val count = countOccurrences(current, oldStr)
                        if (count == 0) {
                            failure = ToolResult.Error(
                                "edits[$i].old_string 未找到。前 ${applied.size} 个编辑已通过校验但**未写盘**，文件保持原样。",
                                ToolResult.INVALID_INPUT,
                            )
                            break
                        }
                        if (count > 1 && !replaceAll) {
                            failure = ToolResult.Error(
                                "edits[$i].old_string 不唯一（出现 $count 次）。请扩大上下文，或设 replace_all=true。",
                                ToolResult.INVALID_INPUT,
                            )
                            break
                        }
                        current = applyReplace(current, oldStr, newStr, replaceAll)
                        applied += "  [$i] 替换${if (replaceAll) " $count 处" else ""}"
                    }

                    val f = failure
                    if (f != null) {
                        EditOutcome.Failed(f)
                    } else {
                        EditOutcome.Done(
                            current,
                            "MultiEdit 完成 ${ch.canonicalKey(path)}（${editsArr.size} 个编辑）\n" +
                                applied.joinToString("\n"),
                        )
                    }
                }
            }

            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return pathError(e)
            }
            if (!file.exists()) return ToolResult.notFound("File not found: ${file.absolutePath}")

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
                current = applyReplace(current, oldStr, newStr, replaceAll)
                applied += "  [$i] 替换${if (replaceAll) " $count 处" else ""}"
            }

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
