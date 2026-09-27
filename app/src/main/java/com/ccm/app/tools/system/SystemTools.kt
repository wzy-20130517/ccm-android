package com.ccm.app.tools.system

import com.ccm.app.bridge.NativeBridge
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Android 原生能力工具集 —— 剪贴板 / Toast / 通知 / 震动 / 电量 / 定位 / 打开链接 / TTS / 分享。
 *
 * ══════════════════════════════════════════════════════════════
 *  这些是 APK 相比 Node CLI 的「原生红利」
 * ══════════════════════════════════════════════════════════════
 *
 * Node CLI 侧要靠 `termux-clipboard-set` / `termux-toast` / `termux-notification`
 * 这些外部命令（每次调用一次进程启动）。APK 直接调 Android API，
 * 快且不依赖 Termux 装没装。
 *
 * 实现复用现有 [NativeBridge]（那个类已经有 sysNotify/sysClipboardGet/... 方法），
 * **不改它** —— 只做「工具壳 → bridge.call()」的适配。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 装饰性工具纪律（CCM 血泪教训）
 * ══════════════════════════════════════════════════════════════
 *
 * 这批工具里的 Toast / TTS / Notify / Vibrate / Battery / Location / ClipboardGet
 * 在 CCM 里被列为**装饰性工具**，`default` 权限模式下**未点名就拒绝**。
 *
 * 原因：AI 被骂了之后会用 Toast 道歉、用 TTS 念检讨、用 Notify 刷存在感 ——
 * 全是二次伤害。判断标准很简单：**用户没点名 + 任务不依赖它 = 不许调**。
 *
 * 这个纪律在 [com.ccm.app.tools.ToolPermissions.DECORATIVE_TOOLS] 里落地，
 * 所以这些工具**不需要自己再判一遍** —— 权限层会拦。
 */
class SystemTools(private val bridge: NativeBridge) {

    /** 调 bridge 并统一处理返回 */
    private fun callBridge(method: String, params: JSONObject = JSONObject()): ToolResult {
        return try {
            val raw = bridge.call(method, params)
            val obj = try {
                JSONObject(raw)
            } catch (_: Throwable) {
                return ToolResult.ok(raw)
            }
            val ok = obj.optBoolean("ok", true)
            if (ok) {
                // 有些返回把内容放 data/result/text 里
                val content = when {
                    obj.has("text") -> obj.optString("text")
                    obj.has("data") -> obj.optString("data")
                    obj.has("result") -> obj.optString("result")
                    else -> raw
                }
                ToolResult.ok(content.ifBlank { raw })
            } else {
                ToolResult.failed(obj.optString("error", raw))
            }
        } catch (e: Throwable) {
            ToolResult.Error("原生调用失败：${e.message}", ToolResult.INTERNAL)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  剪贴板
    // ══════════════════════════════════════════════════════════════

    class ClipboardGetTool(private val tools: SystemTools) : Tool() {
        override val name = "ClipboardGet"
        override val description = "读取系统剪贴板内容。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 5_000

        override val inputSchema: JsonObject = ToolSchema.noArgsSchema()

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            tools.callBridge("sys.clipboardGet")
    }

    class ClipboardSetTool(private val tools: SystemTools) : Tool() {
        override val name = "ClipboardSet"
        override val description = "将文本设置到系统剪贴板。避免长文本手动复制。"
        override val maxResultSizeChars = 300

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "text" to ToolSchema.string("要写入剪贴板的文本"),
            required = listOf("text"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input["text"] == null) "text is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            tools.callBridge("sys.clipboardSet", JSONObject().put("text", input.str("text") ?: ""))
    }

    // ══════════════════════════════════════════════════════════════
    //  Toast / 通知 / 震动
    // ══════════════════════════════════════════════════════════════

    class ToastTool(private val tools: SystemTools) : Tool() {
        override val name = "Toast"
        override val description = "显示 Android Toast 短消息（屏幕底部弹出短提示）。"
        override val maxResultSizeChars = 200

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "text" to ToolSchema.string("提示内容"),
            "short" to ToolSchema.boolean("短提示（默认 true）"),
            required = listOf("text"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("text").isNullOrBlank()) "text is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            tools.callBridge(
                "sys.toast",
                JSONObject()
                    .put("text", input.str("text") ?: "")
                    .put("short", input.bool("short") ?: true),
            )
    }

    class NotifyTool(private val tools: SystemTools) : Tool() {
        override val name = "Notify"
        override val description = "发送 Android 系统通知。支持标题、内容、震动等。"
        override val maxResultSizeChars = 300

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "title" to ToolSchema.string("通知标题"),
            "content" to ToolSchema.string("通知内容"),
            "vibrate" to ToolSchema.boolean("是否震动"),
            "sound" to ToolSchema.boolean("是否有提示音"),
            "priority" to ToolSchema.string("优先级", enum = listOf("high", "default", "low")),
            required = listOf("title"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("title").isNullOrBlank()) "title is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val p = JSONObject().put("title", input.str("title") ?: "")
            input.str("content")?.let { p.put("content", it) }
            input.bool("vibrate")?.let { p.put("vibrate", it) }
            input.bool("sound")?.let { p.put("sound", it) }
            input.str("priority")?.let { p.put("priority", it) }
            return tools.callBridge("sys.notify", p)
        }
    }

    class VibrateTool(private val tools: SystemTools) : Tool() {
        override val name = "Vibrate"
        override val description = "让手机震动指定毫秒。"
        override val maxResultSizeChars = 200

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "duration" to ToolSchema.integer("震动毫秒数（默认 200）", minimum = 1, maximum = 10_000),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            tools.callBridge(
                "sys.vibrate",
                JSONObject().put("duration", input.int("duration") ?: 200),
            )
    }

    // ══════════════════════════════════════════════════════════════
    //  电量 / 定位
    // ══════════════════════════════════════════════════════════════

    class BatteryTool(private val tools: SystemTools) : Tool() {
        override val name = "Battery"
        override val description = "获取电池状态（电量百分比、是否充电、温度等）。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.noArgsSchema()

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            tools.callBridge("sys.battery")
    }

    class LocationTool(private val tools: SystemTools) : Tool() {
        override val name = "Location"
        override val description = "获取 GPS 位置信息（纬度、经度、海拔等）。"
        override val isReadOnly = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "provider" to ToolSchema.string(
                "定位来源",
                enum = listOf("gps", "network", "passive"),
            ),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val p = JSONObject()
            input.str("provider")?.let { p.put("provider", it) }
            return tools.callBridge("sys.location", p)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  打开链接 / 分享 / TTS
    // ══════════════════════════════════════════════════════════════

    class OpenUrlTool(private val tools: SystemTools) : Tool() {
        override val name = "OpenUrl"
        override val description = "在浏览器中打开 URL，或用系统应用打开本地文件（如 .html/.png）。"
        override val maxResultSizeChars = 300

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "url" to ToolSchema.string("http(s) URL 或本地文件路径（/sdcard/... 或 file:///...）"),
            required = listOf("url"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("url").isNullOrBlank()) "url is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            tools.callBridge("sys.openUrl", JSONObject().put("url", input.str("url") ?: ""))
    }

    class ShareTool(private val tools: SystemTools) : Tool() {
        override val name = "Share"
        override val description = "通过 Android 分享菜单分享文件或文本到其他 App。"
        override val maxResultSizeChars = 300

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "text" to ToolSchema.string("要分享的文本"),
            "file" to ToolSchema.string("要分享的文件路径"),
            "action" to ToolSchema.string("send=分享（默认）| view=查看", enum = listOf("send", "view")),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val p = JSONObject()
            input.str("text")?.let { p.put("text", it) }
            input.str("file")?.let { p.put("file", it) }
            input.str("action")?.let { p.put("action", it) }
            if (p.length() == 0) return ToolResult.invalidInput("至少要给 text 或 file")
            return tools.callBridge("sys.share", p)
        }
    }

    /**
     * TTS —— Android 系统语音引擎朗读。
     *
     * ⚠️ 与 `say` 工具的区别（CCM 里两个都存在，别混）：
     * - **TTS**：系统引擎，音质取决于手机装的引擎，离线可用，**给用户听**
     * - **say**：Edge TTS（神经网络），音质好但需联网；还有 `secret` 模式（不回显）
     *
     * 这里实现的是前者（走 NativeBridge → NativeTts.kt 现有实现）。
     */
    class TtsTool(private val tools: SystemTools) : Tool() {
        override val name = "TTS"
        override val description = "用 Android TTS 朗读文本。"
        override val maxResultSizeChars = 300

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "text" to ToolSchema.string("要朗读的文本"),
            "language" to ToolSchema.string("语言（如 zh-CN、en-US），默认跟随系统"),
            "pitch" to ToolSchema.string("音高（如 1.0）"),
            required = listOf("text"),
        )

        override fun validateInput(input: JsonObject): String? =
            if (input.str("text").isNullOrBlank()) "text is required" else null

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val p = JSONObject().put("text", input.str("text") ?: "")
            input.str("language")?.let { p.put("language", it) }
            input.str("pitch")?.let { p.put("pitch", it) }
            return tools.callBridge("sys.tts", p)
        }
    }
}
