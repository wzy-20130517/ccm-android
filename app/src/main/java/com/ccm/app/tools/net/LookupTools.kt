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
 * 参照 Node 版 `core/tools-lookup.mjs`（1180 行，含大量实搜调优经验）。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要它（与 WebSearch 的分工）
 * ══════════════════════════════════════════════════════════════
 *
 * | 工具 | 数据源 | 强项 |
 * |---|---|---|
 * | **WebSearch** | Tavily（英文好） | 技术文档、英文资料 |
 * | **SearchInfo** | Bing/百度/搜狗/360/B站/CSDN/掘金/HN/SO/GitHub/Mojeek + 15 个垂直源 | **中文人物、作品、UP主、站内内容** |
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

        // ── 垂直源的相关性过滤（移植自 tools-lookup.mjs 的 VERTICAL_SOURCES / isRelevant）──
        //
        // 【踩的坑】实测搜「周杰伦」时，MDN/StackOverflow/GitHub 的**无关结果**
        // 排在百度百科前面 —— 因为：
        //   1. 这些源是英文技术站，用中文词查不到东西
        //   2. 但它们会**兜底返回热门内容**（GitHub 搜「周杰伦」返回 24256 星的
        //      书单仓库…，跟查询毫无关系）
        //   3. 而它们的域名权威度高（github.com=2、developer.mozilla.org=2），
        //      按权威度排就窜到前面了
        //
        // 【做法】source（去掉 www. 前缀）命中本集合的条目，标题/摘要里必须出现
        // 查询词（或其英文实词）才保留。通用搜索引擎（Bing/百度/搜狗/360/Mojeek）
        // 不做这个过滤 —— 它们本来就会做语义匹配，返回的是相关结果。
        //
        // 【与 CLI 的 VERTICAL_HOSTS 的区别】那个用于 trustOf 权威度降权到 1.8，
        // 这个用于相关性过滤 —— CLI 注释强调两者独立维护。本文件的 trustOf 属于
        // TrustRule（本次移植不动它），所以只移植了这一半。
        private val VERTICAL_SOURCES = setOf(
            "arxiv.org", "openalex.org", "doi.org", "pubmed.ncbi.nlm.nih.gov",
            "npmjs.com", "pypi.org", "developer.mozilla.org",
            // 第二批（2026-10-03）：同样是「查不到会兜底返回热门内容」的源
            "zenodo.org", "doaj.org", "europepmc.org", "crates.io",
            "nvd.nist.gov", "endoflife.date", "huggingface.co",
            // 通用但会兜底返回无关内容的源（实测 GitHub 搜中文词返回热门仓库）
            "github.com", "stackoverflow.com",
        )

        /**
         * 相关性判断（对齐 CLI isRelevant）：
         * - 查询词无空白 → 整体子串匹配（「周杰伦」必须整体出现在文本里）
         * - 多词查询 → 任一「实词」（≥3 字符，过滤 a/is/of 这类短词）命中即可 ——
         *   标题里通常不含完整短语，逐词匹配才命中得了
         * - 都不满足（如查询全是短词）→ 放行
         *
         * 注意 CLI 注释写「长度 <2 的词忽略」但代码是 `>= 3`，这里按代码对齐。
         */
        private fun isRelevant(title: String, snippet: String, query: String): Boolean {
            val hay = "$title $snippet".lowercase()
            val q = query.lowercase().trim()
            if (q.isEmpty()) return true
            // 中文/无空白查询：整体子串匹配
            if (!Regex("\\s").containsMatchIn(q)) return hay.contains(q)
            // 英文/多词查询：任一实词命中即可
            val words = q.split(Regex("\\s+")).filter { it.length >= 3 }
            if (words.isEmpty()) return true
            return words.any { hay.contains(it) }
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

    // 超时对齐 CLI（tools-lookup.mjs）：CLI 的 HTML 源 fetchText 是 20s 总 abort、
    // JSON API 源 AbortSignal.timeout(15s)、NVD 20s —— 这里用可选参数透传
    //（HttpURLConnection 没有「总超时」，connect/read 各算一次，语义略宽松，已知差异）。
    // headers 用于 CLI 里带额外请求头的源（GitHub 的 Accept）。
    // ⚠️ 两个新参数都有默认值，现有 6 个源的调用不受影响。
    private fun fetchText(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = TIMEOUT_MS,
    ): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                referer?.let { setRequestProperty("Referer", it) }
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
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

    // ── 中文索引补充（搜狗 / 360）────────────────────────────
    //
    // 中文搜索长期只有 Bing/百度两个入口，各有盲区（Bing 中文覆盖一般、
    // 百度结果商业化重），搜狗/360 是第二、第三大中文索引，同样关键词常给出
    // 不同结果 —— 多索引交叉能提升命中率。
    //
    // 【原实现踩过的坑，注释保留】
    //   搜狗：返回 /link?url=<加密串> 重定向，客户端解不开、只能跟随跳转；
    //        所有结果 host 都是 www.sogou.com，按 host 分类会全部误判，
    //        source 只能用 host、兜底 sogou.com。只收 /link? 或 http 开头的
    //        （其余是页面内小工具链接）。
    //   360：li.res-list → h3 a，href 常带点击跟踪，优先取 data-mdurl（规范目标）；
    //        页面按 10 条分页，要多了没用。

    /** 搜狗（HTML 抓取；结果多为 /link? 重定向链接）—— 移植自 tools-lookup.mjs searchSogou */
    private suspend fun searchSogou(q: String): List<Item> = withContext(Dispatchers.IO) {
        val html = fetchText("https://www.sogou.com/web?query=${enc(q)}", timeoutMs = 20_000)
            ?: return@withContext emptyList()
        val out = mutableListOf<Item>()
        try {
            // 按 h3.vr-title 切块（比整体正则稳：能顺带取到块内摘要）
            val blocks = html.split(Regex("<h3[^>]*class=\"[^\"]*vr-title[^\"]*\"", RegexOption.IGNORE_CASE)).drop(1)
            for (blk in blocks) {
                if (out.size >= 5) break
                val m = Regex("<a[^>]*href=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).find(blk) ?: continue
                val href = m.groupValues[1].trim()
                val title = stripTags(m.groupValues[2])
                // 页面内小工具链接（javascript:void(0) 之类）不是结果，跳过
                if (title.isEmpty() || href.startsWith("javascript:")) continue
                // 真结果只有两种形态：站内重定向 /link?url=... 或直接 http(s)
                if (!(href.startsWith("/link?") || href.startsWith("http"))) continue
                val url = if (href.startsWith("/link?")) "https://www.sogou.com$href" else href
                // 摘要：块内找常见容器（搜狗嵌套层级不稳定，试几种）
                val sm = Regex("<div[^>]*class=\"[^\"]*(?:text-layout|fz-mid|space-txt)[^\"]*\"[^>]*>([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE).find(blk)
                    ?: Regex("<p[^>]*class=\"[^\"]*str-info[^\"]*\"[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(blk)
                val host = try {
                    URL(url).host
                } catch (_: Throwable) {
                    ""
                }
                out += Item(
                    title = title.take(80),
                    url = url,
                    snippet = sm?.let { stripTags(it.groupValues[1]).take(180) } ?: "",
                    source = host.ifEmpty { "sogou.com" },
                    trust = trustOf(url),
                )
            }
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        out
    }

    /** 360 搜索（HTML 抓取；链接是直接目标 URL，无需解重定向）—— 移植自 tools-lookup.mjs searchSo360 */
    private suspend fun searchSo360(q: String): List<Item> = withContext(Dispatchers.IO) {
        val html = fetchText("https://www.so.com/s?q=${enc(q)}&rn=10", timeoutMs = 20_000)
            ?: return@withContext emptyList()
        val out = mutableListOf<Item>()
        try {
            val blocks = html.split(Regex("<li[^>]*class=\"[^\"]*res-list[^\"]*\"", RegexOption.IGNORE_CASE)).drop(1)
            for (blk in blocks) {
                if (out.size >= 5) break
                val h3 = Regex("<h3[^>]*>([\\s\\S]*?)</h3>", RegexOption.IGNORE_CASE).find(blk)?.groupValues?.get(1) ?: continue
                val anchorTag = Regex("<a[^>]*>", RegexOption.IGNORE_CASE).find(h3)?.value ?: continue
                // 优先 data-mdurl（360 的 href 常带点击跟踪，mdurl 才是规范目标）
                val href = Regex("data-mdurl=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(anchorTag)?.groupValues?.get(1)
                    ?: Regex("href=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(anchorTag)?.groupValues?.get(1)
                    ?: ""
                val title = stripTags(h3)
                if (title.isEmpty() || !href.startsWith("http")) continue
                val dm = Regex("<p[^>]*class=\"[^\"]*res-desc[^\"]*\"[^>]*>([\\s\\S]*?)</p>", RegexOption.IGNORE_CASE).find(blk)
                    ?: Regex("<div[^>]*class=\"[^\"]*res-comm-con[^\"]*\"[^>]*>([\\s\\S]*?)</div>", RegexOption.IGNORE_CASE).find(blk)
                val host = try {
                    URL(href).host
                } catch (_: Throwable) {
                    ""
                }
                out += Item(
                    title = title.take(80),
                    url = href,
                    snippet = dm?.let { stripTags(it.groupValues[1]).take(180) } ?: "",
                    source = host,
                    trust = trustOf(href),
                )
            }
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        out
    }

    // ── 通用 JSON API 源（HackerNews / StackOverflow / GitHub）────────

    /** HackerNews（Algolia 官方 API，英文一手讨论，CLI 实测 1.1s / 5 条）—— 移植自 tools-lookup.mjs searchHackerNews */
    private suspend fun searchHackerNews(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText(
            "https://hn.algolia.com/api/v1/search?query=${enc(q)}&tags=story&hitsPerPage=5",
            timeoutMs = 15_000,
        ) ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("hits") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val h = arr.optJSONObject(i) ?: continue
                val title = h.optString("title").take(80)
                if (title.isEmpty()) continue
                // 官网帖链接兜底（Algolia 只给原文外链，站内讨论页要自己拼）
                val url = h.optString("url").ifEmpty { "https://news.ycombinator.com/item?id=${h.optString("objectID")}" }
                val date = h.optString("created_at")
                val snippet = "${h.optInt("points", 0)} points · ${h.optInt("num_comments", 0)} comments" +
                    (if (date.isNotEmpty()) " · ${date.take(10)}" else "")
                out += Item(title, url, snippet, "news.ycombinator.com", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** StackOverflow（官方 API，权威技术问答，CLI 实测 0.8s / 5 条）—— 移植自 tools-lookup.mjs searchStackOverflow */
    private suspend fun searchStackOverflow(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText(
            "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&q=${enc(q)}&site=stackoverflow&pagesize=5&filter=default",
            timeoutMs = 15_000,
        ) ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("items") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val it = arr.optJSONObject(i) ?: continue
                val title = stripTags(it.optString("title")).take(90)
                val url = it.optString("link")
                if (url.isEmpty() || title.isEmpty()) continue
                val snippet = "得分 ${it.optInt("score", 0)} · " +
                    (if (it.optBoolean("is_answered", false)) "已解决" else "未解决") +
                    " · ${it.optInt("answer_count", 0)} 回答"
                out += Item(title, url, snippet, "stackoverflow.com", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** GitHub 仓库搜索（找项目和实现，CLI 实测 0.7s / 5 条）—— 移植自 tools-lookup.mjs searchGithub */
    private suspend fun searchGithub(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText(
            "https://api.github.com/search/repositories?q=${enc(q)}&per_page=5&sort=stars",
            headers = mapOf("Accept" to "application/vnd.github+json"),
            timeoutMs = 15_000,
        ) ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("items") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val it = arr.optJSONObject(i) ?: continue
                val desc = stripTags(it.optString("description")).take(50)
                val title = it.optString("full_name") + if (desc.isNotEmpty()) " — $desc" else ""
                val url = it.optString("html_url")
                if (url.isEmpty() || title.isEmpty()) continue
                val lang = it.optString("language").ifEmpty { "?" }
                val snippet = "★${it.optInt("stargazers_count", 0)} · $lang · 更新 ${it.optString("updated_at").take(10)}"
                out += Item(title, url, snippet, "github.com", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  垂直领域 API 源（第一批：学术 + 包 + 文档）
    //
    // 这批从 free-search-mcp 的 78 个引擎里挑出来，选择标准（保留 CLI 原注释）：
    //   1. 能连通 —— 被墙或区域限制的全部不收（wikipedia.org / huggingface.co
    //      官方站 / coingecko / countries.tugraz / openlibrary / dblp / semanticscholar）
    //   2. 不需要 Cookie / 复杂签名 / 渲染 —— 反爬的先不收（doaj 原来有
    //      Cloudflare 挑战，后来实测通了才加回来）
    //   3. 响应时间可接受 —— 限流或超时的不收（sciencedirect / springer 限流）
    //   4. 全部是**官方 JSON API**，不是 HTML 抓取 —— 结构稳定、解析简单
    //
    // 【垂直源的定位】跟通用源分工：
    //   通用源（bing/baidu/mojeek/sogou）：以**网页**为目标，人名/事件/新闻/中文内容
    //   垂直源（arxiv/crossref/...）：查**结构化领域知识**（论文/包/文档）
    //   —— 它们只在关键词落在其领域时才有结果（搜「周杰伦」arxiv 返回空是正常的），
    //      所以它们不抢位置，是「顺带捞一把」。相关性过滤见 VERTICAL_SOURCES。
    //
    // 【没加的源，原因如下，别重复踩】
    //   · wikipedia.org    被墙，连不上
    //   · huggingface.co   被墙，模型列表走 hf-mirror.com 镜像（见第二批）
    //   · coingecko / countries.tugraz / openlibrary
    //                    被墙，连不上
    //   · dblp             有反爬，用 cloudscraper 都被 403
    //   · semanticscholar  限流（无 key 只有 100 次/5min，共 1000 次/天），
    //                      有 key 又不能把 key 写进库里 —— 先不收
    //   · sciencedirect / springer / biorxiv / mdpi / hindawi / arxiv- 推荐列表
    //                      限流 / 超时（实测 30s 没响应）
    //
    // —— 移植自 tools-lookup.mjs 的「垂直领域 API 源（第一批）」注释与 7 个源
    // ══════════════════════════════════════════════════════════════

    /** arXiv（预印本论文，物理/数学/CS 为主）—— 移植自 tools-lookup.mjs searchArxiv */
    private suspend fun searchArxiv(q: String): List<Item> = withContext(Dispatchers.IO) {
        // CLI 用的是 http://export.arxiv.org（服务端会 301 到 https，跟随即可）
        val xml = fetchText(
            "http://export.arxiv.org/api/query?search_query=all:${enc(q)}&max_results=5",
            timeoutMs = 15_000,
        ) ?: return@withContext emptyList()
        val out = mutableListOf<Item>()
        try {
            for (m in Regex("<entry>([\\s\\S]*?)</entry>").findAll(xml)) {
                val e = m.groupValues[1]
                val title = stripTags(Regex("<title>([\\s\\S]*?)</title>").find(e)?.groupValues?.get(1) ?: "")
                val link = Regex("<id>([\\s\\S]*?)</id>").find(e)?.groupValues?.get(1) ?: ""
                val summary = stripTags(Regex("<summary>([\\s\\S]*?)</summary>").find(e)?.groupValues?.get(1) ?: "")
                val pub = (Regex("<published>([\\s\\S]*?)</published>").find(e)?.groupValues?.get(1) ?: "").take(10)
                val authors = Regex("<name>([\\s\\S]*?)</name>").findAll(e).take(3)
                    .joinToString(", ") { stripTags(it.groupValues[1]) }
                if (title.isEmpty() || link.isEmpty()) continue
                val url = link.trim()
                out += Item(
                    title = title.take(80),
                    url = url,
                    snippet = "${summary.take(110)}（$authors${if (authors.isNotEmpty()) " · " else ""}$pub）",
                    source = "arxiv.org",
                    trust = trustOf(url),
                )
                if (out.size >= 5) break
            }
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        out
    }

    /** OpenAlex（学术文献聚合，覆盖最广的开放学术库）—— 移植自 tools-lookup.mjs searchOpenAlex */
    private suspend fun searchOpenAlex(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText(
            "https://api.openalex.org/works?search=${enc(q)}&per_page=5&mailto=ccm@example.com",
            timeoutMs = 15_000,
        ) ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("results") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val w = arr.optJSONObject(i) ?: continue
                // 作者：前 3 个 authorship 的 author.display_name（空的跳过）
                val names = mutableListOf<String>()
                val authorships = w.optJSONArray("authorships")
                if (authorships != null) {
                    for (j in 0 until minOf(authorships.length(), 3)) {
                        val name = authorships.optJSONObject(j)?.optJSONObject("author")?.optString("display_name") ?: ""
                        if (name.isNotEmpty()) names.add(name)
                    }
                }
                val authors = names.joinToString(", ")
                val title = w.optString("title").ifEmpty { w.optString("display_name") }.take(80)
                val url = w.optString("doi").ifEmpty { w.optString("id") }
                if (url.isEmpty() || title.isEmpty()) continue
                // JS: (w.publication_year || '?') —— 0/缺失都显示 ?
                val year = w.optInt("publication_year", 0).let { if (it == 0) "?" else "$it" }
                val snippet = "$year · 被引 ${w.optInt("cited_by_count", 0)}" +
                    (if (authors.isNotEmpty()) " · $authors" else "")
                out += Item(title, url, snippet, "openalex.org", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Crossref（DOI 注册机构，查文献元数据最权威）—— 移植自 tools-lookup.mjs searchCrossref */
    private suspend fun searchCrossref(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText(
            "https://api.crossref.org/works?query=${enc(q)}&rows=5&mailto=ccm@example.com",
            timeoutMs = 15_000,
        ) ?: return@withContext emptyList()
        try {
            val items = JSONObject(json).optJSONObject("message")?.optJSONArray("items") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(items.length(), 5)) {
                val it = items.optJSONObject(i) ?: continue
                val title = (it.optJSONArray("title")?.optString(0) ?: "").take(80)
                // 作者：前 3 个，每人 [given, family]（缺的字段跳过，与 JS filter(Boolean) 一致）
                val names = mutableListOf<String>()
                val authorArr = it.optJSONArray("author")
                if (authorArr != null) {
                    for (j in 0 until minOf(authorArr.length(), 3)) {
                        val a = authorArr.optJSONObject(j) ?: continue
                        val parts = listOfNotNull(
                            a.optString("given").ifEmpty { null },
                            a.optString("family").ifEmpty { null },
                        )
                        names.add(parts.joinToString(" "))
                    }
                }
                val authors = names.joinToString(", ")
                val doi = it.optString("DOI")
                val url = if (doi.isNotEmpty()) "https://doi.org/$doi" else it.optString("URL")
                if (url.isEmpty() || title.isEmpty()) continue
                // JS: (it.published?.['date-parts']?.[0]?.[0]) || '?'
                val yearRaw = it.optJSONObject("published")?.optJSONArray("date-parts")?.optJSONArray(0)
                    ?.let { dp -> if (dp.length() > 0) dp.optInt(0, 0) else 0 } ?: 0
                val year = if (yearRaw == 0) "?" else "$yearRaw"
                val container = it.optJSONArray("container-title")
                    ?.let { ct -> if (ct.length() > 0) ct.optString(0) else "" } ?: ""
                val snippet = "$year · $container${if (authors.isNotEmpty()) " · $authors" else ""}".take(140)
                out += Item(title, url, snippet, "doi.org", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** PubMed（生物医学文献，两步：esearch 拿 ID → esummary 拿详情）—— 移植自 tools-lookup.mjs searchPubmed */
    private suspend fun searchPubmed(q: String): List<Item> = withContext(Dispatchers.IO) {
        try {
            val s = fetchText(
                "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esearch.fcgi?db=pubmed&term=${enc(q)}&retmode=json&retmax=5",
                timeoutMs = 15_000,
            ) ?: return@withContext emptyList()
            val idArr = JSONObject(s).optJSONObject("esearchresult")?.optJSONArray("idlist") ?: return@withContext emptyList()
            if (idArr.length() == 0) return@withContext emptyList()
            val ids = (0 until idArr.length()).map { idArr.optString(it) }
            val d = fetchText(
                "https://eutils.ncbi.nlm.nih.gov/entrez/eutils/esummary.fcgi?db=pubmed&id=${ids.joinToString(",")}&retmode=json",
                timeoutMs = 15_000,
            ) ?: return@withContext emptyList()
            val result = JSONObject(d).optJSONObject("result") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (id in ids) {
                // 局部变量刻意不叫 it —— 避免与 ifEmpty/lambda 的隐式参数同名
                val med = result.optJSONObject(id) ?: continue
                val authorArr = med.optJSONArray("authors")
                val authors = if (authorArr == null) "" else {
                    (0 until minOf(authorArr.length(), 3)).joinToString(", ") { idx ->
                        authorArr.optJSONObject(idx)?.optString("name") ?: ""
                    }
                }
                // JS: String(it.title || '').replace(/\.$/, '').slice(0, 80)
                val title = med.optString("title").replace(Regex("\\.$"), "").take(80)
                if (title.isEmpty()) continue
                val url = "https://pubmed.ncbi.nlm.nih.gov/$id/"
                // JS: it.fulljournalname || it.source || ''
                val snippet = "${med.optString("pubdate").ifEmpty { "?" }} · " +
                    med.optString("fulljournalname").ifEmpty { med.optString("source") } +
                    (if (authors.isNotEmpty()) " · $authors" else "")
                out += Item(title, url, snippet.take(140), "pubmed.ncbi.nlm.nih.gov", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** npm（JS 包，查库/框架）—— 移植自 tools-lookup.mjs searchNpm */
    private suspend fun searchNpm(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText("https://registry.npmjs.org/-/v1/search?text=${enc(q)}&size=5", timeoutMs = 15_000)
            ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("objects") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optJSONObject("package") ?: continue
                val desc = stripTags(p.optString("description")).take(50)
                val title = p.optString("name") + if (desc.isNotEmpty()) " — $desc" else ""
                val url = p.optJSONObject("links")?.optString("npm")?.ifEmpty { null }
                    ?: "https://www.npmjs.com/package/${p.optString("name")}"
                if (url.isEmpty() || title.isEmpty()) continue
                val snippet = "v${p.optString("version").ifEmpty { "?" }} · ${p.optString("date").take(10)}"
                out += Item(title, url, snippet, "npmjs.com", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * PyPI（Python 包）—— 移植自 tools-lookup.mjs searchPypi
     *
     * PyPI 没有搜索 API，用仓库页的 JSON 接口按名称精确查
     * （模糊搜要靠 XML-RPC，已废弃；这里退化为「关键词当包名试」）。
     */
    private suspend fun searchPypi(q: String): List<Item> = withContext(Dispatchers.IO) {
        try {
            val name = q.trim().split(Regex("\\s+")).firstOrNull() ?: ""
            // 包名必须合法（只收 [a-zA-Z0-9._-]，防路径注入式 URL）
            if (!Regex("^[a-zA-Z0-9._-]+$").matches(name)) return@withContext emptyList()
            val json = fetchText("https://pypi.org/pypi/${enc(name)}/json", timeoutMs = 15_000)
                ?: return@withContext emptyList()
            val info = JSONObject(json).optJSONObject("info") ?: return@withContext emptyList()
            val summary = stripTags(info.optString("summary")).take(50)
            val title = info.optString("name") + if (summary.isNotEmpty()) " — $summary" else ""
            val url = info.optString("package_url").ifEmpty { "https://pypi.org/project/${info.optString("name")}/" }
            if (url.isEmpty() || title.isEmpty()) return@withContext emptyList()
            val reqPy = info.optString("requires_python").replace(Regex("[><=,]"), "")
            val snippet = "v${info.optString("version").ifEmpty { "?" }} · ${info.optString("author")} · Python $reqPy"
            return@withContext listOf(Item(title, url, snippet, "pypi.org", trustOf(url)))
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
    }

    /** MDN（Web 前端文档，查 API/语法）—— 移植自 tools-lookup.mjs searchMdn */
    private suspend fun searchMdn(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText(
            "https://developer.mozilla.org/api/v1/search?q=${enc(q)}&locale=zh-CN&size=5",
            timeoutMs = 15_000,
        ) ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("documents") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val d = arr.optJSONObject(i) ?: continue
                val title = d.optString("title").take(80)
                val mdnUrl = d.optString("mdn_url")
                val url = if (mdnUrl.isNotEmpty()) "https://developer.mozilla.org$mdnUrl" else ""
                val snippet = stripTags(d.optString("summary")).take(130)
                if (url.isEmpty() || title.isEmpty()) continue
                out += Item(title, url, snippet, "developer.mozilla.org", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  垂直领域 API 源（第二批，筛选标准（CLI 原注释）：
    //    1. 能连通（被墙/区域限制不收）
    //    2. 不需要 Cookie / 复杂签名 / 浏览器渲染（反爬的不收）
    //    3. 响应时间可接受（限流/超时的不收）
    //    4. 是官方 JSON API，且**查询词落在其领域时真的有结果**
    //  —— 加入后实测有效的 8 个，同样不收的源见文件头注释。
    // ══════════════════════════════════════════════════════════════

    /** Zenodo（开放科研数据集 + 论文，CERN 运营）—— 移植自 tools-lookup.mjs searchZenodo */
    private suspend fun searchZenodo(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText("https://zenodo.org/api/records?q=${enc(q)}&size=5", timeoutMs = 15_000)
            ?: return@withContext emptyList()
        try {
            val hits = JSONObject(json).optJSONObject("hits")?.optJSONArray("hits") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(hits.length(), 5)) {
                val h = hits.optJSONObject(i) ?: continue
                val md = h.optJSONObject("metadata") ?: continue
                val creators = mutableListOf<String>()
                val creatorArr = md.optJSONArray("creators")
                if (creatorArr != null) {
                    for (j in 0 until minOf(creatorArr.length(), 3)) {
                        val name = creatorArr.optJSONObject(j)?.optString("name") ?: ""
                        if (name.isNotEmpty()) creators.add(name)
                    }
                }
                val creatorStr = creators.joinToString(", ")
                val title = stripTags(md.optString("title")).take(80)
                // JS: h.doi_url || h.links?.self_html || (h.doi ? https://doi.org/... : '')
                val linksSelf = h.optJSONObject("links")?.optString("self_html") ?: ""
                val doi = h.optString("doi")
                val url = h.optString("doi_url").ifEmpty { linksSelf }.ifEmpty {
                    if (doi.isNotEmpty()) "https://doi.org/$doi" else ""
                }
                if (url.isEmpty() || title.isEmpty()) continue
                // JS: md.resource_type?.title || 'dataset'
                val rtype = md.optJSONObject("resource_type")?.optString("title")?.ifEmpty { null } ?: "dataset"
                val snippet = "${md.optString("publication_date").take(10)} · $rtype" +
                    (if (creatorStr.isNotEmpty()) " · $creatorStr" else "")
                out += Item(title, url, snippet, "zenodo.org", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** DOAJ（开放获取期刊目录，原反爬实测已通）—— 移植自 tools-lookup.mjs searchDoaj */
    private suspend fun searchDoaj(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText("https://doaj.org/api/search/articles/${enc(q)}?pageSize=5", timeoutMs = 15_000)
            ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("results") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val it = arr.optJSONObject(i) ?: continue
                val b = it.optJSONObject("bibjson") ?: continue
                val authors = mutableListOf<String>()
                val authorArr = b.optJSONArray("author")
                if (authorArr != null) {
                    for (j in 0 until minOf(authorArr.length(), 3)) {
                        val name = authorArr.optJSONObject(j)?.optString("name") ?: ""
                        if (name.isNotEmpty()) authors.add(name)
                    }
                }
                val authorStr = authors.joinToString(", ")
                val title = stripTags(b.optString("title")).take(80)
                // JS: b.link?.[0]?.url || (it.id ? https://doaj.org/article/... : '')
                val linkUrl = b.optJSONArray("link")?.optJSONObject(0)?.optString("url") ?: ""
                val id = it.optString("id")
                val url = linkUrl.ifEmpty { if (id.isNotEmpty()) "https://doaj.org/article/$id" else "" }
                if (url.isEmpty() || title.isEmpty()) continue
                val journal = b.optJSONObject("journal")?.optString("title") ?: ""
                val snippet = "${b.optString("year").ifEmpty { "?" }} · $journal" +
                    (if (authorStr.isNotEmpty()) " · $authorStr" else "")
                out += Item(title, url, snippet.take(140), "doaj.org", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Europe PMC（生命科学文献，比 PubMed 覆盖更广）—— 移植自 tools-lookup.mjs searchEuropePmc */
    private suspend fun searchEuropePmc(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText(
            "https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=${enc(q)}&format=json&pageSize=5",
            timeoutMs = 15_000,
        ) ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONObject("resultList")?.optJSONArray("result") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val it = arr.optJSONObject(i) ?: continue
                val title = stripTags(it.optString("title")).take(80)
                val doi = it.optString("doi")
                val pmid = it.optString("pmid")
                val url = when {
                    doi.isNotEmpty() -> "https://doi.org/$doi"
                    pmid.isNotEmpty() -> "https://pubmed.ncbi.nlm.nih.gov/$pmid/"
                    else -> ""
                }
                if (url.isEmpty() || title.isEmpty()) continue
                val authorStr = it.optString("authorString")
                val snippet = "${it.optString("pubYear").ifEmpty { "?" }} · ${it.optString("journalTitle")}" +
                    (if (authorStr.isNotEmpty()) " · ${authorStr.take(50)}" else "")
                out += Item(title, url, snippet.take(140), "europepmc.org", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** crates.io（Rust 包）—— 移植自 tools-lookup.mjs searchCrates */
    private suspend fun searchCrates(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText("https://crates.io/api/v1/crates?q=${enc(q)}&per_page=5", timeoutMs = 15_000)
            ?: return@withContext emptyList()
        try {
            val arr = JSONObject(json).optJSONArray("crates") ?: return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val c = arr.optJSONObject(i) ?: continue
                val desc = stripTags(c.optString("description")).take(50)
                val title = c.optString("name") + if (desc.isNotEmpty()) " — $desc" else ""
                val url = "https://crates.io/crates/${c.optString("name")}"
                if (url.isEmpty() || title.isEmpty()) continue
                // JS 的 toLocaleString() 千分位
                val downloads = String.format("%,d", c.optLong("downloads", 0))
                val snippet = "v${c.optString("max_version").ifEmpty { "?" }} · ${c.optString("updated_at").take(10)} · 下载 $downloads"
                out += Item(title, url, snippet, "crates.io", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * NVD（美国国家漏洞库，CVE 查询）—— 移植自 tools-lookup.mjs searchNvd
     *
     * 【为什么要收紧】NVD 的 keywordSearch 是**全文模糊匹配**，搜 vite 会返回
     * VITEC（IPTV 设备）、Vitess（数据库）这些只是名字里带 vite 的无关条目，
     * 实测占了前排。所以拿到结果后本地再过滤一次：按词边界匹配关键词才保留。
     * （超时用 CLI 同款 20s，比其他 API 源宽。）
     */
    private suspend fun searchNvd(q: String): List<Item> = withContext(Dispatchers.IO) {
        val kw = q.lowercase().trim()
        val json = fetchText(
            "https://services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch=${enc(q)}&resultsPerPage=10",
            timeoutMs = 20_000,
        ) ?: return@withContext emptyList()
        val out = mutableListOf<Item>()
        try {
            val vulns = JSONObject(json).optJSONArray("vulnerabilities") ?: return@withContext emptyList()
            for (i in 0 until vulns.length()) {
                if (out.size >= 5) break
                val v = vulns.optJSONObject(i) ?: continue
                val c = v.optJSONObject("cve") ?: continue
                // 只取英文描述
                var desc = ""
                val descs = c.optJSONArray("descriptions")
                if (descs != null) {
                    for (j in 0 until descs.length()) {
                        val d = descs.optJSONObject(j) ?: continue
                        if (d.optString("lang") == "en") {
                            desc = d.optString("value")
                            break
                        }
                    }
                }
                // 本地二次过滤：词边界匹配，避免 vite 匹配到 VITEC/Vitess
                val hay = desc.lowercase()
                val hit = if (Regex("\\s").containsMatchIn(kw)) {
                    // 空格分隔的短语：每个短词（<3 字符）忽略，长词必须全部出现
                    kw.split(Regex("\\s+")).all { w -> w.length < 3 || hay.contains(w) }
                } else {
                    Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(hay)
                }
                if (!hit) continue
                // CVSS 分数：v31 → v30 → v2 依次取（JS 里 0 是 falsy 会 fallthrough）
                val metrics = c.optJSONObject("metrics")
                fun baseScore(key: String): Double? = metrics?.optJSONArray(key)?.optJSONObject(0)
                    ?.optJSONObject("cvssData")?.optDouble("baseScore", 0.0)?.takeIf { it > 0 }
                val score = baseScore("cvssMetricV31") ?: baseScore("cvssMetricV30") ?: baseScore("cvssMetricV2")
                // JS 的 number → 字符串：8.0 会变成 "8"，这里对齐
                val scoreStr = score?.let {
                    if (it == it.toLong().toDouble()) "${it.toLong()}" else "$it"
                }
                val title = "${c.optString("id")} — ${stripTags(desc).take(60)}"
                val url = "https://nvd.nist.gov/vuln/detail/${c.optString("id")}"
                val snippet = c.optString("published").take(10) +
                    (if (scoreStr != null) " · CVSS $scoreStr" else "")
                out += Item(title, url, snippet, "nvd.nist.gov", trustOf(url))
            }
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        out
    }

    /**
     * endoflife.date（软件生命周期，查「XX 什么时候停止支持」）
     * —— 移植自 tools-lookup.mjs searchEndOfLife
     */
    private suspend fun searchEndOfLife(q: String): List<Item> = withContext(Dispatchers.IO) {
        try {
            // 先拿全部产品名，再按关键词模糊匹配（API 没有搜索接口）
            val listJson = fetchText("https://endoflife.date/api/all.json", timeoutMs = 15_000)
                ?: return@withContext emptyList()
            val all = org.json.JSONArray(listJson)
            val kw = q.lowercase().replace(Regex("\\s+"), "")
            val hit = mutableListOf<String>()
            for (i in 0 until all.length()) {
                val n = all.optString(i)
                if (n.lowercase().contains(kw)) {
                    hit.add(n)
                    if (hit.size >= 3) break
                }
            }
            if (hit.isEmpty()) return@withContext emptyList()
            val out = mutableListOf<Item>()
            for (name in hit) {
                val r = fetchText("https://endoflife.date/api/${enc(name)}.json", timeoutMs = 15_000)
                    ?: continue
                val cycles = org.json.JSONArray(r)
                for (j in 0 until minOf(cycles.length(), 3)) {
                    val c = cycles.optJSONObject(j) ?: continue
                    // JS: c.eol === true ? '已停止' : (c.eol || '?') —— false/缺失都显示 ?
                    val eol = c.opt("eol")
                    val eolStr = when {
                        eol == true -> "已停止"
                        eol is String && eol.isNotEmpty() -> eol
                        else -> "?"
                    }
                    val latest = c.optString("latest")
                    val title = "$name ${c.optString("cycle")}"
                    val url = "https://endoflife.date/$name"
                    val snippet = "发布 ${c.optString("releaseDate").ifEmpty { "?" }} · 停止支持 $eolStr" +
                        (if (latest.isNotEmpty()) " · 最新 $latest" else "")
                    out += Item(title, url, snippet, "endoflife.date", trustOf(url))
                }
            }
            return@withContext out.take(5)
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
    }

    /** HuggingFace 镜像（官方站被墙，走 hf-mirror.com）—— 移植自 tools-lookup.mjs searchHuggingFace */
    private suspend fun searchHuggingFace(q: String): List<Item> = withContext(Dispatchers.IO) {
        val json = fetchText(
            "https://hf-mirror.com/api/models?search=${enc(q)}&limit=5&sort=downloads&direction=-1",
            timeoutMs = 15_000,
        ) ?: return@withContext emptyList()
        try {
            // 响应直接是数组（不是对象包数组）；非数组会抛异常进 catch
            val arr = org.json.JSONArray(json)
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(arr.length(), 5)) {
                val m = arr.optJSONObject(i) ?: continue
                val id = m.optString("modelId").ifEmpty { m.optString("id") }
                val pipeline = m.optString("pipeline_tag")
                val title = id + if (pipeline.isNotEmpty()) " — $pipeline" else ""
                if (title.isEmpty()) continue
                val url = "https://huggingface.co/$id"
                val downloads = String.format("%,d", m.optLong("downloads", 0))
                val snippet = "下载 $downloads · ♥ ${m.optInt("likes", 0)} · 更新 ${m.optString("lastModified").take(10)}"
                out += Item(title, url, snippet, "huggingface.co", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** GitHub Releases（查软件最新版本，GitHub 搜索只给仓库不给 release）—— 移植自 tools-lookup.mjs searchGithubReleases */
    private suspend fun searchGithubReleases(q: String): List<Item> = withContext(Dispatchers.IO) {
        try {
            // 先搜仓库，再取第一个的 releases
            val s = fetchText(
                "https://api.github.com/search/repositories?q=${enc(q)}&per_page=1&sort=stars",
                headers = mapOf("Accept" to "application/vnd.github+json"),
                timeoutMs = 15_000,
            ) ?: return@withContext emptyList()
            val repo = JSONObject(s).optJSONArray("items")?.optJSONObject(0)?.optString("full_name") ?: ""
            if (repo.isEmpty()) return@withContext emptyList()
            val r = fetchText(
                "https://api.github.com/repos/$repo/releases?per_page=5",
                headers = mapOf("Accept" to "application/vnd.github+json"),
                timeoutMs = 15_000,
            ) ?: return@withContext emptyList()
            val rel = org.json.JSONArray(r)
            val out = mutableListOf<Item>()
            for (i in 0 until minOf(rel.length(), 5)) {
                val x = rel.optJSONObject(i) ?: continue
                val tag = x.optString("tag_name")
                val relName = x.optString("name")
                val title = "$repo $tag" +
                    (if (relName.isNotEmpty() && relName != tag) " — ${relName.take(40)}" else "")
                val url = x.optString("html_url")
                if (url.isEmpty() || title.isEmpty()) continue
                val snippet = "发布 ${x.optString("published_at").take(10)}" +
                    (if (x.optBoolean("prerelease", false)) " · 预发布" else "")
                out += Item(title, url, snippet, "github.com", trustOf(url))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SearchInfo 工具
    // ══════════════════════════════════════════════════════════════

    inner class SearchInfoTool : Tool() {
        override val name = "SearchInfo"
        override val description =
            "多来源资料搜索：一次查 26 个源，返回资料卡（标题+摘要+来源+可信度标注，按可信度排序）。" +
                "通用源 11 个：Bing/百度/搜狗/360/B站/CSDN/掘金/Mojeek/HackerNews/StackOverflow/GitHub；" +
                "垂直源 15 个：学术（arXiv/OpenAlex/Crossref/PubMed/Zenodo/DOAJ/EuropePMC）、" +
                "包（npm/PyPI/crates）、文档（MDN）、安全（NVD CVE）、运维（endoflife）、" +
                "模型（HuggingFace 镜像）、版本（GitHub Releases）——" +
                "垂直源只在关键词落在其领域时才有结果（搜「周杰伦」arXiv 返回空是正常的），不抢通用源的位置。" +
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

            // 并行搜所有源 × 所有关键词（与 CLI 一致：26 个源全并行，关键词之间也并行）。
            // 每条结果带上来源关键词，供下面的垂直源相关性过滤用（对应 CLI 的 tagged._q）。
            // 源的分组与顺序照 CLI 的调度表（tools-lookup.mjs runOne）。
            val tagged = coroutineScope {
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<Pair<Item, String>>>>()
                keywords.forEach { q ->
                    // 通用源 11 个：bing/baidu/bili/mojeek/sogou/360/csdn/juejin/hn/so/gh
                    jobs += async { searchBing(q).map { it to q } }
                    jobs += async { searchBaidu(q).map { it to q } }
                    jobs += async { searchBili(q).map { it to q } }
                    jobs += async { searchMojeek(q).map { it to q } }
                    jobs += async { searchSogou(q).map { it to q } }
                    jobs += async { searchSo360(q).map { it to q } }
                    jobs += async { searchCsdn(q).map { it to q } }
                    jobs += async { searchJuejin(q).map { it to q } }
                    jobs += async { searchHackerNews(q).map { it to q } }
                    jobs += async { searchStackOverflow(q).map { it to q } }
                    jobs += async { searchGithub(q).map { it to q } }
                    // 垂直源 15 个：
                    //   学术 —— arxiv/openalex/crossref/pubmed/zenodo/doaj/europepmc
                    //   包   —— npm/pypi/crates
                    //   文档 —— mdn · 安全 —— nvd · 运维 —— endoflife
                    //   模型 —— huggingface（走镜像） · 版本 —— github-releases
                    // —— 垂直源只在关键词落在其领域时才有结果（搜「周杰伦」arxiv 返回空是正常的），
                    //    所以它们不抢位置，是「顺带捞一把」。
                    jobs += async { searchArxiv(q).map { it to q } }
                    jobs += async { searchOpenAlex(q).map { it to q } }
                    jobs += async { searchCrossref(q).map { it to q } }
                    jobs += async { searchPubmed(q).map { it to q } }
                    jobs += async { searchNpm(q).map { it to q } }
                    jobs += async { searchPypi(q).map { it to q } }
                    jobs += async { searchMdn(q).map { it to q } }
                    jobs += async { searchZenodo(q).map { it to q } }
                    jobs += async { searchDoaj(q).map { it to q } }
                    jobs += async { searchEuropePmc(q).map { it to q } }
                    jobs += async { searchCrates(q).map { it to q } }
                    jobs += async { searchNvd(q).map { it to q } }
                    jobs += async { searchEndOfLife(q).map { it to q } }
                    jobs += async { searchHuggingFace(q).map { it to q } }
                    jobs += async { searchGithubReleases(q).map { it to q } }
                }
                jobs.awaitAll().flatten()
            }

            // 垂直源相关性过滤（移植自 tools-lookup.mjs 的 VERTICAL_SOURCES / isRelevant）：
            // 剔除「英文技术站被中文词查询时兜底返回的热门内容」这类噪音。
            // 用「该条来自哪个关键词」逐条判断，而不是拿整组关键词 ——
            // 多关键词并行时，A 词搜到的条目不该因为 B 词不匹配被误杀（与 CLI 的 _q 机制一致）。
            val filtered = tagged.filter { (item, q) ->
                val host = item.source.removePrefix("www.")
                host !in VERTICAL_SOURCES || isRelevant(item.title, item.snippet, q)
            }

            if (filtered.isEmpty()) {
                return ToolResult.failed("所有来源都没有结果（可能是网络问题，或关键词太生僻）")
            }

            // 去重（按 URL）→ 排序（可信度降序）
            val deduped = filtered
                .map { it.first }
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
