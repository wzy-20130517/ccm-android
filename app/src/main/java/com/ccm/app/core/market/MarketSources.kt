package com.ccm.app.core.market

import android.util.Log
import com.ccm.app.ui.pages.MarketItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 外部市场源适配器（2026-10-06 加）。
 *
 * ══════════════════════════════════════════════════════════════
 *  三个官方源（用户指定）
 * ══════════════════════════════════════════════════════════════
 *
 * | 源 | 类型 | 数据格式 |
 * |---|---|---|
 * | `github.com/anthropics/skills` | skill | GitHub Contents API（列 skills/ 目录） |
 * | `registry.modelcontextprotocol.io` | mcp | `/v0/servers?limit=N`（cursor 分页） |
 * | `api.dsh-plugin.org` | plugin | `/plugins.zh.json`（压缩字段） |
 *
 * ══════════════════════════════════════════════════════════════
 *  设计要点
 * ══════════════════════════════════════════════════════════════
 *
 * 1. **每个源独立失败** —— 一个源挂了不该让整个市场空白。
 *    每个 fetch 自己 try/catch，返回空列表而不是抛异常。
 *
 * 2. **统一成 MarketItem** —— 上层 UI/安装逻辑完全不用改。
 *    源的差异（字段名、分页、URL 拼法）都在这里消化。
 *
 * 3. **安装路径**：
 *    · skill → 下载 SKILL.md 到 files/skills/<name>/
 *    · mcp   → 记 remote URL（写 mcp.json，远程 HTTP 型 MCP）
 *    · plugin → DSH 生态，交给 dsh-host 处理（这里只列，不装）
 */
object MarketSources {

    private const val TAG = "MarketSources"
    private const val UA = "CCM-Android/1.0"

    /**
     * DSH 插件最多列多少个。
     *
     * 源里有 12699 个（实测），全列会卡死 UI。100 个够用户"看看有什么"，
     * 真要找特定的用搜索（后续可加）。
     */
    private const val MAX_DSH_ITEMS = 100

    /** 源标识（给 UI 显示来源用）。 */
    const val SOURCE_ANTHROPIC_SKILLS = "anthropic"
    const val SOURCE_MCP_REGISTRY = "mcp-registry"
    const val SOURCE_DSH_PLUGIN = "dsh-plugin"

    // ══════════════════════════════════════════════════════════════
    //  源 1：anthropics/skills（GitHub）
    // ══════════════════════════════════════════════════════════════

    /**
     * 拉 anthropics/skills 的 skill 列表。
     *
     * 用 GitHub Contents API 列 `skills/` 目录（每个子目录是一个 skill）。
     * 装法：下载该目录下的 SKILL.md（单文件，skill 的核心就是它）。
     *
     * ⚠️ GitHub API 未认证时限流 60 次/小时 —— 只在用户点刷新时调，
     * 不要轮询。
     */
    suspend fun fetchAnthropicSkills(): List<MarketItem> = withContext(Dispatchers.IO) {
        try {
            val text = httpGet("https://api.github.com/repos/anthropics/skills/contents/skills")
                ?: return@withContext emptyList()
            val arr = JSONArray(text)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (o.optString("type") != "dir") return@mapNotNull null
                val name = o.optString("name")
                if (name.isBlank()) return@mapNotNull null
                MarketItem(
                    id = "anthropic-skill-$name",
                    type = "skill",
                    name = name,
                    description = "Anthropic 官方 skill（github.com/anthropics/skills）",
                    author = "Anthropic",
                    // SKILL.md 的 raw 地址 —— 装的时候下载它
                    files = listOf(
                        "https://raw.githubusercontent.com/anthropics/skills/main/skills/$name/SKILL.md",
                    ),
                    entry = "SKILL.md",
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "拉 anthropics/skills 失败：${e.message}")
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  源 2：MCP Registry
    // ══════════════════════════════════════════════════════════════

    /**
     * 拉 MCP 官方注册表。
     *
     * 端点：`GET /v0/servers?limit=N`
     * 返回：`{servers: [{server: {name, description, title, version, remotes|packages}}], metadata: {nextCursor}}`
     *
     * 这里只取第一页（limit=50）—— 分页要 cursor 循环，而市场 UI 是
     * 简单列表，先给用户看到"有什么"比翻完 3000 个更重要。
     */
    suspend fun fetchMcpRegistry(): List<MarketItem> = withContext(Dispatchers.IO) {
        try {
            val text = httpGet("https://registry.modelcontextprotocol.io/v0/servers?limit=50")
                ?: return@withContext emptyList()
            val root = JSONObject(text)
            val arr = root.optJSONArray("servers") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val wrapper = arr.optJSONObject(i) ?: return@mapNotNull null
                val s = wrapper.optJSONObject("server") ?: return@mapNotNull null
                val name = s.optString("name")
                if (name.isBlank()) return@mapNotNull null
                // 远程型 MCP（有 remotes）才有意义 —— 本地包型需要装 node 环境，
                // APK 这边跑不动（那是 dsh-host / CLI 的活）。
                val remotes = s.optJSONArray("remotes")
                val remoteUrl = remotes?.optJSONObject(0)?.optString("url").orEmpty()
                MarketItem(
                    id = "mcp-reg-$name",
                    type = "mcp",
                    name = s.optString("title").ifBlank { name },
                    description = s.optString("description").take(200),
                    author = name.substringBefore('/'),
                    size = s.optString("version"),
                    // 远程型：把 URL 存进 files 字段（安装时写 mcp.json 的 url）
                    files = if (remoteUrl.isNotBlank()) listOf(remoteUrl) else emptyList(),
                    entry = remoteUrl,
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "拉 MCP Registry 失败：${e.message}")
            emptyList()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  源 3：DSH Plugin Hub
    // ══════════════════════════════════════════════════════════════

    /**
     * 拉 DSH 插件目录。
     *
     * 端点：`GET https://api.dsh-plugin.org/plugins.zh.json`
     *
     * 字段是**压缩命名**（省流量）：
     *   s=slug, o=owner, n=name, vr=version, c=category, t=tags[],
     *   f=features[], d=description, r=repo(owner/slug), ic=installCmd,
     *   v=verified, u=updatedAt, a=addedAt
     *
     * 装法：DSH 插件走 `dsh plugin add github:owner/slug`（需要 dsh-host），
     * APK 这边只做**列表展示 + 复制安装命令** —— 不在手机上跑 npm。
     */
    suspend fun fetchDshPlugins(): List<MarketItem> = withContext(Dispatchers.IO) {
        try {
            // 主源失败（实测 TLS 握手直接挂）→ 落 npm 备源（npmmirror 秒回）
            val text = httpGet(
                "https://api.dsh-plugin.org/plugins.zh.json",
                connectMs = 6_000, readMs = 15_000,
            ) ?: return@withContext fetchDshPluginsFromNpm()
            val arr = JSONArray(text)
            // ⚠️ 实测这个源有 **12699 个插件** —— 全列会：
            //   · 解析 12MB JSON 卡住主线程（已放 IO，但内存也吃）
            //   · UI 渲染上万个卡片直接卡死
            // 只取前 MAX_DSH_ITEMS 个（按源里的顺序 = 官方排序，通常
            // 最新的/精选的在前）。要更多得加分页或搜索。
            val limit = minOf(arr.length(), MAX_DSH_ITEMS)
            (0 until limit).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val slug = o.optString("s")
                if (slug.isBlank()) return@mapNotNull null
                val features = o.optJSONArray("f")
                val featText = if (features != null && features.length() > 0) {
                    " · " + (0 until minOf(2, features.length())).joinToString("；") {
                        features.optString(it)
                    }
                } else ""
                MarketItem(
                    id = "dsh-$slug",
                    type = "plugin",
                    name = o.optString("n").ifBlank { slug },
                    description = o.optString("d").take(200) + featText,
                    author = o.optString("o"),
                    size = o.optString("vr"),
                    // DSH 插件的"安装"是给 dsh-host 用的命令 —— 存进 entry
                    entry = o.optString("ic").ifBlank {
                        o.optString("r").takeIf { it.isNotBlank() }?.let { "dsh plugin add github:$it" }.orEmpty()
                    },
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "拉 DSH Plugin Hub 失败：${e.message}")
            fetchDshPluginsFromNpm()
        }
    }

    /**
     * DSH 插件备源：npmmirror 的 npm search API（2026-10-07 加）。
     *
     * 主源 api.dsh-plugin.org 实测 TLS 握手失败（http=000，curl 退出 55），
     * 一进市场页就卡到超时。npm 本来就是 DSH 插件的官方发布渠道，
     * npmmirror 国内秒回（实测 <1s，10000 命中）。
     *
     * 过滤：包名 `dsh-` 前缀（社区命名约定），排除官方 `@deepseek-ai`
     * 作用域（那是框架本体不是插件）。entry = npm 包名 —— MarketClient 的
     * plugin 分支做 substringAfter("add ") 时找不到标记会原样返回，
     * 正好作为 npm install 的 spec。
     */
    private suspend fun fetchDshPluginsFromNpm(): List<MarketItem> =
        withContext(Dispatchers.IO) {
            try {
                val url = "https://registry.npmmirror.com/-/v1/search" +
                    "?text=dsh-plugin&size=100"
                val text = httpGet(url, connectMs = 6_000, readMs = 12_000)
                    ?: return@withContext emptyList()
                val root = JSONObject(text)
                val arr = root.optJSONArray("objects") ?: return@withContext emptyList()
                val out = mutableListOf<MarketItem>()
                for (i in 0 until minOf(arr.length(), MAX_DSH_ITEMS)) {
                    val pkg = arr.optJSONObject(i)?.optJSONObject("package") ?: continue
                    val name = pkg.optString("name")
                    if (name.isBlank()) continue
                    // 排除官方包（框架本体）与不符合命名约定的
                    if (name.startsWith("@deepseek-ai/")) continue
                    if (!name.startsWith("dsh-") && !name.contains("/dsh-")) continue
                    val desc = pkg.optString("description").orEmpty().take(200)
                    if (desc.isBlank()) continue
                    out += MarketItem(
                        id = "npm-$name",
                        type = "plugin",
                        name = name,
                        description = desc,
                        size = pkg.optString("version"),
                        entry = name,
                    )
                }
                out
            } catch (t: Throwable) {
                Log.w(TAG, "npm 备源失败: ${t.message}")
                emptyList()
            }
        }

    /** 并行拉三个源，合并（每源独立失败不影响其他）。 */
    suspend fun fetchAll(): List<MarketItem> = withContext(Dispatchers.IO) {
        // 【2026-10-07 改并发】原顺序拉的前提「三个源都 <1s」已不成立：
        // 实测 dsh-plugin.org TLS 直接失败要等 16s 超时 —— 一个坏源
        // 拖死整页。三源并发，坏源只拖自己（每个源内部 try-catch，
        // 失败返回空列表，不传染）。
        coroutineScope {
            val skills = async { fetchAnthropicSkills() }
            val mcp = async { fetchMcpRegistry() }
            val dsh = async { fetchDshPlugins() }
            // 按固定顺序拼（保持 UI 分组稳定，与顺序拉一致）
            skills.await() + mcp.await() + dsh.await()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════════

    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    /**
     * GET 文本（GitHub 直连失败时走镜像）。
     *
     * @param connectMs 连接超时（坏源实测 TLS 握手就能挂十几秒 —— 收紧到 8s）
     * @param readMs 读取超时（dsh 12MB 大文件单独放宽）
     */
    private fun httpGet(
        url: String,
        connectMs: Int = 8_000,
        readMs: Int = 15_000,
    ): String? {
        val candidates = if (url.contains("github")) {
            listOf(url, MIRROR_PREFIX + url)
        } else listOf(url)
        for (u in candidates) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = connectMs
                conn.readTimeout = readMs
                conn.setRequestProperty("User-Agent", UA)
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode == 200) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                }
                conn.disconnect()
            } catch (_: Throwable) {
                // 试下一个
            }
        }
        return null
    }
}
