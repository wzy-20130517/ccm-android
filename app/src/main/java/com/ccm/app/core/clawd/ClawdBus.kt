package com.ccm.app.core.clawd

import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
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
    /**
     * 兜底收回定时器的协程作用域（2026-10-11 加）。
     *
     * 【为什么 Bus 自己需要 scope】HAPPY/ERROR 的收回原来完全依赖悬浮窗
     * 的脉冲订阅 —— 而 SharedFlow 无重放，订阅者缺失/被杀/重建时脉冲
     * 直接丢失，状态永远卡住。修法是在 Bus 内部自建定时器（不依赖任何
     * 外部订阅者）。
     *
     * 用 SupervisorJob + Dispatchers.Default：单个任务失败不影响其它，
     * 且不占主线程（delay 不需要 UI 线程）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前挂起的「延迟收回」任务（新任务到来时取消旧的）。 */
    private var holdJob: Job? = null

    /** HAPPY/ERROR 的展示时长（与悬浮窗的 REACTION_HOLD_MS 保持一致）。 */
    private const val REACTION_HOLD_MS = 2600L

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
     * 物理动作事件（phone_use 深度适配，2026-10-09）。
     *
     * 与 [pulse] 同为一次性事件，但**携带坐标** —— 悬浮窗收到后会先
     * 平滑移动到目标位置，再播对应动作动画（见 ClawdOverlayService）。
     *
     * 缓冲 16 个：一次手机操作可能连发（点击 → 等待 → 截图 → 再点击），
     * 而悬浮窗的移动动画有 ~300ms，缓冲不够会丢动作。
     */
    private val _actions = MutableSharedFlow<ClawdAction>(extraBufferCapacity = 16)
    val actions: SharedFlow<ClawdAction> = _actions.asSharedFlow()

    /**
     * 发一个物理动作（phone 工具调用）。
     *
     * 悬浮窗没开时静默丢弃 —— 动作是「表演给用户看」的，没有观众时
     * 不需要排队补播（用户回前台时再飘过去点一下反而莫名其妙）。
     */
    fun emitAction(action: ClawdAction) {
        _actions.tryEmit(action)
    }

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
        } else {
            // 【2026-10-11 修「Clawd 一直循环播放一个动画」】
            //
            // 原来 running=false 时**什么都不做** —— 状态靠悬浮窗那边的
            // 「COMPLETED/FAILED 脉冲 + delay(2.6s) + 检查 running」收回。
            // 那条路径有三个洞，任何一个都会让螃蟹**永远停在某个动画上**：
            //
            //   ① 脉冲丢失：SharedFlow 无重放，悬浮窗若在延迟期间被杀/重建，
            //      脉冲已消费，永远不会再收回
            //   ② 顺序问题：若 Done 事件先于 setRunning(false) 到达，
            //      2.6 秒后 running 已 false 能收回；但若某个路径**只**
            //      调 setRunning(false) 而没发 Done（比如用户 Ctrl+C 打断、
            //      或错误分支提前 return），脉冲从没发过 → 卡住
            //   ③ 多次往返：工具往返/续跑会反复 setRunning(true) → 状态
            //      被重置回 THINKING，看起来像"循环播放"
            //
            // 修法：**在这里直接兜底**。running=false 意味着"整轮真的结束了"，
            // 此时无论如何都该回 IDLE —— 唯一例外是刚播完的 HAPPY/ERROR
            // （那是给用户看的"完成/失败"反馈，立即抹掉反而突兀）。
            //
            // 所以：保留 HAPPY/ERROR 让悬浮窗的脉冲逻辑正常收回；
            // 其余状态（THINKING/SPEAKING/TYPING/READING/BUILDING…）
            // 立刻回 IDLE。
            when (_state.value) {
                ClawdState.HAPPY, ClawdState.ERROR -> {
                    // 【2026-10-11 二修】原来这里「不抢，交给悬浮窗的脉冲
                    // 延迟收回」—— 但那条路径**依赖脉冲送达**：
                    //   · SharedFlow 无重放，悬浮窗在 2.6s 延迟期间被杀/
                    //     重建 → 脉冲已消费 → 永远不会再收回
                    //   · 悬浮窗没开时脉冲直接丢弃（无订阅者）
                    //   · 协程被取消（collectLatest 遇到新脉冲）
                    // 任一情况 → **螃蟹永远卡在 HAPPY/ERROR**
                    //（用户报的「任务完成后卡在开心动画上不回到空闲」）。
                    //
                    // 修法：这里**自己起一个兜底定时器** —— 不依赖任何
                    // 外部订阅者。到点后如果状态还是 HAPPY/ERROR（说明
                    // 没有新任务接管），强制回 IDLE。
                    //
                    // 为什么还保留 HAPPY/ERROR 不立即清除：那是给用户的
                    // 「完成/失败」反馈，立即抹掉太突兀。等 2.6s 再看。
                    holdJob?.cancel()
                    holdJob = scope.launch {
                        delay(REACTION_HOLD_MS)
                        // 期间没有新任务接管（running 仍 false）且状态没变
                        // （还是那个 HAPPY/ERROR）才收回 —— 否则说明新一轮
                        // 已经开始，别抢它的状态。
                        if (!_running.value &&
                            (_state.value == ClawdState.HAPPY || _state.value == ClawdState.ERROR)
                        ) {
                            _state.value = ClawdState.IDLE
                        }
                    }
                }
                else -> _state.value = ClawdState.IDLE
            }
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
        // ══════════════════════════════════════════════════════════
        //  【Done 特殊处理】必须在读当前状态的地方判断
        // ══════════════════════════════════════════════════════════
        //
        // AgentLoop 的 finally 保证「恰好发一次 Done」（成功失败都发），
        // 所以**错误路径的事件序列是 Error → Done**。
        //
        // 原来 `forEvent(Done)` 无条件返回 HAPPY → 刚设好的 ERROR
        // 立刻被 HAPPY 覆盖 → 用户看到螃蟹在报错时开开心心。
        //
        // 修法：Done 的分支判断**当前状态**：
        //   · 已是 ERROR → 保持 ERROR，不发 COMPLETED 脉冲
        //     （让 FAILED 的 2.6s 收回逻辑正常走完）
        //   · 其余 → HAPPY + COMPLETED 脉冲（原行为）
        //
        // forEvent 那边已把 Done 改为返回 null（纯函数拿不到当前状态，
        // 判断只能在这里做）。
        if (ev == com.ccm.app.core.agent.AgentEvent.Done) {
            if (_state.value != ClawdState.ERROR) {
                setState(ClawdState.HAPPY)
                emitPulse(ClawdPulse.COMPLETED)
            }
            return
        }

        val next = ClawdState.forEvent(ev) ?: return

        // ══════════════════════════════════════════════════════════
        //  【防闪烁】同「类」状态不重复下发
        // ══════════════════════════════════════════════════════════
        //
        // ReasoningDelta 是逐块流式的（一次思考几十上百个 chunk），
        // 每个 chunk 都调 forEvent。而 forEvent 里对思考做了**随机**
        //（THINKING / THINKING_DEEP 二选一，用户要求随机）——
        // 不拦的话每来一个 chunk 就翻一次动画，螃蟹头顶疯狂闪烁。
        //
        // 判据用「同族」而非「相等」：两个思考态算同一族，
        // 切到别的族（如开始输出正文）才真正下发。
        val sameFamily = isSameFamily(_state.value, next)
        if (!sameFamily) setState(next)

        // 终态脉冲：悬浮窗收到后播几秒再收回（收回条件看 running，
        // 由 ChatSession 的 setRunning(false) 在整轮结束时置位）
        when (ev) {
            is com.ccm.app.core.agent.AgentEvent.Error -> emitPulse(ClawdPulse.FAILED)
            else -> {}
        }
    }

    /**
     * 两个状态是否属于「同一族」（族内切换不需要重下发动画）。
     *
     * 目前只有一族：两种思考态。其余状态各自独立。
     * 这样设计是因为随机选择只发生在思考族内 —— 如果将来别的状态也
     * 加了随机变体，在这里加一条即可。
     */
    private fun isSameFamily(a: ClawdState, b: ClawdState): Boolean {
        val thinking = setOf(ClawdState.THINKING, ClawdState.THINKING_DEEP)
        if (a in thinking && b in thinking) return true
        return a == b
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
