package com.ccm.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * CCM 圆角 Token。
 *
 * 数据源：**MEASURED.md §3**（Playwright 实测，非推算）。
 *
 * ## 为什么数值这么"奇怪"
 * Web 端圆角有两层：
 * 1. Tailwind 基准值（`rounded-md` = 6px …）
 * 2. `@media (max-width:767px)` 用 `clamp()` 覆盖了部分大圆角
 * 3. 最后整体 `zoom: 0.92`
 *
 * 所以 `rounded-xl` 屏幕上是 **11.04dp** 而不是 12dp。这里全部按实测固化 ——
 * 基准机型锁定本机（REDMI Note 15 Pro，393×852 CSS 视口），不做 vw 动态计算。
 *
 * > 若未来要适配其他屏宽，把 [CCMShapes] 改成从 `LocalVw` 计算的 @Composable
 * > 工厂即可，调用点不用动。
 */
object CCMRadius {
    /** `rounded-sm` = 2px × 0.92 */
    val sm = 1.84.dp

    /** `rounded` = 4px × 0.92 */
    val base = 3.68.dp

    /** `rounded-md` = 6px × 0.92 */
    val md = 5.52.dp

    /** `rounded-lg` = 8px × 0.92（最高频） */
    val lg = 7.36.dp

    /** `rounded-xl` = 12px × 0.92 */
    val xl = 11.04.dp

    /** `rounded-2xl` → 移动端 clamp(10, 3vw, 16) = 11.79 × 0.92 */
    val xxl = 10.85.dp

    /** `rounded-3xl` → 移动端 clamp(12, 3.6vw, 20) = 14.148 × 0.92 */
    val xxxl = 13.02.dp

    /** `rounded-[24px]` → 移动端 clamp(12, 3.6vw, 20) = 14.148 × 0.92 */
    val r24 = 13.02.dp

    /** `rounded-[20px]` → 移动端 clamp(11, 3.2vw, 18) = 12.576 × 0.92 */
    val r20 = 11.57.dp

    /** `rounded-[16px]`（无移动端覆盖）= 16 × 0.92 */
    val r16 = 14.72.dp
}

/**
 * 映射到 Material3 [Shapes]，让 Material 组件（Card / Button / TextField）
 * 默认拿到接近 Web 的圆角。
 *
 * 对应关系参考 Material3 组件的实际使用位置：
 * - `extraSmall` → Chip、小标签
 * - `small` → 按钮、输入框
 * - `medium` → 卡片
 * - `large` / `extraLarge` → 大卡片、面板、模态
 */
val CCMShapes = Shapes(
    extraSmall = RoundedCornerShape(CCMRadius.base),
    small = RoundedCornerShape(CCMRadius.md),
    medium = RoundedCornerShape(CCMRadius.lg),
    large = RoundedCornerShape(CCMRadius.xl),
    extraLarge = RoundedCornerShape(CCMRadius.xxl),
)
