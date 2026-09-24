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
 * Claude Code Mobile 无障碍服务 —— 替代 Shizuku/rish，提供手机操作能力。
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

        /**
         * ref → 节点 的映射。
         *
         * 【为什么不用"重新遍历按索引找"】
         * 那样每次操作都要遍历整棵树（慢），且界面一变索引就错位。
         * 存节点引用后直接 performAction，界面变了会返回 false（安全失败），
         * 不会点到别的元素上。
         */
        private val refNodes = mutableListOf<AccessibilityNodeInfo>()
        private val refLock = Any()

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
        // 收集的节点，snapshot 后用来建立 ref → 节点 的映射
        val collected = mutableListOf<AccessibilityNodeInfo>()

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || counter >= maxNodes) return
            // 深度保护：极端布局（如某些 WebView）可能嵌套上百层
            if (depth > 50) return

            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            val hasText = !node.text.isNullOrBlank()

            // 过滤：只要"能交互"或"有文字"的节点
            val interesting = clickable || editable || scrollable || hasText

            // ⚠️ 判断逻辑：interactiveOnly 时只要可交互的；否则还要有文字的
            val shouldCollect = if (interactiveOnly) {
                clickable || editable || scrollable
            } else {
                interesting
            }

            if (shouldCollect) {
                val rect = Rect()
                node.getBoundsInScreen(rect)

                // 跳过不可见 / 零尺寸
                if (rect.width() > 0 && rect.height() > 0) {
                    val o = JSONObject()
                    o.put("ref", "e${counter}")
                    o.put("cls", node.className?.toString()?.substringAfterLast('.') ?: "")
                    // 文本截断：超长文本（如整篇网页）会让响应体爆炸
                    val rawText = node.text?.toString() ?: ""
                    o.put("text", if (rawText.length > 200) rawText.take(200) + "…" else rawText)
                    o.put("desc", node.contentDescription?.toString()?.take(100) ?: "")
                    o.put("id", node.viewIdResourceName ?: "")
                    o.put("bounds", "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]")
                    o.put("cx", (rect.left + rect.right) / 2)
                    o.put("cy", (rect.top + rect.bottom) / 2)
                    if (clickable) o.put("clickable", true)
                    if (editable) o.put("editable", true)
                    if (scrollable) o.put("scrollable", true)
                    if (node.isCheckable) o.put("checked", node.isChecked)
                    arr.put(o)
                    collected.add(node)
                    counter++
                }
            }

            // 递归子节点
            for (i in 0 until node.childCount) {
                if (counter >= maxNodes) break
                walk(node.getChild(i), depth + 1)
            }
        }

        walk(root, 0)

        // ⚠️ 关键：把节点引用存起来，供后续 click/type 直接用。
        //
        // 【为什么】原来 findByRef 每次操作都重新遍历整棵树 —— 慢，而且
        // 界面一变（动画、列表滚动）索引就对不上了，ref 会指到别的元素。
        // 存引用后，点击时直接用当初那个节点（AccessibilityNodeInfo 是
        // 系统侧对象的句柄，界面变了它会失效，performAction 返回 false，
        // 比"点错元素"安全）。
        synchronized(refLock) {
            refNodes.clear()
            refNodes.addAll(collected)
        }

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
        return typeInto(node, text)
    }

    /**
     * 往指定节点写文本。
     * 优先用 ACTION_SET_TEXT（原子替换），失败则尝试聚焦后粘贴。
     */
    fun typeInto(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /**
     * 找当前有焦点的可编辑节点。
     *
     * 用途：AI 的常见流程是 click 输入框 → phone_type（不带 ref），
     * 这时焦点已在输入框上，直接往里写即可，不需要重新 snapshot。
     *
     * 遍历顺序：优先 INPUT_FOCUSED 标记的节点，其次 isFocused 的。
     */
    fun findFocusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var byInputFocus: AccessibilityNodeInfo? = null
        var byFocused: AccessibilityNodeInfo? = null

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 40) return
            if (byInputFocus != null) return

            if (node.isEditable) {
                // ⚠️ Android 的 AccessibilityNodeInfo 没有 isInputFocused()。
                // 只有 isFocused()（视图焦点）—— 对输入框来说这就够了：
                // 用户/AI 点了输入框后，它会拿到视图焦点。
                //
                // 两级优先：先记下任意 focused 的，同时若有 focused 且可编辑的，
                // 直接返回（最常见的场景）。
                if (node.isFocused) {
                    if (byFocused == null) byFocused = node
                    // 输入框同时可编辑 + 有焦点 → 就是它
                    if (node.isEditable) {
                        byInputFocus = node
                        return
                    }
                }
            }
            for (i in 0 until node.childCount) {
                walk(node.getChild(i), depth + 1)
                if (byInputFocus != null) return
            }
        }
        walk(root, 0)
        return byInputFocus ?: byFocused
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

    /**
     * 按 ref 找节点。
     *
     * 直接用 snapshot 时存下的引用（见 refNodes 的注释）。
     * 如果 snapshot 之后界面大变，节点会失效 —— 那时 performAction 返回 false，
     * 调用方会提示"重新 snapshot"，比点错元素安全。
     */
    private fun findByRef(ref: String): AccessibilityNodeInfo? {
        val idx = ref.removePrefix("e").toIntOrNull() ?: return null
        synchronized(refLock) {
            return refNodes.getOrNull(idx)
        }
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
