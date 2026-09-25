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
        return try { collectTree(interactiveOnly, maxNodes) } catch (_: Throwable) { "" }
    }

    private val refCenters = HashMap<String, Pair<Int, Int>>()

    override fun tap(x: Int, y: Int): Boolean {
        return input("tap", x.toString(), y.toString())
    }

    override fun tapRef(ref: String): Boolean {
        val c = refCenters[ref] ?: return false
        return tap(c.first, c.second)
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        return input("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())
    }

    override fun swipeDir(direction: String, durationMs: Int): Boolean {
        val size = android.graphics.Point()
        display?.display?.getRealSize(size) ?: return false
        val cx = size.x / 2
        val cy = size.y / 2
        val d = size.y / 3
        val c = when (direction.lowercase()) {
            "up" -> intArrayOf(cx, cy + d / 2, cx, cy - d / 2)
            "down" -> intArrayOf(cx, cy - d / 2, cx, cy + d / 2)
            "left" -> intArrayOf(cx + d / 2, cy, cx - d / 2, cy)
            "right" -> intArrayOf(cx - d / 2, cy, cx + d / 2, cy)
            else -> return false
        }
        return swipe(c[0], c[1], c[2], c[3], durationMs)
    }

    override fun key(keyCode: Int): Boolean {
        return input("keyevent", keyCode.toString())
    }

    override fun typeText(text: String): Boolean {
        val pair = ui() ?: return input("text", text.replace(" ", "%s"))
        val node = focusedEditable(pair.first, pair.second) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return try { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
        catch (_: Throwable) { false }
    }

    private fun focusedEditable(uiClass: Class<*>, ui: Any): android.view.accessibility.AccessibilityNodeInfo? {
        val id = displayId()
        if (id < 0) return null
        val ws = windowsOnDisplay(uiClass, ui, id) ?: return null
        for (w in ws) {
            val root = w?.javaClass?.getMethod("getRoot")?.invoke(w) as? android.view.accessibility.AccessibilityNodeInfo ?: continue
            findEditable(root)?.let { return it }
        }
        return null
    }

    private fun findEditable(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        if (node.isEditable && node.isFocused) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findEditable(c)?.let { return it }
        }
        return null
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

    private var uiAutomation: Any? = null
    private var uiClass: Class<*>? = null
    private var uiThread: android.os.HandlerThread? = null

    private fun ui(): Pair<Class<*>, Any>? {
        uiAutomation?.let { return uiClass!! to it }
        return try {
            val ht = android.os.HandlerThread("vd-ui").apply { start() }
            val uac = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
            val cls = Class.forName("android.app.UiAutomation")
            val iuac = Class.forName("android.app.IUiAutomationConnection")
            val inst = cls.getConstructor(android.os.Looper::class.java, iuac).newInstance(ht.looper, uac)
            try { cls.getMethod("connect", Int::class.javaPrimitiveType).invoke(inst, 0) }
            catch (_: NoSuchMethodException) { cls.getMethod("connect").invoke(inst) }
            val info = android.accessibilityservice.AccessibilityServiceInfo()
            info.eventTypes = -1
            info.feedbackType = 16
            info.flags = 0x2 or 0x10 or 0x40
            cls.getMethod("setServiceInfo", info::class.java).invoke(inst, info)
            uiThread = ht
            uiClass = cls
            uiAutomation = inst
            cls to inst
        } catch (_: Throwable) { null }
    }

    private fun collectTree(interactiveOnly: Boolean, maxNodes: Int): String {
        val id = displayId()
        if (id < 0) return ""
        val pair = ui() ?: return ""
        var windows: List<*>? = null
        repeat(3) {
            windows = windowsOnDisplay(pair.first, pair.second, id)
            if (!windows.isNullOrEmpty()) return@repeat
            Thread.sleep(350)
        }
        val ws = windows ?: return ""
        if (ws.isEmpty()) return ""
        val arr = org.json.JSONArray()
        var counter = 0
        for (w in ws) {
            val root = w?.javaClass?.getMethod("getRoot")?.invoke(w) as? android.view.accessibility.AccessibilityNodeInfo ?: continue
            counter = walk(root, arr, counter, 0, interactiveOnly, maxNodes)
            if (counter >= maxNodes) break
        }
        refCenters.clear()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            refCenters[o.getString("ref")] = o.getInt("cx") to o.getInt("cy")
        }
        return org.json.JSONObject().apply {
            put("ok", true)
            put("package", "")
            put("count", arr.length())
            put("nodes", arr)
        }.toString()
    }

    private fun windowsOnDisplay(uiClass: Class<*>, ui: Any, displayId: Int): List<*>? {
        return try {
            val map = uiClass.getMethod("getWindowsOnAllDisplays").invoke(ui) ?: return null
            val size = map.javaClass.getMethod("size").invoke(map) as Int
            val keyAt = map.javaClass.getMethod("keyAt", Int::class.javaPrimitiveType)
            val valueAt = map.javaClass.getMethod("valueAt", Int::class.javaPrimitiveType)
            for (i in 0 until size) {
                if (keyAt.invoke(map, i) as Int != displayId) continue
                val list = valueAt.invoke(map, i) ?: return null
                val n = list.javaClass.getMethod("size").invoke(list) as Int
                val get = list.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                return (0 until n).map { get.invoke(list, it) }
            }
            null
        } catch (_: Throwable) { null }
    }

    private fun walk(
        node: android.view.accessibility.AccessibilityNodeInfo,
        arr: org.json.JSONArray, count: Int, depth: Int,
        interactiveOnly: Boolean, maxNodes: Int,
    ): Int {
        if (count >= maxNodes || depth > 50) return count
        var c = count
        val clickable = node.isClickable
        val editable = node.isEditable
        val scrollable = node.isScrollable
        val hasText = !node.text.isNullOrBlank()
        val take = if (interactiveOnly) clickable || editable || scrollable else clickable || editable || scrollable || hasText
        if (take) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                arr.put(org.json.JSONObject().apply {
                    put("ref", "e$c")
                    put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
                    val t = node.text?.toString() ?: ""
                    put("text", if (t.length > 200) t.take(200) + "…" else t)
                    put("desc", node.contentDescription?.toString()?.take(100) ?: "")
                    put("id", node.viewIdResourceName ?: "")
                    put("bounds", "[${r.left},${r.top}][${r.right},${r.bottom}]")
                    put("cx", (r.left + r.right) / 2)
                    put("cy", (r.top + r.bottom) / 2)
                    if (clickable) put("clickable", true)
                    if (editable) put("editable", true)
                    if (scrollable) put("scrollable", true)
                })
                c++
            }
        }
        for (i in 0 until node.childCount) {
            if (c >= maxNodes) break
            val ch = node.getChild(i) ?: continue
            c = walk(ch, arr, c, depth + 1, interactiveOnly, maxNodes)
        }
        return c
    }
}
