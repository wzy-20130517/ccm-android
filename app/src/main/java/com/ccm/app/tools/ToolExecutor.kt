package com.ccm.app.tools

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolCancelledException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONObject

/**
 * 工具执行链 —— **所有工具调用的唯一入口**。
 *
 * ══════════════════════════════════════════════════════════════
 *  执行链（顺序不能变）
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 * ① 取消预检        用户已中断 → 直接返回，不浪费一次权限/hook 计算
 * ② 参数校验        tool.validateInput() → 失败返回 invalid_input（模型可据此改正）
 * ③ 权限裁决        ToolPermissions.resolve() → deny 则拒绝
 * ④ PreToolUse hook 可 DENY / 可改写入参
 * ⑤ 执行            tool.execute()，带超时与异常兜底
 * ⑥ 结果截断        ToolOutputStore（超限写盘 + 保头尾）
 * ⑦ PostToolUse hook 观察类（审计/通知），不影响结果
 * ```
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么必须统一入口（而不是让 Agent 循环直接调 execute）
 * ══════════════════════════════════════════════════════════════
 *
 * 漏掉任何一环都是真实事故：
 * · 漏权限 → `plan` 模式下模型直接把代码改了
 * · 漏截断 → 一次 `cat` 大文件把上下文撑爆
 * · 漏取消预检 → 用户点了停止，工具还在后台跑（截图/网络请求残留）
 * · 漏异常兜底 → 一个工具抛异常把整个 Agent 循环带崩
 *
 * 【线程安全】无状态，可并发调用（工具并行执行时各自独立走一遍链路）。
 *
 * @param permissions 权限裁决器
 * @param hooks hook 管理器
 * @param outputStore 大结果落盘
 * @param defaultTimeoutMs 工具默认超时（工具可通过 [Tool.maxResultSizeChars] 之外的方式覆盖的暂不支持）
 */
class ToolExecutor(
    private val permissions: ToolPermissions,
    private val hooks: ToolHooks,
    private val outputStore: ToolOutputStore,
    private val defaultTimeoutMs: Long = 600_000L,
) : com.ccm.app.core.tool.ToolRunner {

    // ⚠️ 实现 core 的 [com.ccm.app.core.tool.ToolRunner] 接口是**必须的**，不是可选装饰：
    // 依赖方向是 core ← tools（不能倒挂）。AgentLoop 依赖接口、装配时注入本类实例，
    // 这样 core 层能跑 JVM 单测，也不会被绑死在 Android 工具实现上。
    // 漏掉 implements 的后果：AgentLoop 退回 ToolRunner.Passthrough ——
    // 权限/hook/截断六环全绕过（plan 模式下能直接改代码、大输出撑爆上下文）。

    /** 执行统计（供 /stats 与诊断用） */
    @Volatile var totalCalls: Long = 0; private set
    @Volatile var deniedCalls: Long = 0; private set
    @Volatile var failedCalls: Long = 0; private set
    @Volatile var hookDeniedCalls: Long = 0; private set

    /**
     * 执行一个工具调用。
     *
     * **这是唯一入口** —— Agent 循环不要直接调 `tool.execute()`。
     *
     * @param tool 工具实例
     * @param input 入参（JsonObject）
     * @param ctx 执行上下文
     * @param sessionId 会话 id（写进 hook 环境变量）
     * @return 工具结果。**永不抛异常** —— 一切失败都转成 [ToolResult.Error]
     */
    override suspend fun run(
        tool: Tool,
        input: JsonObject,
        ctx: ToolContext,
        sessionId: String,
    ): ToolResult {
        totalCalls++

        // ① 取消预检
        if (ctx.isCancelled) {
            return ToolResult.cancelled("工具未执行：用户已中断")
        }

        // ② 参数校验
        try {
            tool.validateInput(input)?.let { err ->
                return ToolResult.invalidInput(err)
            }
        } catch (e: Throwable) {
            return ToolResult.Error(
                "参数校验器异常：${e.message}",
                ToolResult.INTERNAL,
            )
        }

        // ③ 权限裁决
        val decision = try {
            permissions.resolve(tool.name, input.toOrgJson(), tool)
        } catch (_: Throwable) {
            ToolPermissions.Decision.ALLOW   // 裁决器自身出错 → fail-open（不阻塞工作）
        }
        if (!decision.allowed) {
            deniedCalls++
            val msg = decision.reason ?: "权限被拒绝"
            return if (decision.needAsk) {
                ToolResult.Error("$msg（需要用户确认）", ToolResult.PERMISSION_DENIED)
            } else {
                ToolResult.Error(msg, ToolResult.PERMISSION_DENIED)
            }
        }

        // ④ PreToolUse hook
        var effectiveInput = input
        try {
            val hookResult = hooks.trigger(
                "PreToolUse",
                ToolHooks.HookContext(
                    event = "PreToolUse",
                    tool = tool.name,
                    input = input.toOrgJson(),
                    sessionId = sessionId,
                ),
            )
            if (hookResult.deny) {
                hookDeniedCalls++
                return ToolResult.Error(
                    "Hook 阻止执行：${hookResult.denyMessage}",
                    ToolResult.PERMISSION_DENIED,
                )
            }
            hookResult.updatedInput?.let { updated ->
                effectiveInput = updated.toKotlinJson() ?: input
            }
        } catch (_: Throwable) {
            // hook 崩溃不阻塞（fail-open）
        }

        // ⑤ 执行
        val result: ToolResult = try {
            val r = withTimeoutOrNull(defaultTimeoutMs) {
                tool.execute(effectiveInput, ctx)
            }
            r ?: ToolResult.Error(
                "工具执行超时（${defaultTimeoutMs / 1000} 秒）",
                ToolResult.TIMEOUT,
            )
        } catch (_: ToolCancelledException) {
            ToolResult.cancelled()
        } catch (e: Throwable) {
            // 工具抛异常 → 转成 internal 错误（不崩 Agent 循环）
            ToolResult.Error(
                "工具执行异常：${e.message ?: e.javaClass.simpleName}",
                ToolResult.INTERNAL,
            )
        }

        if (result.failed) failedCalls++

        // ⑥ 结果截断（只处理文本，attachments 独立走多模态通道，不参与截断）
        val finalResult = truncateResult(tool, result)

        // ⑦ PostToolUse hook（观察类，不改结果）
        try {
            hooks.trigger(
                "PostToolUse",
                ToolHooks.HookContext(
                    event = "PostToolUse",
                    tool = tool.name,
                    input = effectiveInput.toOrgJson(),
                    output = finalResult.textOrMessage,
                    sessionId = sessionId,
                ),
            )
        } catch (_: Throwable) {
            // 观察类 hook 失败不影响结果
        }

        return finalResult
    }

    /** 对结果文本做截断（超限写盘 + 保头尾）。attachments 原样保留。 */
    private fun truncateResult(tool: Tool, result: ToolResult): ToolResult = when (result) {
        is ToolResult.Success -> {
            val limit = tool.maxResultSizeChars
            val truncated = outputStore.truncate(result.content, limit, tool.name)
            if (truncated === result.content) result
            else result.copy(content = truncated)
        }
        is ToolResult.Error -> {
            // 错误信息也可能很长（堆栈），同样截断
            val truncated = outputStore.truncate(result.message, null, "${tool.name}-error")
            if (truncated === result.message) result
            else result.copy(message = truncated)
        }
    }
}

// ── JsonObject 转换辅助 ────────────────────────────────────────────────
//
// 项目里有两套 JSON 库并存：
// · kotlinx.serialization（core/ 新代码用，类型安全）
// · org.json（现有 bridge/service 代码用，Android 内置）
//
// 工具的 inputSchema 用前者（要序列化进请求体），
// 而 ToolPermissions 的规则文件、Hook 环境变量用后者（读文件方便）。
// 这两组转换就是桥。

/** kotlinx JsonObject → org.json JSONObject */
internal fun JsonObject.toOrgJson(): JSONObject {
    val obj = JSONObject()
    this.forEach { (k, v) ->
        obj.put(k, jsonElementToAny(v))
    }
    return obj
}

private fun jsonElementToAny(el: kotlinx.serialization.json.JsonElement): Any = when (el) {
    is JsonPrimitive -> when {
        el.isString -> el.content
        el.contentOrNull == "true" -> true
        el.contentOrNull == "false" -> false
        else -> el.contentOrNull?.toDoubleOrNull() ?: el.contentOrNull ?: ""
    }
    is kotlinx.serialization.json.JsonObject -> el.toOrgJson()
    is kotlinx.serialization.json.JsonArray -> {
        val arr = org.json.JSONArray()
        el.forEach { arr.put(jsonElementToAny(it)) }
        arr
    }
    else -> el.toString()
}

/** org.json JSONObject → kotlinx JsonObject（用于 hook 改写入参） */
internal fun JSONObject.toKotlinJson(): JsonObject? = try {
    val map = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
    keys().forEach { k ->
        map[k] = anyToJsonElement(opt(k))
    }
    JsonObject(map)
} catch (_: Throwable) {
    null
}

private fun anyToJsonElement(v: Any?): kotlinx.serialization.json.JsonElement = when (v) {
    null, JSONObject.NULL -> kotlinx.serialization.json.JsonNull
    is Boolean -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is String -> JsonPrimitive(v)
    is JSONObject -> v.toKotlinJson() ?: kotlinx.serialization.json.JsonNull
    is org.json.JSONArray -> {
        val list = mutableListOf<kotlinx.serialization.json.JsonElement>()
        for (i in 0 until v.length()) list += anyToJsonElement(v.opt(i))
        kotlinx.serialization.json.JsonArray(list)
    }
    else -> JsonPrimitive(v.toString())
}
