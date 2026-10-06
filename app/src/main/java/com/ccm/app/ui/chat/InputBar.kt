package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 聊天输入栏 —— 对齐 Web `MainContent.tsx:5032-5210`。
 *
 * ## ★ 实测发现：对话页与首页的输入卡片**不是同一个组件**
 * Playwright 对比（393×852）：
 *
 * | 属性 | 首页（LandingScreen） | 对话页（ChatScreen） |
 * |---|---|---|
 * | 左右边距 | 24.92 | **14.72**（源码注释：移动端横向内距收到 8px） |
 * | 卡片宽度 | 343.16 | **363.6** |
 * | 圆角 | 12.576（被移动端 clamp 覆盖） | **22**（inline style，未被覆盖） |
 * | 边框 | `--border-claude` #E8E7E3 | 同 |
 * | 阴影 | `0 2px 8px rgba(0,0,0,0.02)` | 同 |
 *
 * **为什么圆角不同**：首页那份走 Tailwind 的 `rounded-[20px]`（被移动端媒体查询覆盖成 12.576），
 * 对话页这份用 React inline style `borderRadius: inputBarRadius`（= 22，**inline style 优先级最高，
 * 媒体查询覆盖不了**）。这是 Web 侧的历史遗留，但零变化要求我们照抄。
 *
 * ## 实测结构
 * ```
 * 卡片（bg-claude-input, border, 圆角 22）
 * ├── textarea（px-4 pt-4，min-h 48.6）
 * └── 底部行（h≈32，px-4 pb-4）
 *       ├── 左：+ 按钮 · token 计数（"201 tokens"）· 研究徽章
 *       └── 右：模型选择器 · 麦克风 · 发送按钮（橙色圆形）
 * ```
 *
 * ## 发送按钮
 * Web 用橙色圆角方块（`bg-claude-accent`），图标是上箭头。
 * 空输入时置灰不可点。
 *
 * @param value         当前输入文本
 * @param onValueChange 输入变化
 * @param onSend        发送（空文本时不触发）
 * @param onStop        停止（[running] 为 true 时替代发送按钮）
 * @param running       是否正在跑（跑时显示停止按钮）
 * @param modelName     模型显示名
 * @param tokenCount    token 计数（0 = 不显示）
 */
@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
    running: Boolean = false,
    modelName: String = "",  // 【2026-10-06 问题2】不再硬编码 Sonnet 4.6，由调用方传真实模型名
    tokenCount: Int = 0,
    /** 点模型选择器（2026-09-27 接通 —— 原来是写死的 TODO 空转） */
    onModelClick: () -> Unit = {},
    modelPickerContent: (@Composable () -> Unit)? = null,
    /** 点 + → 拉起图片多选（第18批）。 */
    onAttach: () -> Unit = {},
    /** 已选待发图片（管理条渲染，第19批）。 */
    attachedPaths: List<String> = emptyList(),
    /** 点麦克风 → 语音听写回填（第30批）。 */
    onVoice: () -> Unit = {},
    /** 从待发列表移除一张。 */
    onRemoveImage: (String) -> Unit = {},
) {
    val colors = CCMTheme.colors

    // ★ 第38批：发送后自动聚焦回输入框（连发消息不用每次手动点）。
    //   监听 running 从 true→false（一轮结束）+ value 清空（发送成功）时请求焦点。
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    val wasRunning = remember { mutableStateOf(false) }
    LaunchedEffect(running, value) {
        if (wasRunning.value && !running && value.isEmpty()) {
            try {
                focusRequester.requestFocus()
            } catch (_: Throwable) {}
        }
        wasRunning.value = running
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // ── 输入卡片 ──────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.72.dp)          // ★ 对话页是 8px→14.72（与首页不同）
                // ★ 顺序要紧：shadow 必须在 clip **之前**（同 LandingScreen）。
                //   排在 clip 之后时阴影被圆角裁掉外侧、内部留一圈深色灰环。
                .shadow(elevation = 1.dp, shape = RoundedCornerShape(22.dp), clip = false)
                .clip(RoundedCornerShape(22.dp))         // ★ 22，inline style 未被覆盖
                .background(colors.input)
                // 亮色下 Web 的卡片边框是**透明**的（1.08696px solid rgba(0,0,0,0)），
                // 画成 colors.border 会多一圈灰边。暗色才真画（#3a3a38）。
                .border(
                    width = 1.dp,
                    color = if (CCMTheme.isDark) Color(0xFF3A3A38) else Color.Transparent,
                    shape = RoundedCornerShape(22.dp),
                ),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── 输入区（px-4 pt-4，min-h 48.6）──────────────────────
                // ★ 第41批：原来只有 min —— 粘贴长文本会把输入卡撑爆
                //   （页面被顶走、发送按钮消失）。加 max=200dp + 内部可滚。
                val inputScroll = remember { androidx.compose.foundation.ScrollState(0) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.6.dp, max = 200.dp)
                        .padding(start = 14.72.dp, end = 14.72.dp, top = 14.72.dp),
                ) {
                    // ★ 2026-09-27：原来是两层死 Text（只显示不接受输入，
                    //   且 value 变化要靠外部驱动 —— 对话页 State 没绑 draft，
                    //   打字完全无效）。换成 BasicTextField 叠 placeholder，
                    //   写法照抄首页 LandingScreen（同一天修过的）。
                    if (value.isEmpty()) {
                        Text(
                            text = "今天需要什么帮助？",
                            // 实测 16px（浏览器默认，防移动端缩放）
                            style = CCMText.body16.copy(fontSize = 16.sp, lineHeight = 24.sp),
                            color = colors.textSecondary,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .verticalScroll(inputScroll),
                        textStyle = CCMText.body16.copy(
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            color = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
                        ),
                        cursorBrush = SolidColor(colors.claudeOrange),
                        // 回车行为跟设置走（2026-09-28 打通 —— 原来写死 Send，
                        // 设置页选「仅按钮」没用）。读 MutableState = 订阅，
                        // 设置页改了这里自动重组。
                        keyboardOptions = KeyboardOptions(
                            imeAction = if (com.ccm.app.ui.theme.UiPrefs.sendByEnter.value) {
                                ImeAction.Send
                            } else ImeAction.Default,
                        ),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                // 回车发送：空文本不触发（与 SendButton 的 enabled 一致）
                                if (value.isNotBlank() && com.ccm.app.ui.theme.UiPrefs.sendByEnter.value) onSend()
                            },
                        ),
                    )
                }

                Spacer(Modifier.height(11.04.dp))

                // ── 底部行 ────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.72.dp, end = 14.72.dp, bottom = 14.72.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // 左：+ 按钮 + 附件角标 + token 计数
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                    ) {
                        // 【2026-10-06 用户报「附件时语音键消失、发送键被挤出」】
                        // 原来「图×N」是独立 Text —— 占约 40dp 横向空间，
                        // 加上 token 计数（「30K/1.0M·3%」约 90dp）让左排暴涨，
                        // Row 挤压右排 → 语音键（无 requiredSize 保护）先被挤没。
                        // 改成叠在 + 图标右上角的小角标：**零额外宽度**。
                        Box {
                            PainterIcon(
                                R.drawable.ic_input_plus,
                                size = 20.dp,
                                tint = colors.textMain,
                                modifier = Modifier.clickable(onClick = onAttach),
                            )
                            if (attachedPaths.isNotEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 6.dp, y = (-4).dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(colors.claudeOrange)
                                        .padding(horizontal = 3.dp),
                                ) {
                                    Text(
                                        text = attachedPaths.size.toString(),
                                        style = CCMText.body11.copy(fontSize = 9.sp),
                                        color = Color.White,
                                    )
                                }
                            }
                        }
                        if (tokenCount > 0) {
                            // ★ webgap #7（2026-09-29）：原来只有裸 tokens 数 ——
                            //   看不出离上限多远。显示 `N / 上限 (xx%)` 并分档变色：
                            //   ≥90% 红（该 /compact 了）、≥70% 橙、其余灰。
                            val maxCtx = remember(Unit) {
                                com.ccm.app.AppGraph.storage?.let {
                                    com.ccm.app.core.provider.AppConfig
                                        .load(it.configFile).config.maxContextTokens
                                } ?: 1_000_000
                            }
                            val pct = if (maxCtx > 0) tokenCount * 100 / maxCtx else 0
                            val tint = when {
                                pct >= 90 -> Color(0xFFDC2626)
                                pct >= 70 -> Color(0xFFF59E0B)
                                else -> colors.textSecondary
                            }
                            // 【2026-10-06 问题30 修复】用户报「输入框内的上下文组件
                            // 不是圆环」—— Web 有个 SVG 圆环（`MainContent.tsx:5137`）：
                            //   <circle r=7 stroke=#d4d4d4 />   ← 底环
                            //   <circle r=7 strokeDasharray={dash} rotate(-90) />  ← 进度
                            // 现在用 Canvas 画同款（18×18、r=7、strokeWidth=2）。
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val ringColor = tint
                                androidx.compose.foundation.Canvas(
                                    modifier = Modifier.size(16.56.dp),   // 18px × 0.92
                                ) {
                                        val r = size.minDimension * 7f / 18f
                                        val stroke = size.minDimension * 2f / 18f
                                        val c = 2 * Math.PI * r
                                        // 底环 #d4d4d4
                                        drawCircle(
                                            color = Color(0xFFD4D4D4),
                                            radius = r,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                                        )
                                        // 进度环（从 12 点方向顺时针）
                                        drawArc(
                                            color = ringColor,
                                            startAngle = -90f,
                                            sweepAngle = (pct.coerceIn(0, 100) / 100f) * 360f,
                                            useCenter = false,
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(
                                                width = stroke,
                                                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                            ),
                                            topLeft = androidx.compose.ui.geometry.Offset(
                                                center.x - r, center.y - r,
                                            ),
                                            size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                                        )
                                }
                                Text(
                                    text = "${fmtTokens(tokenCount)} / ${fmtTokens(maxCtx)} · $pct%",
                                    style = CCMText.body12,
                                    color = tint,
                                )
                            }
                        }
                    }

                    // 右：模型 + 麦克风 + 发送/停止
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                    ) {
                        // 模型名是可压缩的那个（weight(1f, fill=false)）——
                        // 空间不够时它先截断，而不是把语音/发送键挤没。
                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            ModelChipInline(modelName = modelName, onClick = onModelClick)
                            modelPickerContent?.invoke()
                        }
                        // 【2026-10-06】加 requiredSize 保护 —— 与发送键同款。
                        // 原来没保护，横向空间不够时被 Row 压缩到 0（「语音键消失」）。
                        // requiredSize 无视父约束，强制保尺寸；挤压会转移到
                        // 模型名（它是设计好会截断的那个）。
                        PainterIcon(
                            R.drawable.ic_voice_mode,
                            size = 20.dp,
                            tint = colors.textMain,
                            modifier = Modifier
                                .requiredSize(20.dp)
                                .clickable(onClick = onVoice),
                        )
                        // 【2026-10-06 mid-turn steering 接线】
                        // 原来运行时按钮固定是「停止」—— 用户想补充一句
                        // （「顺便把 X 也改了」）只能先停再重说，打断当前工作。
                        // 现在：**有文字 = 发送**（入 steering 队列，下一轮注入，
                        // 不打断当前工具批次）；**无文字 = 停止**。
                        // 对齐 CLI/Web 的「执行中补充指令」语义。
                        SendButton(
                            // 【2026-10-06】原来只认文字 —— 只选附件不打字时
                            // 按钮置灰，用户点不动（「仅选择图片/文件时无法发送」）。
                            enabled = value.isNotBlank() || attachedPaths.isNotEmpty() || running,
                            // 有字或有附件 → 显示发送图标；都没有且在跑 → 停止图标
                            running = running && value.isBlank() && attachedPaths.isEmpty(),
                            onClick = {
                                if (running && value.isBlank() && attachedPaths.isEmpty()) onStop()
                                else onSend()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 模型选择器（对话页版）—— 比首页版窄，显示名会被截断 */
@Composable
private fun ModelChipInline(modelName: String, onClick: () -> Unit = {}) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .height(29.44.dp)
            .clip(RoundedCornerShape(5.52.dp))       // rounded-[6px]
            .clickable(onClick = onClick)
            .padding(horizontal = 9.2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.52.dp),
    ) {
        Text(
            text = modelName,
            style = CCMText.body14,
            color = Color(0xFF373734),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(120.dp),        // 实测模型名被截断（"nemotron-3-sup..."）
        )
        PainterIcon(R.drawable.ic_model_caret, size = 12.dp, tint = colors.textSecondary)
    }
}

/**
 * 发送 / 停止按钮 —— 橙色圆角方块。
 *
 * Web：`bg-claude-accent` 圆角方块 + 上箭头图标；
 * 跑动时变成停止图标。
 */
@Composable
private fun SendButton(enabled: Boolean, running: Boolean, onClick: () -> Unit) {
    val colors = CCMTheme.colors
    val bg = when {
        running -> colors.textMain                    // 跑动时用深色（停止）
        enabled -> colors.accent                      // 可发送时橙色
        else -> colors.border                         // 置灰
    }

    Box(
        modifier = Modifier
            // ══════════════════════════════════════════════════════════
            //  【2026-10-06 真根因】发送按钮被**挤压**了
            //
            // 截图实测：按钮渲染成 20.4dp 宽（不是设计的 34.96dp）。
            //
            // 原因：底部 Row 用 SpaceBetween，右边那组（模型chip 156dp +
            // 语音 20dp + 间距 14.72dp + 发送 34.96dp = 225.7dp）加上
            // 左边那组（+ 按钮 + token 圆环+文字 ≈ 147dp）= 372.7dp，
            // **超过可用宽度 370dp** → Row 压缩子元素 → 发送按钮先中招
            // （它没有 weight，是"可压缩"的）。
            //
            // 修法：用 `requiredSize` —— 它**无视父约束**，强制 34.96dp。
            // 这样挤压会转移到其他元素（模型名会先截断，那是设计好的）。
            //
            // 尺寸依据：Web `p-2 + ArrowUp size=22 + p-2` = 38px × 0.92 = 34.96dp
            // ══════════════════════════════════════════════════════════
            .requiredSize(34.96.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            // 停止：实心方块
            Box(
                modifier = Modifier
                    // Web 的停止图标：实心方块约 14px → 12.88dp
                    .size(12.88.dp)
                    .clip(RoundedCornerShape(2.3.dp))
                    .background(colors.bgMain),
            )
        } else {
            // 上箭头 —— 【2026-10-06 用户反馈修正】原来 14dp，
            // Web 是 `ArrowUp size=22 strokeWidth=2.5` → 22 × 0.92 = 20.24dp。
            // 14dp 配 34.96dp 按钮显得空旷，20.24 才是 Web 的比例。
            UpArrowGlyph(tint = Color.White, size = 20.24.dp, strokeWidth = 2.5f)
        }
    }
}

/** 画上箭头（lucide 的 ArrowUp） */
@Composable
private fun UpArrowGlyph(tint: Color, size: androidx.compose.ui.unit.Dp, strokeWidth: Float) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = strokeWidth / 24f * w
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w / 2, h * 0.82f),
            end = androidx.compose.ui.geometry.Offset(w / 2, h * 0.18f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // 箭头两翼
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.46f),
            end = androidx.compose.ui.geometry.Offset(w / 2, h * 0.18f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.46f),
            end = androidx.compose.ui.geometry.Offset(w / 2, h * 0.18f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}


/** token 数缩写：1234 → "1.2K"，1_000_000 → "1M"（徽章显示用）。 */
private fun fmtTokens(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 10_000 -> "%.0fK".format(n / 1_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1_000.0)
    else -> n.toString()
}
