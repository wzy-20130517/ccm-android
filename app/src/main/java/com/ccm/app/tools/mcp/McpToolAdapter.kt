package com.ccm.app.tools.mcp

import com.ccm.app.core.mcp.McpClient
import com.ccm.app.core.mcp.McpManager
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * MCP 工具适配器 —— 把 MCP server 的工具包装成 CCM 的 [Tool]。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * 命名规则：`mcp_<server>_<tool>`（对齐 CLI 的 mcp-client.mjs）。
 *
 * 例：server=mail-qq, tool=search_code → 工具名 `mcp_mail_qq_search_code`
 *
 * ⚠️ 工具名里的 `-` 会被替换成 `_`（有些 API 对工具名有字符限制）。
 * ═══════════════════════════════════════════════════════════════
 */
class McpToolAdapter(
    private val manager: McpManager,
    private val server: String,
    private val mcpTool: McpClient.McpTool,
) : Tool() {

    /** 工具名：`mcp_<server>_<tool>`，非法字符换下划线。 */
    override val name: String =
        "mcp_${server}_${mcpTool.name}".replace(Regex("[^A-Za-z0-9_]"), "_")

    override val description: String = buildString {
        append("[MCP:")
        append(server)
        append("] ")
        append(mcpTool.description.ifBlank { mcpTool.name })
    }

    /** 用 MCP 给的 JSON Schema（解析成 kotlinx JsonObject）。 */
    override val inputSchema: JsonObject = try {
        kotlinx.serialization.json.Json.parseToJsonElement(mcpTool.inputSchema).jsonObject
    } catch (_: Throwable) {
        buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {})
        }
    }

    // MCP 工具的性质未知 —— 保守当"会写"
    override val isReadOnly: Boolean = false
    override val isConcurrencySafe: Boolean = false
    override val maxResultSizeChars: Int = 30_000

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        // JsonObject → Map<String, Any?>（MCP SDK 要 Map）
        val args: Map<String, Any?> = try {
            input.entries.associate { (k, v) -> k to jsonToAny(v) }
        } catch (t: Throwable) {
            return ToolResult.invalidInput("参数解析失败：${t.message}")
        }

        val r = manager.call(server, mcpTool.name, args)
        return if (r.success) {
            ToolResult.ok(formatResult(r.result))
        } else {
            ToolResult.failed("MCP 调用失败：${r.error ?: "未知错误"}")
        }
    }

    /** 把 MCP 返回的 JSON 转成可读文本（提取 content 数组）。 */
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

    /** kotlinx JsonElement → 普通 Kotlin 值。 */
    private fun jsonToAny(el: kotlinx.serialization.json.JsonElement): Any? = when (el) {
        is JsonPrimitive -> when {
            el.isString -> el.content
            el.content == "true" -> true
            el.content == "false" -> false
            el.content.toLongOrNull() != null -> el.content.toLong()
            el.content.toDoubleOrNull() != null -> el.content.toDouble()
            else -> el.content
        }
        is JsonArray -> el.map { jsonToAny(it) }
        is JsonObject -> el.entries.associate { (k, v) -> k to jsonToAny(v) }
        else -> el.toString()
    }
}
