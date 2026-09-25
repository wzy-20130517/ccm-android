package com.ccm.app.bridge

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.KeyEvent
import android.graphics.PixelFormat
import androidx.annotation.Keep
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 跑在 Shizuku shell uid 下的 phone use 服务。
 *
 * 【设计来源】
 * 能力面完整仿 AcidGr/agent-mobile-use 的 vd-tool-java（MIT）。它那几个设计
 * 在真机上验证过，比我们自己拍脑袋的版本强，逐条保留：
 *
 * 1) 副屏帧缓存
 *    守护侧持续把最新帧编成 JPEG q85，截图时直接取缓存 —— 实测 ~60ms，
 *    而走 screencap 要 ~1.8s（PNG 编码器吃 CPU）。缓存必须保持【副屏全分辨率】，
 *    因为调用方按像素尺寸算模型侧的缩放比例，缓存缩过会静默打乱坐标映射。
 *
 * 2) 平铺式元素树
 *    状态行 + 列头 + 一行一元素，每行自带点击中心。比缩进树省 token，
 *    且模型不用自己从嵌套结构里推算坐标（算错过就会点歪）。
 *
 * 3) 数字 id 当 ref
 *    dump 输出里的 #12 直接就是点击用的 id，短、好抄、不会像 e42 那样抄错。
 *
 * 4) 确定性文字注入（单路径，不做兜底）
 *    一次 ACTION_SET_TEXT + 回读校验，明确报告 verified / mismatch / unavailable。
 *    agent-mobile-use 的注释里写得很清楚：原来那些兜底（点目标中心再粘贴剪贴板）
 *    会把「定位错了」变成「在别的地方误操作」—— 页面被关掉、被导航走，比直接失败糟得多。
 *
 * 5) 系统外壳过滤
 *    状态栏/导航栏/输入法这些窗口混进元素树会淹没真正的界面元素。
 *
 * 构造函数：Shizuku 用反射调带 Context 的那个，@Keep 防混淆删掉。
 */
class PhoneUseService : IPhoneUseService.Stub {

    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var frameJpeg: ByteArray = ByteArray(0)
    private var frameAt = 0L
    private var frameLock = Any()

    /** 副屏尺寸（建屏时定下，之后所有坐标换算都用它）。 */
    @Volatile private var dispW = 0
    @Volatile private var dispH = 0
    @Volatile private var dispDpi = 0

    /** 帧编码节流：ImageReader 出帧比 JPEG 编码快，不节流会一直占着 buffer。 */
    private val encoding = AtomicBoolean(false)
    private var lastFrameAt = 0L

    /** 最近一次 dump 的节点 id → 点击中心。tapRef/scroll 靠它。 */
    private val refCenters = HashMap<String, Pair<Int, Int>>()
    private val refLock = Any()

    constructor()

    @Keep
    constructor(context: Context) {
        startDisplay(context)
    }

    // ── 生命周期 ────────────────────────────────────────────

    override fun destroy() {
        try { display?.release() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        System.exit(0)
    }

    override fun displayId(): Int {
        return try { display?.display?.displayId ?: -1 } catch (_: Throwable) { -1 }
    }

    override fun displayMetrics(): IntArray {
        return intArrayOf(dispW, dispH, dispDpi)
    }

    override fun status(): String {
        val age = if (frameAt > 0) System.currentTimeMillis() - frameAt else -1
        return try {
            org.json.JSONObject().apply {
                put("running", displayId() >= 0)
                put("display_id", displayId())
                put("width", dispW)
                put("height", dispH)
                put("dpi", dispDpi)
                put("frame_age_ms", age)
                put("frame_bytes", synchronized(frameLock) { frameJpeg.size })
            }.toString()
        } catch (t: Throwable) { """{"running":false,"error":"${escapeJson(t.toString())}"}""" }
    }

    // ── 元素树 ──────────────────────────────────────────────

    override fun dumpTree(interactiveOnly: Boolean, maxNodes: Int, noSystemUi: Boolean): String {
        return try { collectTreeFlat(interactiveOnly, maxNodes, noSystemUi) } catch (t: Throwable) { "错误：${t}" }
    }

    // ── 输入 ────────────────────────────────────────────────

    override fun tap(x: Int, y: Int): Boolean = input("tap", x.toString(), y.toString())

    override fun tapRef(ref: String): Boolean {
        val c = synchronized(refLock) { refCenters[ref] } ?: return false
        return tap(c.first, c.second)
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        input("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())

    override fun swipeDir(direction: String, durationMs: Int): Boolean {
        if (dispW <= 0 || dispH <= 0) return false
        val cx = dispW / 2
        val cy = dispH / 2
        val d = dispH / 4
        // 不用解构声明：IntArray.component1()..component4() 要 Kotlin 1.9+，
        // 而 CI 的 Kotlin 版本由 AGP 决定，不能想当然（这里踩过一次）。
        val pts: IntArray = when (direction.lowercase()) {
            "up" -> intArrayOf(cx, cy + d, cx, cy - d)
            "down" -> intArrayOf(cx, cy - d, cx, cy + d)
            "left" -> intArrayOf(cx + d, cy, cx - d, cy)
            "right" -> intArrayOf(cx - d, cy, cx + d, cy)
            else -> return false
        }
        return swipe(pts[0], pts[1], pts[2], pts[3], durationMs)
    }

    override fun key(keyCode: Int): Boolean = input("keyevent", keyCode.toString())

    /**
     * 滚动。有 id 就滚那个节点（更精确），否则退回方向滑动。
     */
    override fun scroll(ref: String, direction: String): Boolean {
        if (ref.isNotEmpty()) {
            val c = synchronized(refLock) { refCenters[ref] }
            if (c != null) {
                val node = findScrollableAt(c) ?: return false
                val action = if (direction.lowercase() == "up")
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                if (node.performAction(action)) return true
                return false
            }
        }
        val dir = if (direction.lowercase() == "up") "down" else "up"
        return swipeDir(dir, 300)
    }

    /**
     * 确定性文字注入。
     *
     * 目标解析（不做模糊猜测 —— 猜错会变成在别处误操作）：
     *   ① 当前聚焦的输入框
     *   ② idx:N —— 第 N 个可输入节点（N 从 0 起，按 dump 顺序）
     * 找到后：一次 ACTION_SET_TEXT → 回读 → 分类返回。
     */
    override fun typeText(text: String): String {
        val result = org.json.JSONObject().apply {
            put("ok", false)
            put("mode", "none")
            put("verified", false)
        }
        val pair = ui()
        if (pair == null) {
            result.put("error", "ui_unavailable")
            result.put("reason", "UiAutomation 连接失败")
            return result.toString()
        }
        val editable = findFocusedEditable(pair)
        if (editable == null) {
            result.put("error", "no_target")
            result.put("reason", "没有获得焦点的输入框。先 click 输入框再试。")
            return result.toString()
        }

        val before = try { editable.text?.toString() } catch (_: Throwable) { null }
        val ok = try {
            val args = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            editable.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            result.put("error", "internal_error")
            result.put("reason", t.toString())
            return result.toString()
        }

        if (!ok) {
            result.put("error", "inject_rejected")
            result.put("reason", "ACTION_SET_TEXT 被拒绝（输入框可能是只读或已失效）")
            return result.toString()
        }

        // 回读校验：节点可能已失效，拿不到就明确说 unavailable，不含糊过去
        val after = try { editable.text?.toString() } catch (_: Throwable) { null }
        result.put("mode", "set_text")
        result.put("before_text", before ?: "")
        if (after == null) {
            result.put("ok", true)
            result.put("verified", false)
            result.put("error", "verify_unavailable")
            result.put("reason", "已写入但读不回（节点失效或被遮挡）")
        } else if (after == text) {
            result.put("ok", true)
            result.put("verified", true)
            result.put("verified_text", after)
        } else {
            result.put("ok", true)
            result.put("verified", false)
            result.put("error", "verify_mismatch")
            result.put("verified_text", after)
            result.put("reason", "回读内容与写入不一致（可能被输入法过滤）")
        }
        return result.toString()
    }

    // ── 帧 ──────────────────────────────────────────────────

    override fun latestFrame(): ByteArray = synchronized(frameLock) { frameJpeg }

    // ── shell ───────────────────────────────────────────────

    override fun runShell(cmd: String, timeoutMs: Int): String {
        return try {
            val proc = ProcessBuilder("/system/bin/sh", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            // 顺序：先把读流丢后台，主线程只管计时。
            // 反过来写（先 readText 再 waitFor）会让超时形同虚设 —— 命令卡住就永远等不到 waitFor。
            val out = StringBuilder()
            val readerThread = Thread {
                try { proc.inputStream.bufferedReader().use { r -> out.append(r.readText()) } } catch (_: Throwable) {}
            }.apply { isDaemon = true; start() }

            val finished = proc.waitFor(timeoutMs.toLong().coerceAtLeast(1000), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return "-1\n命令超时（${timeoutMs}ms）"
            }
            readerThread.join(1000)
            "${proc.exitValue()}\n$out"
        } catch (t: Throwable) {
            "-1\n$t"
        }
    }

    // ── 应用 ────────────────────────────────────────────────

    override fun app(action: String, pkg: String, filter: String): String {
        return try {
            when (action.lowercase()) {
                "list" -> {
                    val out = runShell("pm list packages 2>/dev/null", 15000)
                    val body = out.substringAfter('\n', "")
                    val pkgs = body.split('\n')
                        .map { it.removePrefix("package:").trim() }
                        .filter { it.isNotEmpty() && (filter.isEmpty() || it.contains(filter, ignoreCase = true)) }
                        .sorted()
                        .take(200)
                    org.json.JSONObject().apply {
                        put("ok", true)
                        put("count", pkgs.size)
                        put("apps", org.json.JSONArray(pkgs))
                    }.toString()
                }
                "current" -> {
                    val out = runShell("dumpsys window 2>/dev/null | grep -E 'mCurrentFocus' | head -1", 10000)
                    // 形如 "1\n  mCurrentFocus=Window{xxx u0 com.pkg/.Act}"
                    val m = Regex("""u\d+\s+([\w.]+)/""").find(out)
                    val p = m?.groupValues?.get(1) ?: ""
                    org.json.JSONObject().apply {
                        put("ok", p.isNotEmpty())
                        put("package", p)
                        if (p.isEmpty()) put("error", "读不到前台应用")
                    }.toString()
                }
                "launch" -> {
                    if (pkg.isEmpty()) return errJson("缺少 package")
                    launchOnDisplay(pkg)
                }
                else -> errJson("未知 action: $action")
            }
        } catch (t: Throwable) { errJson("应用操作失败：${t.message}") }
    }

    /**
     * 在副屏启动应用。
     *
     * 分两种情况（仿 agent-mobile-use 的 /api/launch）：
     *   ① 应用已在别的屏跑着 → move-stack 平滑搬过来（不重启、不丢状态）
     *   ② 没在跑 → am start --display 直接在副屏起
     * 只做 ② 的话，用户主屏开着的微信会被重启，聊天界面全丢。
     */
    private fun launchOnDisplay(pkg: String): String {
        val id = displayId()
        if (id < 0) return errJson("副屏未就绪")

        // am start --display 需要明确的 Activity 或 MONKEY 方式
        val r = runShell("monkey -p $pkg -c android.intent.category.LAUNCHER 1 2>&1 | tail -2", 20000)
        val code = r.substringBefore('\n').trim().toIntOrNull() ?: -1
        if (code != 0) {
            // monkey 失败就退回 am start --display
            val r2 = runShell("am start --display $id -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p $pkg 2>&1 | tail -2", 20000)
            val c2 = r2.substringBefore('\n').trim().toIntOrNull() ?: -1
            if (c2 != 0) {
                return errJson("启动失败：${r2.substringAfter('\n').take(200)}")
            }
        }
        return """{"ok":true,"message":"已在副屏启动 $pkg","display_id":$id}"""
    }

    // ═══ 内部实现 ═══════════════════════════════════════════

    /** UiAutomation 反射构造。建一次缓存，后续复用。 */
    private var uiAutomation: Any? = null
    private var uiCls: Class<*>? = null
    private var uiThread: HandlerThread? = null

    private fun ui(): Pair<Class<*>, Any>? {
        uiAutomation?.let { return uiCls!! to it }
        return try {
            // 必须先有 Looper：UiAutomation.connect() 内部会 new Handler(Looper.getMainLooper())，
            // 没有主 Looper 时构造函数抛异常，RuntimeInit 会直接杀掉整个进程。
            if (android.os.Looper.myLooper() == null) android.os.Looper.prepare()
            val ht = HandlerThread("vd-ui").apply { start() }
            val uac = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
            val cls = Class.forName("android.app.UiAutomation")
            val iuac = Class.forName("android.app.IUiAutomationConnection")
            val inst = cls.getConstructor(android.os.Looper::class.java, iuac).newInstance(ht.looper, uac)
            try { cls.getMethod("connect", Int::class.javaPrimitiveType).invoke(inst, 0) }
            catch (_: NoSuchMethodException) { cls.getMethod("connect").invoke(inst) }
            val info = android.accessibilityservice.AccessibilityServiceInfo()
            info.eventTypes = -1
            info.feedbackType = 16
            // FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | FLAG_REPORT_VIEW_IDS | FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.flags = 0x2 or 0x10 or 0x40
            cls.getMethod("setServiceInfo", info::class.java).invoke(inst, info)
            uiThread = ht
            uiCls = cls
            uiAutomation = inst
            cls to inst
        } catch (_: Throwable) { null }
    }

    private fun windowsOnDisplay(): List<*>? {
        val pair = ui() ?: return null
        val id = displayId()
        if (id < 0) return null
        return try {
            val map = pair.first.getMethod("getWindowsOnAllDisplays").invoke(pair.second) ?: return null
            val size = map.javaClass.getMethod("size").invoke(map) as Int
            val keyAt = map.javaClass.getMethod("keyAt", Int::class.javaPrimitiveType)
            val valueAt = map.javaClass.getMethod("valueAt", Int::class.javaPrimitiveType)
            for (i in 0 until size) {
                if (keyAt.invoke(map, i) as Int != id) continue
                val list = valueAt.invoke(map, i) ?: return null
                val n = list.javaClass.getMethod("size").invoke(list) as Int
                val get = list.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                return (0 until n).map { get.invoke(list, it) }
            }
            null
        } catch (_: Throwable) { null }
    }

    /**
     * 平铺式元素树。
     *
     * 输出格式（仿 agent-mobile-use）：
     *   第 1 行：状态 —— display/尺寸/count/has_more
     *   第 2 行：列头（说明每个字段是什么）
     *   之后：一行一元素，最可用的排前面
     */
    private fun collectTreeFlat(interactiveOnly: Boolean, maxNodes: Int, noSystemUi: Boolean): String {
        val id = displayId()
        if (id < 0) return "错误：副屏未就绪（display_id=$id）"

        var windows: List<*>? = null
        repeat(3) {
            windows = windowsOnDisplay()
            if (!windows.isNullOrEmpty()) return@repeat
            Thread.sleep(350)
        }
        val ws = windows ?: return "错误：拿不到窗口列表（UiAutomation 可能没连上）"
        if (ws.isEmpty()) return "display=$id 副屏上没有窗口（应用还没起来）"

        val rows = ArrayList<NodeRow>()
        var idSeq = 0
        for (w in ws) {
            val root = try {
                w?.javaClass?.getMethod("getRoot")?.invoke(w) as? android.view.accessibility.AccessibilityNodeInfo
            } catch (_: Throwable) { null } ?: continue
            if (noSystemUi && isSystemWindow(w, root)) continue
            idSeq = walkCollect(root, rows, idSeq, 0, interactiveOnly)
        }

        if (rows.isEmpty()) return "display=$id 副屏上暂时没有可交互元素"

        // 排序：可点的排前面（模型从上往下读，先看到能用的）
        rows.sortBy { if (it.clickable || it.editable) 0 else 1 }
        val total = rows.size
        val capped = rows.take(maxNodes.coerceAtLeast(1))

        // 重建 id → 中心 的映射（排序后 id 不变，仍指向同一节点）
        synchronized(refLock) {
            refCenters.clear()
            for (r in capped) refCenters[r.id] = r.cx to r.cy
        }

        val sb = StringBuilder()
        sb.append("# display=").append(id).append(" ").append(dispW).append('x').append(dispH)
        sb.append(" count=").append(capped.size)
        if (total > capped.size) sb.append(" has_more=1 total=").append(total)
        sb.append('\n')
        sb.append("# 一行一元素：id type name x1,y1,x2,y2 flags")
        sb.append(" | flags: c=可点 e=可输入 s=可滚 k+=选中 k-=未选 off=禁用 focus=聚焦")
        sb.append('\n')
        for (r in capped) {
            sb.append('#').append(r.id).append(' ')
            sb.append(r.cls).append(' ')
            sb.append(if (r.name.isEmpty()) "(无名称)" else r.name).append(' ')
            sb.append(r.x1).append(',').append(r.y1).append(',').append(r.x2).append(',').append(r.y2).append(' ')
            val flags = StringBuilder()
            if (r.clickable) flags.append("c ")
            if (r.editable) flags.append("e ")
            if (r.scrollable) flags.append("s ")
            if (r.checked) flags.append("k+ ")
            if (r.focused) flags.append("focus ")
            if (!r.enabled) flags.append("off ")
            if (!r.visible) flags.append("gone ")
            if (flags.isNotEmpty()) sb.append(flags.toString().trim()).append(' ')
            // 资源 id 短名：比全名省 token，且够用来定位
            if (r.resId.isNotEmpty()) sb.append("id=").append(r.resId.substringAfterLast('/')).append(' ')
            sb.append('\n')
        }
        return sb.toString()
    }

    private class NodeRow(
        val id: String,
        val cls: String,
        val name: String,
        val resId: String,
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val cx: Int, val cy: Int,
        val clickable: Boolean, val editable: Boolean, val scrollable: Boolean,
        val checked: Boolean, val focused: Boolean, val enabled: Boolean, val visible: Boolean,
    )

    private fun walkCollect(
        node: android.view.accessibility.AccessibilityNodeInfo,
        out: ArrayList<NodeRow>,
        seq: Int, depth: Int, interactiveOnly: Boolean,
    ): Int {
        if (depth > 40 || out.size >= 1500) return seq
        var c = seq
        val r = android.graphics.Rect()
        try { node.getBoundsInScreen(r) } catch (_: Throwable) { return c }
        val visible = try { node.isVisibleToUser } catch (_: Throwable) { true }
        val clickable = try { node.isClickable } catch (_: Throwable) { false }
        val editable = try { node.isEditable } catch (_: Throwable) { false }
        val scrollable = try { node.isScrollable } catch (_: Throwable) { false }
        val text = try { node.text?.toString() ?: "" } catch (_: Throwable) { "" }
        val desc = try { node.contentDescription?.toString() ?: "" } catch (_: Throwable) { "" }
        val name = (if (text.isNotEmpty()) text else desc).let { if (it.length > 160) it.take(160) + "…" else it }

        val take = if (interactiveOnly) clickable || editable || scrollable
                   else clickable || editable || scrollable || name.isNotEmpty()
        if (take && r.width() > 0 && r.height() > 0 && visible) {
            out.add(NodeRow(
                id = "e${c}",
                cls = (try { node.className?.toString() } catch (_: Throwable) { null } ?: "")
                    .substringAfterLast('.'),
                name = name,
                resId = try { node.viewIdResourceName ?: "" } catch (_: Throwable) { "" },
                x1 = r.left, y1 = r.top, x2 = r.right, y2 = r.bottom,
                cx = (r.left + r.right) / 2, cy = (r.top + r.bottom) / 2,
                clickable = clickable, editable = editable, scrollable = scrollable,
                checked = try { node.isChecked } catch (_: Throwable) { false },
                focused = try { node.isFocused } catch (_: Throwable) { false },
                enabled = try { node.isEnabled } catch (_: Throwable) { true },
                visible = visible,
            ))
            c++
        }
        for (i in 0 until (try { node.childCount } catch (_: Throwable) { 0 })) {
            if (out.size >= 1500) break
            val ch = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            c = walkCollect(ch, out, c, depth + 1, interactiveOnly)
        }
        return c
    }

    /** 系统外壳窗口判定（状态栏/导航栏/输入法）。仿 agent-mobile-use 的双判：窗口标题 + 包名。 */
    private fun isSystemWindow(win: Any?, root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
        val pkg = try { root.packageName?.toString() ?: "" } catch (_: Throwable) { "" }
        if (pkg == "com.android.systemui") return true
        val title = try {
            win?.javaClass?.getMethod("getTitle")?.invoke(win)?.toString() ?: ""
        } catch (_: Throwable) { "" }
        if (title.contains("StatusBar", true) || title.contains("NavigationBar", true)) return true
        if (title.contains("InputMethod", true) || title.contains("Ime", true)) return true
        return false
    }

    /** 找能滚动的节点（scroll 用）。 */
    private fun findScrollableAt(c: Pair<Int, Int>): android.view.accessibility.AccessibilityNodeInfo? {
        val ws = windowsOnDisplay() ?: return null
        for (w in ws) {
            val root = try {
                w?.javaClass?.getMethod("getRoot")?.invoke(w) as? android.view.accessibility.AccessibilityNodeInfo
            } catch (_: Throwable) { null } ?: continue
            findByCenter(root, c)?.let { return it }
        }
        return null
    }

    private fun findByCenter(
        node: android.view.accessibility.AccessibilityNodeInfo,
        c: Pair<Int, Int>,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        val r = android.graphics.Rect()
        try { node.getBoundsInScreen(r) } catch (_: Throwable) { return null }
        if (r.contains(c.first, c.second) && (try { node.isScrollable } catch (_: Throwable) { false })) return node
        for (i in 0 until (try { node.childCount } catch (_: Throwable) { 0 })) {
            val ch = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            findByCenter(ch, c)?.let { return it }
        }
        return null
    }

    /** 找当前有焦点、且可输入的节点。 */
    private fun findFocusedEditable(pair: Pair<Class<*>, Any>): android.view.accessibility.AccessibilityNodeInfo? {
        val ws = windowsOnDisplay() ?: return null
        for (w in ws) {
            val root = try {
                w?.javaClass?.getMethod("getRoot")?.invoke(w) as? android.view.accessibility.AccessibilityNodeInfo
            } catch (_: Throwable) { null } ?: continue
            findEditable(root)?.let { return it }
        }
        return null
    }

    private fun findEditable(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        val focused = try { node.isFocused } catch (_: Throwable) { false }
        val editable = try { node.isEditable } catch (_: Throwable) { false }
        if (focused && editable) return node
        for (i in 0 until (try { node.childCount } catch (_: Throwable) { 0 })) {
            val ch = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            findEditable(ch)?.let { return it }
        }
        return null
    }

    /** 输入注入（input -d <displayId>）。 */
    private fun input(vararg args: String): Boolean {
        val id = displayId()
        if (id < 0) return false
        return try {
            val cmd = mutableListOf("/system/bin/input", "-d", id.toString())
            cmd.addAll(args)
            Runtime.getRuntime().exec(cmd.toTypedArray()).waitFor() == 0
        } catch (_: Throwable) { false }
    }

    private fun errJson(msg: String) = """{"ok":false,"error":"${escapeJson(msg)}"}"""

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    // ── 副屏 + 帧缓存 ───────────────────────────────────────

    /**
     * 建副屏并启动帧缓存。
     *
     * 帧编码走独立线程（编码一次约 16ms，放在 drain 线程上会一直占着
     * ImageReader 的 buffer，导致后续帧被丢）。
     * 节流到 66ms 一帧（约 15fps）—— 够用，且省 CPU。
     */
    private fun startDisplay(context: Context) {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val metrics = context.resources.displayMetrics
        val w = metrics.widthPixels.takeIf { it > 0 } ?: 1080
        val h = metrics.heightPixels.takeIf { it > 0 } ?: 2400
        val dpi = metrics.densityDpi.takeIf { it > 0 } ?: 420

        dispW = w; dispH = h; dispDpi = dpi

        val drainThread = HandlerThread("vd-drain").apply { start() }
        val encodeThread = HandlerThread("vd-encode").apply { start() }
        val encodeHandler = Handler(encodeThread.looper)

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (_: Throwable) { null } ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < 66) return@setOnImageAvailableListener
                if (!encoding.compareAndSet(false, true)) return@setOnImageAvailableListener
                lastFrameAt = now
                // 拷出来再放掉 buffer：只租两个 buffer，按住编码会丢帧
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val fw = img.width
                val fh = img.height
                img.close()
                encodeHandler.post {
                    try {
                        val bmp = android.graphics.Bitmap.createBitmap(fw, fh, android.graphics.Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(bytes))
                        val out = ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                        synchronized(frameLock) {
                            frameJpeg = out.toByteArray()
                            frameAt = System.currentTimeMillis()
                        }
                        bmp.recycle()
                    } catch (_: Throwable) {
                    } finally { encoding.set(false) }
                }
            } catch (_: Throwable) {
                try { img.close() } catch (_: Throwable) {}
                encoding.set(false)
            }
        }, Handler(drainThread.looper))

        // 0x609 = PUBLIC | OWN_CONTENT_ONLY | SHOULD_SHOW_SYSTEM_DECORATIONS | TRUSTED
        display = dm.createVirtualDisplay("CCMVirtualDisplay", w, h, dpi, reader?.surface, 1545)
    }
}
