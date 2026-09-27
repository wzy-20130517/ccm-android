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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
    modelName: String = "Sonnet 4.6",
    tokenCount: Int = 0,
) {
    val colors = CCMTheme.colors

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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.6.dp)
                        .padding(start = 14.72.dp, end = 14.72.dp, top = 14.72.dp),
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "今天需要什么帮助？",
                            // 实测 16px（浏览器默认，防移动端缩放）
                            style = CCMText.body16.copy(fontSize = 16.sp, lineHeight = 24.sp),
                            color = colors.textSecondary,
                        )
                    } else {
                        Text(
                            text = value,
                            style = CCMText.body16.copy(fontSize = 16.sp, lineHeight = 24.sp),
                            color = Color(0xFF373734),
                        )
                    }
                    // TODO(阶段4·B5-c): 换成真实 TextField（多行自适应 + 回车发送）
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
                    // 左：+ 按钮 + token 计数
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                    ) {
                        PainterIcon(
                            R.drawable.ic_input_plus,
                            size = 20.dp,
                            tint = colors.textMain,
                        )
                        if (tokenCount > 0) {
                            Text(
                                text = "$tokenCount tokens",
                                style = CCMText.body12,
                                color = colors.textSecondary,
                            )
                        }
                    }

                    // 右：模型 + 麦克风 + 发送/停止
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.36.dp),
                    ) {
                        ModelChipInline(modelName = modelName)
                        PainterIcon(
                            R.drawable.ic_voice_mode,
                            size = 20.dp,
                            tint = colors.textMain,
                        )
                        SendButton(
                            enabled = value.isNotBlank() || running,
                            running = running,
                            onClick = { if (running) onStop() else onSend() },
                        )
                    }
                }
            }
        }
    }
}

/** 模型选择器（对话页版）—— 比首页版窄，显示名会被截断 */
@Composable
private fun ModelChipInline(modelName: String) {
    val colors = CCMTheme.colors
    Row(
        modifier = Modifier
            .height(29.44.dp)
            .clip(RoundedCornerShape(5.52.dp))       // rounded-[6px]
            .clickable { /* TODO(B5-c): 打开模型选择器 */ }
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
            .size(30.36.dp)                           // 实测发送按钮尺寸
            .clip(RoundedCornerShape(7.36.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            // 停止：实心方块
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(1.84.dp))
                    .background(colors.bgMain),
            )
        } else {
            // 上箭头
            UpArrowGlyph(tint = Color.White, size = 14.dp, strokeWidth = 2.2f)
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
