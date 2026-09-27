package com.ccm.app.core.compact

import com.ccm.app.core.session.ContentBlock
import com.ccm.app.core.session.Message

/**
 * 上下文压缩。
 *
 * 对应 Node 版 `core/compact.mjs`（447 行）+ `core/auto-compact.mjs`（197 行）。
 *
 * ## 两种压缩，先试便宜的那个
 *
 * | | 微压缩（[microCompact]） | 摘要（[summarize]） |
 * |---|---|---|
 * | API 调用 | **零** | 一次 |
 * | 动什么 | 只截断**可再生的工具输出** | 整段对话压成摘要 |
 * | 信息损失 | 无（重跑工具就能拿回） | 有（推理过程被压成几句话） |
 * | 能多频繁 | 随便跑 | 谨慎 |
 *
 * **推荐顺序**：先 [microCompact]，压力降下来就收手；仍吃紧才 [summarize]。
 * 这正是 Node 版 `/compact` 不带参数的默认行为。
 *
 * ## 为什么微压缩是安全的（关键前提）
 * 只改 `tool_result` 的 **content**，`tool_use_id` 保持原样 ——
 * 所以 assistant 的 `tool_use` 与它的配对关系不断链。
 * （Anthropic/Bedrock 对 tool_use ↔ tool_result 配对校验很严格，断链会直接 400。）
 */
class Compactor(
    private val policy: Policy = Policy(),
) {

    /**
     * 压缩策略。
     *
     * @property enabled 是否允许**自动**摘要。默认 false（用户被自动压缩搞丢过记忆，很反感）
     * @property threshold 工具输出超过这么多字符才考虑截断
     * @property keepHead 截断时保留开头字符数（通常含结构信息：文件头、命令、匹配摘要）
     * @property keepTail 截断时保留结尾字符数（通常含结论：错误信息、退出码、末尾行）
     * @property protectLast 最新 N 条消息不动（刚拿到的工具结果往往正在用）
     */
    data class Policy(
        val enabled: Boolean = false,
        val threshold: Int = 2000,
        val keepHead: Int = 600,
        val keepTail: Int = 300,
        val protectLast: Int = 6,
    )

    /**
     * 微压缩结果。
     *
     * @property messages 处理后的消息列表（原列表不被修改）
     * @property changed 是否有实际改动
     * @property reclaimedChars 回收的字符数
     * @property details 每个被截断的项（工具名 + 回收字符数），用于给用户展示
     */
    data class MicroResult(
        val messages: List<Message>,
        val changed: Boolean,
        val reclaimedChars: Int,
        val details: List<Detail>,
    ) {
        data class Detail(val toolName: String, val reclaimedChars: Int)

        /** 估算回收的 token 数（粗估：4 字符 ≈ 1 token）。 */
        val reclaimedTokens: Int get() = reclaimedChars / 4
    }

    /**
     * 微压缩：只截断**可再生的工具输出**，不动对话本体，**零 API 调用**。
     *
     * @param messages 完整消息列表（不会被原地修改）
     * @return 处理结果；[MicroResult.changed] 为 false 时调用方应原样用旧列表
     */
    fun microCompact(messages: List<Message>): MicroResult {
        // 第一步：扫一遍 assistant 的 tool_use，建 tool_use_id → 工具名 映射。
        // tool_result 自己**不带工具名**，必须靠这个反查才知道能不能截。
        val toolNameById = HashMap<String, String>()
        for (m in messages) {
            if (m.role != Message.ROLE_ASSISTANT) continue
            for (b in m.content) {
                if (b is ContentBlock.ToolUse) {
                    toolNameById[b.id] = b.name
                }
            }
        }

        val protectFrom = (messages.size - policy.protectLast).coerceAtLeast(0)
        val details = mutableListOf<MicroResult.Detail>()
        var reclaimed = 0
        var changed = false

        val out = messages.mapIndexed { idx, msg ->
            // 尾部保护区：最新几条不动（可能正在用）
            if (idx >= protectFrom) return@mapIndexed msg

            var touched = false
            val newContent = msg.content.map { block ->
                if (block !is ContentBlock.ToolResult) return@map block

                val toolName = toolNameById[block.id] ?: return@map block

                // 不可再生的工具：绝不截断
                if (toolName in NEVER_COMPACT_TOOLS) return@map block

                // 只截「可再生产」的工具（重跑一次就能拿回同样内容）
                if (toolName !in REGENERABLE_TOOLS) return@map block

                val shrunk = shrink(block.content, toolName) ?: return@map block

                touched = true
                reclaimed += block.content.length - shrunk.length
                details += MicroResult.Detail(toolName, block.content.length - shrunk.length)
                block.copy(content = shrunk)
            }

            if (touched) {
                changed = true
                msg.copy(content = newContent)
            } else {
                msg
            }
        }

        return MicroResult(out, changed, reclaimed, details)
    }

    /**
     * 截断单条工具输出。
     *
     * 保头 + 保尾，中间换成标记 —— 这个形状不是随便定的：
     * - **头**通常含结构信息（文件前几行、命令本身、grep 的匹配摘要）
     * - **尾**通常含结论（错误信息、退出码、文件的末尾）
     * - 中间往往是重复的正文，删了损失最小
     *
     * @return 截断后的文本；不需要截断返回 null
     */
    private fun shrink(text: String, toolName: String): String? {
        if (text.length <= policy.threshold) return null
        val cut = text.length - policy.keepHead - policy.keepTail
        if (cut <= 0) return null

        val head = text.take(policy.keepHead)
        val tail = text.takeLast(policy.keepTail)
        return "$head\n\n$MARK $cut 字符：$toolName 输出可重跑获取]\n\n$tail"
    }

    /**
     * 估算消息列表的 token 数（粗估：4 字符 ≈ 1 token）。
     *
     * ⚠️ **只是估算**，用于判断「要不要压缩」。真实用量以 API 返回的
     * `prompt_tokens` 为准（[com.ccm.app.core.api.ApiTypes.TokenUsage]）。
     */
    fun estimateTokens(messages: List<Message>): Int {
        var chars = 0
        for (m in messages) {
            for (b in m.content) {
                chars += when (b) {
                    is ContentBlock.Text -> b.text.length
                    is ContentBlock.ToolResult -> b.content.length
                    is ContentBlock.ToolUse -> b.input.toString().length + b.name.length
                    // 图片按固定值估（实际 token 取决于分辨率，这里只做量级判断）
                    is ContentBlock.Image -> IMAGE_TOKEN_ESTIMATE * 4
                }
            }
        }
        return chars / 4
    }

    /**
     * 判断是否该压缩。
     *
     * @param currentTokens 当前上下文 token 数（用 API 返回的真实值）
     * @param maxContextTokens 模型上下文窗口
     * @param triggerRatio 触发比例（默认 0.8 = 用到 80% 就该动手）
     */
    fun shouldCompact(
        currentTokens: Int,
        maxContextTokens: Int,
        triggerRatio: Double = 0.8,
    ): Boolean {
        if (maxContextTokens <= 0) return false
        return currentTokens.toDouble() / maxContextTokens >= triggerRatio
    }

    /**
     * 构造摘要提示词。
     *
     * ⚠️ **必须明确禁止调工具** —— 摘要请求如果被模型当成普通对话，
     * 它会去调 Read/Bash 而把摘要预算烧光，返回一个空摘要。
     * Node 版为此专门写了 `NO_TOOLS_PREAMBLE`。
     */
    fun buildSummaryPrompt(): String = SUMMARY_PREAMBLE

    /**
     * 把消息列表压成纯文本（送去摘要的原料）。
     *
     * 每类块都有固定表示法，**关键信息保头**：
     * - `tool_use` → `[调用工具: Name({...})]`，参数截前 200 字符
     * - `tool_result` → `[工具结果(错误): ...]`，内容截前 240 字符
     *
     * 为什么要截：摘要请求本身有输入上限（[SUMMARY_INPUT_CHAR_LIMIT]），
     * 不截的话一条大工具输出就能把预算吃光。
     */
    fun buildSummaryInput(messages: List<Message>): String {
        val sb = StringBuilder()
        for (m in messages) {
            val role = if (m.role == Message.ROLE_USER) "用户" else "助手"
            sb.append(role).append(": ")
            for (b in m.content) {
                when (b) {
                    is ContentBlock.Text -> sb.append(b.text).append(' ')
                    is ContentBlock.ToolUse -> sb.append("[调用工具: ${b.name}(")
                        .append(b.input.toString().take(200)).append(")] ")
                    is ContentBlock.ToolResult -> sb.append("[工具结果")
                        .append(if (b.isError) "(错误)" else "")
                        .append(": ").append(b.content.take(240)).append("] ")
                    is ContentBlock.Image -> sb.append("[图片] ")
                }
            }
            sb.append("\n\n")
        }
        return sb.toString().take(SUMMARY_INPUT_CHAR_LIMIT)
    }

    /**
     * 摘要请求的**系统提示词**。
     *
     * 核心约束是**不许编造**：「完成事项不得写成待办；没有原文证据不得制造未完成项」——
     * 这是 Node 版实测教训（摘要把已完成的说成待办，接手者会去重做）。
     */
    fun summarySystemPrompt(): String = SUMMARY_SYSTEM_PROMPT

    /**
     * 从模型输出里剥离 `<analysis>` 草稿，只留 `<summary>` 正文。
     *
     * 提示词要求模型先写 `<analysis>` 再写 `<summary>`，草稿**不入上下文**
     * （省 token，也避免模型看到自己的分析被绕进去）。
     *
     * 解析失败时返回原文（宁可多留也不要丢内容）。
     */
    fun extractSummary(raw: String): String {
        val start = raw.indexOf("<summary>")
        val end = raw.lastIndexOf("</summary>")
        val body = if (start >= 0 && end > start) {
            raw.substring(start + "<summary>".length, end).trim()
        } else {
            // 没按格式输出 —— 退而求其次：去掉 analysis 块
            raw.replace(ANALYSIS_RE, "").trim()
        }
        return body.take(SUMMARY_CHAR_LIMIT)
    }

    companion object {
        /** 截断标记（对齐 Node 版 `MICRO_MARK`）。 */
        const val MARK = "⋯[已回收"

        /** 单张图片的 token 估算值。 */
        private const val IMAGE_TOKEN_ESTIMATE = 1500

        /**
         * 输出**可再生产**的工具 —— 重跑一次就能拿回同样内容，截断无信息损失。
         *
         * ⚠️ 加新工具时想清楚：**它重跑一次结果会变吗？**
         * 会变（如 `ImageGen` 同 prompt 出图不同）就不能加进来。
         */
        val REGENERABLE_TOOLS: Set<String> = setOf(
            "Read", "Glob", "Grep", "CodeSearch", "HashlineRead", "HashlineGrep",
            "Symbols", "RepoMap", "Diagnostics", "LSP",
            "GitStatus", "GitDiff", "GitLog",
            "Bash", "BashOutput", "Test",
            "WebFetch", "WebSearch",
        )

        /**
         * **绝不截断**的工具。
         *
         * 两类：
         * 1. **不可复现** —— 用户的回答（重问一次答案可能不同）、
         *    一次性副作用、生成结果（同 prompt 出图不同）
         * 2. **状态本身就是要记住的** —— TodoWrite / Memory
         *
         * 多模态（ViewImage/Screencap 等）也在这里：它们的产物走旁路注入，
         * 截断会破坏配对关系。
         */
        val NEVER_COMPACT_TOOLS: Set<String> = setOf(
            "AskUserQuestion",
            "TodoWrite",
            "Memory",
            "ImageGen",
            "ViewImage", "ViewVideo", "Screencap",
        )

        /**
         * 摘要提示词前言。
         *
         * **必须明确禁止调工具** —— 否则模型会去调 Read/Bash 把预算烧光，
         * 返回空摘要（Node 版真实踩过）。
         */
        val SUMMARY_PREAMBLE: String = """
            CRITICAL: Respond with TEXT ONLY. Do NOT call any tools.

            - Do NOT use Read, Bash, Grep, Glob, Edit, Write, or ANY other tool.
            - You already have all the context you need in the conversation above.
            - Tool calls will be REJECTED and will waste your only turn.
            - Your entire response must be plain text: an <analysis> block followed by a <summary> block.

        """.trimIndent() + "\n\n"

        /** 单个摘要正文上限（字符）。 */
        const val SUMMARY_CHAR_LIMIT = 16000

        /** 摘要请求的输出 token 预算（含思考 token）。 */
        const val SUMMARY_MAX_TOKENS = 65536

        /** 送去摘要的文本墙上限（字符）。 */
        const val SUMMARY_INPUT_CHAR_LIMIT = 60000

        /**
         * 摘要请求的系统提示词。
         *
         * **核心是不许编造状态**：「完成事项不得写成待办；没有原文证据不得制造
         * 未完成项」—— Node 版实测教训：摘要把已完成的说成待办，接手者会去重做，
         * 白烧一整轮。
         */
        const val SUMMARY_SYSTEM_PROMPT: String =
            "你是上下文摘要助手。只总结给定的历史对话块，先判断每项任务在历史末尾的最终状态，" +
                "再输出事实记录。完成事项不得写成待办；没有原文证据不得制造未完成项或下一步。" +
                "不得编造完成状态。"

        /** 剥离 `<analysis>` 草稿块（模型没按格式输出时的兜底）。 */
        private val ANALYSIS_RE = Regex("<analysis>[\\s\\S]*?</analysis>", RegexOption.IGNORE_CASE)
    }
}
