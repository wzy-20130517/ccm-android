package com.ccm.app.bridge

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.KeyEvent
import android.view.PixelFormat
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream

/**
 * 跑在 Shizuku shell uid 下的 phone use 服务。
 *
 * 必须有带 Context 的构造函数，Shizuku 用反射调用它。
 * @Keep 防止混淆时被删掉。
 */
class PhoneUseService : IPhoneUseService.Stub {

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameJpeg: ByteArray = ByteArray(0)
    private var frameLock = Any()

    constructor()

    @Keep
    constructor(context: Context) {
        startDisplay(context)
    }

    override fun destroy() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        System.exit(0)
    }

    override fun displayId(): Int {
        return try { display?.display?.displayId ?: -1 } catch (_: Throwable) { -1 }
    }

    override fun dumpTree(interactiveOnly: Boolean, maxNodes: Int): String {
        // 元素树抓取下一步接 UiAutomation，先返回空，调用方回落到无障碍。
        return ""
    }

    override fun tap(x: Int, y: Int): Boolean {
        return input("tap", x.toString(), y.toString())
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        return input("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())
    }

    override fun key(keyCode: Int): Boolean {
        return input("keyevent", keyCode.toString())
    }

    override fun typeText(text: String): Boolean {
        // ACTION_SET_TEXT 下一步接，这里先用 input text 兜底。
        return input("text", text.replace(" ", "%s"))
    }

    override fun latestFrame(): ByteArray = synchronized(frameLock) { frameJpeg }

    private fun input(vararg args: String): Boolean {
        val id = displayId()
        if (id < 0) return false
        return try {
            val cmd = mutableListOf("/system/bin/input", "-d", id.toString())
            cmd.addAll(args)
            Runtime.getRuntime().exec(cmd.toTypedArray()).waitFor() == 0
        } catch (_: Throwable) { false }
    }

    private fun startDisplay(context: Context) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
        val h = metrics.heightPixels.takeIf { it > 0 } ?: 2400
        val dpi = metrics.densityDpi.takeIf { it > 0 } ?: 420

        val drain = HandlerThread("vd-drain").apply { start() }
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            try {
                val bmp = android.graphics.Bitmap.createBitmap(img.width, img.height, android.graphics.Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(img.planes[0].buffer)
                val out = ByteArrayOutputStream()
                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
                synchronized(frameLock) { frameJpeg = out.toByteArray() }
                bmp.recycle()
            } catch (_: Throwable) {
            } finally {
                img.close()
            }
        }, Handler(drain.looper))

        // 0x609 = PUBLIC | OWN_CONTENT_ONLY | SHOULD_SHOW_SYSTEM_DECORATIONS | TRUSTED
        display = dm.createVirtualDisplay(
            "CCMVirtualDisplay", w, h, dpi, reader?.surface,
            1545
        )
    }
}
