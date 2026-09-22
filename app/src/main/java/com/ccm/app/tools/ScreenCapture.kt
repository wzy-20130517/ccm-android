package com.ccm.app.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 截图器 —— 基于 MediaProjection 的真实截屏。
 *
 * 【为什么不用无障碍的 GLOBAL_ACTION_TAKE_SCREENSHOT】
 * 那个只能"触发系统截图"，图会存到相册，App 拿不到 Bitmap。
 * 要拿到图，必须用 MediaProjection（系统录屏 API）。
 *
 * 【授权流程】
 * 1. Activity 调 startActivityForResult(mediaProjectionManager.createScreenCaptureIntent())
 * 2. 用户点"开始录制"
 * 3. onActivityResult 拿到 resultCode + data
 * 4. 传给本类 init()
 * 5. 之后可以反复截图（授权持续到进程结束或用户撤销）
 *
 * 【实现】
 * MediaProjection → VirtualDisplay → ImageReader → Image → Bitmap
 * 这是官方标准做法，比 `screencap` 命令快且不依赖 Shizuku。
 */
object ScreenCapture {

    private const val TAG = "ScreenCapture"

    /** 请求码，Activity 用它启动授权 */
    const val REQUEST_CODE = 1001

    @Volatile
    private var projection: MediaProjection? = null

    @Volatile
    private var virtualDisplay: VirtualDisplay? = null

    @Volatile
    private var imageReader: ImageReader? = null

    @Volatile
    private var width = 0
    @Volatile
    private var height = 0
    @Volatile
    private var density = 0

    /** 是否已授权 */
    fun isReady(): Boolean = projection != null

    /**
     * 初始化（在 Activity.onActivityResult 里调用）。
     *
     * @param resultCode Activity.RESULT_OK
     * @param data 返回的 Intent
     */
    fun init(context: Context, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "授权被拒绝")
            return false
        }
        return try {
            release()

            val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
            val p = mgr.getMediaProjection(resultCode, data) ?: return false

            // 注册回调：用户撤销时自动释放
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "用户撤销了截屏授权")
                    release()
                }
            }, Handler(Looper.getMainLooper()))

            val dm = context.resources.displayMetrics
            width = dm.widthPixels
            height = dm.heightPixels
            density = dm.densityDpi

            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            val vd = p.createVirtualDisplay(
                "ccm-capture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, null
            )

            projection = p
            virtualDisplay = vd
            imageReader = reader
            Log.i(TAG, "截屏已就绪 ${width}x${height}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "初始化失败", t)
            false
        }
    }

    /** 释放资源 */
    fun release() {
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    /**
     * 截取一帧。
     *
     * @param savePath 保存路径（可选，null 则只返回 base64）
     * @param quality JPEG 质量 0-100
     * @return 成功时返回文件路径；失败返回 null
     */
    fun capture(savePath: String? = null, quality: Int = 85): String? {
        val reader = imageReader ?: return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null

        // 消费上一帧（VirtualDisplay 会持续产生帧，ImageReader 队列会满）
        try {
            reader.acquireLatestImage()?.close()
        } catch (_: Throwable) {}

        // 等新的一帧
        val handler = Handler(Looper.getMainLooper())
        val listener = ImageReader.OnImageAvailableListener { r ->
            try {
                val img = r.acquireLatestImage()
                if (img != null) {
                    bitmap = imageToBitmap(img)
                    img.close()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "取帧失败: ${t.message}")
            } finally {
                latch.countDown()
            }
        }
        reader.setOnImageAvailableListener(listener, handler)

        if (!latch.await(3000, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "取帧超时")
            reader.setOnImageAvailableListener(null, null)
            return null
        }
        reader.setOnImageAvailableListener(null, null)

        val bmp = bitmap ?: return null
        return try {
            val path = savePath ?: defaultPath()
            File(path).parentFile?.mkdirs()
            FileOutputStream(path).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            Log.i(TAG, "截图已保存: $path")
            path
        } catch (t: Throwable) {
            Log.e(TAG, "保存失败", t)
            null
        } finally {
            bmp.recycle()
        }
    }

    /** 截图并转 base64（给 AI 看图用） */
    fun captureBase64(quality: Int = 80): String? {
        val reader = imageReader ?: return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null

        try { reader.acquireLatestImage()?.close() } catch (_: Throwable) {}

        reader.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireLatestImage()
                if (img != null) {
                    bitmap = imageToBitmap(img)
                    img.close()
                }
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }, Handler(Looper.getMainLooper()))

        if (!latch.await(3000, TimeUnit.MILLISECONDS)) return null

        val bmp = bitmap ?: return null
        return try {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } finally {
            bmp.recycle()
        }
    }

    // ── 内部 ──────────────────────────────────

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bmp = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bmp.copyPixelsFromBuffer(buffer)

        // 去掉 padding
        return if (rowPadding == 0) bmp else {
            val cropped = Bitmap.createBitmap(bmp, 0, 0, image.width, image.height)
            bmp.recycle()
            cropped
        }
    }

    private fun defaultPath(): String {
        val dir = File(android.os.Environment.getExternalStorageDirectory(), "CCM/screenshots")
        return File(dir, "shot-${System.currentTimeMillis()}.jpg").absolutePath
    }
}
