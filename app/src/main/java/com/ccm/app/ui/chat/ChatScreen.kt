package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 聊天主界面容器 —— 对齐 Web `MainContent.tsx`（5537 行，全项目最难）。
 *
 * ## 拆分策略（按 recon-b §9 的区块表）
 * ```
 * ChatScreen（本文件）        容器：对话标题栏 + 消息区 + 输入栏
 * ├── ChatHeaderBar            顶栏下第二行（标题 + 下拉 + Export）
 * ├── MessageList              消息流（MessageBubble.kt）
 * │   ├── UserBubble           用户气泡（右对齐、浅底）
 * │   ├── AssistantBubble      助手正文（全宽、衬线、无背景）
 * │   └── ToolCard             工具卡片（ToolCard.kt）
 * └── InputBar                 输入栏（InputBar.kt）
 * ```
 *
 * > Web 的 MainContent 有 5537 行，直接照搬会重蹈「4085 行单文件」的覆辙。
 * > 这里按上表拆成 5 个文件，每个 < 400 行。
 *
 * ## 实测布局（Playwright，393×852，`#/chat/:id`）
 * ```
 * y=0    顶栏（44，由 AppScaffold 提供）
 * y=44   对话标题栏（h≈40：标题 + 下拉箭头 | Export 按钮）
 * y=84.5 消息区（pad 15.72×16，衬线 13.362/20.043）
 * ...    消息区可滚动
 * y=667  输入卡片（浮动，h=97.64）
 * y=765  底部状态行（"Claude 是 AI，可能会出错。请核对回复内容。"）
 * ```
 *
 * ## 对话标题栏
 * 实测：标题在左（`t` + 下拉箭头，`px-1.5 py-2 rounded-lg hover:bg-claude-hover`），
 * Export 按钮在右（77.92×40 / 圆角 8 / 描边 / `px-4`）。
 *
 * @param bubbles     已定型消息
 * @param streaming   流式中内容
 * @param toolCards   本轮工具卡片
 * @param input       输入框文本
 * @param running     是否正在跑
 * @param title       对话标题
 */
@Composable
fun ChatScreen(
    bubbles: List<ChatBubble>,
    modifier: Modifier = Modifier,
    streaming: String = "",
    toolCards: List<ChatToolCard> = emptyList(),
    streamingThinking: String = "",
    todos: List<com.ccm.app.ui.common.TodoItem> = emptyList(),
    presentItems: List<com.ccm.app.core.ChatSession.PresentItem> = emptyList(),
    input: String = "",
    running: Boolean = false,
    title: String = "新对话",
    sessionKey: String = "",
    modelName: String = "Sonnet 4.6",
    tokenCount: Int = 0,
    errorMessage: String? = null,
    onInputChange: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onExport: () -> Unit = {},
    onRename: () -> Unit = {},
    onModelClick: () -> Unit = {},
    modelPickerContent: (@Composable () -> Unit)? = null,
    /** 点标题旁的下拉箭头 → 切换对话（Web 是会话下拉；2026-09-28 接通）。 */
    onSwitchClick: () -> Unit = {},
    onAttach: () -> Unit = {},
    attachedPaths: List<String> = emptyList(),
    onVoice: () -> Unit = {},
    onRemoveImage: (String) -> Unit = {},
    /** 重发某条用户消息（webgap #1）。 */
    onResend: ((String) -> Unit)? = null,
    /** 错误横幅的「重试」（null = 不显示按钮）。 */
    onRetry: (() -> Unit)? = null,
) {
    val colors = CCMTheme.colors
    val imeVisible = WindowInsets.isImeVisible

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── 对话标题栏 ────────────────────────────────────────────
            ChatHeaderBar(
                title = title,
                onRename = onRename,
                onExport = onExport,
                onSwitchClick = onSwitchClick,
            )

            // ── 消息区（可滚动 + 自动跟底，底部留出输入栏高度）────────
            Box(modifier = Modifier.weight(1f)) {
                val scrollState = rememberScrollState()

                // ★ 2026-09-27：原来没有自动滚动 —— 新消息只画在
                //   视口外，用户必须手动往下滑，流式回复时看不到内容。
                //   行为对齐 Web（跟底；用户上翻即停止跟随）。
                var followBottom by remember { mutableStateOf(true) }

                // 判据：离底部 120px 内算「在底部」→ 继续跟；上翻则停
                LaunchedEffect(scrollState) {
                    snapshotFlow { scrollState.value to scrollState.maxValue }
                        .collect { (v, max) -> followBottom = v >= max - 120 }
                }

                // 内容变化 → 跟到底。
                // withFrameNanos 等一帧：新内容刚挂上时 maxValue 还是旧值，
                // 不等这一帧会滚到旧底部（看起来像没滚）。
                // 用瞬时 scrollTo 而非 animate：流式每个 chunk 都会触发，
                // 动画叠加会抖。
                LaunchedEffect(bubbles.size, streaming.length, toolCards.size) {
                    if (followBottom) {
                        withFrameNanos {}
                        scrollState.scrollTo(scrollState.maxValue)
                    }
                }

                // ★ 第38批：打开历史会话时**强制滚到底**（不看 followBottom）。
                //   场景：从侧栏点开一个 50 条历史的会话 —— 首帧时
                //   maxValue 还是 0，上面那个 effect 滚了个寂寞，用户
                //   看到的是**会话最开头**（很反直觉：聊天应用都停最新）。
                //   这里等两帧（布局稳定）再滚，只在 bubbles 首次填充时触发。
                LaunchedEffect(sessionKey, bubbles.size > 0) {
                    if (bubbles.isNotEmpty()) {
                        withFrameNanos {}
                        withFrameNanos {}
                        scrollState.scrollTo(scrollState.maxValue)
                        followBottom = true
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState),
                ) {
                    MessageList(
                        bubbles = bubbles,
                        onResend = onResend,
                        streaming = streaming,
                        toolCards = toolCards,
                        streamingThinking = streamingThinking,
                        streamingRunning = running,
                        todos = emptyList(),
                        presentItems = presentItems,
                    )
                    // 底部留白 —— 实测滚动容器 pad: `0px 0px 154px`（pb-154）
                    // 给浮动输入栏 + 底部状态行让位
                    Spacer(Modifier.height(154.dp))
                }

                if (todos.isNotEmpty()) {
                    com.ccm.app.ui.common.TodoPanel(
                        todos = todos,
                        running = running,
                        modifier = Modifier.align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 154.dp),
                    )
                }
            }
        }

        // ── 输入栏 + 底部状态行（浮在底部）──────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(colors.bgMain)
                // ★ 2026-09-29 键盘遮挡修复：targetSdk 35 强制 edge-to-edge，
                //   AndroidManifest 的 adjustResize 被系统忽略 → 键盘弹出时
                //   输入框被键盘盖住，看不见自己打的字。
                //   ime.union(navigationBars)：键盘弹出用 ime 高度上推；
                //   无键盘时用导航栏高度（手势条避让）。union 取两者较高者，
                //   不会出现「键盘高 + 导航栏高」的叠加空隙。
                .windowInsetsPadding(
                    WindowInsets.ime.union(
                        WindowInsets.navigationBars,
                    ),
                ),
        ) {
            if (errorMessage != null) {
                ErrorBanner(
                    message = errorMessage,
                    // 重试（第36批）：错误横幅原来只有显示 —— 用户只能手动
                    // 重打一遍。现在一键重跑最后一条用户消息。
                    onRetry = onRetry,
                )
                Spacer(Modifier.height(7.36.dp))
            }

            // ── 已选图片管理条（第19批：选了才能删，原来只能发出去）──
            if (attachedPaths.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.72.dp)
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 7.36.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                ) {
                    attachedPaths.forEach { path ->
                        // 缩略图 chip（第20批：文件名 → 真缩略图，点 × 删除）
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onRemoveImage(path) },
                        ) {
                            com.ccm.app.ui.common.ThumbImage(
                                path = path,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 8.dp,
                            )
                            // 删除角标（右上）
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.textMain),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("✕", style = CCMText.body10, color = colors.bgMain)
                            }
                        }
                    }
                    Text(
                        text = "点标签删除",
                        style = CCMText.body11,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(vertical = 5.dp),
                    )
                }
            }

            // ── slash 命令候选（2026-09-28）──────────────────────────
            // 输入 "/" 开头且还没敲到空格 → 浮出候选（对齐 Web 的
            // slash 面板；点选填入输入框，再按发送执行）。
            val slashQuery = input.trim()
            val slashCandidates = if (slashQuery.startsWith("/") && !slashQuery.contains(" ")) {
                SLASH_COMMANDS.filter { it.first.startsWith(slashQuery) }
            } else emptyList()
            if (slashCandidates.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.72.dp)
                        .clip(RoundedCornerShape(11.04.dp))
                        .background(colors.input)
                        .border(1.dp, colors.border, RoundedCornerShape(11.04.dp))
                        .padding(vertical = 4.dp),
                ) {
                    slashCandidates.forEach { (cmd, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInputChange(cmd) }
                                .padding(horizontal = 12.88.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = cmd,
                                style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                                color = colors.textMain,
                            )
                            Text(
                                text = desc,
                                style = CCMText.body12,
                                color = colors.textSecondary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(7.36.dp))
            }

            InputBar(
                value = input,
                onValueChange = onInputChange,
                onSend = onSend,
                onStop = onStop,
                running = running,
                modelName = modelName,
                tokenCount = tokenCount,
                onModelClick = onModelClick,
                modelPickerContent = modelPickerContent,
                onAttach = onAttach,
                attachedPaths = attachedPaths,
                onVoice = onVoice,
                onRemoveImage = onRemoveImage,
            )

            if (!imeVisible) {
                Spacer(Modifier.height(7.36.dp))
                // 底部提示不在输入期间占位。
                Text(
                    text = "Claude 是 AI，可能会出错。请核对回复内容。",
                    style = CCMText.body11,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 11.04.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 对话标题栏 —— 实测 h≈40。
 *
 * 左：标题（可点重命名）+ 下拉箭头；右：Export 按钮（77.92×40 / 圆角 8 / 描边）。
 */
@Composable
private fun ChatHeaderBar(
    title: String,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onSwitchClick: () -> Unit = {},
) {
    val colors = CCMTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.72.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左：标题 + 下拉（实测 px-1.5 py-2 rounded-lg）
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(7.36.dp))
                .clickable(onClick = onRename)
                .padding(horizontal = 5.52.dp, vertical = 7.36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.52.dp),
        ) {
            Text(
                text = title,
                style = CCMText.body13,
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 下拉箭头（表示可切换对话）
            // ★ 2026-09-27：原来是 13.8dp 灰方块占位（没人实现图标），
            //   换成首页模型选择器同款 caret。
            // ★ 2026-09-28：再补点击 —— 之前图标换了但点了没反应。
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onSwitchClick)
                    .padding(2.dp),
            ) {
                com.ccm.app.ui.common.PainterIcon(
                    com.ccm.app.R.drawable.ic_model_caret,
                    size = 13.8.dp,
                    tint = colors.textSecondary,
                )
            }
        }

        // 右：Export
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onExport)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Export",
                    style = CCMText.body14.copy(
                        fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                    ),
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/**
 * 错误横幅 —— 对齐 Web 的错误提示样式。
 *
 * Web 用红色系（`bg-red-50 dark:bg-red-900/20` + `border-red-200`）。
 */
@Composable
private fun ErrorBanner(message: String, onRetry: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.72.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(Color(0x14DC2626))
            .padding(horizontal = 12.88.dp, vertical = 8.28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = CCMText.body12,
            color = Color(0xFFB91C1C),
            modifier = Modifier.weight(1f),
        )
        if (onRetry != null) {
            Text(
                text = "重试",
                style = CCMText.body12.copy(fontWeight = FontWeight.Medium),
                color = Color(0xFFB91C1C),
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * slash 命令候选表 —— 与 `ChatScreenConnected.onSend` 的拦截表**必须同步**。
 * （那边新增命令忘了加这里 → 面板里看不见；反之点了没反应。）
 */
// 2026-09-30：抽到公共 SlashCommands.kt，首页/对话页共用（原来首页没面板）
private val SLASH_COMMANDS = COMMON_SLASH_COMMANDS
