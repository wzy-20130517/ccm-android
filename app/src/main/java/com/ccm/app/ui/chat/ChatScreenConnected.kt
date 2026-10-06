package com.ccm.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.unit.dp
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
    onAutoTitle: (String) -> Unit = {},
    modelName: String = "",  // 【2026-10-06 问题2】不再硬编码 Sonnet 4.6，由调用方传真实模型名
    onExport: () -> Unit = {},
    onRename: () -> Unit = {},
    onModelClick: () -> Unit = {},
    modelPickerContent: (@Composable () -> Unit)? = null,
    onSwitchClick: () -> Unit = {},
    /** /new 新建会话（第 2026-09-30 批 slash 扩充）。 */
    onNewChat: () -> Unit = {},
    /** /style 打开输出风格选择。 */
    onOpenStyle: () -> Unit = {},
    /** slash handler 的导航请求（如 /delete 后回 "home"）。 */
    onNavigate: (String) -> Unit = {},
) {
    // 语音输入（第30批）：听写结果追加到 draft（追加不覆盖 —— 说完一句还能接着说）
    val voiceClick = com.ccm.app.ui.common.rememberVoiceInput(
        onText = { text ->
            val cur = session.state.value.draft
            session.setDraft(if (cur.isBlank()) text else cur + " " + text)
        },
    )
    val coreState by session.state.collectAsState()
    val uiState = remember(coreState) { ChatAdapter.toUi(coreState) }
    LaunchedEffect(coreState.bubbles.firstOrNull { it.isUser }?.text, title) {
        if (title == "新对话") {
            coreState.bubbles.firstOrNull { it.isUser }?.text
                ?.trim()?.replace(Regex("\\s+"), " ")?.take(48)
                ?.takeIf { it.isNotBlank() }
                ?.let(onAutoTitle)
        }
    }

    // ── 图片附件（第18批）───────────────────────────────────────
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    // 待发文件（webgap #2：+ 原来直接塌成选图，文件/其他入口全无）。
    // 文件不进多模态 —— 发送时以 `[附件: 名 @ 路径]` 文本随消息走，
    // 模型用 Read 工具读内容（core 的用户消息通道只支持图片）。
    var pendingFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var showAttachMenu by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current

    // ── slash 统一 handler 的上下文与结果执行器（2026-09-30）──────────
    //   handler（SlashCommandHandler.kt）是纯逻辑，不碰 Compose；
    //   这里负责把 SlashResult 翻译成真实副作用。
    fun buildSlashCtx() = com.ccm.app.ui.chat.SlashContext(
        session = session,
        appContext = ctx.applicationContext,
        navigate = onNavigate,
        newChat = onNewChat,
        openPanel = { p ->
            when (p) {
                "model" -> onModelClick()
                "switcher" -> onSwitchClick()
                "style" -> onOpenStyle()
            }
        },
        // 【2026-10-06 问题40】goal 模式（/goal）——
        // 原来报「APK 暂未接入」，但 runGoal 早就实现好了。
        // 循环是挂起的长任务，必须 launch 在独立协程里。
        startGoal = { desc, first ->
            val gs = com.ccm.app.AppGraph.toolsResult?.goalStore
            val scope = com.ccm.app.AppGraph.appScope
            if (gs != null && scope != null) {
                scope.launch {
                    try {
                        session.runGoal(gs, first)
                    } catch (_: Throwable) {}
                }
            }
        },
        goalStatusText = {
            val gs = com.ccm.app.AppGraph.toolsResult?.goalStore
            val sid = com.ccm.app.AppGraph.sessionId
            gs?.get(sid)?.let { g -> gs.render(g) }
        },
        // 对话页不直接刷新列表：删除/重命名后回列表页时
        // CcmApp 的 CHATS 分支有 LaunchedEffect(route){refreshSessions()}
        refreshSessions = {},
    )

    fun applySlashResult(res: com.ccm.app.ui.chat.SlashResult) {
        when (res) {
            is com.ccm.app.ui.chat.SlashResult.Handled -> {}
            is com.ccm.app.ui.chat.SlashResult.Notice -> session.injectNotice(res.markdown)
            is com.ccm.app.ui.chat.SlashResult.Navigate -> onNavigate(res.route)
            is com.ccm.app.ui.chat.SlashResult.OpenPanel ->
                when (res.panel) {
                    "model" -> onModelClick()
                    "switcher" -> onSwitchClick()
                    "style" -> onOpenStyle()
                }
            is com.ccm.app.ui.chat.SlashResult.Toast ->
                android.widget.Toast.makeText(ctx, res.text, android.widget.Toast.LENGTH_SHORT).show()
            is com.ccm.app.ui.chat.SlashResult.NotHandled -> {}
        }
    }

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

    // 拍照（webgap #2：Web 的 + 菜单有相机 —— TakePicture 输出到 cache 文件）
    val cameraFile = remember { java.io.File(ctx.cacheDir, "attachments/cam_${System.currentTimeMillis()}.jpg") }
    val cameraLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok ->
        if (ok) pendingImages = pendingImages + cameraFile.absolutePath
    }
    val fileLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            ioScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val paths = uris.mapNotNull {
                    com.ccm.app.core.image.AttachmentCache.copyToCache(ctx, it)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (paths.isNotEmpty()) pendingFiles = pendingFiles + paths
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

            // ── 【2026-10-06 问题40】正文自动朗读（/voice）────────────
            //
            // 在**本轮完成时**念最后一条助手消息的正文 —— 不在流式过程中念
            // （会把半句话反复念出来）。
            if (com.ccm.app.ui.theme.UiPrefs.voiceEnabled.value) {
                try {
                    val lastAssistant = uiState.bubbles.lastOrNull { !it.isUser }
                    val text = lastAssistant?.text.orEmpty()
                    if (text.isNotBlank()) {
                        com.ccm.app.tools.NativeTts.speak(
                            context = ctx,
                            text = text,
                            rate = com.ccm.app.ui.theme.UiPrefs.voiceRate.value,
                        )
                    }
                } catch (_: Throwable) {}
            }
        }
        wasRunning = nowRunning
    }

    // ── + 附件菜单（webgap #2：原「+」直接塌成选图）───────────────
    if (showAttachMenu) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAttachMenu = false },
            title = { androidx.compose.material3.Text("添加附件", style = com.ccm.app.ui.theme.CCMText.body14) },
            text = {
                androidx.compose.foundation.layout.Column {
                    listOf(
                        "拍照" to {
                            try {
                                cameraFile.parentFile?.mkdirs()
                                cameraLauncher.launch(
                                    androidx.core.content.FileProvider.getUriForFile(
                                        ctx,
                                        ctx.packageName + ".fileprovider",
                                        cameraFile,
                                    ),
                                )
                            } catch (_: Throwable) {}
                            showAttachMenu = false
                        },
                        "图片（可多选，随消息发给模型看）" to { attachLauncher.launch("image/*"); showAttachMenu = false },
                        "文件（以路径附带，模型用 Read 读）" to { fileLauncher.launch("*/*"); showAttachMenu = false },
                    ).forEach { (label, act) ->
                        androidx.compose.material3.Text(
                            text = label,
                            style = com.ccm.app.ui.theme.CCMText.body13,
                            color = com.ccm.app.ui.theme.CCMTheme.colors.textMain,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable { act() }
                                .padding(vertical = 11.dp, horizontal = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAttachMenu = false }) {
                    androidx.compose.material3.Text("取消", style = com.ccm.app.ui.theme.CCMText.body13)
                }
            },
        )
    }

    ChatScreen(
        bubbles = uiState.bubbles,
        modifier = modifier,
        streaming = stableStreaming.ifBlank { uiState.streaming },
        streamingThinking = uiState.streamingThinking,
        todos = uiState.todos,
        presentItems = uiState.presentItems,
        toolCards = uiState.toolCards,
        input = coreState.draft,
        running = uiState.running,
        title = title,
        sessionKey = session.sessionId,
        modelName = modelName,
        tokenCount = uiState.displayTokens,
        errorMessage = uiState.error,
        onInputChange = session::setDraft,
        // 显式标签：Kotlin lambda 隐式 label 是**函数名**（@ChatScreen），
        // 参数名不能直接当 label —— CI #218 报 Unresolved label('onSend')。
        onSend = onSend@{
            val text = coreState.draft.trim()
            if (text.isNotEmpty()) {
                // ★ 2026-09-30 统一 handler（多 Agent 接入）：所有 slash 先过
                //   SlashCommandHandler.handleSlashCommand（会话/查询/配置/工具
                //   四大分区），认得的在这里执行副作用；不认的才落到下面的老
                //   when（/clear /help 等基础命令 + 兜底提示）。
                if (text.startsWith("/")) {
                    val res = handleSlashCommand(text, buildSlashCtx())
                    if (res != null && res !is SlashResult.NotHandled) {
                        applySlashResult(res)
                        session.setDraft("")
                        return@onSend
                    }
                }
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
                        // 2026-09-30：动态生成自公共命令表（43 个），不再手写
                        // ——手写漏一条就与候选面板/实际拦截漂移。
                        session.injectNotice(
                            "**可用命令（${COMMON_SLASH_COMMANDS.size} 个）**\n\n" +
                            COMMON_SLASH_COMMANDS.joinToString("\n") { (c, d) -> "- `$c` — $d" } +
                            "\n\n输入 `/` 可看候选面板，其余输入直接发给模型。"
                        )
                    }
                    text == "/model" -> onModelClick()
                    text == "/export" -> onExport()
                    // /permissions 已搬进 SlashCommandHandler（B5：首页/对话页统一路径）
                    text == "/compact" -> {
                        // audit-core #7：原来无任何压缩入口，长会话必撞 400。
                        // microCompact 免 API；摘要式后续再接。
                        session.injectNotice("**/compact**\n\n" + session.compactNow())
                    }
                    // ── 2026-09-30 扩充：能在 APK 环境合理实现的命令 ──────
                    text == "/stop" -> {
                        session.stop()
                        session.injectNotice("已停止当前任务。")
                    }
                    text == "/retry" -> {
                        if (session.state.value.running) {
                            session.injectNotice("任务进行中，先 /stop 再重试。")
                        } else {
                            session.retryLast()
                        }
                    }
                    text == "/context" || text == "/cost" -> {
                        val st = session.state.value
                        val inT = st.inputTokens
                        val outT = st.outputTokens
                        val turns = st.bubbles.count { !it.isUser }
                        session.injectNotice(
                            "**上下文用量**\n\n" +
                            "- 消息条数：${st.bubbles.size}\n" +
                            "- 助手轮数：$turns\n" +
                            "- 最近一次输入 token：$inT\n" +
                            "- 最近一次输出 token：$outT\n" +
                            "- 合计（最近一轮）：${inT + outT}\n\n" +
                            "长会话可用 /compact 压缩。"
                        )
                    }
                    text == "/copy" -> {
                        val last = session.state.value.bubbles.lastOrNull { !it.isUser }
                        if (last == null || last.text.isBlank()) {
                            session.injectNotice("没有可复制的回复。")
                        } else {
                            try {
                                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(
                                    android.content.ClipData.newPlainText("CCM", last.text)
                                )
                                android.widget.Toast.makeText(ctx, "已复制最后一条回复", android.widget.Toast.LENGTH_SHORT).show()
                            } catch (e: Throwable) {
                                session.injectNotice("复制失败：${e.message}")
                            }
                        }
                    }
                    text == "/new" -> onNewChat()
                    text == "/style" -> onOpenStyle()
                    // ★ 2026-09-29 未支持命令兜底：/config /style /undo 这类
                    //   CLI 命令原来从 else 溜过去**发给模型**（模型回
                    //   「我不是这样用的」，白烧一轮）。
                    text.startsWith("/") && !SUPPORTED_SLASH.contains(text.substringBefore(" ").trim()) -> {
                        val cmd = text.substringBefore(" ").trim()
                        session.injectNotice(
                            "**${cmd} 在 APK 暂不可用**\n\n" +
                            "APK 共支持 ${COMMON_SLASH_COMMANDS.size} 个命令，输入 `/` 看候选面板" +
                            "（或 /help 列全表）。\n\n" +
                            "${SLASH_HINTS[cmd] ?: "CLI 专属命令（/rewind /doctor 等）请到终端侧使用。"}"
                        )
                    }
                    else -> {
                        // 带附件发送：图走多模态通道；文件以路径文本随消息
                        //（模型拿 Read 读 —— core 用户消息通道只支持图片）
                        val fileLines = pendingFiles.joinToString("") { p ->
                            "\n[附件: ${p.substringAfterLast('/')} @ $p]"
                        }
                        session.send(text + fileLines, pendingImages)
                        pendingImages = emptyList()
                        pendingFiles = emptyList()
                    }
                }
                // slash 分支不走 send，draft 得自己清（send 的清空够不着）
                if (text.startsWith("/")) session.setDraft("")
            }
        },
        onAttach = { showAttachMenu = true },   // ★ #2：+ 弹菜单（原直接开选图）
        attachedPaths = pendingImages + pendingFiles,
        onRemoveImage = { path ->
            pendingImages = pendingImages - path
            pendingFiles = pendingFiles - path
        },
        onStop = { session.stop() },
        onExport = onExport,
        onRename = onRename,
        onModelClick = onModelClick,
        modelPickerContent = modelPickerContent,
        onSwitchClick = onSwitchClick,
        onVoice = voiceClick,
        onResend = { messageId -> session.resendFrom(messageId) },
        onRetry = { session.retryLast() },
    )
}


/** APK 支持的 slash 命令（与 ChatScreen.SLASH_COMMANDS 候选表同步）。 */
private val SUPPORTED_SLASH = setOf(
    "/clear", "/model", "/help", "/export", "/compact",
    // 2026-09-30 扩充
    "/stop", "/retry", "/context", "/cost", "/copy", "/new", "/style",
)

/** 常见 CLI 命令的去处提示（别让用户以为坏了）。 */
private val SLASH_HINTS = mapOf(
    "/style" to "风格选择在 设置 → 通用 → 输出风格（与 CLI /style 同字段互通）。",
    "/config" to "Provider 配置在 设置 → 模型。",
    "/model" to "",   // 已支持，不会走到这
    "/undo" to "回退在 CLI 侧；APK 暂未接入撤销栈。",
    "/memory" to "记忆管理在 CLI 侧；APK 暂未接入。",
)
