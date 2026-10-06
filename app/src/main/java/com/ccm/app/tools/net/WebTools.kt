package com.ccm.app.tools.net

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.core.tool.ToolSchema.strList
import com.ccm.app.core.tool.ToolSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络工具集 —— WebSearch（Tavily）/ WebFetch。
 *
 * 参照 Node 版 `core/tavily.mjs`（165 行）+ `core/extra-tools.mjs:WebFetchTool`。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ Tavily 的实测结论（2026-09-04 对照实验，不要凭直觉加参数）
 * ══════════════════════════════════════════════════════════════
 *
 * **被实测否决的参数（别加回来）**：
 * | 参数 | 结果 |
 * |---|---|
 * | `topic:'news'` | **灾难**。查中文人名返回完全无关的英文诺奖新闻（跨语言实体链接跑偏） |
 * | `exact_match:true` | HTTP 400，API 不支持 |
 * | `filter_by_language` | 英文查询直接变 0 条 |
 * | `language:'zh'` | 中文结果反而变差且慢 3.6s |
 * | `country:'china'` | 无变化 |
 * | `auto_parameters` | 无变化，耗时翻倍 |
 *
 * **唯一值得开的**：`chunks_per_source:3` —— 结果与默认**完全一致**，
 * 耗时 1675ms → 790ms（快一倍以上）。纯赚。
 *
 * ══════════════════════════════════════════════════════════════
 *  相关性自检（这个功能的价值极高，必须保留）
 * ══════════════════════════════════════════════════════════════
 *
 * Tavily 的 `score` 只是向量距离，跟「答对了没有」无关 ——
 * 实测最高分 0.264 那条是完全不相关的页面。
 *
 * 真正能判断的是「查询里的**实体词**有没有真的出现在结果里」。
 * 冷门中文实体（人名/校名/地方机构）常常一条都没命中，这时必须明确告警，
 * 否则调用方会拿一堆无关结果当答案（真实踩过：搜某老师返回的全是外地学校
 * 名单和英文招聘，AI 摘要还据此**编了事迹**）。
 *
 * 判据细节：不能用「任一关键词命中」—— 通用词（"物理老师"）命中毫无意义。
 * 取**去掉通用后缀后最长的 1-2 个词**作为必须出现的实体。
 */
class WebTools(private val settings: ToolSettings?) {

    companion object {
        private const val TAVILY_URL = "https://api.tavily.com/search"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android) CCM/1.0"

        /** WebFetch 响应上限 5MB（防超大页面先 OOM 再截断） */
        private const val MAX_FETCH_BYTES = 5 * 1024 * 1024

        /** WebFetch 超时 15s（不依赖外层工具超时） */
        private const val FETCH_TIMEOUT_MS = 15_000

        /** 通用后缀词：以这些结尾的词不算「有区分度的实体」 */
        private val GENERIC_SUFFIX = Regex(
            "(老师|教师|学校|中学|小学|大学|问题|方法|怎么|如何|是什么|介绍|资料|新闻|视频|图片|下载|官网|多少|哪个|为什么)$"
        )
    }

    /** Tavily key：优先 settings 的专用字段，再退回通用 get() */
    private fun tavilyKey(): String? =
        settings?.tavilyApiKey?.takeIf { it.isNotBlank() }
            ?: settings?.get("tavilyKey")?.takeIf { it.isNotBlank() }

    // ══════════════════════════════════════════════════════════════
    //  WebSearch（Tavily）
    // ══════════════════════════════════════════════════════════════

    inner class WebSearchTool : Tool() {
        override val name = "WebSearch"
        override val description =
            "联网搜索获取最新信息。输入搜索关键词，返回相关网页标题、URL和摘要。支持中英文搜索。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 15_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "query" to ToolSchema.string("搜索关键词"),
            "max_results" to ToolSchema.integer("最大结果数，默认 5，最多 10", minimum = 1, maximum = 10),
            "search_depth" to ToolSchema.string(
                "搜索深度：basic 快速，advanced 深度（更慢但更全）",
                enum = listOf("basic", "advanced"),
            ),
            "include_answer" to ToolSchema.boolean(
                "是否包含 AI 生成的摘要答案，默认 true。注意：摘要经常把不相关的事实缝在一起，只能当线索",
            ),
            "include_domains" to ToolSchema.stringArray(
                "只在这些域名内搜索（如 [\"github.com\",\"stackoverflow.com\"]），查技术问题时很有效",
            ),
            "exclude_domains" to ToolSchema.stringArray("排除这些域名（如排除 CSDN 之类的低质量转载站）"),
            "days" to ToolSchema.integer("只要最近 N 天的内容（查时效性话题用）", minimum = 1),
            required = listOf("query"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("query").isNullOrBlank()) return "query is required"
            input.int("max_results")?.let {
                if (it < 1 || it > 10) return "max_results must be 1-10"
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val apiKey = tavilyKey()
                ?: return ToolResult.Error(
                    // ⚠️ 原文案「请用 /config 设置 tavilyApiKey（或用 /key 命令）」两个都是错的
                    // （/config 是切 Provider、/key 是 LLM 的 key）—— 模型照着错误文案教用户，
                    // 用户就问出了「taly key 怎么设」。2026-10-06 已加 /tvly，指向它。
                    "Tavily API key 未配置。用 /tvly <tvly-...> 设置（key 形如 tvly-xxxxxxxx，" +
                        "注册 https://app.tavily.com/；也可在设置页填「Tavily 密钥」）。" +
                        "没 key 时改用 SearchInfo（国内源，不需要 key）。",
                    ToolResult.PERMISSION_DENIED,
                )

            val query = input.str("query")!!
            val body = JSONObject().apply {
                put("api_key", apiKey)
                put("query", query)
                put("max_results", (input.int("max_results") ?: 5).coerceIn(1, 10))
                put("search_depth", if (input.str("search_depth") == "advanced") "advanced" else "basic")
                put("include_answer", input.bool("include_answer") != false)
                put("include_raw_content", false)
                // chunks_per_source：唯一值得开的参数（快一倍，结果不变）
                put("chunks_per_source", 3)
                input.strList("include_domains")?.take(20)?.let { list ->
                    if (list.isNotEmpty()) put("include_domains", org.json.JSONArray(list))
                }
                input.strList("exclude_domains")?.take(20)?.let { list ->
                    if (list.isNotEmpty()) put("exclude_domains", org.json.JSONArray(list))
                }
                input.int("days")?.takeIf { it > 0 }?.let { put("days", it.coerceAtMost(365)) }
            }

            val raw = try {
                withContext(Dispatchers.IO) {
                    httpPostJson(TAVILY_URL, body.toString(), ctx)
                }
            } catch (e: Throwable) {
                return ToolResult.Error("Tavily 请求失败：${e.message}", ToolResult.NETWORK)
            }

            val data = try {
                JSONObject(raw)
            } catch (e: Throwable) {
                return ToolResult.Error("Tavily 返回非法 JSON：${raw.take(200)}", ToolResult.NETWORK)
            }

            val results = data.optJSONArray("results") ?: org.json.JSONArray()
            val sb = StringBuilder()

            data.optString("answer", "").takeIf { it.isNotEmpty() }?.let {
                sb.append("**AI 摘要（仅供参考，可能拼接不相关事实）:** $it\n\n")
            }

            sb.append("**搜索结果:**\n")
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                sb.append("### ${r.optString("title")}\n")
                sb.append("${r.optString("url")}\n")
                sb.append("${r.optString("content", "(无内容)")}\n\n")
            }

            // ── 相关性自检 ────────────────────────────────────────
            val warning = relevanceWarning(query, results)
            if (warning != null) sb.append(warning)

            return ToolResult.ok(sb.toString().ifEmpty { "(无结果)" })
        }

        /**
         * 相关性告警：结果里一条都不含「有区分度的实体」时给出明确警告。
         *
         * 为什么不能用「任一关键词命中」：实测查「金爱梅 新平中学 物理老师」时，
         * 有两条结果因为含「物理老师」这个通用词被算作命中（实际是英文招聘岗位
         * 和广东某支教老师），警告因此不触发 —— 通用词命中毫无意义。
         *
         * 改为只看**区分度最高的词**：去掉常见通用后缀词，取最长的 1-2 个
         * （人名/校名/专有名词通常就是它），只要它一条都没命中就警告。
         */
        private fun relevanceWarning(query: String, results: org.json.JSONArray): String? {
            if (results.length() == 0) return null

            val keywords = query.split(Regex("[\\s,，、]+"))
                .filter { it.length >= 2 }
            if (keywords.isEmpty()) return null

            val distinctive = keywords
                .filter { !GENERIC_SUFFIX.containsMatchIn(it) }
                .sortedByDescending { it.length }
                .take(2)
            val probe = distinctive.ifEmpty { keywords }

            var hits = 0
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val body = "${r.optString("title")}${r.optString("content")}"
                if (probe.any { body.contains(it) }) hits++
            }

            if (hits > 0) return null
            return "\n**⚠ 相关性警告**：${results.length()} 条结果中没有任何一条包含关键实体（${probe.joinToString(" ")}）。\n" +
                "Tavily 对冷门中文实体（人名、校名、地方机构）覆盖很差，这批结果很可能全部无关，不要基于它下结论。\n"
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  WebFetch
    // ══════════════════════════════════════════════════════════════

    inner class WebFetchTool : Tool() {
        override val name = "WebFetch"
        override val description = "抓取网页内容"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 15_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "url" to ToolSchema.string("要抓取的网页 URL（http/https）"),
            required = listOf("url"),
        )

        override fun validateInput(input: JsonObject): String? {
            val url = input.str("url")
            if (url.isNullOrBlank()) return "url is required"
            return try {
                val u = URL(url)
                if (u.protocol != "http" && u.protocol != "https") {
                    "不支持的协议 ${u.protocol}（只允许 http/https）"
                } else {
                    null
                }
            } catch (_: Throwable) {
                "url is not a valid URL"
            }
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val urlText = input.str("url")!!

            // ── SSRF 防护：阻断内网/环回/云元数据地址 ───────────────
            val host = try {
                URL(urlText).host.lowercase()
            } catch (_: Throwable) {
                return ToolResult.invalidInput("无效 URL")
            }
            if (isBlockedHost(host)) {
                return ToolResult.Error(
                    "拒绝访问内网/环回地址: $host（防 SSRF）",
                    ToolResult.PERMISSION_DENIED,
                )
            }

            return try {
                val (status, raw) = withContext(Dispatchers.IO) {
                    httpGet(urlText, ctx)
                }
                if (status !in 200..299) {
                    return ToolResult.Error("HTTP $status", ToolResult.NETWORK)
                }
                val text = stripHtml(raw).take(15_000)
                ToolResult.ok("URL: $urlText\n\n$text")
            } catch (e: Throwable) {
                ToolResult.Error("Fetch failed: ${e.message}", ToolResult.NETWORK)
            }
        }

        /** 阻断内网/环回/链路本地/云元数据地址（SSRF 防护） */
        private fun isBlockedHost(host: String): Boolean {
            if (host.isEmpty()) return true
            // 云元数据服务的经典地址
            if (host == "169.254.169.254" || host == "metadata.google.internal") return true
            if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true

            val parts = host.split(".")
            if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
                val (a, b) = parts[0].toInt() to parts[1].toInt()
                return when {
                    a == 127 -> true                    // 环回
                    a == 10 -> true                     // 私有 A 类
                    a == 192 && b == 168 -> true        // 私有 C 类
                    a == 172 && b in 16..31 -> true     // 私有 B 类
                    a == 169 && b == 254 -> true        // 链路本地
                    a == 0 -> true
                    else -> false
                }
            }
            return false
        }

        /** 去脚本/样式/标签，压缩空白 */
        private fun stripHtml(html: String): String = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // ══════════════════════════════════════════════════════════════
    //  HTTP 底层
    // ══════════════════════════════════════════════════════════════

    private fun httpPostJson(url: String, body: String, ctx: ToolContext): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code: ${text.take(200)}")
            }
            return text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 流式读取，超 5MB 截断（防超大响应先 OOM） */
    private fun httpGet(url: String, ctx: ToolContext): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val sb = StringBuilder()
            var total = 0
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                val buf = CharArray(8192)
                while (true) {
                    if (total >= MAX_FETCH_BYTES) break
                    val n = reader.read(buf)
                    if (n <= 0) break
                    sb.append(buf, 0, n)
                    total += n
                }
            }
            return code to sb.toString()
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
