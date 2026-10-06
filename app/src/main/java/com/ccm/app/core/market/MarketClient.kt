package com.ccm.app.core.market

import android.content.Context
import android.util.Log
import com.ccm.app.ui.pages.MarketItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** 拉清单。 */
    suspend fun fetchRegistry(): List<MarketItem>? = withContext(Dispatchers.IO) {
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
                MarketItem(
                    id = o.optString("id", ""),
                    type = o.optString("type", ""),
                    name = o.optString("name", ""),
                    description = o.optString("description", ""),
                    author = o.optString("author", ""),
                    size = o.optString("size", ""),
                    url = o.optString("url", ""),
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
            // 1. 下载 tar.gz
            onLog("正在下载 ${item.name}…")
            val tmp = File(ctx.cacheDir, "${item.id}.tar.gz")
            if (!download(item.url, tmp)) {
                onLog("❌ 下载失败")
                return@withContext false
            }

            // 2. 按类型解压
            when (item.type) {
                "skill" -> {
                    onLog("正在解压…")
                    val dest = File(ctx.filesDir, "skills")
                    dest.mkdirs()
                    extractTarGz(tmp, dest)
                    onLog("✅ 已装到 ${dest.absolutePath}")
                }
                "mcp" -> {
                    // MCP 要装到 rootfs（node 在那里）+ 写 mcp.json
                    onLog("正在部署 server…")
                    val runtime = com.ccm.app.runtime.ProotRuntime(ctx)
                    val rootfs = runtime.rootfsDir()
                    val dest = File(rootfs, "root/.ccm/mcp/${item.id}")
                    dest.mkdirs()
                    extractTarGz(tmp, dest)

                    onLog("检查 Node 运行时…")
                    val installer = com.ccm.app.core.mcp.McpInstaller(ctx, runtime)
                    if (!installer.ensureNode { s -> onLog(s) }) {
                        onLog("❌ Node 安装失败")
                        return@withContext false
                    }

                    onLog("写入配置…")
                    val entry = File(dest, "server.mjs")
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
                    File(ctx.filesDir, "skills/${item.id}").deleteRecursively()
                    File(ctx.filesDir, "skills/${item.id}.md").delete()
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
            onError = { err = it },
        )
        if (!ok) {
            throw IllegalStateException("解压失败：$err")
        }
    }
}
