package com.ccm.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 待办清单面板 —— 对齐 `web/src/components/TodoPanel.tsx`（98 行）。
 *
 * ## 实测尺寸（源码值 × 0.92）
 * | 属性 | Web | 屏幕值 |
 * |---|---|---|
 * | 位置 | `fixed bottom-[150px] right-6 z-[95]` | 距底 138 / 距右 22.08 |
 * | 宽度 | `w-[300px]` | **276dp** |
 * | 圆角 | `rounded-2xl`（移动端 clamp → 11.79） | **10.85dp** |
 * | 背景 | `bg-claude-bg/95` + `backdrop-blur` | bgMain @ 95% |
 * | 阴影 | `shadow-xl` | |
 * | 头部内边距 | `px-4 py-3` | 14.72 / 11.04 |
 * | 进度条 | `h-[3px]` 圆角 full | **2.76dp** |
 * | 列表项 | `px-2 py-1.5 gap-2 rounded-lg` | 7.36 / 5.52 / 7.36 / 7.36 |
 * | 正文 | `text-[12.5px] leading-[18px]` | 11.5 / 16.56 |
 *
 * ## 三种状态图标（Web 用 lucide）
 * | 状态 | Web | Compose |
 * |---|---|---|
 * | `completed` | `<Check size={13}>` 橙色 | [ic_check] |
 * | `in_progress` | `<Loader2 size={13}>` 橙色旋转 | 自绘圆弧 + 旋转动画 |
 * | 其他 | `h-[7px] w-[7px] rounded-full border` | 空心小圆 |
 *
 * > `backdrop-blur` 按 B0 决策**降级为半透明纯色**（`bgMain @ 0.95`）。
 *
 * @param todos   待办列表
 * @param running 是否正在运行（为 true 且有待办进行中时，底部显示「正在进行：…」）
 */
@Composable
fun TodoPanel(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier,
    running: Boolean = false,
) {
    val colors = CCMTheme.colors
    var collapsed by remember { mutableStateOf(false) }

    if (todos.isEmpty()) return

    val total = todos.size
    val done = todos.count { it.status == TodoStatus.COMPLETED }
    val active = todos.firstOrNull { it.status == TodoStatus.IN_PROGRESS }
    val progress = if (total > 0) done.toFloat() / total else 0f

    Column(
        modifier = modifier
            .width(276.dp)                                  // w-[300px] × 0.92
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(10.85.dp))
            .clip(RoundedCornerShape(10.85.dp))             // rounded-2xl 移动端
            .background(colors.bgMain.copy(alpha = 0.95f))  // bg-claude-bg/95
            .border(1.dp, colors.border, RoundedCornerShape(10.85.dp)),
    ) {
        // ── 头部（可点折叠）──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { collapsed = !collapsed }
                .padding(horizontal = 14.72.dp, vertical = 11.04.dp),   // px-4 py-3
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.36.dp),      // gap-2
        ) {
            // ListChecks 图标（橙色）
            PainterIcon(R.drawable.ic_list_checks, size = 13.8.dp, tint = colors.accent)  // size={15} × 0.92

            Text(
                text = "任务清单",
                style = CCMText.body13Medium,
                color = colors.textMain,
                modifier = Modifier.weight(1f),
            )

            // 进度计数 `3/5`（tabular-nums 对齐数字）
            Text(
                text = "$done/$total",
                style = CCMText.body11,
                color = colors.textSecondary,
            )

            // ChevronDown —— 折叠时旋转 -90°
            PainterIcon(
                R.drawable.ic_chevron_down,
                size = 12.88.dp,                            // size={14} × 0.92
                tint = colors.textSecondary,
                modifier = Modifier.rotate(if (collapsed) -90f else 0f),
            )
        }

        // ── 进度条（px-4，h-[3px]）──────────────────────────────────
        Box(modifier = Modifier.padding(horizontal = 14.72.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.76.dp)                        // h-[3px] × 0.92
                    .clip(RoundedCornerShape(1.38.dp))
                    .background(colors.border),
            ) {
                // 进度填充（宽度动画 300ms ease-out）
                val animated by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = tween(durationMillis = 300),
                    label = "todoProgress",
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animated)
                        .height(2.76.dp)
                        .clip(RoundedCornerShape(1.38.dp))
                        .background(colors.accent),
                )
            }
        }

        // ── 列表（可折叠，max-h-[260px]）─────────────────────────────
        if (!collapsed) {
            Column(
                modifier = Modifier
                    .heightIn(max = 239.2.dp)               // max-h-[260px] × 0.92
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 7.36.dp, vertical = 7.36.dp),   // px-2 py-2
            ) {
                todos.forEach { item ->
                    TodoRow(item)
                }
            }
        }

        // ── 底部状态（border-t px-4 py-2）────────────────────────────
        if (running && active != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, colors.border, RoundedCornerShape(0.dp))
                    .padding(horizontal = 14.72.dp, vertical = 7.36.dp),   // px-4 py-2
            ) {
                Text(
                    text = "正在进行：${active.content}",
                    style = CCMText.body11,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** 单行待办 */
@Composable
private fun TodoRow(item: TodoItem) {
    val colors = CCMTheme.colors
    val isDone = item.status == TodoStatus.COMPLETED
    val isActive = item.status == TodoStatus.IN_PROGRESS

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.36.dp))              // rounded-lg
            .background(if (isActive) colors.hover else Color.Transparent)
            .padding(horizontal = 7.36.dp, vertical = 5.52.dp),   // px-2 py-1.5
        horizontalArrangement = Arrangement.spacedBy(7.36.dp),    // gap-2
    ) {
        // 状态图标（h-4 w-4 容器，mt-[2px]）
        Box(
            modifier = Modifier
                .padding(top = 1.84.dp)
                .size(14.72.dp),                            // h-4 w-4 × 0.92
            contentAlignment = Alignment.Center,
        ) {
            when {
                isDone -> PainterIcon(R.drawable.ic_check, size = 11.96.dp, tint = colors.accent)
                isActive -> SpinningLoader(size = 11.96.dp, color = colors.accent)
                else -> Box(
                    modifier = Modifier
                        .size(6.44.dp)                      // h-[7px] × 0.92
                        .clip(RoundedCornerShape(3.22.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(3.22.dp)),
                )
            }
        }

        Text(
            text = item.content,
            // text-[12.5px] leading-[18px] → 11.5 / 16.56
            style = CCMText.body12.copy(
                fontSize = 11.5.sp,
                lineHeight = 16.56.sp,
                textDecoration = if (isDone) TextDecoration.LineThrough else null,
            ),
            color = when {
                isDone -> colors.textSecondary
                isActive -> colors.textMain
                else -> colors.textSecondary
            },
        )
    }
}

/**
 * 旋转加载圈 —— 对应 lucide 的 `Loader2` + `animate-spin`。
 *
 * lucide 的 Loader2 是「两条不相连的弧」，这里用 [androidx.compose.foundation.Canvas]
 * 画一段 270° 圆弧近似（13px 尺寸下视觉差异极小）。
 */
@Composable
private fun SpinningLoader(size: androidx.compose.ui.unit.Dp, color: Color) {
    val transition = rememberInfiniteTransition(label = "spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinAngle",
    )

    androidx.compose.foundation.Canvas(modifier = Modifier.size(size).rotate(angle)) {
        val stroke = this.size.minDimension * 0.16f
        drawArc(
            color = color,
            startAngle = 0f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
            size = androidx.compose.ui.geometry.Size(
                this.size.width - stroke,
                this.size.height - stroke,
            ),
        )
    }
}

/** 待办状态 —— 对齐 Web 的 `'pending' | 'in_progress' | 'completed' | string` */
enum class TodoStatus(val wire: String) {
    PENDING("pending"),
    IN_PROGRESS("in_progress"),
    COMPLETED("completed"),
    UNKNOWN("");

    companion object {
        /** 从字符串解析（未知值归 [UNKNOWN]，与 Web 的 `| string` 行为一致） */
        fun from(s: String): TodoStatus =
            entries.firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

/**
 * 待办项 —— 对应 Web 的 `TodoItem` 接口。
 */
data class TodoItem(
    val content: String,
    val status: TodoStatus = TodoStatus.PENDING,
) {
    constructor(content: String, status: String) : this(content, TodoStatus.from(status))
}
