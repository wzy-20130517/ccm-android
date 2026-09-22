package com.ccm.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * CCM 无障碍服务 —— 替代 Shizuku/rish，提供手机操作能力。
 *
 * 【为什么用无障碍而不是 Shizuku】
 * | 能力        | 无障碍          | Shizuku(rish)      |
 * |------------|----------------|-------------------|
 * | 读界面结构   | ✅ 直接拿节点树  | ⚠️ dumpsys 文本解析 |
 * | 点击/滑动   | ✅ performAction | ✅ input tap        |
 * | 坐标手势    | ✅ dispatchGesture| ✅ input swipe     |
 * | 输入文本    | ✅ ACTION_SET_TEXT| ✅ input text      |
 * | 需要外部依赖 | ❌ 无           | ✅ 要装 Shizuku     |
 *
 * 无障碍是系统级能力，装上就能用，不需要用户装额外 App。
 *
 * 【节点树 vs dumpsys】
 * 原 tools-phone.mjs 靠 `uiautomator dump` 拿 XML，再正则解析出 ref。
 * 这里直接遍历 AccessibilityNodeInfo 树，拿到的是结构化对象，
 * 且能直接 performAction —— 省掉"解析文本 → 算坐标 → 再点"三步。
 */
class CcmAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "CcmA11y"

        @Volatile
        private var instance: CcmAccessibilityService? = null

        fun get(): CcmAccessibilityService? = instance
        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不订阅具体事件（省电）。需要界面内容时主动 getRootInActiveWindow()
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "无障碍服务已断开")
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════
    //  界面快照 —— 对应 phone_snapshot
    // ═══════════════════════════════════════════════════

    /**
     * 把当前界面转成元素树 JSON。
     *
     * 输出格式对齐原 phone_snapshot（ref / text / class / bounds / clickable），
     * 这样 Node 侧的工具不用改调用方式。
     *
     * @param interactiveOnly 只返回可点击/可输入的节点
     * @param maxNodes 上限，防止超大界面卡死
     */
    fun snapshot(interactiveOnly: Boolean = true, maxNodes: Int = 300): String {
        val root = rootInActiveWindow ?: return """{"ok":false,"error":"无法获取界面（可能被系统限制）"}"""

        val arr = JSONArray()
        var counter = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || counter >= maxNodes) return

            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val hasText = !node.text.isNullOrBlank()

            // 过滤：只要"能交互"或"有文字"的节点
            val interesting = clickable || editable || scrollable || hasText

            if (!interactiveOnly || (clickable || editable || scrollable)) {
                if (interesting) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)

                    // 跳过不可见 / 零尺寸
                    if (rect.width() > 0 && rect.height() > 0) {
                        val o = JSONObject()
                        o.put("ref", "e${counter}")
                        o.put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
                        o.put("text", node.text?.toString() ?: "")
                        o.put("desc", node.contentDescription?.toString() ?: "")
                        o.put("id", node.viewIdResourceName ?: "")
                        o.put("bounds", "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
                        o.put("cx", (rect.left + rect.right) / 2)
                        o.put("cy", (rect.top + rect.bottom) / 2)
                        if (clickable) o.put("clickable", true)
                        if (editable) o.put("editable", true)
                        if (scrollable) o.put("scrollable", true)
                        if (node.isCheckable) o.put("checked", node.isChecked)
                        arr.put(o)
                        counter++
                    }
                }
            }

            // 递归子节点
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
                if (counter >= maxNodes) break
            }
        }

        walk(root, 0)

        return JSONObject().apply {
            put("ok", true)
            put("package", root.packageName?.toString() ?: "")
            put("count", arr.length())
            put("nodes", arr)
        }.toString()
    }

    // ═══════════════════════════════════════════════════
    //  操作 —— 对应 phone_click / phone_type / phone_swipe
    // ═══════════════════════════════════════════════════

    /**
     * 按 ref 点击（ref 来自 snapshot）。
     * ref 格式 "e12" → 索引 12。
     */
    fun clickByRef(ref: String, longPress: Boolean = false): Boolean {
        val node = findByRef(ref) ?: return false
        val action = if (longPress)
            AccessibilityNodeInfo.ACTION_LONG_CLICK
        else
            AccessibilityNodeInfo.ACTION_CLICK
        return node.performAction(action)
    }

    /** 按 ref 输入文本（清空后设置） */
    fun typeByRef(ref: String, text: String): Boolean {
        val node = findByRef(ref) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** 按坐标点击（用手势 API） */
    fun tapXY(x: Float, y: Float, longPress: Boolean = false): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val duration = if (longPress) 800L else 60L
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGestureSync(gesture)
    }

    /** 滑动 */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGestureSync(gesture)
    }

    /** 滚动（在指定 ref 上，或全屏） */
    fun scrollByRef(ref: String?, direction: String): Boolean {
        val node = (if (ref != null) findByRef(ref) else rootInActiveWindow)
            ?: return false
        val action = when (direction.lowercase()) {
            "down", "up" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return node.performAction(action)
    }

    /** 全局按键（back/home/recents/notifications） */
    fun globalAction(action: String): Boolean {
        val id = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recent", "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return performGlobalAction(id)
    }

    // ═══════════════════════════════════════════════════
    //  内部工具
    // ═══════════════════════════════════════════════════

    /** 按 ref 找节点（重新遍历，因为 ref 是快照时的索引） */
    private fun findByRef(ref: String): AccessibilityNodeInfo? {
        val idx = ref.removePrefix("e").toIntOrNull() ?: return null
        val root = rootInActiveWindow ?: return null
        var counter = 0
        var found: AccessibilityNodeInfo? = null

        fun walk(node: AccessibilityNodeInfo?) {
            if (node == null || found != null) return
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val hasText = !node.text.isNullOrBlank()
            if (clickable || editable || scrollable || hasText) {
                val rect = Rect().also { node.getBoundsInScreen(it) }
                if (rect.width() > 0 && rect.height() > 0) {
                    if (counter == idx) { found = node; return }
                    counter++
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i))
                if (found != null) return
            }
        }
        walk(root)
        return found
    }

    /** 同步等待手势完成（dispatchGesture 是异步的） */
    private fun dispatchGestureSync(gesture: GestureDescription, timeoutMs: Long = 3000): Boolean {
        val latch = CountDownLatch(1)
        var ok = false
        val callback = object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(g: GestureDescription?) { ok = false; latch.countDown() }
        }
        return try {
            if (dispatchGesture(gesture, callback, null)) {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                ok
            } else false
        } catch (t: Throwable) {
            Log.w(TAG, "手势失败: ${t.message}")
            false
        }
    }
}
