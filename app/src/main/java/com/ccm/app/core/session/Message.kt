package com.ccm.app.core.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 对话消息模型 —— 会话历史的最小单元。
 *
 * dev-core 提供，dev-ui 消费（渲染历史、算 token 用量）。
 * 签名由 `/sdcard/Download/claude-workspace/rewrite/CONTRACTS.md` 第五节定义。
 *
 * ## 序列化设计
 * 用 kotlinx.serialization 的**多态**（sealed class + `@SerialName`）而不是
 * 「一个大 data class 带一堆可空字段」。理由：ContentBlock 的四种形态
 * 互斥且字段完全不同，可空字段版本会让「text 和 toolUse 同时有值」这种
 * 非法状态变得可表达，每个消费点都得自己判空。
 *
 * ⚠️ 落盘的 JSON 格式**必须与 Node 版兼容**（用户可能把 Node 会话导入 APK）。
 * Node 版的 content 是数组，元素形如 `{type:'text', text:'...'}`，
 * 所以 `@SerialName` 用下划线风格的 `type` 判别字段 —— 见 [ContentBlock] 的
 * `classDiscriminator` 配置说明。
 *
 * @property role 角色：`"user"` / `"assistant"` / `"system"` / `"tool"`
 * @property content 内容块列表。纯文本消息是单个 [ContentBlock.Text]。
 * @property timestamp 毫秒时间戳。0 = 未设置（旧数据）。
 */
@Serializable
data class Message(
    val role: String,
    val content: List<ContentBlock> = emptyList(),
    val timestamp: Long = 0,
    /**
     * 内部消息（不显示给用户）—— 2026-10-06 加，对齐 CLI 的 `hidden: true`。
     *
     * 用于：空响应提示、轮次上限提醒、watch 继续提示、队友消息注入等
     * **系统自己塞进历史的**消息。不带这个标记的话：
     *   · 用户会看到「（系统提示）你上一条回复是空的…」冒充自己发的
     *   · 这些噪音被存进会话文件、回放时再现
     *   · watch 模式每轮一条「继续执行」刷屏
     */
    val hidden: Boolean = false,
    /**
     * 本轮 assistant 的思考文本（/effort replay on 时回传给模型）。
     *
     * 【为什么存这里】CLI 的 attachReasoning 在**发送时**把 reasoning
     * 附到消息上（api.mjs:603）—— APK 的历史是 Message 结构，
     * 所以在**写入历史时**存下来（appendAssistantText/appendToolResults），
     * 发送时按 replayReasoning 开关决定带不带（buildApiMessages）。
     *
     * 不回传时（默认 off）这个字段只用于 UI 展示，不占请求带宽。
     */
    val reasoning: String? = null,
) {

    /** 便捷构造：纯文本用户消息。 */
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
        const val ROLE_TOOL = "tool"

        fun user(
            text: String,
            timestamp: Long = System.currentTimeMillis(),
            /** true = 系统内部消息（不显示给用户，见 [Message.hidden]）。 */
            hidden: Boolean = false,
        ): Message =
            Message(ROLE_USER, listOf(ContentBlock.Text(text)), timestamp, hidden)

        fun assistant(text: String, timestamp: Long = System.currentTimeMillis()): Message =
            Message(ROLE_ASSISTANT, listOf(ContentBlock.Text(text)), timestamp)

        fun system(text: String, timestamp: Long = System.currentTimeMillis()): Message =
            Message(ROLE_SYSTEM, listOf(ContentBlock.Text(text)), timestamp)
    }

    // ───────────────────── 便捷读取 ─────────────────────

    /** 拼接所有文本块（UI 显示正文用）。 */
    val text: String
        get() = content.filterIsInstance<ContentBlock.Text>().joinToString("") { it.text }

    /** 是否包含工具调用。 */
    val hasToolUse: Boolean
        get() = content.any { it is ContentBlock.ToolUse }

    /** 取出所有工具调用块。 */
    val toolUses: List<ContentBlock.ToolUse>
        get() = content.filterIsInstance<ContentBlock.ToolUse>()

    /** 取出所有工具结果块。 */
    val toolResults: List<ContentBlock.ToolResult>
        get() = content.filterIsInstance<ContentBlock.ToolResult>()

    /** 是否为空消息（无任何内容块，或全是空文本）。 */
    val isEmpty: Boolean
        get() = content.isEmpty() || content.all { it is ContentBlock.Text && it.text.isEmpty() }
}

/**
 * 元信息标签剥离 —— 判断「这条消息剥掉拼给模型的元信息后还剩什么」。
 *
 * 【2026-10-09 加，修「重启 APP 后老有空白用户气泡」】
 *
 * AgentLoop 会在给模型喂图时注入 `<image_resize_notice>`（缩放提示）、
 * `<vision_unsupported>`（端点不支持图）这类**拼给模型看的标签**，
 * 它们混在 user 消息的文本里。这类消息如果被当作用户发言恢复成气泡，
 * UI 又会把标签剥掉 → 只剩空字符串 → 一个纯空白气泡。
 *
 * 本函数是**剥标签的唯一实现**：loadHistory 用它判断该不该生成气泡，
 * MessageBubble 用它渲染正文 —— 两处共用，不会漂移。
 *
 * 注意：只剥「整条文本仅由元信息构成」的情况不成立 —— 这里剥的是标签本身，
 * 标签前后可能还有真实文本（如工具注入的 `[附件: xxx]`），剥完保留。
 */
fun stripMetaTags(text: String): String {
    if (text.isEmpty()) return text
    var t = text
    for (re in META_TAG_REGEXES) {
        t = t.replace(re, "")
    }
    return t.trim()
}

/**
 * 拼给模型看的元信息标签（开始/结束标签成对）。
 *
 * 预编译 Regex：stripMetaTags 在 Compose 渲染路径上被每个气泡每帧调用，
 * 每次现场构造 Regex 对象是纯浪费。
 */
private val META_TAG_REGEXES = listOf(
    Regex("<image_resize_notice>[\\s\\S]*?</image_resize_notice>"),
    Regex("<vision_unsupported>[\\s\\S]*?</vision_unsupported>"),
)

/**
 * 消息内容块。
 *
 * 四种形态对应 Node 版 message.content 数组里的元素类型。
 * 用 `@SerialName` 显式命名，保证落盘 JSON 的字段名与 Node 版一致。
 */
@Serializable
sealed class ContentBlock {

    /** 纯文本。 */
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock()

    /**
     * 图片（多模态）。
     *
     * [base64] 是**不含 data URL 前缀**的裸 base64（前缀在构造请求时拼）。
     * 对齐 Node 版 `core/image.mjs` 的 data URL 方案。
     */
    @Serializable
    @SerialName("image")
    data class Image(
        val base64: String,
        val mimeType: String = "image/png",
    ) : ContentBlock()

    /**
     * 工具调用请求（模型发起）。
     *
     * @property id 工具调用 id，与 [ToolResult.id] 配对
     * @property name 工具名
     * @property input 原始参数（JSON）
     */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
    ) : ContentBlock()

    /**
     * 工具执行结果（回填给模型）。
     *
     * @property id 对应的 [ToolUse.id]
     * @property content 结果文本（可能已被截断）
     * @property isError 是否算失败
     */
    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val id: String,
        val content: String,
        val isError: Boolean = false,
    ) : ContentBlock()
}
