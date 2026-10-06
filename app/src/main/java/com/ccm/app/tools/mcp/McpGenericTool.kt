package com.ccm.app.tools.mcp

import com.ccm.app.core.mcp.McpManager
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * MCP 通用工具 —— 一个工具干两件事：列工具 / 调工具。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * ## 为什么用「通用工具」而不是每个 MCP 工具一个 Tool 实例
 *
 * MCP server 的工具是**运行时发现**的（连上后 listTools）——
 * 而 CCM 的工具注册是**启动时一次性**的（`ToolsBootstrap.install`）。
 *
 * 两条路：
 * - A. 启动时同步连所有 MCP server、列工具、注册 → **启动变慢**
 *   （每个 server 要握手，网络差的要十几秒）
 * - B. 注册一个通用工具（本类），内部**懒加载** → 启动不慢
 *
 * 选了 B。代价：模型看到的工具名是 `mcp_<server>`（不是具体工具名），
 * 需要先 `action:list` 再 `action:call`。多一步但可接受。
 *
 * ## 用法
 * ```
 * { "server": "mail-qq", "action": "list" }              → 列出该 server 的工具
 * { "server": "mail-qq", "action": "call",
 *   "tool": "search_code", "args": {"minutes": 5} }      → 调用
 * ```
 * ═══════════════════════════════════════════════════════════════
 */
class McpGenericTool(
    private val manager: McpManager,
    private val serverName: String,
) : Tool() {

    override val name: String =
        "mcp_${serverName}".replace(Regex("[^A-Za-z0-9_]"), "_")

    override val description: String = buildString {
        append("MCP 服务器「")
        append(serverName)
        append("」的工具入口。\n")
        append("先 `action:list` 看有哪些工具（每个工具的参数 schema 也会列出），")
        append("再 `action:call` 调用具体的。\n")
        append("例：`{action:list}` → `{action:call, tool:\"xxx\", args:{...}}`")
    }

    override val inputSchema: JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("properties", buildJsonObject {
            put("action", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("list = 列工具（含 schema）· call = 调用"))
                put("enum", kotlinx.serialization.json.JsonArray(listOf(
                    JsonPrimitive("list"), JsonPrimitive("call"),
                )))
            })
            put("tool", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("action=call 时必填：工具名"))
            })
            put("args", buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("description", JsonPrimitive("action=call 时的参数对象"))
            })
        })
        put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("action"))))
    }

    override val isReadOnly: Boolean = false
    override val isConcurrencySafe: Boolean = false
    override val maxResultSizeChars: Int = 30_000

    override fun validateInput(input: JsonObject): String? {
        val action = input["action"]?.toString()?.trim('"') ?: return "action is required"
        if (action !in listOf("list", "call")) return "action 必须是 list 或 call"
        if (action == "call" && input["tool"] == null) return "action=call 时 tool 必填"
        return null
    }

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val action = input["action"]?.toString()?.trim('"') ?: return ToolResult.invalidInput("action 缺失")

        return when (action) {
            "list" -> {
                val tools = manager.connectAll()[serverName]
                    ?: return ToolResult.failed("服务器「$serverName」未连接（检查 mcp.json 的 url 和网络）")
                if (tools.isEmpty()) {
                    ToolResult.ok("服务器「$serverName」没有暴露任何工具。")
                } else {
                    val sb = StringBuilder("**$serverName 的工具（${tools.size} 个）**\n\n")
                    tools.forEach { t ->
                        sb.append("### ${t.name}\n")
                        sb.append(t.description).append("\n")
                        sb.append("参数 schema：`").append(t.inputSchema).append("`\n\n")
                    }
                    sb.append("_调用：`{action:\"call\", tool:\"<名字>\", args:{...}}`_")
                    ToolResult.ok(sb.toString())
                }
            }
            "call" -> {
                val toolName = input["tool"]?.toString()?.trim('"')
                    ?: return ToolResult.invalidInput("tool 缺失")
                val argsObj = input["args"]?.jsonObject
                val args: Map<String, Any?> = argsObj?.entries?.associate { (k, v) ->
                    k to jsonToAny(v)
                } ?: emptyMap()

                // 确保已连接
                manager.connectAll()
                val r = manager.call(serverName, toolName, args)
                if (r.success) ToolResult.ok(formatResult(r.result))
                else ToolResult.failed("MCP 调用失败：${r.error ?: "未知错误"}")
            }
            else -> ToolResult.invalidInput("未知 action：$action")
        }
    }

    private fun formatResult(json: org.json.JSONObject?): String {
        if (json == null) return "（MCP 返回空）"
        val content = json.optJSONArray("content")
        if (content != null && content.length() > 0) {
            val sb = StringBuilder()
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "text" -> sb.append(item.optString("text", "")).append("\n")
                    "image" -> sb.append("[图片]").append("\n")
                    "resource" -> sb.append(item.optString("uri", "[资源]")).append("\n")
                    else -> sb.append(item.toString()).append("\n")
                }
            }
            if (sb.isNotBlank()) return sb.toString().trim()
        }
        return json.toString()
    }

    private fun jsonToAny(el: kotlinx.serialization.json.JsonElement): Any? = when (el) {
        is JsonPrimitive -> when {
            el.isString -> el.content
            el.content == "true" -> true
            el.content == "false" -> false
            el.content.toLongOrNull() != null -> el.content.toLong()
            el.content.toDoubleOrNull() != null -> el.content.toDouble()
            else -> el.content
        }
        is kotlinx.serialization.json.JsonArray -> el.map { jsonToAny(it) }
        is JsonObject -> el.entries.associate { (k, v) -> k to jsonToAny(v) }
        else -> el.toString()
    }
}
