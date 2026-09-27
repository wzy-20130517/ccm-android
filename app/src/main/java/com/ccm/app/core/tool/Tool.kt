package com.ccm.app.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * 工具基类 —— 所有工具的统一契约。
 *
 * 阶段 2（dev-core）提供契约，阶段 3（dev-tools）实现具体工具。
 * 参照 Node 版 `core/tools.mjs` 的 `Tool` / `buildTool()`。
 *
 * ## 契约冻结
 * 下面四个 `abstract` 成员与三个 `open val` 标志位是**冻结签名**，
 * 由 `/sdcard/Download/claude-workspace/rewrite/CONTRACTS.md` 第二节定义。
 * 改动前必须先发消息给 main。
 *
 * ## 三个标志位的用途
 * Agent 循环靠它们决定调度策略（对齐 Node 版 `_partitionToolCalls`）：
 * - [isReadOnly] + [isConcurrencySafe] 都为 true → 可以**并发**执行（Read/Grep/Glob）
 * - [isDestructive] → 需要权限确认；`bypassPermissions` 模式下才自动放行
 *
 * 注意「只读」和「可并发」是**两个独立维度**：一个工具可以只读但不能并发
 * （如 phone_snapshot —— 读手机屏幕，但多个并发快照会互相干扰），
 * 所以不要用一个布尔量代替两个。
 *
 * ## 为什么 input 是 JsonObject 而不是强类型参数
 * 工具参数来自**模型生成的 JSON**，运行时才知道长什么样。用 JsonObject 原样传递，
 * 由工具自己用 `input["path"]?.jsonPrimitive?.contentOrNull` 取值 —— 这与 Node 版
 * 一致，也让 [inputSchema] 与 [execute] 收到的数据天然同构（schema 就是给它校验的）。
 * 强类型化会让「模型少传一个字段」变成反序列化异常，错误信息反而更差。
 *
 * @see ToolContext 执行上下文（取消信号、cwd、UI 回调）
 * @see ToolResult 返回值
 * @see normalizeToolSchema 出口兜底（防 Gemini 后端 400）
 */
abstract class Tool {

    /** 工具名，模型用这个名字调用。必须全局唯一，如 `Read` / `Bash` / `phone_click`。 */
    abstract val name: String

    /** 工具说明，会进系统提示词的可用工具列表。写清「什么时候用、什么时候别用」。 */
    abstract val description: String

    /**
     * 参数的 JSON Schema。
     *
     * ⚠️ **必须是合法的 object schema**：`type: "object"` 时 `properties` 字段必须存在，
     * 哪怕是个空对象。漏写 `properties` 会让 Gemini 系后端报
     * `400 /properties: null is not of type "object"`，**一个工具坏 → 整个请求被拒**。
     * 用 [objectSchema] 构造可以避免这个坑；实在手写，Agent 出口处还有
     * [normalizeToolSchema] 兜底。
     */
    abstract val inputSchema: JsonObject

    /**
     * 只读工具（不修改任何外部状态）。
     *
     * 只读工具在 `plan` 权限模式下自动放行，非只读的要拦。
     * 默认 `false`（安全优先：拿不准就当成会写）。
     */
    open val isReadOnly: Boolean = false

    /**
     * 破坏性操作（删文件、跑任意命令、改配置）。
     *
     * 默认 `false`。注意这不是「会不会写」——`Write` 会写但不是破坏性
     * （内容可被 undo 恢复），而 `Bash rm -rf` 是破坏性。
     */
    open val isDestructive: Boolean = false

    /**
     * 能否与其他工具**并发**执行。
     *
     * 默认 `false`。只有明确安全（无共享状态、无顺序依赖）才置 true。
     * 典型反例：两个 `Edit` 改同一个文件 —— 并发会互相覆盖。
     */
    open val isConcurrencySafe: Boolean = false

    /**
     * 结果字符数上限。`null` = 用全局默认（30000，对齐 Node 版 `TOOL_OUTPUT_DEFAULT`）。
     *
     * 超限时由 Agent 层截断（保头尾）并把完整结果写盘。
     * 大多数工具不需要覆盖它。
     */
    open val maxResultSizeChars: Int? = null

    /**
     * 参数校验（可选覆写）。返回 `null` = 通过；返回字符串 = 错误信息。
     *
     * 在校验失败时**不要抛异常** —— 返回清晰的错误信息能让模型自己修正重试，
     * 而异常会被当成工具崩溃，模型不知道该改什么。
     * 参照 Node 版 `Tool.validateInput` 的「字符串 = 错误信息」风格。
     */
    open fun validateInput(input: JsonObject): String? = null

    /**
     * 执行工具。
     *
     * **取消**：长任务（Bash、网络请求）必须检查 [ToolContext.cancelSignal] 或把
     * 它传给底层 API（OkHttp 的 `Call.cancel()`、Process 的 `destroy()`），
     * 否则用户 Ctrl+C / 点停止后进程会残留。
     *
     * **进度**：超过 2 秒的操作应该通过 [ToolContext.ui] 报进度，
     * 让用户知道没卡死。
     *
     * **异常**：正常情况下不要抛 —— 失败请返回 [ToolResult.Error]。
     * 抛出的异常会被 Agent 层捕获并转成错误结果（不会崩 App），
     * 但错误分类会退化成 `unknown`，可读性差。
     */
    abstract suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult

    /** 调试用：`Tool(name=Read, readOnly=true, concurrent=true)` */
    override fun toString(): String =
        "Tool(name=$name, readOnly=$isReadOnly, destructive=$isDestructive, concurrent=$isConcurrencySafe)"
}
