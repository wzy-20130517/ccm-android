package com.ccm.app.tools.file

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 搜索工具集 —— Glob / Grep / CodeSearch。
 *
 * 参照 Node 版 `core/search-tools.mjs`（215 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  CCM 踩过的坑（都已修正，注释里标了位置）
 * ══════════════════════════════════════════════════════════════
 *
 * **坑 1：path 指向单个文件时静默返回 "(no matches)"**
 * 原实现无条件 `readdirSync(root)`，对文件路径抛 ENOTDIR 被 catch 吞掉。
 * 用户看到「没匹配」，实际是**根本没搜**。
 * 修法：[FileWalker.walk] 支持「root 就是单个文件」。
 *
 * **坑 2：Grep 的 include 过滤时机**
 * 若先读文件再过滤，遇到大目录时会读几百个无关文件才丢弃。
 * 修法：先按文件名过滤（在读之前）。
 *
 * 【设计取舍：为什么不用 Java 的 `Files.find` + `Pattern`】
 * 行为必须与 CCM 对齐（模型侧的可预期性），而 Java 的 glob 语义在
 * `**` 和隐藏文件处理上与 shell 有细微差异，详见 [GlobMatcher]。
 */
class SearchTools {

    companion object {
        /** Grep / CodeSearch 的命中上限（防止一次搜索把上下文塞爆） */
        private const val MAX_MATCHES = 200

        /** 单文件大小上限：超过就跳过（防止读一个 100MB 的日志卡死） */
        private const val MAX_FILE_BYTES = 5L * 1024 * 1024

        /** CodeSearch 只扫这些扩展名（文本类） */
        private val CODE_EXTENSIONS = Regex(
            "\\.(mjs|js|cjs|ts|jsx|tsx|kt|java|py|go|rs|c|cpp|h|json|md|css|html|xml|sh|yml|yaml|toml)$",
            RegexOption.IGNORE_CASE,
        )
    }

    private fun resolveRoot(input: JsonObject, ctx: ToolContext): File {
        val raw = input.str("path") ?: "."
        val cwd = File(ctx.cwd)
        val extras = ctx.extraDirs.map { File(it) }
        return PathGuard.resolveAllowed(cwd, extras, raw)
    }

    /** 读取文本文件（带大小保护）。读不了返回 null。 */
    private fun readTextSafe(file: File): String? = try {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) null
        else file.readText()
    } catch (_: Throwable) {
        null
    }

    // ══════════════════════════════════════════════════════════════
    //  Glob
    // ══════════════════════════════════════════════════════════════

    // ⚠️ 必须是 `inner class` 而不是嵌套 `class` ——
    // 嵌套类不持有外部实例，访问不到 [SearchTools.resolveRoot] / [SearchTools.readTextSafe]
    // 这些私有方法（CI 报了一串 Unresolved reference）。
    // 代价是每个实例多一个外部引用，对这些短命工具对象无所谓。
    inner class GlobTool : Tool() {
        override val name = "Glob"
        override val description = "glob 模式查找文件（如 **/*.kt）"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "pattern" to ToolSchema.string("glob 模式，如 **/*.kt"),
            "path" to ToolSchema.string("搜索根目录（默认当前工作目录）"),
            required = listOf("pattern"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("pattern").isNullOrBlank()) "pattern is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val pattern = input.str("pattern")!!
            val re = GlobMatcher.compile(pattern)
                ?: return ToolResult.invalidInput("glob 模式非法：$pattern")

            val root = try {
                resolveRoot(input, ctx)
            } catch (e: Throwable) {
                return ToolResult.Error(e.message ?: "路径错误", ToolResult.INVALID_INPUT)
            }
            if (!root.exists()) return ToolResult.notFound("路径不存在：${root.absolutePath}")

            val results = mutableListOf<String>()
            FileWalker.walk(root, maxFiles = 50_000) { file, rel ->
                if (re.containsMatchIn(rel)) {
                    results += file.absolutePath
                }
                results.size < 500   // 上限 500（对齐 CCM）
            }

            return ToolResult.ok(results.joinToString("\n").ifEmpty { "(no matches)" })
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Grep
    // ══════════════════════════════════════════════════════════════

    inner class GrepTool : Tool() {
        override val name = "Grep"
        override val description = "正则搜索文件内容"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 30_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "pattern" to ToolSchema.string("正则表达式"),
            "path" to ToolSchema.string("搜索根目录或单个文件（默认当前目录）"),
            "include" to ToolSchema.string("文件名过滤正则（如 \\.kt$）"),
            required = listOf("pattern"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("pattern").isNullOrBlank()) "pattern is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val patternText = input.str("pattern")!!
            val regex = try {
                Regex(patternText)
            } catch (e: Throwable) {
                return ToolResult.invalidInput("正则表达式非法：${e.message}")
            }

            val includeFilter = input.str("include")?.let {
                try {
                    Regex(it)
                } catch (_: Throwable) {
                    return ToolResult.invalidInput("include 正则非法：$it")
                }
            }

            val root = try {
                resolveRoot(input, ctx)
            } catch (e: Throwable) {
                return ToolResult.Error(e.message ?: "路径错误", ToolResult.INVALID_INPUT)
            }
            if (!root.exists()) return ToolResult.notFound("路径不存在：${root.absolutePath}")

            val matches = mutableListOf<String>()
            FileWalker.walk(root, maxFiles = 20_000) { file, _ ->
                ctx.checkCancelled()
                // ⚠️ 先按文件名过滤（在读之前）—— 否则大目录里会白读几百个文件
                if (includeFilter != null && !includeFilter.containsMatchIn(file.name)) {
                    return@walk true
                }
                val content = readTextSafe(file) ?: return@walk true
                val lines = content.split("\n")
                for ((i, line) in lines.withIndex()) {
                    if (matches.size >= MAX_MATCHES) return@walk false
                    if (regex.containsMatchIn(line)) {
                        matches += "${file.absolutePath}:${i + 1}:$line"
                    }
                }
                true
            }

            return ToolResult.ok(matches.joinToString("\n").ifEmpty { "(no matches)" })
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  CodeSearch
    // ══════════════════════════════════════════════════════════════

    inner class CodeSearchTool : Tool() {
        override val name = "CodeSearch"
        override val description =
            "关键词模糊找符号（函数/类/变量名）。空格分隔多个词时要全部命中；" +
                "比 Grep 适合「那个函数叫什么来着」——只记得大概叫什么、写不准正则时用它"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 30_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "query" to ToolSchema.string("搜索关键词（空格分隔多个词，全部匹配才命中）"),
            "path" to ToolSchema.string("限定目录（默认当前目录）"),
            "include" to ToolSchema.string("文件名过滤正则（如 \\.kt$）"),
            "limit" to ToolSchema.integer("返回条数上限（默认 50，最大 200）"),
            required = listOf("query"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("query").isNullOrBlank()) "query is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val keywords = input.str("query")!!
                .lowercase()
                .split(Regex("\\s+"))
                .filter { it.isNotEmpty() }
            if (keywords.isEmpty()) return ToolResult.invalidInput("query 不能为空")

            val limit = (input.int("limit") ?: 50).coerceIn(1, 200)
            val includeFilter = input.str("include")?.let {
                try {
                    Regex(it)
                } catch (_: Throwable) {
                    null
                }
            }

            val root = try {
                resolveRoot(input, ctx)
            } catch (e: Throwable) {
                return ToolResult.Error(e.message ?: "路径错误", ToolResult.INVALID_INPUT)
            }
            if (!root.exists()) return ToolResult.notFound("路径不存在：${root.absolutePath}")

            val results = mutableListOf<String>()

            fun searchFile(file: File) {
                val name = file.name
                if (includeFilter != null && !includeFilter.containsMatchIn(name)) return
                // 只扫代码/文本类文件（对齐 CCM）
                if (includeFilter == null && !CODE_EXTENSIONS.containsMatchIn(name)) return
                val content = readTextSafe(file) ?: return
                val lines = content.split("\n")
                for ((i, line) in lines.withIndex()) {
                    if (results.size >= limit) return
                    val low = line.lowercase()
                    // 全部关键词都出现在这一行才算命中（模糊匹配）
                    if (keywords.all { low.contains(it) }) {
                        results += "${file.absolutePath}:${i + 1}:${line.trim().take(200)}"
                    }
                }
            }

            FileWalker.walk(root, maxFiles = 20_000) { file, _ ->
                ctx.checkCancelled()
                searchFile(file)
                results.size < limit
            }

            return ToolResult.ok(results.joinToString("\n").ifEmpty { "(no matches)" })
        }
    }
}
