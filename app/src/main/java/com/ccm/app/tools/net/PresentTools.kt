package com.ccm.app.tools.net

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.str
import com.ccm.app.core.tool.ToolSchema.strList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Present 工具 —— 让 Agent 主动把可视内容「展示」出来。
 *
 * 参照 Node 版 `core/present-tool.mjs`（152 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ APK 端是「保存 + 返回路径」的降级实现（刻意如此）
 * ══════════════════════════════════════════════════════════════
 *
 * **为什么降级而不是不做**（main 2026-09-27 拍板）：
 * 工具存在 → 模型知道有这个东西、知道为什么这里用不了，不会瞎猜或绕路；
 * 工具不存在 → 模型以为没这能力，可能去调 Bash 写文件再猜路径。
 *
 * **为什么 APK 端没有渲染通道**：
 * 看 [com.ccm.app.core.tool.ToolUiCallback] —— 只有 onProgress / onContent 两个回调，
 * 没有 Present 事件。Node 版的 `onPresent(payload)` 是 Web 端推 SSE 用的
 * （前端收到后内联渲染 SVG/HTML）。APK 的 Compose 界面目前没有对应的渲染组件，
 * 所以这里**不假装能渲染**，改为把内容落盘 + 返回路径，让模型自己决定后续
 * （比如用 ViewImage 看渲染后的图，或告诉用户文件在哪）。
 *
 * **接线后想升级成真渲染**：给 ToolUiCallback 加一个 `onPresent` 方法，
 * 本工具里调它即可 —— schema / 描述 / 注册都不用动。
 *
 * ══════════════════════════════════════════════════════════════
 *  两种输入方式（对齐 Node 版）
 * ══════════════════════════════════════════════════════════════
 *
 * · `kind=svg/html/mermaid` → 用 `content` 传源码，本工具**写到文件**再返回路径
 * · `kind=image/images/video` → 用 `paths` 传已存在的本地文件，本工具**校验后原样返回**
 *
 * 后者不复制文件（视频动辄几十 MB，白拷一份没意义），只做存在性 + 后缀白名单校验。
 *
 * @param saveDir 落盘目录（通常是 storage.rootDir 下的 present/）
 */
class PresentTools(private val saveDir: File) {

    companion object {
        /** 支持的展示类型 */
        private val KINDS = listOf("svg", "html", "mermaid", "image", "images", "video")

        /**
         * 图片后缀白名单。
         *
         * **为什么要白名单**：不校验的话，模型传 `paths: ["/data/.../config.json"]`
         * 也会被当成「图片」展示 —— 前端拿它当 <img> 用，等于把一个任意文件
         * 塞进了渲染通道。Node 版同样有这个白名单。
         */
        private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "avif")

        /** 视频后缀白名单（同上） */
        private val VIDEO_EXT = setOf("mp4", "webm", "mov", "m4v")

        /** content 源码上限 200_000 字符（对齐 Node 版 MAX_CONTENT） */
        private const val MAX_CONTENT = 200_000

        /** 源码类展示的文件名后缀 */
        private fun extOf(kind: String): String = when (kind) {
            "svg" -> "svg"
            "html" -> "html"
            "mermaid" -> "mmd"
            else -> "txt"
        }
    }

    /** 落盘目录：present/ 下按类型分子目录，方便用户去文件管理器找 */
    private fun kindDir(kind: String): File =
        File(saveDir, "present/$kind").apply { if (!exists()) mkdirs() }

    inner class PresentTool : Tool() {
        override val name = "Present"
        override val description =
            "把可视内容主动展示出来（用户直接看到成品，而不是源码或文件路径）。" +
                "适用：写完 SVG/HTML 动画想给用户看效果；处理视频后抽几帧；生成图表/流程图。\n" +
                "kind=svg/html/mermaid 时用 content 传源码；kind=image/images/video 时用 paths 传本地文件路径。\n" +
                "⚠️ **本端（Android APK）没有内联渲染通道**：源码类会写到本地文件并返回路径，" +
                "图片/视频类校验路径后原样返回 —— 都不会在对话里渲染。" +
                "想看渲染结果请用 ViewImage 打开返回的路径；想让用户看请把路径告诉他。"
        override val isReadOnly = false
        override val isConcurrencySafe = false
        override val maxResultSizeChars = 1_500

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "kind" to ToolSchema.string(
                "展示类型：svg=SVG图形/动画, html=完整HTML, mermaid=流程图, image=单图, images=多图, video=视频",
                enum = KINDS,
            ),
            "title" to ToolSchema.string("可选标题"),
            "content" to ToolSchema.string("kind=svg/html/mermaid 时的源码内容"),
            "paths" to ToolSchema.stringArray("kind=image/images/video 时的本地文件路径"),
            "caption" to ToolSchema.string("可选说明文字"),
            required = listOf("kind"),
        )

        override fun validateInput(input: JsonObject): String? {
            val kind = input.str("kind")?.lowercase()?.trim()
            if (kind.isNullOrBlank()) return "kind is required"
            if (kind !in KINDS) return "kind 必须是: ${KINDS.joinToString(" / ")}"

            // 源码类必须有 content，路径类必须有 paths —— 提前拦，别等 execute 里才发现
            val needsContent = kind == "svg" || kind == "html" || kind == "mermaid"
            if (needsContent && input.str("content").isNullOrBlank()) {
                return "kind=$kind 需要 content（源码内容）"
            }
            if (!needsContent && input.strList("paths").isNullOrEmpty()) {
                return "kind=$kind 需要 paths（本地文件路径列表）"
            }
            return null
        }

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val kind = input.str("kind")!!.lowercase().trim()
                val title = input.str("title")?.takeIf { it.isNotBlank() }
                val caption = input.str("caption")?.takeIf { it.isNotBlank() }
                val needsContent = kind == "svg" || kind == "html" || kind == "mermaid"

                val header = buildString {
                    if (title != null) append("【$title】\n")
                    append("kind=$kind")
                    if (caption != null) append(" · $caption")
                }

                if (needsContent) {
                    val content = input.str("content") ?: ""
                    if (content.length > MAX_CONTENT) {
                        return@withContext ToolResult.invalidInput(
                            "content 太长（${content.length} 字符，上限 $MAX_CONTENT）—— " +
                                "超长内容请写到文件后用 kind=image/paths 或直接告诉用户路径。",
                        )
                    }

                    val f = File(kindDir(kind), "present-${System.currentTimeMillis()}.${extOf(kind)}")
                    try {
                        f.parentFile?.mkdirs()
                        f.writeText(content)
                    } catch (e: Throwable) {
                        return@withContext ToolResult.Error(
                            "写入展示文件失败：${e.message}",
                            ToolResult.INTERNAL,
                        )
                    }

                    // 诚实说明「没渲染」—— 不说的话模型会以为用户已经看到了，
                    // 后续对话里就可能出现「你看上面的图」这种对不上的话
                    return@withContext ToolResult.ok(
                        "$header\n已保存到：${f.absolutePath}（${content.length} 字符）\n" +
                            "⚠️ 本端无内联渲染通道，内容**没有**显示在对话里。" +
                            "要查看效果可用 ViewImage 打开该路径（svg 需先转 png），或把路径告诉用户。",
                    )
                }

                // ── 路径类：校验后原样返回，不复制文件 ──────────────
                val paths = input.strList("paths")!!
                val allowed = if (kind == "video") VIDEO_EXT else IMAGE_EXT
                val okFiles = mutableListOf<File>()
                val bad = mutableListOf<String>()

                for (p in paths) {
                    val f = File(p).let { if (it.isAbsolute) it else File(ctx.cwd, p) }
                    when {
                        !f.exists() -> bad += "$p（文件不存在）"
                        !f.isFile -> bad += "$p（不是文件）"
                        f.extension.lowercase() !in allowed ->
                            bad += "$p（后缀 .${f.extension} 不在白名单：${allowed.joinToString("/")}）"
                        else -> okFiles += f
                    }
                }

                if (okFiles.isEmpty()) {
                    return@withContext ToolResult.failed(
                        "$header\n没有可展示的文件：\n" + bad.joinToString("\n") { "  · $it" },
                    )
                }

                val sizeNote = okFiles.joinToString("\n") { "  · ${it.absolutePath}（${fmtSize(it.length())}）" }
                val badNote = if (bad.isEmpty()) "" else "\n以下被跳过：\n" + bad.joinToString("\n") { "  · $it" }

                ToolResult.ok(
                    "$header\n共 ${okFiles.size} 个${if (kind == "video") "视频" else "图片"}：\n" +
                        sizeNote + badNote + "\n" +
                        "⚠️ 本端无内联渲染通道，文件**没有**显示在对话里。" +
                        "要查看可用 ViewImage（图片）或把路径告诉用户。",
                )
            }

        private fun fmtSize(n: Long): String = when {
            n >= 1024 * 1024 -> "${n / 1024 / 1024}MB"
            n >= 1024 -> "${n / 1024}KB"
            else -> "${n}B"
        }
    }
}
