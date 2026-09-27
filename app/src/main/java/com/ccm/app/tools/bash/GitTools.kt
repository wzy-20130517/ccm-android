package com.ccm.app.tools.bash

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.core.tool.ToolSchema.strList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Git 工具组（5 个）—— 查看状态/差异/历史，暂存与提交。
 *
 * 参照 Node 版 `core/git.mjs`（85 行，5 个工具）。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么走 BashChannel 而不是自己起 git 进程
 * ══════════════════════════════════════════════════════════════
 *
 * git 只在 **rootfs 内**（proot 环境）或 **Termux 内** 装好，Android 本体没有。
 * 而这两条通道已经由 [BashChannel] 封装好了（含环境变量清理、工作目录映射、
 * 超时与 kill-on-exit 那一堆真机实测参数）。
 *
 * 自己起 `ProcessBuilder("git", ...)` 的后果：在 proot 场景下 `git` 不在
 * Android 的 PATH 里，**必然失败**；就算能跑，也拿不到 rootfs 的工作目录映射。
 * 所以**复用通道**是唯一正确做法，不是图省事。
 *
 * ══════════════════════════════════════════════════════════════
 *  参数传递：必须 shell 转义（安全关键）
 * ══════════════════════════════════════════════════════════════
 *
 * [BashChannel.execute] 收的是**一整条 shell 命令字符串**（不是 argv 数组），
 * 而 Node 版用的是 `execFileSync('git', args)` —— argv 数组天然免注入。
 * 移植时必须补上转义，否则 commit message 里的 `; rm -rf /` 会被执行。
 *
 * 见 [shellQuote]：单引号包裹 + 内部单引号按 `'\''` 转义，这是 POSIX 下
 * 唯一稳妥的写法（双引号里 `$`/反引号/`\` 仍会展开，不够）。
 *
 * ══════════════════════════════════════════════════════════════
 *  超时与输出上限
 * ══════════════════════════════════════════════════════════════
 *
 * git 是本地操作，正常都是毫秒级；给 30s 是防「巨大仓库首次 status」
 * 这类极端情况。输出上限 10MB 对齐 Node 版的 `maxBuffer`。
 */
class GitTools(
    private val primary: BashChannel,
    private val fallback: BashChannel? = null,
) {

    companion object {
        /** git 命令超时 30s（本地操作，正常毫秒级） */
        private const val GIT_TIMEOUT_MS = 30_000L

        /** 输出上限（对齐 Node 版 maxBuffer = 10MB） */
        private const val MAX_OUTPUT_CHARS = 10 * 1024 * 1024

        /**
         * POSIX shell 单引号转义。
         *
         * `'` → `'\''`（结束引号 + 转义单引号 + 重新开引号），
         * 其余字符原样放进单引号里 —— 单引号内一切都不展开，
         * 所以 `$`、反引号、`\`、`;`、`|` 全部安全。
         */
        private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }

    /**
     * 跑一条 git 命令。
     *
     * @param sub 子命令与参数（**已转义**的片段，直接拼进命令行）
     * @param workDir 工作目录（可选；空 = 通道默认目录）
     */
    private suspend fun runGit(
        ctx: ToolContext,
        sub: List<String>,
        workDir: String?,
    ): ToolResult = withContext(Dispatchers.IO) {
        val cmd = (listOf("git") + sub).joinToString(" ")

        // 主通道不可用就换兜底（对齐 BashTool 的策略）
        val channel = if (primary.isAvailable()) primary else fallback
            ?: return@withContext ToolResult.Error(
                "Bash 通道不可用：${primary.unavailableReason() ?: "未知原因"}",
                ToolResult.INTERNAL,
            )

        val res = try {
            channel.execute(cmd, workDir, GIT_TIMEOUT_MS) { /* 不需要实时进度 */ }
        } catch (e: Throwable) {
            return@withContext ToolResult.Error("执行 git 失败：${e.message}", ToolResult.INTERNAL)
        }

        val out = buildString {
            if (res.stdout.isNotBlank()) append(res.stdout.trimEnd())
            if (res.stderr.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(res.stderr.trimEnd())
            }
        }.take(MAX_OUTPUT_CHARS)

        when {
            res.timedOut -> ToolResult.failed("git 超时（${GIT_TIMEOUT_MS / 1000}s）：$cmd")
            res.exitCode != 0 -> {
                // git 的报错（"not a git repository" 等）走 stderr，对模型很有用 ——
                // 带上原始输出而不是只说「失败」，模型才知道是路径不对还是没初始化
                ToolResult.failed(out.ifBlank { "git 退出码 ${res.exitCode}：$cmd" })
            }
            out.isBlank() -> ToolResult.ok("(无输出)")
            else -> ToolResult.ok(out)
        }
    }

    /** 取工作目录：给了 path 就解析它，否则用 ctx 的 cwd。 */
    private fun workDirOf(input: JsonObject, ctx: ToolContext): String? =
        input.str("path")?.takeIf { it.isNotBlank() } ?: ctx.cwd.takeIf { it.isNotBlank() }

    // ══════════════════════════════════════════════════════════════
    //  GitStatus
    // ══════════════════════════════════════════════════════════════

    inner class GitStatusTool : Tool() {
        override val name = "GitStatus"
        override val description =
            "查看仓库状态（git status）。**只读工具** —— 问「改了哪些文件」「当前分支」时用它，" +
                "不要为此跑 Bash。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 10_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "path" to ToolSchema.string("仓库路径（可选，默认当前工作目录）"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            runGit(ctx, listOf("status"), workDirOf(input, ctx))
    }

    // ══════════════════════════════════════════════════════════════
    //  GitDiff
    // ══════════════════════════════════════════════════════════════

    inner class GitDiffTool : Tool() {
        override val name = "GitDiff"
        override val description =
            "查看文件差异（git diff）。只读工具。" +
                "staged=true 时看已暂存的改动（等价 git diff --staged）。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "path" to ToolSchema.string("仓库路径（可选，默认当前工作目录）"),
            "staged" to ToolSchema.boolean("true = 看已暂存的改动（--staged）"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val sub = buildList {
                add("diff")
                if (input.bool("staged") == true) add("--staged")
            }
            return runGit(ctx, sub, workDirOf(input, ctx))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GitLog
    // ══════════════════════════════════════════════════════════════

    inner class GitLogTool : Tool() {
        override val name = "GitLog"
        override val description = "查看提交历史（git log --oneline）。只读工具。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 10_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "path" to ToolSchema.string("仓库路径（可选，默认当前工作目录）"),
            "limit" to ToolSchema.integer("返回条数，默认 20", minimum = 1, maximum = 200),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val n = (input.int("limit") ?: 20).coerceIn(1, 200)
            return runGit(ctx, listOf("log", "--oneline", "-n$n"), workDirOf(input, ctx))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GitAdd
    // ══════════════════════════════════════════════════════════════

    inner class GitAddTool : Tool() {
        override val name = "GitAdd"
        override val description =
            "暂存文件（git add）。**破坏性工具** —— 会改变暂存区状态。\n" +
                "files 是必填的文件列表，如 [\"src/a.kt\", \"src/b.kt\"]。"
        override val isDestructive = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "files" to ToolSchema.stringArray("要暂存的文件列表（必填，至少一个）"),
            "path" to ToolSchema.string("仓库路径（可选，默认当前工作目录）"),
            required = listOf("files"),
        )

        override fun validateInput(input: JsonObject): String? {
            val files = input.strList("files")
            if (files.isNullOrEmpty()) return "files is required and must be a non-empty array"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val files = input.strList("files")!!
            // 逐个转义 —— 文件名可能含空格/引号/分号
            val sub = listOf("add") + files.map { shellQuote(it) }
            return runGit(ctx, sub, workDirOf(input, ctx))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GitCommit
    // ══════════════════════════════════════════════════════════════

    inner class GitCommitTool : Tool() {
        override val name = "GitCommit"
        override val description =
            "提交暂存区（git commit -m）。**破坏性工具** —— 会产生一条提交记录。\n" +
                "提交前一般先用 GitAdd 暂存；只提交已暂存的内容。"
        override val isDestructive = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "message" to ToolSchema.string("提交信息（必填）"),
            "path" to ToolSchema.string("仓库路径（可选，默认当前工作目录）"),
            required = listOf("message"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("message").isNullOrBlank()) "message is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val msg = input.str("message")!!
            // ⚠️ 转义是安全关键：commit message 是模型生成的自由文本，
            // 里面完全可能出现 `"; rm -rf / #` 这类内容
            return runGit(ctx, listOf("commit", "-m", shellQuote(msg)), workDirOf(input, ctx))
        }
    }
}
