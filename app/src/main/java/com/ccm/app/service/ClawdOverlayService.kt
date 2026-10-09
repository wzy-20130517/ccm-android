package com.ccm.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.ccm.app.core.clawd.ClawdAction
import com.ccm.app.core.clawd.ClawdBus
import com.ccm.app.core.clawd.ClawdPulse
import com.ccm.app.core.clawd.ClawdState

/**
 * Clawd 悬浮窗服务（2026-10-09）。
 *
 * ## 需求
 * 用户：「当 ccm 退到后台的时候，会有一个 Clawd（就是欢迎页那个吉祥物），
 * 那个 Clawd 有各种动画，按照 spinner 来。比如思考时，它做出思考的动作，
 * bash 或者其他需要写的动作时，它会做出打字的动作。如果它说了正文，
 * 会有一个消息气泡在它周围，气泡里就是它说的话。」
 *
 * ## 架构
 * ```
 * ChatSession.collectEvents
 *      ↓ (每个 AgentEvent)
 * ClawdBus.setState/appendBubble        ← core 层，零 Android 依赖
 *      ↓ (StateFlow)
 * ClawdOverlayService                    ← 本文件，订阅 Flow 切 WebView
 * ```
 *
 * ## 为什么用 WebView
 * 素材 SVG 自带 CSS @keyframes（打字有 arm-type / code-line 等动画组），
 * WebView 加载即自动播放。用 AndroidSVG 之类只能得静态图，动画要用 Kotlin
 * 重写 22 个文件上百组关键帧，不值。
 *
 * ## 生命周期
 * - [show] 由 MainActivity 在 onStop 时调用（App 退后台）
 * - [hide] 由 MainActivity 在 onStart 时调用（App 回前台）
 * - 用户可长按拖动，拖动位置记在 SharedPreferences
 * - 用户点击 → 打开 App（回到对话页）
 *
 * ## 权限
 * 需要 SYSTEM_ALERT_WINDOW（悬浮窗权限），由 MainActivity 引导申请。
 * 没权限时 [show] 静默失败（记日志），不弹崩溃。
 */
class ClawdOverlayService : Service() {

    companion object {
        private const val TAG = "ClawdOverlay"

        /** 悬浮窗显示的开关（Activity 生命周期驱动）。 */
        @Volatile private var instance: ClawdOverlayService? = null

        /** 悬浮窗是否已成功挂到 WindowManager 上。 */
        fun isShowing(): Boolean = instance?.view != null

        /** App 退后台 → 显示悬浮窗（需已授权，否则静默跳过）。 */
        fun show(context: Context) {
            if (instance?.view != null) return          // 已在显示
            if (!canDrawOverlays(context)) {
                Log.i(TAG, "无悬浮窗权限，跳过显示")
                return
            }
            try {
                val i = Intent(context, ClawdOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "启动悬浮窗服务失败: ${t.message}")
            }
        }

        /** App 回前台 → 隐藏悬浮窗。 */
        fun hide(context: Context) {
            try {
                context.stopService(Intent(context, ClawdOverlayService::class.java))
            } catch (t: Throwable) {
                Log.w(TAG, "停止悬浮窗服务失败: ${t.message}")
            }
        }

        /** 是否有悬浮窗权限（Android 6+ 需显式检查）。 */
        fun canDrawOverlays(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                android.provider.Settings.canDrawOverlays(context)
            } else true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var view: WebView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null

    /** 页面是否加载完成（未完成时 evaluateJavascript 会丢）。 */
    private var pageReady = false

    /** 最近一次下发的状态/气泡（页面就绪后补发）。 */
    private var pendingSvg: String? = null
    private var pendingBubble: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 前台服务通知：Android 8+ 起 startForegroundService 后必须 5 秒内
        // 调 startForeground，否则 ANR。用「后台运行中」的静默通知占位
        //（用户要的是「看不见的吉祥物」，不是多一条通知）。
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = android.app.NotificationChannel(
                    CHANNEL_ID, "后台悬浮窗", android.app.NotificationManager.IMPORTANCE_MIN,
                )
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(this)
            }
                .setContentTitle("CCM 后台运行中")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(android.app.Notification.PRIORITY_MIN)
                .build()
            startForeground(NOTIF_ID, notif)
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground 失败: ${t.message}")
        }
        attachView()
        observeBus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (view == null) attachView()
        return START_NOT_STICKY   // 被系统杀掉不必自动重启（Activity 下次进后台会重新拉起）
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        try {
            view?.let { wm?.removeView(it) }
        } catch (t: Throwable) {
            Log.w(TAG, "移除悬浮窗失败: ${t.message}")
        }
        view = null
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════════════
    //  视图挂载
    // ══════════════════════════════════════════════════════════════

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun attachView() {
        if (view != null) return
        val w = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w

        val web = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // JS 必须开：overlay.html 的 setState/setBubble 就是通过
            // evaluateJavascript 注入的（Kotlin ↔ 页面通信全靠它）。
            settings.javaScriptEnabled = true
            // 读 assets/clawd/*.svg 需要（file:///android_asset/ 属于 file 协议）
            settings.allowFileAccess = true
            // 禁缩放：悬浮窗尺寸固定，缩放会让螃蟹抖
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            // 【与 MessageBubble 的 WebView 有意不同的一点】：
            // allowFileAccessFromFileURLs 保持**默认 true**（MessageBubble 关掉了它）。
            //
            // 原因：overlay.html 需要通过 `<img src="clawd-xxx.svg">` 加载同目录的
            // 22 个 SVG 素材。这个设置控制「file:// 页面里的 JS 能否访问其他
            // file:// 资源」，对 `<img>` 标签加载是否受限**未经实测**——
            // 保持默认值（true）是不冒风险的选择。
            //
            // 安全性为什么可控：
            //   · 页面是我们自己写的 overlay.html（在 assets 里，非用户输入）
            //   · 素材是固定的 22 个 SVG 文件（不是运行时生成的）
            //   · shouldInterceptRequest 已白名单化：只放行 /android_asset/
            //     下的 file 请求，其余（http/file 外链）一律空响应
            //   · JS 只用来 setState/setBubble，不 eval 任何外部内容
            //
            // 下面两条不影响 assets 内加载，纯收紧：
            settings.allowContentAccess = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.domStorageEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    pageReady = true
                    // 补发订阅期间累积的状态（页面加载通常几百毫秒，
                    // 这期间 Agent 可能已经切了好几次状态）
                    pendingSvg?.let { setSvg(it) }
                    if (pendingBubble.isNotEmpty()) setBubbleText(pendingBubble)
                }

                /** 只放行本包 assets 的 file 请求，其余（http/file 外链）一律空响应。 */
                override fun shouldInterceptRequest(
                    v: WebView,
                    request: android.webkit.WebResourceRequest,
                ): android.webkit.WebResourceResponse? {
                    val scheme = request.url.scheme?.lowercase()
                    return if (scheme == "file" && request.url.toString().contains("/android_asset/")) {
                        null   // 放行：overlay.html 与同目录的 .svg
                    } else {
                        android.webkit.WebResourceResponse(
                            "text/plain", "UTF-8",
                            java.io.ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                }

                /** 禁止离页跳转（悬浮窗不该把用户带走）。 */
                override fun shouldOverrideUrlLoading(
                    v: WebView,
                    request: android.webkit.WebResourceRequest,
                ): Boolean = !(request.url.scheme?.lowercase() == "file")
            }
            loadUrl("file:///android_asset/clawd/overlay.html")
        }

        // 尺寸：正方形 + 气泡空间。气泡在顶部，所以高度略大于宽度。
        val density = resources.displayMetrics.density
        val size = (SIZE_DP * density).toInt()
        val p = WindowManager.LayoutParams(
            size,
            (size * 1.35f).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // FLAG_NOT_FOCUSABLE：不抢输入焦点（否则用户打字会被悬浮窗吃掉）
            // FLAG_LAYOUT_NO_LIMITS：允许拖到屏幕边缘
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val saved = savedPosition()
            x = saved.first
            y = saved.second
        }
        params = p

        // 拖动 + 点击
        web.setOnTouchListener(DragListener(p, w))

        try {
            w.addView(web, p)
            view = web
        } catch (t: Throwable) {
            Log.w(TAG, "addView 失败（权限被撤销？）: ${t.message}")
            web.destroy()
        }
    }

    /**
     * 拖动 + 点击手势（2026-10-09 重做）。
     *
     * ## 变更历史
     * 初版：整个窗口都能接收触摸，点击 = 打开 App。
     * 问题（用户反馈）：「点某些地方会自动打开 ccm，但我又没有碰到 clawd」——
     * 窗口是 132×178dp，但螃蟹只占下半部分，上半部分是**气泡预留空间**。
     * 没有气泡时那片区域是透明的，却仍然吃触摸。
     *
     * ## 现在
     * 1. **碰撞箱缩小**：只有螃蟹所在的矩形区域接收触摸（[inHitBox]），
     *    其余区域 return false → 触摸穿透到下层应用。
     * 2. **点击 = 撒娇**（不再是打开 App）：随机播一个反应动画 + 随机台词。
     *    用户明确要求「不要让它点开 CCM」。
     * 3. **拖动 = react-drag 动画**：用户拖着它走时，螃蟹做出「被拖着走」
     *    的动作（用户要求「被拖动作改成用户拖动它时的动作」）。
     */
    private inner class DragListener(
        private val p: WindowManager.LayoutParams,
        private val w: WindowManager,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 【碰撞箱】触摸点不在螃蟹身上 → 不消费，让事件穿透
                    if (!inHitBox(v, e.x, e.y)) return false
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (kotlin.math.abs(dx) > TOUCH_SLOP || kotlin.math.abs(dy) > TOUCH_SLOP)) {
                        moved = true
                        // 开始拖动 → 播「被拖着走」动画（用户要求）
                        setSvg("clawd-react-drag.svg")
                        currentActionSvg = "clawd-react-drag.svg"
                    }
                    if (moved) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try { w.updateViewLayout(v, p) } catch (_: Throwable) {}
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        savePosition(p.x, p.y)
                        // 拖动结束 → 回落 Agent 状态动画
                        currentActionSvg = null
                        setSvg(ClawdBus.state.value.svg)
                    } else {
                        onPetTapped()
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        currentActionSvg = null
                        setSvg(ClawdBus.state.value.svg)
                    }
                    return true
                }
            }
            return false
        }

        /**
         * 触摸点是否落在螃蟹身上（碰撞箱）。
         *
         * 窗口布局（overlay.html）：
         * ```
         * ┌─────────────┐  ← 顶部 26%：气泡预留空间（透明，不该吃触摸）
         * │   (bubble)  │
         * ├─────────────┤  ← 下面 74%：螃蟹图片（SVG 500×500 等比缩放到窗口宽）
         * │    🦀       │     螃蟹视觉中心在窗口高度的 62% 处
         * └─────────────┘
         * ```
         *
         * 碰撞箱取「水平居中 72% 宽 × 纵向 42%~96%」—— 比螃蟹本身略大
         *（好点），但远小于整个窗口（不误触）。
         */
        private fun inHitBox(v: View, x: Float, y: Float): Boolean {
            val w0 = v.width.toFloat()
            val h0 = v.height.toFloat()
            if (w0 <= 0f || h0 <= 0f) return false
            val left = w0 * HITBOX_LEFT_RATIO
            val right = w0 * HITBOX_RIGHT_RATIO
            val top = h0 * HITBOX_TOP_RATIO
            val bottom = h0 * HITBOX_BOTTOM_RATIO
            return x in left..right && y in top..bottom
        }
    }

    /**
     * 点击螃蟹 → 随机反应 + 撒娇台词（2026-10-09）。
     *
     * 用户需求：「点击之后随机触发动作，并从词库里调一句话对主人撒娇什么的。」
     *
     * 原实现是「点击打开 App」—— 已删除。用户要的是一只**有性格的宠物**，
     * 不是一个快捷方式。
     */
    private fun onPetTapped() {
        try {
            // ① 随机反应动画
            val svg = com.ccm.app.core.clawd.ClawdTalk.randomReaction()
            setSvg(svg)
            currentActionSvg = svg
            // ② 随机撒娇台词
            setBubbleText(com.ccm.app.core.clawd.ClawdTalk.randomLine())
            // ③ 播一会儿回落到 Agent 状态
            scope.launch {
                delay(TAP_REACTION_HOLD_MS)
                if (currentActionSvg == svg) {
                    currentActionSvg = null
                    setSvg(ClawdBus.state.value.svg)
                    setBubbleText("")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "点击反应失败: ${t.message}")
        }
    }

    private fun savedPosition(): Pair<Int, Int> {
        val sp = getSharedPreferences(PREF, MODE_PRIVATE)
        return sp.getInt("x", DEFAULT_X_DP) to sp.getInt("y", DEFAULT_Y_DP)
    }

    private fun savePosition(x: Int, y: Int) {
        try {
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("x", x).putInt("y", y).apply()
        } catch (_: Throwable) {}
    }

    // ══════════════════════════════════════════════════════════════
    //  订阅事件总线
    // ══════════════════════════════════════════════════════════════

    private fun observeBus() {
        // 状态切换（切 SVG）
        scope.launch {
            ClawdBus.state.collect { st ->
                setSvg(st.svg)
                // 离开 SPEAKING 时清气泡（Bus 侧也会清，这里保证 UI 同步）
                if (st != ClawdState.SPEAKING) setBubbleText("")
            }
        }
        observeBubble()
        observeActions()
        // 脉冲事件（完成/失败动画播完自动回 IDLE）
        //
        // ⚠️ 用 collectLatest 而不是 collect：collect 是顺序的，
        // `delay(REACTION_HOLD_MS)` 会**挡住后续脉冲**（比如 ERROR 紧跟
        // COMPLETED 时，第二个要等第一个的 2.6 秒走完才处理）。
        scope.launch {
            ClawdBus.pulse.collectLatest { p ->
                when (p) {
                    ClawdPulse.COMPLETED, ClawdPulse.FAILED -> {
                        // Bus 已经切到 HAPPY/ERROR，这里只负责定时收回
                        delay(REACTION_HOLD_MS)
                        // running 仍为 true = 任务还在继续（工具往返中），
                        // 不收回 —— 下一个事件会自然切走状态
                        if (!ClawdBus.running.value) {
                            ClawdBus.setState(ClawdState.IDLE)
                        }
                    }
                    ClawdPulse.STOPPED -> ClawdBus.setState(ClawdState.IDLE)
                }
            }
        }
    }

    /**
     * 物理动作订阅（phone_use 深度适配，2026-10-09）。
     *
     * ## 用户需求
     * 「当 Agent 点击时，Clawd 会流畅地来到点击处摆个动作，滑动时，
     *   Clawd 也有相应动作。」
     *
     * ## 实现要点
     *
     * 1. **平滑移动**：用 ValueAnimator 把 WindowManager 的 x/y 从当前位置
     *    插值到目标位置（不是瞬移）。瞬移会让螃蟹"闪现"，看起来像 bug。
     *
     * 2. **移动的是窗口不是页面**：改变的是 [WindowManager.LayoutParams].x/y，
     *    螃蟹在页面内位置不变 —— 这样 SVG 动画不会因为页面重排而重启。
     *
     * 3. **坐标换算**：动作坐标是「目标点的屏幕像素」，窗口坐标要减去
     *    窗口尺寸的一半（让螃蟹中心对准目标点），再减去螃蟹在窗口内的偏移
     *    （螃蟹不在窗口正中 —— 气泡占顶部，见 [PET_CENTER_Y_RATIO]）。
     *
     * 4. **动作播完自动复位**：动作是临时的，播 [ACTION_HOLD_MS] 后回到
     *    当前 Agent 状态对应的动画（不能永远停在「探头看」）。
     *
     * 5. **副屏操作不带坐标**：`hasPosition == false` 时原地播动作。
     */
    private fun observeActions() {
        scope.launch {
            ClawdBus.actions.collectLatest { action ->
                // ① 播动作动画
                setSvg(svgForAction(action))
                // ② 有坐标就飘过去（副屏操作没有主屏坐标，不移动）
                if (action.hasPosition) {
                    flyTo(action.x, action.y)
                }
                // ③ 动作是临时的：播一会儿回落到当前 Agent 状态
                delay(ACTION_HOLD_MS)
                // 只有当前动画仍是「动作动画」时才回落 —— 若期间 Agent 切了
                // 状态（如开始输出正文），那个状态优先级更高，不要覆盖它。
                if (currentActionSvg != null) {
                    currentActionSvg = null
                    setSvg(ClawdBus.state.value.svg)
                }
            }
        }
    }

    /**
     * 动作 → SVG。
     *
     * 素材库没有相机/点击专用素材，用语义最近的替代：
     * - 点击 → 探头看（左右按落点定朝向）
     * - 滑动 → **搬运**（用户指定：「它滑动可以使用搬运」）——
     *   螃蟹扛着东西跟着滑，比「被拖」更贴合「Agent 在划屏」的语义
     * - 截图 → 放大镜（"看"的最近语义）
     * - 拖动螃蟹本身 → 走 [DragListener] 的 react-drag，不经过这里
     */
    private fun svgForAction(action: ClawdAction): String = when (action.type) {
        // 点击：探头看（左右按落点在屏幕哪半边决定朝向）
        ClawdAction.Type.TAP ->
            if (action.hasPosition && action.x > screenWidth() / 2) {
                "clawd-react-right.svg"
            } else {
                "clawd-react-left.svg"
            }
        // 滑动：扛着东西跟着滑（用户指定用搬运动作）
        ClawdAction.Type.SWIPE -> "clawd-working-carrying.svg"
        // 截图：举放大镜观察（素材无相机，这是「看」的最近语义）
        ClawdAction.Type.SCREENSHOT -> "clawd-working-debugger.svg"
        // 读元素树：左右张望
        ClawdAction.Type.SNAPSHOT -> "clawd-idle-look.svg"
        // 输入文字：打字
        ClawdAction.Type.TYPE_TEXT -> "clawd-working-typing.svg"
        // 启动应用：施法变出一个 App
        ClawdAction.Type.LAUNCH_APP -> "clawd-working-wizard.svg"
    }.also { currentActionSvg = it }

    /** 当前正在播的「动作动画」文件（null = 没有动作，显示 Agent 状态动画）。 */
    private var currentActionSvg: String? = null

    private fun screenWidth(): Int {
        return try {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
            metrics.widthPixels
        } catch (_: Throwable) { 1080 }
    }

    private fun screenHeight(): Int {
        return try {
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
            metrics.heightPixels
        } catch (_: Throwable) { 2400 }
    }

    /**
     * 平滑飞到目标点（螃蟹中心对准目标）。
     *
     * 用 ValueAnimator 而不是直接设值：瞬移会让螃蟹"闪现"，看起来像 bug。
     * 插值曲线用 Decelerate（先快后慢）—— 像真的"飞过去刹住"。
     */
    private fun flyTo(targetX: Int, targetY: Int) {
        val p = params ?: return
        val w = view ?: return
        val wmLocal = wm ?: return

        // 窗口中心要对准目标点 → 窗口左上角 = 目标 - 窗口尺寸/2
        // 但螃蟹不在窗口正中（顶部有气泡空间），所以纵向要补偿
        val winW = p.width
        val winH = p.height
        val destX = targetX - winW / 2
        val destY = targetY - (winH * PET_CENTER_Y_RATIO).toInt()

        // 夹取到屏幕内（别飞出去）
        val maxX = (screenWidth() - winW).coerceAtLeast(0)
        val maxY = (screenHeight() - winH).coerceAtLeast(0)
        val toX = destX.coerceIn(0, maxX)
        val toY = destY.coerceIn(0, maxY)

        val fromX = p.x
        val fromY = p.y
        if (fromX == toX && fromY == toY) return   // 已在目标点

        try {
            val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = FLY_DURATION_MS
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { a ->
                    val f = a.animatedFraction
                    p.x = (fromX + (toX - fromX) * f).toInt()
                    p.y = (fromY + (toY - fromY) * f).toInt()
                    try { wmLocal.updateViewLayout(w, p) } catch (_: Throwable) {}
                }
                // 动画结束落点可能因取整有 1~2px 偏差，校准一次
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        p.x = toX; p.y = toY
                        try { wmLocal.updateViewLayout(w, p) } catch (_: Throwable) {}
                    }
                })
            }
            anim.start()
        } catch (t: Throwable) {
            // 动画失败就直接瞬移（降级，不影响功能）
            p.x = toX; p.y = toY
            try { wmLocal.updateViewLayout(w, p) } catch (_: Throwable) {}
        }
    }

    /**
     * 气泡文本订阅 —— **立即显示 + 冷却期合并**的节流。
     *
     * ## 为什么不能直接 collectLatest { delay(300); inject() }
     *
     * 这是个陷阱写法（本文件初版就这么写的，已修）：
     * ```
     * collectLatest { text ->
     *     delay(300)          // ← 每来一个新值就取消重启
     *     setBubbleText(text)
     * }
     * ```
     * 正文是流式的（约每 20~50ms 一个 TextDelta），delay 永远走不完 →
     * **气泡一次都不会更新**，用户看到的是空白。
     *
     * ## 正确策略
     * - 距上次注入 ≥ 300ms：立即注入（首字延迟低，看起来是实时的）
     * - 冷却期内的更新：记下来，等冷却结束补发一次（不丢最后一帧）
     *
     * 这样注入频率被压到 ≤ 3.3 次/秒，同时保证用户总能看到最新的文字。
     */
    private fun observeBubble() {
        scope.launch {
            var lastInject = 0L
            var pending: String? = null
            var flushJob: kotlinx.coroutines.Job? = null

            ClawdBus.bubbleText.collect { text ->
                if (text.isEmpty()) {
                    flushJob?.cancel()
                    flushJob = null
                    pending = null
                    setBubbleText("")
                    return@collect
                }
                val now = System.currentTimeMillis()
                val elapsed = now - lastInject
                if (elapsed >= BUBBLE_THROTTLE_MS) {
                    // 冷却期已过 → 立即注入
                    lastInject = now
                    setBubbleText(text)
                } else {
                    // 冷却期内 → 记下最新值，等冷却结束时补发
                    pending = text
                    if (flushJob?.isActive != true) {
                        flushJob = scope.launch {
                            delay(BUBBLE_THROTTLE_MS - elapsed)
                            lastInject = System.currentTimeMillis()
                            pending?.let { setBubbleText(it) }
                            pending = null
                        }
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  WebView 注入
    // ══════════════════════════════════════════════════════════════

    private fun setSvg(file: String) {
        pendingSvg = file
        if (!pageReady) return
        // 用 JSON 转义防注入（文件名理论上安全，但状态字符串最终来自工具名，
        // 不排除有特殊字符；JSON 转义是最省的稳妥做法）
        val js = "window.setState(${quote(file)})"
        view?.evaluateJavascript(js, null)
    }

    private fun setBubbleText(text: String) {
        pendingBubble = text
        if (!pageReady) return
        val js = "window.setBubble(${quote(text)})"
        view?.evaluateJavascript(js, null)
    }

    /** 把字符串转成 JS 字面量（安全转义）。 */
    private fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")   // 防 </script> 之类的注入
                '>' -> sb.append("\\u003e")
                else -> if (c.code < 0x20) sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

private const val CHANNEL_ID = "ccm_clawd_overlay"
private const val NOTIF_ID = 0xC1A0
private const val PREF = "ccm_clawd_overlay"
private const val DEFAULT_X_DP = 24
private const val DEFAULT_Y_DP = 260
private const val TOUCH_SLOP = 12f
private const val BUBBLE_THROTTLE_MS = 300L
private const val REACTION_HOLD_MS = 2600L

/** 悬浮窗边长（dp）。132dp ≈ 屏幕上不到两指宽，不挡操作又不至于看不清动画。 */
private const val SIZE_DP = 132

/**
 * 螃蟹中心在窗口内的纵向比例（0 = 顶，1 = 底）。
 *
 * 窗口是 132×178dp（气泡占顶部），螃蟹实际画在下半部分。
 * 「飞到某个坐标」时要把窗口左上角算成 `目标 - 窗口尺寸 × 这个比例`，
 * 否则螃蟹会落在目标点上方一大截。
 *
 * 0.62 是实测值：窗口高 178dp，螃蟹视觉中心约在 110dp 处 → 110/178 ≈ 0.62。
 */
private const val PET_CENTER_Y_RATIO = 0.62f

/** 飞行动画时长（ms）。太快像瞬移，太慢跟不上连续操作。 */
private const val FLY_DURATION_MS = 320L

/** 动作动画停留时长（ms）—— 播完回落到当前 Agent 状态。 */
private const val ACTION_HOLD_MS = 1200L

/** 点击反应的停留时长（ms）—— 比工具动作长一点，让用户看清台词。 */
private const val TAP_REACTION_HOLD_MS = 2200L

// ══════════════════════════════════════════════════════════════════
//  碰撞箱（2026-10-09 加）
// ══════════════════════════════════════════════════════════════════
//
// 窗口是 132×178dp，但螃蟹只占下半部分（上半是气泡预留空间）。
// 没有气泡时上半区透明却仍吃触摸 → 用户点"空处"也触发点击反应。
//
// 现在只有这个矩形接收触摸（比例相对于窗口尺寸）：
//   横向 14%~86%（居中 72% 宽）
//   纵向 42%~96%（下半部分，避开气泡区）
//
// 为什么纵向到 96% 而不是 100%：底部留 4% 边距，避免和屏幕边缘手势冲突。

/** 碰撞箱左边界（窗口宽度比例）。 */
private const val HITBOX_LEFT_RATIO = 0.14f

/** 碰撞箱右边界（窗口宽度比例）。 */
private const val HITBOX_RIGHT_RATIO = 0.86f

/** 碰撞箱上边界（窗口高度比例）。 */
private const val HITBOX_TOP_RATIO = 0.42f

/** 碰撞箱下边界（窗口高度比例）。 */
private const val HITBOX_BOTTOM_RATIO = 0.96f
