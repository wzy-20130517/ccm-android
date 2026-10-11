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
 *
 * ══════════════════════════════════════════════════════════════
 *  【2026-10-11 加】执行通道（外接 Termux 模式）
 * ══════════════════════════════════════════════════════════════
 *
 * `envMode == "termux"` 时，工作目录在 Termux 私有目录里 —— Android 侧的
 * `File.walk` 连目录都进不去（跨沙箱）。所以三个搜索工具都改走通道：
 *
 * | 工具 | 通道实现 |
 * |---|---|
 * | Glob | `find` 列文件 + **本地** [GlobMatcher] 匹配（保持与 Android 侧同一套 glob 语义）|
 * | Grep | `grep -rn`（PCRE），只回传命中的行 |
 * | CodeSearch | `grep` 出候选行 → 本地按关键词逐行过滤 |
 *
 * 【为什么 Glob 只把 `find` 交给通道】
 * 若直接用 `find -name '<glob>'`，glob 语义就变成 **shell 的**了 ——
 * `**` 在 shell 里不递归、`*` 不跨目录，与 [GlobMatcher] 的行为对不上，
 * 模型会看到「同一个模式在两种环境下结果不同」。所以只借 `find` 的
 * 遍历能力，匹配仍在本地做。
 *
 * @param channel 主执行通道。null = 用 Android [File] API（proot / 未选环境）
 */
class SearchTools(private val channel: ChannelFs? = null) {

    /** 是否走通道（Termux 模式） */
    private val viaChannel: Boolean get() = channel != null

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

    /**
     * 通道模式的搜索根（字符串形式）。
     *
     * 语义与 [com.ccm.app.tools.file.FileTools.resolvePath] 一致：
     * 绝对路径原样用；相对路径交给 Termux 的 `$HOME`（通道默认 workDir）。
     * `ctx.cwd` 不参与 —— 它是 App 私有目录，Termux 侧看不见。
     */
    private fun resolveRootPath(input: JsonObject): String = (input.str("path") ?: ".").trim()

    /** 通道执行失败时的统一文案。 */
    private fun channelError(detail: String): ToolResult =
        ToolResult.Error(
            "Termux 通道执行失败：$detail\n" +
                "当前环境是「外接 Termux」，文件检索必须由 Termux 执行。\n" +
                "排查：① Termux 是否在运行 ② 是否已授权「在 Termux 中运行命令」",
            ToolResult.INTERNAL,
        )

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

            // ── 通道模式（外接 Termux）──────────────────────────────
            if (viaChannel) {
                val ch = channel ?: return channelError("通道未注入")
                val rootPath = resolveRootPath(input)
                val listed = ch.listFiles(rootPath, maxFiles = 50_000)
                listed.error?.let { return ToolResult.notFound(it) }

                val results = mutableListOf<String>()
                for (f in listed.files) {
                    ctx.checkCancelled()
                    if (results.size >= 500) break
                    val rel = ch.relativeTo(f, rootPath)
                    if (re.containsMatchIn(rel)) results += ch.canonicalKey(f)
                }
                return ToolResult.ok(results.joinToString("\n").ifEmpty { "(no matches)" })
            }

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

            // ── 通道模式（外接 Termux）──────────────────────────────
            if (viaChannel) {
                val ch = channel ?: return channelError("通道未注入")
                val rootPath = resolveRootPath(input)

                // include 过滤在本地做（保持 Kotlin 正则语义），所以先多取一些行，
                // 过滤后仍有足够命中。上限 800 行，防单次回传过大。
                val fetch = if (includeFilter != null) (MAX_MATCHES * 4).coerceAtMost(800) else MAX_MATCHES
                val res = ch.grep(patternText, rootPath, maxMatches = fetch)
                res.error?.let { return ToolResult.notFound(it) }

                val out = mutableListOf<String>()
                for (h in res.hits) {
                    if (out.size >= MAX_MATCHES) break
                    val name = h.path.substringAfterLast('/')
                    if (includeFilter != null && !includeFilter.containsMatchIn(name)) continue
                    out += "${h.path}:${h.line}:${h.text}"
                }
                return ToolResult.ok(out.joinToString("\n").ifEmpty { "(no matches)" })
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

            // ── 通道模式（外接 Termux）──────────────────────────────
            //
            // CodeSearch 的语义是「一行里出现全部关键词」（不分词、不要求相邻），
            // 直接映射成 grep 的多个 `-e`（AND 语义）会把**同一行**的约束
            // 变成「同一文件任意行」—— 那会大量误报。
            //
            // 所以：用第一个关键词 grep 出候选行（多取一些），其余关键词
            // 在本地逐行校验。绝大多数情况下第一个关键词的候选集已经很小。
            if (viaChannel) {
                val ch = channel ?: return channelError("通道未注入")
                val rootPath = resolveRootPath(input)

                val head = keywords.first()
                val fetch = (limit * 8).coerceAtMost(1000)
                val res = ch.grep(
                    pattern = Regex.escape(head),
                    root = rootPath,
                    maxMatches = fetch,
                    ignoreCase = true,
                )
                res.error?.let { return ToolResult.notFound(it) }

                val results = mutableListOf<String>()
                for (h in res.hits) {
                    if (results.size >= limit) break
                    val name = h.path.substringAfterLast('/')
                    if (includeFilter != null && !includeFilter.containsMatchIn(name)) continue
                    if (includeFilter == null && !CODE_EXTENSIONS.containsMatchIn(name)) continue
                    val low = h.text.lowercase()
                    if (keywords.all { low.contains(it) }) {
                        results += "${ch.canonicalKey(h.path)}:${h.line}:${h.text.trim().take(200)}"
                    }
                }
                return ToolResult.ok(results.joinToString("\n").ifEmpty { "(no matches)" })
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
