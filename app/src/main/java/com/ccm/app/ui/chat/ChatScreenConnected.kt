package com.ccm.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.ccm.app.core.ChatSession

/**
 * 已接上 [ChatSession] 的聊天界面 —— **阶段 5 只需传一个 session**。
 *
 * ## 用法（阶段 5 / MainActivity）
 * ```kotlin
 * val session = ChatSession.create(
 *     storage = FileAppStorage(context.filesDir),
 *     registry = registry,
 *     toolRunner = executor,
 *     scope = lifecycleScope,
 *     imageScaler = AndroidImageScaler(cacheDir),
 * ) ?: run {
 *     // 配置无效（没 Provider / 没 key）→ 引导去设置页
 *     SettingsScreen(); return@setContent
 * }
 *
 * ChatScreenConnected(session = session)
 * ```
 *
 * ## 这一层做什么
 * 1. **订阅** `session.state`（`StateFlow<ChatSession.State>`）
 * 2. **适配** core 类型 → UI 类型（[ChatAdapter]）
 * 3. **驱动流式记账**（[StreamingMarkdown]）—— core 给的是累积全文，
 *    这里按「换行边界」切出可安全渲染的部分
 * 4. **转发交互**（send / stop）到 session
 *
 * ## 流式记账的时机（关键）
 * ```
 * state.streaming 变化 → md.feed(全文) → 拿 stable 渲染
 * state.running 从 true 变 false → md.flush()  ← ★ 必须，否则末尾内容被吞
 * ```
 * 注意判据是 **running 变 false**（而不是 `streaming` 变空）——
 * 因为气泡定型后 `streaming` 会被清空，但那时内容已经进了 `bubbles`。
 *
 * @param session 已装配的会话（由阶段 5 创建）
 */
@Composable
fun ChatScreenConnected(
    session: ChatSession,
    modifier: Modifier = Modifier,
    title: String = "新对话",
    modelName: String = "Sonnet 4.6",
    onExport: () -> Unit = {},
    onRename: () -> Unit = {},
    onModelClick: () -> Unit = {},
    onSwitchClick: () -> Unit = {},
) {
    val coreState by session.state.collectAsState()
    val uiState = remember(coreState) { ChatAdapter.toUi(coreState) }

    // ── 图片附件（第18批）───────────────────────────────────────
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ioScope = rememberCoroutineScope()
    val attachLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            ioScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val paths = uris.mapNotNull {
                    com.ccm.app.core.image.AttachmentCache.copyToCache(ctx, it)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (paths.isNotEmpty()) pendingImages = pendingImages + paths
                }
            }
        }
    }

    // 输入框文本 —— ★ 2026-09-27 改走 core 的 State.draft：
    //   原来是 UI 本地 remember，屏幕旋转/进程重建就丢草稿；
    //   走 core 后单一数据源，发送时由 ChatSession.send() 统一清空
    //   （draft = ""），外部 sendAndOpen 触发的发送也能清到。

    // 流式记账器（跨重组保持）
    val md = remember { StreamingMarkdown() }
    var stableStreaming by remember { mutableStateOf("") }
    var wasRunning by remember { mutableStateOf(false) }

    // ── 流式内容变化 → 记账 ──────────────────────────────────────────
    LaunchedEffect(uiState.streaming) {
        if (uiState.streaming.isNotBlank()) {
            val r = md.feed(uiState.streaming)
            stableStreaming = r.stable
        }
    }

    // ── running 状态跃迁 → 记账器生命周期管理 ──────────────────────
    //
    // ⚠️ 必须放在**一个** LaunchedEffect 里处理两个方向：
    // 拆成两个的话，两者都会读改写 `wasRunning`，执行顺序不确定 →
    // 要么漏 flush（丢末尾内容），要么漏 reset（下一轮带上轮残留）。
    LaunchedEffect(uiState.running) {
        val nowRunning = uiState.running
        if (!wasRunning && nowRunning) {
            // 新一轮开始 → 清空上一轮残留
            md.reset()
            stableStreaming = ""
        } else if (wasRunning && !nowRunning) {
            // 收尾 → 必须 flush（不变量 3：不能吞内容）
            //
            // 注意：气泡定型发生在 core 层的 `TurnEnd`，此后 `streaming` 被清空、
            // 内容进了 `bubbles`。所以 flush 的结果通常「已没必要渲染」，
            // 但**仍然必须调用** —— 它同时负责重置内部状态供下一轮使用。
            // 这里不把结果写回 stableStreaming，否则会与 bubbles 重复显示。
            md.flush()
            md.reset()
            stableStreaming = ""
        }
        wasRunning = nowRunning
    }

    ChatScreen(
        bubbles = uiState.bubbles,
        modifier = modifier,
        streaming = stableStreaming.ifBlank { uiState.streaming },
        streamingThinking = uiState.streamingThinking,
        todos = uiState.todos,
        toolCards = uiState.toolCards,
        input = coreState.draft,
        running = uiState.running,
        title = title,
        modelName = modelName,
        tokenCount = uiState.displayTokens,
        errorMessage = uiState.error,
        onInputChange = session::setDraft,
        onSend = {
            val text = coreState.draft.trim()
            if (text.isNotEmpty()) {
                // ── slash 命令（2026-09-28 最小集）──────────────────
                // 原来 ChatSession.send 对 "/xxx" 照样发给模型 ——
                // 模型收到后只能回一句「我不是这样用的」。
                // UI 类命令（/model /export）必须在这里拦：
                // 它们要操作的是 Compose 状态，core 层够不着。
                when {
                    text == "/clear" -> {
                        session.clear()          // 停任务 + 清历史 + 清气泡
                    }
                    text == "/help" -> {
                        session.injectNotice(
                            "**可用命令**\n\n" +
                            "- `/clear` — 清空当前对话\n" +
                            "- `/model` — 打开模型选择器\n" +
                            "- `/export` — 导出对话（系统分享）\n" +
                            "- `/help` — 显示本帮助\n\n" +
                            "其余输入会直接发给模型。"
                        )
                    }
                    text == "/model" -> onModelClick()
                    text == "/export" -> onExport()
                    else -> {
                        // 带图发送（第18批）：pendingImages 随消息走，发完清零
                        session.send(text, pendingImages)
                        pendingImages = emptyList()
                    }
                }
                // slash 分支不走 send，draft 得自己清（send 的清空够不着）
                if (text.startsWith("/")) session.setDraft("")
            }
        },
        onAttach = { attachLauncher.launch("image/*") },
        attachedCount = pendingImages.size,
        onStop = { session.stop() },
        onExport = onExport,
        onRename = onRename,
        onModelClick = onModelClick,
        onSwitchClick = onSwitchClick,
    )
}
