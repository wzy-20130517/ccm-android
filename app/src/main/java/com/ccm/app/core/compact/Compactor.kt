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
    fun buildSummaryPrompt(text: String = ""): String =
        SUMMARY_PREAMBLE + SUMMARY_INSTRUCTION + if (text.isBlank()) "" else "\n\n$text"

    /**
     * 摘要式压缩 —— **把稳定前缀压成一段 [历史摘要]，保留新鲜尾部**。
     *
     * 对照 CLI `core/session/compact.mjs` 的 `compact()`：
     *   toSummarize = messages.dropLast(keepLast)
     *   toKeep      = messages.takeLast(keepLast)
     *   结果        = [摘要段] + toKeep
     *
     * ⚠️ 与 [microCompact] 的区别（两者互补，不是替代）：
     *   · microCompact：零 API 调用，只截断可再生工具输出，对话本体不动
     *   · 本方法：花一次 API 调用，把旧对话整体浓缩成摘要 —— 信息损失更大，
     *     但压缩比高得多（长会话必撞 400 时只有它能救）
     *
     * 本方法**只负责切分与组装**（纯本地），API 调用由调用方做 ——
     * 这样 Compactor 不依赖 ApiClient（保持可单测），调用方也能决定
     * effort/重试策略。
     *
     * @param keepLast 保留尾部条数（CLI 默认 10）
     * @return null = 无可压缩内容（消息数 ≤ keepLast）
     */
    fun splitForSummary(
        messages: List<Message>,
        keepLast: Int = DEFAULT_KEEP_LAST,
    ): SummarySplit? {
        if (messages.size <= keepLast) return null
        val toSummarize = messages.dropLast(keepLast)
        val toKeep = messages.takeLast(keepLast)
        if (toSummarize.isEmpty()) return null

        // compact 差集重注入（照搬 CLI）：列出被摘要的消息里 Read/Grep/Glob 读过的
        // 文件路径，减去尾部已读的 —— 接手后引用这些路径时直接引摘要，不重复读。
        // 对照官方 context-collapse：默认省 ~25K tokens/次。
        val readPaths = toSummarize.flatMap { it.content }
            .filterIsInstance<ContentBlock.ToolUse>()
            .filter { it.name in PATH_READ_TOOLS }
            .mapNotNull { extractPath(it.input.toString()) }
            .distinct()
        val keepPaths = toKeep.flatMap { it.content }
            .filterIsInstance<ContentBlock.ToolUse>()
            .mapNotNull { extractPath(it.input.toString()) }
            .toSet()
        val lostPaths = readPaths.filter { it !in keepPaths }.take(60)

        return SummarySplit(toSummarize, toKeep, lostPaths)
    }

    /** [splitForSummary] 的结果。 */
    data class SummarySplit(
        val toSummarize: List<Message>,
        val toKeep: List<Message>,
        /** 被摘要消息里读过、尾部没再读的文件路径（差集重注入用）。 */
        val lostPaths: List<String>,
    )

    /**
     * 组装压缩结果：`[历史摘要段] + 保留尾部`。
     *
     * @param summary 模型返回的摘要（已过 [extractSummary] / [formatSummary]）
     */
    fun assembleCompacted(split: SummarySplit, summary: String): List<Message> {
        val body = buildString {
            append("[历史摘要]\n")
            append(formatSummary(summary))
            if (split.lostPaths.isNotEmpty()) {
                append("\n\n【compact 前已读过的文件，以下内容已在摘要中，直接引用即可：】\n")
                append(split.lostPaths.joinToString(", "))
            }
        }
        return listOf(Message(role = Message.ROLE_USER, content = listOf(ContentBlock.Text(body)))) + split.toKeep
    }

    /**
     * 对齐官方 `formatCompactSummary`：剥离 <analysis> 草稿、<summary> 标签换成可读标题。
     */
    fun formatSummary(raw: String): String {
        var s = raw
        s = ANALYSIS_RE.replace(s, "")
        val m = Regex("<summary>([\\s\\S]*?)</summary>", RegexOption.IGNORE_CASE).find(s)
        if (m != null) {
            s = s.replace(m.value, "Summary:\n${m.groupValues[1].trim()}")
        }
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

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
            "Symbols", "RepoMap", "Diagnostics",
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

        /**
         * 摘要任务正文（照搬 CLI `core/session/compact.mjs` 的 instruction）。
         *
         * ══════════════════════════════════════════════════════════════
         *  ⚠️ 2026-10-06 修复：原来 APK 只有 [SUMMARY_PREAMBLE]（四行禁工具），
         *  **九部分结构整个缺失** —— 摘要质量差一大截（用户要求检查
         *  「CLI 精心设计过的提示词，APK 是否差不多」时发现）。
         * ══════════════════════════════════════════════════════════════
         *
         * 为什么九部分都重要（不是凑数）：
         * · 6. All user messages —— 用户意图变化的唯一原始记录
         * · 7/8. Pending/Current —— 接手者靠这个知道「干到哪了」
         * · 9. Optional Next Step 要求**引用原文**证明接续点 —— 防任务漂移
         * · 4. Errors and fixes 特别强调「用户纠正过的地方」—— 防重犯
         */
        val SUMMARY_INSTRUCTION: String = """
你的任务：为接下来的对话生成一份详细的交接摘要，让另一个 LLM 能无缝继续工作。摘要要完整保留继续开发所需的技术细节、代码模式和架构决策。

在给出最终摘要前，先用 <analysis> 标签组织你的分析（这份草稿会在使用前被剥离，不影响上下文）：
1. 按时间顺序逐条分析每条消息：用户的明确请求与意图；采取的方案；关键决策、技术概念、代码模式；具体细节（文件名、代码片段、函数签名、文件编辑）；遇到的错误及修复方式；特别注意用户纠正过的地方。
2. 核对技术准确性和完整性。

然后用 <summary> 标签输出摘要，包含以下九个部分：

1. Primary Request and Intent：详细记录用户的所有明确请求和意图
2. Key Technical Concepts：列出重要的技术概念、技术栈和框架
3. Files and Code Sections：枚举查看/修改/创建的文件和代码段，附关键代码片段，说明该文件为何重要
4. Errors and fixes：列出的所有错误及修复方式，特别是用户纠正过的地方
5. Problem Solving：已解决的问题和进行中的排查
6. All user messages：列出全部非工具结果的用户消息原文（这对理解用户反馈和意图变化至关重要）
7. Pending Tasks：用户明确要求但尚未完成的任务
8. Current Work：精确描述摘要请求前正在做什么（含文件名和代码片段）
9. Optional Next Step：与最近工作直接相关的下一步。必须引用最近对话的原文证明任务接续点，防止任务漂移。若上一任务已结束，不要自作主张列新步骤

输出格式：
<analysis>
[你的逐条分析过程]
</analysis>
<summary>
1. Primary Request and Intent: ...
...
9. Optional Next Step: ...
</summary>

如果有额外的摘要指令（如用户自定义关注点），优先遵循这些指令。
        """.trimIndent()

        /** 摘要式压缩保留的尾部条数（对齐 CLI `compact()` 的默认 10）。 */
        const val DEFAULT_KEEP_LAST = 10

        /**
         * 「读过文件」的工具名 —— 差集重注入用（照搬 CLI compact.mjs 的列表）。
         * 这些工具的入参里有 file_path / pattern / path，摘要后把路径列出来，
         * 接手者引用时不必重读。
         */
        private val PATH_READ_TOOLS = setOf("Read", "Grep", "Glob", "LS", "HashlineGrep", "HashlineRead")

        /** 从工具入参 JSON 里抠出路径类字段（CLI 同款：file_path → pattern → path）。 */
        private fun extractPath(inputJson: String): String? {
            for (key in listOf("file_path", "pattern", "path")) {
                val m = Regex("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").find(inputJson)
                if (m != null) return m.groupValues[1]
            }
            return null
        }

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
