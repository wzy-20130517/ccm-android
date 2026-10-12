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

    /**
     * 探测文件是不是文本（读前 8KB，看有没有 NUL 字节 + 能否 UTF-8 解码）。
     *
     * 【为什么不用扩展名白名单】现实里扩展名无穷无尽（.zjs / .tmpl /
     * 无扩展名…），白名单永远补不全 —— 补不全的后果是**用户的文本文件
     * 读不到内容**。按内容判断才是完备的。
     *
     * 判据（宽松，宁可当文本）：
     *   · 不含 NUL 字节（二进制文件几乎都有）
     *   · 前 8KB 能按 UTF-8 解码（用 REPLACE 模式，允许少量坏字节）
     */
    private fun looksLikeText(f: File): Boolean {
        val probe = java.io.FileInputStream(f).use { input ->
            val buf = ByteArray(8192)
            val n = input.read(buf)
            if (n <= 0) return true          // 空文件当文本
            buf.copyOf(n)
        }
        if (probe.any { it == 0.toByte() }) return false   // NUL → 二进制
        // UTF-8 解码（REPLACE 模式不抛异常；坏字节比例过高说明不是文本）
        val decoded = String(probe, Charsets.UTF_8)
        val bad = decoded.count { it == '\uFFFD' }
        return bad * 20 < decoded.length     // 坏字符 < 5% 就当文本
    }



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

            // 【2026-10-12 改成内容探测，不再靠扩展名白名单】
            //
            // 原来按扩展名判断（白名单 40 多个）—— 后果是**任何不在名单里的
            // 文本文件都读不到内容**（用户报「发个 .zjs 过去它压根看不见」）。
            // 现实里扩展名无穷无尽（.zjs / .foo / 无扩展名 / .tmpl …），
            // 白名单永远补不全。
            //
            // 现在按**内容**判断：读前 8KB 探测是不是合法 UTF-8 文本
            //（没有 NUL 字节、可解码）。是文本就读全文塞进消息。
            val isText = try {
                if (f.length() > MAX_TEXT_BYTES) false
                else looksLikeText(f)
            } catch (_: Throwable) {
                false
            }
            if (isText) {
                val text = try {
                    f.readText()
                } catch (_: Throwable) {
                    null
                }
                if (text != null) {
                    sb.append("\n<文件内容>\n").append(text).append("\n</文件内容>")
                }
                // 读不出来（权限等）就不追加 —— 只留上面的路径。
                // 不写「请用 Read」：那是下指令。
            }
            // 【2026-10-12 删掉「请用 Read 读取」这类指令】
            //
            // 原来二进制/未知类型会拼一句「请在需要时使用 Read 等工具读取该文件。」
            // —— 那是**给模型下指令**，不是告诉它事实。
            //
            // 问题：① 附件已经在消息里了，模型需要时会自己判断用什么工具，
            //         不需要在用户消息里教它；
            //       ② 图片也会走到这个分支（.jpg 不在文本扩展名里）→
            //         提示「用 Read 读图片」，这是**错的**（Read 读不了二进制）；
            //       ③ 这段文字还会漏进用户气泡（除非剥离规则覆盖）。
            //
            // 现在只保留事实：「[附件: 名] 已保存到: 路径」。路径本身就是
            // 模型需要的信息，怎么用由它决定。
        }
        return sb.toString()
    }
}
