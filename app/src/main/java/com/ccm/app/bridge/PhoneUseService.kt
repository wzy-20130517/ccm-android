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
    /**
     * ref(id) → 节点信息。
     *
     * 【为什么存节点而不只存坐标】agent-mobile-use 的做法值得抄：
     * 点击用中心坐标，但**输入文字必须拿到节点本身**（要 performAction(SET_TEXT)，
     * 还要回读校验）。只存坐标的话，输入就得靠「点一下再粘剪贴板」——
     * 那条路在节点已失效时会变成在别处误操作，比直接失败糟得多。
     *
     * 这里同时存 raw（可空，UiAutomation 节点会随界面刷新失效）和中心坐标：
     * raw 失效时点击还能用坐标兜底，但输入会明确报「节点已失效」让模型重新 dump。
     */
    private val refTable = HashMap<String, RefEntry>()
    private val refLock = Any()

    private class RefEntry(
        val cx: Int,
        val cy: Int,
        @Volatile var raw: android.view.accessibility.AccessibilityNodeInfo?,
        val editable: Boolean,
    )

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
        val e = synchronized(refLock) { refTable[ref] } ?: return false
        return tap(e.cx, e.cy)
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
            val e = synchronized(refLock) { refTable[ref] }
            if (e != null) {
                val node = e.raw?.takeIf { it.isScrollable } ?: findScrollableAt(e.cx to e.cy) ?: return false
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
    /**
     * 文字注入。目标解析仿 agent-mobile-use 的 smartType，双轨 + 明确分类：
     *
     *   ① 空 / "focused"  → 当前有焦点的输入框
     *      （焦点节点本身不可编辑时，往下找它的第一个可编辑子孙 ——
     *        安卓里带焦点的常是外层容器，真正能写的是里面那个 EditText）
     *   ② "e12" / "12"    → 上次 dumpTree 里的节点 id
     *      （节点失效就明确报 target_stale，让模型重新 dump，不猜、不兜底点击）
     *
     * 定位到之后：一次 ACTION_SET_TEXT → 回读 → 分类。
     * 绝不退回「点中心再粘剪贴板」—— 那会把「定位错了」变成「在别处误操作」。
     */
    override fun typeText(text: String): String = typeTextAt(text, "")

    override fun typeTextAt(text: String, targetSpec: String): String {
        val result = org.json.JSONObject().apply {
            put("ok", false)
            put("mode", "none")
            put("verified", false)
        }
        return doType(text, targetSpec, result)
    }

    private fun doType(text: String, targetSpec: String, result: org.json.JSONObject): String {
        val pair = ui()
        if (pair == null) {
            result.put("error", "ui_unavailable")
            result.put("reason", "UiAutomation 连接失败（Shizuku 授权或虚拟屏异常）")
            return result.toString()
        }

        val focusMode = targetSpec.isBlank() || targetSpec.equals("focused", ignoreCase = true)
        var targetNode: android.view.accessibility.AccessibilityNodeInfo? = null

        if (focusMode) {
            targetNode = findFocusedEditable(pair)
            if (targetNode == null) {
                val focusedAny = findFocusedAny(pair)
                result.put("error", "no_focused_input")
                result.put(
                    "focus_hint",
                    if (focusedAny == null) "nothing"
                    else "${simpleClass(focusedAny)}@${rectStr(focusedAny)}",
                )
                result.put("reason", "没有获得焦点的输入框。先点击输入框再输入。")
                return result.toString()
            }
        } else {
            // e12 / node:12 / 12 都能认
            val clean = targetSpec.removePrefix("node:").trim().removePrefix("e")
            val id = clean.toIntOrNull()
            if (id == null) {
                result.put("error", "invalid_target")
                result.put("reason", "target 只能是 dump 里的节点 id（如 e12 或 12），或省略表示用当前焦点框")
                return result.toString()
            }
            val key = "e$id"
            val entry = synchronized(refLock) { refTable[key] }
            if (entry == null) {
                result.put("error", "target_not_found")
                result.put("reason", "节点 $key 不在上次 dump 里（界面可能已刷新）。重新 phone_snapshot 再试。")
                return result.toString()
            }
            // raw 会随界面刷新失效 —— 失效时明确指出，不退回坐标点击
            val raw = entry.raw
            if (raw == null) {
                result.put("error", "target_stale")
                result.put("reason", "节点 $key 已失效（界面刷新过）。重新 phone_snapshot 再试。")
                return result.toString()
            }
            targetNode = if (raw.isEditable) raw else findEditable(raw)
            if (targetNode == null) {
                result.put("error", "target_not_editable")
                result.put("reason", "节点 $key 及其子节点都不是输入框，换个可输入的元素。")
                return result.toString()
            }
        }

        val node = targetNode!!
        val before = try { node.text?.toString() } catch (_: Throwable) { null }
        val ok = try {
            val args = android.os.Bundle().apply {
                putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (t: Throwable) {
            result.put("error", "internal_error")
            result.put("reason", t.toString())
            return result.toString()
        }

        if (!ok) {
            result.put("error", "inject_rejected")
            result.put("reason", "ACTION_SET_TEXT 被拒绝（输入框只读，或节点刚失效）")
            return result.toString()
        }

        // 回读校验。拿不到就明确说 unavailable —— 不含糊过去（模型需要知道到底写没写进去）
        val after = try { node.text?.toString() } catch (_: Throwable) { null }
        result.put("mode", if (focusMode) "focused" else "node")
        result.put("before_text", before ?: "")
        when {
            after == null -> {
                result.put("ok", true)
                result.put("verified", false)
                result.put("error", "verify_unavailable")
                result.put("reason", "已写入但读不回（节点失效或被遮挡）")
            }
            after == text -> {
                result.put("ok", true)
                result.put("verified", true)
                result.put("verified_text", after)
            }
            else -> {
                result.put("ok", true)
                result.put("verified", false)
                result.put("error", "verify_mismatch")
                result.put("verified_text", after)
                result.put("reason", "回读与写入不一致（可能被输入法过滤，或字段有长度/格式限制）")
            }
        }
        return result.toString()
    }

    private fun simpleClass(node: android.view.accessibility.AccessibilityNodeInfo): String =
        try { node.className?.toString()?.substringAfterLast('.') ?: "View" } catch (_: Throwable) { "View" }

    private fun rectStr(node: android.view.accessibility.AccessibilityNodeInfo): String {
        val r = android.graphics.Rect()
        return try { node.getBoundsInScreen(r); "${r.left},${r.top},${r.right},${r.bottom}" }
        catch (_: Throwable) { "?,?,?,?" }
    }

    /** 当前有焦点的任意节点（焦点框不是输入框时，用来给失败提示）。 */
    private fun findFocusedAny(pair: Pair<Class<*>, Any>): android.view.accessibility.AccessibilityNodeInfo? {
        val ws = windowsOnDisplay() ?: return null
        for (w in ws) {
            val root = try {
                w?.javaClass?.getMethod("getRoot")?.invoke(w) as? android.view.accessibility.AccessibilityNodeInfo
            } catch (_: Throwable) { null } ?: continue
            findFocusedAnyIn(root)?.let { return it }
        }
        return null
    }

    private fun findFocusedAnyIn(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
        if (try { node.isFocused } catch (_: Throwable) { false }) return node
        for (i in 0 until (try { node.childCount } catch (_: Throwable) { 0 })) {
            val ch = try { node.getChild(i) } catch (_: Throwable) { null } ?: continue
            findFocusedAnyIn(ch)?.let { return it }
        }
        return null
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
    /**
     * 在副屏启动应用。仿 agent-mobile-use 的 /api/launch 三分支：
     *
     *   ① 已在副屏且在跑        → 直接返回，不动它（重启会把用户进度清掉）
     *   ② 已在别的屏（如主屏）在跑 → cmd activity display move-stack 平滑搬过来
     *                             —— 不重启、不丢状态。这是它最有价值的一招：
     *                                用户在主屏开着的微信，AI 要操作时搬过去而不是重开。
     *   ③ 没在跑              → am start --display 冷启动
     *
     * 注意不要用 monkey：不支持 --display（会在主屏起），且不主动退出会挂住。
     */
    private fun launchOnDisplay(pkg: String): String {
        val id = displayId()
        if (id < 0) return errJson("副屏未就绪")

        // 查这个包在哪个 display 的 stack 里（RootTask 列表）
        val stack = findStackOfPackage(pkg)
        if (stack != null) {
            val (stackId, stackDisplay) = stack
            if (stackDisplay == id) {
                return """{"ok":true,"message":"$pkg 已在副屏运行","display_id":$id,"stack_id":$stackId,"already":true}"""
            }
            // 在别的屏 → 搬过来
            val mv = runShell("cmd activity display move-stack $stackId $id 2>&1; echo \"__exit=$?\"", 20000)
            val mb = mv.substringAfter('\n')
            val mExit = Regex("""__exit=(\d+)""").find(mb)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (mExit == 0) {
                return """{"ok":true,"message":"已把 $pkg 从 display $stackDisplay 平滑移到副屏（未重启）","display_id":$id,"stack_id":$stackId,"moved":true}"""
            }
            // 搬运失败就继续走冷启动，别把路堵死
        }

        // 冷启动：am start --display
        val out = runShell(
            "am start --display $id --user 0 -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -p $pkg 2>&1; echo \"__exit=$?\"",
            20000,
        )
        val body = out.substringAfter('\n')
        val exit = Regex("""__exit=(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        val started = body.contains("Starting: Intent") || body.contains("Status: ok")
        if (exit != 0 || !started) {
            val firstErr = body.lines().firstOrNull { it.isNotBlank() && !it.startsWith("Warning") } ?: body
            return errJson("在副屏启动 $pkg 失败：${firstErr.take(200)}")
        }
        return """{"ok":true,"message":"已在副屏启动 $pkg","display_id":$id}"""
    }

    /**
     * 找某个包所在的 activity stack。
     * 输出形如：
     *   RootTask id=14555 displayId=8 type=standard
     *   ...  mResumedActivity ... com.android.settings/.MiuiSettings ...
     * 返回 (stackId, displayId)，找不到返回 null。
     */
    private fun findStackOfPackage(pkg: String): Pair<Int, Int>? {
        val out = runShell("dumpsys activity activities 2>/dev/null | grep -E 'RootTask id=|mResumedActivity|topResumedActivity' | head -120", 15000)
        val body = out.substringAfter('\n')
        var curStack = -1
        var curDisplay = -1
        for (line in body.lines()) {
            val rt = Regex("""RootTask id=(\d+) displayId=(-?\d+)""").find(line)
            if (rt != null) {
                curStack = rt.groupValues[1].toIntOrNull() ?: -1
                curDisplay = rt.groupValues[2].toIntOrNull() ?: -1
                continue
            }
            // 这一行提到我们的包 → 就是它
            if (curStack >= 0 && line.contains(pkg)) return curStack to curDisplay
        }
        return null
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
        // ⚠️ 不能用 repeat(3) { ... return@repeat }：那是 continue 不是 break，
        // 拿到窗口后还会白 sleep 两次（各 350ms）。副屏刚建好时窗口要等一会儿才有，
        // 但一旦拿到就该立刻走。
        for (attempt in 0 until 3) {
            windows = windowsOnDisplay()
            if (!windows.isNullOrEmpty()) break
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
        // 排序仿 agent-mobile-use 的 priorityOf + Ranked：
        //   先按「有用程度」分层，同层内可操作的优先，再按文档顺序（保持界面上下关系）。
        // 目的是让模型从上往下读时，真正能点的元素排在前面 —— 免得有价值的操作
        // 被一堆装饰性文本挤到 maxNodes 之外。
        rows.sortWith(compareBy(
            { priorityOf(it) },
            { if (it.clickable) 0 else 1 },
            { it.seq },
        ))
        val total = rows.size
        val capped = rows.take(maxNodes.coerceAtLeast(1))

        // 重建 id → 中心 的映射（排序后 id 不变，仍指向同一节点）
        synchronized(refLock) {
            refTable.clear()
            for (r in capped) refTable[r.id] = RefEntry(r.cx, r.cy, r.raw, r.editable)
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
        val raw: android.view.accessibility.AccessibilityNodeInfo?,
        val seq: Int,
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
                raw = node,
                seq = c,
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

    /**
     * 有用程度分层（0 最有用）。
     * 仿 agent-mobile-use 的 priorityOf：
     *   0 可操作且是「真元素」（不是包着可点子节点的空壳容器）
     *   1 可操作但是容器
     *   2 有操作但当前不可见
     *   3 可操作但被禁用
     *   4 纯展示且可见
     *   5 纯展示且不可见
     */
    private fun priorityOf(n: NodeRow): Int {
        val interactive = n.clickable || n.editable
        if (!interactive) return if (n.visible) 4 else 5
        if (!n.enabled) return 3
        if (!n.visible) return 2
        // 「空壳容器」判定：自己可点、但没有文本也没有资源 id —— 多半只是包着一堆子元素
        val shell = n.name.isEmpty() && n.resId.isEmpty()
        return if (shell) 1 else 0
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
        //
        // ⚠️ DisplayManager 必须用【调用进程自己的包名】构造。
        //
        // 这个进程是 Shizuku 以 shell uid(2000) 起的，但传进来的 Context 是 CCM 的
        // （packageName=com.ccm.app，uid 10349）。Android 16 的 system_server 新增了校验：
        //   SecurityException: packageName must match the calling uid
        // 因为 DisplayManager 会把 Context 的包名一起透传给 IDisplayManager.createVirtualDisplay。
        //
        // 修法：查当前进程 uid 对应的包名（shell 是 com.android.shell），用它建一个 Context，
        // 拿到的 DisplayManager 才会带对包名。实测这是 Android 16 上建 TRUSTED 虚拟屏的硬要求。
        val dm = displayManagerForSelf(context)
        display = dm.createVirtualDisplay("CCMVirtualDisplay", w, h, dpi, reader?.surface, 1545)
    }

    /**
     * 拿一个「包名与当前进程 uid 匹配」的 DisplayManager。
     *
     * Android 16 的 system_server 在建虚拟屏时会校验
     * `packageName must match the calling uid`。
     * DisplayManager 从哪个 Context 取，就把那个 Context 的包名传下去 ——
     * 所以直接传 CCM 的 Context（包名 com.ccm.app）在 shell 进程里必定被拒。
     *
     * 这里反过来：先查当前 uid 有哪个包，再用它 createPackageContext。
     * shell uid 对应 com.android.shell，正好是系统允许建虚拟屏的身份。
     * 查不到就退回传入的 context（至少不会崩，报错也更清楚）。
     */
    private fun displayManagerForSelf(context: Context): DisplayManager {
        val fallback = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return try {
            val pkgs = context.packageManager.getPackagesForUid(android.os.Process.myUid())
            val selfPkg = pkgs?.firstOrNull() ?: return fallback
            val selfCtx = context.createPackageContext(selfPkg, 0)
            selfCtx.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: fallback
        } catch (_: Throwable) { fallback }
    }
}
