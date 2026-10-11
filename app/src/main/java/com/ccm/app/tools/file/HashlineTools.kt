package com.ccm.app.tools.file

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Hashline 工具集（3 个）—— 基于锚点的读取、编辑、搜索。
 *
 * 参照 Node 版 `core/tools-hashline.mjs`（287 行）。
 * 底层算法在 [Hashline]（FNV-1a + 空白归一化 + 5 字母编码）。
 *
 * ══════════════════════════════════════════════════════════════
 *  与普通 Read/Edit 的分工（什么时候该用哪个）
 * ══════════════════════════════════════════════════════════════
 *
 * | 场景 | 用哪个 |
 * |---|---|
 * | 需要精确控制编辑位置 | **Hashline**（锚点验证行未被改动） |
 * | 文件大、怕行号偏移 | **Hashline** |
 * | 简单字符串替换、知道要换什么 | 普通 Edit |
 * | 小改动 | 普通 Edit |
 *
 * 核心价值：**普通 Edit 按「内容匹配」，Hashline 按「行锚点 + 内容验证」**。
 * 当文件被并发修改时，Hashline 会明确报「锚点过期」而不是改错地方。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 批量编辑必须 **bottom-up**（从后往前）执行
 * ══════════════════════════════════════════════════════════════
 *
 * 从前往后执行的话，第一个编辑插入/删除行后，**后续所有锚点的行号全部失效**。
 * 从后往前则天然免疫（后面的行号不受前面改动影响）。
 *
 * ══════════════════════════════════════════════════════════════
 *  【2026-10-11 加】执行通道（外接 Termux 模式）
 * ══════════════════════════════════════════════════════════════
 *
 * `envMode == "termux"` 时文件在 Termux 私有目录里，Android 侧 [File] 读不到。
 * 三个 Hashline 工具改走 [ChannelFs]：
 *  · HashlineRead  → 通道读全文，再交给 [Hashline.format] 算锚点（算法一行不改）
 *  · HashlineEdit  → 通道读 → **原地跑原有编辑逻辑** → 通道写
 *  · HashlineGrep  → 通道 grep 拿命中行 → 本地补算锚点（锚点必须在**完整文件**
 *    的行内容上算，所以命中行只做展示，锚点用 grep 返回的原文行算即可 ——
 *    锚点只依赖该行内容，不依赖上下文）
 *
 * @param channel 主执行通道。null = 用 Android [File] API（proot / 未选环境）
 */
class HashlineTools(
    private val trashStore: TrashStore,
    private val undoStore: UndoStore,
    private val channel: ChannelFs? = null,
) {

    /** 是否走通道（Termux 模式） */
    private val viaChannel: Boolean get() = channel != null

    private fun resolve(input: JsonObject, ctx: ToolContext, key: String = "file_path"): File {
        val raw = input.str(key) ?: throw IllegalArgumentException("$key is required")
        val cwd = File(ctx.cwd)
        val extras = ctx.extraDirs.map { File(it) }
        return PathGuard.resolveAllowed(cwd, extras, raw)
    }

    /** 通道模式的路径（绝对原样用；相对交给 Termux 的 $HOME）。 */
    private fun resolvePath(input: JsonObject, key: String = "file_path"): String =
        (input.str(key) ?: throw IllegalArgumentException("$key is required")).trim()

    private fun channelError(detail: String): ToolResult =
        ToolResult.Error(
            "Termux 通道执行失败：$detail\n" +
                "当前环境是「外接 Termux」，文件读写必须由 Termux 执行。\n" +
                "排查：① Termux 是否在运行 ② 是否已授权「在 Termux 中运行命令」",
            ToolResult.INTERNAL,
        )

    // ══════════════════════════════════════════════════════════════
    //  HashlineRead
    // ══════════════════════════════════════════════════════════════

    inner class HashlineReadTool : Tool() {
        override val name = "HashlineRead"
        override val description =
            "读取文件内容，每行带 hashline 锚点（格式：行号:hash→内容，如 22:abcde→  let x = 1;）。" +
                "锚点是基于行内容计算的 FNV-1a 哈希，**空白归一化**（缩进变化不影响）。" +
                "读取后可用 HashlineEdit 的 anchor 精确编辑 —— 编辑前会验证锚点仍匹配当前行内容，" +
                "比纯字符串匹配更安全（文件被并发改动时会明确报错而不是改错地方）。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 50_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("文件路径"),
            "offset" to ToolSchema.integer("起始行号（1-based，默认 1）", minimum = 1),
            "limit" to ToolSchema.integer("读取行数（默认全部）", minimum = 1),
            required = listOf("file_path"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            // ── 通道模式（外接 Termux）──────────────────────────────
            if (viaChannel) {
                val ch = channel ?: return channelError("通道未注入")
                val path = try {
                    resolvePath(input)
                } catch (e: Throwable) {
                    return ToolResult.Error(e.message ?: "路径错误", ToolResult.INVALID_INPUT)
                }
                return when (val r = ch.read(path)) {
                    is ChannelFs.ReadResult.NotFound -> ToolResult.notFound("文件不存在: $path")
                    is ChannelFs.ReadResult.IsDirectory -> ToolResult.invalidInput("是目录不是文件：$path")
                    is ChannelFs.ReadResult.TooLarge -> ToolResult.Error(
                        "文件过大（${r.size} bytes，通道单次读取上限 ${r.limit}）。\n" +
                            "请用 offset / limit 分段读。",
                        ToolResult.INVALID_INPUT,
                    )
                    is ChannelFs.ReadResult.Failed -> channelError(r.message)
                    is ChannelFs.ReadResult.Ok -> {
                        FileVersionTracker.trackRemote(ch.canonicalKey(path), r.mtimeMs, r.size)
                        ToolResult.ok(Hashline.format(r.content, input.int("offset"), input.int("limit")))
                    }
                }
            }

            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return ToolResult.Error(e.message ?: "路径错误", ToolResult.INVALID_INPUT)
            }
            if (!file.exists()) return ToolResult.notFound("文件不存在: ${file.absolutePath}")
            if (file.isDirectory) return ToolResult.invalidInput("是目录不是文件：${file.absolutePath}")

            // 记录版本（与普通 Read 一致，供后续写保护用）
            FileVersionTracker.track(file)

            return try {
                val content = file.readText()
                ToolResult.ok(Hashline.format(content, input.int("offset"), input.int("limit")))
            } catch (e: Throwable) {
                ToolResult.Error("读取失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HashlineEdit
    // ══════════════════════════════════════════════════════════════

    inner class HashlineEditTool : Tool() {
        override val name = "HashlineEdit"
        override val description =
            "使用 hashline 锚点精确编辑文件。每个编辑操作需要 anchor（从 HashlineRead 获取）验证目标行未改动。" +
                "支持三种操作：\n" +
                "  · replace：替换一行或多行（用 anchor + 可选 end_anchor 指定范围）\n" +
                "  · insert_after：在某行后插入（anchor \"0:\" = 文件开头，\"EOF\" = 文件末尾）\n" +
                "  · write：整个文件重写\n" +
                "多操作批量执行（**bottom-up**，从后往前 —— 避免前面的改动让后面锚点失效）。" +
                "anchor 过期会报错并返回当前行的新锚点，重新读取即可。"
        override val isDestructive = true
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 5_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("文件路径"),
            "edits" to kotlinx.serialization.json.buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("编辑操作列表（bottom-up 执行）"))
                put(
                    "items",
                    kotlinx.serialization.json.buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put(
                            "properties",
                            kotlinx.serialization.json.buildJsonObject {
                                put(
                                    "op",
                                    ToolSchema.string(
                                        "操作类型",
                                        enum = listOf("replace", "insert_after", "write"),
                                    ),
                                )
                                put(
                                    "anchor",
                                    ToolSchema.string(
                                        "锚点字符串（如 \"22:abcde\"）；insert_after 可用 \"0:\" = 文件开头，" +
                                            "\"EOF\" = 文件末尾；write 不需要",
                                    ),
                                )
                                put(
                                    "end_anchor",
                                    ToolSchema.string("replace 范围结束锚点（可选，用于批量替换多行）"),
                                )
                                put("content", ToolSchema.string("新内容（replace/insert_after/write 用）"))
                            },
                        )
                        put(
                            "required",
                            JsonArray(listOf(JsonPrimitive("op"))),
                        )
                    },
                )
            },
            required = listOf("file_path", "edits"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("file_path").isNullOrBlank()) return "file_path is required"
            val edits = input["edits"] as? JsonArray ?: return "edits 必须是数组"
            if (edits.isEmpty()) return "edits 不能为空"
            edits.forEachIndexed { i, el ->
                val obj = el as? JsonObject ?: return "edits[$i] 不是对象"
                val op = (obj["op"] as? JsonPrimitive)?.content
                    ?: return "edits[$i].op is required"
                if (op !in listOf("replace", "insert_after", "write")) {
                    return "edits[$i].op 非法：$op（只能是 replace / insert_after / write）"
                }
                if (op != "write" && (obj["anchor"] as? JsonPrimitive)?.content.isNullOrBlank()) {
                    return "edits[$i].anchor is required（op=$op 需要锚点）"
                }
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val edits = input["edits"] as? JsonArray
                ?: return ToolResult.invalidInput("edits 必须是数组")

            // ── 通道模式（外接 Termux）──────────────────────────────
            if (viaChannel) {
                val ch = channel ?: return channelError("通道未注入")
                val path = try {
                    resolvePath(input)
                } catch (e: Throwable) {
                    return ToolResult.Error(e.message ?: "路径错误", ToolResult.INVALID_INPUT)
                }

                // 读旧内容（含并发守卫）。HashlineEdit 允许「文件不存在 → 新建」，
                // 所以 NotFound 不是错误，当作空文件处理。
                var existed = true
                var original = ""
                var oldMtime = 0L
                var oldSize = 0L
                when (val r = ch.read(path)) {
                    is ChannelFs.ReadResult.NotFound -> {
                        existed = false
                    }
                    is ChannelFs.ReadResult.IsDirectory ->
                        return ToolResult.invalidInput("是目录不是文件：$path")
                    is ChannelFs.ReadResult.TooLarge -> return ToolResult.Error(
                        "文件过大（${r.size} bytes，通道单次读取上限 ${r.limit}）。" +
                            "HashlineEdit 需要读全文来验证锚点，大文件请用 Bash 工具处理。",
                        ToolResult.INVALID_INPUT,
                    )
                    is ChannelFs.ReadResult.Failed -> return channelError(r.message)
                    is ChannelFs.ReadResult.Ok -> {
                        original = r.content
                        oldMtime = r.mtimeMs
                        oldSize = r.size
                    }
                }

                if (existed) {
                    FileVersionTracker.staleReasonRemote(ch.canonicalKey(path), oldMtime, oldSize, true)?.let {
                        return ToolResult.Error(it, ToolResult.INVALID_INPUT)
                    }
                }

                return when (val outcome = applyEdits(original, edits, ctx)) {
                    is EditResult.Failed -> outcome.result
                    is EditResult.Done -> {
                        if (existed) {
                            trashStore.backupBeforeOverwriteAt(path, original, outcome.content, "HashlineEdit")
                            try {
                                undoStore.saveSnapshotAt(path, original, "HashlineEdit")
                            } catch (_: Throwable) {
                            }
                        }
                        when (val w = ch.write(path, outcome.content)) {
                            is ChannelFs.WriteResult.Ok -> {
                                FileVersionTracker.trackRemote(ch.canonicalKey(path), w.mtimeMs, w.bytes)
                                ToolResult.ok(
                                    "HashlineEdit 完成 ${ch.canonicalKey(path)}（${edits.size} 个操作）\n" +
                                        outcome.applied.joinToString("\n"),
                                )
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

            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return ToolResult.Error(e.message ?: "路径错误", ToolResult.INVALID_INPUT)
            }

            // 并发写守卫
            FileVersionTracker.staleReason(file)?.let {
                return ToolResult.Error(it, ToolResult.INVALID_INPUT)
            }

            val existed = file.exists()
            val original = if (existed) {
                try {
                    file.readText()
                } catch (e: Throwable) {
                    return ToolResult.Error("读取失败：${e.message}", ToolResult.INTERNAL)
                }
            } else {
                ""
            }

            return when (val outcome = applyEdits(original, edits, ctx)) {
                is EditResult.Failed -> outcome.result
                is EditResult.Done -> {
                    trashStore.backupBeforeOverwrite(file, outcome.content, "HashlineEdit")
                    if (existed) {
                        try {
                            undoStore.saveSnapshot(file, original, "HashlineEdit")
                        } catch (_: Throwable) {
                        }
                    }
                    try {
                        AtomicFile.writeText(file, outcome.content)
                        FileVersionTracker.track(file)
                        ToolResult.ok(
                            "HashlineEdit 完成 ${file.absolutePath}（${edits.size} 个操作）\n" +
                                outcome.applied.joinToString("\n"),
                        )
                    } catch (e: Throwable) {
                        ToolResult.Error("写入失败：${e.message}", ToolResult.INTERNAL)
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HashlineEdit 的纯计算部分（两条路径共用）
    // ══════════════════════════════════════════════════════════════
    //
    // 抽出来的理由：通道模式与 Android 模式**必须逐条对齐语义**
    // （bottom-up 执行、锚点验证、越界/过期报错文案）。复制一份必然漂移。

    private sealed interface EditResult {
        data class Done(val content: String, val applied: List<String>) : EditResult
        data class Failed(val result: ToolResult) : EditResult
    }

    private fun applyEdits(original: String, edits: JsonArray, ctx: ToolContext): EditResult {
        var lines = Hashline.splitLines(original).toMutableList()

        // ⚠️ bottom-up：按行号降序排（从后往前执行）
        // 从前往后的话，第一个编辑改了行数 → 后续所有锚点行号失效
        val parsed = edits.mapIndexed { i, el ->
            val obj = el as? JsonObject
                ?: return EditResult.Failed(ToolResult.invalidInput("edits[$i] 不是对象"))
            val op = (obj["op"] as? JsonPrimitive)?.content ?: ""
            val anchorStr = (obj["anchor"] as? JsonPrimitive)?.content
            val endAnchorStr = (obj["end_anchor"] as? JsonPrimitive)?.content
            val content = (obj["content"] as? JsonPrimitive)?.content ?: ""
            Triple(i, op, Triple(anchorStr, endAnchorStr, content))
        }

        // 简化：直接手动分组 —— write 先做，其余按行号降序
        val writeOps = parsed.filter { it.second == "write" }
        val anchoredOps = parsed.filter { it.second != "write" }
            .sortedByDescending { p ->
                val a = Hashline.parseAnchor(p.third.first)
                a?.line ?: 0
            }

        val applied = mutableListOf<String>()

        // ① write 先执行（整体替换）
        for ((idx, _, data) in writeOps) {
            val content = data.third
            lines = Hashline.splitLines(content).toMutableList()
            applied += "  [$idx] write（整文件重写，${lines.size} 行）"
        }

        // ② 锚点操作（从后往前）
        for ((idx, op, data) in anchoredOps) {
            ctx.checkCancelled()
            val (anchorStr, endAnchorStr, content) = data

            // 特殊锚点
            if (op == "insert_after" && (anchorStr == "0:" || anchorStr == "0")) {
                lines.add(0, content)
                applied += "  [$idx] insert_after 文件开头"
                continue
            }
            if (op == "insert_after" && anchorStr == "EOF") {
                lines.add(content)
                applied += "  [$idx] insert_after 文件末尾"
                continue
            }

            val anchor = Hashline.parseAnchor(anchorStr)
                ?: return EditResult.Failed(
                    ToolResult.invalidInput(
                        "edits[$idx].anchor 格式非法：\"$anchorStr\"（应为 \"行号:哈希\"，如 \"22:abcde\"）",
                    ),
                )

            // 验证锚点
            when (Hashline.validate(anchor, lines)) {
                Hashline.Validity.OUT_OF_RANGE -> return EditResult.Failed(
                    ToolResult.Error(
                        "edits[$idx].anchor 行号越界：第 ${anchor.line} 行（当前文件共 ${lines.size} 行）。" +
                            "请重新 HashlineRead 获取正确锚点。",
                        ToolResult.INVALID_INPUT,
                    ),
                )

                Hashline.Validity.STALE -> {
                    // 尝试找漂移后的位置，给出有用的提示
                    val hint = when (val s = Hashline.findShifted(anchor, lines)) {
                        is Hashline.ShiftResult.Found ->
                            "该内容现在在第 ${s.line} 行（新锚点：${Hashline.anchor(lines[s.line - 1], s.line)}）"
                        is Hashline.ShiftResult.Ambiguous ->
                            "该内容在多处出现（第 ${s.lines.joinToString(", ")} 行），**不能自动选择** —— 请重新读取确认"
                        Hashline.ShiftResult.NotFound ->
                            "原位置附近找不到该内容 —— 文件可能已被改动"
                    }
                    return EditResult.Failed(
                        ToolResult.Error(
                            "edits[$idx].anchor 已过期（第 ${anchor.line} 行内容已变）：$hint",
                            ToolResult.INVALID_INPUT,
                        ),
                    )
                }

                Hashline.Validity.VALID -> {
                    val startIdx = anchor.line - 1
                    when (op) {
                        "replace" -> {
                            // 范围结束锚点
                            var endIdx = startIdx
                            if (!endAnchorStr.isNullOrBlank()) {
                                val ea = Hashline.parseAnchor(endAnchorStr)
                                    ?: return EditResult.Failed(
                                        ToolResult.invalidInput("edits[$idx].end_anchor 格式非法"),
                                    )
                                if (Hashline.validate(ea, lines) != Hashline.Validity.VALID) {
                                    return EditResult.Failed(
                                        ToolResult.Error(
                                            "edits[$idx].end_anchor 已过期（第 ${ea.line} 行内容已变）",
                                            ToolResult.INVALID_INPUT,
                                        ),
                                    )
                                }
                                endIdx = ea.line - 1
                                if (endIdx < startIdx) {
                                    return EditResult.Failed(
                                        ToolResult.invalidInput(
                                            "edits[$idx].end_anchor 行号（${ea.line}）小于 anchor（${anchor.line}）",
                                        ),
                                    )
                                }
                            }
                            // 删除 [startIdx, endIdx]，插入新内容
                            repeat(endIdx - startIdx + 1) { lines.removeAt(startIdx) }
                            if (content.isNotEmpty()) {
                                lines.addAll(startIdx, content.split("\n"))
                            }
                            applied += "  [$idx] replace 第 ${anchor.line}..${endIdx + 1} 行"
                        }

                        "insert_after" -> {
                            val insertAt = (startIdx + 1).coerceAtMost(lines.size)
                            lines.addAll(insertAt, content.split("\n"))
                            applied += "  [$idx] insert_after 第 ${anchor.line} 行"
                        }
                    }
                }
            }
        }

        return EditResult.Done(lines.joinToString("\n") + "\n", applied)
    }

    // ══════════════════════════════════════════════════════════════
    //  HashlineGrep
    // ══════════════════════════════════════════════════════════════

    inner class HashlineGrepTool : Tool() {
        override val name = "HashlineGrep"
        override val description =
            "搜索文件内容，结果带 hashline 锚点。可直接用搜索结果中的锚点在 HashlineEdit 中编辑，无需先读取文件。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 30_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "pattern" to ToolSchema.string("正则表达式"),
            "path" to ToolSchema.string("搜索路径（文件或目录）"),
            "include" to ToolSchema.string("文件名过滤（glob 模式，如 *.kt）"),
            required = listOf("pattern"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("pattern").isNullOrBlank()) "pattern is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val patternText = input.str("pattern")!!
            val regex = try {
                Regex(patternText)
            } catch (e: Throwable) {
                return ToolResult.invalidInput("正则非法：${e.message}")
            }

            val includeGlob = input.str("include")

            // ── 通道模式（外接 Termux）──────────────────────────────
            //
            // 锚点只依赖**该行内容**（不含行号以外的上下文），所以 grep 返回的
            // 命中行可以直接算锚点，不需要把整个文件读回来。
            // 行号由 grep 给出（`-n`），锚点用返回的原文行算。
            if (viaChannel) {
                val ch = channel ?: return channelError("通道未注入")
                val rootPath = (input.str("path") ?: ".").trim()

                val res = ch.grep(patternText, rootPath, maxMatches = 400)
                res.error?.let { return ToolResult.notFound(it) }

                val out = mutableListOf<String>()
                for (h in res.hits) {
                    if (out.size >= 200) break
                    val name = h.path.substringAfterLast('/')
                    if (includeGlob != null && !GlobMatcher.matches(name, includeGlob)) continue
                    // ⚠️ 锚点用 grep 回传的**原文行**算 —— 它就是文件里的那一行
                    val anchor = Hashline.anchor(h.text, h.line)
                    out += "${ch.canonicalKey(h.path)}:$anchor→${h.text}"
                }
                return ToolResult.ok(out.joinToString("\n").ifEmpty { "(no matches)" })
            }

            val root = try {
                resolve(input, ctx, "path")
            } catch (_: Throwable) {
                File(ctx.cwd)   // path 省略时用 cwd
            }
            if (!root.exists()) return ToolResult.notFound("路径不存在：${root.absolutePath}")

            val out = mutableListOf<String>()
            var count = 0

            FileWalker.walk(root, maxFiles = 20_000) { file, _ ->
                ctx.checkCancelled()
                if (includeGlob != null && !GlobMatcher.matches(file.name, includeGlob)) return@walk true
                if (file.length() > 2_000_000) return@walk true

                val content = try {
                    file.readText()
                } catch (_: Throwable) {
                    return@walk true
                }
                val lines = Hashline.splitLines(content)
                lines.forEachIndexed { i, line ->
                    if (count >= 200) return@walk false
                    if (regex.containsMatchIn(line)) {
                        val anchor = Hashline.anchor(line, i + 1)
                        out += "${file.absolutePath}:$anchor→$line"
                        count++
                    }
                }
                count < 200
            }

            return ToolResult.ok(out.joinToString("\n").ifEmpty { "(no matches)" })
        }
    }
}
