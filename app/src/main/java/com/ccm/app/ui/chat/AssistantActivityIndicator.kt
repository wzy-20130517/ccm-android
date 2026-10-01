package com.ccm.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import androidx.compose.ui.graphics.asComposePath

/**
 * 助手活动指示器（2026-10-01 移植 Web `AssistantActivityIndicator.tsx`）。
 *
 * ## 为什么要有它
 * APK 原来「发送后到首字之间」**什么都不显示**（MessageList 只在
 * streaming.isNotBlank() 时才渲染），用户以为卡死。Web 的对应实现是
 * Claude 官方星芒 sprite：15 帧 × 100px、2 秒循环的 SVG path 动画。
 *
 * ## 实现
 * - path 数据从 Web 的 `claude-thinking-sprite.svg` 提取（22K 字符，
 *   15 帧纵向排列，每帧 100×100），放 `res/raw/claude_thinking_sprite.txt`
 * - [PathParser] 解析一次（remember 缓存），Canvas 里逐帧 translate 绘制
 * - 用 `clipRect` 裁出 100×100 视窗，配合 translate 实现帧切换
 * - 颜色/节奏与 Web 完全一致（`rgb(217,119,87)` / 2s 线性）
 *
 * ## 与 Web 的差异
 * Web 还有 dissolve（溶解）转场与 typing lottie；这里先做**主循环**——
 * 覆盖「等待/思考中」的主场景，转场细节后续按需补。
 */
@Composable
fun AssistantActivityIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = Color(0xFFD97757),   // rgb(217,119,87) —— Web 同款
) {
    val context = LocalContext.current
    // path 解析一次（22K 字符，每帧重解析会卡）
    val framePath: Path? = remember {
        try {
            val raw = context.resources.openRawResource(
                context.resources.getIdentifier(
                    "claude_thinking_sprite", "raw", context.packageName,
                ),
            ).bufferedReader().use { it.readText() }
            val androidPath = PathParser.createPathFromPathData(raw)
            if (androidPath == null) null
            else Path().apply { addPath(androidPath.asComposePath()) }
        } catch (_: Throwable) {
            null
        }
    }

    if (framePath == null) return   // 资产缺失时静默（不崩、不占位）

    val transition = rememberInfiniteTransition(label = "claude-sprite")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "frame",
    )

    Canvas(modifier = modifier.size(size)) {
        // 15 帧（0..14），与 SVG animateTransform 的 15 个值一致
        val frame = (progress * 15f).toInt().coerceIn(0, 14)
        val scaleF = this.size.width / 100f   // viewBox 100×100 → 目标尺寸
        clipRect(
            left = 0f, top = 0f,
            right = this.size.width, bottom = this.size.height,
        ) {
            // 先缩放到目标尺寸，再上移让当前帧对齐视窗
            scale(scaleF, scaleF, pivot = Offset.Zero) {
                translate(top = -frame * 100f) {
                    drawPath(framePath, color)
                }
            }
        }
    }
}
