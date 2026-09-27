package com.ccm.app.core.tool

/**
 * 工具执行结果。
 *
 * 参照 Node 版 `Tool.run()` 的返回：它把结果统一成「文本 + 是否错误」两个维度，
 * 外加一个错误分类（用于决定要不要重试、以及给用户看什么样的提示）。
 *
 * ## 为什么 Success 也带 [Success.isError]
 * 因为「执行成功」和「业务失败」是两件事。典型场景：
 * - `Bash` 跑 `grep` 没匹配到 → 命令正常退出但 exitCode=1，对模型来说算**失败信息**
 * - `Read` 读一个不存在的文件 → 工具没崩，但结果应该被标成错误，让模型知道要换个路径
 *
 * 所以 [Success] 表示「工具跑完了」，[Success.isError] 表示「跑完的结果算不算失败」。
 * 真正的异常路径（工具崩了、参数没通过校验、被用户中断）才走 [Error]。
 *
 * ## 为什么不直接抛异常
 * 抛出的异常在 Agent 层会被捕获，但**错误分类会退化成 `unknown`** ——
 * 而 `unknown` 意味着「不重试」，一次网络抖动就变成硬失败。
 * 明确返回 [Error] 并给出正确的 [Error.category]，才能让重试策略正常工作。
 *
 * ## 多模态附件（[Attachment]）—— 为什么不能塞进文本
 * 图片类工具（ViewImage / ViewVideo / Screencap / phone_screenshot / ImageGen /
 * FindImage）产出的**必须以图片形式进模型上下文**，不能塞进 tool_result 文本里
 * （文本里只能给个路径，模型看不见画面）。
 *
 * Node 版的机制是：工具返回 `{__type:'vision', text, images}` → Agent 循环
 * **先推 tool_result（只存文本摘要），再追加一条多模态 user 消息**（图片走
 * `image_url` / `image` block）。Kotlin 版对应 [Success.attachments]。
 *
 * ⚠️ **两条必须遵守的纪律**（Node 版踩过坑）：
 * 1. **模型看不到图时必须如实告知**。若端点已知不支持图片（收到过
 *    `image not supported` 类 400），不能只把图丢掉就完事 —— 工具文本还写着
 *    「手机屏幕截图（关注：xxx）」，模型会以为图到了，**凭上下文编造画面内容**。
 *    这是最糟的错，因为从输出上看不出来。要追加 `<vision_unsupported>` 提示。
 * 2. **图片被缩放时要透明**。缩过就追加 `<image_resize_notice>` 说明
 *    原尺寸 → 新尺寸，否则模型可能把「细节看不清」误判成「图里本来就没有」。
 */
sealed class ToolResult {

    /**
     * 执行完成（含「跑完但业务失败」）。
     *
     * @param content 给模型看的文本。会被截断（超 [Tool.maxResultSizeChars]）并可能写盘。
     * @param isError 业务层是否算失败。true 时 UI 标红，且模型会看到失败提示。
     * @param attachments 多模态附件（图片等）。**默认空 = 纯文本工具零改动**。
     *   非空时 Agent 循环会在 tool_result 之后**追加一条多模态 user 消息**，
     *   让模型直接看到图片 —— 见下方说明。
     */
    data class Success(
        val content: String,
        val isError: Boolean = false,
        val attachments: List<Attachment> = emptyList(),
    ) : ToolResult()

    /**
     * 执行失败（参数错、权限拒、工具崩溃、被中断）。
     *
     * @param message 错误信息。**要写给模型看** —— 说清「哪里错了、该怎么改」，
     *   模型能据此自我修正；只说 "failed" 等于让它瞎猜。
     * @param category 错误分类，见下方常量。决定 Agent 层要不要重试。
     */
    data class Error(val message: String, val category: String = UNKNOWN) : ToolResult()

    /** 便捷读取：结果文本（[Error] 取 message）。 */
    val textOrMessage: String
        get() = when (this) {
            is Success -> content
            is Error -> message
        }

    /** 便捷读取：是否算失败。 */
    val failed: Boolean
        get() = when (this) {
            is Success -> isError
            is Error -> true
        }

    companion object {
        // ───────── 错误分类常量（对齐 Node 版 `_classifyError` 的 name 字段） ─────────
        //
        // 命名与 Node 版保持一致，方便对照排查。**不要改这些字符串** ——
        // 它们会写进 trace 日志，改了会让历史日志和新日志对不上。

        /** 未知错误。不重试（安全默认）。 */
        const val UNKNOWN = "unknown"

        /** 参数不合法（模型传错了）。不重试 —— 重试同样的参数必然再失败。 */
        const val INVALID_INPUT = "invalid_input"

        /** 权限被拒。不重试，要用户改权限设置。 */
        const val PERMISSION_DENIED = "permission_denied"

        /** 用户主动中断。不重试（这是用户的明确意图）。 */
        const val USER_ABORT = "user_abort"

        /** 网络错误。可重试。 */
        const val NETWORK = "network"

        /** 文件不存在。不重试。 */
        const val NOT_FOUND = "not_found"

        /** 超时。可重试（但注意别跨层叠加，见 Node 版教训）。 */
        const val TIMEOUT = "timeout"

        /** 工具内部崩溃（未预期异常）。不重试。 */
        const val INTERNAL = "internal"

        // ───────── 便捷构造 ─────────

        /** 成功。 */
        fun ok(content: String): ToolResult = Success(content)

        /** 跑完了但算失败（如 exitCode != 0）。 */
        fun failed(content: String): ToolResult = Success(content, isError = true)

        /** 参数错误。 */
        fun invalidInput(message: String): ToolResult = Error(message, INVALID_INPUT)

        /** 权限被拒。 */
        fun denied(message: String = "权限被拒绝"): ToolResult = Error(message, PERMISSION_DENIED)

        /** 被用户中断。 */
        fun cancelled(message: String = "工具执行被中断"): ToolResult = Error(message, USER_ABORT)

        /** 文件/资源不存在。 */
        fun notFound(message: String): ToolResult = Error(message, NOT_FOUND)

        // ───────── 带附件的便捷构造 ─────────

        /** 成功 + 图片附件（图片类工具的主力构造）。 */
        fun okWithImages(
            content: String,
            images: List<Attachment>,
            isError: Boolean = false,
        ): ToolResult = Success(content, isError, images)
    }
}

/**
 * 工具产出的附件 —— 会被 Agent 循环转成多模态消息块。
 *
 * 由 dev-tools 构造（图片类工具），dev-core 的 Agent 循环消费。
 *
 * ## 三种形态怎么选
 * | 形态 | 用在哪 | 代价 |
 * |---|---|---|
 * | [ImageFile] | 图片已在磁盘上（Screencap / phone_screenshot / FindImage） | 最小 —— 注入时才读盘 |
 * | [ImageBytes] | 图片在内存里，没落盘（ImageGen 的返回、裁剪/缩放后的结果） | 内存占用，但省一次写盘 |
 * | [FileLink] | 非图片文件（视频/音频/文档），**不进多模态**，只在文本里给链接 | 无 |
 *
 * ## 为什么 ImageFile 优先
 * 图片可能很大（截图 1080x2400）。传路径让 Agent 在**真正注入时**才读取 + 缩放，
 * 避免「工具执行完就把几十 MB 图片常驻内存」——手机上这是会 OOM 的。
 *
 * ## ⚠️ 缩放信息（`resizedFrom`）
 * 若 Agent 注入时缩放了图片，要在提示里告诉模型原尺寸。
 * Node 版的 `<image_resize_notice>` 就是干这个的 —— 见 [ToolResult] 的类注释。
 */
sealed class Attachment {

    /**
     * 磁盘上的图片文件。**最常用**。
     *
     * @property path 绝对路径
     * @property mimeType 图片格式，默认 png
     */
    data class ImageFile(
        val path: String,
        val mimeType: String = "image/png",
    ) : Attachment()

    /**
     * 内存中的图片字节。用于还没落盘的图片（如生成结果、裁剪后的小图）。
     *
     * ⚠️ `ByteArray` 的 `equals` 是引用比较，data class 的自动相等性对它无效 ——
     * 不要拿 [ImageBytes] 做去重或集合成员判断。
     */
    class ImageBytes(
        val bytes: ByteArray,
        val mimeType: String = "image/png",
    ) : Attachment() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ImageBytes) return false
            return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }

    /**
     * 非图片文件（视频 / 音频 / 文档 / 压缩包）。
     *
     * **不进多模态**，只用于在结果文本里给一个可点击的路径。
     * 典型：ViewVideo 抽帧后，原视频用这个给出链接。
     */
    data class FileLink(
        val path: String,
        val name: String,
    ) : Attachment()
}
