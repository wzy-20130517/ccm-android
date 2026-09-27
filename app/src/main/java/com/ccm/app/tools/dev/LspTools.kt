package com.ccm.app.tools.dev

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.tools.bash.BashChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * LSP 工具 —— 代码智能（诊断 / 类型信息 / 定义跳转 / 补全）。
 *
 * 参照 Node 版 `core/lsp.mjs`（573 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 本端能力边界（先读这段再改代码）
 * ══════════════════════════════════════════════════════════════
 *
 * **做得到的**：`action=diagnostic` —— 一次性跑语法/类型检查器拿诊断。
 * 这条路径走 [BashChannel]，与 [Diagnostics][com.ccm.app.tools.dev.DevTools] 工具同源，
 * 是**真实可用**的。
 *
 * **做不到的**：`hover` / `definition` / `completion` —— 这三个需要
 * **长连接的 language server 子进程**（LSP 协议是双向 JSON-RPC，
 * server 要常驻并维护文档状态）。本端没有这个能力，原因有二：
 *
 * 1. **BashChannel 是一次性执行模型**：`execute()` 跑完命令就返回，
 *    拿不到常驻进程的 stdin/stdout 句柄，也就没法做 LSP 握手。
 * 2. **language server 依赖没装**（实测 2026-09-27）：
 *    Node 版硬编码的 `/data/data/com.termux/files/usr/lib/node_modules` 下
 *    **没有** `typescript-language-server` 也没有 `pyright`
 *    （只有 acorn / node-imap / npm / openclaw 等）。
 *    也就是说 **Node 版这三个 action 同样是跑不通的** —— 不是移植时丢的能力。
 *
 * **为什么还保留这三个 action**（而不是从 schema 里删掉）：
 * 模型看到 enum 里有 `hover`，调了之后拿到的是「为什么用不了 + 该用什么替代」
 * 的明确说明，比「schema 里没这个选项、模型自己猜一个不存在的工具」好得多。
 * 这是 main 2026-09-27 拍板的降级策略。
 *
 * ══════════════════════════════════════════════════════════════
 *  替代路径（降级说明里会告诉模型）
 * ══════════════════════════════════════════════════════════════
 *
 * | 想要 | 用什么 |
 * |---|---|
 * | 语法/类型诊断 | 本工具 `diagnostic`，或直接用 `Diagnostics` |
 * | 找定义 | `Grep` / `CodeSearch` / `Symbols` |
 * | 类型信息 | 读源码 + `Read`，或 `CodeSearch` 找类型声明 |
 * | 补全建议 | 无对应能力，直接读相关文件 |
 */
class LspTools(private val channel: BashChannel?) {

    companion object {
        /** 诊断命令超时（tsc 首次跑要下依赖，给足） */
        private const val DIAG_TIMEOUT_MS = 120_000L

        /** 支持的扩展名 → 检查器说明 */
        private val SUPPORTED = mapOf(
            "ts" to "TypeScript（tsc --noEmit）",
            "tsx" to "TypeScript JSX（tsc --noEmit）",
            "js" to "JavaScript（node --check）",
            "jsx" to "JavaScript JSX（node --check）",
            "mjs" to "ES Module（node --check）",
            "cjs" to "CommonJS（node --check）",
            "py" to "Python（py_compile）",
            "kt" to "Kotlin（需完整 classpath，单文件检查意义有限）",
        )

        /** POSIX 单引号转义（防命令注入 —— 路径来自模型） */
        private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }

    inner class LspTool : Tool() {
        override val name = "LSP"
        override val description =
            "调用 Language Server Protocol 获取代码诊断、类型信息、定义跳转、补全建议。" +
                "支持 TypeScript/JavaScript (.ts/.tsx/.js/.jsx/.mjs/.cjs) 和 Python (.py)。" +
                "操作：\n" +
                "  · diagnostic: 获取文件诊断（错误/警告）—— **本端可用**\n" +
                "  · hover: 获取指定位置的类型信息（需 line、character）\n" +
                "  · definition: 跳转到定义（需 line、character）\n" +
                "  · completion: 获取补全建议（需 line、character）\n" +
                "行号和列号从 1 开始。\n" +
                "⚠️ hover/definition/completion 需要常驻 language server 进程，" +
                "**本端（Android APK）不可用** —— 调了会返回说明和替代方案。"
        override val isReadOnly = true
        override val isConcurrencySafe = false   // 与 Node 版一致：诊断会读文件状态，不并发
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("目标文件路径"),
            "action" to ToolSchema.string(
                "要执行的 LSP 操作",
                enum = listOf("diagnostic", "hover", "definition", "completion"),
            ),
            "line" to ToolSchema.integer("行号（从1开始，用于 hover/definition/completion）", minimum = 1),
            "character" to ToolSchema.integer("列号（从1开始，用于 hover/definition/completion）", minimum = 1),
            required = listOf("file_path", "action"),
        )

        override fun validateInput(input: JsonObject): String? {
            val action = input.str("action")
            if (input.str("file_path").isNullOrBlank()) return "file_path is required"
            if (action.isNullOrBlank()) return "action is required"
            if (action !in listOf("diagnostic", "hover", "definition", "completion")) {
                return "action 必须是: diagnostic / hover / definition / completion"
            }
            // 对齐 Node 版：三个交互操作必须给坐标
            if (action in listOf("hover", "definition", "completion")) {
                if (input.int("line") == null || input.int("character") == null) {
                    return "$action 需要 line 和 character 参数"
                }
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val action = input.str("action")!!
                val rawPath = input.str("file_path")!!
                val file = if (File(rawPath).isAbsolute) File(rawPath) else File(ctx.cwd, rawPath)

                if (!file.exists()) return@withContext ToolResult.notFound("文件不存在: ${file.absolutePath}")

                // ── 交互能力：诚实降级 ──────────────────────────────
                if (action != "diagnostic") {
                    val line = input.int("line") ?: 1
                    val ch = input.int("character") ?: 1
                    return@withContext ToolResult.failed(
                        "LSP $action 在本端不可用。\n\n" +
                            "**原因**：$action 需要常驻的 language server 子进程（LSP 是双向 JSON-RPC，" +
                            "server 要一直活着并维护文档状态），而本端的 Bash 通道是**一次性执行**模型，" +
                            "拿不到常驻进程的 stdin/stdout 句柄。\n" +
                            "（补充：Node 版硬编码依赖的 typescript-language-server / pyright " +
                            "在本机 node_modules 里实测也没装 —— 所以这不是移植丢的能力。）\n\n" +
                            "**替代方案**（位置 ${file.absolutePath}:$line:$ch）：\n" +
                            "  · 找定义 → 用 Grep / CodeSearch / Symbols\n" +
                            "  · 看类型 → 用 Read 读源码，或 CodeSearch 找类型声明\n" +
                            "  · 补全建议 → 无对应能力，直接读相关文件\n" +
                            "  · 只要诊断 → action=diagnostic（本端可用）",
                    )
                }

                // ── diagnostic：真实执行 ───────────────────────────
                val ch = channel
                    ?: return@withContext ToolResult.failed(
                        "LSP 诊断需要 Bash 通道（要调用语法检查器）。" +
                            "请先安装内置环境或配置外接 Termux。",
                    )

                val quoted = shellQuote(file.absolutePath)
                val cmd = when (file.extension.lowercase()) {
                    "js", "jsx", "mjs", "cjs" -> "node --check $quoted"
                    "ts", "tsx" -> "npx -y tsc --noEmit --pretty false $quoted 2>&1 | head -60"
                    "py", "pyi" -> "python3 -m py_compile $quoted && echo 'py_compile OK'"
                    "kt", "kts" -> null   // 需要完整 classpath，单文件检查无意义
                    else -> null
                }

                if (cmd == null) {
                    val ext = file.extension.lowercase()
                    return@withContext ToolResult.ok(
                        "LSP diagnostic：扩展名 .$ext 不支持单文件诊断。\n" +
                            "支持的扩展名：${SUPPORTED.entries.joinToString(", ") { ".${it.key}（${it.value}）" }}",
                    )
                }

                val res = try {
                    ch.execute(cmd, ctx.cwd.takeIf { it.isNotBlank() }, DIAG_TIMEOUT_MS) { }
                } catch (e: Throwable) {
                    return@withContext ToolResult.Error("诊断执行失败：${e.message}", ToolResult.INTERNAL)
                }

                val out = buildString {
                    if (res.stdout.isNotBlank()) append(res.stdout.trimEnd())
                    if (res.stderr.isNotBlank()) {
                        if (isNotEmpty()) append('\n')
                        append(res.stderr.trimEnd())
                    }
                }.trim()

                when {
                    res.timedOut -> ToolResult.failed("诊断超时（${DIAG_TIMEOUT_MS / 1000}s）：$cmd")
                    res.exitCode == 0 && (out.isEmpty() || out.contains("OK")) ->
                        ToolResult.ok("✓ 无诊断错误（${file.name}）")
                    out.isBlank() -> ToolResult.ok("✓ 无诊断输出（退出码 ${res.exitCode}）")
                    else -> ToolResult.ok(out)
                }
            }
    }
}
