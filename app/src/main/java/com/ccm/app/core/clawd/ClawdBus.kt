package com.ccm.app.core.clawd

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Clawd 悬浮窗与 Agent 之间的**单向事件总线**（2026-10-09）。
 *
 * ## 为什么需要总线（而不是直接调用）
 *
 * 数据流向：`ChatSession.collectEvents` → **总线** → `ClawdOverlayService`
 *
 * 两者互不知道对方存在：
 * - ChatSession 在 core 层，不该 import Android Service（core 零 Android 依赖）
 * - 悬浮窗可能没开（用户没授权 / App 在前台），此时事件应被静默丢弃
 *
 * 用单例对象而不是依赖注入：进程内只有一个悬浮窗、一个 Agent 循环，
 * 多实例没有意义，反而要处理「谁持有谁」的生命周期问题。
 *
 * ## 为什么用 SharedFlow 而不是 StateFlow
 *
 * StateFlow 会重放最后一个值 —— 悬浮窗晚启动时会收到一个陈旧的中间状态
 * （比如已经结束的 THINKING），然后卡在那儿。SharedFlow 无重放：
 * 没订阅时事件直接丢弃，订阅后只收新的。
 *
 * 但**状态本身**用 StateFlow（[state] / [bubbleText]）—— 悬浮窗
 * 一启动就要知道「现在该显示什么」，不能等下一个事件。
 *
 * @see ClawdState 状态定义与事件映射
 */
object ClawdBus {

    /**
     * 当前应显示的动画状态。
     *
     * 悬浮窗订阅它切 SVG；没有悬浮窗时值照样更新（下次打开即是最新）。
     */
    private val _state = MutableStateFlow(ClawdState.IDLE)
    val state: StateFlow<ClawdState> = _state.asStateFlow()

    /**
     * 消息气泡文本（空串 = 不显示气泡）。
     *
     * 只在 SPEAKING 状态有意义 —— 用户要求「气泡里就是它说的话」。
     * 正文是流式的，所以这个值会被高频更新（每个 TextDelta 一次），
     * 悬浮窗侧做了节流（见 ClawdOverlayService）。
     */
    private val _bubbleText = MutableStateFlow("")
    val bubbleText: StateFlow<String> = _bubbleText.asStateFlow()

    /**
     * 任务是否正在跑（决定「用户手动关掉悬浮窗后要不要自动弹回来」）。
     */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /**
     * 一次性事件流（需要做动画过渡的场景，如「刚完成，播一下 HAPPY」）。
     *
     * 状态类（StateFlow）适合「持续显示什么」，脉冲类（SharedFlow）适合
     * 「发生了什么」—— 后者订阅者不在时丢弃是正确的（不该补播一个
     * 十分钟前的完成动画）。
     */
    private val _pulse = MutableSharedFlow<ClawdPulse>(extraBufferCapacity = 8)
    val pulse: SharedFlow<ClawdPulse> = _pulse.asSharedFlow()

    /**
     * 更新状态（悬浮窗未开时静默，值仍然保留）。
     *
     * @param bubble 非 null 时同时更新气泡文本；null = 不动气泡。
     *   为什么这么设计：切到 SPEAKING 时要带文本，但 ToolStart 等事件
     *   切状态时不该清掉气泡（用户还在读上一条）。
     */
    fun setState(next: ClawdState, bubble: String? = null) {
        _state.value = next
        if (bubble != null) _bubbleText.value = bubble
        // 离开 SPEAKING 时清空气泡（气泡只在说话时显示）
        if (next != ClawdState.SPEAKING) _bubbleText.value = ""
    }

    /** 追加正文到气泡（流式累加，超长自动保留尾部）。 */
    fun appendBubble(delta: String) {
        if (delta.isEmpty()) return
        val merged = _bubbleText.value + delta
        // 悬浮窗气泡只有几行，留太长既看不清也费内存。
        // 保留尾部 300 字符 —— 用户看的是「刚说了什么」。
        _bubbleText.value = if (merged.length > 300) merged.takeLast(300) else merged
        _state.value = ClawdState.SPEAKING
    }

    /** 标记任务开始/结束（悬浮窗据此决定是否自动弹出）。 */
    fun setRunning(running: Boolean) {
        _running.value = running
        if (running) {
            // 新一轮开始：清掉上一轮的残留（气泡 + 回到思考态）
            _bubbleText.value = ""
            _state.value = ClawdState.THINKING
        }
    }

    /** 发一个脉冲事件（如任务完成时的 HAPPY 动画）。 */
    fun emitPulse(p: ClawdPulse) {
        _pulse.tryEmit(p)
    }

    /** 重置到空闲（用户手动停止 / 会话被清空）。 */
    fun reset() {
        _bubbleText.value = ""
        _state.value = ClawdState.IDLE
        _running.value = false
    }

    /**
     * AgentEvent 统一入口 —— ChatSession 每收到一个事件就调它一次。
     *
     * 把「事件 → 状态/气泡」的全部判断收在这里，调用方（ChatSession）
     * 只转发不判断。好处是映射规则集中一处，改动画策略不用动对话逻辑。
     *
     * ⚠️ **[running] 不由本函数维护** —— 它只认 [setRunning]（ChatSession 在
     * run 开始/结束时显式调用）。早期版本在这里对每个事件 `_running.value = true`，
     * 与 setRunning 语义重复且更糟：Done 事件到达时 running 仍为 true，
     * 悬浮窗的「HAPPY 播完收回 IDLE」判断会一直失败 → 螃蟹永远停在开心动画。
     *
     * 对每个事件的处理：
     *   · TextDelta     → SPEAKING + 累加气泡文本（用户要的「气泡里是它说的话」）
     *   · ReasoningDelta→ THINKING（思考动画）
     *   · ToolStart     → 按工具类型切 READING/TYPING/BUILDING
     *   · Error         → ERROR（并发 FAILED 脉冲，悬浮窗停留几秒后收回）
     *   · Done          → HAPPY（并发 COMPLETED 脉冲）
     *   · 其余          → 不动（Usage/TurnEnd 等纯统计事件）
     */
    fun onAgentEvent(ev: com.ccm.app.core.agent.AgentEvent) {
        // 正文增量走累加（不走 setState，否则会清空气泡）
        if (ev is com.ccm.app.core.agent.AgentEvent.TextDelta) {
            appendBubble(ev.text)
            return
        }
        val next = ClawdState.forEvent(ev) ?: return
        setState(next)
        // 终态脉冲：悬浮窗收到后播几秒再收回（收回条件看 running，
        // 由 ChatSession 的 setRunning(false) 在整轮结束时置位）
        when (ev) {
            is com.ccm.app.core.agent.AgentEvent.Error -> emitPulse(ClawdPulse.FAILED)
            com.ccm.app.core.agent.AgentEvent.Done -> emitPulse(ClawdPulse.COMPLETED)
            else -> {}
        }
    }
}

/** 一次性脉冲事件。 */
enum class ClawdPulse {
    /** 任务成功完成 → 播 HAPPY，几秒后回 IDLE。 */
    COMPLETED,

    /** 任务失败 → 播 ERROR，几秒后回 IDLE。 */
    FAILED,

    /** 用户停止 → 回 IDLE。 */
    STOPPED,
}
