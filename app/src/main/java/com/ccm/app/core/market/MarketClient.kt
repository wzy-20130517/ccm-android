package com.ccm.app.core.market

import android.content.Context
import android.util.Log
import com.ccm.app.ui.pages.MarketItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 市场客户端 —— 拉清单 + 下载安装。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * ## 数据源
 *
 * GitHub Release `market-v1`：
 * - `registry.json` —— 清单（本类解析）
 * - `<id>.tar.gz` —— 各资源包
 *
 * ## 安装位置
 *
 * | 类型 | 解压到 | 额外 |
 * |---|---|---|
 * | skill | `files/skills/<id>/` | — |
 * | mcp | `files/mcp/<id>/` | 写 mcp.json（command 指向 rootfs 里的 server）|
 *
 * ## MCP 的额外步骤
 *
 * MCP server 是 Node 脚本，需要：
 * 1. 部署到 rootfs（`/root/.ccm/mcp/<id>/`）—— 因为 node 在 rootfs 里
 * 2. 确保 node 已装（apt install nodejs）
 * 3. 写 mcp.json（command = proot 包装的完整命令）
 * ═══════════════════════════════════════════════════════════════
 */
object MarketClient {

    private const val TAG = "MarketClient"

    /** 市场清单地址（GitHub Release）。 */
    private const val REGISTRY_URL =
        "https://github.com/wzy-20130517/ccm-android/releases/download/market-v1/registry.json"

    /** 国内镜像前缀（github.com 直连不通时用）。 */
    private const val MIRROR_PREFIX = "https://gh-proxy.com/"

    /**
     * 拉清单（**多源合并**，2026-10-06 改）。
     *
     * 三个来源：
     *   1. 本仓库的 registry.json（自有 skill/mcp，原有逻辑）
     *   2. **外部源**（MarketSources）：anthropics/skills、MCP Registry、
     *      DSH Plugin Hub
     *
     * 每源独立失败 —— 自有源挂了仍有外部源可用，反之亦然。
     * 只有**全部为空**才返回 null（让 UI 显示"拉取失败"而不是"没有内容"）。
     */
    /** 缓存文件（市场清单落盘，6 小时 TTL）。 */
    private const val REGISTRY_CACHE_FILE = "market-registry-cache.json"

    /** 缓存有效期：6 小时。市场数据不频繁变，过期才拉网络。 */
    private const val REGISTRY_CACHE_TTL_MS = 6 * 3600_000L

    /**
     * 拉清单（**缓存优先**，2026-10-07 加）。
     *
     * 背景：外部源实测有死源（api.dsh-plugin.org TLS 握手失败，卡到超时）
     * —— 即便加了并发，每次进市场页都白等一轮网络。
     *
     * 策略：
     *   1. 缓存新鲜（<6h）→ **秒回**，不碰网络
     *   2. 过期/无缓存 → 拉网络，成功写缓存
     *   3. 网络全挂 → 过期缓存兜底（比给用户看空白强）
     */
    suspend fun fetchRegistry(ctx: Context): List<MarketItem>? = withContext(Dispatchers.IO) {
        val cacheFile = File(ctx.filesDir, REGISTRY_CACHE_FILE)
        val cached = readCache(cacheFile)

        // 1. 新鲜缓存秒回
        if (cached != null && System.currentTimeMillis() - cached.first < REGISTRY_CACHE_TTL_MS) {
            return@withContext cached.second
        }

        // 2. 拉网络
        val own = fetchOwnRegistry()
        val external = try { MarketSources.fetchAll() } catch (_: Throwable) { emptyList() }
        // own 可空（自有源挂了）→ 用 orEmpty 兜底，别让整个市场跟着失败
        val merged = own.orEmpty() + external
        // 按 id 去重（理论上不会重，但外部源 id 前缀不同，防御性去重）
        val seen = mutableSetOf<String>()
        val deduped = merged.filter { seen.add(it.id) }
        val fresh = if (deduped.isEmpty() && own == null) null else deduped

        if (fresh != null && fresh.isNotEmpty()) {
            writeCache(cacheFile, fresh)
            return@withContext fresh
        }

        // 3. 网络失败 → 过期缓存兜底
        cached?.second?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        fresh
    }

    // ── 缓存读写 ────────────────────────────────────────────────

    private fun writeCache(file: File, items: List<MarketItem>) {
        try {
            val arr = JSONArray()
            items.forEach { arr.put(itemToJson(it)) }
            val root = JSONObject()
            root.put("ts", System.currentTimeMillis())
            root.put("items", arr)
            file.writeText(root.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "写市场缓存失败: ${t.message}")
        }
    }

    private fun readCache(file: File): Pair<Long, List<MarketItem>>? {
        return try {
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val ts = root.optLong("ts", 0L)
            val arr = root.optJSONArray("items") ?: return null
            val items = (0 until arr.length()).mapNotNull { jsonToItem(arr.optJSONObject(it)) }
            if (items.isEmpty()) null else ts to items
        } catch (t: Throwable) {
            Log.w(TAG, "读市场缓存失败: ${t.message}")
            null
        }
    }

    private fun itemToJson(i: MarketItem): JSONObject = JSONObject().apply {
        put("id", i.id)
        put("type", i.type)
        put("name", i.name)
        put("description", i.description)
        put("author", i.author)
        put("size", i.size)
        put("url", i.url)
        put("files", JSONArray(i.files))
        put("npmInstall", i.npmInstall)
        put("entry", i.entry)
        put("npmPackage", i.npmPackage)
        put("installBrowser", i.installBrowser)
        put("aptDeps", JSONArray(i.aptDeps))
        val env = JSONObject()
        i.env.forEach { (k, v) -> env.put(k, v) }
        put("env", env)
    }

    private fun jsonToItem(o: JSONObject?): MarketItem? = o?.let { j ->
            MarketItem(
                id = j.optString("id"),
                type = j.optString("type"),
                name = j.optString("name"),
                description = j.optString("description"),
                author = j.optString("author"),
                size = j.optString("size"),
                url = j.optString("url"),
                files = j.optJSONArray("files").jsonStrList(),
                npmInstall = j.optBoolean("npmInstall"),
                entry = j.optString("entry"),
                npmPackage = j.optString("npmPackage"),
                installBrowser = j.optBoolean("installBrowser"),
                aptDeps = j.optJSONArray("aptDeps").jsonStrList(),
                env = j.optJSONObject("env").jsonStrMap(),
            )
        }

    private fun JSONArray?.jsonStrList(): List<String> =
        this?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()

    private fun JSONObject?.jsonStrMap(): Map<String, String> {
        val o = this ?: return emptyMap()
    val out = mutableMapOf<String, String>()
        o.keys().forEach { k -> out[k] = o.optString(k) }
        return out
    }

    /** 拉自有 registry.json（原 fetchRegistry 的逻辑，重命名保留）。 */
    private suspend fun fetchOwnRegistry(): List<MarketItem>? = withContext(Dispatchers.IO) {
        try {
            val text = httpGet(REGISTRY_URL) ?: return@withContext null
            val root = JSONObject(text)
            val arr = root.optJSONArray("items") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val env = mutableMapOf<String, String>()
                o.optJSONObject("env")?.let { e ->
                    e.keys().forEach { k -> env[k] = e.optString(k, "") }
                }
                val filesArr = o.optJSONArray("files")
                val files = if (filesArr != null) {
                    (0 until filesArr.length()).mapNotNull { j -> filesArr.optString(j, "").takeIf { it.isNotBlank() } }
                } else emptyList()

                val aptArr = o.optJSONArray("aptDeps")
                val aptDeps = if (aptArr != null) {
                    (0 until aptArr.length()).mapNotNull { j -> aptArr.optString(j, "").takeIf { it.isNotBlank() } }
                } else emptyList()

                MarketItem(
                    id = o.optString("id", ""),
                    type = o.optString("type", ""),
                    name = o.optString("name", ""),
                    description = o.optString("description", ""),
                    author = o.optString("author", ""),
                    size = o.optString("size", ""),
                    url = o.optString("url", ""),
                    files = files,
                    npmInstall = o.optBoolean("npmInstall", false),
                    entry = o.optString("entry", ""),
                    npmPackage = o.optString("npmPackage", ""),
                    installBrowser = o.optBoolean("installBrowser", false),
                    aptDeps = aptDeps,
                    env = env,
                )
            }.filter { it.id.isNotBlank() }
        } catch (t: Throwable) {
            Log.e(TAG, "拉清单失败：${t.message}", t)
            null
        }
    }

    /**
     * 安装一个条目。
     *
     * @param env MCP 的环境变量（skill 不用）
     * @param onLog 进度回调
     */
    suspend fun install(
        ctx: Context,
        item: MarketItem,
        env: Map<String, String> = emptyMap(),
        onLog: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // ⚠️ 只有 tar.gz 方式（url 非空）才在这里下载 ——
            // files 方式（mcp）在每个分支里逐个下载散文件。
            val tmp = File(ctx.cacheDir, "${item.id}.tar.gz")
            if (item.url.isNotBlank()) {
                onLog("正在下载 ${item.name}…")
                if (!download(item.url, tmp)) {
                    onLog("❌ 下载失败")
                    return@withContext false
                }
            }

            // 按类型处理
            when (item.type) {
                "skill" -> {
                    // 【2026-10-06 改】支持两种形式：
                    //   · tar.gz 包（自有 registry 的老格式）→ 解压
                    //   · 散文件（外部源如 anthropics/skills 的单 SKILL.md）→ 逐个下载
                    // 判据：url 非空 = tar.gz；files 非空 = 散文件。
                    // 统一推导安装目录名（与 UI 的 installedNameOf 必须一致）
                    val skillName = item.id
                        .removePrefix("anthropic-skill-")
                        .removePrefix("skill-")
                        .ifBlank { item.id }
                    if (item.url.isNotBlank()) {
                        // tar.gz：解压到 skills/<skillName>/ —— 与散文件路径统一，
                        // 这样 installed 检测（按目录名）对两种格式都成立。
                        val dest = File(ctx.filesDir, "skills/$skillName")
                        dest.mkdirs()
                        onLog("正在解压…")
                        extractTarGz(tmp, dest)
                        onLog("✅ 已装到 ${dest.absolutePath}")
                    } else if (item.files.isNotEmpty()) {
                        // 散文件 skill：装到 files/skills/<名字>/
                        val dest = File(ctx.filesDir, "skills/$skillName")
                        dest.mkdirs()
                        onLog("正在下载 ${item.files.size} 个文件…")
                        item.files.forEach { fileUrl ->
                            val fname = fileUrl.substringAfterLast('/').ifBlank { "SKILL.md" }
                            if (!download(fileUrl, File(dest, fname))) {
                                onLog("❌ 下载失败：$fname")
                                return@withContext false
                            }
                        }
                        onLog("✅ 已装到 ${dest.absolutePath}")
                    } else {
                        onLog("❌ 这个条目既没有包地址也没有文件列表（源数据异常）")
                        return@withContext false
                    }
                }
                "mcp" -> {
                    // MCP 要装到 rootfs（node 在那里）+ 写 mcp.json
                    val runtime = com.ccm.app.runtime.ProotRuntime(ctx)
                    val rootfs = runtime.rootfsDir()
                    val dest = File(rootfs, "root/.ccm/mcp/${item.id}")
                    dest.mkdirs()

                    if (item.npmPackage.isNotBlank()) {
                        // 【2026-10-06】npm 包方式（playwright 这类）——
                        // 1. 确保 node
                        // 2. apt 装系统依赖（libnss3 等）
                        // 3. npm install 包
                        // 4. 可选：下载浏览器
                        onLog("检查 Node 运行时…")
                        val installer1 = com.ccm.app.core.mcp.McpInstaller(ctx, runtime)
                        if (!installer1.ensureNode { s -> onLog(s) }) {
                            onLog("❌ Node 安装失败")
                            return@withContext false
                        }

                        // apt 依赖
                        if (item.aptDeps.isNotEmpty()) {
                            onLog("正在装系统依赖（${item.aptDeps.size} 个）…")
                            runAptInstall(runtime, item.aptDeps) { s -> onLog(s) }
                        }

                        // npm install
                        onLog("正在装 npm 包（${item.npmPackage}）…")
                        val npmDir = "/root/.ccm/mcp/${item.id}"
                        val ok1 = runNpmPackageInstall(runtime, npmDir, item.npmPackage) { s -> onLog(s) }
                        if (!ok1) {
                            onLog("❌ npm install 失败")
                            return@withContext false
                        }

                        // 浏览器（playwright）
                        if (item.installBrowser) {
                            onLog("正在下载浏览器（~150MB，慢）…")
                            runPlaywrightInstall(runtime, npmDir) { s -> onLog(s) }
                        }
                    } else if (item.files.isNotEmpty()) {
                        // 【2026-10-06】散文件方式（不打包 tar.gz）——
                        // 从 GitHub raw 逐个下载（server.mjs + package.json 等）
                        onLog("正在下载 ${item.files.size} 个文件…")
                        item.files.forEach { url ->
                            val name = url.substringAfterLast('/')
                            if (!download(url, File(dest, name))) {
                                onLog("❌ 下载失败：$name")
                                return@withContext false
                            }
                        }
                        // 需要 npm install（装依赖）
                        if (item.npmInstall) {
                            onLog("正在装依赖（npm install）…")
                            val installer0 = com.ccm.app.core.mcp.McpInstaller(ctx, runtime)
                            if (!installer0.ensureNode { s -> onLog(s) }) {
                                onLog("❌ Node 安装失败")
                                return@withContext false
                            }
                            val ok = runNpmInstall(runtime, "/root/.ccm/mcp/${item.id}") { s -> onLog(s) }
                            if (!ok) {
                                onLog("❌ npm install 失败")
                                return@withContext false
                            }
                        }
                    } else {
                        // tar.gz 方式
                        onLog("正在部署 server…")
                        extractTarGz(tmp, dest)
                    }

                    onLog("检查 Node 运行时…")
                    val installer = com.ccm.app.core.mcp.McpInstaller(ctx, runtime)
                    if (!installer.ensureNode { s -> onLog(s) }) {
                        onLog("❌ Node 安装失败")
                        return@withContext false
                    }

                    onLog("写入配置…")
                    val entryName = item.entry.ifBlank { "server.mjs" }
                    val entry = File(dest, entryName)
                    val cmd = installer.buildProotCommand(entry.absolutePath, env)
                    val mcpFile = File(ctx.filesDir, "mcp.json")
                    val o = if (mcpFile.exists()) JSONObject(mcpFile.readText()) else JSONObject()
                    val servers = o.optJSONObject("mcpServers") ?: JSONObject()
                    servers.put(item.id, JSONObject().apply {
                        put("command", cmd[0])
                        put("args", org.json.JSONArray(cmd.drop(1)))
                        put("env", JSONObject(env as Map<*, *>))
                    })
                    o.put("mcpServers", servers)
                    mcpFile.writeText(o.toString(2))
                }
                "plugin" -> {
                    // DSH 插件：走 proot 里的 dsh-host（DshHostManager 部署 + 启动，
                    // 再调 /control/install 让宿主自己 npm install + 热加载）。
                    val host = com.ccm.app.core.plugin.DshHostManager(ctx)

                    // 首次使用：部署文件 + Node + 依赖（334MB，一次性的）
                    if (host.state() is com.ccm.app.core.plugin.DshHostManager.State.NotInstalled ||
                        host.state() is com.ccm.app.core.plugin.DshHostManager.State.DepsMissing
                    ) {
                        onLog("首次安装插件宿主（较大，约 334MB）…")
                        if (!host.deploy { s -> onLog(s) }) {
                            onLog("❌ 插件宿主部署失败")
                            return@withContext false
                        }
                    }
                    if (!host.isAlive() && !host.start { s -> onLog(s) }) {
                        onLog("❌ 插件宿主启动失败")
                        return@withContext false
                    }

                    // entry 形如「dsh plugin add github:owner/slug」或裸包名
                    val spec = item.entry
                        .substringAfter("add ", item.entry)
                        .trim()
                    if (spec.isBlank()) {
                        onLog("❌ 该条目没有可识别的安装规格")
                        return@withContext false
                    }
                    onLog("安装插件 $spec（npm，可能需要几分钟）…")
                    when (val r = com.ccm.app.core.plugin.PluginManager.install(spec)) {
                        is com.ccm.app.core.plugin.PluginManager.Result.Ok -> {
                            onLog("✅ 插件 $spec 已安装")
                        }
                        is com.ccm.app.core.plugin.PluginManager.Result.Err -> {
                            onLog("❌ ${r.message}")
                            return@withContext false
                        }
                    }
                }
                else -> {
                    onLog("❌ 未知类型：${item.type}")
                    return@withContext false
                }
            }

            tmp.delete()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "安装 ${item.id} 失败：${t.message}", t)
            onLog("❌ ${t.message}")
            false
        }
    }

    /** 卸载。 */
    suspend fun uninstall(
        ctx: Context,
        item: MarketItem,
        onLog: (String) -> Unit = {},
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            when (item.type) {
                "skill" -> {
                    // 三种可能的安装位置都清（散文件装的是「去前缀后的名字」目录）
                    val skillName = item.id
                        .removePrefix("anthropic-skill-")
                        .removePrefix("skill-")
                        .ifBlank { item.id }
                    File(ctx.filesDir, "skills/${item.id}").deleteRecursively()
                    File(ctx.filesDir, "skills/$skillName").deleteRecursively()
                    File(ctx.filesDir, "skills/${item.id}.md").delete()
                }
                "plugin" -> {
                    // 从宿主配置移除 + 热卸载（npm 包保留，与 CLI 侧行为一致）
                    val host = com.ccm.app.core.plugin.DshHostManager(ctx)
                    if (host.isAlive()) {
                        val spec = item.entry
                            .substringAfter("add ", item.entry)
                            .trim()
                        com.ccm.app.core.plugin.PluginManager.remove(spec)
                    }
                }
                "mcp" -> {
                    // 删 rootfs 里的 + mcp.json 里的
                    try {
                        val runtime = com.ccm.app.runtime.ProotRuntime(ctx)
                        File(runtime.rootfsDir(), "root/.ccm/mcp/${item.id}").deleteRecursively()
                    } catch (_: Throwable) {}
                    val mcpFile = File(ctx.filesDir, "mcp.json")
                    if (mcpFile.exists()) {
                        val o = JSONObject(mcpFile.readText())
                        o.optJSONObject("mcpServers")?.remove(item.id)
                        mcpFile.writeText(o.toString(2))
                    }
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "卸载 ${item.id} 失败：${t.message}", t)
            false
        }
    }

    /** apt 装系统依赖。 */
    private fun runAptInstall(
        runtime: com.ccm.app.runtime.ProotRuntime,
        pkgs: List<String>,
        onLog: (String) -> Unit,
    ) {
        try {
            runtime.execWithTimeout(
                command = listOf(
                    "/bin/bash", "-c",
                    "export DEBIAN_FRONTEND=noninteractive; " +
                        "apt-get install -y -qq ${pkgs.joinToString(" ")} 2>&1 | tail -3; echo APT_DONE",
                ),
                workDir = "/root",
                onLine = { line -> if (line.contains("APT_DONE")) onLog("系统依赖装完") },
                timeoutMs = 5 * 60_000L,
                idleMs = 2 * 60_000L,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "apt 装依赖失败：${t.message}", t)
        }
    }

    /** npm install 一个包（到指定目录）。 */
    private fun runNpmPackageInstall(
        runtime: com.ccm.app.runtime.ProotRuntime,
        dir: String,
        pkg: String,
        onLog: (String) -> Unit,
    ): Boolean {
        var ok = false
        try {
            runtime.execWithTimeout(
                command = listOf(
                    "/bin/bash", "-c",
                    "mkdir -p $dir && cd $dir && " +
                        "npm init -y > /dev/null 2>&1; " +
                        "npm install --no-audit --no-fund $pkg 2>&1 | tail -3; echo NPM_DONE",
                ),
                workDir = "/root",
                onLine = { line ->
                    if (line.contains("NPM_DONE")) ok = true
                    if (line.contains("added") || line.contains("npm error")) onLog(line.take(80))
                },
                timeoutMs = 10 * 60_000L,
                idleMs = 3 * 60_000L,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "npm 装包失败：${t.message}", t)
        }
        return ok
    }

    /** playwright install chromium（下载浏览器）。 */
    private fun runPlaywrightInstall(
        runtime: com.ccm.app.runtime.ProotRuntime,
        dir: String,
        onLog: (String) -> Unit,
    ) {
        try {
            runtime.execWithTimeout(
                command = listOf(
                    "/bin/bash", "-c",
                    "cd $dir && " +
                        "export PLAYWRIGHT_BROWSERS_PATH=/root/.cache/ms-playwright; " +
                        "npx playwright install chromium 2>&1 | tail -5; echo PW_DONE",
                ),
                workDir = "/root",
                onLine = { line ->
                    if (line.contains("PW_DONE")) onLog("浏览器下载完成")
                    else if (line.contains("Downloading") || line.contains("%")) onLog(line.take(80))
                },
                timeoutMs = 30 * 60_000L,   // 150MB 慢，给 30 分钟
                idleMs = 5 * 60_000L,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "playwright install 失败：${t.message}", t)
        }
    }

    /** 在 rootfs 里跑 npm install。 */
    private fun runNpmInstall(
        runtime: com.ccm.app.runtime.ProotRuntime,
        dir: String,
        onLog: (String) -> Unit,
    ): Boolean {
        var ok = false
        try {
            runtime.execWithTimeout(
                command = listOf(
                    "/bin/bash", "-c",
                    "cd $dir && npm install --omit=dev --no-audit --no-fund 2>&1 | tail -5; echo NPM_DONE",
                ),
                workDir = "/root",
                onLine = { line ->
                    if (line.contains("NPM_DONE")) ok = true
                    if (line.contains("added") || line.contains("npm error")) onLog(line.take(80))
                },
                timeoutMs = 5 * 60_000L,
                idleMs = 2 * 60_000L,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "npm install 失败：${t.message}", t)
        }
        return ok
    }

    // ── 工具方法 ────────────────────────────────────────────────────

    /** HTTP GET（直连失败走镜像）。 */
    private fun httpGet(url: String): String? {
        for (u in listOf(url, MIRROR_PREFIX + url)) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "CCM/1.0")
                if (conn.responseCode == 200) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                }
                conn.disconnect()
            } catch (_: Throwable) {}
        }
        return null
    }

    /** 下载文件（直连失败走镜像）。 */
    private fun download(url: String, dest: File): Boolean {
        for (u in listOf(url, MIRROR_PREFIX + url)) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "CCM/1.0")
                if (conn.responseCode != 200) { conn.disconnect(); continue }
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                return dest.length() > 0
            } catch (_: Throwable) {}
        }
        return false
    }

    /**
     * 解压 tar.gz —— 用项目自己的 TarExtractor（不引新依赖）。
     *
     * 【为什么不用 commons-compress】项目已有 TarExtractor（自己写的，
     * 支持 gzip/xz 按魔数识别 + 权限恢复 + 硬链接转符号链接）——
     * 引新库只会让 APK 变大。
     */
    private fun extractTarGz(archive: File, destDir: File) {
        var err = ""
        val ok = com.ccm.app.runtime.TarExtractor.extract(
            archive = archive,
            destDir = destDir,
            // 【2026-10-06】市场下载的是**第三方内容**（接入外部源后更是），
            // 用严格模式拦截逃出安装目录的符号链接（tar 逃逸的经典手法）。
            // rootfs 解压不开这个（那边有合法的包外链接）。
            strict = true,
            onError = { err = it },
        )
        if (!ok) {
            throw IllegalStateException("解压失败：$err")
        }
    }
}
