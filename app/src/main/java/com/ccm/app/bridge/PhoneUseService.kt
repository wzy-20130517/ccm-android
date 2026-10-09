package com.ccm.app.bridge

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
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

    /**
     * 节点名最长输出。
     *
     * 【为什么保头也保尾】URL 查询参数、订单号、取件码都长在尾巴上 ——
     * agent-mobile-use 有实测事故：一条聊天消息的链接结尾是 ...?orderId=xyz，
     * 按 140 从头部截断后 id 丢了，而 truncated 还报 0（它以为没截）。
     * 所以截断必须在**中间**标注，两头都留。
     *
     * 4000 是灾难墙上限而非常规预算：实测 8 个富文本界面最长真实字段 311 字符，
     * 正常内容碰不到，只有整章小说/日志页那种病态节点才会被截。
     */
    private val maxFieldChars = 4000
    private val fieldTailChars = 160
    private val fieldHeadChars = maxFieldChars - fieldTailChars - 22

    /** 超长时保头 + 保尾，中间标注丢了多少字符。 */
    private fun clip(s: String): String {
        if (s.length <= maxFieldChars) return s
        val dropped = s.length - fieldHeadChars - fieldTailChars
        return s.substring(0, fieldHeadChars) + "...[cut:$dropped]..." +
            s.substring(s.length - fieldTailChars)
    }

    private class RefEntry(
        val cx: Int,
        val cy: Int,
        @Volatile var raw: android.view.accessibility.AccessibilityNodeInfo?,
        val editable: Boolean,
    )

    constructor()

    /**
     * 应用 Context（2026-10-06 加字段）。
     *
     * 原来只在构造函数参数里可见，targetScreenSize() 这类**构造函数之外**
     * 的方法用不到 —— 编译报 Unresolved reference: context。
     */
    @Volatile private var appContext: Context? = null

    @Keep
    constructor(context: Context) {
        appContext = context
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

    // ══════════════════════════════════════════════════════════════
    //  操作目标屏（2026-10-06 加，对齐 CLI 的 phoneMode）
    // ══════════════════════════════════════════════════════════════
    //
    // CLI 有两层模式语义（core/tools/tools-phone.mjs:55）：
    //   偏好（持久）: 'foreground' | 'background' | 'ask' | null
    //   本次生效值 : 'foreground' | 'background' | 'idle'
    //
    // APK 侧对应关系：
    //   foreground → 目标屏 = 0（主屏，用户看得见）
    //   background → 目标屏 = 副屏 displayId（默认，静默）
    //
    // 所有操作点（dump/tap/swipe/type/app/scroll）都改读 targetDisplayId()。
    // 副屏没建时 background 会报明确错误（而不是悄悄操作主屏）。

    /** 0 = 主屏；-1 = 副屏（默认）。 */
    @Volatile private var targetDisplay: Int = -1

    override fun setTargetDisplay(target: Int) {
        targetDisplay = if (target == 0) 0 else -1
    }

    override fun targetDisplayId(): Int {
        return if (targetDisplay == 0) 0 else displayId()
    }

    /**
     * 目标屏的尺寸（宽, 高）。
     *
     * 主屏模式从 WindowManager 现取（用户可能改过显示设置/旋转）；
     * 副屏模式用建屏时记下的 dispW/dispH（那是权威值）。
     */
    private fun targetScreenSize(): Pair<Int, Int> {
        if (targetDisplay != 0) return dispW to dispH
        return try {
            val c = appContext ?: return dispW to dispH
            val wm = c.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } catch (_: Throwable) {
            dispW to dispH   // 兜底：至少不比原来差
        }
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

    /**
     * 长按（2026-10-06 加）。
     *
     * Android 的 `input` 命令没有 longpress 子命令 —— 用 swipe 模拟：
     * 起点终点相同 + 持续 durationMs（系统按按住时长判定长按）。
     * 默认 600ms 是实测值：短于 500ms 系统当单击，太长（>1500ms）在
     * 部分 ROM 上会被当成拖拽起始。
     */
    override fun longPress(x: Int, y: Int, durationMs: Int): Boolean {
        val d = if (durationMs in 300..5000) durationMs else 600
        return input("swipe", x.toString(), y.toString(), x.toString(), y.toString(), d.toString())
    }

    override fun tapRef(ref: String): Boolean {
        val e = synchronized(refLock) { refTable[ref] } ?: return false
        return tap(e.cx, e.cy)
    }

    /** 取节点中心坐标（不点击）—— 长按用，见 AIDL 注释。 */
    override fun tapRefAt(ref: String): IntArray {
        val e = synchronized(refLock) { refTable[ref] } ?: return IntArray(0)
        return intArrayOf(e.cx, e.cy)
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        input("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString(), durationMs.toString())

    override fun swipeDir(direction: String, durationMs: Int): Boolean {
        // 【2026-10-06】尺寸要按**目标屏**取 —— 主屏和副屏分辨率可能不同
        // （副屏是建屏时按主屏尺寸建的，但用户可能改过显示设置）。
        // 原来写死 dispW/dispH（副屏尺寸），前台模式下算出的坐标会偏。
        val (w, h) = targetScreenSize()
        if (w <= 0 || h <= 0) return false
        val cx = w / 2
        val cy = h / 2
        val d = h / 4
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

    /**
     * 应用中文名缓存（pkg → label）。
     *
     * 内存级即可：服务是常驻进程，label 本身由 packageManager 即时读（毫秒级，
     * 不像 CLI 要走 aapt 解析 APK），缓存只为 list labels:true 的批量展示。
     */
    private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    override fun app(action: String, pkg: String, filter: String): String {
        return try {
            when (action.lowercase()) {
                "list", "list_labels" -> {
                    // CLI 同款语义（tools-phone.mjs T:1302-1308）：
                    // 默认只列第三方（-3）用户应用；filter 非空时查全部（含系统应用）——
                    // 全量 200+ 行会淹掉有用信息，系统应用只在明确过滤时出现。
                    val flag = if (filter.isEmpty()) " -3" else ""
                    val out = runShell("pm list packages$flag 2>/dev/null", 15000)
                    val body = out.substringAfter('\n', "")
                    val pkgs = body.split('\n')
                        .map { it.removePrefix("package:").trim() }
                        .filter {
                            it.isNotEmpty() && (filter.isEmpty() ||
                                it.contains(filter, ignoreCase = true) ||
                                // labels 模式下 filter 也匹配已缓存的中文名（CLI T:1327-1330）
                                (action == "list_labels" &&
                                    labelCache[it]?.contains(filter, ignoreCase = true) == true))
                        }
                        .sorted()
                        .take(500)
                    org.json.JSONObject().apply {
                        put("ok", true)
                        put("count", pkgs.size)
                        put("apps", org.json.JSONArray(pkgs))
                        if (action == "list_labels") {
                            // 只回**缓存里已有**的中文名，不做批量扫描 ——
                            // 要某个应用名用 action=label 按需读（读完自动进缓存）。
                            val lm = org.json.JSONObject()
                            for ((k, v) in labelCache) lm.put(k, v)
                            put("labels", lm)
                        }
                    }.toString()
                }
                "label" -> {
                    if (pkg.isEmpty()) return errJson("缺少 package")
                    labelCache[pkg]?.let {
                        return org.json.JSONObject().apply {
                            put("ok", true)
                            put("label", it)
                            put("cached", true)
                        }.toString()
                    }
                    // APK 就是 Android 应用本身 —— packageManager 直接读资源里的 label，
                    // 不需要 CLI 那套「复制 APK 到中立区 + aapt 解析」的绕行。
                    val label = try {
                        val pm = appContext?.packageManager
                        val info = pm?.getApplicationInfo(pkg, 0)
                        if (pm != null && info != null) pm.getApplicationLabel(info).toString() else null
                    } catch (_: Throwable) { null }
                    if (label.isNullOrEmpty()) {
                        return org.json.JSONObject().apply {
                            put("ok", false)
                            put("error", "读不到中文名（APK 可能被加固或不存在）")
                        }.toString()
                    }
                    labelCache[pkg] = label
                    org.json.JSONObject().apply {
                        put("ok", true)
                        put("label", label)
                        put("cached", false)
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
                        if (p.isEmpty()) put("error", "未获取到前台应用")
                    }.toString()
                }
                "launch" -> {
                    if (pkg.isEmpty()) return errJson("缺少 package")
                    launchOnDisplay(pkg)
                }
                "stop" -> {
                    if (pkg.isEmpty()) return errJson("缺少 package")
                    val out = runShell("am force-stop $pkg", 15000)
                    val exit = out.substringBefore('\n').trim().toIntOrNull() ?: -1
                    if (exit != 0) {
                        return errJson("停止失败：exit=$exit ${out.substringAfter('\n').take(200)}")
                    }
                    org.json.JSONObject().apply {
                        put("ok", true)
                        put("message", "已停止 $pkg")
                    }.toString()
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
        // 目标屏（前台=主屏 0 / 后台=副屏）—— 见 setTargetDisplay 注释
        val id = targetDisplayId()
        // 【2026-10-09】屏幕名按目标屏动态取 —— 原来全文硬编码「副屏」，
        // 前台模式下失败也报「在副屏启动失败」，把 Agent 误导去
        // am start --display <副屏id> 自己绕路（实测踩过）。
        val screenName = if (id == 0) "主屏" else "副屏"
        if (id < 0) return errJson("目标屏未就绪（$screenName 模式需要虚拟屏；切前台模式可解除）")

        // 查这个包在哪个 display 的 stack 里（RootTask 列表）
        val stack = findStackOfPackage(pkg)
        if (stack != null) {
            val (stackId, stackDisplay) = stack
            if (stackDisplay == id) {
                return """{"ok":true,"message":"$pkg 已在$screenName运行","display_id":$id,"stack_id":$stackId,"already":true}"""
            }
            // 在别的屏 → 搬过来
            val mv = runShell("cmd activity display move-stack $stackId $id 2>&1; echo \"__exit=$?\"", 20000)
            val mb = mv.substringAfter('\n')
            val mExit = Regex("""__exit=(\d+)""").find(mb)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (mExit == 0) {
                return """{"ok":true,"message":"已把 $pkg 从 display $stackDisplay 平滑移到$screenName（未重启）","display_id":$id,"stack_id":$stackId,"moved":true}"""
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
            return errJson("在$screenName启动 $pkg 失败：${firstErr.take(200)}")
        }
        return """{"ok":true,"message":"已在$screenName启动 $pkg","display_id":$id}"""
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

    private fun ui(): Pair<Class<*>, Any>? {
        uiAutomation?.let { return uiCls!! to it }
        return try {
            // ⚠️ 必须用**主 Looper**，不能自建 HandlerThread —— 这是从 AOSP 源码挖出来的硬要求。
            //
            // UiAutomationManager.UiAutomationService 的构造函数：
            //   final boolean isMainHandler = mainHandler.getLooper() == Looper.getMainLooper();
            //   if (IS_USERDEBUG || IS_ENG) Preconditions.checkArgument(isMainHandler,
            //           "UiAutomationService must use the main handler");
            // 而服务端 connectServiceUnknownThread() 是往这个 handler **post** 回调的 ——
            // 传别的 Looper 回调投递不到，客户端等到 CONNECT_TIMEOUT_MILLIS(5s) 抛异常。
            //
            // 官方入口也印证：UiAutomation(Context, IUiAutomationConnection) 内部就是
            //   this(getDisplayId(context), context.getMainLooper(), connection)
            //
            // 之前用自建 HandlerThread，表现是服务起不来 / 进程异常退出 —— 换成主 Looper 后正常。
            val mainLooper = android.os.Looper.getMainLooper()
                ?: throw IllegalStateException("没有主 Looper（UiAutomation 要求主线程）")
            val uac = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance()
            val cls = Class.forName("android.app.UiAutomation")
            val iuac = Class.forName("android.app.IUiAutomationConnection")
            val inst = cls.getConstructor(android.os.Looper::class.java, iuac).newInstance(mainLooper, uac)
            try { cls.getMethod("connect", Int::class.javaPrimitiveType).invoke(inst, 0) }
            catch (_: NoSuchMethodException) { cls.getMethod("connect").invoke(inst) }
            val info = android.accessibilityservice.AccessibilityServiceInfo()
            info.eventTypes = -1
            info.feedbackType = 16
            // FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | FLAG_REPORT_VIEW_IDS | FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.flags = 0x2 or 0x10 or 0x40
            cls.getMethod("setServiceInfo", info::class.java).invoke(inst, info)
            uiCls = cls
            uiAutomation = inst
            lastUiError = null
            cls to inst
        } catch (t: Throwable) {
            // 【2026-10-06 问题28 修复】原来静默吞掉 —— 用户报「phone use
            // 几乎无法使用」，但拿到的只是「UiAutomation 可能没连上」这种
            // 含糊提示，真正的异常（比如 SecurityException: packageName
            // must match the calling uid）完全看不到。
            // 现在记下来，dumpTree 会把它带出去。
            // 【2026-10-06 修】反射调用的异常要**解包** ——
            // InvocationTargetException 的 message 恒为 null，
            // 真正的原因在 cause 里。原来直接取 t.message，用户只看到
            // 「InvocationTargetException: null」这种无信息量的提示。
            lastUiError = describeReflectError(t)
            null
        }
    }

    /**
     * 把反射异常翻译成人能看的话。
     *
     * 层层解包（InvocationTargetException → cause → cause），
     * 一直到有 message 的那层。同时给常见根因加一句人话解释 ——
     * 这个函数的输出会直接展示给用户，不能只给类名。
     */
    private fun describeReflectError(t: Throwable): String {
        val chain = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            val msg = cur.message?.takeIf { it.isNotBlank() } ?: "(无消息)"
            if (chain.isNotEmpty()) chain.append(" ← ")
            chain.append("${cur.javaClass.simpleName}: $msg")
            cur = cur.cause
            depth++
        }
        // 常见根因的人话注解（看到就补一句，方便用户直接照做）
        val hint = when {
            chain.contains("SecurityException") && chain.contains("packageName") ->
                "\n→ 包名与调用 uid 不匹配（Shizuku 服务跑在 shell uid，需用 shell 包名）"
            chain.contains("hidden") || chain.contains("NoSuchMethod") ->
                "\n→ 反射的目标 API 在当前 Android 版本上不可用（hidden API 限制或签名变化）"
            chain.contains("Looper") || chain.contains("main handler") ->
                "\n→ UiAutomation 必须用主 Looper 构造"
            chain.contains("connect") || chain.contains("Timeout") ->
                "\n→ UiAutomation 连接超时（Shizuku 服务可能未就绪）"
            else -> ""
        }
        return chain.toString() + hint
    }

    /** 最近一次 UiAutomation 构造失败的原因（null = 没失败过）。 */
    private var lastUiError: String? = null

    private fun windowsOnDisplay(): List<*>? {
        val pair = ui() ?: run {
            lastWindowError = "ui() 返回 null（UiAutomation 构造失败：${lastUiError ?: "未知"}）"
            return null
        }
        val id = targetDisplayId()
        if (id < 0) {
            lastWindowError = "targetDisplayId() = $id（副屏未建；后台模式需要虚拟屏）"
            return null
        }

        // ── 主路径：getWindowsOnAllDisplays() ────────────────────────
        // 返回 SparseArray<displayId, List<AccessibilityWindowInfo>>。
        // 【2026-10-06 问题28】加诊断：原来整个 catch 吞异常 + 找不到 key
        // 也返回 null，外面只看到「UiAutomation 可能没连上」——
        // 实际可能是「map 里压根没有 display 130 这个 key」。
        try {
            val map = pair.first.getMethod("getWindowsOnAllDisplays").invoke(pair.second)
            if (map != null) {
                val size = map.javaClass.getMethod("size").invoke(map) as Int
                val keyAt = map.javaClass.getMethod("keyAt", Int::class.javaPrimitiveType)
                val valueAt = map.javaClass.getMethod("valueAt", Int::class.javaPrimitiveType)
                val keys = ArrayList<Int>()
                for (i in 0 until size) {
                    val k = keyAt.invoke(map, i) as Int
                    keys.add(k)
                    if (k != id) continue
                    val list = valueAt.invoke(map, i) ?: continue
                    val n = list.javaClass.getMethod("size").invoke(list) as Int
                    val get = list.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                    return (0 until n).map { get.invoke(list, it) }
                }
                lastWindowError = "getWindowsOnAllDisplays 里没有 display $id（现有 keys=$keys）"
            } else {
                lastWindowError = "getWindowsOnAllDisplays 返回 null"
            }
        } catch (t: Throwable) {
            lastWindowError = "getWindowsOnAllDisplays 抛异常：${t.javaClass.simpleName}: ${t.message}"
        }

        // ── 兜底路径：getWindows() ───────────────────────────────────
        // 部分机型/ROM 的 getWindowsOnAllDisplays 只返回主屏，
        // 这时用 getWindows()（返回当前 display 的窗口，不按 display 分组）。
        try {
            val list = pair.first.getMethod("getWindows").invoke(pair.second)
            if (list != null) {
                val n = list.javaClass.getMethod("size").invoke(list) as Int
                if (n > 0) {
                    val get = list.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                    lastWindowError = null
                    return (0 until n).map { get.invoke(list, it) }
                }
                lastWindowError = (lastWindowError ?: "") + "；getWindows() 返回空列表"
            } else {
                lastWindowError = (lastWindowError ?: "") + "；getWindows() 返回 null"
            }
        } catch (t: Throwable) {
            lastWindowError = (lastWindowError ?: "") + "；getWindows() 抛异常：${t.message}"
        }

        return null
    }

    /** 最近一次窗口枚举失败的原因（问题28：不再只给含糊提示）。 */
    private var lastWindowError: String? = null

    /**
     * 平铺式元素树。
     *
     * 输出格式（仿 agent-mobile-use）：
     *   第 1 行：状态 —— display/尺寸/count/has_more
     *   第 2 行：列头（说明每个字段是什么）
     *   之后：一行一元素，最可用的排前面
     */
    private fun collectTreeFlat(interactiveOnly: Boolean, maxNodes: Int, noSystemUi: Boolean): String {
        val id = targetDisplayId()
        if (id < 0) return "错误：副屏未就绪（display_id=$id；后台模式需要虚拟屏，或切前台模式）"

        var windows: List<*>? = null
        // ⚠️ 不能用 repeat(3) { ... return@repeat }：那是 continue 不是 break，
        // 拿到窗口后还会白 sleep 两次（各 350ms）。副屏刚建好时窗口要等一会儿才有，
        // 但一旦拿到就该立刻走。
        for (attempt in 0 until 3) {
            windows = windowsOnDisplay()
            if (!windows.isNullOrEmpty()) break
            Thread.sleep(350)
        }
        val ws = windows ?: return buildString {
            append("错误：拿不到窗口列表（display_id=$id）")
            append("\n原因：")
            append(lastWindowError ?: lastUiError ?: "UiAutomation 可能没连上")
            append("\n排查：1) Shizuku 是否在运行 2) CCM 是否已授权 3) 副屏是否就绪")
        }
        if (ws.isEmpty()) return "display=$id 副屏上没有窗口（应用还没起来）"

        val rows = ArrayList<NodeRow>()
        var idSeq = 0
        // 前台包名：取第一个非系统窗口的 root.packageName（UiAutomation 当前可读的最上层窗口）。
        // 拿不到就省略 —— 首行不带 pkg= 字段而已，不影响其余格式。
        var frontPkg: String? = null
        for (w in ws) {
            val root = try {
                w?.javaClass?.getMethod("getRoot")?.invoke(w) as? android.view.accessibility.AccessibilityNodeInfo
            } catch (_: Throwable) { null } ?: continue
            if (noSystemUi && isSystemWindow(w, root)) continue
            if (frontPkg == null) {
                frontPkg = try { root.packageName?.toString() } catch (_: Throwable) { null }
            }
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

        // 无损记账：整棵树有多少可点击元素、实际发了多少。
        // 只报「截断了」不够 —— 模型没法知道丢的是不是它能点的东西。
        var actTotal = 0; var actSent = 0
        for (r in rows) if (r.clickable || r.editable) actTotal++
        for (r in capped) if (r.clickable || r.editable) actSent++

        val sb = StringBuilder()
        sb.append("# display=").append(id).append(" ").append(dispW).append('x').append(dispH)
        if (!frontPkg.isNullOrBlank()) sb.append(" pkg=").append(frontPkg)
        sb.append(" count=").append(capped.size)
        if (total > capped.size) {
            sb.append(" truncated=1 total=").append(total)
            sb.append(" omitted=").append(total - capped.size)
            sb.append(" actionable_sent=").append(actSent).append('/').append(actTotal)
            if (actSent < actTotal) sb.append(" (!)")
        }
        sb.append('\n')
        sb.append("# 一行一元素：id type name x1,y1,x2,y2 flags")
        sb.append(" | flags: c=可点 e=可输入 s=可滚 k+=选中 k-=未选 off=禁用 focus=聚焦 dN=深度")
        sb.append('\n')
        sb.append("# 元素已按有用程度排序（能点、有名字的在前），从上往下读就是推荐顺序")
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
        val name = clip(if (text.isNotEmpty()) text else desc)

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
        val id = targetDisplayId()
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
