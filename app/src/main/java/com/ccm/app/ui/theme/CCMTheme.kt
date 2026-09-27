package com.ccm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CCM 主题 —— 像素级对齐 Web 版。
 *
 * ## 用法
 * ```kotlin
 * CcmApp()          // ui/shell/CcmApp.kt，内部已包好 CCMTheme
 * // 或单独用：
 * CCMTheme(darkTheme = true) { ChatScreen() }
 * ```
 *
 * ## 三个关键设计
 *
 * ### 1. fontScale 锁死为 1.0（★ 零变化的命门）
 * Compose 的 `sp` 会乘系统 `fontScale`（用户在设置里调「字体大小」就变），
 * 而 Web 版**不受影响**。这里把 [LocalDensity] 的 `fontScale` 强制为 1f，
 * 使 `1.sp ≡ 1.dp`，[CCMText] 里所有 `.sp` 数值才会等于实测的 dp 值。
 *
 * ⚠️ 副作用：系统字号设置对本 App 无效。这是**有意为之** —— 要的就是与 Web 一致。
 *
 * ### 2. 颜色走 [LocalCCMColors]，不硬套 Material ColorScheme
 * Web 配色不是 Material 体系。这里仍提供一份 [MaterialTheme]（让 Button/TextField
 * 等组件能正常工作、有涟漪和无障碍语义），但**业务颜色一律从 [CCMTheme.colors] 取**。
 *
 * ### 3. 提供 vw 换算（备用）
 * 当前所有尺寸已按本机固化（393×852），[LocalVw] 是为将来适配其他屏宽留的口子。
 * 需要动态计算时用 [clampVw]，不用改调用点。
 */

// ── CompositionLocal 定义 ──────────────────────────────────────────────

/**
 * 当前主题的颜色 Token。
 *
 * 用 [staticCompositionLocalOf]（而非 `compositionLocalOf`）：颜色在主题切换时整体替换，
 * 静态版本会让读取它的所有节点在值变化时整体重组 —— 对主题切换是正确且更省的行为。
 */
val LocalCCMColors: ProvidableCompositionLocal<CCMColors> =
    staticCompositionLocalOf { LightCCMColors }

/** 当前是否为暗色主题 */
val LocalCCMDark: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }

/**
 * 视口宽度的 1%（vw）。
 * 本机为 393 / 100 = **3.93**。用于 [clampVw] 做动态尺寸计算（当前未启用）。
 */
val LocalVw: ProvidableCompositionLocal<Float> =
    staticCompositionLocalOf { 3.93f }

/**
 * 全局缩放比例（对应 Web 的 `--mobile-zoom`，实测 **0.92**）。
 *
 * Web 端由 `#root { zoom: 0.92 }` 实现；Compose 侧因为所有尺寸已预先乘过 0.92
 * 固化进常量，**这个值仅用于需要反向补偿的地方**（如顶栏，见下）。
 */
val LocalMobileZoom: ProvidableCompositionLocal<Float> =
    staticCompositionLocalOf { 0.92f }

// ── 顶栏豁免常量（MEASURED.md §5）────────────────────────────────────

/**
 * 顶栏高度 —— **44dp，不乘 0.92**。
 *
 * Web 的 `[data-chrome="titlebar"]` 有反向 zoom `1 / 0.92 = 1.08696`，
 * 抵消后屏幕真实高度恰好 44 CSS px。Compose 里直接写死 44dp。
 */
val TitleBarHeight: Dp = 44.dp

/** 顶栏底部分割线宽度 —— `1px solid #E8E7E3`（颜色用 [CCMColors.border]） */
val TitleBarBorderWidth: Dp = 1.dp

// ── 主题入口 ──────────────────────────────────────────────────────────

/**
 * CCM 主题包装。
 *
 * @param darkTheme 是否暗色。默认跟随系统；Web 版是手动切换（存 localStorage），
 *                  所以实际使用时建议显式传入，由 App 层的状态控制。
 * @param content   内容
 */
@Composable
fun CCMTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkCCMColors else LightCCMColors

    // ★ 关键：fontScale 锁 1.0，让 sp ≡ dp
    val systemDensity = LocalDensity.current
    val lockedDensity = Density(
        density = systemDensity.density,
        fontScale = 1f,
    )

    CompositionLocalProvider(
        LocalCCMColors provides colors,
        LocalCCMDark provides darkTheme,
        LocalMobileZoom provides 0.92f,
        LocalVw provides (3.93f),   // 本机固化；适配其他屏宽时改为动态计算
        LocalDensity provides lockedDensity,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterialColorScheme(darkTheme),
            typography = CCMType,
            shapes = CCMShapes,
            content = content,
        )
    }
}

// ── Material3 桥接 ────────────────────────────────────────────────────

/**
 * 把 [CCMColors] 映射到 Material3 的 ColorScheme。
 *
 * **这不是为了"让 Material 好看"，而是让 Material 组件别蹦出紫色默认值。**
 * 业务代码请用 [CCMTheme.colors]，不要用 `MaterialTheme.colorScheme.primary`。
 */
private fun CCMColors.toMaterialColorScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = accent,
        onPrimary = Color.White,
        background = bgMain,
        onBackground = textMain,
        surface = bgMain,
        onSurface = textMain,
        surfaceVariant = input,
        onSurfaceVariant = textSecondary,
        outline = border,
        error = error,
        onError = Color.White,
        scrim = scrim60,
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = Color.White,
        background = bgMain,
        onBackground = textMain,
        surface = bgMain,
        onSurface = textMain,
        surfaceVariant = input,
        onSurfaceVariant = textSecondary,
        outline = border,
        error = error,
        onError = Color.White,
        scrim = scrim60,
    )
}

// ── 访问器 ────────────────────────────────────────────────────────────

/**
 * 主题访问器 —— `CCMTheme.colors` / `CCMTheme.isDark`。
 *
 * 用法：
 * ```kotlin
 * Text("你好", color = CCMTheme.colors.textMain, style = CCMText.body13)
 * ```
 */
object CCMTheme {
    /** 当前颜色 Token */
    val colors: CCMColors
        @Composable @ReadOnlyComposable get() = LocalCCMColors.current

    /** 当前是否暗色 */
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalCCMDark.current

    /** 全局缩放（0.92） */
    val mobileZoom: Float
        @Composable @ReadOnlyComposable get() = LocalMobileZoom.current

    /** 视口 1vw（本机 3.93） */
    val vw: Float
        @Composable @ReadOnlyComposable get() = LocalVw.current
}

// ── vw 工具（备用）─────────────────────────────────────────────────────

/**
 * `clamp(min, Nvw, max)` 的 Compose 等价物。
 *
 * 当前**未启用** —— 所有尺寸已按本机固化进 [CCMText] / [CCMRadius]。
 * 将来要适配其他屏宽时，把固化值换成这里的动态计算即可，调用点不用改。
 *
 * ```kotlin
 * // Web: font-size: clamp(11.5px, 3.1vw, 13px)
 * val size = clampVw(11.5.dp, 3.1f, 13.dp)
 * ```
 */
@Composable
@ReadOnlyComposable
fun clampVw(min: Dp, vwFactor: Float, max: Dp): Dp {
    val vw = LocalVw.current
    return (vw * vwFactor).dp.coerceIn(min, max)
}

/**
 * 把一个「已按 0.92 固化」的 dp 还原成未缩放值。
 *
 * 用于需要反向补偿的场景（Web 用 `zoom: calc(1 / var(--mobile-zoom))`）。
 */
@Composable
@ReadOnlyComposable
fun unzoom(value: Dp): Dp = value / LocalMobileZoom.current

/**
 * 便捷：把未缩放的 Web 原始 px 值转成最终 dp（自动乘 zoom）。
 *
 * ```kotlin
 * val w = zoomed(288f)   // Web 的 288px 侧栏 → 本机屏幕 264.96dp
 * ```
 */
@Composable
@ReadOnlyComposable
fun zoomed(webPx: Float): Dp = (webPx * LocalMobileZoom.current).dp

/** 给 [Box] 用的调试包装（可选）：填满并套上主题 */
@Composable
fun CCMThemePreviewBox(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    CCMTheme(darkTheme = darkTheme) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}
