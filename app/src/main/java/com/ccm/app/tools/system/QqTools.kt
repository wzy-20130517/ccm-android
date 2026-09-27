package com.ccm.app.tools.system

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.bool
import com.ccm.app.core.tool.ToolSchema.int
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * QQ 桥工具 —— QQPush（主动推送）/ QQRecall（回溯群消息）。
 *
 * 参照 Node 版 `core/qq-tools.mjs` + `core/qq-bridge.mjs`。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ QQPush 的两条硬约束（CCM 的设计，原样保留）
 * ══════════════════════════════════════════════════════════════
 *
 * **1. 只能发给主人号，不接受目标参数**
 * 这是防「被诱导用用户的号给别人发东西」—— 若模型能指定收件人，
 * 一段恶意 prompt 就能让它往外发消息。所以**接口上就没有目标参数**。
 *
 * **2. 用户明确要求时才用**
 * 「把这张图发我 QQ」「把报告发到我手机」「生成完发我」→ 用。
 * **不要用它做进度播报** —— 用户在终端/App 里就能看到正文。
 *
 * ══════════════════════════════════════════════════════════════
 *  QQRecall 的用途
 * ══════════════════════════════════════════════════════════════
 *
 * 群消息**不会自动进对话**（只有主人私聊/@ 才唤醒 AI），但会缓存在内存里。
 * 用户说「群里那个」「刚才发的」「有人发了」时用它回溯。
 *
 * @param pusher QQ 推送器（App 层注入；null 时 QQPush 报未接入）
 * @param recaller 群消息回溯器（App 层注入；null 时 QQRecall 报未接入）
 */
class QqTools(
    private val pusher: Pusher? = null,
    private val recaller: Recaller? = null,
) {

    /** QQ 推送能力（App 层实现） */
    fun interface Pusher {
        /**
         * 推送。
         *
         * @param text 文本（asImage=true 时渲染成图片再发）
         * @param asImage 是否强制转图片（表格/代码用）
         * @param path 文件路径（图片按图片发，其他类型按文件发）
         * @return 结果描述；失败抛异常或返回以「失败」开头的信息
         */
        suspend fun push(text: String?, asImage: Boolean, path: String?): String
    }

    /** 群消息回溯能力（App 层实现） */
    fun interface Recaller {
        /**
         * 回溯群消息。
         *
         * @param groupId 限定群（null = 所有群）
         * @param keyword 关键词过滤（匹配文本或发送者昵称）
         * @param limit 最多返回多少条
         * @return 渲染好的文本
         */
        suspend fun recall(groupId: String?, keyword: String?, limit: Int): String
    }

    // ══════════════════════════════════════════════════════════════
    //  QQPush
    // ══════════════════════════════════════════════════════════════

    inner class QQPushTool : Tool() {
        override val name = "QQPush"
        override val description =
            "把消息、图片或文件主动推送到用户的 QQ（**只能发给主人号**，不能发给别人、不能发群）。" +
                "用户明确要求时才用，比如「把这张图发我 QQ」「把报告发到我手机」「生成完发我」。" +
                "**不要用它做进度播报或主动搭话** —— 用户在终端就能看到你的正文。"
        override val isDestructive = true
        override val maxResultSizeChars = 1_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "text" to ToolSchema.string(
                "要发送的文字。单独给它 = 发一条文本消息；配合 path 给 = 作为文件的说明先发出去。超长会自动转成图片。",
            ),
            "as_image" to ToolSchema.boolean(
                "true = 把 text 强制渲染成图片再发（表格、代码、长文本用这个，QQ 里看得更清）。默认按长度自动判断。",
            ),
            "path" to ToolSchema.string(
                "要发送的本地文件绝对路径。图片（png/jpg/gif/webp/bmp）显示成图片气泡，其他类型作为文件发送。" +
                    "目录不支持，需先打包 zip。",
            ),
        )

        override fun validateInput(input: JsonObject): String? {
            if (input.str("text").isNullOrBlank() && input.str("path").isNullOrBlank()) {
                return "至少要给 text 或 path"
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val fn = pusher
                ?: return ToolResult.Error(
                    "QQPush 未接入（需要 App 层注入推送器；也请确认 /qq 已开启）。",
                    ToolResult.INTERNAL,
                )

            val text = input.str("text")
            val path = input.str("path")
            val asImage = input.bool("as_image") == true

            if (path != null) {
                val f = File(path)
                if (!f.exists()) return ToolResult.notFound("文件不存在：$path")
                if (f.isDirectory) {
                    return ToolResult.invalidInput("不支持发送目录（QQ 本身不支持文件夹）。请先打包成 zip。")
                }
            }

            return try {
                val r = fn.push(text, asImage, path)
                if (r.startsWith("失败") || r.startsWith("错误")) {
                    ToolResult.failed(r)
                } else {
                    ToolResult.ok(r)
                }
            } catch (e: Throwable) {
                ToolResult.Error("推送失败：${e.message}", ToolResult.NETWORK)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  QQRecall
    // ══════════════════════════════════════════════════════════════

    inner class QQRecallTool : Tool() {
        override val name = "QQRecall"
        override val description =
            "回溯最近的 QQ 群消息（群消息不会自动进对话，需要主动查）。" +
                "用户提到「群里那个/刚才发的/有人发了」但没给具体内容时用它。" +
                "可用 keyword 过滤（匹配消息文本或发送者昵称）、groupId 限定某个群、limit 控制条数。" +
                "返回发送者、时间、文本和图片 URL；要看图片内容再用 ViewImage（先下载到本地）。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 10_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "groupId" to ToolSchema.string("只看某个群（群号）；省略=所有群"),
            "keyword" to ToolSchema.string("按关键词过滤，匹配消息文本或发送者昵称"),
            "limit" to ToolSchema.integer("返回最近多少条，默认 20，上限 60", minimum = 1, maximum = 60),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
            val fn = recaller
                ?: return ToolResult.Error(
                    "QQRecall 未接入（需要 App 层注入群消息缓存；也请确认 /qq 已开启）。",
                    ToolResult.INTERNAL,
                )

            val groupId = input.str("groupId")?.takeIf { it.isNotBlank() }
            val keyword = input.str("keyword")?.takeIf { it.isNotBlank() }
            val limit = (input.int("limit") ?: 20).coerceIn(1, 60)

            return try {
                val r = fn.recall(groupId, keyword, limit)
                if (r.isBlank()) ToolResult.ok("（没有找到匹配的群消息）") else ToolResult.ok(r)
            } catch (e: Throwable) {
                ToolResult.Error("回溯失败：${e.message}", ToolResult.INTERNAL)
            }
        }
    }
}
