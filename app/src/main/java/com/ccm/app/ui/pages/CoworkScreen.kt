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
    modelName: String = "Sonnet 4.6",
    onSend: (String) -> Unit = {},
) {
    val colors = CCMTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
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
                // 实测 36 / 41.4 / fw600 / Inter 族 / #313131
                style = CCMText.body32.copy(
                    fontSize = 36.sp,
                    lineHeight = 41.4.sp,
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
                .clickable { /* TODO(B5): 打开说明弹窗 */ }
                .padding(vertical = 0.dp),
        )

        Spacer(Modifier.height(20.41.dp))       // 副标题底 235.44 → 卡片顶 256.41

        // ── 输入卡片 ──────────────────────────────────────────────────
        CoworkInputCard(modelName = modelName, onSend = onSend)

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
private fun CoworkInputCard(modelName: String, onSend: (String) -> Unit) {
    val colors = CCMTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.576.dp))
            .background(colors.input)
            .border(1.dp, Color(0xFFC7C7C7), RoundedCornerShape(12.576.dp))
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    ) {
        // 输入区 —— ★ 2026-09-28 第22批：死 Text → BasicTextField
        //   （原来只有一个灰色占位文案，打不了字）。协作会话的发送链
        //   尚未接 core（Cowork 页整体还是静态壳），先让输入能打字。
        var coworkInput by remember { androidx.compose.runtime.mutableStateOf("") }
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
                onValueChange = { coworkInput = it },
                textStyle = CCMText.body14.copy(
                    color = if (CCMTheme.isDark) colors.textMain else Color(0xFF373734),
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.claudeOrange),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── 底部行：+ 按钮（左）/ 麦克风（右）────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PainterIcon(R.drawable.ic_input_plus, size = 20.dp, tint = colors.textMain)
            PainterIcon(R.drawable.ic_voice_mode, size = 20.dp, tint = colors.textMain)
        }

        Spacer(Modifier.height(8.dp))

        // ── 下拉行：在项目中工作 / 提问 / 模型 ──────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CoworkDropdown(label = "在项\n目中\n工作", value = "")
            CoworkDropdown(label = "提\n问", value = "")
            Spacer(Modifier.weight(1f))
            CoworkDropdown(label = "", value = modelName)
        }
    }
}

/**
 * 协作页的下拉选择器 —— 实测 h=43.19 / 圆角 6 / pad 4×8 / fs 13 / 色 #61615F。
 *
 * 结构：上方小 label（可多行）+ 下方值 + 右侧箭头。
 */
@Composable
private fun CoworkDropdown(label: String, value: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { /* TODO(B5): 打开下拉菜单 */ }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
    CoworkChecklistItem("安排周期性任务", "让 Claude 自动完成重复的工作。"),
)
