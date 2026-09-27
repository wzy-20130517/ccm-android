package com.ccm.app.tools.phone

import android.content.Context
import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.tools.NativeTts
import kotlinx.serialization.json.JsonObject

/**
 * say —— 语音播报。
 *
 * 参照 Node 版 `core/tools-phone.mjs:SayTool`（约 40 行）+ `core/edge-tts.mjs`。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 什么时候说（CCM 的使用纪律，必须遵守）
 * ══════════════════════════════════════════════════════════════
 *
 * **只在这几种时刻说**：
 * - 任务开始（说明大概要几步）
 * - 遇到障碍改路线
 * - 需要用户介入（等密码/等确认）
 * - 任务完成 / 任务失败
 *
 * **禁止**：逐步播报点击滑动等中间动作 —— 那些用户切回终端就能看到，
 * 念出来只是噪音。**一个多步任务通常只该说 2~4 次。**
 *
 * 一句话控制在 **25 字内**，说人话不念路径行号。
 *
 * ══════════════════════════════════════════════════════════════
 *  secret 模式（听写场景）
 * ══════════════════════════════════════════════════════════════
 *
 * `secret:true` = **只播报、终端不回显内容**。
 * 用于听写/答题等「答案不能出现在屏幕上」的场景 —— 用户只能用耳朵听。
 * 结果行会显示「已播报（内容隐藏，N 字符）」而不是原文。
 *
 * ══════════════════════════════════════════════════════════════
 *  与 /voice 的区别（别混）
 * ══════════════════════════════════════════════════════════════
 *
 * | 机制 | 谁决定 | 念什么 |
 * |---|---|---|
 * | **say 工具** | **AI 主动决定** | 指定的一句话（进度播报、听写答案） |
 * | **`/voice` 命令** | **用户开启** | 自动念 AI 的**正文**（渲染层能力） |
 *
 * `/voice` 是渲染层能力，**没有对应工具，AI 不能开关它**。
 * 两者互不影响，可以同时存在。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 与 TTS 工具的区别
 * ══════════════════════════════════════════════════════════════
 *
 * - **TTS**：走 NativeBridge 的系统 TTS（`tools/system/SystemTools.kt`）
 * - **say**（本文件）：直接调 [NativeTts] 单例，多一个 secret 模式
 *
 * 两者底层是同一个引擎，但 say 有「隐藏内容」能力，且语义更明确
 * （「播报进度」而不是「朗读文本」）。保留两个是为了语义清晰。
 */
class SayTool(private val context: Context) : Tool() {

    override val name = "say"
    override val description =
        "用语音播报一句话（不占屏幕、不进截图/dump）。" +
            "专为「用户不在 Termux 界面时也能知道进展」设计。\n" +
            "【只在这几种时刻说】任务开始（说明大概要几步）、遇到障碍改路线、" +
            "需要用户介入（等密码/等确认）、任务完成、任务失败。\n" +
            "【禁止】逐步播报点击滑动等中间动作 —— 那些用户切回终端就能看到，念出来只是噪音。" +
            "一个多步任务通常只该说 2~4 次。\n" +
            "一句话控制在 25 字内，说人话不念路径行号。\n" +
            "【secret:true】只播报、终端不回显内容（结果行显示为「已播报（内容隐藏）」）。" +
            "用于听写/答题等「答案不能出现在屏幕上」的场景：用户只能用耳朵听。"
    override val maxResultSizeChars = 500

    override val inputSchema: JsonObject = ToolSchema.objectSchema(
        "text" to ToolSchema.string("要念的话，25 字内，口语化"),
        "secret" to ToolSchema.boolean("为 true 时终端不回显播报内容（听写场景用），默认 false"),
        "voice" to ToolSchema.string("可选音色短名（如 yunxia / xiaoxiao），省略用默认"),
        "style" to ToolSchema.string(
            "可选语气预设：cheerful/excited/gentle/calm/serious/sad/angry/affectionate/chat/narration",
        ),
        "styledegree" to ToolSchema.string("可选语气强度，0.01 到 2；1 为默认强度"),
        required = listOf("text"),
    )

    override fun validateInput(input: JsonObject): String? =
        if (input.str("text").isNullOrBlank()) "text is required" else null

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val text = input.str("text")!!
        val secret = input.bool("secret") == true

        // 语速/音调：style 与 styledegree 在 APK 侧映射为 rate/pitch 的轻微偏移。
        // （Node 版走 Edge TTS 的 prosody 参数；APK 用系统 TTS，只能近似。）
        val style = input.str("style")
        val degree = input.str("styledegree")?.toFloatOrNull()?.coerceIn(0.01f, 2f) ?: 1f
        val (rate, pitch) = mapStyle(style, degree)

        return try {
            val ok = NativeTts.speak(
                context = context,
                text = text,
                flush = true,
                rate = rate,
                pitch = pitch,
            )

            if (ok) {
                if (secret) {
                    // ⚠️ secret 模式下**不要**把原文写进结果 ——
                    // 结果会进对话历史，等于把答案写在了屏幕上
                    ToolResult.ok("已播报（内容隐藏，${text.length} 字符）")
                } else {
                    ToolResult.ok("已播报：$text")
                }
            } else {
                ToolResult.failed("播报失败（系统 TTS 引擎不可用或未初始化）")
            }
        } catch (e: Throwable) {
            ToolResult.Error("播报异常：${e.message}", ToolResult.INTERNAL)
        }
    }

    /**
     * 语气预设 → (rate, pitch)。
     *
     * ⚠️ 这是**近似映射**，不是 Edge TTS 的 prosody。
     * Node 版的 `mstts:express-as` 在免费端点上不受支持（会返回空音频），
     * 所以那边也是用 prosody 预设近似 —— 两边语义一致。
     */
    private fun mapStyle(style: String?, degree: Float): Pair<Float, Float> {
        if (style == null) return 1.0f to 1.0f
        val d = degree.coerceIn(0.01f, 2f)
        return when (style.lowercase()) {
            "cheerful" -> (1.0f + 0.12f * d) to (1.0f + 0.15f * d)
            "excited" -> (1.0f + 0.25f * d) to (1.0f + 0.25f * d)
            "gentle" -> (1.0f - 0.15f * d) to (1.0f - 0.05f * d)
            "calm" -> (1.0f - 0.12f * d) to (1.0f - 0.10f * d)
            "serious" -> (1.0f - 0.10f * d) to (1.0f - 0.15f * d)
            "sad" -> (1.0f - 0.20f * d) to (1.0f - 0.20f * d)
            "angry" -> (1.0f + 0.15f * d) to (1.0f + 0.05f * d)
            "affectionate" -> (1.0f - 0.10f * d) to (1.0f + 0.12f * d)
            "chat" -> (1.0f + 0.05f * d) to (1.0f + 0.05f * d)
            "narration" -> (1.0f - 0.08f * d) to (1.0f - 0.05f * d)
            else -> 1.0f to 1.0f
        }.let { (r, p) ->
            r.coerceIn(0.1f, 3.0f) to p.coerceIn(0.1f, 2.0f)
        }
    }
}
