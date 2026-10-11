package com.ccm.app.tools.net

import com.ccm.app.core.tool.Attachment
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.core.tool.ToolSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * 图片工具集 —— FindImage（Pexels 找图）/ ImageGen（OpenAI images 生图）。
 *
 * 参照 Node 版 `core/tools-image-search.mjs` + `core/tools-imagegen.mjs`。
 *
 * ══════════════════════════════════════════════════════════════
 *  两个工具都会返回 [Attachment]（图片进多模态）
 * ══════════════════════════════════════════════════════════════
 *
 * 它们的结果必须让模型**看到画面**，而不是只拿到一个路径字符串。
 * 所以返回 `ToolResult.okWithImages(text, listOf(Attachment.ImageFile(path)))`，
 * 由 Agent 循环转成多模态 user 消息注入。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么 FindImage 不调外部脚本
 * ══════════════════════════════════════════════════════════════
 *
 * Node 版注释里写过：曾考虑复用某个 skill 的 py 脚本，**否决** ——
 * 那是跨项目依赖，脚本的输出目录/命名/错误处理都不受控，一变这边就坏。
 * Pexels API 本身只有两个 HTTP 请求（search + download），直接写更干净。
 *
 * @param settings 配置（Pexels key / ImageGen 三件套）
 * @param saveDir 图片保存目录
 */
class ImageTools(
    private val settings: ToolSettings?,
    private val saveDir: File,
) {

    companion object {
        private const val PEXELS_SEARCH = "https://api.pexels.com/v1/search"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) CCM/1.0"

        /** 单张图片下载上限 20MB */
        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024

        /** ImageGen 超时 300s（生图慢，与工具超时分级一致） */
        private const val IMAGEGEN_TIMEOUT_MS = 300_000
    }

    private fun pexelsKey(): String? =
        settings?.pexelsApiKey?.takeIf { it.isNotBlank() }
            ?: settings?.get("pexelsKey")?.takeIf { it.isNotBlank() }

    private fun imageDir(): File = File(saveDir, "images").apply { if (!exists()) mkdirs() }

    // ══════════════════════════════════════════════════════════════
    //  FindImage（Pexels）
    // ══════════════════════════════════════════════════════════════

    inner class FindImageTool : Tool() {
        override val name = "FindImage"
        override val description =
            "以文找图：按关键词从 Pexels 图库找相关图片并下载到本地。返回本地路径列表。" +
                "用户说「找几张…的图」「给我来张…壁纸」「做视频缺…素材」时用。" +
                "中英文关键词都行，英文结果更多。图片可免费商用，无需署名。"
        override val isReadOnly = true
        override val maxResultSizeChars = 1_500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "query" to ToolSchema.string("搜索关键词，如「星空」「sunset city」"),
            "count" to ToolSchema.integer("要几张，默认 3，最多 10", minimum = 1, maximum = 10),
            required = listOf("query"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("query").isNullOrBlank()) "query is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val key = pexelsKey()
                ?: return ToolResult.Error(
                    "Pexels API key 未配置。用 /pexels set <key> 配置（免费：pexels.com/api）。",
                    ToolResult.PERMISSION_DENIED,
                )
            val query = input.str("query")!!.trim()
            val count = (input.int("count") ?: 3).coerceIn(1, 10)

            val json = try {
                withContext(Dispatchers.IO) {
                    httpGetText(
                        "$PEXELS_SEARCH?query=${enc(query)}&per_page=$count&orientation=landscape",
                        mapOf("Authorization" to key),
                    )
                }
            } catch (e: Throwable) {
                return ToolResult.Error("Pexels 请求失败：${e.message}", ToolResult.NETWORK)
            }

            val photos = try {
                JSONObject(json).optJSONArray("photos") ?: org.json.JSONArray()
            } catch (e: Throwable) {
                return ToolResult.Error("Pexels 返回非法 JSON", ToolResult.NETWORK)
            }
            if (photos.length() == 0) return ToolResult.ok("(没有找到相关图片：$query)")

            val attachments = mutableListOf<Attachment>()
            val lines = mutableListOf<String>()

            for (i in 0 until photos.length()) {
                ctx.checkCancelled()
                val p = photos.optJSONObject(i) ?: continue
                val src = p.optJSONObject("src") ?: continue
                // large 足够看清且体积小；original 原图太大，手机下载慢
                val url = src.optString("large").ifEmpty { src.optString("medium") }
                if (url.isEmpty()) continue

                val id = p.optLong("id", 0L)
                val target = File(imageDir(), "pexels-$id.jpg")
                try {
                    withContext(Dispatchers.IO) { download(url, target) }
                    attachments += Attachment.ImageFile(target.absolutePath, "image/jpeg")
                    lines += "- ${target.absolutePath}" +
                        "  (摄影师: ${p.optString("photographer", "?")})" +
                        "  ${p.optString("alt", "")}".take(120)
                } catch (e: Throwable) {
                    lines += "- 下载失败: ${e.message}"
                }
            }

            if (attachments.isEmpty()) {
                return ToolResult.Error("图片下载全部失败", ToolResult.NETWORK)
            }
            return ToolResult.okWithImages(
                "找到 ${attachments.size} 张「$query」的图片：\n${lines.joinToString("\n")}",
                attachments,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  ImageGen（OpenAI images 兼容）
    // ══════════════════════════════════════════════════════════════

    inner class ImageGenTool : Tool() {
        override val name = "ImageGen"
        override val description =
            "生图工具。两种模式：①文生图——只给 prompt，走 /images/generations；" +
                "②图生图——给 image（本地图片路径）+ prompt，走 /images/edits，按描述改写参考图。" +
                "用户说「画/生成一张图」用①，说「改这张图/参考这张图/换成…」用②。"
        override val isReadOnly = false
        override val maxResultSizeChars = 1_500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "prompt" to ToolSchema.string("图片描述（文生图）或修改要求（图生图），建议英文或中英混合"),
            "size" to ToolSchema.string("尺寸，如 1024x1024、1536x1024、1024x1536、auto"),
            "filename" to ToolSchema.string("保存文件名（不含目录），省略则按时间戳命名"),
            "image" to ToolSchema.string("图生图的参考图本地路径（给了它就走 /images/edits）"),
            "n" to ToolSchema.integer("生成几张（1-10），默认 1", minimum = 1, maximum = 10),
            required = listOf("prompt"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("prompt").isNullOrBlank()) "prompt is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val baseUrl = settings?.imageGenBaseUrl?.takeIf { it.isNotBlank() }
                ?: return ToolResult.Error(
                    // /imagegen 是 CLI 命令，APK 没有（handler 里无此分支）——
                    // 别引导幽灵命令，指向真实可操作的路径。
                    "生图未配置。APK 没有 /imagegen 命令 —— 用 Edit 工具改应用私有目录 " +
                        "config.json 的 imageGen 字段（url/key/model，OpenAI images 兼容端点）。",
                    ToolResult.PERMISSION_DENIED,
                )
            val apiKey = settings.imageGenApiKey.orEmpty()
            val model = settings.imageGenModel?.takeIf { it.isNotBlank() } ?: "gpt-image-1"

            val prompt = input.str("prompt")!!
            val refImage = input.str("image")
            val n = (input.int("n") ?: 1).coerceIn(1, 10)
            val size = input.str("size") ?: "1024x1024"

            // 端点规范化（对齐 Node 版：base 自动拼，完整端点直接用）
            val endpoint = if (baseUrl.endsWith("/images/generations") || baseUrl.endsWith("/images/edits")) {
                baseUrl
            } else {
                val base = baseUrl.trimEnd('/')
                if (refImage != null) "$base/images/edits" else "$base/images/generations"
            }

            val body = JSONObject().apply {
                put("model", model)
                put("prompt", prompt)
                put("n", n)
                if (size != "auto") put("size", size)
                put("response_format", "b64_json")
            }

            val raw = try {
                withContext(Dispatchers.IO) {
                    httpPostJson(endpoint, body.toString(), apiKey)
                }
            } catch (e: Throwable) {
                return ToolResult.Error("生图请求失败：${e.message}", ToolResult.NETWORK)
            }

            val data = try {
                JSONObject(raw).optJSONArray("data")
            } catch (e: Throwable) {
                null
            } ?: return ToolResult.Error("生图返回非法 JSON：${raw.take(200)}", ToolResult.NETWORK)

            val attachments = mutableListOf<Attachment>()
            val lines = mutableListOf<String>()

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val b64 = item.optString("b64_json", "")
                val url = item.optString("url", "")

                val stamp = System.currentTimeMillis()
                val name = input.str("filename")?.takeIf { it.isNotBlank() }
                    ?: "img-$stamp-$i.png"
                val target = File(imageDir(), name)

                try {
                    if (b64.isNotEmpty()) {
                        val bytes = Base64.getDecoder().decode(b64)
                        withContext(Dispatchers.IO) { target.writeBytes(bytes) }
                    } else if (url.isNotEmpty()) {
                        withContext(Dispatchers.IO) { download(url, target) }
                    } else {
                        lines += "- 第 $i 张：返回里既无 b64_json 也无 url"
                        continue
                    }
                    attachments += Attachment.ImageFile(target.absolutePath)
                    lines += "- ${target.absolutePath}"
                } catch (e: Throwable) {
                    lines += "- 第 $i 张保存失败：${e.message}"
                }
            }

            if (attachments.isEmpty()) {
                return ToolResult.Error("生图成功但保存失败：\n${lines.joinToString("\n")}", ToolResult.INTERNAL)
            }
            return ToolResult.okWithImages(
                "已生成 ${attachments.size} 张图片：\n${lines.joinToString("\n")}",
                attachments,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HTTP 底层
    // ══════════════════════════════════════════════════════════════

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private fun httpGetText(url: String, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(200)}")
            return text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun httpPostJson(url: String, body: String, apiKey: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = IMAGEGEN_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
            if (apiKey.isNotEmpty()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(300)}")
            return text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun download(url: String, target: File) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw java.io.IOException("HTTP ${conn.responseCode}")
            }
            target.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_IMAGE_BYTES) {
                            throw java.io.IOException("图片超过 20MB 上限")
                        }
                        output.write(buf, 0, n)
                    }
                }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
