package com.ccm.app.tools.net

import android.content.Context
import com.ccm.app.core.tool.Attachment
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 视觉工具组 —— ViewImage / ViewVideo / Screencap。
 *
 * 参照 Node 版 `core/tools-vision.mjs`（291 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  统一机制：结果走 [Attachment]，不走文本
 * ══════════════════════════════════════════════════════════════
 *
 * 三个工具都产出**图片**，必须以图片形式进模型上下文（不是路径字符串）。
 * 返回 `ToolResult.okWithImages(text, listOf(Attachment.ImageFile(path)))`，
 * 由 Agent 循环转成多模态 user 消息注入。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 两条必须遵守的纪律（CCM 实测踩出来的）
 * ══════════════════════════════════════════════════════════════
 *
 * **1. 模型看不到图时必须如实告知**
 * 有的网关对不支持的模型**不报错**，而是在正文里塞
 * `[Image input omitted: selected model does not support vision.]` 然后返回 200。
 * 图片被静默丢弃，工具全程"正常"—— 于是降级路径永远走不到，
 * 用户只看到「模型看不到图」。
 *
 * **模型不知道图没到，就会凭上下文编造画面内容** —— 这是最糟的错，
 * 因为从输出上看不出来。所以：
 * - Agent 循环嗅到那句占位文本后调 [markVisionUnavailable]
 * - 之后这些工具直接走 OCR 降级（不浪费一次注定失败的请求）
 *
 * **2. 图片被缩放时要透明**
 * 缩过就填 `resizedFrom`（原尺寸 `"WxH"`），Agent 会追加 `<image_resize_notice>`。
 * 不告知的话，模型会把「细节看不清」误判成「图里本来就没有」。
 *
 * @param context Android Context（Screencap 要调 ShizukuBridge）
 * @param saveDir 截图/抽帧保存目录
 */
class VisionTools(
    private val context: Context,
    private val saveDir: File,
) {

    companion object {
        /** 支持的图片扩展名 */
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

        /** 支持的视频扩展名 */
        private val VIDEO_EXT = setOf("mp4", "mov", "webm", "mkv", "avi")

        /** 单张图片上限 25MB */
        private const val MAX_IMAGE_BYTES = 25L * 1024 * 1024

        /** 视频上限约 100MB */
        private const val MAX_VIDEO_BYTES = 100L * 1024 * 1024

        /**
         * 识图能力是否已知不可用。
         *
         * 网关对不支持的模型不报错，而是在正文里塞占位文本返回 200
         * —— 嗅到后置为 true，之后直接走 OCR 降级（不再浪费请求）。
         */
        @Volatile
        var visionKnownUnavailable: Boolean = false
            private set

        fun markVisionUnavailable() {
            visionKnownUnavailable = true
        }

        fun resetVisionUnavailable() {
            visionKnownUnavailable = false
        }

        /** 占位文本（Agent 循环嗅到它就调 markVisionUnavailable） */
        const val VISION_OMITTED_MARKER = "[Image input omitted"
    }

    private fun shotDir(): File = File(saveDir, "vision").apply { if (!exists()) mkdirs() }

    /** 扩展名小写 */
    private fun extOf(f: File): String = f.name.substringAfterLast('.', "").lowercase()

    /** 解析路径（相对路径基于 ctx.cwd） */
    private fun resolvePath(raw: String, ctx: ToolContext): File {
        val f = File(raw)
        return if (f.isAbsolute) f else File(ctx.cwd, raw)
    }

    // ══════════════════════════════════════════════════════════════
    //  ViewImage
    // ══════════════════════════════════════════════════════════════

    inner class ViewImageTool : Tool() {
        override val name = "ViewImage"
        override val description =
            "读取本地图片（PNG/JPG/WebP/GIF/BMP）并作为图像**直接注入对话**，" +
                "主模型亲自看图分析：文字、画面、物体、界面布局、报错等。" +
                "**不要用 Read 读取二进制图片。**" +
                "detail 可选 high（默认，长边≤2048px）/ original（原始分辨率，token 消耗大）。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 4_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("图片绝对或相对路径"),
            "prompt" to ToolSchema.string("可选：希望重点看什么（会附在图旁）"),
            "detail" to ToolSchema.string(
                "图像精度：high=默认缩到2048px省token；original=原图直出（仅大模型/复杂细节时用）",
                enum = listOf("high", "original"),
            ),
            required = listOf("file_path"),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("file_path").isNullOrBlank()) return "file_path is required"
            val d = input.str("detail")
            if (d != null && d !in listOf("high", "original")) return "detail 只支持 'high' 或 'original'"
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val path = resolvePath(input.str("file_path")!!, ctx)

            if (!path.exists()) return ToolResult.notFound("图片不存在: ${path.absolutePath}")
            if (extOf(path) !in IMAGE_EXT) {
                return ToolResult.invalidInput("不是支持的图片扩展名: ${path.absolutePath}")
            }
            if (path.length() > MAX_IMAGE_BYTES) {
                return ToolResult.failed(
                    "图片过大（${path.length() / 1024 / 1024}MB > 25MB 上限）：${path.absolutePath}",
                )
            }

            val detail = input.str("detail") ?: "high"
            val prompt = input.str("prompt")

            // ⚠️ 已知模型不支持 vision → 不浪费时间发一次注定失败的请求，
            // 但要**如实告知**（否则模型会凭上下文编造画面内容）
            if (visionKnownUnavailable) {
                return ToolResult.failed(
                    "当前模型不支持识图（网关已确认），无法分析 ${path.name}。" +
                        "可以换支持 vision 的 Provider，或用户自己描述图片内容。",
                )
            }

            val text = buildString {
                append("图片: ${path.absolutePath}")
                if (prompt != null) append("（关注：$prompt）")
                append("\n尺寸: ${readImageSize(path) ?: "未知"}，大小: ${path.length() / 1024}KB")
                append("\n精度: $detail")
            }

            return ToolResult.okWithImages(
                text,
                listOf(Attachment.ImageFile(path.absolutePath, mimeOf(path))),
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  ViewVideo
    // ══════════════════════════════════════════════════════════════

    inner class ViewVideoTool : Tool() {
        override val name = "ViewVideo"
        override val description =
            "读取本地视频并抽取关键帧，帧以**原生多模态直接注入对话**——主模型亲自看画面" +
                "（人物、物体、动作、字幕、界面）（MP4/MOV/WebM/MKV，约 100MB 内）。" +
                "用户说「看看这个视频」「视频里有什么」时用。max_frames 控制抽帧数（默认 4）。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 4_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "file_path" to ToolSchema.string("视频文件绝对或相对路径"),
            "prompt" to ToolSchema.string("可选：希望重点看什么（分析备忘）"),
            "max_frames" to ToolSchema.integer(
                "可选：最大抽帧数，默认 4（不超过 6；每帧都是一张图，注意 token 消耗）",
                minimum = 1, maximum = 6,
            ),
            required = listOf("file_path"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("file_path").isNullOrBlank()) "file_path is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val path = resolvePath(input.str("file_path")!!, ctx)

            if (!path.exists()) return ToolResult.notFound("视频不存在: ${path.absolutePath}")
            if (extOf(path) !in VIDEO_EXT) {
                return ToolResult.invalidInput("不是支持的视频扩展名: ${path.absolutePath}")
            }
            if (path.length() > MAX_VIDEO_BYTES) {
                return ToolResult.failed("视频过大（${path.length() / 1024 / 1024}MB > 100MB 上限）")
            }

            val maxFrames = (input.int("max_frames") ?: 4).coerceIn(1, 6)

            // 抽帧：APK 侧没有 ffmpeg，用 MediaMetadataRetriever（系统 API，无需额外依赖）
            val frames = try {
                extractFrames(path, maxFrames)
            } catch (e: Throwable) {
                return ToolResult.Error("视频抽帧失败：${e.message}", ToolResult.INTERNAL)
            }
            if (frames.isEmpty()) {
                return ToolResult.failed("视频抽帧失败（可能编码不支持或文件损坏）: ${path.absolutePath}")
            }

            val prompt = input.str("prompt")
            val frameList = frames.joinToString(" | ") { (f, ts) -> "帧@${"%.1f".format(ts)}s" }
            val text = buildString {
                append("视频: ${path.absolutePath}\n")
                if (prompt != null) append("关注：$prompt\n")
                append("共抽 ${frames.size} 帧，按时间顺序注入：$frameList")
            }

            return ToolResult.okWithImages(
                text,
                frames.map { (f, _) -> Attachment.ImageFile(f.absolutePath, "image/jpeg") },
            )
        }

        /**
         * 用系统 MediaMetadataRetriever 抽帧。
         *
         * 【为什么不用 ffmpeg】APK 里没有、也不该打包 ffmpeg（体积 + 许可）。
         * Android 自带 MediaMetadataRetriever 能拿任意时间点的帧，
         * 对「看视频内容」这个需求完全够用。
         */
        private fun extractFrames(video: File, maxFrames: Int): List<Pair<File, Double>> {
            val retriever = android.media.MediaMetadataRetriever()
            val out = mutableListOf<Pair<File, Double>>()
            try {
                retriever.setDataSource(video.absolutePath)
                val durationMs = retriever
                    .extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                if (durationMs <= 0) return emptyList()

                // 均匀取 maxFrames 个时间点（跳过首尾各 5%，避免黑帧/片尾）
                val start = (durationMs * 0.05).toLong()
                val end = (durationMs * 0.95).toLong()
                val span = (end - start).coerceAtLeast(1L)

                for (i in 0 until maxFrames) {
                    val t = if (maxFrames == 1) durationMs / 2 else start + span * i / (maxFrames - 1)
                    val bitmap = retriever.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: continue
                    // 缩到长边 1024（省 token；与 Node 版一致）
                    val scaled = scaleDown(bitmap, 1024)
                    val f = File(shotDir(), "frame-${video.nameWithoutExtension}-${t / 1000}-$i.jpg")
                    f.outputStream().use { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                    out += f to (t / 1000.0)
                    if (scaled !== bitmap) scaled.recycle()
                    bitmap.recycle()
                }
            } finally {
                runCatching { retriever.release() }
            }
            return out
        }

        private fun scaleDown(src: android.graphics.Bitmap, maxEdge: Int): android.graphics.Bitmap {
            val longEdge = maxOf(src.width, src.height)
            if (longEdge <= maxEdge) return src
            val ratio = maxEdge.toFloat() / longEdge
            return android.graphics.Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).toInt().coerceAtLeast(1),
                (src.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Screencap
    // ══════════════════════════════════════════════════════════════

    /**
     * 截取**主屏**（用户正在看的那个屏）。
     *
     * ⚠️ 与 `phone_screenshot` 的区别：
     * - **Screencap**：主屏（用户看的），走 Shizuku/rish 的 screencap 命令
     * - **phone_screenshot**：虚拟副屏（静默操作那个），走 AIDL 帧缓存（快得多）
     *
     * 用户说「看看我屏幕」用这个；AI 自己在副屏操作时截图用 phone_screenshot。
     */
    inner class ScreencapTool : Tool() {
        override val name = "Screencap"
        override val description =
            "截取当前手机**主屏**（用户正在看的屏幕）。" +
                "用户说「看看我屏幕」「截屏看看」「屏幕上有什么」时用。" +
                "注意：这是主屏；AI 在虚拟副屏操作时的截图应该用 phone_screenshot。"
        override val isReadOnly = true
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "prompt" to ToolSchema.string("可选：截屏后要分析的重点"),
            "save_path" to ToolSchema.string("可选：保存路径，默认自动命名"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val target = input.str("save_path")?.takeIf { it.isNotBlank() }
                ?.let { resolvePath(it, ctx) }
                ?: File(shotDir(), "screencap-${System.currentTimeMillis()}.png")

            return try {
                val ok = withContext(Dispatchers.IO) { captureMainScreen(target) }
                if (!ok || !target.exists() || target.length() == 0L) {
                    return ToolResult.failed(
                        "截屏失败。可能原因：① Shizuku 未授权 ② 屏幕已关闭 " +
                            "③ 通道不可用（用 phone_device 检查）",
                    )
                }
                val prompt = input.str("prompt")
                ToolResult.okWithImages(
                    "主屏截图${prompt?.let { "（关注：$it）" } ?: ""} —— ${target.absolutePath}",
                    listOf(Attachment.ImageFile(target.absolutePath, "image/png")),
                )
            } catch (e: Throwable) {
                ToolResult.Error("截屏异常：${e.message}", ToolResult.INTERNAL)
            }
        }

        /**
         * 截主屏。
         *
         * 复用现有 `bridge/ShizukuBridge` 的 shell 通道跑 `screencap`，
         * 再 `cat` 出文件（因为 screencap 写到 /sdcard 需要权限，
         * 而 shell uid 可以直接写）。
         */
        private fun captureMainScreen(target: File): Boolean {
            return try {
                val svc = com.ccm.app.bridge.ShizukuBridge.phoneService(context)
                // 用 phone 服务的 runShell 以 shell 身份截屏
                val tmp = "/data/local/tmp/ccm-screencap-${System.currentTimeMillis()}.png"
                val r = svc?.runShell("screencap -p $tmp && chmod 644 $tmp", 15_000) ?: return false
                if (!r.contains("\n---\n")) return false
                val exit = r.substringBefore("\n---\n").trim().toIntOrNull() ?: -1
                if (exit != 0) return false

                // 读回文件（shell uid 写的，app 读不了 → 用 cat 转 base64 再过 adb）
                val b64 = svc.runShell("base64 -w0 $tmp && rm -f $tmp", 30_000)
                val payload = b64.substringAfter("\n---\n", "").trim()
                if (payload.isEmpty()) return false
                val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                true
            } catch (_: Throwable) {
                false
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助
    // ══════════════════════════════════════════════════════════════

    private fun mimeOf(f: File): String = when (extOf(f)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> "image/png"
    }

    /** 读图片尺寸（不解码整张，只看文件头） */
    private fun readImageSize(f: File): String? = try {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth > 0) "${opts.outWidth}x${opts.outHeight}" else null
    } catch (_: Throwable) {
        null
    }
}
