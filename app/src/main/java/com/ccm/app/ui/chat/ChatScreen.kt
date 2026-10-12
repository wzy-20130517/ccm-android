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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
    input: String = "",
    running: Boolean = false,
    title: String = "新对话",
    sessionKey: String = "",
    modelName: String = "",  // 【2026-10-06 问题2】不再硬编码 Sonnet 4.6，由调用方传真实模型名
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
    plusMenuContent: (@Composable () -> Unit)? = null,
    onAttach: () -> Unit = {},
    attachedPaths: List<String> = emptyList(),
    onVoice: () -> Unit = {},
    onRemoveImage: (String) -> Unit = {},
    /** 重发某条用户消息（webgap #1）。 */
    onResend: ((String) -> Unit)? = null,
    /** 编辑用户消息并重发（2026-10-11 加）—— 参数：(messageId, 新文本)。 */
    onEdit: ((String, String) -> Unit)? = null,
    /** 错误横幅的「重试」（null = 不显示按钮）。 */
    onRetry: (() -> Unit)? = null,
) {
    val colors = CCMTheme.colors
    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // 【2026-10-06 问题24】键盘高度（dp），消息区留白要加上它
    val imeBottomDp = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    // todo 看板折叠态（提升到这里 —— 折叠时面板不渲染，标题栏显徽标）
    var todoCollapsed by remember { mutableStateOf(true) }
    // 文档预览面板（点文档卡片打开；null = 未打开）。
    // 状态放这里而不是 DocumentPanel 内部 —— 点卡片的地方在 MessageList 深处，
    // 得由上层持有才能在两个 composable 之间传递。
    var openDoc by remember {
        mutableStateOf<com.ccm.app.core.ChatSession.DocumentEntry?>(null)
    }

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
                // todo 折叠徽标（零占位）—— 展开时不显示（面板自己有头部）
                trailing = {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = todos.isNotEmpty() && todoCollapsed,
                        enter = androidx.compose.animation.fadeIn(
                            animationSpec = androidx.compose.animation.core.tween(180),
                        ),
                        exit = androidx.compose.animation.fadeOut(
                            animationSpec = androidx.compose.animation.core.tween(150),
                        ),
                    ) {
                        val tDone = todos.count { it.status == com.ccm.app.ui.common.TodoStatus.COMPLETED }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { todoCollapsed = false }
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            com.ccm.app.ui.common.PainterIcon(
                                com.ccm.app.R.drawable.ic_list_checks,
                                size = 12.dp,
                                tint = colors.accent,
                            )
                            Text(
                                "$tDone/${todos.size}",
                                style = CCMText.body11,
                                color = colors.textSecondary,
                            )
                            com.ccm.app.ui.common.PainterIcon(
                                com.ccm.app.R.drawable.ic_chevron_down,
                                size = 10.dp,
                                tint = colors.textSecondary,
                            )
                        }
                    }
                },
            )

            // 【2026-10-06「开不开都挡 + 进消息流不常驻」】看板定稿：
            //   · 浮动浮层 → 开不开都盖正文（否）
            //   · 进消息流文档流 → 滚上去就不常驻（否）
            //   · **常驻条**：标题栏下方的独立区域，消息区 Box(weight 1f)
            //     自动让位 —— 既一直看得见，也不遮挡任何内容。
            // 默认折叠（TodoPanel.kt 改的），折叠态只占一行进度。
            // 【2026-10-07 优化「折叠也挡」】折叠态不渲染面板（原来那条
            // 42dp 常驻条把消息区永久压矮）→ 收进标题栏徽标（零占位），
            // 展开才恢复常驻条（此时占位是用户主动要的）。
            // 【2026-10-07 加动画】原来是 if 硬切（面板瞬间出现/消失
            // 像闪屏）→ 展开向下撑开 + 淡入，收起向上收拢 + 淡出。
            androidx.compose.animation.AnimatedVisibility(
                visible = todos.isNotEmpty() && !todoCollapsed,
                enter = androidx.compose.animation.expandVertically(
                    animationSpec = androidx.compose.animation.core.tween(220),
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(180),
                ),
                exit = androidx.compose.animation.shrinkVertically(
                    animationSpec = androidx.compose.animation.core.tween(200),
                ) + androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(150),
                ),
            ) {
                com.ccm.app.ui.common.TodoPanel(
                    todos = todos,
                    running = running,
                    collapsed = false,
                    onToggle = { todoCollapsed = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── 消息区（可滚动 + 自动跟底，底部留出输入栏高度）────────
            //
            // 【2026-10-06 问题45 修复·第二版】上一版只在底部 Spacer 加了
            // imeBottomDp（154 + 键盘高）—— 那只解决「能滚到底看到内容」，
            // **用户没滚动时正文仍被输入栏+键盘盖住**（用户报「拉起输入框时
            // 正文仍不向上」）。
            //
            // 真正的原因：输入栏是 `align(BottomCenter)` 浮动层 + 自己吃
            // ime padding 上推；而消息区 Box 是 `weight(1f)` **高度不变** ——
            // 输入栏往上顶多少，就盖住消息区多少。
            //
            // 修法：消息区也吃同样的 ime padding（union navigationBars），
            // 这样它的**可用高度**随键盘缩小，内容自然上移。
            // 【2026-10-06 问题45 修复·第三版（最终）】
            //
            // 历史：第一版加底部 Spacer（不够）、第二版给消息区加 ime padding
            // （与输入栏的 ime padding 叠加，缩两次）。
            //
            // 最终方案：**把输入栏从浮动层改成 Column 的普通子元素** ——
            // 它自己吃 ime padding 上推时，消息区 `weight(1f)` 自动缩，
            // 消息区**不需要**再吃一次 ime padding。
            Box(modifier = Modifier.weight(1f)) {
                // ══════════════════════════════════════════════════════════════
                //  【2026-10-11 滚动方案重写 —— 照抄官方 Jetchat】
                //
                //  旧的 `Column + verticalScroll` + 一整套跟随判定
                //  （followBottom / scrollRequested / programmaticScroll /
                //   判定协程 / 跟随协程 / 追底循环）修了 6 版仍有 bug：
                //  用户报「滑到最低也不跟随」。
                //
                //  根因：`verticalScroll` 下**内容长高会改变滚动位置和 maxValue**
                //  —— 所有「离底部多远」的判定在流式期间都不可靠。
                //
                //  Jetchat（官方 compose-samples）的方案：
                //    · `LazyColumn(reverseLayout = true)` —— 坐标系翻转，
                //      index 0 = 视觉**底部**（最新消息）
                //    · 新内容**自动**出现在底部 —— 不需要任何滚动代码
                //    · 内容长高**不影响**滚动位置（锚点在底部）
                //    · 「是否在底部」= firstVisibleItemIndex == 0 && offset < 阈值
                //      —— 精确、无歧义
                //
                //  参考：Jetchat/Conversation.kt:455（reverseLayout）、
                //        :503-505（判定）、:513（滚到底）、
                //        ConversationUiState.kt:28（数据 add(0, msg) 反序）
                // ══════════════════════════════════════════════════════════
                val listState = androidx.compose.foundation.lazy.rememberLazyListState()
                // 「回到底部」按钮的 animateScrollToItem 需要协程作用域
                val scope = androidx.compose.runtime.rememberCoroutineScope()

                // 「是否在底部」判定 —— derivedStateOf 只在值变化时触发重组
                val jumpThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 50.dp.toPx() }
                val isAtBottom by remember {
                    androidx.compose.runtime.derivedStateOf {
                        listState.firstVisibleItemIndex == 0 &&
                            listState.firstVisibleItemScrollOffset < jumpThresholdPx
                    }
                }

                // ══════════════════════════════════════════════════════════════
                //  【2026-10-12 重写「跟随到底」—— 修「上滑脱离不了」】
                //
                //  ## 之前的问题
                //
                //  旧实现：
                //    snapshotFlow { totalItemsCount to firstVisibleItemSize }
                //      .collect { if (isAtBottom) scrollToItem(0) }
                //
                //  三个缺陷叠加，导致用户上滑后仍被拽回底部：
                //
                //  ① **监听源错了**。流式输出时 item 数量/高度**一直在变**，
                //     所以 collect 一直在触发。而触发时读的 `isAtBottom`
                //     是**那一刻**的值 —— 用户刚开始上滑（还没超过 50dp
                //     阈值）时它仍是 true → 立刻被拽回。用户感觉
                //     「手指刚动就被吸回去」。
                //
                //  ② **判据只看位置，不看意图**。`isAtBottom` 是
                //     「当前是否在底部」，不是「用户是否想跟随」。
                //     用户手动滚到底部看一眼 ≠ 想一直跟随。
                //
                //  ③ **scrollToItem 会自我强化**。拽回后 firstVisibleItemIndex
                //     回到 0 → isAtBottom 仍 true → 下次内容变化又拽。
                //
                //  ## 现在的做法：**显式「跟随模式」状态**
                //
                //  · `following` = 是否自动跟随（初始 true）
                //  · 监听 `listState.isScrollInProgress` —— **用户一动手**
                //    （滑动开始）就检查：如果不在底部了，就 `following = false`
                //  · 内容变化时只在 `following == true` 时滚 —— 而
                //    following 只在用户**手动滚回底部**时恢复
                //
                //  这样「用户上滑 → 脱离跟随 → 不会被拽回」是**状态机保证**的，
                //  不依赖阈值和时序。
                //
                //  ⚠️ 不能用 `isScrollInProgress` 直接当判据：程序自己的
                //  `scrollToItem` 也会让它变 true（自我触发）。
                //  所以用「滚动进行中 + 不在底部」的组合判断 —— 程序滚到底部时
                //  一定在底部，不会误关 following。
                // ══════════════════════════════════════════════════════════════

                // 跟随模式（用户上滑后关闭，手动滚回底部时恢复）
                var following by remember { mutableStateOf(true) }

                // ① 监听用户手势：滚动中且不在底部 → 关闭跟随
                //
                // 【2026-10-12 三修：这里是真正的根因，前几版都改错地方了】
                //
                // 原来 `snapshotFlow { listState.isScrollInProgress }` ——
                // **block 里只读了 isScrollInProgress**，所以只有它变化才触发。
                // 而 isAtBottom 是在 collect 里读的，它的变化**不会触发**。
                //
                // 实际时序（用户上滑）：
                //   ① 手指按下 → isScrollInProgress false→true → 触发
                //      → 但此刻 isAtBottom 还是 true（还没滑出 50dp）
                //      → `scrolling && !atBottom` = false → **不关**
                //   ② 继续滑动 → isScrollInProgress 保持 true（**值没变**）
                //      → 即使 isAtBottom 变成了 false，**也不会再触发**
                //   ③ 手指抬起 → isScrollInProgress true→false → 触发
                //      → 但 scrolling 已是 false → 条件不满足 → **不关**
                //
                // **结论：following 永远不会变成 false** → ③ 一直滚到底
                // → 用户每次看到输出都被拉回底部（「随着 Agent 输出而被下拉」）。
                //
                // 修法：把 `isAtBottom` 也放进 snapshotFlow 的 **block**
                //（参与依赖追踪）—— 它变 false 时就能触发。
                LaunchedEffect(listState) {
                    snapshotFlow { listState.isScrollInProgress to isAtBottom }
                        .collect { (scrolling, atBottom) ->
                            if (scrolling && !atBottom) {
                                following = false
                            }
                        }
                }

                // ② 滚回底部时恢复跟随（用户手动滑到底 或 点「回到底部」按钮）
                LaunchedEffect(listState) {
                    snapshotFlow { isAtBottom }
                        .collect { atBottom ->
                            if (atBottom) following = true
                        }
                }

                // ③ 内容变化时跟随（只在 following 时）
                LaunchedEffect(listState) {
                    snapshotFlow {
                        listState.layoutInfo.totalItemsCount to
                            listState.layoutInfo.visibleItemsInfo.firstOrNull()?.size
                    }.collect {
                        if (following) {
                            listState.scrollToItem(0)
                        }
                    }
                }

                // ══════════════════════════════════════════════════════════
                //  ④ 【2026-10-12 加：脱离跟随时锚定用户正在看的那条】
                //
                //  ## 问题（用户原话：「上滑脱离跟随，界面仍随着 Agent 输出
                //  而被下拉」）
                //
                //  **跟随确实关了**（上滑后不再 scrollToItem），但内容**仍在动** ——
                //  这是 reverseLayout 的固有行为：
                //
                //  ```
                //  上滑前：index 3 = [bubble A]   ← 用户在看
                //  流式结束、新 bubble 插入到 index 1：
                //          index 4 = [bubble A]   ← A 的索引 +1 了
                //  而 LazyColumn 保持 firstVisibleItemIndex = 3 不变
                //  → index 3 现在是别的消息 → 画面整体往下移一格
                //  ```
                //
                //  **不是"被拽回底部"，是"内容整体位移"** —— 两种现象的修法
                //  完全不同（前者改滚动时机，后者要主动补偿位移）。
                //
                //  ## 修法：记住锚点 key + 位置，变了就补偿
                //
                //  · 脱离跟随时，记下第一个可见 item 的 key 和它的屏幕偏移
                //  · 内容变化后检查：那个 key 还在吗？屏幕位置变了吗？
                //  · 变了 → `requestScrollToItem(它的新索引, 补偿偏移)`
                //
                //  用 `requestScrollToItem`（Compose 1.7+）而非 `scrollToItem`：
                //  前者非挂起、在下一帧生效，不会与布局过程打架
                //  （后者挂起 + 立即生效，在 snapshotFlow 里用容易引发循环）。
                //
                //  ⚠️ 只在 `!following` 时做 —— 跟随中由 ③ 负责滚到底，
                //  两者同时跑会互相拉扯。
                // ══════════════════════════════════════════════════════════
                var anchorKey by remember { mutableStateOf<Any?>(null) }
                var anchorOffset by remember { mutableStateOf(0) }

                // 记录锚点 —— **只在用户滚动结束的那一刻**记录。
                //
                // 【2026-10-12 修一个隐蔽 bug】上一版的条件是
                // `!following && !scrolling` —— 但**内容变化时 scrolling 也是
                // false**，于是每次新 item 插入导致位移后，这个 collect 又触发、
                // 把 anchorOffset 更新成**位移后**的值 → 补偿逻辑看到
                // 「位置没变」→ 不补偿。**锚点被污染，补偿永远不生效。**
                //
                // 现在：只在 `scrolling` 从 **true → false** 的边沿记录 ——
                // 那才是「用户手指离开屏幕」的时刻，之后内容怎么变都不动锚点。
                LaunchedEffect(listState) {
                    var wasScrolling = false
                    snapshotFlow {
                        val info = listState.layoutInfo
                        val first = info.visibleItemsInfo.firstOrNull()
                        Triple(first?.key, first?.offset ?: 0, listState.isScrollInProgress)
                    }.collect { (key, off, scrolling) ->
                        val justStopped = wasScrolling && !scrolling
                        wasScrolling = scrolling
                        if (justStopped && !following && key != null) {
                            anchorKey = key
                            anchorOffset = off
                        }
                    }
                }

                // 补偿位移
                //
                // 【监听源：锚点项自己的 offset】上一版监听
                // `totalItemsCount to visibleItemsInfo.size` —— 只能抓到
                // 「新 item 插入」，抓不到「streaming 高度变化导致的位移」
                //（那两个值都不变）。改成直接监听**锚点项的位置**。
                LaunchedEffect(listState) {
                    snapshotFlow {
                        if (following) return@snapshotFlow null
                        val target = anchorKey ?: return@snapshotFlow null
                        val item = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == target } ?: return@snapshotFlow null
                        item.index to item.offset
                    }.collect { pair ->
                        if (pair == null) return@collect
                        val (idx, off) = pair
                        // 位置变了才补偿（避免每帧都请求滚动 → 抖动）
                        //
                        // 符号：`visibleItemsInfo[i].offset` 是「item 相对视口
                        // 起点的偏移」；`requestScrollToItem` 的 scrollOffset
                        // 同语义 —— **同号传递**。
                        if (off != anchorOffset) {
                            listState.requestScrollToItem(
                                index = idx,
                                scrollOffset = anchorOffset,
                            )
                        }
                    }
                }

                // 键盘弹出时滚到底（只在用户本来就在底部时 —— 别把他从历史里拽回来）
                LaunchedEffect(imeVisible) {
                    if (imeVisible && isAtBottom) {
                        listState.scrollToItem(0)
                    }
                }

                // 【reverseLayout 改造】不再需要外层 Column + verticalScroll ——
                // MessageList 自己就是 LazyColumn（带 reverseLayout），
                // 滚动状态由 listState 管理（上面已创建）。
                // 呼吸空间的 Spacer 也移进 MessageList（reverseLayout 下
                // 它要在**视觉最底部**，即声明顺序的最后）。
                MessageList(
                    bubbles = bubbles,
                    onResend = onResend,
                    onEdit = onEdit,
                    streaming = streaming,
                    toolCards = toolCards,
                    streamingThinking = streamingThinking,
                    streamingRunning = running,
                    todos = emptyList(),
                    onOpenDocument = { openDoc = it },
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                )

                // ── 回到底部按钮（不在跟随时显示）─────────────────────
                // 【2026-10-07 修两个问题】
                //  1. 看不清：原来 bgMain@0.95 + textSecondary，与正文对比度
                //     太低 → 改 accent 实底 + 反色文字（对比度拉满）+ 更明显阴影。
                //  2. 点了没反应：点击后判定协程会把 followBottom 打回 false ——
                //     animateScrollTo 动画途中 (max-v)>50，snapshotFlow 判定
                //     「用户上翻」；流式期间 max 还在涨，动画永远追不上 →
                //     按钮反复闪、点了像没反应。修：加 scrollRequested 标志，
                //     判定协程见到它时不打回；滚动用「追底循环」（每帧滚，
                //     对齐键盘弹出那段的成熟做法），追到后清标志。
                // 【reverseLayout 改造】显示条件用 isAtBottom（derivedStateOf，
                // 精确判定）；点击直接 scrollToItem(0) —— index 0 就是视觉底部，
                // 不再需要追底循环 / 标志位那套（reverseLayout 下滚到底就是滚到底）。
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isAtBottom,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp),
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50)) // 椭圆胶囊
                            // 【2026-10-11】加透明度 —— 原来是实底 accent，
                            // 浮在消息上方时太抢眼（用户报「有点挡了」）。
                            // 0.85 既能看清、又透出下面的内容。
                            .background(colors.accent.copy(alpha = 0.85f))
                            .shadow(8.dp, RoundedCornerShape(50))
                            .clickable {
                                // 需要一个协程作用域来跑 animateScrollToItem
                                scope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        com.ccm.app.ui.common.PainterIcon(
                            com.ccm.app.R.drawable.ic_chevron_down,
                            size = 14.dp,
                            tint = colors.bgMain,
                        )
                        Text(
                            "回到底部",
                            style = CCMText.body13.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                            color = colors.bgMain,
                        )
                    }
                }

                // 【2026-10-06「开不开都挡」】浮动看板已删 —— 它浮在右下角
                // （bottom 154dp），展开折叠都盖正文。看板改由上面
                // MessageList 顶部渲染（文档流，不遮挡）。
                // TodoPanel 默认也改为折叠（TodoPanel.kt），头部一行 + 进度不丢。
            }
            Column(
                modifier = Modifier
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
                    // 【2026-10-06 问题39 修复】原来 forEach 直接铺开所有候选 ——
                    // 输入一个 `/` 匹配全部 94 个命令，**撑满整屏**（用户报
                    // 「命令面板无截断，打一个/会吃满屏幕」）。
                    // 对齐 Web SlashCommandMenu：容器 max-h 240dp + 滚动。
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
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
                }
                Spacer(Modifier.height(7.36.dp))
                }

                // ══════════════════════════════════════════════════════════
                //  【2026-10-06 问题24 真根因】
                //
                // 重构时把 InputBar 的 ime padding **弄丢了** ——
                // 它现在没有任何 modifier，键盘弹出时不上推 → 被键盘盖住。
                //
                // 补回：ime.union(navigationBars) ——
                // 键盘弹出用 ime 高度上推；无键盘时用导航栏高度（手势条避让）。
                // union 取两者较高者，不会「键盘高 + 导航栏高」叠加。
                //
                // 消息区（weight 1f）会因输入栏上推而自动缩 —— 不需要额外处理。
                // ══════════════════════════════════════════════════════════
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
                    plusMenuContent = plusMenuContent,
                    attachedPaths = attachedPaths,
                    onVoice = onVoice,
                    onRemoveImage = onRemoveImage,
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.ime.union(WindowInsets.navigationBars),
                    ),
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

        // ── 文档全屏预览（点消息里的文档卡片打开）─────────────────────
        // 挂在最外层 Box 末尾 → 盖住整屏（含标题栏/输入栏），与 Web 的全屏抽屉一致。
        openDoc?.let { doc ->
            DocumentPanel(
                // 内联 artifact 没有文件名 → 用它的 title 当顶栏标题
                filename = doc.displayName,
                content = doc.content,
                // 【2026-10-11 修】format 原来写死空串 —— 内联 artifact 无文件名，
                // 只能靠它的 format 属性；不传的话 html/svg 全被当纯文本渲染。
                format = com.ccm.app.ui.common.DocumentMeta.resolveDocumentFormat(
                    format = doc.inlineFormat,
                    filename = doc.filename,
                    content = doc.content,
                ),
                onDismiss = { openDoc = null },
            )
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
    /** 标题栏右侧小插槽（todo 折叠徽标用 —— 零占位替代常驻条）。 */
    trailing: (@Composable () -> Unit)? = null,
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

        // 右：插槽（todo 徽标）+ Export
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            trailing?.invoke()
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
