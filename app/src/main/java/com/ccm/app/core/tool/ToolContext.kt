package com.ccm.app.core.tool

import kotlinx.coroutines.Job

/**
 * 工具执行上下文 —— 工具运行期间能拿到的一切外部环境。
 *
 * 由 Agent 循环在每次调用 [Tool.execute] 前构造并传入。
 * 参照 Node 版 `Tool.execute(input, ctx)` 的 `ctx` 参数（带 `signal`）。
 *
 * ## 为什么把这些塞进一个类，而不是全局单例
 * 工具可能被**并发**执行（[Tool.isConcurrencySafe] 为 true 的那些），
 * 每个工具看到的 cwd / 取消信号 / UI 回调都必须是**自己那一份**。
 * 全局单例在并发下会串味（A 工具的进度推到 B 工具的窗口）。
 *
 * @property cwd 当前工作目录。文件类工具的相对路径以此为基准。
 * @property extraDirs 额外可访问目录（对齐 `/add-dir`）。文件工具做路径白名单校验时用。
 * @property permissionMode 权限模式，取值 `default` / `acceptEdits` / `plan` / `bypassPermissions`。
 *   工具可据此决定是否放行（如 `plan` 模式下拒绝写操作）。
 * @property cancelSignal 取消信号。**长任务必须监听它**，否则用户中断后进程残留。
 * @property ui 进度与内容回调。
 * @property spawnSubAgent 派生子 Agent 的能力。`null` = 当前工具不允许派子 Agent。
 * @property storage 应用存储（回收站 / 撤销快照 / 大输出落盘 / 会话目录）。
 *   `null` = 无持久化场景（纯计算工具、单测）。
 * @property settings 只读配置快照（Provider 的 baseUrl/apiKey/model、各服务的 key）。
 *   `null` = 未注入，需要配置的工具应给出「未配置」提示而非崩溃。
 * @property sessionId 当前会话 id。Hooks 会把它写进环境变量 `SESSION_ID`，
 *   Agent 工具也用它区分「哪个会话派生的子任务」。空串 = 无会话（单测）。
 */
class ToolContext(
    val cwd: String,
    val extraDirs: List<String> = emptyList(),
    val permissionMode: String = "default",
    val cancelSignal: Job,
    val ui: ToolUiCallback,
    val spawnSubAgent: (suspend (SubAgentSpec) -> SubAgentResult)? = null,
    val storage: ToolStorage? = null,
    val settings: ToolSettings? = null,
    val sessionId: String = "",
    /**
     * 当前正在跑的 AgentLoop（问题40：ExtendTurns 用）。
     *
     * 【为什么放这里而不是全局 getter】ExtendTurns 是「给自己续轮」——
     * 必须改**调用它的那个 loop** 的 maxTurns。主 Agent 和子 Agent 同时
     * 在跑时，全局引用会指错对象（子 Agent 续轮改到主 loop 上）。
     * 通过 ctx 传递，每个工具调用天然知道自己属于哪个 loop。
     *
     * 用 `Any?` 而不是具体类型 —— core/tool 不能依赖 core/agent
     * （依赖方向），调用方自行 cast。
     */
    val selfLoop: Any? = null,
) {

    /** 便捷判断：取消信号是否已失效（用户中断 / 超时）。 */
    val isCancelled: Boolean get() = !cancelSignal.isActive

    /**
     * 取消时抛出 [ToolCancelledException]，用于在长循环里插检查点。
     *
     * 用法：
     * ```
     * for (line in hugeFile) {
     *     ctx.checkCancelled()   // 用户中断时立刻退出，不留半成品
     *     process(line)
     * }
     * ```
     */
    fun checkCancelled() {
        if (isCancelled) throw ToolCancelledException()
    }

    override fun toString(): String =
        "ToolContext(cwd=$cwd, mode=$permissionMode, extraDirs=${extraDirs.size})"
}

/** 用户中断工具执行时抛出（由 Agent 层捕获并转成 `user_abort` 结果）。 */
class ToolCancelledException : Exception("工具执行被中断")

/**
 * 工具 → UI 的回调。
 *
 * 两个方法的区别（别混用）：
 * - [onProgress]：**临时**状态，覆盖式显示（"下载中 45%"），不进对话历史
 * - [onContent]：**正式**内容，进对话历史（如后台任务产出的日志片段）
 *
 * 实现方（dev-ui）需要保证这两个方法可被并发调用且不抛异常 ——
 * 工具不该因为 UI 卡住而失败。
 */
interface ToolUiCallback {

    /** 上报临时进度（覆盖式，不进历史）。 */
    suspend fun onProgress(text: String)

    /** 往对话里推一条正式内容（进历史）。 */
    suspend fun onContent(text: String)

    /** 请求 UI 展示富内容；后台/单测没有 UI 时允许忽略。 */
    suspend fun onPresent(kind: String, title: String?, caption: String?, content: String, paths: List<String>)

    companion object {
        /** 空实现 —— 无 UI 场景（后台任务、单元测试）用。 */
        val NoOp: ToolUiCallback = object : ToolUiCallback {
            override suspend fun onProgress(text: String) = Unit
            override suspend fun onContent(text: String) = Unit
            override suspend fun onPresent(kind: String, title: String?, caption: String?, content: String, paths: List<String>) = Unit
        }
    }
}

/**
 * 派生子 Agent 的规格（由工具构造，Agent 层消费）。
 *
 * 对齐 Node 版 `Agent` 工具的参数：`description` / `prompt` / `subagent_type` /
 * `run_in_background` / `agent_name`。
 */
data class SubAgentSpec(
    /** 完整任务指令，子 Agent 以此为「首轮用户消息」。 */
    val prompt: String,
    /** 3~5 词的任务标识，仅用于显示与命名。 */
    val description: String = "",
    /** 子 Agent 类型：`general-purpose` / `Explore` / `Plan` / `Coordinator` 或自定义角色名。 */
    val subagentType: String = "general-purpose",
    /** 是否后台运行。true = 立即返回占位，用 AgentStatus/AgentOutput 观察。 */
    val runInBackground: Boolean = false,
    /** 给子 Agent 起名，便于之后用 SendMessage 唤醒它继续干活（复用其上下文）。 */
    val agentName: String? = null,
)

/**
 * 子 Agent 的执行结果。
 *
 * [ok] 为 false 时**不一定是错误** —— 典型如并发超限
 * （`rejected = "concurrency_limit"`），此时任务没失败，只是没排上，
 * 调用方应该重试或改串行，而不是放弃。用 [rejected] 区分这两种情况。
 */
data class SubAgentResult(
    val ok: Boolean,
    /** 子 Agent 的最终输出（或错误说明）。 */
    val output: String,
    /** 后台任务的 task_id，可用于 AgentStatus / AgentOutput / AgentStop。 */
    val taskId: String? = null,
    /** 消耗的轮次。 */
    val turns: Int = 0,
    /**
     * 未被执行的原因，`null` = 正常执行过。
     * 已知值：`concurrency_limit`（并发超限，重试即可）。
     */
    val rejected: String? = null,
    /** 失败时的错误信息。 */
    val error: String? = null,
)
