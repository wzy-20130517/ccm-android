package com.ccm.app.core.image

import java.io.File

/**
 * 文件附件的发送处理（2026-10-12 加，对齐 Web 的 `buildUserContent`）。
 *
 * ## 为什么需要
 *
 * 原来（首页 + 对话页）都是简单拼一行路径：
 * ```
 * [附件: main.mjs @ /path/to/main.mjs]
 * ```
 * 模型只拿到路径，**必须自己调 Read** 才能看到内容 —— 多一轮工具调用，
 * 而且模型经常忘记读（用户报「文件形式只是发路径」）。
 *
 * Web 的处理（`web/server.mjs:548-573`）分三类：
 *
 * | 类型 | 处理 |
 * |---|---|
 * | 文本/代码（按扩展名） | **直接读内容塞进消息**（`<文件内容>…</文件内容>`，≤2MB） |
 * | 图片 | 路径 + base64（APK 的图片走独立的多模态通道，这里不管） |
 * | 其他（二进制） | 路径 + 「请用 Read 读取」 |
 *
 * APK 之前只做了第三类 —— 本文件补上前两类。
 *
 * ## 为什么在客户端拼而不是服务端
 *
 * APK 的 `ChatSession.send` 直接构造 `Message`（没有 Web 那样的
 * 「server 端 buildUserContent」层）。所以处理放在 UI 侧、发送之前。
 */
object FileAttachment {

    /** 文本类文件的内容上限（对齐 Web 的 2MB）。 */
    private const val MAX_TEXT_BYTES = 2L * 1024 * 1024

    /** 按扩展名判断是否文本类（对齐 Web 的正则）。 */
    private val TEXT_EXT = setOf(
        "txt", "md", "csv", "json", "xml", "yaml", "yml",
        "js", "jsx", "ts", "tsx", "py", "java", "cpp", "c", "h", "cs",
        "go", "rs", "rb", "php", "swift", "kt", "kts", "scala",
        "html", "css", "scss", "less", "sql", "sh", "bash",
        "vue", "svelte", "lua", "r", "m", "pl", "ex", "exs",
        "toml", "ini", "conf", "log", "gitignore", "env",
    )

    /**
     * 把待发文件列表拼成消息文本。
     *
     * @return 追加到用户消息末尾的文本（空列表返回空串）
     */
    fun buildMessageText(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        val sb = StringBuilder()
        for (path in paths) {
            val f = File(path)
            val name = f.name
            sb.append("\n\n[附件: ").append(name).append("]\n已保存到: ").append(path)

            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext in TEXT_EXT) {
                // 文本类：直接读内容塞进去（模型不用再调 Read）
                val text = try {
                    if (f.length() <= MAX_TEXT_BYTES) f.readText() else null
                } catch (_: Throwable) {
                    null
                }
                if (text != null) {
                    sb.append("\n<文件内容>\n").append(text).append("\n</文件内容>")
                } else {
                    sb.append("\n（文件过大或读取失败，请用 Read 工具读取）")
                }
            } else {
                // 二进制/未知：给路径让模型自己读
                sb.append("\n请在需要时使用 Read 等工具读取该文件。")
            }
        }
        return sb.toString()
    }
}
