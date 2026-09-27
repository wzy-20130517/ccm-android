package com.ccm.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 计划任务页 —— 对齐 Web 的 `/scheduled` 路由（`ScheduledPage.tsx`，80 行）。
 *
 * ## 实测（与 ProjectsScreen 同构）
 * 该页与 ProjectsScreen 结构一致（标题 + 右上按钮 + 居中空态），
 * 但 `ScheduledPage.tsx` 只有 80 行 —— 它把大部分内容委托给了
 * `<CoworkPage onStartTask={...} />` 或显示空态。
 *
 * 源码要点（`ScheduledPage.tsx`）：
 * ```tsx
 * // 空态：「还没有计划任务」+ 说明 + 「新建任务」按钮
 * // 新建任务 → onNewTask() → navigate('/cowork')
 * ```
 *
 * 实测同 ProjectsScreen 的样式规范：
 * - h1 21.222 / 27.5886 / fw500 / Spectral
 * - 右上深色按钮 圆角 8 / pad 6×14
 * - 空态居中，插图 + 标题 + 说明 + 描边按钮
 *
 * @param tasks    计划任务列表
 * @param onNewTask 点「新建任务」（Web 会跳到 /cowork）
 */
@Composable
fun ScheduledScreen(
    modifier: Modifier = Modifier,
    tasks: List<ScheduledTaskUi> = emptyList(),
    onNewTask: () -> Unit = {},
) {
    val colors = CCMTheme.colors

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgMain)
            .padding(horizontal = 37.94.dp),
    ) {
        Spacer(Modifier.height(43.09.dp))

        // ── 标题行 ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "计划任务",
                style = CCMText.body20.copy(
                    fontSize = 21.222.sp,
                    lineHeight = 27.5886.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = colors.textMain,
            )

            // 深色按钮 —— 与 ProjectsScreen 的「New project」同规格
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.textMain)
                    .clickable(onClick = onNewTask)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlusGlyphScheduled(tint = colors.bgMain, size = 14.dp, strokeWidth = 2.5f)
                Text(
                    text = "新建任务",
                    style = CCMText.body14.copy(
                        fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium,
                    ),
                    color = colors.bgMain,
                )
            }
        }

        if (tasks.isEmpty()) {
            // ── 空态（居中）────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 128.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 时钟插图（用 Canvas 画一个简笔时钟）
                ClockGlyph(size = 56.dp, color = colors.textSecondary)

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "还没有计划任务",
                    style = CCMText.body16.copy(
                        fontSize = 17.sp, lineHeight = 25.5.sp, fontWeight = FontWeight.Medium,
                    ),
                    color = colors.textMain,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    text = "让 Claude 按你设定的时间自动执行任务。",
                    style = CCMText.body13.copy(fontSize = 12.7725.sp, lineHeight = 20.7553.sp),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(14.148.dp))

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onNewTask)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PlusGlyphScheduled(tint = colors.textMain, size = 14.5.dp, strokeWidth = 2f)
                    Text(
                        text = "新建任务",
                        style = CCMText.body14.copy(
                            fontSize = 14.5.sp, lineHeight = 21.75.sp, fontWeight = FontWeight.Medium,
                        ),
                        color = colors.textMain,
                    )
                }
            }
        } else {
            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(11.04.dp),
            ) {
                tasks.forEach { task ->
                    ScheduledTaskRow(task = task)
                }
            }
        }
    }
}

/** 计划任务行 */
@Composable
private fun ScheduledTaskRow(task: ScheduledTaskUi) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.04.dp))
            .background(colors.input)
            .padding(14.72.dp),
    ) {
        Text(
            text = task.title,
            style = CCMText.body14.copy(fontWeight = FontWeight.Medium),
            color = colors.textMain,
        )
        Spacer(Modifier.height(3.68.dp))
        Text(
            text = task.schedule,
            style = CCMText.body12,
            color = colors.textSecondary,
        )
    }
}

/** 简笔时钟图标 */
@Composable
private fun ClockGlyph(size: androidx.compose.ui.unit.Dp, color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.045f
        // 外圈
        drawCircle(
            color = color,
            radius = w / 2 - stroke / 2,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
        )
        // 时针（指向 10 点方向）
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w / 2, h / 2),
            end = androidx.compose.ui.geometry.Offset(w * 0.32f, h * 0.36f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // 分针（指向 12 点）
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(w / 2, h / 2),
            end = androidx.compose.ui.geometry.Offset(w / 2, h * 0.24f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** 画 Plus（与 ChatsScreen / ProjectsScreen 的同名函数重复，TODO 抽到 common） */
@Composable
private fun PlusGlyphScheduled(tint: Color, size: androidx.compose.ui.unit.Dp, strokeWidth: Float) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = strokeWidth / 24f * w
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w / 2, h * 0.2f),
            end = androidx.compose.ui.geometry.Offset(w / 2, h * 0.8f),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.2f, h / 2),
            end = androidx.compose.ui.geometry.Offset(w * 0.8f, h / 2),
            strokeWidth = stroke,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/** 计划任务（UI 层） */
data class ScheduledTaskUi(
    val id: String,
    val title: String,
    val schedule: String,
)
