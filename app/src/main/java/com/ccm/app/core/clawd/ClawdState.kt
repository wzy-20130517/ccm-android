package com.ccm.app.core.clawd

import com.ccm.app.core.agent.AgentEvent

/**
 * Clawd 悬浮窗的动画状态（2026-10-09）。
 *
 * ## 需求来源
 * 用户：「当 ccm 退到后台的时候，会有一个 Clawd（就是欢迎页那个吉祥物），
 * 那个 Clawd 有各种动画，按照 spinner 来。比如思考时，它做出思考的动作，
 * bash 或者其他需要写的动作时，它会做出打字的动作。如果它说了正文，
 * 会有一个消息气泡在它周围，气泡里就是它说的话。」
 *
 * ## 素材来源
 * `rullerzhou-afk/clawd-on-desk`（6.4k star）的 22 个 SVG，每个自带 CSS 动画
 * （纯 SVG + @keyframes，不需要 JS 驱动）。存在 `assets/clawd/`。
 *
 * ## 设计：状态 ↔ SVG 一对一
 * 每个状态映射到一个 SVG 文件。切换状态 = 切换 WebView 里的 img.src。
 * SVG 的动画在加载后自动播放，我们只负责「什么时候显示哪个」。
 *
 * @param svg assets/clawd/ 下的文件名
 * @param label 用于调试和气泡默认文案
 */
enum class ClawdState(val svg: String, val label: String) {
    /** 空闲：呼吸 + 眼球跟随。App 在后台但没有任务在跑。 */
    IDLE("clawd-idle-follow.svg", "空闲"),

    /** 思考中：头顶冒思考气泡（对应 spinner 的 "Thinking…"）。 */
    THINKING("clawd-working-thinking.svg", "思考中"),

    /**
     * 深度思考：头上顶着 "ultrathink" 字样发光（2026-10-09 加）。
     *
     * 与 [THINKING] 随机切换 —— 用户要求「思考和深度思考随机着使用」。
     * 真实区分「浅想/深思」需要模型侧信号（thinking budget 之类），
     * 而我们只有「在思考」这一个事实。所以随机是**刻意的表演**：
     * 一只活的螃蟹本来就会偶尔显得「想得特别用力」。
     */
    THINKING_DEEP("clawd-working-ultrathink.svg", "深度思考"),

    /** 打字：螃蟹敲键盘 + 屏幕代码滚动（对应 Bash / Write / Edit 类工具）。 */
    TYPING("clawd-working-typing.svg", "写代码"),

    /** 建造：搬砖盖楼（对应长时间执行 / 批量操作）。 */
    BUILDING("clawd-working-building.svg", "执行中"),

    /** 读文件：捧着书看（对应 Read / Grep / Glob 类只读工具）。 */
    READING("clawd-idle-reading.svg", "读文件"),

    /** 调试：拿放大镜（对应报错排查 / 反复重试）。 */
    DEBUGGING("clawd-working-debugger.svg", "调试"),

    /** 说话：带消息气泡（对应正文输出，气泡里显示实际文字）。 */
    SPEAKING("clawd-idle-bubble.svg", "说话"),

    /** 完成：闪光 + 开心（任务成功结束，停留几秒后回 IDLE）。 */
    HAPPY("clawd-happy.svg", "完成"),

    /** 出错：乌云 + ERROR（任务失败，停留几秒后回 IDLE）。 */
    ERROR("clawd-error.svg", "出错"),

    /** 休眠：躺着睡（长时间无活动，省电 + 不打扰）。 */
    SLEEPING("clawd-sleeping.svg", "休眠"),
    ;

    companion object {
        /**
         * 工具名 → 动画状态。
         *
         * 按「工具在干什么」分三类，对应用户要的「bash 或其他需要写的动作 → 打字」：
         *   · 读类（Read/Grep/Glob…）→ READING（捧着书）
         *   · 写类（Bash/Write/Edit…）→ TYPING（敲键盘）
         *   · 其他（搜索/生图/手机操作…）→ BUILDING（搬砖）
         *
         * 用前缀/包含匹配而不是精确表：工具集会增长（还有 MCP 工具、
         * 子 Agent 的动态工具），精确表一定会漏。
         */
        fun forTool(toolName: String): ClawdState {
            val n = toolName.lowercase()
            return when {
                // 读类：只读检索工具
                n.startsWith("read") || n.startsWith("grep") || n.startsWith("glob") ||
                    n.startsWith("codesearch") || n.startsWith("hashline") ||
                    n.startsWith("symbols") || n.startsWith("repomap") ||
                    n.startsWith("viewimage") || n.startsWith("viewvideo") ||
                    n.startsWith("lsp") -> READING
                // 写类：改文件 / 跑命令
                n.startsWith("bash") || n.startsWith("write") || n.startsWith("edit") ||
                    n.startsWith("multiedit") || n.startsWith("applypatch") ||
                    n.startsWith("saferename") || n.startsWith("hashlineedit") ||
                    n.startsWith("test") -> TYPING
                // 其余（搜索/网络/生图/手机/邮箱…）→ 搬砖
                else -> BUILDING
            }
        }

        /**
         * AgentEvent → 动画状态。
         *
         * 返回 null = 该事件不改变状态（如 Usage/TurnEnd 这类纯统计事件）。
         *
         * 【为什么 TextDelta 要特殊处理】正文输出时用户想看到「它说的话」，
         * 所以 SPEAKING 状态还要带气泡文本 —— 由调用方（ClawdOverlay 的事件桥）
         * 另行更新，这里只返回状态。
         */
        fun forEvent(ev: AgentEvent): ClawdState? = when (ev) {
            // 思考：随机在「普通思考 / 深度思考」之间选（用户要求随机使用）。
            // 用 Math.random() 而不是固定值 —— 每次思考的动画都不一样，
            // 螃蟹看起来才有「在想不同的事」的感觉。
            is AgentEvent.ReasoningDelta -> if (Math.random() < DEEP_THINK_CHANCE) THINKING_DEEP else THINKING
            is AgentEvent.TextDelta -> SPEAKING
            is AgentEvent.ToolStart -> forTool(ev.name)
            is AgentEvent.ToolProgress -> null   // 工具进行中，保持当前状态
            is AgentEvent.ToolResult -> null
            is AgentEvent.Present -> null
            is AgentEvent.Error -> ERROR
            is AgentEvent.TurnEnd -> null        // 一轮结束但任务可能继续
            is AgentEvent.Usage -> null
            AgentEvent.Done -> HAPPY
        }

        /** 深度思考出现概率（0.35 = 约三分之一的思考会「想得特别用力」）。 */
        private const val DEEP_THINK_CHANCE = 0.35
    }
}
