package com.ccm.app.core.agent

/**
 * 运行模式状态 —— deep / plan / watch 三个模式的**唯一持有者**。
 *
 * 对齐 CLI 的 `core/plan.mjs` 里的 `PlanMode` / `DeepMode` 两个类，
 * 外加 watch 模式（CLI 存在 `Agent.watchMode` 字段上）。
 *
 * ## 为什么抽成一个独立对象，而不是塞进 AgentLoop 的字段
 *
 * CLI 里这三个状态在 `index.mjs` 里 new 出来，**工具**和**主循环**共享同一个实例：
 * ```
 * const deepMode = new DeepMode()
 * registry.register(new EnterDeepModeTool(() => deepMode.enable()))   // 工具写
 * agent.setMaxTurns(deepMode.getMaxTurns())                           // 主循环读
 * ```
 * 工具层拿不到 AgentLoop（工具在 AgentLoop 之前就构造好了，而且
 * `core` 不能反向依赖 `tools`）。所以状态的持有者必须是**第三个对象**，
 * 两边都引用它 —— 这正是本类。
 *
 * APK 的传递路径：`AppGraph` 建实例 → 传给 `ChatSession.create` →
 * `AppContainer.build` → `AgentLoop`（读）**和** 塞进 `ToolContext`（工具写）。
 *
 * ## 生命周期
 * 一个会话一个实例，由 AppGraph 持有并跨会话重建。**进程重启即重置**
 * （对齐 CLI：这些状态是内存态，不落盘）。
 *
 * ## 线程安全
 * 工具在 Agent 协程里写，主循环在另一个协程里读 → 三个字段都是 `@Volatile`。
 */
class ModeState {

    /** 计划模式：只出计划、不执行工具（通过系统提示词实现，**不是**权限模式）。 */
    @Volatile
    var planMode: Boolean = false

    /** deep 模式：轮次上限提到 [DEEP_MAX_TURNS]。 */
    @Volatile
    var deepMode: Boolean = false

    /** 持续模式：一轮结束不返回，注入「继续」保持循环，直到关闭或用户打断。 */
    @Volatile
    var watchMode: Boolean = false

    /**
     * 计划模式要追加到系统提示词的片段（对齐 CLI `PlanMode.getSystemPromptAddition()`）。
     *
     * 返回空串 = 不追加。**每轮调用**（不是缓存），因为用户/AI 可能随时切换。
     */
    fun planPromptAddition(): String = if (planMode) PLAN_PROMPT else ""

    /** 给用户看的一句话状态（`/plan` `/deep` `/watch` 类命令回显可用）。 */
    fun describe(): String = buildString {
        append("模式：")
        append(if (planMode) "计划 " else "")
        append(if (deepMode) "deep " else "")
        append(if (watchMode) "持续" else "")
        if (!planMode && !deepMode && !watchMode) append("普通")
    }

    companion object {
        /**
         * 普通模式轮次上限。
         *
         * **必须与 `AppContainer.DEFAULT_MAX_TURNS` 一致** —— 那是构造 AgentLoop 时
         * 传进去的初值，这里是每轮 run 开始时用来「重置回普通档」的值。
         * 两处不一致会导致「退出 deep 后轮数变了个莫名其妙的数」。
         */
        const val NORMAL_MAX_TURNS = 200

        /**
         * deep 模式轮次上限。
         *
         * ⚠️ **APK 是 400，CLI 是 3000** —— 这是刻意的差异，不是漏改：
         * `AgentLoop.MAX_TURNS_HARD_CAP` 就是 400（子 Agent 续轮的硬闸门，
         * 设计理由是「子 Agent 在后台跑、用户看不见，给它无限轮次可能烧光额度」）。
         * 主 Agent 的 deep 模式如果设成 3000，会让「硬上限」这个概念出现两个值，
         * 而 `ExtendTurns` 的三道闸门全都基于 400 —— 从 1000 出发反而会被夹回去。
         *
         * 要真正对齐 CLI 的 3000，需要先重新评估硬上限的设计取舍（改它同时影响
         * 子 Agent 的额度保护），属于单独的技术决策，不在这里顺手改。
         */
        const val DEEP_MAX_TURNS = 400

        /**
         * 计划模式提示词（逐字对齐 CLI `core/plan.mjs` 的 `getSystemPromptAddition`）。
         */
        const val PLAN_PROMPT: String =
            "\n\n# 计划模式已启用\n" +
                "你需要先给出一个执行计划（步骤列表），不要执行任何工具。\n" +
                "等用户批准后再开始执行。\n"

        /**
         * 持续模式每轮的续跑指令（逐字对齐 CLI `core/agent.mjs` 的 watch 注入文本）。
         *
         * ⚠️ 与 CLI 的差异：CLI 给这条消息打 `hidden: true`（不进 UI）；
         * APK 的 `Message` 没有 hidden 字段，所以它会作为一条普通 user 消息
         * 出现在历史里。行为影响一致（模型都看得到），只是 UI 上多一行文字。
         * 加 hidden 字段要动 `Message` 的序列化与 UI 渲染，跨了 ui/ 的边界，
         * 不在本次范围内 —— 记在这里，留给后续。
         */
        const val WATCH_CONTINUE_PROMPT: String =
            "（持续模式：继续执行当前任务，如无新任务则保持监听，不要停止回复）"
    }
}
