package com.ccm.app.core.image

import java.io.File
import java.util.Base64

/**
 * 图片处理 —— 读图 → 缩放 → 编码 base64。
 *
 * 对应 Node 版 `core/image.mjs`（388 行）。
 *
 * ## 为什么必须缩放（不是优化，是硬需求）
 *
 * 图片 token 消耗 ≈ (宽 × 高) / 750。一张 4000×3000 的手机原图：
 * - 不缩放：(4000×3000)/750 = **16000 token** —— 单张图吃掉半个上下文
 * - 缩到长边 1568：(1568×1176)/750 ≈ 2460 token —— 降 85%
 *
 * 更糟的是**解码像素数超限**：某些网关对超大图直接返回
 * `media_image_work_exceeded`（解码工作量超限），整个请求失败。
 *
 * ## 关键参数（对齐 Node 版，别乱改）
 *
 * | 参数 | 值 | 理由 |
 * |---|---|---|
 * | 长边上限 | **1568** | Node 版 `MAX_LONG_EDGE`；再大 token 收益递减 |
 * | 单张字节上限 | **4MB** | base64 后约 5.3MB，是多数网关的请求体上限 |
 * | 视频帧长边 | **1024** | 视频要抽多帧，单帧更小（防上下文爆炸） |
 *
 * ## 零 Android 依赖（架构约束）
 *
 * 本类**不引用 `android.graphics.Bitmap`** —— `core/` 层要能在 JVM 单测里跑。
 * 缩放能力由 [ImageScaler] 接口注入：
 * - 生产：`tools/AndroidImageScaler`（用 `BitmapFactory`）
 * - 单测：不注入即可（`imageScaler = null` → 图片原样注入，不影响断言）
 */
object ImageProcessor {

    /** 长边上限（像素）。超过就等比缩小。 */
    const val MAX_LONG_EDGE = 1568

    /** 单张图片字节上限（4MB）。 */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** 视频帧长边上限（比图片小 —— 一次要注入多帧）。 */
    const val VIDEO_FRAME_LONG_EDGE = 1024

    /** 支持的图片扩展名。 */
    val ALLOWED_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

    /** 扩展名 → MIME。 */
    private val MIME_BY_EXT = mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
    )

    /** 判断路径是否像图片。 */
    fun isImagePath(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in ALLOWED_EXTENSIONS
    }

    /** 取 MIME（未知返回 `application/octet-stream`）。 */
    fun mimeOf(path: String): String =
        MIME_BY_EXT[path.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    /**
     * 读图并编码成可注入的多模态附件。
     *
     * @param file 图片文件
     * @param scaler 缩放器（生产用 Android 的，测试用 JVM 的）
     * @param maxLongEdge 长边上限
     * @param maxBytes 字节上限
     * @return 附件；失败抛 [ImageException]（带可读原因）
     */
    fun load(
        file: File,
        scaler: ImageScaler,
        maxLongEdge: Int = MAX_LONG_EDGE,
        maxBytes: Int = MAX_BYTES,
    ): ImageAttachment {
        if (!file.exists()) throw ImageException("图片不存在: ${file.absolutePath}")
        if (!file.isFile) throw ImageException("不是文件: ${file.absolutePath}")

        val ext = file.extension.lowercase()
        if (ext !in ALLOWED_EXTENSIONS) {
            throw ImageException("不支持的格式 .$ext（支持 ${ALLOWED_EXTENSIONS.joinToString("/")}）")
        }

        // 记录缩放前尺寸（探测不到就不标 —— 别编造）
        val before = scaler.size(file)
        val willResize = before != null && maxOf(before.first, before.second) > maxLongEdge

        // 长边超限 → 等比缩小（防 media_image_work_exceeded）
        val useFile = if (willResize) {
            scaler.scale(file, maxLongEdge) ?: file
        } else {
            file
        }

        if (useFile.length() > maxBytes) {
            throw ImageException(
                "图片过大 ${useFile.length() / 1024}KB，上限 ${maxBytes / 1024}KB，请先压缩"
            )
        }

        val bytes = useFile.readBytes()
        val b64 = Base64.getEncoder().encodeToString(bytes)

        // 缩放透明化：记录 原尺寸 → 新尺寸
        val resizedFrom = if (willResize && before != null) "${before.first}x${before.second}" else null
        val resizedTo = if (willResize) scaler.size(useFile)?.let { "${it.first}x${it.second}" } else null

        return ImageAttachment(
            base64 = b64,
            mimeType = mimeOf(useFile.name),
            resizedFrom = resizedFrom,
            resizedTo = resizedTo,
            byteSize = bytes.size,
        )
    }

    /** 便捷：从路径读（失败返回 null 而不是抛）。 */
    fun loadOrNull(
        path: String,
        scaler: ImageScaler,
        maxLongEdge: Int = MAX_LONG_EDGE,
    ): ImageAttachment? = try {
        load(File(path), scaler, maxLongEdge)
    } catch (_: Throwable) {
        null
    }

    /**
     * 生成缩放提示（追加到多模态消息里）。
     *
     * **必须发** —— 见类注释「缩放透明化」。
     */
    fun resizeNotice(attachments: List<ImageAttachment>): String? {
        val notes = attachments.mapNotNull { a ->
            a.resizedFrom?.let { from ->
                if (a.resizedTo != null) {
                    "图片已从 $from 缩放到 ${a.resizedTo}（节省 token；微小文字/细节可能受影响，" +
                        "必要时可用 detail:original 重看）"
                } else {
                    "原图 $from 已等比缩小"
                }
            }
        }
        if (notes.isEmpty()) return null
        return "<image_resize_notice>\n${notes.joinToString("\n")}\n</image_resize_notice>"
    }

    /**
     * 生成「模型看不到图」提示。
     *
     * ⚠️ **端点不支持图片时必须发这个**。否则工具文本还写着「手机屏幕截图（关注：xxx）」，
     * 模型以为图到了，会**凭上下文编造画面内容** —— 比直接报错更糟，因为看不出来。
     */
    fun visionUnsupportedNotice(): String =
        "<vision_unsupported>\n当前模型不支持读图，本次图片已被丢弃，你看不到画面内容。\n" +
            "不要再猜测或编造图里的内容；需要看图请换一个支持视觉的模型（切 Provider），" +
            "或让用户直接用文字描述关键信息。\n</vision_unsupported>"
}

/** 读好的图片附件。 */
data class ImageAttachment(
    /** 裸 base64（**不含** data URL 前缀，前缀在构造请求时拼）。 */
    val base64: String,
    val mimeType: String,
    /** 缩放前尺寸 `"WxH"`；null = 未缩放。 */
    val resizedFrom: String? = null,
    /** 缩放后尺寸 `"WxH"`；null = 未缩放或测不到。 */
    val resizedTo: String? = null,
    /** 字节数。 */
    val byteSize: Int = 0,
) {
    /** 拼成 data URL（部分协议需要）。 */
    fun dataUrl(): String = "data:$mimeType;base64,$base64"
}

/** 图片处理失败（消息面向用户，可直接显示）。 */
class ImageException(message: String) : Exception(message)

/**
 * 缩放能力接口 —— 把「怎么缩放」从 core 层剥离。
 *
 * ## 为什么是接口
 * 缩放要用平台 API：
 * - **Android**：`BitmapFactory` + `Bitmap.createScaledBitmap`（在 `tools/` 侧实现）
 * - **Android**：`BitmapFactory` + `Bitmap.createScaledBitmap`（在 `tools/` 侧实现）
 *
 * ⚠️ **不要用 `javax.imageio` / `java.awt`** —— Android SDK 不含 `java.desktop` 模块，
 * 这两个包在设备上根本不存在（编译期可能过，运行时抛 `NoClassDefFoundError`）。
 * 我一开始写了个 `JvmImageScaler` 想「单测用」，CI 直接编译失败才发现这点。
 *
 * `core/` 层零 Android 依赖是硬约束，所以定义接口、实现注入。
 */
interface ImageScaler {

    /**
     * 探测图片尺寸。
     *
     * @return `(宽, 高)`；探测不到返回 null（**不要编造** —— 宁可不标缩放也不给错数据）
     */
    fun size(file: File): Pair<Int, Int>?

    /**
     * 等比缩放到长边不超过 [maxLongEdge]。
     *
     * @return 缩放后的文件；失败返回 null（调用方会退回用原图）
     */
    fun scale(file: File, maxLongEdge: Int): File?
}
