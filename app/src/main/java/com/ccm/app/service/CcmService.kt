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
import com.ccm.app.runtime.RootfsManager
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
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

    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private lateinit var bridge: NativeBridge
    private lateinit var rootfsManager: RootfsManager

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        rootfsManager = RootfsManager(this)
        bridge = NativeBridge(this)

        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("服务运行中"))

        startBridgeServer()
        Log.i(TAG, "服务已启动，桥接端口 $BRIDGE_PORT")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
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

const val ACTION_UPDATE_NOTIF = "com.ccm.app.UPDATE_NOTIF"
