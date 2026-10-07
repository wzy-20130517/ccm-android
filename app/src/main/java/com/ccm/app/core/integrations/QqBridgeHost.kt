package com.ccm.app.core.integrations

import android.util.Log
import com.ccm.app.core.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File

/**
 * QQ 桥接线器 —— 把 [QqBridge] 与 [ChatSession] 接起来。
 *
 * ══════════════════════════════════════════════════════════════════
 * 【职责】三段管道：
 *   1. 收：桥收到 QQ 消息 → 注入「【QQ消息｜来自 xxx】正文」→ session.send
 *   2. 发：session 产出新的 assistant 气泡 → 发回 QQ（按 turn 发，
 *      像真人说话，不攒到整轮结束）
 *   3. 路由：回复发给谁 —— 私聊回私聊、群消息回那个群
 *
 * 【为什么独立于 AppGraph】AppGraph 已经 1100+ 行；QQ 桥的生命周期
 * （attach/detach/启停）自成一体，独立类更清晰，也方便 /qq 命令
 * 直接拿到实例操作。
 *
 * 【回复收集的实现选择】
 * CLI 是流式回调攒句子。APK 用 **StateFlow 订阅**：
 *   · bubbles 每新增一条 assistant 气泡 = 一个 turn 定型 → 发一条
 *   · 这天然是「按 turn 发」（对齐 CLI 语义），且不用碰 AgentLoop
 *   · 流式中间态（streaming 字段）不发 —— 避免碎消息
 */
class QqBridgeHost(
    private val filesDir: File,
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "QqBridgeHost"
    }

    /** 当前桥实例（未 attach 时为 null）。 */
    @Volatile
    var bridge: QqBridge? = null
        private set

    /** 回复路由：本轮任务回给谁（null = 本轮不是 QQ 发起的，不回）。 */
    @Volatile
    private var replyTarget: Pair<String, String?>? = null   // (userId, groupId)

    private var collectJob: Job? = null
    private var session: ChatSession? = null

    /** 上一次看到的 assistant 气泡数（增量检测）。 */
    private var lastBubbleCount = 0

    /**
     * 接线：订阅会话 + 按需启动桥。
     *
     * @return null = 成功；非 null = 失败原因
     */
    @Synchronized
    fun attach(session: ChatSession): String? {
        // 重复 attach 先拆旧的（换会话时）
        detach()

        this.session = session
        lastBubbleCount = session.state.value.bubbles.size

        // 订阅气泡流（回复收集）
        collectJob = scope.launch(Dispatchers.Default) {
            try {
                session.state.collect { st ->
                    // 新增的 assistant 气泡 → 发回 QQ
                    val bubbles = st.bubbles
                    if (bubbles.size > lastBubbleCount && replyTarget != null) {
                        val newOnes = bubbles.drop(lastBubbleCount)
                        newOnes.forEach { b ->
                            if (b.role == com.ccm.app.core.session.Message.ROLE_ASSISTANT && b.text.isNotBlank()) {
                                sendReply(b.text)
                            }
                        }
                    }
                    lastBubbleCount = bubbles.size
                    // 整轮结束 + 队列空 → 清路由（后续终端对话不再发 QQ）
                    if (!st.running && bridge?.pending() != true) {
                        replyTarget = null
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "会话订阅失败: ${e.message?.take(80)}")
            }
        }

        // 按配置启动桥
        val cfg = QqConfigStore.load(filesDir)
        if (!cfg.ccmEnabled) return null   // 没开 = 正常（用户没启用的端不该自动开）
        return start(cfg.owner, cfg.napcatApi, cfg.port, cfg.openMode, cfg.interrupt)
    }

    /**
     * 启动桥（/qq on 或 attach 时配置已开）。
     */
    @Synchronized
    fun start(
        owner: String,
        napcatApi: String,
        port: Int,
        openMode: Boolean = false,
        interrupt: Boolean = false,
    ): String? {
        if (owner.isBlank()) return "主人号未配置 —— 先 /qq owner <你的QQ号>（只有主人号的私聊会进会话）"

        // 已有实例先停
        bridge?.stop()

        val b = QqBridge(
            filesDir = filesDir,
            napcatApi = napcatApi,
            port = port,
            owner = owner,
            onMessage = { msg ->
                Log.i(TAG, "onMessage 回调: ${msg.text.take(30)}")
                injectToSession(msg)
            },
            onAbort = { session?.stop() },
            isRunning = { session?.isRunning == true },
            scope = scope,
        )
        b.openMode = openMode
        b.allowInterrupt = interrupt

        val err = b.start()
        if (err != null) {
            bridge = null
            return err
        }
        bridge = b
        Log.i(TAG, "QQ 桥已启动（owner=$owner port=$port api=$napcatApi）")
        return null
    }

    /** 停止桥（/qq off）。 */
    @Synchronized
    fun stop() {
        bridge?.stop()
        bridge = null
        replyTarget = null
    }

    /** 拆接线（换会话时调 —— 订阅旧会话的协程取消）。 */
    @Synchronized
    fun detach() {
        collectJob?.cancel()
        collectJob = null
        session = null
    }

    // ══════════════════════════════════════════════════════════════
    //  消息注入
    // ══════════════════════════════════════════════════════════════

    private fun injectToSession(msg: QqBridge.QqMessage) {
        val s = session
        if (s == null) {
            Log.w(TAG, "注入失败：session 为 null（未 attach）")
            return
        }

        // 【去重】队列里的这条已经被消费（next()）才轮到注入 ——
        // 若还在队首说明是 onMessage 直调进来的，需要出队；
        // 出队失败（已被别的路径消费）则不重复注入。
        // 原来 onMessage 直接调 injectToSession 且不动队列，drain 循环
        // 见队列非空又注入一次 —— 同一条消息进会话两次（实测日志双份）。
        val head = bridge?.peekQueue()?.firstOrNull()
        if (head === msg) {
            bridge?.next()   // 消费队首（就是本条）
        }

        // 路由：本轮回复发回给谁
        replyTarget = msg.userId to msg.groupId

        // 组装正文：带来源头（对齐 CLI 的「【QQ消息｜来自 用户xxx】」）
        val who = if (msg.nickname.isNotBlank()) "${msg.nickname}(${msg.userId})" else msg.userId
        // ⚠️ 必须用 ${msg.userId}（$msg.userId 会被解析成 $msg 后跟字面 .userId，
        // 把整个 data class 打进正文 —— 实测踩过）
        val from = if (msg.groupId != null) "群${msg.groupId} · $who" else "用户${msg.userId}"
        val body = buildString {
            append("【QQ消息｜来自 $from】\n")
            append(msg.text)
            if (msg.filePaths.isNotEmpty()) {
                append("\n\n[附件]")
                msg.filePaths.forEach { append("\n- $it") }
            }
        }

        Log.i(TAG, "注入会话: session=${s.hashCode()} text=${body.take(40)}")
        s.send(body, msg.imagePaths)
        Log.i(TAG, "send 已调用")

        // 队列里的下一条：等当前轮跑完再注入（对齐 CLI 的 drain 循环）
        scope.launch(Dispatchers.Default) {
            while (true) {
                delay(500)
                val b = bridge ?: break
                if (!b.pending()) break
                if (s.isRunning) continue
                val next = b.next() ?: break
                injectToSession(next)
                break   // 一次注一条；下条由下一轮结束时再检查
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  回复发送
    // ══════════════════════════════════════════════════════════════

    private fun sendReply(text: String) {
        val b = bridge ?: return
        val (userId, groupId) = replyTarget ?: return
        // 敏感信息打码（对齐 CLI 的 maskSensitive）
        val masked = maskSensitive(text)
        b.send(userId, groupId, masked)
    }

    /** key/token 打码（对齐 CLI：sk-xxx / ghp_xxx / 长 hex）。 */
    private fun maskSensitive(text: String): String {
        return text
            .replace(Regex("(sk-[A-Za-z0-9_-]{8})[A-Za-z0-9_-]+"), "$1…")
            .replace(Regex("(ghp_[A-Za-z0-9]{8})[A-Za-z0-9]+"), "$1…")
            .replace(Regex("(tvly-[A-Za-z0-9]{6})[A-Za-z0-9]+"), "$1…")
    }

    /** QQPush 工具用：主动推送（只能发给主人）。 */
    fun pushToOwner(text: String) {
        val b = bridge ?: return
        if (b.owner.isBlank()) return
        b.send(b.owner, null, maskSensitive(text))
    }

    /** QQPush 工具用：推送图片。 */
    fun pushImageToOwner(path: String) {
        val b = bridge ?: return
        if (b.owner.isBlank()) return
        b.sendImage(b.owner, null, path)
    }

    /** 是否已接入（QQPush 判断用）。 */
    fun isConnected(): Boolean = bridge?.running == true
}
