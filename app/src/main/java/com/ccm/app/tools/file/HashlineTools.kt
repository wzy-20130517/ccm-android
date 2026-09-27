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
 */
class HashlineTools(
    private val trashStore: TrashStore,
    private val undoStore: UndoStore,
) {

    private fun resolve(input: JsonObject, ctx: ToolContext, key: String = "file_path"): File {
        val raw = input.str(key) ?: throw IllegalArgumentException("$key is required")
        val cwd = File(ctx.cwd)
        val extras = ctx.extraDirs.map { File(it) }
        return PathGuard.resolveAllowed(cwd, extras, raw)
    }

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
            val file = try {
                resolve(input, ctx)
            } catch (e: Throwable) {
                return ToolResult.Error(e.message ?: "路径错误", ToolResult.INVALID_INPUT)
            }
            val edits = input["edits"] as? JsonArray
                ?: return ToolResult.invalidInput("edits 必须是数组")

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

            var lines = Hashline.splitLines(original).toMutableList()

            // ⚠️ bottom-up：按行号降序排（从后往前执行）
            // 从前往后的话，第一个编辑改了行数 → 后续所有锚点行号失效
            val parsed = edits.mapIndexed { i, el ->
                val obj = el as? JsonObject ?: throw IllegalArgumentException("edits[$i] 不是对象")
                val op = (obj["op"] as? JsonPrimitive)?.content ?: ""
                val anchorStr = (obj["anchor"] as? JsonPrimitive)?.content
                val endAnchorStr = (obj["end_anchor"] as? JsonPrimitive)?.content
                val content = (obj["content"] as? JsonPrimitive)?.content ?: ""
                Triple(i, op, Triple(anchorStr, endAnchorStr, content))
            }

            // 排序 key：write 排最前（它会整体替换，不需要锚点），其余按 anchor 行号降序
            val sorted = parsed.sortedWith(
                compareByDescending<Pair<Int, String>> { }
                    .let { _ ->
                        // 用 anchor 的行号排序（write 当作 -1，最先执行）
                        Comparator<Pair<Int, String>> { a, b -> 0 }
                    },
            )

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
                    ?: return ToolResult.invalidInput(
                        "edits[$idx].anchor 格式非法：\"$anchorStr\"（应为 \"行号:哈希\"，如 \"22:abcde\"）",
                    )

                // 验证锚点
                when (Hashline.validate(anchor, lines)) {
                    Hashline.Validity.OUT_OF_RANGE -> {
                        return ToolResult.Error(
                            "edits[$idx].anchor 行号越界：第 ${anchor.line} 行（当前文件共 ${lines.size} 行）。" +
                                "请重新 HashlineRead 获取正确锚点。",
                            ToolResult.INVALID_INPUT,
                        )
                    }
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
                        return ToolResult.Error(
                            "edits[$idx].anchor 已过期（第 ${anchor.line} 行内容已变）：$hint",
                            ToolResult.INVALID_INPUT,
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
                                        ?: return ToolResult.invalidInput("edits[$idx].end_anchor 格式非法")
                                    if (Hashline.validate(ea, lines) != Hashline.Validity.VALID) {
                                        return ToolResult.Error(
                                            "edits[$idx].end_anchor 已过期（第 ${ea.line} 行内容已变）",
                                            ToolResult.INVALID_INPUT,
                                        )
                                    }
                                    endIdx = ea.line - 1
                                    if (endIdx < startIdx) {
                                        return ToolResult.invalidInput(
                                            "edits[$idx].end_anchor 行号（${ea.line}）小于 anchor（${anchor.line}）",
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

            // 落盘
            val newContent = lines.joinToString("\n") + "\n"
            trashStore.backupBeforeOverwrite(file, newContent, "HashlineEdit")
            if (existed) {
                try {
                    undoStore.saveSnapshot(file, original, "HashlineEdit")
                } catch (_: Throwable) {
                }
            }

            return try {
                AtomicFile.writeText(file, newContent)
                FileVersionTracker.track(file)
                ToolResult.ok(
                    "HashlineEdit 完成 ${file.absolutePath}（${edits.size} 个操作）\n" +
                        applied.joinToString("\n"),
                )
            } catch (e: Throwable) {
                ToolResult.Error("写入失败：${e.message}", ToolResult.INTERNAL)
            }
        }
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

            val root = try {
                resolve(input, ctx, "path")
            } catch (_: Throwable) {
                File(ctx.cwd)   // path 省略时用 cwd
            }
            if (!root.exists()) return ToolResult.notFound("路径不存在：${root.absolutePath}")

            val includeGlob = input.str("include")
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
