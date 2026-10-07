package com.ccm.app.core.integrations

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * QQ 桥（APK 版）—— NapCat 上报接收 + 消息注入 + 回复发送。
 *
 * ══════════════════════════════════════════════════════════════════
 * 【2026-10-07 新建】移植自 CLI 的 core/integrations/qq-bridge.mjs
 * （1048 行）。**只搬协议对接层**，与 NapCat 怎么部署无关 ——
 * NapCat（或任何 OneBot v11 实现）跑在哪、怎么跑，桥不关心：
 * 它只做两件事：
 *   1. 监听 HTTP（默认 3000 端口）收 NapCat 的 POST 上报
 *   2. 往 NapCat 的 HTTP API（默认 http://127.0.0.1:5700）POST 发消息
 *
 * ══════════════════════════════════════════════════════════════════
 * ## 与 CLI 版的差异（如实记录）
 *
 * | 项 | CLI | APK |
 * |---|---|---|
 * | HTTP 服务器 | node http.createServer | ServerSocket 手写（CcmService 同款先例）|
 * | 发消息 | fetch | HttpURLConnection |
 * | 消息注入 | processInput（REPL 输入通道）| ChatSession.send（会话门面）|
 * | 回复收集 | 流式回调攒句子 | ChatSession 事件流订阅（TextDelta）|
 *
 * ## 安全边界（与 CLI 一致）
 * - **只有主人号的私聊**进会话，别人静默丢弃
 * - 群消息：主人 @ 我 → 进会话；其他人 @ → 只进缓存（QQRecall 回溯）
 * - QQ 侧控制词「停/打断/stop」→ 中止当前任务
 *
 * ## 生命周期
 * 由 AppGraph 持有。start() 开监听，stop() 关。
 * 配置存 files/qq-config.json（字段名对齐 CLI，方便用户迁移）。
 */
class QqBridge(
    /** 应用私有目录（图片/文件落盘用）。 */
    private val filesDir: File,
    /** 发消息的目标 API 地址（NapCat 的 HTTP 端口）。 */
    var napcatApi: String = "http://127.0.0.1:5700",
    /** 监听端口（NapCat 上报配到这里）。 */
    var port: Int = 3000,
    /** 主人 QQ 号（只有他的私聊会进会话）。 */
    var owner: String = "",
    /** 消息回调：桥收到「该进会话」的消息时调用（AppGraph 注入 → ChatSession.send）。 */
    private val onMessage: (QqMessage) -> Unit,
    /** 中止当前任务（QQ 发「停」时调用；AppGraph 注入）。 */
    private val onAbort: () -> Unit,
    /** 当前是否在跑（决定「停」有没有意义）。 */
    private val isRunning: () -> Boolean,
    /** 作用域（图片下载等异步操作用）。 */
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "QqBridge"

        /** 群消息缓存上限（QQRecall 回溯用）。 */
        private const val GROUP_CACHE_MAX = 60

        /** 控制词：中止当前任务。 */
        private val STOP_WORDS = setOf("停", "停止", "打断", "stop", "abort")
    }

    /** 一条待注入的 QQ 消息。 */
    data class QqMessage(
        val userId: String,
        val groupId: String?,
        val nickname: String,
        /** 纯文本正文（已剥离 CQ 码 / @ 段）。 */
        val text: String,
        /** 本地已下载的图片路径。 */
        val imagePaths: List<String> = emptyList(),
        /** 本地已下载的文件路径。 */
        val filePaths: List<String> = emptyList(),
        /** 群消息专用：是否 @ 了我。 */
        val atMe: Boolean = false,
    )

    /** 群消息缓存条目（QQRecall 用）。 */
    data class GroupMsg(
        val groupId: String,
        val groupName: String,
        val userId: String,
        val nickname: String,
        val message: String,
        val atMe: Boolean,
        val at: Long,
    )

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var startedAt: Long = 0
        private set

    /** 放行模式：群内任何人 @ 都能唤醒（/qq open on）。默认关。 */
    @Volatile
    var openMode: Boolean = false

    /** 任意新消息都打断当前任务（/qq interrupt on）。默认关。 */
    @Volatile
    var allowInterrupt: Boolean = false

    /** 待处理消息队列（busy 时不打断，排队执行）。 */
    private val queue = ConcurrentLinkedQueue<QqMessage>()

    /** 群消息缓存（最近 GROUP_CACHE_MAX 条）。 */
    val groupCache = mutableListOf<GroupMsg>()

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    /** 上次发送失败原因（/qq status 展示）。 */
    @Volatile
    var lastError: String? = null
        private set

    // ══════════════════════════════════════════════════════════════
    //  生命周期
    // ══════════════════════════════════════════════════════════════

    /**
     * 启动监听。
     *
     * @return null = 成功；非 null = 失败原因（端口占用等）
     */
    fun start(): String? {
        if (running) return null
        return try {
            val ss = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            running = true
            startedAt = System.currentTimeMillis()
            executor.execute { acceptLoop(ss) }
            Log.i(TAG, "QQ 桥已监听 127.0.0.1:$port")
            null
        } catch (e: Throwable) {
            running = false
            val msg = "监听 $port 失败：${e.message?.take(80)}（端口被占用？先 /qq off 或换端口）"
            Log.w(TAG, msg)
            msg
        }
    }

    /** 停止监听。 */
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
        queue.clear()
        Log.i(TAG, "QQ 桥已停止")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try { ss.accept() } catch (_: Throwable) { break }
            executor.execute { handleConnection(socket) }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  接收：手写 HTTP（ServerSocket 层）
    // ══════════════════════════════════════════════════════════════

    /**
     * 处理一条 HTTP 连接。
     *
     * ⚠️ **必须用字节流**（2026-10-07 实测踩坑）：
     * 最初用 BufferedReader（字符流）读 body —— `Content-Length` 是
     * **字节数**，而 read(CharArray) 读的是**字符数**。body 含中文时
     * （UTF-8 每字 3 字节）字符数 < 字节数，按 contentLength 循环读
     * 字符永远读不够 → 卡死在 read → 客户端超时断开 → 「Broken pipe」。
     * 现在：header 按行读（ASCII 安全），body 按字节精确读满。
     */
    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val rawIn = socket.getInputStream()

            /** 按行读 header（读字节到 \n，ASCII 安全）。 */
            fun readHeaderLine(): String {
                val sb = StringBuilder()
                while (true) {
                    val b = rawIn.read()
                    if (b < 0) break
                    if (b == '\n'.code) break
                    if (b != '\r'.code) sb.append(b.toChar())
                }
                return sb.toString()
            }

            val requestLine = readHeaderLine()
            if (requestLine.isBlank()) return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: ""

            // 读 headers 拿 Content-Length
            var contentLength = 0
            while (true) {
                val line = readHeaderLine()
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            // GET → 身份端点（对齐 CLI：多端抢端口时能看出谁占着）
            if (method == "GET") {
                val body = JSONObject().apply {
                    put("ok", true)
                    put("service", "claude-code-mobile-qq-bridge")
                    put("endpoint", "ccm")
                    put("port", port)
                    put("owner", owner)
                    put("uptime", (System.currentTimeMillis() - startedAt) / 1000)
                }.toString()
                respond(socket, 200, body)
                return
            }

            // POST → NapCat 上报（body 按字节精确读满）
            if (method == "POST" && contentLength > 0) {
                val buf = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = rawIn.read(buf, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }
                val body = String(buf, 0, read, Charsets.UTF_8)
                respond(socket, 200, """{"ok":true}""")
                try {
                    handleEvent(body)
                } catch (e: Throwable) {
                    Log.w(TAG, "上报处理失败: ${e.message?.take(100)}")
                }
                return
            }

            respond(socket, 200, """{"ok":true}""")
        } catch (e: Throwable) {
            Log.w(TAG, "连接处理失败: ${e.message?.take(80)}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun respond(socket: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out: OutputStream = socket.getOutputStream()
        val header = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    // ══════════════════════════════════════════════════════════════
    //  解析：OneBot v11 事件
    // ══════════════════════════════════════════════════════════════

    private fun handleEvent(body: String) {
        val root = try { JSONObject(body) } catch (_: Throwable) { return }
        val postType = root.optString("post_type", "")
        if (postType != "message") return

        val msgType = root.optString("message_type", "")
        val userId = root.optString("user_id", "")
        if (userId.isBlank()) return

        // 解析消息段
        val parsed = parseMessage(root.opt("message"))
        val text = parsed.first
        val imageUrls = parsed.second
        val fileInfos = parsed.third

        val sender = root.optJSONObject("sender")
        val nickname = sender?.optString("card", "")?.takeIf { it.isNotBlank() }
            ?: sender?.optString("nickname", "") ?: ""

        if (msgType == "private") {
            // 私聊：只有主人能用，别人静默丢弃（对齐 CLI）
            if (owner.isBlank() || userId != owner) return
            if (text.isBlank() && imageUrls.isEmpty() && fileInfos.isEmpty()) return

            // ── QQ 侧控制指令拦截（不喂给 agent）──
            val trimmed = text.trim()
            if (trimmed.lowercase() in STOP_WORDS) {
                handleStopCommand()
                return
            }

            // 图片/文件下载（异步，下载完再注入）
            scope.launch(Dispatchers.IO) {
                val images = downloadImages(imageUrls)
                val files = downloadFiles(fileInfos)
                enqueueOrInterrupt(
                    QqMessage(userId, null, nickname, text, images, files, atMe = true),
                )
            }
            return
        }

        if (msgType == "group") {
            val groupId = root.optString("group_id", "")
            if (groupId.isBlank()) return
            val selfId = root.optString("self_id", "")
            val atMe = isAtMe(root.opt("message"), selfId)

            // 群消息：主人在群里 @ 我 → 进会话（回复发回群）
            // 放行模式：任何人 @ 都进（带来源标注）
            val fromOwner = owner.isNotBlank() && userId == owner
            if (atMe && (fromOwner || openMode)) {
                scope.launch(Dispatchers.IO) {
                    val images = downloadImages(imageUrls)
                    val files = downloadFiles(fileInfos)
                    val label = if (fromOwner) nickname else "放行模式｜非主人消息，来自 $nickname"
                    enqueueOrInterrupt(
                        QqMessage(userId, groupId, label, text, images, files, atMe = true),
                    )
                }
                return
            }

            // 其他群消息 → 只进缓存（QQRecall 用）
            if (text.isNotBlank() || imageUrls.isNotEmpty()) {
                synchronized(groupCache) {
                    groupCache.add(GroupMsg(groupId, "", userId, nickname, text, atMe, System.currentTimeMillis()))
                    while (groupCache.size > GROUP_CACHE_MAX) groupCache.removeAt(0)
                }
            }
        }
    }

    /** 解析消息段 → (纯文本, 图片URL, 文件列表[(url,name)])。 */
    private fun parseMessage(el: Any?): Triple<String, List<String>, List<Pair<String, String>>> {
        val textSb = StringBuilder()
        val images = mutableListOf<String>()
        val files = mutableListOf<Pair<String, String>>()

        when (el) {
            is JSONArray -> {
                for (i in 0 until el.length()) {
                    val obj = el.optJSONObject(i) ?: continue
                    when (obj.optString("type", "")) {
                        "text" -> textSb.append(obj.optJSONObject("data")?.optString("text", "") ?: "")
                        "image" -> {
                            val d = obj.optJSONObject("data")
                            val url = d?.optString("url", "")?.takeIf { it.isNotBlank() }
                                ?: d?.optString("file", "")
                            url?.takeIf { it.isNotBlank() }?.let { images.add(it) }
                        }
                        "file" -> {
                            val d = obj.optJSONObject("data")
                            val url = d?.optString("url", "") ?: ""
                            val name = d?.optString("name", "")?.takeIf { it.isNotBlank() }
                                ?: d?.optString("file", "") ?: "file"
                            if (url.isNotBlank()) files.add(url to name)
                        }
                        "at" -> { /* @ 标记剥离 */ }
                    }
                }
            }
            is String -> {
                // CQ 码字符串格式：剥离 CQ 码，提图片
                textSb.append(el.replace(Regex("\\[CQ:[^]]+\\]"), "").trim())
                Regex("\\[CQ:image,[^]]*url=([^,\\]]+)").findAll(el).forEach { images.add(it.groupValues[1]) }
            }
            else -> {}
        }
        return Triple(textSb.toString().trim(), images, files)
    }

    /** 是否 @ 了我。 */
    private fun isAtMe(el: Any?, selfId: String): Boolean {
        val arr = el as? JSONArray ?: return false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("type", "") == "at") {
                val qq = obj.optJSONObject("data")?.optString("qq", "") ?: ""
                if (qq == selfId || qq == "all") return true
            }
        }
        return false
    }

    // ══════════════════════════════════════════════════════════════
    //  控制指令
    // ══════════════════════════════════════════════════════════════

    private fun handleStopCommand() {
        if (isRunning()) {
            onAbort()
            send(owner, null, "已打断当前任务。发新消息继续。")
        } else {
            send(owner, null, "当前没有正在跑的任务。")
        }
    }

    /** 队列非空？ */
    fun pending(): Boolean = queue.isNotEmpty()

    /** 取队首并移除（注入方调用）。 */
    fun next(): QqMessage? = queue.poll()

    /** 看队首不移除。 */
    fun peekQueue(): List<QqMessage> = queue.toList()

    /** 删除第 i 条（/qq queue 用）。 */
    fun deleteQueued(index: Int): Boolean {
        val list = queue.toList()
        if (index !in list.indices) return false
        queue.clear()
        list.forEachIndexed { i, m -> if (i != index) queue.add(m) }
        return true
    }

    private fun enqueueOrInterrupt(msg: QqMessage) {
        // allowInterrupt：任何新消息都打断（/qq interrupt on）
        if (allowInterrupt && isRunning()) {
            onAbort()
        }
        queue.add(msg)
        onMessage(msg)
    }

    // ══════════════════════════════════════════════════════════════
    //  发送：POST NapCat API
    // ══════════════════════════════════════════════════════════════

    /**
     * 发消息。
     *
     * @param userId 私聊目标（groupId 为 null 时用）
     * @param groupId 群目标（非 null 时发群）
     * @param text 正文
     */
    fun send(userId: String?, groupId: String?, text: String) {
        if (text.isBlank()) return
        val action = if (groupId != null) "send_group_msg" else "send_private_msg"
        val payload = if (groupId != null) {
            JSONObject().put("group_id", groupId).put("message", text).toString()
        } else if (userId != null) {
            JSONObject().put("user_id", userId).put("message", text).toString()
        } else return

        scope.launch(Dispatchers.IO) {
            try {
                val conn = (URL("$napcatApi/$action").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != 200) {
                    lastError = "发送失败 HTTP $code"
                    Log.w(TAG, lastError!!)
                } else {
                    lastError = null
                }
            } catch (e: Throwable) {
                lastError = "发送失败：${e.message?.take(80)}"
                Log.w(TAG, lastError!!)
            }
        }
    }

    /** 发图片（按文件路径，NapCat 支持 file:// 形式）。 */
    fun sendImage(userId: String?, groupId: String?, imagePath: String) {
        val action = if (groupId != null) "send_group_msg" else "send_private_msg"
        val seg = """[CQ:image,file=file://$imagePath]"""
        val payload = if (groupId != null) {
            JSONObject().put("group_id", groupId).put("message", seg).toString()
        } else if (userId != null) {
            JSONObject().put("user_id", userId).put("message", seg).toString()
        } else return

        scope.launch(Dispatchers.IO) {
            try {
                val conn = (URL("$napcatApi/$action").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                if (conn.responseCode != 200) lastError = "发图失败 HTTP ${conn.responseCode}"
            } catch (e: Throwable) {
                lastError = "发图失败：${e.message?.take(80)}"
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  图片/文件下载
    // ══════════════════════════════════════════════════════════════

    private fun downloadImages(urls: List<String>): List<String> {
        if (urls.isEmpty()) return emptyList()
        val dir = File(filesDir, "qq-images").apply { mkdirs() }
        return urls.mapNotNull { url ->
            try {
                val name = "img-${System.currentTimeMillis()}-${(0..9999).random()}.jpg"
                val f = File(dir, name)
                URL(url).openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                if (f.length() > 0) f.absolutePath else null
            } catch (e: Throwable) {
                Log.w(TAG, "图片下载失败: ${e.message?.take(60)}")
                null
            }
        }
    }

    private fun downloadFiles(files: List<Pair<String, String>>): List<String> {
        if (files.isEmpty()) return emptyList()
        val dir = File(filesDir, "qq-files").apply { mkdirs() }
        return files.mapNotNull { (url, name) ->
            try {
                val safe = name.replace(Regex("[^\\w.\\-]"), "_").take(80)
                val f = File(dir, "f-${System.currentTimeMillis()}-$safe")
                URL(url).openStream().use { input -> f.outputStream().use { input.copyTo(it) } }
                if (f.length() > 0) f.absolutePath else null
            } catch (e: Throwable) {
                Log.w(TAG, "文件下载失败: ${e.message?.take(60)}")
                null
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  探测（/qq status / 端口占用诊断）
    // ══════════════════════════════════════════════════════════════

    /** 探测指定端口上是否已有桥在跑（区分端）。 */
    fun probe(port: Int): String? {
        return try {
            val conn = (URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 1500
                readTimeout = 1500
            }
            val body = conn.inputStream.bufferedReader().readText()
            val o = JSONObject(body)
            "被 ${o.optString("endpoint", "?")} 占用（pid ${o.optInt("pid", 0)}）"
        } catch (_: Throwable) {
            null
        }
    }
}
