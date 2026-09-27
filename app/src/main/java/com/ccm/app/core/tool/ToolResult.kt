package com.ccm.app.core.tool

/**
 * 工具执行结果。
 *
 * 参照 Node 版 `Tool.run()` 的返回：它把结果统一成「文本 + 是否错误」两个维度，
 * 外加一个错误分类（用于决定要不要重试、以及给用户看什么样的提示）。
 *
 * ## 为什么 Success 也带 [Success.isError]
 * 因为「执行成功」和「业务失败」是两件事。典型场景：
 * - `Bash` 跑 `grep` 没匹配到 → 命令正常退出但 exitCode=1，对模型来说算**失败信息**
 * - `Read` 读一个不存在的文件 → 工具没崩，但结果应该被标成错误，让模型知道要换个路径
 *
 * 所以 [Success] 表示「工具跑完了」，[Success.isError] 表示「跑完的结果算不算失败」。
 * 真正的异常路径（工具崩了、参数没通过校验、被用户中断）才走 [Error]。
 *
 * ## 为什么不直接抛异常
 * 抛出的异常在 Agent 层会被捕获，但**错误分类会退化成 `unknown`** ——
 * 而 `unknown` 意味着「不重试」，一次网络抖动就变成硬失败。
 * 明确返回 [Error] 并给出正确的 [Error.category]，才能让重试策略正常工作。
 */
sealed class ToolResult {

    /**
     * 执行完成（含「跑完但业务失败」）。
     *
     * @param content 给模型看的文本。会被截断（超 [Tool.maxResultSizeChars]）并可能写盘。
     * @param isError 业务层是否算失败。true 时 UI 标红，且模型会看到失败提示。
     */
    data class Success(val content: String, val isError: Boolean = false) : ToolResult()

    /**
     * 执行失败（参数错、权限拒、工具崩溃、被中断）。
     *
     * @param message 错误信息。**要写给模型看** —— 说清「哪里错了、该怎么改」，
     *   模型能据此自我修正；只说 "failed" 等于让它瞎猜。
     * @param category 错误分类，见下方常量。决定 Agent 层要不要重试。
     */
    data class Error(val message: String, val category: String = UNKNOWN) : ToolResult()

    /** 便捷读取：结果文本（[Error] 取 message）。 */
    val textOrMessage: String
        get() = when (this) {
            is Success -> content
            is Error -> message
        }

    /** 便捷读取：是否算失败。 */
    val failed: Boolean
        get() = when (this) {
            is Success -> isError
            is Error -> true
        }

    companion object {
        // ───────── 错误分类常量（对齐 Node 版 `_classifyError` 的 name 字段） ─────────
        //
        // 命名与 Node 版保持一致，方便对照排查。**不要改这些字符串** ——
        // 它们会写进 trace 日志，改了会让历史日志和新日志对不上。

        /** 未知错误。不重试（安全默认）。 */
        const val UNKNOWN = "unknown"

        /** 参数不合法（模型传错了）。不重试 —— 重试同样的参数必然再失败。 */
        const val INVALID_INPUT = "invalid_input"

        /** 权限被拒。不重试，要用户改权限设置。 */
        const val PERMISSION_DENIED = "permission_denied"

        /** 用户主动中断。不重试（这是用户的明确意图）。 */
        const val USER_ABORT = "user_abort"

        /** 网络错误。可重试。 */
        const val NETWORK = "network"

        /** 文件不存在。不重试。 */
        const val NOT_FOUND = "not_found"

        /** 超时。可重试（但注意别跨层叠加，见 Node 版教训）。 */
        const val TIMEOUT = "timeout"

        /** 工具内部崩溃（未预期异常）。不重试。 */
        const val INTERNAL = "internal"

        // ───────── 便捷构造 ─────────

        /** 成功。 */
        fun ok(content: String): ToolResult = Success(content)

        /** 跑完了但算失败（如 exitCode != 0）。 */
        fun failed(content: String): ToolResult = Success(content, isError = true)

        /** 参数错误。 */
        fun invalidInput(message: String): ToolResult = Error(message, INVALID_INPUT)

        /** 权限被拒。 */
        fun denied(message: String = "权限被拒绝"): ToolResult = Error(message, PERMISSION_DENIED)

        /** 被用户中断。 */
        fun cancelled(message: String = "工具执行被中断"): ToolResult = Error(message, USER_ABORT)

        /** 文件/资源不存在。 */
        fun notFound(message: String): ToolResult = Error(message, NOT_FOUND)
    }
}
