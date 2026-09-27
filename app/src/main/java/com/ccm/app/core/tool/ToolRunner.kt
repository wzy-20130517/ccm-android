package com.ccm.app.core.tool

import kotlinx.serialization.json.JsonObject

/**
 * 工具执行器接口 —— **所有工具调用的统一入口**。
 *
 * ## 为什么要有这个接口（而不是 AgentLoop 直接调 `tool.execute()`）
 *
 * 因为「执行一个工具」远不止调它的 `execute()`。完整的执行链有七环：
 * ```
 * ① 取消预检        用户已中断 → 直接返回，不浪费一次权限/hook 计算
 * ② 参数校验        tool.validateInput() → 失败返回 invalid_input（模型可据此改正）
 * ③ 权限裁决        权限规则 → deny 则拒绝
 * ④ PreToolUse hook 可 DENY / 可改写入参
 * ⑤ 执行            tool.execute()，带超时与异常兜底
 * ⑥ 结果截断        超限写盘 + 保头尾（防一次 cat 大文件撑爆上下文）
 * ⑦ PostToolUse hook 观察类（审计/通知）
 * ```
 * 漏掉任何一环都是真实事故（见 `tools/ToolExecutor.kt` 的类注释）。
 *
 * ## 为什么要抽象成接口（而不是 core 直接依赖 tools 的实现）
 *
 * **依赖方向是 `core ← tools`**，不能反过来：
 * - `tools/` 的 115 处 import 都指向 `core/`
 * - `core/` 是零 Android 依赖的纯逻辑层，能在 JVM 单测里跑
 *
 * 如果 `AgentLoop` 直接 `import com.ccm.app.tools.ToolExecutor`：
 * - 编译期：core 模块被绑死在 Android 工具实现上，单测跑不了
 * - 架构上：依赖倒挂，将来换实现要改 core
 *
 * 所以接口定义在 core（本文件），**实现由 tools 层提供**（`ToolExecutor`），
 * 装配时注入（见 `tools/ToolsBootstrap`）。
 *
 * ## 空实现
 * [Passthrough] 只调 `execute()`，跳过其余六环 —— **仅供单测**，
 * 生产环境必须用真正的执行器（否则权限/截断全失效）。
 */
interface ToolRunner {

    /**
     * 执行一个工具调用。
     *
     * **约定**：
     * - **永不抛异常** —— 一切失败（校验失败、权限拒绝、超时、工具崩溃）
     *   都转成 [ToolResult.Error] 返回，由调用方转成给模型的提示。
     *   抛异常会把整个 Agent 循环带崩。
     * - 已取消时应尽快返回 [ToolResult.Error]（category = [ToolResult.USER_ABORT]）
     *
     * @param tool 工具实例
     * @param input 入参（模型给的原始 JSON，**未校验**）
     * @param ctx 执行上下文（含取消信号、cwd、UI 回调）
     * @param sessionId 会话 id（写进 hook 环境变量 `SESSION_ID`）
     */
    suspend fun run(
        tool: Tool,
        input: JsonObject,
        ctx: ToolContext,
        sessionId: String = "",
    ): ToolResult

    /**
     * 空实现 —— **仅供单测**。
     *
     * 直接调 `tool.execute()`，**跳过**校验/权限/hook/截断。
     * 生产环境用它会：`plan` 模式下模型能直接改代码、大输出撑爆上下文、
     * 用户中断后工具继续跑。
     */
    class Passthrough : ToolRunner {
        override suspend fun run(
            tool: Tool,
            input: JsonObject,
            ctx: ToolContext,
            sessionId: String,
        ): ToolResult = try {
            if (ctx.isCancelled) ToolResult.cancelled()
            else tool.execute(input, ctx)
        } catch (e: ToolCancelledException) {
            ToolResult.cancelled()
        } catch (e: Throwable) {
            ToolResult.Error(e.message ?: "工具执行异常", ToolResult.INTERNAL)
        }
    }

    companion object {
        /** 默认执行器（生产环境必须显式注入真正的实现，这里只是防 NPE）。 */
        val Unset: ToolRunner = Passthrough()
    }
}
