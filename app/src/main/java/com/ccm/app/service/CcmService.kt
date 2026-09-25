package com.ccm.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.ccm.app.MainActivity
import com.ccm.app.bridge.NativeBridge
import com.ccm.app.runtime.ProotRuntime
import com.ccm.app.runtime.RootfsManager
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Claude Code Mobile 核心服务 —— 前台服务 + 本地 HTTP 桥接服务器。
 *
 * 【职责】
 * 1. 前台服务：让进程常驻（替代 Termux 的 wake-lock + 静音音频那套 hack）
 * 2. HTTP 桥接：监听 127.0.0.1:3457，给 Node 内核提供原生能力调用入口
 * 3. 生命周期：负责启动/停止 Node 进程（跑在 proot 里）
 *
 * 【为什么用前台服务】
 * Android 会杀后台进程。前台服务有常驻通知，系统不会杀。
 * 这是官方推荐的长任务方案，比"播放静音音频防止休眠"干净得多。
 *
 * 【端口约定】
 * 3456 — Node 侧的 web/server.mjs（React UI 连这个）
 * 3457 — Kotlin 侧的桥接服务器（Node 连这个调原生能力）
 */
class CcmService : Service() {

    companion object {
        private const val TAG = "CcmService"

        /**
         * 主线程 Handler。
         *
         * 【为什么需要】Shizuku 的 bindUserService 内部用 ServiceConnection 回调，
         * 而回调是投递到【主线程 Looper】的。桥服务器跑在 CachedThreadPool 的工作线程上，
         * 从工作线程直接调 bindUserService → 回调永远送不到 → CountDownLatch 白等。
         * 实测后果：phone.use 的首次调用静默卡死，还会把线程池线程逐个咬住，
         * 最后整个桥连 /ping 都不响应。
         */
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private const val CHANNEL_ID = "ccm_service"
        private const val NOTIF_ID = 1001
        const val BRIDGE_PORT = 3457

        /**
         * 单次桥调用的硬超时。
         *
         * 【为什么需要】phone* 系列会经 Shizuku binder 进 phoneuse 进程执行，
         * 那边卡住时（实测：写文件到不可写路径）调用方会永远等下去。
         * 更糟的是处理线程被占住后，后续请求也排不上队 —— 整个桥连 /ping 都死。
         * 给个上限，卡住就返回错误，让调用方拿到明确失败而不是干等。
         *
         * 45 秒：要覆盖最慢的正常操作（副屏冷启动应用 + 首次建 UiAutomation）。
         */
        const val BRIDGE_CALL_TIMEOUT_MS = 45_000L

        @Volatile
        var isRunning = false
            private set

        /**
         * Node 内核的最近输出（环形缓冲，最多 [NODE_LOG_LIMIT] 行）。
         *
         * 【为什么要暴露给界面】内核启动失败的原因都在它的 stdout/stderr 里：
         *   · 模块缺失（MODULE_NOT_FOUND）
         *   · 语法错误（进程直接退出）
         *   · 端口被占（EADDRINUSE）
         *   · proot 层的路径问题
         * 但原来这些只进 logcat —— 用户在界面上只看到「Node 服务未启动」，
         * 完全不知道为什么，也无法自助排查（手机上看 logcat 门槛太高）。
         *
         * 现在缓存最近 200 行，ReadyScreen 提供「查看内核日志」入口。
         * 用 @Volatile + 同步块：写在线程池、读在 UI 线程。
         */
        private const val NODE_LOG_LIMIT = 200
        private val nodeLogLines = ArrayDeque<String>()

        @Volatile
        var nodeLogText: String = ""
            private set

        fun appendNodeLog(line: String) {
            synchronized(nodeLogLines) {
                nodeLogLines.addLast(line)
                while (nodeLogLines.size > NODE_LOG_LIMIT) nodeLogLines.removeFirst()
                nodeLogText = nodeLogLines.joinToString("\n")
            }
        }

        fun clearNodeLog() {
            synchronized(nodeLogLines) {
                nodeLogLines.clear()
                nodeLogText = ""
            }
        }
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private lateinit var bridge: NativeBridge
    private lateinit var rootfsManager: RootfsManager
    private lateinit var prootRuntime: ProotRuntime

    /** Node 进程（如果启动过） */
    private var nodeProcess: Process? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        rootfsManager = RootfsManager(this)
        // ⚠️ prootRuntime 必须在 bridge 之前初始化 —— bridge 的构造要拿它
        // （第一次改的时候顺序反了，Kotlin 直接报 "variable must be initialized"）
        prootRuntime = ProotRuntime(this)
        // 传 proot 实例：让 /runtime/status 能报 Node 状态（见 NativeBridge 的说明）
        bridge = NativeBridge(this, prootRuntime)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("服务运行中"))

        startBridgeServer()
        Log.i(TAG, "服务已启动，桥接端口 $BRIDGE_PORT")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_NODE -> startNode()
            ACTION_STOP_NODE -> stopNode()
            ACTION_UPDATE_NOTIF -> {
                val text = intent.getStringExtra("text") ?: "服务运行中"
                updateNotification(text)
            }
        }
        return START_STICKY   // 被杀后自动重启
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        stopNode()
        executor.shutdownNow()
        Log.i(TAG, "服务已停止")
        super.onDestroy()
    }

    // ═══════════════════════════════════════════════════
    //  桥接 HTTP 服务器
    // ═══════════════════════════════════════════════════

    private fun startBridgeServer() {
        executor.execute {
            try {
                val ss = ServerSocket(BRIDGE_PORT, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                Log.i(TAG, "桥接服务器监听 127.0.0.1:$BRIDGE_PORT")

                while (!ss.isClosed) {
                    val socket = try { ss.accept() } catch (t: Throwable) { break }
                    executor.execute { handleConnection(socket) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "桥接服务器异常", t)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30000

                // ⚠️ 这里刻意【不用 BufferedReader】。
                //
                // 两个坑都是实测踩出来的：
                //  1) BufferedReader 会预读 8KB —— headers 读完时 body 可能已被它吞进内部缓冲，
                //     之后再用 s.getInputStream() 读 body 什么都读不到，while 循环空转到超时。
                //     表现：POST 带 body 全部卡死，GET 和无 body 的 POST 正常。
                //  2) 改用 reader.readLine() 读 body 也不行 —— body 未必带尾部换行
                //     （curl -d '{}' 就不带），readLine 会一直等换行等到超时。
                // 所以按字节手工解析：先读 headers 到 CRLFCRLF，再按 Content-Length 精确读 body。
                val input = s.getInputStream()
                val headerBuf = java.io.ByteArrayOutputStream()
                var state = 0   // 匹配 \r\n\r\n 的进度
                while (state < 4) {
                    val b = input.read()
                    if (b == -1) return
                    headerBuf.write(b)
                    state = when {
                        state == 0 && b == '\r'.code -> 1
                        state == 1 && b == '\n'.code -> 2
                        state == 2 && b == '\r'.code -> 3
                        state == 3 && b == '\n'.code -> 4
                        b == '\r'.code -> 1
                        else -> 0
                    }
                    if (headerBuf.size() > 64 * 1024) return   // headers 过大，直接断
                }

                val headerText = String(headerBuf.toByteArray(), Charsets.ISO_8859_1)
                val lines = headerText.split("\r\n")
                val requestLine = lines.firstOrNull() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]

                var contentLength = 0
                for (line in lines) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                // 按【字节数】精确读 body —— Content-Length 就是字节数，这里正好对上
                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                // 【必须加超时】route() 里最终会走到 phone* 方法，而它们经 Shizuku
                // binder 调进 phoneuse 进程。那边任何一个调用卡住（实测写文件到不可写
                // 路径就会），这个工作线程就永久占住，客户端超时断开后再来的请求
                // 也一起排队 —— 表现是整个桥连 /ping 都不响应，只能重启 App。
                // 这里把 route 丢到独立线程并限时，卡住也只影响这一个请求。
                val response = runWithTimeout(BRIDGE_CALL_TIMEOUT_MS) { route(method, path, body) }
                writeResponse(s.getOutputStream(), response)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "连接处理失败: ${t.message}")
        }
    }

    /**
     * 限时执行。超时返回 JSON 错误（而不是抛异常，调用方拿到的仍是合法响应体）。
     */
    private fun runWithTimeout(timeoutMs: Long, block: () -> String): String {
        val pool = Executors.newSingleThreadExecutor()
        val future = pool.submit<String> { block() }
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            future.cancel(true)
            Log.w(TAG, "桥调用超时（${timeoutMs}ms）")
            """{"ok":false,"error":"调用超时（${timeoutMs / 1000}s）。可能是 Shizuku 掉线或 phone use 服务卡住，试试重启 App 或检查 Shizuku 是否在运行。"}"""
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            Log.w(TAG, "桥调用失败: ${cause.message}")
            """{"ok":false,"error":"${cause.message ?: "内部错误"}"}"""
        } finally {
            pool.shutdownNow()
        }
    }

    /** 路由分发 */
    private fun route(method: String, rawPath: String, body: String): String {
        val path = rawPath.substringBefore('?')

        return try {
            when {
                // 健康检查
                path == "/ping" -> """{"ok":true,"service":"ccm","port":$BRIDGE_PORT}"""

                // 原生能力统一入口
                path == "/native/call" -> {
                    val json = JSONObject(body.ifEmpty { "{}" })
                    val m = json.optString("method")
                    val params = json.optJSONObject("params") ?: JSONObject()
                    bridge.call(m, params)
                }

                // 运行时状态
                path == "/runtime/status" -> bridge.call("runtime.status", JSONObject())

                // 启动 Node
                path == "/runtime/start-node" -> {
                    startNode()
                    """{"ok":true,"message":"Node 启动中"}"""
                }

                // 停止 Node
                path == "/runtime/stop-node" -> {
                    stopNode()
                    """{"ok":true,"message":"Node 已停止"}"""
                }

                else -> """{"ok":false,"error":"未知路径: $path"}"""
            }
        } catch (t: Throwable) {
            """{"ok":false,"error":"${t.javaClass.simpleName}: ${t.message}"}"""
        }
    }

    private fun writeResponse(out: OutputStream, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ═══════════════════════════════════════════════════
    //  Node 进程管理
    // ═══════════════════════════════════════════════════

    /**
     * 启动 Node 内核（跑在 proot 里）。
     *
     * 命令形如：
     *   proot -r rootfs ... /usr/bin/node /root/ccm/web/server.mjs
     */
    fun startNode(): Boolean {
        // 检查已有进程是否真活着（可能是残留的僵尸引用）
        nodeProcess?.let { p ->
            if (p.isAlive) {
                // 注：Process.pid() 是 Java 9+ 的 API，Android 上没有。
                // 这里用 hashCode 做标识（够用于日志区分不同进程实例）。
                Log.i(TAG, "Node 已在运行 (ref=${System.identityHashCode(p)})")
                return true
            }
            Log.w(TAG, "发现已死进程引用，清理后重启")
            nodeProcess = null
        }
        if (!rootfsManager.isInstalled()) {
            Log.w(TAG, "rootfs 未安装")
            updateNotification("rootfs 未安装，请先在 App 里初始化")
            return false
        }
        val node = prootRuntime.nodePath()
        if (node == null) {
            Log.w(TAG, "rootfs 里没有 Node")
            updateNotification("环境里没有 Node，请先安装")
            appendNodeLog("❌ 环境里没有 Node —— 请先在「管理工具链」里装 Node.js")
            return false
        }
        // 新一轮启动：清掉上次的日志，避免混淆
        clearNodeLog()
        appendNodeLog("启动 Node：$node")

        // 内核入口：ccm-start.mjs（会自己拉起 web/server.mjs）
        val hasKernel = java.io.File(filesDir, "rootfs/root/ccm/ccm-start.mjs").exists()
        val script = if (hasKernel) "/root/ccm/ccm-start.mjs" else "web/server.mjs"
        Log.i(TAG, "启动脚本: $script")

        return try {
            // buildProcess 已处理：--rootfs=. / LD_PRELOAD 清除 / PROOT_L2S_DIR / LD_LIBRARY_PATH
            val pb = prootRuntime.buildProcess(
                workDir = "/root/ccm",
                command = listOf(node, script),
                extraEnv = mapOf(
                    "CCM_BRIDGE_PORT" to BRIDGE_PORT.toString(),
                    "CCM_WEB_PORT" to "3456",
                    "CCM_MODE" to "native",
                    "CCM_NATIVE_ADAPTERS" to "1",
                )
            )
            val p = pb.start()
            nodeProcess = p

            // 读输出到日志 + 进程退出时清理状态
            executor.execute {
                try {
                    val r = BufferedReader(InputStreamReader(p.inputStream))
                    while (true) {
                        val line = r.readLine() ?: break
                        Log.i("CcmNode", line)
                        // 同时进缓冲区，供界面「查看内核日志」显示
                        appendNodeLog(line)
                    }
                } catch (_: Throwable) {
                } finally {
                    // ⚠️ 必须清理，否则 nodeProcess != null 会让后续 startNode 误判"已在运行"
                    try {
                        val code = p.waitFor()
                        Log.i(TAG, "Node 进程退出，code=$code")
                        if (nodeProcess === p) {
                            nodeProcess = null
                            updateNotification("Node 已退出（code=$code）")
                        }
                    } catch (_: Throwable) {}
                }
            }

            updateNotification("Node 已启动")
            Log.i(TAG, "Node 已启动: $node")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "启动 Node 失败", t)
            updateNotification("Node 启动失败: ${t.message}")
            false
        }
    }

    fun stopNode() {
        val p = nodeProcess ?: return
        nodeProcess = null
        try {
            p.destroy()   // SIGTERM，proot 的 --kill-on-exit 会清理子进程
            // 给 2 秒优雅退出，超时强杀
            executor.execute {
                try {
                    if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        Log.w(TAG, "Node 未响应 SIGTERM，强杀")
                        p.destroyForcibly()
                    }
                } catch (_: Throwable) {}
            }
            Log.i(TAG, "Node 停止中")
        } catch (t: Throwable) {
            Log.w(TAG, "停止失败: ${t.message}")
        }
    }

    // ═══════════════════════════════════════════════════
    //  通知
    // ═══════════════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "Claude Code Mobile 服务", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Claude Code Mobile 运行状态"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Claude Code Mobile")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    fun updateNotification(text: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) {}
    }
}

const val ACTION_START_NODE = "com.ccm.app.START_NODE"
const val ACTION_STOP_NODE = "com.ccm.app.STOP_NODE"
const val ACTION_UPDATE_NOTIF = "com.ccm.app.UPDATE_NOTIF"
