package com.ccm.app.tools.net

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * GitHub 工具组（9 个）—— 读写仓库 issue / PR / 文件。
 *
 * 参照 Node 版 `core/tools-github.mjs`（383 行）+ `core/github.mjs`（137 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 与 /backup 无关（各自管 token）
 * ══════════════════════════════════════════════════════════════
 *
 * 用户可能配了两套 GitHub 凭据：
 * - `/github login` 设的 token → **本文件用**（读仓库、发 issue）
 * - `/backup github` 的 token → 备份用（打包上传，另一套配置）
 *
 * 两边互不影响，改一个不会动另一个。**别混**。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 写入类操作的提醒
 * ══════════════════════════════════════════════════════════════
 *
 * `GitHubComment` / `GitHubCreateIssue` **会让仓库成员看到** ——
 * 发之前确认内容无误。工具描述里已写明这一点。
 *
 * @param token GitHub PAT（null = 未配置，工具会提示怎么配）
 * @param defaultRepo 默认仓库（`owner/name`，可空）
 */
class GitHubTools(
    /**
     * token 提供者（**每次调用时现取**，不是构造快照）。
     *
     * 【2026-10-07 改惰性】原为构造参数快照 —— AppGraph 装配时读一次
     * github.json，启动后 `/github login` 写的 token 进不了已建好的
     * 实例，必须重启 App 才生效（连 /github 自己的提示文案都写着
     * 「需重启」）。改成提供者后配置**立即生效**，与 toolsProvider
     * 的惰性取值同一思路。
     */
    private val tokenProvider: () -> String?,
    /** 默认仓库提供者（同上，现取）。 */
    private val defaultRepoProvider: () -> String? = { null },
) {
    /** 兼容旧调用点的便捷构造：固定值包装成提供者。 */
    constructor(token: String?, defaultRepo: String? = null) : this(
        tokenProvider = { token },
        defaultRepoProvider = { defaultRepo },
    )

    private val token: String? get() = tokenProvider()
    private val defaultRepo: String? get() = defaultRepoProvider()

    companion object {
        private const val API = "https://api.github.com"
        private const val UA = "CCM-Android/1.0"
        private const val TIMEOUT_MS = 20_000
    }

    /** API 调用结果 */
    private sealed class ApiResult {
        data class Ok(val code: Int, val body: String) : ApiResult()
        data class Fail(val code: Int, val message: String) : ApiResult()
    }

    private fun requireToken(): ToolResult? =
        if (token.isNullOrBlank()) {
            ToolResult.Error(
                "GitHub token 未配置。用 /github login 设置（需要 repo 权限的 PAT）。",
                ToolResult.PERMISSION_DENIED,
            )
        } else {
            null
        }

    /** 解析 repo 参数：支持 `owner/name`、完整 URL、或省略（用默认仓库） */
    private fun resolveRepo(input: JsonObject): Pair<String, String>? {
        val raw = input.str("repo")?.takeIf { it.isNotBlank() } ?: defaultRepo ?: return null
        // 支持 https://github.com/owner/name、git@github.com:owner/name.git 等形式
        val cleaned = raw.trim()
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .removePrefix("git@github.com:")
            .removeSuffix(".git")
            .trim('/')
        val parts = cleaned.split("/")
        if (parts.size < 2) return null
        return parts[0] to parts[1]
    }

    private suspend fun api(
        path: String,
        method: String = "GET",
        body: String? = null,
    ): ApiResult = withContext(Dispatchers.IO) {
        try {
            val url = if (path.startsWith("http")) path else "$API$path"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("Authorization", "Bearer $token")
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            try {
                body?.let { conn.outputStream.use { os -> os.write(it.toByteArray(Charsets.UTF_8)) } }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code in 200..299) ApiResult.Ok(code, text)
                else ApiResult.Fail(code, extractMessage(text))
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (e: Throwable) {
            ApiResult.Fail(-1, e.message ?: "网络错误")
        }
    }

    private fun extractMessage(body: String): String = try {
        JSONObject(body).optString("message", body.take(200))
    } catch (_: Throwable) {
        body.take(200)
    }

    private fun fail(what: String, r: ApiResult.Fail): ToolResult {
        val hint = when (r.code) {
            401 -> "（token 无效或过期，用 /github login 重设）"
            403 -> "（权限不足或触发了限流）"
            404 -> "（仓库/资源不存在，或 token 无权限访问私有仓库）"
            else -> ""
        }
        return ToolResult.Error("$what 失败：HTTP ${r.code} ${r.message} $hint", ToolResult.NETWORK)
    }

    // ══════════════════════════════════════════════════════════════
    //  GitHubRepo
    // ══════════════════════════════════════════════════════════════

    inner class GitHubRepoTool : Tool() {
        override val name = "GitHubRepo"
        override val description =
            "查看 GitHub 仓库概览（描述/星标/语言/默认分支/最近提交）。" +
                "不传 repo 时用 /github repo 设置的仓库。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 4_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "repo" to ToolSchema.string("owner/name 或完整 URL；省略则用已设置的仓库"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            requireToken()?.let { return it }
            val (owner, name) = resolveRepo(input)
                ?: return ToolResult.invalidInput("未指定仓库，且没有默认仓库。请传 repo 或用 /github repo 设置。")

            val info = api("/repos/$owner/$name")
            val d = when (info) {
                is ApiResult.Ok -> try {
                    JSONObject(info.body)
                } catch (_: Throwable) {
                    return ToolResult.Error("返回格式异常", ToolResult.NETWORK)
                }
                is ApiResult.Fail -> return fail("获取仓库", info)
            }

            val sb = StringBuilder()
            sb.append("# ${d.optString("full_name")}${if (d.optBoolean("private")) "（私有）" else ""}\n")
            sb.append(d.optString("description", "(无描述)")).append("\n\n")
            sb.append("默认分支: ${d.optString("default_branch")}\n")
            sb.append("语言: ${d.optString("language", "—")}")
            sb.append(" · 星标: ${d.optInt("stargazers_count")}")
            sb.append(" · Fork: ${d.optInt("forks_count")}\n")
            sb.append("大小: ${"%.1f".format(d.optInt("size") / 1024.0)} MB")
            sb.append(" · 更新: ${d.optString("updated_at").take(10)}\n")
            d.optJSONArray("topics")?.let { topics ->
                if (topics.length() > 0) {
                    sb.append("标签: ${(0 until topics.length()).joinToString(", ") { topics.optString(it) }}\n")
                }
            }

            // 最近提交
            when (val commits = api("/repos/$owner/$name/commits?per_page=5")) {
                is ApiResult.Ok -> {
                    try {
                        val arr = JSONArray(commits.body)
                        if (arr.length() > 0) {
                            sb.append("\n最近提交:\n")
                            for (i in 0 until arr.length()) {
                                val c = arr.optJSONObject(i) ?: continue
                                val sha = c.optString("sha").take(7)
                                val commit = c.optJSONObject("commit")
                                val msg = commit?.optString("message", "")?.lineSequence()?.firstOrNull() ?: ""
                                val author = commit?.optJSONObject("author")?.optString("name", "?") ?: "?"
                                sb.append("  $sha  ${msg.take(70)}  ($author)\n")
                            }
                        }
                    } catch (_: Throwable) {
                        // 提交列表失败不影响概览
                    }
                }
                is ApiResult.Fail -> { /* 忽略 */ }
            }

            return ToolResult.ok(sb.toString())
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GitHubIssues
    // ══════════════════════════════════════════════════════════════

    inner class GitHubIssuesTool : Tool() {
        override val name = "GitHubIssues"
        override val description = "列出 GitHub 仓库的 issue（默认 open）。可选按标签/关键词过滤。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 6_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "repo" to ToolSchema.string("owner/name；省略用默认仓库"),
            "state" to ToolSchema.string("默认 open", enum = listOf("open", "closed", "all")),
            "labels" to ToolSchema.string("逗号分隔的标签，如 \"bug,help wanted\""),
            "search" to ToolSchema.string("按标题/正文搜索的关键词"),
            "limit" to ToolSchema.integer("返回条数，默认 15，最多 50", minimum = 1, maximum = 50),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            requireToken()?.let { return it }
            val (owner, name) = resolveRepo(input)
                ?: return ToolResult.invalidInput("未指定仓库（传 repo 或设置默认）")

            val limit = (input.int("limit") ?: 15).coerceIn(1, 50)
            val state = input.str("state")?.takeIf { it in listOf("open", "closed", "all") } ?: "open"

            // 有 search 时走搜索端点（全站搜索 + repo: 限定）
            val search = input.str("search")
            if (!search.isNullOrBlank()) {
                val q = "repo:$owner/$name $search type:issue state:$state"
                val r = api("/search/issues?q=${enc(q)}&per_page=$limit")
                return when (r) {
                    is ApiResult.Fail -> fail("搜索 issue", r)
                    is ApiResult.Ok -> {
                        val items = JSONObject(r.body).optJSONArray("items")
                        if (items == null || items.length() == 0) {
                            ToolResult.ok("没找到匹配「$search」的 issue")
                        } else {
                            ToolResult.ok(
                                (0 until items.length()).joinToString("\n\n") { i ->
                                    val it = items.optJSONObject(i) ?: return@joinToString ""
                                    "#${it.optInt("number")} [${it.optString("state")}] ${it.optString("title")}\n" +
                                        "  ${it.optJSONObject("user")?.optString("login")} · " +
                                        "${it.optString("created_at").take(10)} · ${it.optInt("comments")} 评论"
                                },
                            )
                        }
                    }
                }
            }

            // 常规列表
            val params = "state=$state&per_page=$limit" +
                (input.str("labels")?.let { "&labels=${enc(it)}" } ?: "")
            return when (val r = api("/repos/$owner/$name/issues?$params")) {
                is ApiResult.Fail -> fail("列出 issue", r)
                is ApiResult.Ok -> {
                    val arr = try {
                        JSONArray(r.body)
                    } catch (_: Throwable) {
                        return ToolResult.Error("返回格式异常", ToolResult.NETWORK)
                    }
                    // ⚠️ GitHub 的 issues 端点会把 PR 混进来 —— 按 pull_request 字段剔除
                    val items = (0 until arr.length())
                        .mapNotNull { arr.optJSONObject(it) }
                        .filter { it.isNull("pull_request") }
                    if (items.isEmpty()) {
                        ToolResult.ok("没有 $state 状态的 issue")
                    } else {
                        ToolResult.ok(
                            items.joinToString("\n") { it ->
                                val labels = it.optJSONArray("labels")?.let { ls ->
                                    (0 until ls.length()).mapNotNull { i ->
                                        ls.optJSONObject(i)?.optString("name")
                                    }.joinToString(",")
                                } ?: ""
                                "#${it.optInt("number")} ${it.optString("title")}\n" +
                                    "  ${it.optJSONObject("user")?.optString("login")} · " +
                                    "${it.optString("created_at").take(10)}" +
                                    (if (labels.isNotEmpty()) " · $labels" else "") +
                                    (if (it.optInt("comments") > 0) " · ${it.optInt("comments")} 评论" else "")
                            },
                        )
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GitHubIssueView
    // ══════════════════════════════════════════════════════════════

    inner class GitHubIssueViewTool : Tool() {
        override val name = "GitHubIssueView"
        override val description = "读单个 GitHub issue/PR 的正文与全部评论。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "number" to ToolSchema.integer("issue 或 PR 编号"),
            "repo" to ToolSchema.string("owner/name；省略用默认仓库"),
            "comments" to ToolSchema.boolean("是否附带评论，默认 true"),
            required = listOf("number"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.int("number") == null) "number is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            requireToken()?.let { return it }
            val (owner, name) = resolveRepo(input)
                ?: return ToolResult.invalidInput("未指定仓库")
            val num = input.int("number")!!

            val issue = when (val r = api("/repos/$owner/$name/issues/$num")) {
                is ApiResult.Fail -> return fail("获取 issue", r)
                is ApiResult.Ok -> try {
                    JSONObject(r.body)
                } catch (_: Throwable) {
                    return ToolResult.Error("返回格式异常", ToolResult.NETWORK)
                }
            }

            val sb = StringBuilder()
            val isPr = !issue.isNull("pull_request")
            sb.append("#${issue.optInt("number")} ${issue.optString("title")}")
            if (isPr) sb.append("  [PR]")
            sb.append("\n")
            sb.append("状态: ${issue.optString("state")}")
            sb.append("  作者: ${issue.optJSONObject("user")?.optString("login")}")
            sb.append("  创建: ${issue.optString("created_at").take(10)}\n")
            issue.optJSONArray("labels")?.let { ls ->
                if (ls.length() > 0) {
                    sb.append("标签: ${(0 until ls.length()).joinToString(", ") { ls.optJSONObject(it)?.optString("name") ?: "" }}\n")
                }
            }
            sb.append("\n--- 正文 ---\n")
            sb.append(issue.optString("body", "(空)").take(8_000))

            if (input.bool("comments") != false) {
                when (val r = api("/repos/$owner/$name/issues/$num/comments?per_page=50")) {
                    is ApiResult.Ok -> {
                        try {
                            val arr = JSONArray(r.body)
                            if (arr.length() > 0) {
                                sb.append("\n\n--- 评论（${arr.length()} 条）---\n")
                                for (i in 0 until arr.length()) {
                                    val c = arr.optJSONObject(i) ?: continue
                                    sb.append("\n[${c.optJSONObject("user")?.optString("login")} · ${c.optString("created_at").take(10)}]\n")
                                    sb.append(c.optString("body", "").take(2_000)).append("\n")
                                }
                            }
                        } catch (_: Throwable) {
                            // 评论失败不影响正文
                        }
                    }
                    is ApiResult.Fail -> sb.append("\n（评论拉取失败：HTTP ${r.code}）")
                }
            }

            return ToolResult.ok(sb.toString())
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GitHubPRs / GitHubPRComments
    // ══════════════════════════════════════════════════════════════

    inner class GitHubPRsTool : Tool() {
        override val name = "GitHubPRs"
        override val description = "列出 GitHub 仓库的 Pull Request。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 6_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "repo" to ToolSchema.string("owner/name；省略用默认仓库"),
            "state" to ToolSchema.string("默认 open", enum = listOf("open", "closed", "all")),
            "limit" to ToolSchema.integer("返回条数，默认 15，最多 50", minimum = 1, maximum = 50),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            requireToken()?.let { return it }
            val (owner, name) = resolveRepo(input)
                ?: return ToolResult.invalidInput("未指定仓库")
            val limit = (input.int("limit") ?: 15).coerceIn(1, 50)
            val state = input.str("state")?.takeIf { it in listOf("open", "closed", "all") } ?: "open"

            return when (val r = api("/repos/$owner/$name/pulls?state=$state&per_page=$limit")) {
                is ApiResult.Fail -> fail("列出 PR", r)
                is ApiResult.Ok -> {
                    val arr = try {
                        JSONArray(r.body)
                    } catch (_: Throwable) {
                        return ToolResult.Error("返回格式异常", ToolResult.NETWORK)
                    }
                    if (arr.length() == 0) {
                        ToolResult.ok("没有 $state 状态的 PR")
                    } else {
                        ToolResult.ok(
                            (0 until arr.length()).joinToString("\n") { i ->
                                val p = arr.optJSONObject(i) ?: return@joinToString ""
                                val draft = if (p.optBoolean("draft")) " [草稿]" else ""
                                "#${p.optInt("number")} ${p.optString("title")}$draft\n" +
                                    "  ${p.optJSONObject("user")?.optString("login")} · " +
                                    "${p.optString("head")?.let { "" }}${p.optJSONObject("head")?.optString("ref")} → " +
                                    "${p.optJSONObject("base")?.optString("ref")} · " +
                                    "${p.optString("created_at").take(10)}"
                            },
                        )
                    }
                }
            }
        }
    }

    inner class GitHubPRCommentsTool : Tool() {
        override val name = "GitHubPRComments"
        override val description = "读某个 PR 的评审意见（含代码行级评论与 diff 上下文）。对齐官方 /pr-comments。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "number" to ToolSchema.integer("PR 编号"),
            "repo" to ToolSchema.string("owner/name；省略用默认仓库"),
            required = listOf("number"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.int("number") == null) "number is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            requireToken()?.let { return it }
            val (owner, name) = resolveRepo(input)
                ?: return ToolResult.invalidInput("未指定仓库")
            val num = input.int("number")!!

            // 代码行级评论（含 diff hunk 上下文）
            val r = api("/repos/$owner/$name/pulls/$num/comments?per_page=100")
            return when (r) {
                is ApiResult.Fail -> fail("获取 PR 评论", r)
                is ApiResult.Ok -> {
                    val arr = try {
                        JSONArray(r.body)
                    } catch (_: Throwable) {
                        return ToolResult.Error("返回格式异常", ToolResult.NETWORK)
                    }
                    if (arr.length() == 0) {
                        return ToolResult.ok("这个 PR 还没有代码行级评论（issue 级评论用 GitHubIssueView）")
                    }
                    val sb = StringBuilder("PR #$num 的代码评论（${arr.length()} 条）：\n")
                    for (i in 0 until arr.length()) {
                        val c = arr.optJSONObject(i) ?: continue
                        sb.append("\n---\n")
                        sb.append("${c.optString("path")}:${c.optInt("line", c.optInt("original_line"))}")
                        sb.append("  @${c.optJSONObject("user")?.optString("login")}\n")
                        c.optString("diff_hunk", "").take(800).let { hunk ->
                            if (hunk.isNotEmpty()) sb.append("```diff\n$hunk\n```\n")
                        }
                        sb.append(c.optString("body", "").take(2_000)).append("\n")
                    }
                    ToolResult.ok(sb.toString())
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GitHubFile
    // ══════════════════════════════════════════════════════════════

    inner class GitHubFileTool : Tool() {
        override val name = "GitHubFile"
        override val description =
            "读取 GitHub 仓库里的文件（不需要本地 clone）。用于看远端代码、README、配置。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "path" to ToolSchema.string("仓库内路径，如 src/index.ts"),
            "repo" to ToolSchema.string("owner/name；省略用默认仓库"),
            "ref" to ToolSchema.string("分支/标签/commit SHA；省略用默认分支"),
            "list" to ToolSchema.boolean("true = 列目录而不是读文件"),
            required = listOf("path"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("path").isNullOrBlank()) "path is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            requireToken()?.let { return it }
            val (owner, name) = resolveRepo(input)
                ?: return ToolResult.invalidInput("未指定仓库")
            val path = input.str("path")!!.trim('/')
            val ref = input.str("ref")?.takeIf { it.isNotBlank() }
            val refQuery = ref?.let { "?ref=${enc(it)}" } ?: ""

            val r = api("/repos/$owner/$name/contents/$path$refQuery")
            return when (r) {
                is ApiResult.Fail -> fail("读取文件", r)
                is ApiResult.Ok -> {
                    val body = r.body.trim()
                    if (body.startsWith("[")) {
                        // 目录列表
                        try {
                            val arr = JSONArray(body)
                            ToolResult.ok(
                                "目录 $path（${arr.length()} 项）：\n" +
                                    (0 until arr.length()).joinToString("\n") { i ->
                                        val it = arr.optJSONObject(i) ?: return@joinToString ""
                                        val icon = if (it.optString("type") == "dir") "📁" else "📄"
                                        "$icon ${it.optString("name")}  (${it.optInt("size")}B)"
                                    },
                            )
                        } catch (e: Throwable) {
                            ToolResult.Error("解析目录失败：${e.message}", ToolResult.INTERNAL)
                        }
                    } else {
                        // 单文件
                        try {
                            val obj = JSONObject(body)
                            val content = obj.optString("content", "")
                            val encoding = obj.optString("encoding")
                            if (encoding == "base64" && content.isNotEmpty()) {
                                val decoded = String(
                                    android.util.Base64.decode(content.replace("\n", ""), android.util.Base64.DEFAULT),
                                    Charsets.UTF_8,
                                )
                                ToolResult.ok("$path（${obj.optInt("size")}B）:\n\n$decoded")
                            } else {
                                ToolResult.ok("$path:\n\n$content")
                            }
                        } catch (e: Throwable) {
                            ToolResult.Error("解析文件失败：${e.message}", ToolResult.INTERNAL)
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GitHubComment（写入）
    // ══════════════════════════════════════════════════════════════

    inner class GitHubCommentTool : Tool() {
        override val name = "GitHubComment"
        override val description =
            "在 GitHub issue 或 PR 下发表评论。**会让仓库成员看到**，发之前确认内容无误。"
        override val isDestructive = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "number" to ToolSchema.integer("issue 或 PR 编号"),
            "body" to ToolSchema.string("评论正文（Markdown）"),
            "repo" to ToolSchema.string("owner/name；省略用默认仓库"),
            required = listOf("number", "body"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.int("number") == null) return "number is required"
            if (input.str("body").isNullOrBlank()) return "body is required"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            requireToken()?.let { return it }
            val (owner, name) = resolveRepo(input)
                ?: return ToolResult.invalidInput("未指定仓库")
            val num = input.int("number")!!

            val payload = JSONObject().put("body", input.str("body")!!).toString()
            return when (val r = api("/repos/$owner/$name/issues/$num/comments", "POST", payload)) {
                is ApiResult.Fail -> fail("发表评论", r)
                is ApiResult.Ok -> {
                    val url = try {
                        JSONObject(r.body).optString("html_url")
                    } catch (_: Throwable) {
                        ""
                    }
                    ToolResult.ok("已发表评论" + if (url.isNotEmpty()) "：$url" else "")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GitHubCreateIssue（写入）
    // ══════════════════════════════════════════════════════════════

    inner class GitHubCreateIssueTool : Tool() {
        override val name = "GitHubCreateIssue"
        override val description =
            "在 GitHub 仓库新建 issue。**会真实创建、仓库成员可见**，提交前确认标题和正文。"
        override val isDestructive = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "title" to ToolSchema.string("issue 标题"),
            "body" to ToolSchema.string("正文（Markdown）"),
            "labels" to ToolSchema.stringArray("可选标签名"),
            "repo" to ToolSchema.string("owner/name；省略用默认仓库"),
            required = listOf("title"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("title").isNullOrBlank()) "title is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            requireToken()?.let { return it }
            val (owner, name) = resolveRepo(input)
                ?: return ToolResult.invalidInput("未指定仓库")

            val payload = JSONObject().apply {
                put("title", input.str("title")!!)
                input.str("body")?.let { put("body", it) }
                (input["labels"] as? kotlinx.serialization.json.JsonArray)?.let { arr ->
                    put("labels", JSONArray(arr.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }))
                }
            }.toString()

            return when (val r = api("/repos/$owner/$name/issues", "POST", payload)) {
                is ApiResult.Fail -> fail("创建 issue", r)
                is ApiResult.Ok -> {
                    val obj = try {
                        JSONObject(r.body)
                    } catch (_: Throwable) {
                        null
                    }
                    ToolResult.ok(
                        "已创建 issue #${obj?.optInt("number")}" +
                            (obj?.optString("html_url")?.takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: ""),
                    )
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助
    // ══════════════════════════════════════════════════════════════

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
