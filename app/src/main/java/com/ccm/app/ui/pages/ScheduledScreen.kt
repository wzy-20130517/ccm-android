package com.ccm.app.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
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
    /**
     * 点「新建任务」。
     *
     * 【2026-10-06 问题32 修复】原来是 `navigate(CcmRoute.COWORK)` ——
     * 用户报「计划任务中新建任务直接跳转协作模式页」，莫名其妙。
     * 现在：弹创建对话框（cron 表达式 + prompt），创建后刷新列表。
     * 参数保留（调用方可覆盖），默认行为改为弹对话框。
     */
    onNewTask: () -> Unit = {},
    /** 创建任务（cron + prompt + durable）→ 成功返回 true。 */
    onCreateTask: (cron: String, prompt: String, durable: Boolean) -> Boolean = { _, _, _ -> false },
) {
    val colors = CCMTheme.colors
    // 【2026-10-06 问题32】创建对话框开关
    var showCreate by remember { mutableStateOf(false) }

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
                    .clickable { showCreate = true }
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
                        .clickable { showCreate = true }
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

    // ── 创建任务对话框（问题32）──────────────────────────────────
    if (showCreate) {
        CreateCronDialog(
            onDismiss = { showCreate = false },
            onCreate = { cron, prompt, durable ->
                val ok = onCreateTask(cron, prompt, durable)
                if (ok) showCreate = false
                ok
            },
        )
    }
}

/**
 * 创建计划任务对话框 —— 对齐 CLI 的 `CronCreate` 工具语义。
 *
 * 【2026-10-06 问题32】原来「新建任务」直接 `navigate(CcmRoute.COWORK)` ——
 * 用户报「计划任务中新建任务直接跳转协作模式页」，莫名其妙。
 * 现在弹这个对话框，创建后刷新列表。
 */
@Composable
private fun CreateCronDialog(
    onDismiss: () -> Unit,
    onCreate: (cron: String, prompt: String, durable: Boolean) -> Boolean,
) {
    val colors = CCMTheme.colors
    var cron by remember { mutableStateOf("0 9 * * *") }
    var prompt by remember { mutableStateOf("") }
    var durable by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(14.72.dp))
                .clip(RoundedCornerShape(14.72.dp))
                .background(colors.bgMain)
                .border(1.dp, colors.border, RoundedCornerShape(14.72.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(9.2.dp),
        ) {
            Text(
                "新建计划任务",
                style = CCMText.body16.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )

            Text("执行时间（cron 5 字段：分 时 日 月 周）",
                style = CCMText.body12.copy(fontSize = 10.48.sp),
                color = colors.textSecondary)
            androidx.compose.foundation.text.BasicTextField(
                value = cron,
                onValueChange = { cron = it; error = "" },
                textStyle = CCMText.body14.copy(color = colors.textMain),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.claudeOrange),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(7.36.dp))
                    .background(colors.input)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    "0 9 * * *" to "每天9点",
                    "0 * * * *" to "每小时",
                    "*/30 * * * *" to "每30分",
                    "0 9 * * 1" to "每周一",
                ).forEach { (expr, label) ->
                    Text(
                        label,
                        style = CCMText.body12.copy(fontSize = 10.48.sp),
                        color = if (cron == expr) colors.accent else colors.textSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .clickable { cron = expr; error = "" }
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
            }

            Text("执行内容（到点自动跑的 prompt）",
                style = CCMText.body12.copy(fontSize = 10.48.sp),
                color = colors.textSecondary)
            androidx.compose.foundation.text.BasicTextField(
                value = prompt,
                onValueChange = { prompt = it; error = "" },
                textStyle = CCMText.body13.copy(color = colors.textMain),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.claudeOrange),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(7.36.dp))
                    .background(colors.input)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.clickable { durable = !durable },
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (durable) colors.accent else Color.Transparent)
                        .border(1.dp, if (durable) colors.accent else colors.border, RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (durable) Text("✓", style = CCMText.body12, color = Color.White)
                }
                Text(
                    "长期任务（写盘，重启后仍生效）",
                    style = CCMText.body12.copy(fontSize = 11.sp),
                    color = colors.textMain,
                )
            }

            if (error.isNotBlank()) {
                Text(error, style = CCMText.body12, color = Color(0xFFD9534F))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.2.dp, Alignment.End),
            ) {
                Text(
                    "取消",
                    style = CCMText.body13,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
                Text(
                    "创建",
                    style = CCMText.body13.copy(fontWeight = FontWeight.Medium),
                    color = if (cron.isNotBlank() && prompt.isNotBlank()) Color(0xFFD97757)
                            else colors.textSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(enabled = cron.isNotBlank() && prompt.isNotBlank()) {
                            val ok = onCreate(cron.trim(), prompt.trim(), durable)
                            if (!ok) error = "创建失败：cron 表达式格式不对（需要 5 个字段）"
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
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
