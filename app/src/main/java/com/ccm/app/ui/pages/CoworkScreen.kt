package com.ccm.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 协作页 —— 对齐 Web 的 `/cowork` 路由（`CoworkPage.tsx`，381 行）。
 *
 * ## 实测数据（Playwright，393×852）
 * | 元素 | 实测值 |
 * |---|---|
 * | `h1` 大标题 | **36 / 41.4 / fw600** / **Inter 族** / color **#313131** / x=58.86 y=128.78 |
 * | 副标题 | 13.362 / 20.043 / textMain / x=22.08 y=216 |
 * | 输入卡片 | 325.81×116.81 / 圆角 **12.576** / **边框 #C7C7C7** / pad `16 16 12` |
 * | 底部下拉 | h=43.19 / 圆角 6 / pad 4×8 / fs **13** / 色 **#61615F** |
 * | 分组标题「了解协作模式」 | 16 / 24 / fw500 / **#A19F9C**（浅灰）/ y=510.73 |
 * | 清单圆圈 | 33.11×33.11 / 全圆 / **bg #D1C9BA**（暖褐）/ 有勾时显示 |
 *
 * ## ★ 三处与首页不同的地方
 * 1. **标题用 Inter 族**（首页用衬线 Anthropic Serif）
 * 2. **字号 36**（首页 19）—— 协作页是「大标题」风格
 * 3. **输入卡片边框是 #C7C7C7**（比首页的 `--border-claude` 深）
 * 4. **分组标题色 #A19F9C**（比 textSecondary #666666 浅很多）
 *
 * ## 底部下拉（Web 特有）
 * 三个下拉：`在项目中工作` / `提问` / 模型选择器。
 * 实测高度 43.19（含 label 和值两行），gap 4。
 *
 * @param checklist  清单项
 * @param onSend     发送
 */
@Composable
fun CoworkScreen(
    modifier: Modifier = Modifier,
    checklist: List<CoworkChecklistItem> = DefaultCoworkChecklist,
    modelName: String = "未配置模型",
    onSend: (String, List<String>) -> Unit = { _, _ -> },
) {
    val colors = CCMTheme.colors
    var showSafeTips by remember { mutableStateOf(false) }   // 说明弹窗（第23批）

    // 模型下拉：真实 Provider（点选 = setCurrent + 重建会话，与对话页同机制）

    // ── 第30批：真能力（用户要求补上而非置灰）─────────────────────
    // 待发图片（+ 按钮 → PhotoPicker，随 onSend 带进主对话）
    var pendingImages by remember { mutableStateOf<List<String>>(emptyList()) }
    val ioScope = rememberCoroutineScope()
    val ctxCow = androidx.compose.ui.platform.LocalContext.current
    val attachLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            ioScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val paths = uris.mapNotNull {
                    com.ccm.app.core.image.AttachmentCache.copyToCache(ctxCow, it)
                }
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (paths.isNotEmpty()) pendingImages = pendingImages + paths
                }
            }
        }
    }
    val attachClick: () -> Unit = { attachLauncher.launch("image/*") }
    // 输入文本上提到本层 —— 语音回填、草稿落盘都要从外面够得着
    // （CoworkInputCard 内部持 local state 是第30批修过的同款结构问题）
    var coworkInput by remember {
        androidx.compose.runtime.mutableStateOf(
            com.ccm.app.ui.theme.UiPrefs.coworkDraft.value,
        )
    }
    // 麦克风：系统听写 → 追加到输入框
    val voiceClick: () -> Unit = com.ccm.app.ui.common.rememberVoiceInput(
        onText = { text ->
            coworkInput = if (coworkInput.isBlank()) text else coworkInput + " " + text
            com.ccm.app.ui.theme.UiPrefs.setCoworkDraft(coworkInput)
        },
    )


    Column(
        modifier = modifier
            .fillMaxSize()
            // ★ 2026-09-29 键盘遮挡（同对话页/首页修法）：edge-to-edge 下
            //   adjustResize 失效，键盘弹出盖住输入卡。
            .windowInsetsPadding(
                WindowInsets.ime.union(
                    WindowInsets.navigationBars,
                ),
            )
            .background(colors.bgMain)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.08.dp),
    ) {
        Spacer(Modifier.height(40.78.dp))       // h1 y=128.78 − 顶栏 44 − 内部偏移

        // ── 大标题（星芒图标 + 两行文字）─────────────────────────────
        Row(verticalAlignment = Alignment.Top) {
            // 橙色星芒（hero-star.svg）
            PainterIcon(
                R.drawable.ic_hero_star,
                size = 26.dp,
                tint = colors.claudeOrange,
                modifier = Modifier.padding(top = 8.dp, end = 6.44.dp),
            )
            Text(
                text = "完成清单上的一件事吧",
                // 【2026-10-06 问题33 修复】用户报「协作模式页很多字是竖着的」。
                // 根因：36sp 字号 + 星芒图标挤占宽度 → 每行只放得下 2~3 个字，
                // 视觉上就是「竖排」。36sp 是**桌面 Web 的值**，手机 393dp 宽放不下
                // （36 × 9 字 = 324dp + 星芒 32dp + 边距 44dp = 400dp > 393dp）。
                //
                // 对齐 Web 的移动端 clamp：`font-size: clamp(19px, 4.8vw, 40px)`
                // → 393px 下 4.8vw = 18.86px → **17.36sp**（×0.92）
                style = CCMText.body32.copy(
                    fontSize = 17.36.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = Color(0xFF313131),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(9.22.dp))        // 标题底 204.97 → 副标题顶 216

        // ── 副标题（链接样式）─────────────────────────────────────────
        Text(
            text = "了解如何安全使用协作模式。",
            style = CCMText.body13,
            color = colors.textMain,
            modifier = Modifier
                .clickable { showSafeTips = true }
                .padding(vertical = 0.dp),
        )

        // 协作模式说明（第23批 —— 原 TODO 空转。文案按 Web 副标题语义自撰）
        if (showSafeTips) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showSafeTips = false },
                title = { androidx.compose.material3.Text("安全使用协作模式") },
                text = {
                    androidx.compose.material3.Text(
                        "协作模式下，AI 会在你确认后执行多步骤任务" +
                            "（改文件、跑命令、调用工具）。建议：\n\n" +
                            "· 先在小范围任务里试，确认输出符合预期再放大\n" +
                            "· 涉及删除/发布等不可逆操作时，逐条审阅它要做的事\n" +
                            "· 敏感凭据不要粘进对话，交给工具的环境变量处理",
                        style = CCMText.body13,
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showSafeTips = false },
                    ) { androidx.compose.material3.Text("知道了") }
                },
            )
        }

        Spacer(Modifier.height(20.41.dp))       // 副标题底 235.44 → 卡片顶 256.41

        // ── 输入卡片 ──────────────────────────────────────────────────
        CoworkInputCard(
            modelName = modelName,
            value = coworkInput,
            onValueChange = {
                coworkInput = it
                com.ccm.app.ui.theme.UiPrefs.setCoworkDraft(it)
            },
            // 包装：卡片只管文本，图片在本层 pendingImages 里随发送带上
            onSend = { t -> onSend(t, pendingImages); pendingImages = emptyList() },
            onVoice = voiceClick,
            onAttach = attachClick,
            attachCount = pendingImages.size,
        )

        Spacer(Modifier.height(39.6.dp))        // 卡片底 373.22 → 分组标题顶 510.73 − 清单间距

        // ── 分组标题 ──────────────────────────────────────────────────
        Text(
            text = "了解协作模式",
            // 实测 16 / 24 / fw500 / #A19F9C
            style = CCMText.body16.copy(
                fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
            ),
            color = Color(0xFFA19F9C),
        )

        Spacer(Modifier.height(31.27.dp))       // 标题底 532.81 → 首个圆圈顶 564.08

        // ── 清单 ──────────────────────────────────────────────────────
        checklist.forEachIndexed { index, item ->
            CoworkChecklistRow(item = item)
            if (index < checklist.size - 1) {
                Spacer(Modifier.height(16.dp))
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * 输入卡片 —— 实测 325.81×116.81 / 圆角 12.576 / 边框 #C7C7C7 / pad `16 16 12`。
 *
 * 结构与首页类似，但**多了一行底部下拉**（在项目中工作 / 提问 / 模型）。
 */
@Composable
private fun CoworkInputCard(
    modelName: String,
    onSend: (String) -> Unit,
    value: String = "",
    onValueChange: (String) -> Unit = {},
    onVoice: () -> Unit = {},
    onAttach: () -> Unit = {},
    attachCount: Int = 0,
) {
    val colors = CCMTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.576.dp))
            .background(colors.input)
            .border(1.dp, Color(0xFFC7C7C7), RoundedCornerShape(12.576.dp))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    ) {
        // 输入区 —— 受控（value/onValueChange 从 CoworkScreen 来，第30批上提）。
        val coworkInput = value
        // 下拉 state 必须在本函数内 —— 下拉行是输入卡的一部分，
        // 声明放主函数会够不着（#202 unresolved reference 实锤）。
        var projectChoice by remember {
            androidx.compose.runtime.mutableStateOf(
                com.ccm.app.ui.theme.UiPrefs.coworkProject.value,
            )
        }
        // ★ M5：key=modelName —— 设置页换模型后下拉要跟着刷新
        var modelChoice by remember(modelName) { androidx.compose.runtime.mutableStateOf(modelName) }
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp)) {
            if (coworkInput.isEmpty()) {
                Text(
                    text = "今天需要什么帮助？",
                    style = CCMText.body14,
                    color = colors.textSecondary,
                )
            }
            androidx.compose.foundation.text.BasicTextField(
                value = coworkInput,
                onValueChange = onValueChange,
                textStyle = CCMText.body14.copy(
                    color = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.claudeOrange),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 底部行：+（左）/ 麦克风 · 发送（右）─────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ★ L4 真接通（第30批，用户要求补能力而非置灰）：
                //   + = 选图（随发送带进主对话）；麦克风 = 系统听写回填。
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box {
                        PainterIcon(
                            R.drawable.ic_input_plus,
                            size = 20.dp,
                            tint = colors.textMain,
                            modifier = Modifier.clickable(onClick = onAttach),
                        )
                        if (attachCount > 0) {
                            Text(
                                text = "$attachCount",
                                style = CCMText.body10,
                                color = colors.bgMain,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(start = 8.dp, top = 6.dp),
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.claudeOrange),
                            )
                        }
                    }
                    PainterIcon(
                        R.drawable.ic_voice_mode,
                        size = 20.dp,
                        tint = colors.textMain,
                        modifier = Modifier.clickable(onClick = onVoice),
                    )
                }
                // ★ H1（audit-pages #1）：输入卡原来没有发送按钮、onSend 是死参数 ——
                //   文字永远发不出去。有内容才亮，发完清空并落盘草稿。
                Box(
                    modifier = Modifier
                        .size(29.44.dp)
                        .clip(RoundedCornerShape(7.36.dp))
                        .background(if (coworkInput.isNotBlank()) colors.claudeOrange else colors.border)
                        .clickable(enabled = coworkInput.isNotBlank()) {
                            onSend(coworkInput)
                            onValueChange("")
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("↑", style = CCMText.body14.copy(fontSize = 15.sp), color = Color.White)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── 下拉行：在项目中工作 / 提问 / 模型 ──────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 项目下拉（Web PROJECT_OPTIONS 三选项）
            CoworkDropdown(
                label = "在项\n目中\n工作",
                value = projectChoice,
                options = listOf("在项目中工作", "个人", "研究"),
                onPick = {
                    projectChoice = it
                    com.ccm.app.ui.theme.UiPrefs.setCoworkProject(it)
                },
            )
            // 「提问」：Web 端（CoworkPage.tsx:271）这个按钮**本身也没接
            // onClick** —— 对齐 Web 保持展示态，不假接。
            CoworkDropdown(label = "提\n问", value = "")
            Spacer(Modifier.weight(1f))
            // 模型下拉：走真实 Provider 列表（不照抄 Web 的写死 Opus 4.7）
            CoworkDropdown(
                label = "",
                value = modelChoice,
                // 【2026-10-06 修】modelOptions() 内部读 ProviderStore.list()
                // → AppConfig.load → file.readText() + JSON 反序列化。
                // 原来直接写在 composition 里 —— 协作页读了输入框 state，
                // 每敲一个字符重组一次就**读一次盘**（实测 LandingScreen 同款
                // 问题导致 Skipped 39 frames）。
                // 包 remember，用 modelChoice 做 key（切模型后仍能刷新）。
                options = remember(modelChoice) { modelOptions() },
                onPick = { pick ->
                    modelChoice = pick
                    // 切 Provider 让下次请求生效（ApiClient 是装配期快照）
                    //
                    // 【2026-10-06 修「选了不生效」】原来调 openSession(sessionId)
                    // 期望重建，但那个函数开头有守卫
                    //   `if (id == sessionId && session != null) return session`
                    // 传的就是当前 id、session 也非 null → 第一行就 return，
                    // 重建逻辑一行没跑 → 回对话还是旧模型。
                    // 改用 rebuild（真正的重建 API，与设置页关闭时走同一条）。
                    com.ccm.app.AppGraph.storage?.let { st ->
                        val store = com.ccm.app.core.provider.ProviderStore(st)
                        store.list().firstOrNull { "${it.name} · ${it.model}" == pick }
                            ?.let { store.setCurrent(it.id) }
                    }
                    com.ccm.app.AppGraph.appScope?.let { sc ->
                        com.ccm.app.AppGraph.rebuild(ctxCow.applicationContext, sc)
                    }
                },
            )
        }
    }
}

/**
 * 协作页的下拉选择器 —— 实测 h=43.19 / 圆角 6 / pad 4×8 / fs 13 / 色 #61615F。
 *
 * 结构：上方小 label（可多行）+ 下方值 + 右侧箭头。
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun CoworkDropdown(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    options: List<String> = emptyList(),
    onPick: (String) -> Unit = {},
) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            // 无 options 的下拉（如「提问」）保持展示态 —— 对齐 Web
            .clickable(enabled = options.isNotEmpty()) { open = true }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (options.isNotEmpty() && open) {
            // ★ 面板化尾巴（2026-09-29）：项目/模型下拉原来是 AlertDialog 弹窗，
            //   与其他选择器（已改底部面板）不一致 —— 统一成底部滑出面板。
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { open = false },
                containerColor = CCMTheme.colors.bgMain,
            ) {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                ) {
                    if (label.isNotBlank()) {
                        Text(
                            text = label.replace("\n", ""),
                            style = CCMText.body13,
                            color = CCMTheme.colors.textMain,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                    options.forEach { opt ->
                        val selected = opt == value
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(opt)
                                    open = false
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = opt,
                                style = CCMText.body13,
                                color = if (selected) CCMTheme.colors.accent
                                else CCMTheme.colors.textMain,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) Text("✓", style = CCMText.body13, color = CCMTheme.colors.accent)
                        }
                    }
                }
            }
        }
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = CCMText.body12.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                color = Color(0xFF61615F),
            )
        }
        if (value.isNotEmpty()) {
            Text(
                text = value,
                style = CCMText.body12.copy(fontSize = 13.sp, lineHeight = 19.5.sp),
                color = Color(0xFF61615F),
                maxLines = 1,
            )
        }
        // 下拉箭头
        PainterIcon(
            R.drawable.ic_model_caret,
            size = 10.dp,
            tint = Color(0xFF61615F),
        )
    }
}

/**
 * 清单项 —— 左侧圆形勾选圈 + 标题 + 描述。
 *
 * 实测：圆圈 33.11×33.11 / 全圆 / 已完成时 bg **#D1C9BA**（暖褐）。
 */
@Composable
private fun CoworkChecklistRow(item: CoworkChecklistItem) {
    val colors = CCMTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(11.04.dp),
    ) {
        // 勾选圈
        Box(
            modifier = Modifier
                .size(33.11.dp)
                .clip(RoundedCornerShape(16.56.dp))
                .background(if (item.done) Color(0xFFD1C9BA) else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (item.done) Color(0xFFD1C9BA) else colors.border,
                    shape = RoundedCornerShape(16.56.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (item.done) {
                CheckGlyph(tint = Color.White, size = 15.dp, strokeWidth = 2f)
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                color = colors.textMain,
            )
            if (item.description.isNotBlank()) {
                Spacer(Modifier.height(3.68.dp))
                Text(
                    text = item.description,
                    style = CCMText.body12,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** 画勾选符号 */
@Composable
private fun CheckGlyph(tint: Color, size: androidx.compose.ui.unit.Dp, strokeWidth: Float) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = strokeWidth / 24f * w
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.2f, h * 0.5f)
            lineTo(w * 0.42f, h * 0.72f)
            lineTo(w * 0.8f, h * 0.3f)
        }
        drawPath(
            path = path,
            color = tint,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = stroke,
                cap = StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round,
            ),
        )
    }
}

/** 清单项 */
data class CoworkChecklistItem(
    val title: String,
    val description: String = "",
    val done: Boolean = false,
)

/** 默认清单 —— 来自 `CoworkPage.tsx` 的静态内容（截图实测文案） */
val DefaultCoworkChecklist = listOf(
    CoworkChecklistItem("下载协作模式", "欢迎！", done = true),
    CoworkChecklistItem("连接日常工具", "Claude 越了解你的工作环境，就能帮你完成越多事情。", done = true),
    CoworkChecklistItem("根据你的角色定制 Claude", "添加现成的工具和工作流。"),
    CoworkChecklistItem("让 Claude 创建内容", "试试创建表格、文档或演示文稿。"),
    CoworkChecklistItem("安排周期性任务", "适合设置提醒、报告或定期检查。"),
)

/** 当前配置里可选的模型（`名称 · 模型`），给协作页模型下拉用。 */
private fun modelOptions(): List<String> =
    com.ccm.app.AppGraph.storage
        ?.let { com.ccm.app.core.provider.ProviderStore(it).list() }
        ?.filter { it.enabled }
        ?.map { "${it.name} · ${it.model}" }
        ?: emptyList()
