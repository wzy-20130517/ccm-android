package com.ccm.app.ui.chat

import java.io.File

/**
 * `/context7` —— Context7 文档查询 MCP 配置（2026-10-07 对齐 CLI `core/integrations/context7.mjs`）。
 *
 * ## 语义（与 CLI 一一对应）
 *
 * | 输入 | 行为 |
 * |---|---|
 * | `/context7` / `status` | 查看配置状态（不启动） |
 * | `/context7 setup` | 写入默认配置（默认禁用，不启动） |
 * | `/context7 enable` | 启用（需重启加载） |
 * | `/context7 disable` | 禁用（重启后卸载） |
 * | `/context7 help` | 帮助 |
 *
 * ## 与 CLI 的差异
 *
 * CLI 写 `~/.claude-code-mobile/mcp.json`，APK 写存储根的 `mcp.json`
 * （同一个文件，路径不同）。默认配置（npx @upstash/context7-mcp）两端口径一致 ——
 * APK 的 stdio 通道走 proot 包装（McpInstaller），需要 Node 环境。
 *
 * setup **只补缺省值**，不覆盖已有的 command/args/env 自定义项（对齐 CLI）。
 * API key 不写入 mcp.json（对齐 CLI 的「key 走 shell 环境变量」纪律）。
 */
internal object SlashContext7 {

    /** 默认服务器配置（对齐 CLI `DEFAULT_SERVER`）。 */
    private const val DEFAULT_COMMAND = "npx"
    private val DEFAULT_ARGS = listOf("-y", "@upstash/context7-mcp@latest")

    fun dispatch(arg: String, mcpFile: File?): SlashResult {
        if (mcpFile == null) return SlashResult.Notice("存储未初始化。")
        val sub = arg.trim().split(Regex("\\s+")).firstOrNull()?.lowercase().orEmpty()

        return when (sub) {
            "", "status" -> status(mcpFile)
            "setup" -> setup(mcpFile)
            "enable" -> setEnabled(mcpFile, true)
            "disable" -> setEnabled(mcpFile, false)
            else -> help()
        }
    }

    private fun help() = SlashResult.Notice(
        "**Context7（可选 MCP）**\n\n" +
            "- `/context7 setup` — 写入默认配置（默认禁用，不启动）\n" +
            "- `/context7 enable` — 启用，**重启后加载**\n" +
            "- `/context7 disable` — 禁用，重启后卸载\n" +
            "- `/context7 status` — 查看配置状态\n\n" +
            "说明：\n" +
            "- 首次启用可能由 npx 下载 `@upstash/context7-mcp@latest`（需 Node 环境）\n" +
            "- 需要更高限额时，可在 shell 设置 `CONTEXT7_API_KEY`，再重启\n" +
            "- API key 不会写入 `mcp.json`；已有的其他服务器不会被覆盖",
    )

    /** 读 mcp.json（不存在或损坏时返回空 mcpServers 结构）。 */
    private fun readMcp(f: File): org.json.JSONObject {
        if (!f.exists()) return org.json.JSONObject().put("mcpServers", org.json.JSONObject())
        return try {
            val o = org.json.JSONObject(f.readText())
            if (!o.has("mcpServers") || o.optJSONObject("mcpServers") == null) {
                o.put("mcpServers", org.json.JSONObject())
            }
            o
        } catch (_: Throwable) {
            org.json.JSONObject().put("mcpServers", org.json.JSONObject())
        }
    }

    private fun writeMcp(f: File, o: org.json.JSONObject): Boolean = try {
        f.parentFile?.mkdirs()
        f.writeText(o.toString(2) + "\n")
        true
    } catch (_: Throwable) { false }

    private fun status(f: File): SlashResult {
        if (!f.exists()) {
            return SlashResult.Notice(
                "**Context7**：未配置（找不到 `mcp.json`）。\n\n用 `/context7 setup` 写入默认配置。",
            )
        }
        val data = try { org.json.JSONObject(f.readText()) } catch (t: Throwable) {
            return SlashResult.Notice("**Context7**：读取 `mcp.json` 失败 —— `${t.message}`")
        }
        val server = data.optJSONObject("mcpServers")?.optJSONObject("context7")
            ?: return SlashResult.Notice(
                "**Context7**：未配置。\n\n用 `/context7 setup` 写入默认配置。",
            )
        val disabled = server.optBoolean("disabled", false)
        val cmd = server.optString("command", "").ifBlank { "(未配置)" }
        val args = server.optJSONArray("args")?.let { arr ->
            (0 until arr.length()).joinToString(" ") { arr.optString(it, "") }
        }.orEmpty()
        val hasKey = server.toString().contains("CONTEXT7_API_KEY") && server.toString().contains("sk-")
        return SlashResult.Notice(
            "**Context7 状态**\n\n" +
                "- 已配置：是\n" +
                "- 启用：${if (disabled) "否（disabled）" else "是"}\n" +
                "- 命令：`$cmd $args`\n" +
                "- 内联 API key：${if (hasKey) "⚠ 有（建议改为环境变量）" else "无"}\n" +
                "- 配置文件：`${f.absolutePath}`\n\n" +
                "_enable 后需重启 App 才加载；disable 同样重启后生效。_",
        )
    }

    private fun setup(f: File): SlashResult {
        val data = readMcp(f)
        val servers = data.optJSONObject("mcpServers")!!
        val old = servers.optJSONObject("context7")
        val fresh = org.json.JSONObject().apply {
            put("command", DEFAULT_COMMAND)
            put("args", org.json.JSONArray(DEFAULT_ARGS))
            put("env", org.json.JSONObject())
            // setup 只补缺省值，不覆盖已有自定义项（对齐 CLI）
            put("disabled", old?.optBoolean("disabled", true) != false)
        }
        // 旧配置里的自定义 command/args/env 保留
        if (old != null) {
            old.keys().forEach { k ->
                if (k != "disabled") fresh.put(k, old.get(k))
            }
        }
        servers.put("context7", fresh)
        return if (writeMcp(f, data)) {
            SlashResult.Notice(
                "**Context7 配置完成**（默认仍禁用，用 `/context7 enable` 启用后重启加载）。\n\n" +
                    "_配置文件：`${f.absolutePath}`_",
            )
        } else {
            SlashResult.Notice("保存失败：写入 `mcp.json` 出错。")
        }
    }

    private fun setEnabled(f: File, enabled: Boolean): SlashResult {
        val data = readMcp(f)
        val servers = data.optJSONObject("mcpServers")!!
        val server = servers.optJSONObject("context7")
            ?: org.json.JSONObject().apply {
                put("command", DEFAULT_COMMAND)
                put("args", org.json.JSONArray(DEFAULT_ARGS))
                put("env", org.json.JSONObject())
            }
        server.put("disabled", !enabled)
        servers.put("context7", server)
        return if (writeMcp(f, data)) {
            SlashResult.Notice(
                if (enabled) {
                    "**Context7 已启用**（重启后加载）。\n\n" +
                        "_首次加载可能由 npx 下载 `@upstash/context7-mcp`，需要 Node 环境。_"
                } else {
                    "**Context7 已禁用**（重启后卸载）。"
                },
            )
        } else {
            SlashResult.Notice("保存失败：写入 `mcp.json` 出错。")
        }
    }
}
