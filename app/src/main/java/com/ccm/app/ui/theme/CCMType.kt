package com.ccm.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * CCM 字号 Token。
 *
 * 数据源：**MEASURED.md §2**（Playwright 实测，非推算）。
 *
 * ## ★ 「用 dp 不用 sp」怎么落地的
 * Compose 的 [TextStyle.fontSize] **只接受 sp/em，没有 dp 类型** ——
 * `TextUnit` 在测量时会乘 `Density.fontScale`，所以直接写 `14.sp` 会被用户的
 * 系统「字体大小」设置缩放，破坏零变化。
 *
 * 解决办法不在本文件，而在 [CCMTheme]：那里用
 * ```kotlin
 * CompositionLocalProvider(LocalDensity provides Density(density = ..., fontScale = 1f))
 * ```
 * 把 `fontScale` **锁死为 1.0**。此后 `1.sp ≡ 1.dp`，本文件可以放心用 `.sp`。
 *
 * > ⚠️ 依赖关系：**本文件的值只有在 [CCMTheme] 包裹下才正确**。
 * > 单独把 [CCMText] 拿到别处用（不经过 CCMTheme）会重新受 fontScale 影响。
 *
 * ## 数值来源
 * 三层叠加后的最终屏幕值（见 MEASURED.md §1）：
 * ```
 * 屏幕值 = clamp(min, vw系数 × 3.93, max) × 0.92
 * ```
 * 全部已固化为常量，不做运行时 vw 计算 —— 基准机型锁定本机（393×852）。
 *
 * ## 字体族
 * Web 声明了 `'Figtree'` / `'Source Serif 4'` / `'Anthropic Sans'`，但**从未被加载**
 * （无 Google Fonts link、无 @import、dist 里只有 KaTeX 字体）→ 实际全部回退系统默认。
 * 所以这里**不打包字体**，用 [FontFamily.Default] / [FontFamily.Serif]，
 * 与 Web 的真实渲染行为一致。（推翻 DECISIONS.md 决策 2）
 */

/** 无衬线族 —— 对应 Web 的 `'Figtree', sans-serif`（实际回退系统 sans） */
val CcmSans = FontFamily.Default

/** 衬线族 —— 对应 Web 的 `'Source Serif 4', serif`（实际回退系统 serif） */
val CcmSerif = FontFamily.Serif

/** 等宽族 —— 对应 Web 的 `font-family: monospace` */
val CcmMono = FontFamily.Monospace

/** 用实测值构造 [TextStyle]，减少重复样板 */
private fun ts(
    size: Float,
    lineHeight: Float,
    family: FontFamily = CcmSans,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

/**
 * 按「Tailwind 类名 → 实测屏幕值」组织的字号表。
 *
 * 命名保持 Tailwind 语义（13px / 14px …），方便与 Web 源码对照；
 * **数值是实测屏幕值，不是类名里的数字**（如 `size13` 实际是 11.21）。
 */
object CCMFontSize {
    /** `text-[10px]` → 实测 9.20 / 13.80 */
    const val S10 = 9.20f
    /** `text-[11px]` → 实测 10.12 / 15.18 */
    const val S11 = 10.12f
    /** `text-[12px]` → 实测 10.49 / 15.73 */
    const val S12 = 10.49f
    /** `text-[13px]` → 实测 11.21 / 16.81（最高频，178 处） */
    const val S13 = 11.21f
    /** `text-[14px]` → 实测 11.39 / 17.08（148 处） */
    const val S14 = 11.39f
    /** `text-[15px]` → 实测 11.75 / 17.63 */
    const val S15 = 11.75f
    /** `text-[16px]` → 实测 12.29 / 18.44 */
    const val S16 = 12.29f
    /** `text-[17px]` → 实测 15.64 / 23.46（无移动端覆盖，故偏大） */
    const val S17 = 15.64f
    /** `text-[18px]` → 实测 12.65 / 17.72 */
    const val S18 = 12.65f
    /** `text-[20px]` → 实测 13.38 / 18.46 */
    const val S20 = 13.38f
    /** `text-[22px]` → 实测 20.24 / 30.36（无移动端覆盖） */
    const val S22 = 20.24f
    /** `text-[24px]` → 实测 15.91 / 21.32 */
    const val S24 = 15.91f
    /** `text-[28px]` → 实测 16.27 / 21.48 */
    const val S28 = 16.27f
    /** `text-[32px]` → 实测 19.52 / 25.38 */
    const val S32 = 19.52f
    /** `text-[40px]` → 实测 20.97 / 26.84 */
    const val S40 = 20.97f

    // ── 行高（与上面字号一一配对）──────────────────────────────────
    /** `text-[10px]` 行高 */
    const val L10 = 13.80f
    /** `text-[11px]` 行高 */
    const val L11 = 15.18f
    /** `text-[12px]` 行高 */
    const val L12 = 15.73f
    /** `text-[13px]` 行高 */
    const val L13 = 16.81f
    /** `text-[14px]` 行高 */
    const val L14 = 17.08f
    /** `text-[15px]` 行高 */
    const val L15 = 17.63f
    /** `text-[16px]` 行高 */
    const val L16 = 18.44f
    /** `text-[17px]` 行高 */
    const val L17 = 23.46f
    /** `text-[18px]` 行高 */
    const val L18 = 17.72f
    /** `text-[20px]` 行高 */
    const val L20 = 18.46f
    /** `text-[22px]` 行高 */
    const val L22 = 30.36f
    /** `text-[24px]` 行高 */
    const val L24 = 21.32f
    /** `text-[28px]` 行高 */
    const val L28 = 21.48f
    /** `text-[32px]` 行高 */
    const val L32 = 25.38f
    /** `text-[40px]` 行高 */
    const val L40 = 26.84f

    // ── Tailwind 语义类（有独立覆盖，与任意值类不同）────────────────
    /** `text-xs` → 11.04 / 14.72 */
    const val XS = 11.04f
    /** `text-xs` 行高 */
    const val XSL = 14.72f
    /** `text-sm` → 12.88 / 18.40 */
    const val SM = 12.88f
    /** `text-sm` 行高 */
    const val SML = 18.40f
    /** `text-base` → 12.29 / 22.08 */
    const val BASE = 12.29f
    /** `text-base` 行高 */
    const val BASEL = 22.08f
    /** `text-lg` → 14.10 / 19.74 */
    const val LG = 14.10f
    /** `text-lg` 行高 */
    const val LGL = 19.74f
    /** `text-xl` → 15.19 / 20.50 */
    const val XL = 15.19f
    /** `text-xl` 行高 */
    const val XLL = 20.50f
    /** `text-2xl` → 16.99 / 22.09 */
    const val XL2 = 16.99f
    /** `text-2xl` 行高 */
    const val XL2L = 22.09f
    /** `text-3xl` → 19.52 / 25.38 */
    const val XL3 = 19.52f
    /** `text-3xl` 行高 */
    const val XL3L = 25.38f

    // ── 特殊 ──────────────────────────────────────────────────────
    /**
     * 首页 `h1` 标题实测：`font-size: 19px` + 衬线族。
     * 屏幕值 = 19 × 0.92 = **17.48**，行高 25.08 × 0.92 = **23.07**。
     */
    const val TITLE_SERIF = 17.48f
    /** 首页 `h1` 行高 */
    const val TITLE_SERIF_L = 23.07f
}

/**
 * 显式命名的排版表 —— **推荐在 UI 里直接用这个**，比 Material 槽位更贴近 Web 源码。
 *
 * 命名规则：`body13` 表示"Tailwind 的 `text-[13px]`"，数字是源码里的数字，
 * 但 [TextStyle.fontSize] 是**实测屏幕值**（11.21），不是 13。
 */
object CCMText {
    /** `text-[10px]` */
    val body10 = ts(CCMFontSize.S10, CCMFontSize.L10)
    /** `text-[11px]` */
    val body11 = ts(CCMFontSize.S11, CCMFontSize.L11)
    /** `text-[12px]` */
    val body12 = ts(CCMFontSize.S12, CCMFontSize.L12)
    /** `text-[13px]` —— 最高频，正文/列表项主力 */
    val body13 = ts(CCMFontSize.S13, CCMFontSize.L13)
    /** `text-[14px]` —— 次高频 */
    val body14 = ts(CCMFontSize.S14, CCMFontSize.L14)
    /** `text-[15px]` */
    val body15 = ts(CCMFontSize.S15, CCMFontSize.L15)
    /** `text-[16px]` */
    val body16 = ts(CCMFontSize.S16, CCMFontSize.L16)
    /** `text-[18px]` */
    val body18 = ts(CCMFontSize.S18, CCMFontSize.L18)
    /** `text-[20px]` */
    val body20 = ts(CCMFontSize.S20, CCMFontSize.L20)
    /** `text-[24px]` */
    val body24 = ts(CCMFontSize.S24, CCMFontSize.L24)
    /** `text-[32px]` */
    val body32 = ts(CCMFontSize.S32, CCMFontSize.L32)

    // ── Tailwind 语义类 ────────────────────────────────────────────
    /** `text-xs` */
    val xs = ts(CCMFontSize.XS, CCMFontSize.XSL)
    /** `text-sm` */
    val sm = ts(CCMFontSize.SM, CCMFontSize.SML)
    /** `text-base` */
    val base = ts(CCMFontSize.BASE, CCMFontSize.BASEL)
    /** `text-lg` */
    val lg = ts(CCMFontSize.LG, CCMFontSize.LGL)
    /** `text-xl` */
    val xl = ts(CCMFontSize.XL, CCMFontSize.XLL)
    /** `text-2xl` */
    val xl2 = ts(CCMFontSize.XL2, CCMFontSize.XL2L)
    /** `text-3xl` */
    val xl3 = ts(CCMFontSize.XL3, CCMFontSize.XL3L)

    // ── 衬线（标题、模型名等）───────────────────────────────────────
    /**
     * 首页大标题（"有什么我可以帮你？"）。
     * 实测：衬线族 + `#373734`（颜色见 [CCMColors.textTitle]）。
     */
    val titleSerif = ts(CCMFontSize.TITLE_SERIF, CCMFontSize.TITLE_SERIF_L, family = CcmSerif)

    // ── 等宽（代码块、终端输出）────────────────────────────────────
    /**
     * 代码块正文。Web 的 `.markdown-body pre` 是 `font-size: 0.9em`（相对正文 13px），
     * 换算 ≈ 11.7px × 0.92 ≈ 10.77。这里取与正文同级的实测值，避免嵌套 em 计算。
     */
    val code = ts(CCMFontSize.S13, CCMFontSize.L13, family = CcmMono)

    /** 行内代码（`.inline-code`，同样是 0.9em 相对值） */
    val inlineCode = ts(CCMFontSize.S12, CCMFontSize.L12, family = CcmMono)

    // ── 加权变体 ───────────────────────────────────────────────────
    /** 中等字重 13px（列表项标题、按钮文字） */
    val body13Medium = ts(CCMFontSize.S13, CCMFontSize.L13, weight = FontWeight.Medium)
    /** 中等字重 14px */
    val body14Medium = ts(CCMFontSize.S14, CCMFontSize.L14, weight = FontWeight.Medium)
    /** 半粗 13px */
    val body13SemiBold = ts(CCMFontSize.S13, CCMFontSize.L13, weight = FontWeight.SemiBold)
    /** 半粗 14px（页面标题） */
    val body14SemiBold = ts(CCMFontSize.S14, CCMFontSize.L14, weight = FontWeight.SemiBold)
    /** 粗体 16px */
    val body16Bold = ts(CCMFontSize.S16, CCMFontSize.L16, weight = FontWeight.Bold)
}

/**
 * 映射到 Material3 [Typography]，让 Material 组件（Button / TextField / Card）
 * 的默认文字大小接近 Web。
 *
 * > 推荐优先用 [CCMText] 的显式命名，Material 槽位只是兜底
 * > （避免某个组件忘了指定 style 时蹦出 Material 默认的 16sp）。
 */
val CCMType = Typography(
    // 主力正文（Web 最高频：13px / 14px / 12px）
    bodyLarge = CCMText.body15,
    bodyMedium = CCMText.body14,
    bodySmall = CCMText.body13,

    // 标签、辅助文字
    labelLarge = CCMText.body14,
    labelMedium = CCMText.body12,
    labelSmall = CCMText.body11,

    // 标题
    titleLarge = CCMText.body20,
    titleMedium = CCMText.body16,
    titleSmall = CCMText.body15,

    // 大标题（页面级）
    headlineLarge = CCMText.body32,
    headlineMedium = CCMText.body24,
    headlineSmall = CCMText.body20,
)

/** 便捷别名：`TextStyle` 里没指定 family 时用的默认族（与 Web body 一致） */
val DefaultFontFamily: FontFamily = CcmSans

/** 便捷别名：字号单位类型（供调用方做显式转换时引用） */
typealias CcmTextUnit = TextUnit
