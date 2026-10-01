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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import kotlinx.coroutines.delay

/**
 * 助手活动阶段 —— 对应 Web `assistantActivityState.ts` 的 `AssistantActivityPhase`。
 *
 * | 阶段 | Web 播放 | 本组件播放 |
 * |---|---|---|
 * | [WAITING] | `thinking.lottie`（8 帧 webp，7fps，1.14s 循环） | sprite 主循环 |
 * | [STREAMING] | `typing.lottie`（12 帧 webp，14fps，0.86s 循环） | dissolve 转场（若长思考），否则不显示 |
 * | [DONE] | `typing.lottie` 定格第 3 帧 | 不显示 |
 */
enum class ActivityPhase { WAITING, STREAMING, DONE }

/** sprite 总帧数 —— 与 SVG `animateTransform` 的 15 个 values 一致。 */
private const val SPRITE_FRAME_COUNT = 15

/** 单帧在 viewBox 中的边长（`viewBox="0 0 100 100"`，15 帧纵向排列）。 */
private const val SPRITE_FRAME_SIZE = 100f

/**
 * dissolve 转场帧间隔 —— 对应 Web `DISSOLVE_INTERVAL_MS = 52`。
 *
 * ⚠️ 这个值**比主循环快得多**（主循环 2000/15 ≈ 133ms/帧）。
 * 溶解要的是「唰」地一下化开，用主循环的速度会变成慢动作，失去转场语义。
 */
private const val DISSOLVE_INTERVAL_MS = 52L

/** 主循环周期 —— 对应 SVG `dur="2s"`。 */
private const val SPRITE_CYCLE_MS = 2000

/**
 * 助手活动指示器（移植自 Web `AssistantActivityIndicator.tsx`）。
 *
 * ## 为什么要有它
 * APK 原来「发送后到首字之间」**什么都不显示**（MessageList 只在
 * `streaming.isNotBlank()` 时才渲染），用户以为卡死。
 *
 * ## 资产与降级（重要，别当成等价移植）
 * Web 有**三套**图形，本组件只有一套 sprite：
 * 1. `thinking.lottie` —— 8 张 1000×1000 webp 序列（等待中）
 * 2. `typing.lottie` —— 12 张 webp 序列（流式中 / 完成后定格）
 * 3. `claude-thinking-sprite.svg` —— 单条 path 的 15 帧矢量序列（**仅用于 dissolve 转场**）
 *
 * 1、2 是位图序列，要播放得引入 lottie 依赖（`com.airbnb.android:lottie`）
 * 并把 20 张 webp 搬进 `res/`。本项目零 lottie 依赖，改动范围又限定在本文件，
 * 故**用 sprite 承担主循环**（矢量、单文件 22KB、任意尺寸不糊）。
 *
 * 代价如实记下：sprite 是 Claude 星芒的形变序列，`thinking.lottie` 是另一套形态。
 * 两者视觉不同，只是「都在动」。这是当前资产条件下的最优近似。
 *
 * ## dissolve 转场（Web 有、原实现完全缺失）
 * Web 在「思考很久 → 开始出正文」时会插一段溶解：sprite 的 15 帧以
 * **52ms/帧**（共 780ms）快速播完，再切到打字动画。这是「思考结束、正文接管」
 * 的视觉断句 —— 少了它，等待动画会突然消失。
 *
 * ⚠️ 触发条件是 `didLongThinking`（思考 >900ms，对应 Web 的
 * `LONG_THINKING_THRESHOLD_MS = 900`）。短暂等待不播 —— 否则每次出字都闪一下。
 * ⚠️ 需要调用方传 [phase] 才会触发；只传默认值时恒为 [WAITING]，不播转场。
 *
 * ## 颜色
 * `rgb(217,119,87)` —— Web 的 sprite `fill` 是**硬编码**这个值，
 * 不随主题变（不是 `currentColor`）。本组件保持一致。
 *
 * @param phase 当前阶段，默认 [WAITING]（保持旧调用点行为不变）
 * @param didLongThinking 本次思考是否超过 900ms —— 决定是否播 dissolve
 */
@Composable
fun AssistantActivityIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = Color(0xFFD97757),   // rgb(217,119,87) —— Web 同款
    phase: ActivityPhase = ActivityPhase.WAITING,
    didLongThinking: Boolean = false,
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

    // ── dissolve 状态机 ────────────────────────────────────────────────
    // 对应 Web 的 `previousPhaseRef` + `shouldPlayDissolve`：
    //   next === 'streaming' && didLongThinking && previous !== 'streaming'
    var previousPhase by remember { mutableStateOf<ActivityPhase?>(null) }
    var dissolving by remember { mutableStateOf(false) }
    var dissolveFrame by remember { mutableStateOf(0) }

    LaunchedEffect(phase, didLongThinking) {
        val previous = previousPhase
        previousPhase = phase

        val shouldDissolve = phase == ActivityPhase.STREAMING &&
            didLongThinking &&
            previous != ActivityPhase.STREAMING

        if (!shouldDissolve) {
            dissolving = false
            return@LaunchedEffect
        }

        dissolving = true
        // 从第 0 帧起逐帧推进，播满 15 帧后交还静止态。
        // delay 放在帧后（先显示、再等待），与 Web `setInterval` 的节奏一致。
        for (frame in 0 until SPRITE_FRAME_COUNT) {
            dissolveFrame = frame
            delay(DISSOLVE_INTERVAL_MS)
        }
        dissolving = false
    }

    // ── 主循环（2s 走完 15 帧，线性）────────────────────────────────────
    val transition = rememberInfiniteTransition(label = "claude-sprite")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = SPRITE_CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "frame",
    )

    // 流式/完成阶段不播 sprite：
    //   Web 在这两个阶段用 typing lottie，形态与 sprite 不同（见类注释）。
    //   与其拿形态不对的动画硬顶，不如不显示 —— 此时正文已在流式输出，
    //   指示器本就该退场（DONE 后 Web 也只是让打字动画定格，不抢注意力）。
    val active = phase == ActivityPhase.WAITING || dissolving
    if (!active) return

    Canvas(modifier = modifier.size(size)) {
        val frame = if (dissolving) {
            dissolveFrame.coerceIn(0, SPRITE_FRAME_COUNT - 1)
        } else {
            (progress * SPRITE_FRAME_COUNT).toInt().coerceIn(0, SPRITE_FRAME_COUNT - 1)
        }
        val scaleF = this.size.width / SPRITE_FRAME_SIZE   // viewBox 100×100 → 目标尺寸
        clipRect(
            left = 0f, top = 0f,
            right = this.size.width, bottom = this.size.height,
        ) {
            // 先缩放到目标尺寸，再上移让当前帧对齐视窗。
            // ⚠️ 顺序不能反：translate 在外层的话偏移量会被当成目标像素，
            //    15 帧一帧也切不动。
            scale(scaleF, scaleF, pivot = Offset.Zero) {
                translate(top = -frame * SPRITE_FRAME_SIZE) {
                    drawPath(framePath, color)
                }
            }
        }
    }
}
