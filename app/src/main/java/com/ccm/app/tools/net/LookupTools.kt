package com.ccm.app.tools.net

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.core.tool.ToolSchema.strList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 多来源资料搜索（SearchInfo）+ 打开某条抓全文（Lookup）。
 *
 * 参照 Node 版 `core/tools-lookup.mjs`（590 行，含大量实搜调优经验）。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要它（与 WebSearch 的分工）
 * ══════════════════════════════════════════════════════════════
 *
 * | 工具 | 数据源 | 强项 |
 * |---|---|---|
 * | **WebSearch** | Tavily（英文好） | 技术文档、英文资料 |
 * | **SearchInfo** | Bing/百度/B站/CSDN/掘金/V2EX/HN/SO/GitHub/Mojeek | **中文人物、作品、UP主、站内内容** |
 *
 * ══════════════════════════════════════════════════════════════
 *  可信度映射 —— 这是本文件最有价值的部分，改动前请读完注释
 * ══════════════════════════════════════════════════════════════
 *
 * 每条结果按来源域名给 0~4 分，**分数决定排序与标签**，标签写清「该怎么用」
 * 而不只给星级（模型看到「一手来源」比看到「★★★★」更知道怎么用）。
 *
 * **两条实搜踩出来的规则（不要改回去）**：
 *
 * 1. **官方域判定必须按注册域（apex），不能按「品牌名出现在哪一段」**
 *    第一版只判「品牌名出现在子域就算蹭品牌」，结果 `api-docs.deepseek.com`
 *    （真官方文档站）被误标成仿冒。修正：先取注册域，在 [OFFICIAL_APEX] 里就放行
 *    **任意子域**。
 *
 * 2. **UGC 平台要允许任意子域**
 *    知乎专栏是 `zhuanlan.zhihu.com`，正则只写 `^(www\.)?zhihu\.com` 匹配不到。
 *    统一用 `(^|\.)域名\.` 形式。
 *
 * ⚠️ 仿冒域名（注册域不是官方但带品牌名）标 **-1 分**，排最后 + 明确警示 ——
 * 但注释里也说了「内容常常是真的」（镜像站/搬运），所以是「看，但要跟一手来源对照」。
 */
class LookupTools(private val saveDir: File) {

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"

        private const val TIMEOUT_MS = 12_000

        /** 单条结果摘要长度 */
        private const val SNIPPET_LEN = 180

        /** Lookup 抓全文上限 */
        private const val FULLTEXT_LIMIT = 30_000

        // ── 可信度映射 ────────────────────────────────────────────
        //
        // 分数含义（标签同时写清「怎么用」）：
        //   4 = 一手来源（厂商官网/官方文档）—— 查版本号/价格/政策以它为准
        //   3 = 官媒/权威机构 —— 可直接引用
        //   2 = 主流媒体/技术权威站 —— 可直接引用
        //   1.5 = UGC 平台（B站/知乎）—— 单条别当结论，多个独立作者讲同一件事就可信
        //   1.3 = 技术社区讨论（V2EX）
        //   0.5 = 廉价/临时顶级域 —— 引用前多问一句「这站明天还在吗」
        //   0 = 论坛/个人博客 —— 要打折
        //   -1 = 疑似仿冒品牌域名 —— 排最后 + 警示

        private data class TrustRule(val pattern: Regex, val score: Double)

        private val DOMAIN_TRUST = listOf(
            TrustRule(Regex("^(docs|developer|devcenter)\\.", RegexOption.IGNORE_CASE), 4.0),
            TrustRule(Regex("\\.(gov|edu)(\\.[a-z]{2})?$", RegexOption.IGNORE_CASE), 4.0),
            TrustRule(Regex("^(www\\.)?(xinhuanet|people|cctv|chinanews|cnr|china)\\.(com|cn)$", RegexOption.IGNORE_CASE), 3.0),
            TrustRule(Regex("^(news\\.)?(qq|163|sina|sohu|ifeng|thepaper)\\.", RegexOption.IGNORE_CASE), 2.0),
            TrustRule(Regex("baike\\.baidu\\.com", RegexOption.IGNORE_CASE), 2.0),
            // 技术问答/代码站：SO 有投票采纳机制，GitHub 是代码与文档的一手载体
            TrustRule(Regex("(^|\\.)(github|stackoverflow|npmjs|pypi|readthedocs)\\.", RegexOption.IGNORE_CASE), 2.0),
            TrustRule(Regex("developer\\.mozilla\\.org", RegexOption.IGNORE_CASE), 2.0),
            TrustRule(Regex("(^|\\.)(news\\.ycombinator|ycombinator)\\.com", RegexOption.IGNORE_CASE), 1.8),
            // UGC 内容平台：⚠️ 必须允许任意子域（知乎专栏是 zhuanlan.zhihu.com）
            TrustRule(Regex("(^|\\.)(bilibili|zhihu|juejin|csdn|jianshu|douban|weibo)\\.", RegexOption.IGNORE_CASE), 1.5),
            TrustRule(Regex("(^|\\.)(v2ex)\\.com", RegexOption.IGNORE_CASE), 1.3),
            TrustRule(Regex("^(tieba|bbs|forum|blog)\\.|tieba\\.baidu", RegexOption.IGNORE_CASE), 0.0),
            // 廉价/临时顶级域
            TrustRule(Regex("\\.(bond|top|xyz|cyou|icu|shop|site|online|click|link|buzz|monster)$", RegexOption.IGNORE_CASE), 0.5),
        )

        private val BRANDS = listOf(
            "deepseek", "anthropic", "openai", "claude", "chatgpt", "moonshot", "kimi", "gemini",
        )

        /**
         * 官方注册域。
         *
         * ⚠️ 有这张表才能正确处理子域 —— 判定「是不是仿冒」必须看**注册域对不对**，
         * 而不是「品牌名出现在哪一段」。见类注释的规则 1。
         */
        private val OFFICIAL_APEX = setOf(
            "deepseek.com", "anthropic.com", "claude.ai", "claude.com", "openai.com", "chatgpt.com",
            "moonshot.cn", "kimi.com", "bigmodel.cn", "zhipuai.cn", "z.ai",
            "google.com", "nvidia.com", "microsoft.com", "apple.com",
        )

        private val OFFICIAL_HOSTS = setOf(
            "deepseek.com", "www.deepseek.com", "chat.deepseek.com", "api.deepseek.com",
            "anthropic.com", "www.anthropic.com", "claude.ai", "claude.com", "www.claude.com", "docs.claude.com",
            "openai.com", "www.openai.com", "chatgpt.com", "platform.openai.com",
            "moonshot.cn", "kimi.com", "www.kimi.com", "gemini.google.com",
        )

        /** 取注册域（处理 .com.cn / .co.uk 这类两段后缀） */
        fun apexOf(host: String): String {
            val h = host.lowercase().removePrefix("www.")
            val parts = h.split(".")
            if (parts.size < 2) return h
            val lastTwo = parts.takeLast(2).joinToString(".")
            val twoPart = Regex("^(com|net|org|gov|edu|co)\\.[a-z]{2}$").matches(lastTwo)
            return parts.takeLast(if (twoPart) 3 else 2).joinToString(".")
        }

        fun looksImpersonating(host: String): Boolean {
            val h = host.lowercase().removePrefix("www.")
            val apex = apexOf(h)
            // 注册域就是官方 → 任意子域都放行（api-docs.deepseek.com / platform.openai.com）
            if (apex in OFFICIAL_APEX) return false
            if (h in OFFICIAL_HOSTS || host.lowercase() in OFFICIAL_HOSTS) return false
            // 注册域不是官方但域名里带品牌名 → 蹭品牌
            return BRANDS.any { h.contains(it) }
        }

        /** 算可信度分数 */
        fun trustOf(url: String): Double {
            val host = try {
                URL(url).host
            } catch (_: Throwable) {
                return 0.0
            }
            if (looksImpersonating(host)) return -1.0
            val apex = apexOf(host)
            if (apex in OFFICIAL_APEX) return 4.0
            DOMAIN_TRUST.forEach { (re, score) ->
                if (re.containsMatchIn(host)) return score
            }
            return 1.0
        }

        /** 分数 → 标签（写清「该怎么用」，不只给星级） */
        fun labelOf(trust: Double): String = when {
            trust < 0 -> "⚠ 疑似仿冒品牌域名（内容可能是搬运的真货，但必须跟一手来源对照核实）"
            trust >= 4.0 -> "★★★★ 一手来源（官网/官方文档）—— 查版本号·价格·政策以它为准"
            trust >= 3.0 -> "★★★ 官媒/权威机构 —— 可直接引用"
            trust >= 2.0 -> "★★☆ 主流媒体/技术权威站 —— 可直接引用"
            trust >= 1.8 -> "★★☆ 技术圈一手讨论 —— 可参考，注意是个人发言"
            trust >= 1.5 -> "★☆☆ UGC 平台 —— 单条别当结论；多个独立作者讲同一件事就可信"
            trust >= 1.3 -> "★☆☆ 技术社区讨论 —— 可参考，较零散"
            trust >= 1.0 -> "★☆☆ 一般来源 —— 建议交叉验证"
            trust >= 0.5 -> "✩☆☆ 廉价/临时域名 —— 引用前确认站点是否可靠"
            else -> "✩☆☆ 论坛/个人博客 —— 要打折"
        }
    }

    private fun lookupDir(): File = File(saveDir, "lookup").apply { if (!exists()) mkdirs() }

    /**
     * 取「整数数组」参数（如 `indexes: [0, 3, 5]`）。
     *
     * ⚠️ 为什么不用 `ToolSchema.strList`：它只接受**字符串数组**，
     * 模型按 schema（`array of integer`）传的数字数组会被静默判为 null ——
     * 表现是「参数明明传了却报缺参数」，很难查。
     *
     * 这里同时容忍三种形态：数字数组、字符串数组、单个数字/字符串。
     * （`ToolSchema` 目前没有 `intList`，那是 dev-core 的文件，所以本地实现。
     *  若将来他加了，这里可以替换掉。）
     */
    private fun intListOf(input: JsonObject, key: String): List<Int>? {
        val el = input[key] ?: return null
        return when (el) {
            is kotlinx.serialization.json.JsonArray -> el.mapNotNull { item ->
                (item as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.toIntOrNull()
            }
            is kotlinx.serialization.json.JsonPrimitive -> el.content.trim().toIntOrNull()?.let { listOf(it) }
            else -> null
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  各源搜索实现
    // ══════════════════════════════════════════════════════════════

    private data class Item(
        val title: String,
        val url: String,
        val snippet: String,
        val source: String,
        val trust: Double,
    )

    /** 去掉 HTML 标签与实体 */
    private fun stripTags(s: String): String = s
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun fetchText(url: String, referer: String? = null): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                referer?.let { setRequestProperty("Referer", it) }
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** Bing（cn.bing.com，b_algo 块可解析） */
    private suspend fun searchBing(q: String): List<Item> = withContext(Dispatchers.IO) {
        val html = fetchText("https://cn.bing.com/search?q=${enc(q)}&mkt=zh-CN") ?: return@withContext emptyList()
        val blocks = html.split("<li class=\"b_algo\"").drop(1).take(6)
        blocks.mapNotNull { blk ->
            val u = Regex("<a[^>]*href=\"(https?://[^\"]+)\"").find(blk)?.groupValues?.get(1) ?: return@mapNotNull null
            val t = Regex("<h2[^>]*>([\\s\\S]*?)</h2>").find(blk)?.groupValues?.get(1) ?: return@mapNotNull null
            val c = Regex("<p class=\"b_lineclamp[^\"]*\"[^>]*>([\\s\\S]*?)</p>").find(blk)?.groupValues?.get(1)
            val host = try {
                URL(u).host
            } catch (_: Throwable) {
                ""
            }
            Item(
                title = stripTags(t),
                url = u,
                snippet = stripTags(c ?: "").take(SNIPPET_LEN),
                source = host,
                trust = trustOf(u),
            )
        }
    }

    /** 百度（data-url + cosc-source-text） */
    private suspend fun searchBaidu(q: String): List<Item> = withContext(Dispatchers.IO) {
        val html = fetchText("https://www.baidu.com/s?wd=${enc(q)}") ?: return@withContext emptyList()
        val out = mutableListOf<Item>()
        val re = Regex(
            "data-url=\"(https?://[^\"]+)\"[\\s\\S]{0,3000}?<span class=\"cosc-source-text[^\"]*\">([^<]{1,40})</span>[\\s\\S]{0,1500}?<em>([^<]{2,60})</em>"
        )
        re.findAll(html).take(6).forEach { m ->
            val url = m.groupValues[1]
            out += Item(
                title = stripTags(m.groupValues[3]),
                url = url,
                snippet = "",
                source = m.groupValues[2],
                trust = trustOf(url),
            )
        }
        out
    }

    /** B站（官方 API） */
    private suspend fun searchBili(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText(
            "https://api.bilibili.com/x/web-interface/search/all/v2?keyword=${enc(q)}&page=1",
            referer = "https://www.bilibili.com",
        ) ?: return@withContext emptyList()
        try {
            val obj = JSONObject(json)
            val results = obj.optJSONObject("data")?.optJSONArray("result") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until results.length()) {
                val group = results.optJSONObject(i) ?: continue
                if (group.optString("result_type") != "video") continue
                val arr = group.optJSONArray("data") ?: continue
                for (j in 0 until minOf(arr.length(), 4)) {
                    val v = arr.optJSONObject(j) ?: continue
                    val url = v.optString("arcurl").ifEmpty { "https://www.bilibili.com/video/${v.optString("bvid")}" }
                    out += Item(
                        title = stripTags(v.optString("title")),
                        url = url,
                        snippet = stripTags(v.optString("description")).take(SNIPPET_LEN),
                        source = "bilibili.com",
                        trust = trustOf(url),
                    )
                }
            }
            out.take(6)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** CSDN（JSON API） */
    private suspend fun searchCsdn(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText("https://so.csdn.net/api/v3/search?q=${enc(q)}&t=all&p=1") ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("result_vos") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val it = arr.optJSONObject(i) ?: continue
                val url = it.optString("url").ifEmpty {
                    "https://blog.csdn.net/${it.optString("username")}/article/details/${it.optString("article_id")}"
                }
                out += Item(
                    title = stripTags(it.optString("title")),
                    url = url,
                    snippet = stripTags(it.optString("description")).take(SNIPPET_LEN),
                    source = "blog.csdn.net",
                    trust = trustOf(url),
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 掘金（POST JSON） */
    private suspend fun searchJuejin(q: String): List<Item> = withContext(Dispatchers.IO) {
        val body = """{"query":"$q","id_type":0,"cursor":"0","limit":6,"search_type":0}"""
        val json = try {
            val conn = (URL("https://api.juejin.cn/search_api/v1/search").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", UA)
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode !in 200..299) return@withContext emptyList()
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        try {
            val arr = JSONObject(json).optJSONObject("data")?.optJSONArray("data") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val it = arr.optJSONObject(i) ?: continue
                val info = it.optJSONObject("result_model") ?: it
                val url = info.optString("article_id").takeIf { it.isNotEmpty() }
                    ?.let { "https://juejin.cn/post/$it" }
                    ?: info.optString("url")
                if (url.isEmpty()) continue
                out += Item(
                    title = stripTags(info.optString("title")),
                    url = url,
                    snippet = stripTags(info.optString("content")).take(SNIPPET_LEN),
                    source = "juejin.cn",
                    trust = trustOf(url),
                )
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Mojeek（独立索引，无广告） */
    private suspend fun searchMojeek(q: String): List<Item> = withContext(Dispatchers.IO) {
        val html = fetchText("https://www.mojeek.com/search?q=${enc(q)}") ?: return@withContext emptyList()
        val blocks = html.split("<li>").drop(1).take(6)
        blocks.mapNotNull { blk ->
            val u = Regex("<a[^>]*href=\"(https?://[^\"]+)\"[^>]*class=\"ob\"").find(blk)?.groupValues?.get(1)
                ?: Regex("<a[^>]*class=\"ob\"[^>]*href=\"(https?://[^\"]+)\"").find(blk)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val t = Regex("<a[^>]*class=\"ob\"[^>]*>([\\s\\S]*?)</a>").find(blk)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val p = Regex("<p class=\"s\">([\\s\\S]*?)</p>").find(blk)?.groupValues?.get(1)
            val host = try {
                URL(u).host
            } catch (_: Throwable) {
                ""
            }
            Item(stripTags(t), u, stripTags(p ?: "").take(SNIPPET_LEN), host, trustOf(u))
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SearchInfo 工具
    // ══════════════════════════════════════════════════════════════

    inner class SearchInfoTool : Tool() {
        override val name = "SearchInfo"
        override val description =
            "多来源资料搜索：一次查 Bing/百度/B站/CSDN/掘金/Mojeek 等源，返回资料卡" +
                "（标题+摘要+来源+可信度标注，按可信度排序）。" +
                "返回资料卡 id 和条目列表，再用 Lookup({card, index}) 打开某条抓全文。" +
                "与 WebSearch 的分工：WebSearch 走 Tavily（英文好、中文冷门实体差），" +
                "本工具用国内源（百度/B站命中率高）——查中文人物/作品/UP主/站内内容用它。" +
                "⚠ 涉及模型版本、产品发布、价格、政策这类会变的事实，先搜再答。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 20_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            // ⚠️ keywords 声明成 `string or array of string`（JSON Schema 的 anyOf）。
            // 模型可以传单个词 `"DeepSeek V4"` 也可以传多角度 `["V4 多模态","V4 评测"]`。
            // 若只声明 string，模型看到描述里说「可以传数组」也不敢传；
            // 若只声明 array，传单个词会被拒。两种都收才顺手。
            "keywords" to kotlinx.serialization.json.buildJsonObject {
                put(
                    "anyOf",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            ToolSchema.string("单个搜索关键词"),
                            ToolSchema.stringArray("多个关键词，并行搜多个角度（最多 4 个）"),
                        ),
                    ),
                )
                put(
                    "description",
                    kotlinx.serialization.json.JsonPrimitive(
                        "搜索关键词。**可以传数组并行搜多个角度**（最多 4 个）——" +
                            "同一件事换个说法能搜出完全不同的结果，多角度并行比反复改词重搜高效得多。",
                    ),
                )
            },
            "limit" to ToolSchema.integer("返回多少条，默认 25，上限 50", minimum = 1, maximum = 50),
            required = listOf("keywords"),
        )

        override fun validateInput(input: JsonObject): String? {
            val kw = input.strList("keywords") ?: input.str("keywords")?.let { listOf(it) }
            if (kw.isNullOrEmpty()) return "keywords is required"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val keywords = (input.strList("keywords") ?: listOfNotNull(input.str("keywords")))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(4)
            if (keywords.isEmpty()) return ToolResult.invalidInput("keywords is required")

            val limit = (input.int("limit") ?: 25).coerceIn(1, 50)

            // 并行搜所有源 × 所有关键词
            val all = coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<Item>>>()
                keywords.forEach { q ->
                    jobs += async { searchBing(q) }
                    jobs += async { searchBaidu(q) }
                    jobs += async { searchBili(q) }
                    jobs += async { searchCsdn(q) }
                    jobs += async { searchJuejin(q) }
                    jobs += async { searchMojeek(q) }
                }
                jobs.awaitAll().flatten()
            }

            if (all.isEmpty()) {
                return ToolResult.failed("所有来源都没有结果（可能是网络问题，或关键词太生僻）")
            }

            // 去重（按 URL）→ 排序（可信度降序）
            val deduped = all
                .filter { it.url.isNotEmpty() }
                .distinctBy { it.url }
                .sortedByDescending { it.trust }
                .take(limit)

            // 资料卡 id（存盘，供 Lookup 用）
            val cardId = "card-${System.currentTimeMillis() % 1_000_000}"
            saveCard(cardId, keywords.joinToString(" "), deduped)

            val sb = StringBuilder()
            sb.append("资料卡 $cardId（关键词: ${keywords.joinToString(" / ")}，共 ${deduped.size} 条）\n\n")
            deduped.forEachIndexed { i, it ->
                sb.append("[$i] ${it.title}\n")
                sb.append("    ${it.url}\n")
                sb.append("    来源: ${it.source} — ${labelOf(it.trust)}\n")
                if (it.snippet.isNotEmpty()) sb.append("    ${it.snippet}\n")
                sb.append("\n")
            }
            sb.append("用 Lookup({card:\"$cardId\", index:N}) 打开某条抓全文（支持批量 indexes:[0,2,4]）")

            return ToolResult.ok(sb.toString())
        }
    }

    // ── 资料卡持久化 ──────────────────────────────────────────────

    private fun cardFile(cardId: String): File = File(lookupDir(), "$cardId.json")

    private fun saveCard(cardId: String, keywords: String, items: List<Item>) {
        try {
            val arr = org.json.JSONArray()
            items.forEach { it ->
                arr.put(
                    JSONObject()
                        .put("title", it.title)
                        .put("url", it.url)
                        .put("snippet", it.snippet)
                        .put("source", it.source)
                        .put("trust", it.trust),
                )
            }
            val obj = JSONObject()
                .put("card", cardId)
                .put("keywords", keywords)
                .put("items", arr)
                .put("createdAt", System.currentTimeMillis())
            cardFile(cardId).writeText(obj.toString())
        } catch (_: Throwable) {
            // 卡片存不了不阻塞返回（只是 Lookup 用不了）
        }
    }

    private fun loadCard(cardId: String): JSONObject? = try {
        val f = cardFile(cardId)
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (_: Throwable) {
        null
    }

    // ══════════════════════════════════════════════════════════════
    //  Lookup 工具
    // ══════════════════════════════════════════════════════════════

    inner class LookupTool : Tool() {
        override val name = "Lookup"
        override val description =
            "打开资料卡里的条目，抓全文落盘返回路径。配合 SearchInfo 用。" +
                "**支持批量**：indexes:[0,3,5] 一次抓多条（最多 5 条，并行下载）——" +
                "对比多个来源的说法、或一次要看几篇时用它，比逐条调快得多。" +
                "全文以 md 存在 App 存储的 lookup/ 目录，用 Read 看。" +
                "摘要不够下结论、或用户要看详细内容时用。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 2_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "card" to ToolSchema.string("SearchInfo 返回的资料卡 id（card-开头）"),
            "index" to ToolSchema.integer("单条序号（资料卡里 [0] [1] 那个数字）", minimum = 0),
            "indexes" to ToolSchema.stringArray("批量序号，如 [0, 3, 5]（最多 5 条，并行抓取）。与 index 二选一。"),
            required = listOf("card"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("card").isNullOrBlank()) return "card is required"
            val idxs = intListOf(input, "indexes")
            if (idxs != null && idxs.size > 5) return "indexes 最多 5 条"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val cardId = input.str("card")!!
            val card = loadCard(cardId)
                ?: return ToolResult.notFound("找不到资料卡 $cardId（可能已过期或未保存）")

            val items = card.optJSONArray("items") ?: return ToolResult.failed("资料卡内容为空")
            val keywords = card.optString("keywords", "")

            // 序号来源：indexes 优先，否则 index
            //
            // ⚠️ 这里不能用 ToolSchema.strList —— 它只接受**字符串数组**，
            // 而模型按 schema（array of integer）传的是 `[0, 3, 5]` 数字数组，
            // 用 strList 会静默拿到 null（表现为「参数明明传了却报缺参数」）。
            // ToolSchema 目前没有 intList（那是 dev-core 的文件），所以本地实现。
            val indexes: List<Int> = intListOf(input, "indexes")
                ?: listOfNotNull(input.int("index"))
            if (indexes.isEmpty()) {
                return ToolResult.invalidInput("请给 index（单条）或 indexes（批量，最多 5 条）")
            }

            val results = coroutineScope {
                indexes.take(5).map { idx ->
                    async { fetchOne(items, idx, keywords, cardId) }
                }.awaitAll()
            }

            val okCount = results.count { it.second }
            val sb = StringBuilder()
            sb.append("Lookup 完成：$okCount/${results.size} 条抓取成功\n\n")
            results.forEach { (line, _) -> sb.append(line).append('\n') }
            return ToolResult.ok(sb.toString())
        }

        /** 抓一条，返回 (结果描述, 是否成功) */
        private suspend fun fetchOne(
            items: org.json.JSONArray,
            idx: Int,
            keywords: String,
            cardId: String,
        ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
            if (idx < 0 || idx >= items.length()) {
                return@withContext "[$idx] 序号越界（共 ${items.length()} 条）" to false
            }
            val item = items.optJSONObject(idx) ?: return@withContext "[$idx] 条目损坏" to false
            val url = item.optString("url")
            val title = item.optString("title")
            val source = item.optString("source")
            val trust = item.optDouble("trust", 1.0)

            val html = fetchText(url)
                ?: return@withContext "[$idx] $title\n     抓取失败（$source）—— 可能是网络问题或需要 JS 渲染" to false

            val cleaned = html
                .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            val paragraphs = Regex("<p[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE)
                .findAll(cleaned)
                .map { stripTags(it.groupValues[1]) }
                .filter { it.length > 20 }
                .toList()
            val full = (if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else stripTags(cleaned))
                .take(FULLTEXT_LIMIT)

            if (full.length < 30) {
                val snippet = item.optString("snippet")
                return@withContext "[$idx] $title\n     正文抓不到（页面可能是 JS 渲染）\n     摘要: ${snippet.ifEmpty { "(无)" }}" to false
            }

            val mdFile = File(lookupDir(), "doc-${System.currentTimeMillis() % 1_000_000}-$idx.md")
            val md = buildString {
                append("# $title\n\n")
                append("- 来源: $source（${labelOf(trust)}）\n")
                append("- 链接: $url\n")
                append("- 抓取时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}\n")
                append("- 搜索关键词: $keywords\n\n")
                append(full)
            }
            return@withContext try {
                mdFile.writeText(md)
                "[$idx] $title\n     ${mdFile.absolutePath}（${full.length} 字符）\n     来源: $source" to true
            } catch (e: Throwable) {
                "[$idx] $title\n     写盘失败: ${e.message}" to false
            }
        }
    }
}
