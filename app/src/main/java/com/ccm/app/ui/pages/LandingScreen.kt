package com.ccm.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ccm.app.R
import com.ccm.app.ui.common.CcmPillButton
import com.ccm.app.ui.common.Gap2
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.common.CcmDivider
import com.ccm.app.ui.theme.CCMRadius
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 首页（Landing）—— 对齐 Web 的 `/` 路由。
 *
 * ## 实测结构（Playwright，393×852）
 * ```
 * ├── 顶栏（44dp，由 AppScaffold 提供）
 * ├── 标题区（居中，衬线族，17.48dp）
 * │     动态文案，见 [greetingFor]
 * ├── 输入卡片（w=343.16 → 315.7dp，圆角 11.57dp）
 * │     ├── 输入行（textarea，16px 浏览器默认字号）
 * │     └── 底部行（h=29.44）
 * │           ├── + 按钮（左）
 * │           └── 模型选择器 + 麦克风（右）
 * ├── 建议胶囊（5 个，**横向可滚动**）
 * │     写作 / 学习 / 编程 / 生活 / Claude 推荐
 * └── 底部状态行
 * ```
 *
 * ## ★ 胶囊行是可滚动的（实测确认）
 * ```
 * 容器 clientWidth = 393，scrollWidth = 571，overflow-x: auto，flex-wrap: nowrap
 * ```
 * 5 个胶囊**排不下**：第 5 个「Claude 推荐」x=374.94，**超出屏幕**（393）。
 * 所以 Compose 侧必须用 `horizontalScroll`，不能假设它们在一行内放得下。
 *
 * ## 实测关键值
 * | 元素 | 实测（屏幕值） |
 * |---|---|
 * | 标题 y | 107.69（含顶栏 44） |
 * | 标题字号 | 19 × 0.92 = **17.48**，行高 23.07 |
 * | 标题色 | `#373734`（亮）/ `#d6cec3`（暗，Web 用 `dark:!text-[#d6cec3]`） |
 * | 卡片 | y=151.39 / w=343.16 / h=104.98 / 圆角 12.576 → **11.57dp** |
 * | 卡片阴影 | `rgba(0,0,0,0.04) 0px 4px 20px 0px` |
 * | 卡片底 | `--bg-claude-input`（亮 #FFFFFF / 暗 #30302E） |
 * | 输入行高 | 50.42（含 pt-4=14.72） |
 * | 底部行 | h=29.44，mt=11.04，gap=7.36 |
 * | 胶囊行 | y=271.09，h=29.44，gap=**8px**（未乘 zoom，因为容器有反向补偿） |
 *
 * @param greeting     标题文案（动态，见 [greetingFor]）
 * @param onSend       发送消息（输入框回车）
 */
@Composable
fun LandingScreen(
    modifier: Modifier = Modifier,
    greeting: String = greetingFor(null),
    onSend: (String, List<String>) -> Unit = { _, _ -> },
    modelLabel: String = "未配置模型",
    /** 点首页模型 chip → 打开模型选择器（第22批接通，原 TODO 空转）。 */
    onModelClick: () -> Unit = {},
) {
    val colors = CCMTheme.colors

    // ★ 2026-09-28 第17批：input 从 InputCard 内上提到这里 ——
    //   建议面板要点「填入输入框」，state 在子组件里外面够不着。
    var input by remember { mutableStateOf("") }
    // 待发送的图片路径（第18批：+ 按钮选图 → 发送时随消息带上）
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    // 展开的建议分类（null = 收起）—— 对齐 Web activeLandingPromptSection 的 toggle
    var activeSection by remember { mutableStateOf<PromptSection?>(null) }
    // 灵感库（assets/inspirations.json，首次组合加载一次）
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val library = remember { com.ccm.app.core.inspiration.InspirationLibrary.ensure(ctx) }

    // ── 图片选择（第18批）─────────────────────────────────────────
    // PhotoPicker（GetMultipleContents）免权限；URI 要拷成真实路径
    // （core 按路径读），拷贝放 IO 线程防卡主线程。
    val ioScope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
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


    // ★ 结构对齐 Web 的三层（实测链，见 KDoc）：
    //   scroll 容器  px-2 (8) + pt-[64px]
    //     └ 内容列  max-w-[373px]（= 672 × 0.92 / 1.08696 的反向 zoom 折算）居中
    //         └ 胶囊行  mx-[-8px] + px-[8px]，抵消父级内边距后**满宽可滚**
    //   原来用 `padding(horizontal = 24.92)` 一把梭，导致胶囊行被压在 24.92 边距里、
    //   起点 x=24.92（Web 是 12.05），且可滚区域变窄、第 5 个胶囊提前被裁。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain)
            // ★ 2026-09-29 键盘遮挡：edge-to-edge 下 adjustResize 失效，
            //   键盘弹出会盖住输入卡。imePadding 缩小滚动区域；
            //   已聚焦的 BasicTextField 由 Compose 自动 bring-into-view。
            .windowInsetsPadding(
                WindowInsets.ime.union(
                    WindowInsets.navigationBars,
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),          // Web: px-2
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 373.dp)            // Web: max-w-[672px] 折算后 373
                .align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 标题（实测 y=107.69，顶栏 40.47 → 需再留 67.22）──────────
            Spacer(Modifier.height(67.22.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.36.dp),
            ) {
                // 花朵图标（hero-star.svg，橙色 #D97757）
                PainterIcon(R.drawable.ic_hero_star, size = 22.dp, tint = colors.claudeOrange)
                Text(
                    text = greeting,
                    style = CCMText.titleSerif,
                    color = if (CCMTheme.isDark) Color(0xFFD6CEC3) else Color(0xFF373734),
                    textAlign = TextAlign.Center,
                )
            }

            // 标题底 130.77 → 卡片顶 151.39
            Spacer(Modifier.height(20.62.dp))

            // ── 已选图片管理条（第19批）─────────────────────────────
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                ) {
                    pendingImages.forEach { path ->
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { pendingImages = pendingImages - path },
                        ) {
                            com.ccm.app.ui.common.ThumbImage(
                                path = path,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 8.dp,
                            )
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

            // ── 输入卡片 ─────────────────────────────────────────────
            InputCard(
                value = input,
                onValueChange = { input = it },
                onSend = { text ->
                    sendWithImages(onSend, text, pendingImages) {
                        pendingImages = emptyList()
                        input = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                modelLabel = modelLabel,
                onModelClick = onModelClick,
                attachedCount = pendingImages.size,
                onAttach = { launcher.launch("image/*") },
            )

            // 卡片底 256.37 → 胶囊顶 271.09
            Spacer(Modifier.height(14.72.dp))

            // ── 建议面板（点胶囊展开，对齐 Web activePromptSection）──
            //   位置：input 卡与胶囊行之间（Web 同序：mt-12 面板在 tabs 上方）。
            activeSection?.let { sec ->
                PromptSuggestionPanel(
                    section = sec,
                    library = library,
                    onDismiss = { activeSection = null },
                    onPick = { item ->
                        // Web 行为（MainContent.tsx:4867）：收起面板 +
                        // setInputText(starting_prompt)，**不直接发送**
                        input = item.starting_prompt
                        activeSection = null
                    },
                )
                Spacer(Modifier.height(14.72.dp))
            }

            // ── 建议胶囊（横向可滚动）─────────────────────────────────
            //   mx-[-8px] 抵消外层 px-2，让可滚区域回到满宽（Web 的 scrollWidth=571）
            //
            // ⚠️ 【2026-09-27 修崩溃】原来写的是 `Modifier.padding(horizontal = (-8).dp)`
            //    —— Compose 的 padding() **不接受负值**，运行时会抛
            //    IllegalArgumentException（要求 ≥ 0）。这不是编译期能发现的：
            //    check_kotlin 只查语法，CI 只编译，都要等**真机点开这一屏**才崩。
            //    实测崩溃栈：LandingScreen.kt:143 → PaddingKt.padding-VpY3zN4
            //
            //    CSS 的负 margin 在 Compose 里的等价物是 `offset`（允许负值）。
            //    offset 只影响绘制位置、不改变测量尺寸，正好符合「把可滚区域往左挪
            //    8dp 抵消父容器 padding」的意图。
            PromptPills(
                // Web（MainContent.tsx:4842）：toggle —— 再点同一个分类收起
                onPick = { sec ->
                    activeSection = if (activeSection == sec) null else sec
                },
                modifier = Modifier.offset(x = (-8).dp),
            )

            Spacer(Modifier.height(40.dp))
        }
    }
}

/**
 * 首页问候语 —— 对齐 `MainContent.tsx:1594`。
 *
 * Web 原文：
 * ```js
 * const general = [`有什么我可以帮你？`, `今天需要什么帮助？`, `开始工作吧，${name}`, `随时可以开始，${name}`];
 * ```
 * 另有按时间段的问候（"早安，Jay"）。这里实现时间段分支。
 *
 * @param name 用户名；`null` 时用无名字的通用文案
 */
fun greetingFor(name: String?, hourOfDay: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)): String {
    if (name.isNullOrBlank()) {
        return if (hourOfDay in 5..11) "有什么我可以帮你？" else "今天需要什么帮助？"
    }
    return when (hourOfDay) {
        in 5..11 -> "早安，$name"
        in 12..17 -> "下午好，$name"
        in 18..22 -> "晚上好，$name"
        else -> "夜深了，$name"
    }
}

/**
 * 输入卡片 —— 对齐 Web 的 `bg-claude-input border shadow-... flex flex-col`。
 *
 * 实测：w=343.16 / h=104.98 / 圆角 **12.576px**（移动端 `rounded-[20px]` 覆盖了源码的 22）
 * → 屏幕 **11.57dp**。
 */
@Composable
private fun InputCard(
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    modelLabel: String = "未配置模型",
    onModelClick: () -> Unit = {},
    // ★ 2026-09-28：受控化 —— input state 上提到 LandingScreen
    //   （建议面板要往里填 prompt），这里只转发。
    value: String = "",
    onValueChange: (String) -> Unit = {},
    /** 已选待发图片数（第18批，+ 按钮角标）。 */
    attachedCount: Int = 0,
    /** 点 + → 拉起图片多选。 */
    onAttach: () -> Unit = {},
) {
    val colors = CCMTheme.colors
    // 别名保持函数体内既有引用不变
    val input = value
    // ★ 亮色下 Web 的卡片边框是**透明**的（computed: 1.08696px solid rgba(0,0,0,0)）。
    //   类名里的 `border` 只是占位，hover/focus 时才显色。画成 colors.border 会多出
    //   一圈肉眼可见的灰边 —— 这是「卡片比 Web 脏」的第一来源。
    //   暗色下 Web 覆盖成 #3a3a38，所以只有暗色才真的画。
    val borderColor = if (CCMTheme.isDark) Color(0xFF3A3A38) else Color.Transparent

    Box(
        modifier = modifier
            // ★ 顺序要紧：shadow 必须在 clip **之前**。
            //   Compose 的 modifier 链从左到右绘制，shadow 排在 clip 之后时，
            //   阴影会被圆角裁掉外侧、只在内部留下一圈深色，看着像卡片里套了个灰环
            //   （实测 9dp 宽、#C9C9C9）—— 这是「卡片比 Web 脏」的第二来源。
            //   Web 原值：`0 3.68px 18.4px rgba(0,0,0,0.04)`，4% 极淡，用 1dp 近似。
            .shadow(
                elevation = 1.dp,
                shape = RoundedCornerShape(CCMRadius.r20),
                clip = false,
            )
            .clip(RoundedCornerShape(CCMRadius.r20))     // 11.57dp
            .background(colors.input)
            .border(1.dp, borderColor, RoundedCornerShape(CCMRadius.r20)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // ★ 实测推导（屏幕值）：textarea.x(36.41) − card.x(24.92) = **11.49**
                //   垂直同理：textarea.y(161.06) − card.y(151.39) = **9.67**
                //   底部也自洽：card.bottom(256.37) − row.bottom(246.70) = 9.67 ✓
                //   （原来写 14.72，卡片因此矮了 14.4dp）
                .padding(horizontal = 11.49.dp, vertical = 9.67.dp),
        ) {
            // ── 输入区（min-h 50.42）──────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.42.dp)
                    // textarea 自身 padding-left 6px × 0.92
                    .padding(start = 5.52.dp),
            ) {
                // ★ 真输入框（2026-09-27）：能打字、回车发送
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (input.isEmpty()) {
                        Text(
                            text = "今天需要什么帮助？",
                            style = CCMText.body16.copy(fontSize = CCMText.body16.fontSize),
                            color = colors.textSecondary,
                        )
                    }
                    BasicTextField(
                        value = input,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = CCMText.body16.copy(
                            fontSize = CCMText.body16.fontSize,
                            color = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
                        ),
                        cursorBrush = SolidColor(colors.claudeOrange),
                        // 回车行为跟设置走（与对话页 InputBar 同一机制）
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (com.ccm.app.ui.theme.UiPrefs.sendByEnter.value) {
                                ImeAction.Send
                            } else ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (input.isNotBlank() && com.ccm.app.ui.theme.UiPrefs.sendByEnter.value) {
                                    onSend(input)   // 外层已是带图包装（card call 处）
                                }
                            },
                        ),
                    )
                }
            }

            // ── 底部行（h=29.44，与输入区间距 5.82）────────────────
            //   ★ 不要再加水平 padding —— 外层 Column 已经有 11.49，
            //     实测 + 按钮 x=36.40 与 textarea x=36.41 左对齐。
            Spacer(Modifier.height(5.82.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(29.44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 左：+ 按钮（上传文件）
                // ★ 2026-09-27：原来只有图标没有 clickable —— 点了什么都不发生。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PainterIcon(
                        R.drawable.ic_input_plus,
                        size = 20.dp,
                        tint = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
                        modifier = Modifier.clickable(onClick = onAttach),
                    )
                    // 已选图片数（有才显示 —— 发送后清零消失）
                    if (attachedCount > 0) {
                        Text(
                            text = "图×$attachedCount",
                            style = CCMText.body12,
                            color = colors.claudeOrange,
                        )
                    }
                }

                // 右：模型选择器 + 麦克风 + 发送（★ 2026-09-27 加发送按钮）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gap2),
                ) {
                    // ★ 2026-09-27：ModelChip 原来写死 "Sonnet 4.6" 且 onClick 是空的
                    //
                    // ⚠️ 模型名**不能**在这里直接读配置 —— InputCard 每帧都在重组，
                    //    而 AppConfig.load 会读磁盘 + 解析 JSON，实测导致
                    //    `Skipped 39 frames`（主线程堵死 → 所有按钮点不动）。
                    //    所以从上层传入（LandingScreen 用 remember 缓存过一次）。
                    ModelChip(
                        modelName = modelLabel,
                        onClick = onModelClick,
                    )
                    // ★ L1 真接通（2026-09-29 用户要求补能力而非置灰）：
                    //   系统 SpeechRecognizer 听写 → 整句回填输入框。
                    val voiceClick = com.ccm.app.ui.common.rememberVoiceInput(
                        onText = { text ->
                            // 本函数在 InputCard 内（input 是受控别名 val）——
                            // 走 onValueChange 转发给上层的 var input
                            onValueChange(if (value.isBlank()) text else "$value $text")
                        },
                    )
                    PainterIcon(
                        R.drawable.ic_voice_mode,
                        size = 20.dp,
                        tint = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
                        modifier = Modifier.clickable(onClick = voiceClick),
                    )
                    // 发送按钮：有内容才亮，点了发消息（对齐 Web 的 ↑ 按钮）
                    Box(
                        modifier = Modifier
                            .size(29.44.dp)
                            .clip(RoundedCornerShape(CCMRadius.md))
                            .background(
                                if (input.isNotBlank()) colors.claudeOrange
                                else Color.Transparent
                            )
                            .clickable(enabled = input.isNotBlank()) {
                                onSend(input)   // 外层带图包装负责清空 input/pending
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "↑",
                            style = CCMText.body14.copy(fontSize = 15.sp),
                            color = if (input.isNotBlank()) Color.White else colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 模型选择器 —— 对齐 Web 的
 * `flex h-[32px] items-center gap-[6px] rounded-[6px] px-[10px] text-[14px]`。
 *
 * 实测：w=195.11 / h=29.44 / 圆角 6px（无移动端覆盖 → 5.52dp）
 */
@Composable
private fun ModelChip(
    modelName: String = "未配置模型",
    onClick: () -> Unit = {},
) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .height(29.44.dp)
            .clip(RoundedCornerShape(CCMRadius.md))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.52.dp),
    ) {
        Text(
            text = modelName,
            style = CCMText.body14,
            color = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
            maxLines = 1,
        )
        // 下拉箭头
        PainterIcon(
            R.drawable.ic_model_caret,
            size = 12.dp,
            tint = colors.textSecondary,
        )
    }
}

/**
 * 建议胶囊行 —— 5 个标签横向排列。
 *
 * ## ★ 横向可滚动（实测）
 * 容器 `clientWidth=393 / scrollWidth=571 / overflow-x: auto / nowrap`。
 * 5 个胶囊放不下，第 5 个 x=374.94 超出屏幕。
 *
 * 实测各胶囊宽度（屏幕值）：写作 76 / 学习 77 / 编程 75.14 / 生活 97.95 / Claude 推荐 136.56
 * 间距 gap **8px**（容器有反向 zoom，未乘 0.92）。
 *
 * > Web 隐藏了滚动条（`.landing-prompt-tabs` 在 index.css 里有 `display:none` 规则）。
 */
@Composable
private fun PromptPills(
    onPick: (PromptSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Web: `h-[32px] px-[8px]`（右侧另有 pr-[28px] 是滚动渐隐留白，
            //  在 Compose 里由 horizontalScroll 自身处理，不重复加）
            .height(29.44.dp)
            .padding(start = 7.36.dp, end = 25.76.dp)
            .horizontalScroll(rememberScrollState()),
        // Web: gap-[8px] → 8 × 0.92 = 7.36
        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PromptSection.entries.forEach { sec ->
            CcmPillButton(
                label = sec.label,
                iconRes = sec.iconRes,
                onClick = { onPick(sec) },
            )
        }
    }
}

/**
 * 5 个建议分组 —— 数据来自 `MainContent.tsx:99-160` 的 `LANDING_PROMPT_SECTIONS`。
 *
 * [width] 是 Web 里写死的测量宽度（点击后展开的建议列表宽度要与之匹配）。
 * 本机 393 视口下 5 个胶囊**刚好排满一行**（Web 的容器宽 361.55）。
 */
enum class PromptSection(
    val label: String,
    val iconRes: Int,
    val width: Float,
    /**
     * 该分类的 5 个灵感名 —— 与 Web `LANDING_PROMPT_SECTIONS` 的
     * `pickInspirations([...])` 名单逐字对齐（2026-09-28 第17批）。
     * 名字去 [InspirationLibrary] 查完整 starting_prompt。
     */
    val items: List<String>,
) {
    WRITE("写作", R.drawable.ic_prompt_write, 82.609f, listOf(
        "Writing editor", "Email writing assistant", "Meeting notes summary",
        "One-pager PRD maker", "My weekly chronicle",
    )),
    LEARN("学习", R.drawable.ic_prompt_learn, 83.703f, listOf(
        "Flashcards", "PyLingo", "Molecule studio",
        "Language learning tutor", "Origin stories",
    )),
    CODE("编程", R.drawable.ic_prompt_code, 81.688f, listOf(
        "CodeVerter", "Project dashboard generator", "Interactive drum machine",
        "Join dots", "Piano",
    )),
    LIFE("生活", R.drawable.ic_prompt_life, 106.484f, listOf(
        "Your life in weeks", "Dream interpreter", "Team activity ideas",
        "Magic in the grass", "How petty are you?",
    )),
    CHOICE("Claude 推荐", R.drawable.ic_prompt_choice, 148.453f, listOf(
        "Historical SVG amphitheater", "Stories in the sky", "Word cloud maker",
        "Sakura serenity", "Better than very",
    )),
}

/**
 * 首页建议面板 —— 对齐 Web `MainContent.tsx:4881-4916` 的 activePromptSection。
 *
 * 结构：圆角 16 卡片 = 头部（图标 + 分类名 + 关闭 X）→ 分割线 → items 列表。
 * 实测（Tailwind × 0.92）：外框 border rgba(31,31,30,0.15) / 阴影 0 4px 20px 4%；
 * 头部 px-16 py-12；每项 min-h-44 + px-16 py-10，项间 border-b 12%；
 * 点项 = 收起面板 + **填入输入框**（不发送，Web setInputText 同款）。
 *
 * @param library 灵感库（name → item，查 starting_prompt）
 */
@Composable
private fun PromptSuggestionPanel(
    section: PromptSection,
    library: Map<String, com.ccm.app.core.inspiration.InspirationItem>,
    onDismiss: () -> Unit,
    onPick: (com.ccm.app.core.inspiration.InspirationItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CCMTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Color(0x261F1F1E), RoundedCornerShape(16.dp))   // rgba(31,31,30,0.15)
            .background(if (CCMTheme.isDark) colors.input else Color.White)
            .shadow(elevation = 1.dp, shape = RoundedCornerShape(16.dp), clip = false),
    ) {
        // ── 头部（px-16 py-12）────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PainterIcon(section.iconRes, size = 18.dp, tint = colors.textMain)
                Text(
                    text = section.label,
                    style = CCMText.body14,
                    color = colors.textSecondary,
                )
            }
            // 关闭 X（Web Close suggestions 按钮）
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", style = CCMText.body12, color = colors.textSecondary)
            }
        }

        CcmDivider(color = Color(0x1F1F1F1E))   // rgba(31,31,30,0.12)

        // ── 建议列表 ───────────────────────────────────────────────
        section.items.forEachIndexed { index, name ->
            val item = library[name] ?: return@forEachIndexed
            // 项间分隔线（Web: border-b rgba(31,31,30,0.12)，最后一项无）
            // ⚠️ 不能用 Modifier.border(bottom=...) —— Compose 的 border()
            //    只有整体边框 (width,color,shape)，没有方向参数。
            if (index > 0) {
                CcmDivider(color = Color(0x1F1F1F1E))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(item) }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = CCMText.body14,
                        color = colors.textMain,
                    )
                    if (item.description.isNotBlank()) {
                        Text(
                            text = item.description,
                            style = CCMText.body12,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text("→", style = CCMText.body14, color = colors.textSecondary)
            }
        }
    }
}

/**
 * 首页发送包装（第18批）：带图发送 + 清空输入/待发图片。
 *
 * 抽成顶层函数是因为 InputCard 的两个发送入口（回车 / ↑按钮）都只拿得到
 * `(String) -> Unit`，pendingImages 的清空必须在能访问它的外层完成。
 */
private fun sendWithImages(
    onSend: (String, List<String>) -> Unit,
    text: String,
    images: List<String>,
    onSent: () -> Unit,
) {
    if (text.isBlank()) return
    onSend(text, images)
    onSent()   // 清 pendingImages（input 的清空在 onSend 成功路径由外层做）
}
