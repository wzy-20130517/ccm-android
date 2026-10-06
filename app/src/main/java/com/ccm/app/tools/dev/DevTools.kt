package com.ccm.app.tools.dev

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.tools.bash.BashChannel
import com.ccm.app.tools.file.AtomicFile
import com.ccm.app.tools.file.FileVersionTracker
import com.ccm.app.tools.file.FileWalker
import com.ccm.app.tools.file.TrashStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 开发辅助工具组 —— Test / Diagnostics / RepoMap / Symbols / SafeRename。
 *
 * 参照 Node 版 `core/tools-smart.mjs`（619 行）+ `core/repo-map.mjs`（158 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ APK 侧的关键差异：**没有随时可用的 shell**
 * ══════════════════════════════════════════════════════════════
 *
 * Node 版的 `Test` / `Diagnostics` 靠 `child_process` 跑 `npm test` / `node --check`。
 * APK 里必须通过 [BashChannel]（proot 或 Termux）—— **通道没装好时要明确提示**，
 * 不能静默失败（用户会以为「测试通过了」）。
 *
 * `RepoMap` / `Symbols` / `SafeRename` 是**纯 Kotlin 实现**（正则解析源码），
 * 不依赖 shell —— 这是 APK 侧的优势：快、无进程启动开销。
 *
 * @param channel Bash 通道（Test/Diagnostics 用，可为 null）
 * @param trashStore SafeRename 的备份
 */
class DevTools(
    private val channel: BashChannel?,
    private val trashStore: TrashStore,
    /** 程序内命令执行器（App 层注入；null 时 CommandExec 报未接入） */
    private val commandExec: (suspend (String) -> String)? = null,
) {

    companion object {
        /** 单文件扫描上限（防超大文件卡死） */
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024

        /** 源码扩展名（RepoMap/Symbols/SafeRename 只扫这些） */
        val SOURCE_EXT = setOf(
            "kt", "kts", "java", "js", "mjs", "cjs", "ts", "jsx", "tsx",
            "py", "go", "rs", "c", "cpp", "h", "hpp", "cs", "rb", "php", "swift",
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  Test
    // ══════════════════════════════════════════════════════════════

    inner class TestTool : Tool() {
        override val name = "Test"
        override val description =
            "运行测试或检查命令（npm test / node --check / 自定义命令）。" +
                "默认运行 package.json 的 test 脚本；可指定 script 名或自定义命令字符串。" +
                "返回结构化结果（退出码、stdout/stderr）。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 8_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "command" to ToolSchema.string("自定义命令（如 \"node tests/xxx.mjs\"）；不传则用 script 参数"),
            "script" to ToolSchema.string("package.json scripts 里的名字（如 \"test\"、\"check:web\"）"),
            "cwd" to ToolSchema.string("工作目录（默认当前）"),
            "timeout" to ToolSchema.integer("超时 ms（默认 60000）", minimum = 1_000, maximum = 600_000),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val ch = channel
                ?: return ToolResult.Error(
                    "Test 需要 Bash 通道，但当前未配置。" +
                        "请先安装内置环境（首页「安装运行环境」）或配置外接 Termux。",
                    ToolResult.INTERNAL,
                )

            val cwd = input.str("cwd")?.let { File(ctx.cwd, it).absolutePath } ?: ctx.cwd
            val timeoutMs = (input.int("timeout") ?: 60_000).toLong()

            // 决定跑什么命令
            val cmd: String = when {
                !input.str("command").isNullOrBlank() -> input.str("command")!!
                !input.str("script").isNullOrBlank() -> {
                    val name = input.str("script")!!
                    val scripts = readPackageScripts(File(cwd))
                        ?: return ToolResult.failed("$cwd 下没有 package.json")
                    val body = scripts[name]
                        ?: return ToolResult.failed(
                            "package.json 中没有 script \"$name\"。可用: " +
                                scripts.keys.joinToString(", ").ifEmpty { "(无)" },
                        )
                    "npm run $name   # $body"
                }
                else -> {
                    val scripts = readPackageScripts(File(cwd))
                    val test = scripts?.get("test")
                        ?: return ToolResult.failed(
                            "未找到 test script。请显式传 command（如 \"node tests/xxx.mjs\"）或 script 名。",
                        )
                    "npm test   # $test"
                }
            }

            val lines = mutableListOf<String>()
            val r = try {
                ch.execute(cmd, cwd, timeoutMs) { lines += it }
            } catch (e: Throwable) {
                return ToolResult.Error("执行失败：${e.message}", ToolResult.INTERNAL)
            }

            val out = lines.joinToString("\n")
            val sb = StringBuilder()
            sb.append("Test 结果: ${if (r.ok) "✅ 通过" else "❌ 失败（exit ${r.exitCode}）"}\n")
            sb.append("命令: $cmd\n")
            sb.append("目录: $cwd\n")
            if (out.isNotEmpty()) sb.append("\n--- 输出（尾部 4000 字符）---\n").append(out.takeLast(4_000))
            if (r.stderr.isNotEmpty()) sb.append("\n--- stderr ---\n").append(r.stderr.takeLast(2_000))

            return if (r.ok) ToolResult.ok(sb.toString()) else ToolResult.failed(sb.toString())
        }

        private fun readPackageScripts(dir: File): Map<String, String>? {
            val f = File(dir, "package.json")
            if (!f.exists()) return null
            return try {
                val obj = org.json.JSONObject(f.readText())
                val scripts = obj.optJSONObject("scripts") ?: return emptyMap()
                scripts.keys().asSequence().associateWith { scripts.optString(it) }
            } catch (_: Throwable) {
                null
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Diagnostics
    // ══════════════════════════════════════════════════════════════

    inner class DiagnosticsTool : Tool() {
        override val name = "Diagnostics"
        // ⚠️ 本工具**只做语法检查**（node --check / py_compile / tsc），
        // 没有 LSP 路径 —— 别在 description 里承诺类型检查。
        override val description =
            "获取文件的代码诊断（语法/类型错误）。" +
                "js/mjs/cjs 走 node --check，py 走 py_compile，ts 走 tsc。" +
                "适合修改代码后自证无错。"
        override val isReadOnly = true
        override val maxResultSizeChars = 10_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("要诊断的文件"),
            // 【2026-10-06 删】原 check_only 参数（"true 时只跑语法检查"）——
            // 本工具的实现**本来就只跑语法检查**（调 node --check / py_compile），
            // 没有 LSP 路径，所以这个参数没有任何行为差异。
            // 留着会让模型以为"不传它就会跑更慢的完整诊断"，白付认知成本。
            required = listOf("file_path"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("file_path").isNullOrBlank()) "file_path is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val path = input.str("file_path")!!
            val file = if (File(path).isAbsolute) File(path) else File(ctx.cwd, path)
            if (!file.exists()) return ToolResult.notFound("文件不存在: ${file.absolutePath}")

            val ch = channel
                ?: return ToolResult.failed(
                    "Diagnostics 需要 Bash 通道（要调用语法检查器）。" +
                        "请先安装内置环境或配置外接 Termux。",
                )

            // 路径含空格/引号时要用引号包住（防命令注入）
            val quoted = "'" + file.absolutePath.replace("'", "'\\''") + "'"
            val cmd = when (file.extension.lowercase()) {
                "js", "mjs", "cjs" -> "node --check $quoted"
                "py" -> "python3 -m py_compile $quoted && echo 'py_compile OK'"
                "ts", "tsx" -> "npx -y tsc --noEmit --allowJs $quoted 2>&1 | head -40"
                "kt", "kts", "java" -> "echo '（Kotlin/Java 需要完整 classpath，单文件检查意义有限；建议用 CI）'"
                else -> "echo '（未知扩展名，跳过）'"
            }

            val lines = mutableListOf<String>()
            val r = try {
                ch.execute(cmd, ctx.cwd, 60_000) { lines += it }
            } catch (e: Throwable) {
                return ToolResult.Error("执行失败：${e.message}", ToolResult.INTERNAL)
            }

            val out = lines.joinToString("\n").trim()
            val hasError = out.contains("error", ignoreCase = true) ||
                out.contains("SyntaxError") ||
                out.contains("Traceback")
            return if (r.ok && !hasError) {
                ToolResult.ok("${file.name} 无诊断错误\n${out.ifEmpty { "(无输出)" }}")
            } else {
                ToolResult.failed("${file.name} 诊断发现问题：\n$out")
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  定义提取（RepoMap 与 Symbols 共用）
    // ══════════════════════════════════════════════════════════════

    /**
     * 从一行源码里提取定义（多语言，尽力而为）。
     *
     * @return Pair(符号名, 类型) 或 null
     */
    private fun extractDefinition(line: String): Pair<String, String>? {
        val t = line.trim()
        if (t.startsWith("//") || t.startsWith("*") || t.startsWith("#") || t.startsWith("/*")) return null
        // 跳过纯注释块内的行
        if (t.isEmpty()) return null

        val indent = line.length - line.trimStart().length

        // Kotlin / Java / Scala
        Regex(
            "^\\s*(?:(?:public|private|protected|internal|open|abstract|override|suspend|inline|data|sealed|companion|final|static|lateinit|const)\\s+)*" +
                "(fun|class|object|interface|enum class|enum|record|val|var)\\s+([A-Za-z_][\\w]*)",
        ).find(line)?.let { m ->
            val kind = m.groupValues[1]
            val name = m.groupValues[2]
            // val/var 在深缩进里多半是局部变量，不算「库结构」
            if ((kind == "val" || kind == "var") && indent > 4) return null
            if (name.length < 2) return null
            return name to kind
        }

        // JS / TS
        Regex("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?(function|class|const|let|var)\\s+([A-Za-z_$][\\w$]*)")
            .find(line)?.let { m ->
                val kind = m.groupValues[1]
                val name = m.groupValues[2]
                if ((kind == "const" || kind == "let" || kind == "var") && indent > 4) return null
                if (name.length < 2) return null
                return name to kind
            }

        // Go
        Regex("^\\s*func\\s+(?:\\([^)]*\\)\\s*)?([A-Za-z_]\\w*)")
            .find(line)?.let { m -> return m.groupValues[1] to "func" }

        // Rust
        Regex("^\\s*(?:pub\\s+)?(?:async\\s+)?(fn|struct|enum|trait|impl)\\s+([A-Za-z_]\\w*)")
            .find(line)?.let { m -> return m.groupValues[2] to m.groupValues[1] }

        // Python（def/class 已在第一条覆盖，但 Python 无修饰符前缀，这里兜底）
        Regex("^\\s*(def|class)\\s+([A-Za-z_]\\w*)")
            .find(line)?.let { m -> return m.groupValues[2] to m.groupValues[1] }

        return null
    }

    // ══════════════════════════════════════════════════════════════
    //  RepoMap
    // ══════════════════════════════════════════════════════════════

    inner class RepoMapTool : Tool() {
        override val name = "RepoMap"
        override val description =
            "生成代码库结构地图（函数/类/常量定义，按引用频次排序）。" +
                "用于快速了解项目，比全量 Glob+Read 更省 token。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 8_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "path" to ToolSchema.string("仓库目录（默认当前）"),
            "max_tags" to ToolSchema.integer("最多显示多少标签（默认 60）", minimum = 5, maximum = 300),
            "max_chars" to ToolSchema.integer("输出字符上限（默认 4000）", minimum = 500, maximum = 20_000),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val root = input.str("path")?.let { File(ctx.cwd, it) } ?: File(ctx.cwd)
                if (!root.exists()) return@withContext ToolResult.notFound("路径不存在：${root.absolutePath}")

                val maxTags = (input.int("max_tags") ?: 60).coerceIn(5, 300)
                val maxChars = (input.int("max_chars") ?: 4_000).coerceIn(500, 20_000)

                data class Sym(val name: String, val kind: String, val file: String, val line: Int)

                val defs = mutableListOf<Sym>()
                val allText = StringBuilder()

                FileWalker.walk(root, maxFiles = 5_000) { file, rel ->
                    if (file.extension.lowercase() !in SOURCE_EXT) return@walk true
                    if (file.length() > MAX_FILE_BYTES) return@walk true
                    val content = try {
                        file.readText()
                    } catch (_: Throwable) {
                        return@walk true
                    }
                    allText.append(content).append('\n')
                    content.split("\n").forEachIndexed { i, line ->
                        extractDefinition(line)?.let { (name, kind) ->
                            defs += Sym(name, kind, rel, i + 1)
                        }
                    }
                    true
                }

                if (defs.isEmpty()) {
                    return@withContext ToolResult.ok("（未找到可识别的定义；支持的扩展名：${SOURCE_EXT.joinToString("/")}）")
                }

                // 按「名字在全文出现次数」排序（引用频次近似，对应 Node 版的 PageRank 简化）
                val text = allText.toString()
                val ranked = defs
                    .groupBy { it.name }
                    .map { (name, list) -> Triple(name, list.first(), countOccurrences(text, name)) }
                    .sortedByDescending { it.third }
                    .take(maxTags)

                val sb = StringBuilder()
                sb.append("代码库地图：${root.absolutePath}\n")
                sb.append("扫描到 ${defs.size} 个定义，按引用频次显示前 ${ranked.size} 个\n\n")
                for ((name, sym, count) in ranked) {
                    val line = "  ${sym.file}:${sym.line}  ${sym.kind} $name  (引用 $count 次)\n"
                    if (sb.length + line.length > maxChars) break
                    sb.append(line)
                }
                ToolResult.ok(sb.toString())
            }
    }

    // ══════════════════════════════════════════════════════════════
    //  CommandExec
    // ══════════════════════════════════════════════════════════════

    /**
     * 执行程序内 slash 命令（如 `/model`、`/compact`、`/cost`）。
     *
     * ⚠️ **不是 shell** —— 要跑 shell 用 Bash 工具。
     * 执行器由 App 层注入（[commandExec]），未注入时明确报「未接入」而不是假装成功。
     */
    inner class CommandExecTool : Tool() {
        override val name = "CommandExec"
        override val description =
            "执行程序内 slash 命令（如 /model, /compact, /cost, /clear 等）。" +
                "**不要用这个执行 shell 命令** —— 用 Bash 工具。"
        override val maxResultSizeChars = 6_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "command" to ToolSchema.string("要执行的命令，如 \"model\" 或 \"compact 10\" 或 \"cost\"（不要带 / 前缀）"),
            required = listOf("command"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("command").isNullOrBlank()) "command is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val fn = commandExec
                ?: return ToolResult.Error(
                    "CommandExec 未接入（需要 App 层注入命令执行器）。",
                    ToolResult.INTERNAL,
                )
            val cmd = input.str("command")!!.removePrefix("/")
            return try {
                ToolResult.ok(fn(cmd))
            } catch (e: Throwable) {
                ToolResult.Error("命令执行失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助
    // ══════════════════════════════════════════════════════════════

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.length < 3) return 0
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0 && count < 10_000) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    // ══════════════════════════════════════════════════════════════
    //  Symbols
    // ══════════════════════════════════════════════════════════════

    inner class SymbolsTool : Tool() {
        override val name = "Symbols"
        override val description =
            "列出代码库中的符号（类/函数/常量/接口/类型），可按名称关键词过滤。" +
                "返回 文件:行号 符号名 (类型) —— 可直接用 Read 定位。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 6_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "keyword" to ToolSchema.string("按符号名关键词过滤（如 \"api\"、\"tool\"）"),
            "path" to ToolSchema.string("目录（默认当前）"),
            "limit" to ToolSchema.integer("最多返回多少（默认 80）", minimum = 1, maximum = 500),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val root = input.str("path")?.let { File(ctx.cwd, it) } ?: File(ctx.cwd)
                if (!root.exists()) return@withContext ToolResult.notFound("路径不存在：${root.absolutePath}")

                val keyword = input.str("keyword")?.lowercase()
                val limit = (input.int("limit") ?: 80).coerceIn(1, 500)

                val out = mutableListOf<String>()
                FileWalker.walk(root, maxFiles = 5_000) { file, rel ->
                    if (out.size >= limit) return@walk false
                    if (file.extension.lowercase() !in SOURCE_EXT) return@walk true
                    if (file.length() > MAX_FILE_BYTES) return@walk true
                    val content = try {
                        file.readText()
                    } catch (_: Throwable) {
                        return@walk true
                    }
                    content.split("\n").forEachIndexed { i, line ->
                        if (out.size >= limit) return@forEachIndexed
                        val def = extractDefinition(line) ?: return@forEachIndexed
                        val (name, kind) = def
                        if (keyword != null && !name.lowercase().contains(keyword)) return@forEachIndexed
                        out += "$rel:${i + 1}  $name  ($kind)"
                    }
                    true
                }

                ToolResult.ok(
                    if (out.isEmpty()) "（没有匹配的符号）"
                    else "符号 ${out.size} 个：\n" + out.joinToString("\n"),
                )
            }
    }

    // ══════════════════════════════════════════════════════════════
    //  SafeRename
    // ══════════════════════════════════════════════════════════════

    inner class SafeRenameTool : Tool() {
        override val name = "SafeRename"
        override val description =
            "安全重命名文件/目录内的标识符。先扫描引用并给出预览（dry_run=true 只预览不改），" +
                "确认后原子替换所有出现。"
        override val isDestructive = true
        override val maxResultSizeChars = 6_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "from" to ToolSchema.string("旧名称（标识符/文件名片段）"),
            "to" to ToolSchema.string("新名称"),
            "path" to ToolSchema.string("扫描目录（默认当前）"),
            "include" to ToolSchema.string("文件名过滤正则（如 \\.kt$）"),
            "dry_run" to ToolSchema.boolean("true=只预览不修改（默认 true，先看再改更安全）"),
            required = listOf("from", "to"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("from").isNullOrBlank()) return "from is required"
            if (input.str("to").isNullOrBlank()) return "to is required"
            if (input.str("from") == input.str("to")) return "from 与 to 相同，无需替换"
            val f = input.str("from")!!
            if (!Regex("^[A-Za-z_][\\w]*$").matches(f)) {
                return "from 必须是合法标识符（字母/下划线开头，只含字母数字下划线）"
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val from = input.str("from")!!
                val to = input.str("to")!!
                val dryRun = input.bool("dry_run") ?: true
                val root = input.str("path")?.let { File(ctx.cwd, it) } ?: File(ctx.cwd)
                if (!root.exists()) return@withContext ToolResult.notFound("路径不存在：${root.absolutePath}")

                val includeFilter = input.str("include")?.let {
                    try {
                        Regex(it)
                    } catch (_: Throwable) {
                        null
                    }
                }

                // ⚠️ 标识符边界匹配：避免 `user` 命中 `username`
                // （CCM 的教训：不加边界会把无关代码一起改掉）
                val pattern = Regex("\\b" + Regex.escape(from) + "\\b")

                val hits = mutableListOf<Triple<File, Int, String>>()
                FileWalker.walk(root, maxFiles = 5_000) { file, _ ->
                    if (includeFilter != null && !includeFilter.containsMatchIn(file.name)) return@walk true
                    if (file.extension.lowercase() !in SOURCE_EXT) return@walk true
                    if (file.length() > MAX_FILE_BYTES) return@walk true
                    val content = try {
                        file.readText()
                    } catch (_: Throwable) {
                        return@walk true
                    }
                    content.split("\n").forEachIndexed { i, line ->
                        if (pattern.containsMatchIn(line)) {
                            hits += Triple(file, i + 1, line.trim().take(100))
                        }
                    }
                    true
                }

                if (hits.isEmpty()) {
                    return@withContext ToolResult.ok("没有找到 \"$from\" 的引用（未做任何改动）")
                }

                val preview = buildString {
                    append("找到 ${hits.size} 处引用（${hits.map { it.first }.distinct().size} 个文件）：\n\n")
                    hits.take(40).forEach { (f, line, text) -> append("  ${f.name}:$line  $text\n") }
                    if (hits.size > 40) append("  …还有 ${hits.size - 40} 处\n")
                }

                if (dryRun) {
                    return@withContext ToolResult.ok(
                        "$preview\n[dry_run] 未做任何改动。确认无误后用 dry_run:false 执行替换。",
                    )
                }

                // 实际替换
                var changedFiles = 0
                var changedCount = 0
                val errors = mutableListOf<String>()

                hits.map { it.first }.distinct().forEach { file ->
                    FileVersionTracker.staleReason(file)?.let {
                        errors += "${file.name}: $it"
                        return@forEach
                    }
                    val original = try {
                        file.readText()
                    } catch (_: Throwable) {
                        return@forEach
                    }
                    val replaced = pattern.replace(original, to)
                    if (replaced == original) return@forEach

                    trashStore.backupBeforeOverwrite(file, replaced, "SafeRename ${from}→${to}")
                    try {
                        AtomicFile.writeText(file, replaced)
                        FileVersionTracker.track(file)
                        changedFiles++
                        changedCount += pattern.findAll(original).count()
                    } catch (e: Throwable) {
                        errors += "${file.name}: ${e.message}"
                    }
                }

                ToolResult.ok(
                    "$preview\n已替换 $changedCount 处（$changedFiles 个文件）" +
                        if (errors.isEmpty()) "" else "\n失败：\n" + errors.joinToString("\n"),
                )
            }
    }
}
