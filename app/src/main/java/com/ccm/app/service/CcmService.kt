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
 * CCM 核心服务 —— 前台服务 + 本地 HTTP 桥接服务器。
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
        private const val CHANNEL_ID = "ccm_service"
        private const val NOTIF_ID = 1001
        const val BRIDGE_PORT = 3457

        @Volatile
        var isRunning = false
            private set
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
        bridge = NativeBridge(this)
        rootfsManager = RootfsManager(this)
        prootRuntime = ProotRuntime(this)

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
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

                // 读请求行
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]

                // 读 headers（找 Content-Length）
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                // 读 body
                //
                // ⚠️ Content-Length 是**字节数**，而 Reader.read 按**字符**算。
                // 直接 CharArray(contentLength) 读中文会少读（一个汉字 3 字节但算 1 字符），
                // 导致 JSON 截断解析失败。所以这里按字节读再解码。
                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    val input = s.getInputStream()
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                val response = route(method, path, body)
                writeResponse(s.getOutputStream(), response)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "连接处理失败: ${t.message}")
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
                Log.i(TAG, "Node 已在运行 (pid=${p.pid()})")
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
            return false
        }

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
                CHANNEL_ID, "CCM 服务", NotificationManager.IMPORTANCE_LOW
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
            .setContentTitle("CCM")
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
