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
import com.ccm.app.tools.file.ChannelFs
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
 * ══════════════════════════════════════════════════════════════
 *  【2026-10-11 加】文件读写的执行通道
 * ══════════════════════════════════════════════════════════════
 *
 * 上面那句「纯 Kotlin 实现」有个前提：**文件能被 Android 侧读到**。
 * 用户选「外接 Termux」后，工作目录在 Termux 私有目录里（跨沙箱），
 * `File.walk` 连目录都进不去 —— 三个工具会统一报「路径不存在」。
 *
 * 修法与上一批的 FileTools / SearchTools 相同：把遍历与读写交给 [ChannelFs]。
 * 差别在于这三个工具是**遍历型**的（要读很多文件），所以：
 *
 * | 工具 | 通道实现 |
 * |---|---|
 * | RepoMap | `find` 列清单（剪掉 node_modules 等）→ [ChannelFs.readBatch] 批量读 → 本地正则提取 |
 * | Symbols | 同上（共用 [collectSources]） |
 * | SafeRename | 同上 + 批量写回（预检 → 逐个写 → 失败回滚） |
 *
 * **为什么用批量读而不是逐个读**：N 个文件就是 N 次 Intent 往返
 * （每次 50~200ms）。300 个文件的项目逐个读要 15~60 秒，用户会以为卡死；
 * 批量读把往返数降到 N/60。
 *
 * @param channel Bash 通道（Test/Diagnostics 用，可为 null）
 * @param trashStore SafeRename 的备份
 * @param channelFs 通道文件系统（Termux 模式非空；proot 模式 null → 用 [File] API）
 */
class DevTools(
    private val channel: BashChannel?,
    private val trashStore: TrashStore,
    /** 程序内命令执行器（App 层注入；null 时 CommandExec 报未接入） */
    private val commandExec: (suspend (String) -> String)? = null,
    private val channelFs: ChannelFs? = null,
) {

    companion object {
        /** 单文件扫描上限（防超大文件卡死） */
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024

        /**
         * **通道模式**下一次扫描最多读几个文件。
         *
         * 本地模式的上限是 5000（纯内存操作，很快）。通道模式每次往返
         * 都要过 Binder，且整条命令受 `MAX_ARG_STRLEN` 约束 —— 批量读
         * 按 60 个/批算，600 个文件 = 10 批 ≈ 10 次往返 ≈ 2~5 秒，是
         * 「能扫完」与「不把用户等烦」之间的折衷。
         *
         * ⚠️ 超过上限时**必须在输出里明说**（不能静默少扫）——
         * 否则模型会以为「这个项目就这么大」，据此做出错误判断。
         */
        private const val MAX_CHANNEL_FILES = 600

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

            // ⚠️ 必须判 isAbsolute —— Java 的 `File(parent, child)` 对绝对路径
            // child 是**拼接**（`/a/b` + `/x/y` = `/a/b/x/y`），与 Python/Node
            // 的 resolve 语义相反。不判的话模型传 `/data/data/.../ccm` 会变成
            // `/storage/.../workspace/data/data/.../ccm`（实测报「路径不存在」）。
            val cwd = input.str("cwd")?.let {
                if (File(it).isAbsolute) it else File(ctx.cwd, it).absolutePath
            } ?: ctx.cwd
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
                val (root, rootPath) = resolveRoot(input, ctx)
                localRootMissing(root)?.let { return@withContext it }

                val maxTags = (input.int("max_tags") ?: 60).coerceIn(5, 300)
                val maxChars = (input.int("max_chars") ?: 4_000).coerceIn(500, 20_000)

                data class Sym(val name: String, val kind: String, val file: String, val line: Int)

                val scan = collectSources(
                    root = root,
                    rootPath = rootPath,
                    maxFiles = scanLimit(),
                )
                scan.error?.let { return@withContext channelError(it) }

                val defs = mutableListOf<Sym>()
                val allText = StringBuilder()
                for (sf in scan.files) {
                    allText.append(sf.content).append('\n')
                    sf.content.split("\n").forEachIndexed { i, line ->
                        extractDefinition(line)?.let { (name, kind) ->
                            defs += Sym(name, kind, sf.rel, i + 1)
                        }
                    }
                }

                if (defs.isEmpty()) {
                    val note = scanNote(scan)
                    return@withContext ToolResult.ok(
                        "（未找到可识别的定义；支持的扩展名：${SOURCE_EXT.joinToString("/")}）" +
                            if (note.isEmpty()) "" else "\n$note",
                    )
                }

                // 按「名字在全文出现次数」排序（引用频次近似，对应 Node 版的 PageRank 简化）
                val text = allText.toString()
                val ranked = defs
                    .groupBy { it.name }
                    .map { (name, list) -> Triple(name, list.first(), countOccurrences(text, name)) }
                    .sortedByDescending { it.third }
                    .take(maxTags)

                val sb = StringBuilder()
                sb.append("代码库地图：$rootPath\n")
                sb.append("扫描到 ${defs.size} 个定义，按引用频次显示前 ${ranked.size} 个\n")
                // ⚠️ 只有真有话要说时才加这一行 —— 无条件 append 会在正常输出里
                // 插一个空行（scanNote 返回 ""），看起来像格式错乱。
                scanNote(scan).takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
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

    /**
     * 扫描一个源码文件。
     *
     * @property path 用于展示与后续读写的路径。本地模式是绝对路径，
     *   通道模式是 Termux 侧路径 —— 两种都是「能直接喂给对应 API」的形式。
     * @property rel 相对扫描根的路径（用于输出，让结果可读且不泄露绝对路径长度）
     * @property content 文件内容
     * @property size 文件**字节数**（不是字符数）。
     *   ⚠️ 并发保护（[FileVersionTracker.staleReasonRemote]）拿它跟
     *   通道返回的 stat 比对，用 `content.length`（字符数）会在含中文时
     *   对不上 —— 一个 3 字节的中文字符只算 1，于是**每次都被误判为
     *   「文件已被修改」而拒绝写入**。必须用真实字节数。
     * @property mtimeMs 修改时间（毫秒）
     */
    data class SourceFile(
        val path: String,
        val rel: String,
        val content: String,
        val size: Long,
        val mtimeMs: Long = 0L,
    )

    /**
     * 一次扫描的结果。
     *
     * @param truncated 因数量上限被截断（目录里还有更多文件没扫）
     * @param skipped 被跳过的文件（太大 / 读不了）。写操作必须报给用户。
     * @param error 致命错误（通道失败）。非 null 时 [files] 可能不完整。
     */
    data class ScanResult(
        val files: List<SourceFile> = emptyList(),
        val truncated: Boolean = false,
        val skipped: List<String> = emptyList(),
        val error: String? = null,
    )

    /**
     * 遍历 [root] 下的源码文件并读出内容 —— **本地 / 通道双路径**。
     *
     * 这是 RepoMap / Symbols / SafeRename 三个工具共同的底座。
     *
     * ══════════════════════════════════════════════════════════════
     *  为什么把「遍历 + 读」抽出来
     * ══════════════════════════════════════════════════════════════
     *
     * 三个工具的差别只在「拿到内容之后干什么」（提取定义 / 过滤符号 /
     * 替换标识符）。遍历与读取这部分在两种模式下写法完全不同（File API
     * vs Binder 往返），抽出来才能保证三个工具**行为一致** ——
     * 否则很容易出现「RepoMap 修了通道、SafeRename 忘了」这种漏改。
     *
     * ══════════════════════════════════════════════════════════════
     *  两条路径的差异（重要）
     * ══════════════════════════════════════════════════════════════
     *
     * | | 本地（proot） | 通道（Termux） |
     * |---|---|---|
     * | 遍历 | [FileWalker.walk] | `find`（剪掉噪音目录）|
     * | 读 | `file.readText()` | [ChannelFs.readBatch]（60 个/批）|
     * | 单文件上限 | 2MB | 120KB（批量读的上限，见 ChannelFs）|
     * | 文件数上限 | 5000 | [MAX_CHANNEL_FILES] |
     *
     * ⚠️ **通道模式的单文件上限低得多**（120KB vs 2MB），这是 Binder 的
     * 硬约束（见 [ChannelFs.MAX_BATCH_FILE_BYTES]）。超过的文件进 [ScanResult.skipped]，
     * 调用方要如实报出来 —— 静默跳过会让写操作变成半成品。
     *
     * @param maxFiles 最多读几个文件（两种模式都适用）
     */
    private suspend fun collectSources(
        root: File?,
        rootPath: String,
        maxFiles: Int,
        includeFilter: Regex? = null,
    ): ScanResult {
        val fs = channelFs
        if (fs == null) {
            if (root == null) return ScanResult(error = "内部错误：本地模式缺少扫描根")
            return collectLocal(root, maxFiles, includeFilter)
        }

        // ── 通道模式 ─────────────────────────────────────────────
        //
        // 1. 列清单。剪掉 node_modules/.git 这类噪音 —— 它们动辄几万文件，
        //    不剪的话 maxFiles 名额会被它们占满，真正的源码一个都扫不到。
        //    （本地模式的 FileWalker 用 skipDirs 做同样的事，两边要对齐。）
        val listed = fs.listFiles(
            rootPath,
            maxFiles = maxFiles + 1,
            excludeDirs = FileWalker.SKIP_DIRS,
        )
        listed.error?.let { return ScanResult(error = it) }

        val candidates = listed.files
            .filter { isSourcePath(it) }
            .filter { includeFilter == null || includeFilter.containsMatchIn(fileNameOf(it)) }

        val truncated = listed.truncated || candidates.size > maxFiles
        val wanted = if (candidates.size > maxFiles) candidates.take(maxFiles) else candidates
        if (wanted.isEmpty()) return ScanResult(truncated = truncated)

        // 2. 批量读。一次往返读一整批，不是逐个读（见类注释的性能说明）。
        val batch = fs.readBatch(wanted, maxFiles = maxFiles)
        batch.error?.let { return ScanResult(error = it, truncated = truncated) }

        val files = batch.files.map { bf ->
            SourceFile(
                path = bf.path,
                rel = fs.relativeTo(bf.path, rootPath),
                content = bf.content,
                // ⚠️ 用通道 stat 回来的真实字节数，不是 content.length
                // （见 [SourceFile.size] 的说明：字符数会让并发保护误判）。
                size = bf.size,
                mtimeMs = bf.mtimeMs,
            )
        }
        return ScanResult(
            files = files,
            truncated = truncated,
            // 跳过项用「相对路径」展示，与 files 里的 rel 保持同一套坐标 ——
            // 混用绝对路径会让输出里出现两种风格，读起来很乱。
            skipped = batch.skipped.map { fs.relativeTo(it, rootPath) },
        )
    }

    /** [collectSources] 的本地路径（proot / 未选环境）。 */
    private fun collectLocal(
        root: File,
        maxFiles: Int,
        includeFilter: Regex?,
    ): ScanResult {
        val files = mutableListOf<SourceFile>()
        val skipped = mutableListOf<String>()
        var truncated = false

        // ⚠️ 这里有两层上限，别搞混：
        //  · [walkLimit] 是 [FileWalker] 的「最多访问几个文件」—— 防目录里
        //    塞了几万个非源码文件（如 assets/、日志）时白跑一遍。
        //  · [maxFiles] 是「最多**收集**几个源码文件」—— 调用方真正关心的。
        // 前者必须比后者大得多，否则「目录里前 5000 个文件都不是源码」
        // 会让收集提前结束，看起来像「项目里没有源码」。
        val walkLimit = maxFiles * 20

        FileWalker.walk(root, maxFiles = walkLimit) { file, rel ->
            if (file.extension.lowercase() !in SOURCE_EXT) return@walk true
            if (includeFilter != null && !includeFilter.containsMatchIn(file.name)) return@walk true
            if (file.length() > MAX_FILE_BYTES) {
                // 与通道模式一样如实记下来（本地模式的 2MB 上限比通道宽松得多，
                // 真正触发时值得让用户知道）
                skipped += rel
                return@walk true
            }
            // 已经够了：再多一个源码文件就说明被截断了
            if (files.size >= maxFiles) {
                truncated = true
                return@walk false
            }
            val content = try {
                file.readText()
            } catch (_: Throwable) {
                skipped += rel
                return@walk true
            }
            files += SourceFile(file.absolutePath, rel, content, file.length(), file.lastModified())
            true
        }

        return ScanResult(files, truncated, skipped)
    }

    /** 通道模式的源码扩展名判定（路径形式，不是 [File]）。 */
    private fun isSourcePath(path: String): Boolean {
        val name = fileNameOf(path)
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        return name.substring(dot + 1).lowercase() in SOURCE_EXT
    }

    /** 从路径里取文件名（`a/b/c.kt` → `c.kt`）。**不**用 [File] —— 它是 Termux 侧路径。 */
    private fun fileNameOf(path: String): String {
        val slash = path.lastIndexOf('/')
        return if (slash >= 0) path.substring(slash + 1) else path
    }

    /**
     * 扫描根路径的解析 —— **双路径**。
     *
     * @return 本地模式是 [File]；通道模式是 Termux 侧路径字符串。两者互斥。
     *
     * 【为什么返回两个值而不是一个】
     * 本地模式后续操作（[FileWalker]、[AtomicFile]、[FileVersionTracker]）
     * 全都要 [File] 对象；通道模式后续操作（[ChannelFs] 的每个方法）
     * 全都要路径字符串。用一个「统一抽象」包起来反而要在每处再拆开。
     *
     * 【路径解析规则（通道模式）】
     * · **绝对路径** → 原样用（`/data/.../home/ccm` 是 Termux 侧的真实路径）
     * · **相对路径** → 交给 Termux 的 `$HOME`（通道默认 workDir）
     * `ctx.cwd` **不参与** —— 它是 App 私有目录，Termux 侧看不见。
     * 这与 [com.ccm.app.tools.file.FileTools] / [com.ccm.app.tools.file.SearchTools]
     * 的通道路径语义一致。
     */
    private fun resolveRoot(input: JsonObject, ctx: ToolContext, key: String = "path"): Pair<File?, String> {
        val raw = (input.str(key) ?: ".").trim()
        if (channelFs != null) {
            // ⚠️ 空串（如模型传了 ""）要退化成 "."，否则会解析成 Termux home 的
            // 父级或报奇怪错误。
            return null to raw.ifEmpty { "." }
        }
        // ⚠️ isAbsolute 判断不能省（见 TestTool 处注释：Java 的
        // File(parent, child) 对绝对路径 child 是拼接，不是替换）
        val f = if (File(raw).isAbsolute) File(raw) else File(ctx.cwd, raw)
        return f to f.absolutePath
    }

    /** 通道模式的错误文案（与 SearchTools 保持一致）。 */
    private fun channelError(detail: String): ToolResult =
        ToolResult.Error(
            "Termux 通道执行失败：$detail\n" +
                "当前环境是「外接 Termux」，文件读写必须由 Termux 执行。\n" +
                "排查：① Termux 是否在运行 ② 是否已授权「在 Termux 中运行命令」",
            ToolResult.INTERNAL,
        )

    /**
     * 一次扫描的文件数上限 —— 两种模式取值不同。
     *
     * 本地是纯内存操作（读 5000 个文件也就几秒），通道每个文件都要过
     * Binder（批量读 60 个/批），所以上限低一个数量级。
     *
     * ⚠️ 集中在这里定义（而不是三处调用点各写一遍三元表达式）是为了
     * 避免漂移：曾经 RepoMap 用 5000、SafeRename 用 800 这种不一致，
     * 会让「同一个项目，RepoMap 扫得到、SafeRename 改不到」。
     */
    private fun scanLimit(): Int =
        if (channelFs != null) MAX_CHANNEL_FILES else 5_000

    /** 本地模式的「路径不存在」检查。通道模式由 [ChannelFs] 自己报错。 */
    private fun localRootMissing(root: File?): ToolResult? =
        if (root != null && !root.exists()) {
            ToolResult.notFound("路径不存在：${root.absolutePath}")
        } else {
            null
        }

    /**
     * 扫描结果的「如实说明」—— 被截断 / 有跳过的文件时必须说出来。
     *
     * 【为什么一定要报】
     * 静默少扫的后果不是「少几条输出」，而是**模型据此形成错误判断**：
     * 它看到「扫到 42 个定义」，会以为这个项目就这么大、这个符号不存在。
     * 对只读工具是误导，对 SafeRename 这种写操作更危险 ——
     * 「以为全改了、其实有文件没扫到」正是我们要避免的半成品。
     */
    private fun scanNote(scan: ScanResult): String {
        val parts = mutableListOf<String>()
        if (scan.truncated) {
            parts += "⚠️ 已达扫描上限（${scan.files.size} 个文件），结果不完整"
        }
        if (scan.skipped.isNotEmpty()) {
            val shown = scan.skipped.take(5).joinToString(", ")
            val more = if (scan.skipped.size > 5) " 等 ${scan.skipped.size} 个" else ""
            parts += "⚠️ 跳过 ${scan.skipped.size} 个文件（过大或读不了）：$shown$more"
        }
        return if (parts.isEmpty()) "" else parts.joinToString("\n")
    }

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
                val (root, rootPath) = resolveRoot(input, ctx)
                localRootMissing(root)?.let { return@withContext it }

                val keyword = input.str("keyword")?.lowercase()
                val limit = (input.int("limit") ?: 80).coerceIn(1, 500)

                // ⚠️ maxFiles 用 [scanLimit]，**不能**按 `limit` 缩小。
                // 曾写成 `minOf(scanLimit(), limit * 8)` 图快，结果是行为回归：
                // 若前 8*limit 个文件里符号不够（很常见 —— 大量文件是测试、
                // 配置、空壳），会**少报符号**，而模型看到「符号 12 个」
                // 会以为这就是全部。
                // 「找到 limit 个就停」的优化留在**提取阶段**（下面的 break@scanLoop），
                // 那里停是安全的：少读文件不影响结果，少扫文件才会。
                val scan = collectSources(
                    root = root,
                    rootPath = rootPath,
                    maxFiles = scanLimit(),
                )
                scan.error?.let { return@withContext channelError(it) }

                val out = mutableListOf<String>()
                // ⚠️ 标签 + break 是 `for` 循环唯一合法的跳出方式 ——
                // `return@scanLoop` 编译不过（局部 return 只对 lambda 有效）。
                scanLoop@ for (sf in scan.files) {
                    val lines = sf.content.split("\n")
                    for (i in lines.indices) {
                        if (out.size >= limit) break@scanLoop
                        val def = extractDefinition(lines[i]) ?: continue
                        val (name, kind) = def
                        if (keyword != null && !name.lowercase().contains(keyword)) continue
                        out += "${sf.rel}:${i + 1}  $name  ($kind)"
                    }
                }

                val note = scanNote(scan)
                ToolResult.ok(
                    when {
                        out.isEmpty() -> "（没有匹配的符号）" + if (note.isEmpty()) "" else "\n$note"
                        note.isEmpty() -> "符号 ${out.size} 个：\n" + out.joinToString("\n")
                        else -> "符号 ${out.size} 个：\n" + out.joinToString("\n") + "\n$note"
                    },
                )
            }
    }

    // ══════════════════════════════════════════════════════════════
    //  SafeRename
    // ══════════════════════════════════════════════════════════════

    inner class SafeRenameTool : Tool() {

        /**
         * 写操作的结果（两种模式共用）。
         *
         * 是普通嵌套类（`class`），**不是** `inner class` —— `inner` 会隐式
         * 持有外部实例引用，而这里只需要装三个数。用 `inner` 还会带来一个
         * 实际麻烦：`inner class` 的构造函数依赖外部实例，将来想把它提到
         * 类外（如放到 companion 或顶层）就得连带改所有构造点。
         */
        private class WriteOutcome(
            val files: Int,
            val count: Int,
            val errors: List<String>,
        )

        /**
         * 两阶段提交里「一个文件的改动计划」。
         *
         * ⚠️ 定义在类级（不是 `writeViaChannel` 里的局部类）—— 局部类在
         * Kotlin 里限制不少（不能是 `data class`、不能被某些 lambda 捕获），
         * 而且它被 for 循环里的 lambda 间接引用。类级更省心。
         *
         * @property sf 扫描时的快照（含原内容、mtime、size）
         * @property replaced 替换后的新内容
         * @property tmp 临时文件路径（**与目标同目录**，保证 rename 原子）
         * @property mtimeMs 由 prepare 阶段的 `write` 填上 —— 同目录 rename
         *   保留 mtime，所以它也是最终文件的 mtime（省一次 stat）
         */
        private class Plan(
            val sf: SourceFile,
            val replaced: String,
            val tmp: String,
        ) {
            var mtimeMs: Long = 0L
        }

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
                val (root, rootPath) = resolveRoot(input, ctx)
                localRootMissing(root)?.let { return@withContext it }

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

                val scan = collectSources(
                    root = root,
                    rootPath = rootPath,
                    maxFiles = scanLimit(),
                    includeFilter = includeFilter,
                )
                scan.error?.let { return@withContext channelError(it) }

                // 命中项：文件路径 + 行号 + 行内容（用于预览）
                val hits = mutableListOf<Triple<SourceFile, Int, String>>()
                for (sf in scan.files) {
                    sf.content.split("\n").forEachIndexed { i, line ->
                        if (pattern.containsMatchIn(line)) {
                            hits += Triple(sf, i + 1, line.trim().take(100))
                        }
                    }
                }

                // ⚠️ 扫描不完整时**拒绝执行写操作**（dry_run 除外）。
                // 「以为全改了、其实有一批文件根本没扫到」比报错糟糕得多：
                // 项目会处于半改状态，且没有任何迹象提示用户去检查。
                val note = scanNote(scan)
                if (!dryRun && scan.truncated) {
                    return@withContext ToolResult.failed(
                        "扫描未覆盖全部文件，拒绝执行替换（避免只改一部分）。\n" +
                            "请缩小 path 范围（如逐个子目录执行），或先确认上限是否够用。\n" +
                            "当前扫到 ${scan.files.size} 个文件。",
                    )
                }

                if (hits.isEmpty()) {
                    return@withContext ToolResult.ok(
                        "没有找到 \"$from\" 的引用（未做任何改动）" + if (note.isEmpty()) "" else "\n$note",
                    )
                }

                val preview = buildString {
                    append("找到 ${hits.size} 处引用（${hits.map { it.first.path }.distinct().size} 个文件）：\n\n")
                    hits.take(40).forEach { (f, line, text) ->
                        append("  ${f.rel}:$line  $text\n")
                    }
                    if (hits.size > 40) append("  …还有 ${hits.size - 40} 处\n")
                }

                if (dryRun) {
                    return@withContext ToolResult.ok(
                        "$preview\n[dry_run] 未做任何改动。确认无误后用 dry_run:false 执行替换。" +
                            if (note.isEmpty()) "" else "\n$note",
                    )
                }

                // ── 实际替换 ─────────────────────────────────────────
                //
                // 两条路径的失败语义不同，别搞混：
                //  · **通道模式**：两阶段提交（prepare 全部成功才 commit）——
                //    要么全改，要么全不改。详见 [writeViaChannel] 的注释。
                //  · **本地模式**：逐个原子写（[AtomicFile] 保证单文件要么旧
                //    要么新）。一个文件失败不影响其他文件 —— 这是既有行为，
                //    保持不变。
                // 两者都把**失败如实报出来**，且只要有一个失败就整体标 failed
                // （避免模型看到「已替换 N 处」就以为全成功）。
                val writeResult = if (channelFs != null) {
                    writeViaChannel(hits, pattern, to, from)
                } else {
                    writeLocal(hits, pattern, to, from)
                }

                val tail = buildString {
                    if (note.isNotEmpty()) append("\n$note")
                    if (writeResult.errors.isNotEmpty()) {
                        append("\n失败（未改动）：\n")
                        append(writeResult.errors.joinToString("\n"))
                    }
                }
                val summary = "已替换 ${writeResult.count} 处（${writeResult.files} 个文件）"
                if (writeResult.errors.isEmpty()) {
                    ToolResult.ok("$preview\n$summary$tail")
                } else {
                    // 有失败 → 整体标 failed，避免模型看到「已替换 N 处」就以为全成功
                    ToolResult.failed("$preview\n$summary$tail")
                }
            }


        /**
         * 通道模式的替换 —— **两阶段提交**（要么全改，要么全不改）。
         *
         * ══════════════════════════════════════════════════════════════
         *  为什么不能像本地模式那样「逐个直接覆盖」
         * ══════════════════════════════════════════════════════════════
         *
         * 本地模式里 [AtomicFile.writeText] 是原子的（写临时文件 + rename），
         * 每个文件独立失败只影响它自己。通道模式也是这个道理，但**代价高得多**：
         * 一次 write 可能是多次 Binder 往返（大文件分块），20 个文件里第 17 个
         * 失败时，前 16 个已经改了、后面 4 个没改 —— 项目处于半改状态，
         * 而用户看到的是「部分失败」，得自己判断哪些改了。
         *
         * 所以这里改成两阶段：
         *
         * ```
         * prepare: 全部文件写到同目录的临时文件（.ccm-rename-<token>）
         *          —— 任一失败 → 删掉所有临时文件，原文件零改动
         * commit : 逐个 `mv -f 临时文件 目标`（同目录 rename，原子）
         *          —— 中途失败 → 用内存里的原内容写回已提交的文件（回滚）
         * ```
         *
         * 【回滚为什么可行】所有原内容都在 [SourceFile.content] 里（扫描时
         * 已经读出来了），不需要重新读。回滚 = 把原内容再写回去。
         *
         * 【回滚也可能失败】磁盘满 / 权限变化这类故障会同时影响回滚。这时
         * 如实报「回滚失败，文件处于中间状态」，并指出备份在回收站里 ——
         * 绝不假装成功。
         *
         * 【并发保护】用通道 stat 回来的 mtime/size 与
         * [FileVersionTracker.staleReasonRemote] 比对 —— 与本地模式同语义：
         * 文件在我们读完之后被别人改过，就拒绝覆盖（否则对方的改动会静默消失）。
         */
        private suspend fun writeViaChannel(
            hits: List<Triple<SourceFile, Int, String>>,
            pattern: Regex,
            to: String,
            from: String,
        ): WriteOutcome {
            val fs = channelFs ?: return WriteOutcome(0, 0, emptyList())
            val errors = mutableListOf<String>()

            // 按文件分组（一个文件可能有多处命中，只需写一次）
            val byFile = hits.groupBy { it.first.path }

            // ── 阶段 0：预检 + 准备内容 ────────────────────────────
            // 这一步不发命令，纯内存：算好每个文件的新内容，把不该动的挑出去。
            //
            // [Plan.mtimeMs] 在 prepare 阶段由 `write` 的返回值填上 ——
            // 不能等到 commit 之后再 stat：那要多一次往返，而且
            // **同目录 rename 会保留 mtime**（Linux 语义），所以临时文件的
            // mtime 就是最终文件的 mtime，直接拿来用是准确的。
            val plans = mutableListOf<Plan>()
            // token 用纳秒时间戳：同一次调用内唯一即可（不同调用不会撞）
            val token = "ccm-rename-" + System.nanoTime().toString(36)

            for ((path, fileHits) in byFile) {
                val sf = fileHits.first().first
                val rel = sf.rel

                // ⚠️ 不能用 `?.let { return@let }` —— 那只是从 lambda 返回，
                // 循环照样往下走，会**静默地覆盖掉别人的改动**（这正是并发保护
                // 要防的事）。必须显式 continue。
                val stale = FileVersionTracker.staleReasonRemote(
                    key = fs.canonicalKey(path),
                    mtimeMs = sf.mtimeMs,
                    size = sf.size,
                    exists = true,
                )
                if (stale != null) {
                    errors += "$rel: $stale"
                    continue
                }

                val replaced = pattern.replace(sf.content, to)
                if (replaced == sf.content) continue

                // 临时文件放**同目录** —— rename 跨文件系统会退化成复制+删除，
                // 那样就不是原子的了（见 [ChannelFs.rename]）。
                plans += Plan(sf, replaced, "$path.$token.tmp")
            }

            if (plans.isEmpty()) return WriteOutcome(0, 0, errors)

            // ── 阶段 1：prepare —— 全部写临时文件 ──────────────────
            val prepared = mutableListOf<Plan>()
            for (p in plans) {
                when (val w = fs.write(p.tmp, p.replaced)) {
                    is ChannelFs.WriteResult.Ok -> {
                        // 记下 mtime 供 commit 后写进版本表 —— 省一次 stat。
                        // 同目录 rename 保留 mtime，所以这个值对最终文件同样成立。
                        p.mtimeMs = w.mtimeMs
                        prepared += p
                    }

                    is ChannelFs.WriteResult.TooLarge -> {
                        errors += "${p.sf.rel}: 文件太大（${w.size} 字节 > 上限 ${w.limit}）"
                        break
                    }

                    is ChannelFs.WriteResult.Failed -> {
                        errors += "${p.sf.rel}: 准备失败：${w.message}"
                        break
                    }
                }
            }

            if (prepared.size != plans.size) {
                // 有任何没准备好的 → 清理所有临时文件，**原文件一个都没动**
                for (p in prepared) fs.remove(p.tmp)
                errors += "已中止：原文件未做任何改动"
                return WriteOutcome(0, 0, errors)
            }

            // 备份（内容已在内存里，不需要额外读）。
            // ⚠️ 放在 prepare **之后**：注定失败的批次不该污染回收站。
            for (p in prepared) {
                trashStore.backupBeforeOverwriteAt(
                    p.sf.path, p.sf.content, p.replaced, "SafeRename $from→$to",
                )
            }

            // ── 阶段 2：commit —— 逐个 rename ──────────────────────
            var changedFiles = 0
            var changedCount = 0
            val committed = mutableListOf<Plan>()

            for ((i, p) in prepared.withIndex()) {
                if (fs.rename(p.tmp, p.sf.path)) {
                    // ⚠️ mtime 必须用 prepare 时 `write` 返回的真实值，**不能填 0**
                    // —— [FileVersionTracker.staleReasonRemote] 拿它跟下次
                    // 通道 stat 回来的值比对，填 0 会让**每次**比对都不相等，
                    // 于是后续对这个文件的写入全被误判成「已被他人修改」而拒绝。
                    // （踩过：填 0 后 SafeRename 改完一次就再也改不动这个文件。）
                    FileVersionTracker.trackRemote(
                        key = fs.canonicalKey(p.sf.path),
                        mtimeMs = p.mtimeMs,
                        size = p.replaced.toByteArray(Charsets.UTF_8).size.toLong(),
                    )
                    committed += p
                    changedFiles++
                    changedCount += pattern.findAll(p.sf.content).count()
                } else {
                    // ── 回滚 ───────────────────────────────────────
                    errors += "${p.sf.rel}: 提交失败（mv 失败）"

                    // 还没提交的临时文件删掉
                    for (q in prepared.drop(i)) fs.remove(q.tmp)

                    // 已提交的用原内容写回
                    val rollbackFailed = mutableListOf<String>()
                    for (q in committed) {
                        val w = fs.write(q.sf.path, q.sf.content)
                        if (w !is ChannelFs.WriteResult.Ok) {
                            rollbackFailed += q.sf.rel
                        }
                    }
                    if (rollbackFailed.isNotEmpty()) {
                        errors += "⚠️ 回滚失败（文件处于中间状态）：${rollbackFailed.joinToString(", ")}\n" +
                            "   原内容已备份到回收站，可用 /trash 查看"
                    } else {
                        errors += "已回滚全部改动（${committed.size} 个文件）"
                    }
                    return WriteOutcome(0, 0, errors)
                }
            }

            return WriteOutcome(changedFiles, changedCount, errors)
        }

        /** 本地模式的替换（proot / 未选环境）。行为与改造前完全一致。 */
        private fun writeLocal(
            hits: List<Triple<SourceFile, Int, String>>,
            pattern: Regex,
            to: String,
            from: String,
        ): WriteOutcome {
            var changedFiles = 0
            var changedCount = 0
            val errors = mutableListOf<String>()

            hits.map { it.first.path }.distinct().forEach { path ->
                val file = File(path)
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

                trashStore.backupBeforeOverwrite(file, replaced, "SafeRename $from→$to")
                try {
                    AtomicFile.writeText(file, replaced)
                    FileVersionTracker.track(file)
                    changedFiles++
                    changedCount += pattern.findAll(original).count()
                } catch (e: Throwable) {
                    errors += "${file.name}: ${e.message}"
                }
            }

            return WriteOutcome(changedFiles, changedCount, errors)
        }
    }
}
