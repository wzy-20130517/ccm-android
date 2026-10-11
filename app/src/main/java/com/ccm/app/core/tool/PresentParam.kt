package com.ccm.app.core.tool

import com.ccm.app.core.tool.ToolSchema.double
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Present 展示块的可调参数（一个数值滑块）。
 *
 * 对齐 Node 版 `core/tools/present-tool.mjs` 的 params 清洗结果 ——
 * 模型传进来的原始 JSON 先经 [parse] 过滤，脏数据不往下传。
 *
 * ## 三种读取方式（与 Web 端 PresentBlock.tsx 完全一致）
 * 1. **CSS 变量**：参数名 `speed` → content 里写 `var(--speed)`，动画连续不断
 * 2. **JS 回调**：读 `window.PARAMS.speed`，并设 `window.onParamChange = (name, value) => {}`
 * 3. **`{{speed}}` 占位符**：整体重渲染（动画从头播，只适合静态图）
 *
 * @property name 参数名，对应 content 里的 `var(--name)` / `window.PARAMS.name` / `{{name}}`
 * @property label 显示名（默认用 name）
 * @property min 滑块下界
 * @property max 滑块上界（**保证 > min**，否则 Android Slider 直接抛异常）
 * @property step 步长（**保证 > 0**）
 * @property value 默认值
 */
data class PresentParam(
    val name: String,
    val label: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val value: Double,
) {
    companion object {
        /**
         * 最多 8 个参数（对齐 Node 版 `slice(0, 8)`）。
         *
         * 手机屏幕竖着排 8 个滑块已经占掉大半屏，再多说明模型在拿滑块当表单用，
         * 那不该是 Present 的用法。
         */
        const val MAX_COUNT = 8

        /**
         * 从工具入参清洗出参数列表。
         *
         * **脏数据一律丢弃，不报错**：模型给错格式时用户该看到的是**画面**，
         * 而不是因为某个滑块格式不对导致整次展示失败。
         *
         * 丢弃规则（对齐 Node 版）：
         * - 不是对象 / `name` 为空 → 丢
         * - `value` 缺失或不是有限数 → 丢（滑块没有初始位置就没意义）
         *
         * 补齐规则：
         * - `min` 缺省 0；`max` 缺省 `max(10, value*2)`；`step` 缺省 1（非正数也视为缺省）
         * - **`max <= min` 时兜底成 `min + 1`** —— 否则 Compose Slider 的
         *   `valueRange` 直接抛 `IllegalArgumentException`，整个展示块崩掉
         */
        fun parse(raw: JsonArray?): List<PresentParam> {
            if (raw == null) return emptyList()
            val out = mutableListOf<PresentParam>()
            for (el in raw) {
                val o = el as? JsonObject ?: continue
                val name = o.str("name")?.trim().orEmpty()
                if (name.isEmpty()) continue
                val value = o.double("value")?.takeIf { it.isFinite() } ?: continue

                val min = o.double("min")?.takeIf { it.isFinite() } ?: 0.0
                val rawMax = o.double("max")?.takeIf { it.isFinite() } ?: maxOf(10.0, value * 2)
                val max = if (rawMax > min) rawMax else min + 1.0
                val step = o.double("step")?.takeIf { it.isFinite() && it > 0.0 } ?: 1.0

                out += PresentParam(
                    name = name,
                    label = o.str("label")?.takeIf { it.isNotBlank() } ?: name,
                    min = min,
                    max = max,
                    step = step,
                    value = value.coerceIn(min, max),
                )
                if (out.size >= MAX_COUNT) break
            }
            return out
        }

        /** 速度类参数名 —— 拖动这类参数时优先用 Web Animations 的 playbackRate（保进度不跳帧）。 */
        private val SPEEDISH = Regex(
            "^(speed|rate|velocity|spin|rotation|rotate|fps|转速|速度|速率)$",
            RegexOption.IGNORE_CASE,
        )

        /**
         * 这个参数是不是「速度类」。
         *
         * Web 端（PresentBlock.tsx 的 paramRuntime）对速度类参数走
         * `document.getAnimations()` + `playbackRate` 接管：直接改
         * `animation-duration` 会让 CSS 按新周期重算进度、画面瞬移，
         * 而 `playbackRate` 只改快慢、当前进度保留 —— 这才是「对着画面调参数」的手感。
         *
         * Android 端在注入的 JS 运行时里用同一套判断（见 [com.ccm.app.ui.chat] 的
         * presentParamRuntime），这里导出规则避免两边各写一份正则漂移。
         */
        fun isSpeedLike(name: String): Boolean = SPEEDISH.matches(name)
    }
}
