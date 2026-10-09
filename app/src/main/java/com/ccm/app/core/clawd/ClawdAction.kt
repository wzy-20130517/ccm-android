package com.ccm.app.core.clawd

/**
 * Clawd 的「物理动作」事件（2026-10-09）。
 *
 * ══════════════════════════════════════════════════════════════
 *  与 [ClawdState] 的分工
 * ══════════════════════════════════════════════════════════════
 *
 * - [ClawdState] = **它是什么状态**（持续态：思考中 / 打字中 / 空闲）
 *   由 AgentEvent 驱动，映射到一个 SVG，切了就停在那
 *
 * - [ClawdAction] = **它做了什么动作**（瞬时态：在某个位置点了一下 / 划了一下）
 *   由 phone_use 工具驱动，包含**屏幕坐标**，悬浮窗会**移动过去**再播动画
 *
 * 需求：点击时 Clawd 流畅地来到点击处摆动作，滑动时有相应动作。
 *
 * ══════════════════════════════════════════════════════════════
 *  坐标系
 * ══════════════════════════════════════════════════════════════
 *
 * [x]/[y] 是**主屏物理像素坐标**（与 phone_snapshot 的元素坐标同一空间）。
 *
 * ⚠️ 操作副屏（background 模式）时**不要传坐标** —— 副屏坐标与主屏
 * 不同尺度，而且用户根本看不到副屏，飘过去没有意义。这种情况传
 * [NO_POSITION]，悬浮窗原地播动作即可（表示「我在干活」）。
 *
 * @param type 动作类型
 * @param x 屏幕 X（物理像素）；[NO_POSITION] = 不移动
 * @param y 屏幕 Y（物理像素）
 * @param dx 滑动横向分量（仅 [Type.SWIPE] 有意义，用于让螃蟹往反方向倾）
 * @param dy 滑动纵向分量（仅 [Type.SWIPE] 有意义）
 */
data class ClawdAction(
    val type: Type,
    val x: Int = NO_POSITION,
    val y: Int = NO_POSITION,
    val dx: Int = 0,
    val dy: Int = 0,
) {
    enum class Type {
        /** 点击/长按某处 → 飘过去探头看（react-left / react-right）。 */
        TAP,

        /** 滑动/滚动 → 拖着走（react-drag），方向由 [dx]/[dy] 决定。 */
        SWIPE,

        /** 截图 → 放大镜观察（working-debugger）。 */
        SCREENSHOT,

        /** 读元素树（snapshot）→ 左右张望（idle-look）。 */
        SNAPSHOT,

        /** 输入文字 → 打字（working-typing）。 */
        TYPE_TEXT,

        /** 启动应用 → 施法（working-wizard）。 */
        LAUNCH_APP,
    }

    /** 是否带有效坐标（决定要不要移动悬浮窗）。 */
    val hasPosition: Boolean get() = x != NO_POSITION && y != NO_POSITION

    companion object {
        /** 坐标哨兵：不移动（原地播动作）。 */
        const val NO_POSITION = Int.MIN_VALUE

        /** 带坐标的动作（点击/截图这类知道落点的）。 */
        fun at(type: Type, x: Int, y: Int): ClawdAction = ClawdAction(type, x, y)

        /** 不带坐标的动作（原地播 —— 副屏操作、或拿不到坐标时）。 */
        fun here(type: Type): ClawdAction = ClawdAction(type)

        /** 滑动（带方向分量）。 */
        fun swipe(dx: Int, dy: Int, x: Int = NO_POSITION, y: Int = NO_POSITION): ClawdAction =
            ClawdAction(Type.SWIPE, x, y, dx, dy)
    }
}
