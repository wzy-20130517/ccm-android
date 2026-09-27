package com.ccm.app.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.ccm.app.core.image.ImageScaler
import java.io.File

/**
 * Android 图片缩放器 —— [ImageScaler] 的生产实现。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要它（不缩放会怎样）
 * ══════════════════════════════════════════════════════════════
 *
 * 手机拍照是 4000×3000。原样注入多模态 = 一张图几百万 token，
 * **一轮对话就能把上下文撑爆**。缩到长边 1568/2048 能降一个数量级。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 两个必须处理的坑（不做的话图会坏 / 会 OOM）
 * ══════════════════════════════════════════════════════════════
 *
 * **坑 1：直接 decode 大图会 OOM**
 * `BitmapFactory.decodeFile()` 默认按原尺寸解码 —— 4000×3000 的 ARGB_8888
 * 就是 48MB，几张图连着来必然 `OutOfMemoryError`。
 *
 * 正确做法是**两趟解码**：
 * ```
 * ① inJustDecodeBounds = true  → 只读尺寸（不分配像素内存，几乎零成本）
 * ② 算出 inSampleSize（2 的幂） → 按采样率解码（内存降到 1/N²）
 * ③ 若还需更精确，再 createScaledBitmap 微调
 * ```
 * 为什么不只用 `createScaledBitmap`：那要求先把原图完整解码进内存，
 * 等于没省 —— 必须先靠 `inSampleSize` 把解码尺寸降下来。
 *
 * **坑 2：EXIF 旋转（手机竖拍照片会「躺着」）**
 * 手机竖拍时，传感器输出的像素其实是**横的**，靠 EXIF 的 `ORIENTATION`
 * 标记告诉看图软件「请转 90°」。`BitmapFactory` **不会**自动应用这个标记 ——
 * 不处理的话，模型看到的是**躺着的图**（人脸朝左，文字竖排）。
 * 这个错误很隐蔽：图能显示、只是方向不对，而模型不会说「图是歪的」，
 * 它会直接按看到的歪图分析（把横躺的人说成「侧卧」）。
 *
 * 所以：解码后必须读 EXIF 并按需 `Matrix` 旋转。
 *
 * 【为什么用 `android.media.ExifInterface` 而不是 `androidx.exifinterface`】
 * API 24+ 内置了 `android.media.ExifInterface`（我们 minSdk=26），
 * 不需要额外依赖 —— 少一个库就少一份体积和版本冲突。
 *
 * @param cacheDir 缩放结果的存放目录（App cacheDir，系统可回收）
 */
class AndroidImageScaler(private val cacheDir: File) : ImageScaler {

    companion object {
        /** JPEG 质量：88 是「肉眼无差、体积可控」的平衡点（实测） */
        private const val JPEG_QUALITY = 88

        /**
         * 缩放后长边的硬上限。
         *
         * 即使调用方传更大的值也夹到这个数 —— 防止「传 8000 就等于不缩」。
         * 2048 是 Anthropic/OpenAI 视觉模型的长边上限附近，再大纯属浪费 token。
         */
        private const val MAX_LONG_EDGE_HARD_CAP = 2048
    }

    // ══════════════════════════════════════════════════════════════
    //  size
    // ══════════════════════════════════════════════════════════════

    /**
     * 探测图片尺寸（不分配像素内存）。
     *
     * ⚠️ 返回的是**EXIF 校正后**的尺寸 —— 竖拍照片的像素是横的，
     * 但用户和模型认知里的「宽高」应该是转正之后的。
     * 不校正的话，`resizedFrom` 里写的尺寸会和实际看到的图对不上。
     */
    override fun size(file: File): Pair<Int, Int>? = try {
        if (!file.exists() || file.length() == 0L) {
            null
        } else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)

            if (opts.outWidth <= 0 || opts.outHeight <= 0) {
                null   // 解不出来就如实返回 null，**不要编造**
            } else {
                // EXIF 说转了 90/270 度 → 宽高互换
                if (isRotated(file)) opts.outHeight to opts.outWidth
                else opts.outWidth to opts.outHeight
            }
        }
    } catch (_: Throwable) {
        null
    }

    // ══════════════════════════════════════════════════════════════
    //  scale
    // ══════════════════════════════════════════════════════════════

    /**
     * 等比缩放到长边不超过 [maxLongEdge]。
     *
     * @return 缩放后的文件；**失败或无需缩放时返回 null**
     *   （接口约定：null = 调用方退回用原图）
     *
     * 注意：若原图已经小于 [maxLongEdge]，返回 **null** 而不是原文件 ——
     * 语义是「不需要缩放」，调用方据此跳过替换。这与 Node 版一致。
     */
    override fun scale(file: File, maxLongEdge: Int): File? {
        val target = maxLongEdge.coerceIn(64, MAX_LONG_EDGE_HARD_CAP)

        return try {
            if (!file.exists() || file.length() == 0L) return null

            // ① 只读尺寸（不分配像素内存）
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            // EXIF 校正后的真实长边
            val rotated = isRotated(file)
            val realLongEdge = if (rotated) maxOf(srcW, srcH) else maxOf(srcW, srcH)

            // 已经够小 → 不需要缩放
            if (realLongEdge <= target) return null

            // ② 算 inSampleSize（2 的幂，向下取整到「不小于 target」的那一档）
            //    先粗缩到 target 的 1~2 倍，再用 createScaledBitmap 精缩到目标
            //    —— 直接 inSampleSize 到目标会让尺寸不精确（它只能是 2 的幂）
            var sample = 1
            while (realLongEdge / (sample * 2) >= target) {
                sample *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = try {
                BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
            } catch (_: Throwable) {
                null
            } ?: return null

            // ③ EXIF 旋转（先转正，再精缩 —— 否则旋转后的宽高比会算错）
            val upright = applyExifRotation(decoded, file)
            if (upright !== decoded) decoded.recycle()

            // ④ 精缩到精确目标尺寸
            val longEdge = maxOf(upright.width, upright.height)
            val finalBitmap = if (longEdge > target) {
                val ratio = target.toDouble() / longEdge
                val w = (upright.width * ratio).toInt().coerceAtLeast(1)
                val h = (upright.height * ratio).toInt().coerceAtLeast(1)
                try {
                    Bitmap.createScaledBitmap(upright, w, h, true).also {
                        if (it !== upright) upright.recycle()
                    }
                } catch (_: Throwable) {
                    upright
                }
            } else {
                upright
            }

            // ⑤ 写盘（统一转 jpg —— 体积小、兼容性好）
            cacheDir.mkdirs()
            val baseName = file.nameWithoutExtension.take(40).ifEmpty { "img" }
            val out = File(cacheDir, "scaled-${System.currentTimeMillis()}-$baseName.jpg")

            val ok = try {
                out.outputStream().use { stream ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                }
                out.exists() && out.length() > 0
            } catch (_: Throwable) {
                false
            } finally {
                finalBitmap.recycle()
            }

            if (ok) out else null
        } catch (_: OutOfMemoryError) {
            // OOM 不是异常能兜住的（它是 Error）—— 显式捕获，退回用原图
            null
        } catch (_: Throwable) {
            null
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  EXIF
    // ══════════════════════════════════════════════════════════════

    /**
     * 读 EXIF 判断是否需要旋转（90° 或 270°）。
     *
     * 只有这两种会让宽高互换；180° 不需要交换宽高。
     */
    private fun isRotated(file: File): Boolean = try {
        val orientation = ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
            orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
            orientation == ExifInterface.ORIENTATION_TRANSVERSE
    } catch (_: Throwable) {
        false
    }

    /**
     * 按 EXIF 应用旋转/镜像。
     *
     * @return 处理后的 bitmap；无需处理时返回**原对象**（调用方据此判断是否 recycle）
     */
    private fun applyExifRotation(src: Bitmap, file: File): Bitmap {
        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Throwable) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return src   // NORMAL / UNDEFINED → 不动
        }

        return try {
            val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
            if (rotated !== src) rotated else src
        } catch (_: Throwable) {
            src
        }
    }
}
