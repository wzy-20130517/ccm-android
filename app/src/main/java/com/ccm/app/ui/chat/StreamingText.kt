package com.ccm.app.ui.chat

/**
 * 流式 Markdown 记账器 —— 移植自 Node 版 `core/stream-md.mjs` 的核心策略。
 *
 * ## ★ 核心原则（Node 版踩坑后定下的）
 * > **以「源文本的换行边界」为唯一记账单位，逐行判断能否独立渲染。**
 *
 * ```
 * 每收到一个完整行：
 *   ├─ 能独立渲染（标题/引用/列表/分割线/普通文本）→ 立刻渲染吐出
 *   └─ 不能（代码块 fence 内、表格连续行）→ 攒进缓冲，等结构收尾再整块渲染
 * ```
 *
 * ## 为什么不用「攒到空行才 flush 整段」（旧做法，已废弃）
 * 1. 模型输出末尾常常没有空行收尾 → 最后一段要靠 [flush] 兜底才出来
 * 2. **流断在非边界处就直接丢正文**（Node 版真实 bug）
 *
 * ## 三条不变量
 * 1. 「已消费位置」只在消费掉**完整行**时前进，**永不跨越未闭合结构**
 * 2. 每次吐出的片段都是**终态**（可直接渲染）
 * 3. **[flush] 时必须把所有未闭合结构落地** —— 不能吞内容
 *
 * ## 实现要点（两个踩过的坑，已用 Python 复刻算法验证 7 个用例）
 *
 * **坑 1：尾部半截行必须独立存字段，不能追加进 pending。**
 * 每次 `feed` 都会重新计算尾部半截行；若直接 `pending.append(tail)`，
 * 同一个 tail 会被反复追加，导致输出重复。
 * → 用独立字段 [tail]，每次**覆盖**而不是追加。
 *
 * **坑 2：`chunk.split('\n')` 的最后一项一定是空串**（因为 chunk 以 `\n` 结尾），
 * 必须 `dropLast(1)`，否则会多出一个空行。
 *
 * ## 用法
 * ```kotlin
 * val md = StreamingMarkdown()
 * // state.streaming 每次变化时：
 * val r = md.feed(newFullText)
 * render(r.stable)        // 已定型，可安全渲染
 * showPending(r.pending)  // 可选：把尾部也渲染（用降级样式）
 * // 收到 Done 时：
 * val all = md.flush()    // ★ 必须调用，否则丢内容
 * ```
 *
 * > 本类**只做记账**，不做 Markdown 解析 —— 解析交给渲染层。
 * > 这样「记账策略」与「渲染实现」解耦，换渲染器不用改这里。
 */
class StreamingMarkdown {

    /** 已定型输出（终态片段累积） */
    private val stable = StringBuilder()

    /** 未闭合结构的缓冲（fence 内 / 表格） */
    private val pending = StringBuilder()

    /**
     * 尾部半截行（最后一个 `\n` 之后的部分）。
     *
     * ⚠️ 独立字段而非塞进 [pending]：每次 [feed] 都会重算尾部，
     * 塞进 pending 会重复追加（Python 复刻验证时抓到的 bug）。
     */
    private var tail = ""

    /** 已消费到的源文本下标 —— 只在吃掉完整行时前进 */
    private var consumed = 0

    /** 是否在未闭合的代码块内 */
    private var inFence = false

    /** 当前代码块的围栏标记（``` 或 ~~~） */
    private var fenceMarker = ""

    /**
     * 喂入累积全文（**不是增量** —— 传 `state.streaming` 的当前值）。
     *
     * 幂等：同一文本重复调用不会重复消费（靠 [consumed] 判据）。
     *
     * @return [Result.stable] 已定型部分，[Result.pending] 仍在缓冲的尾部
     */
    fun feed(fullText: String): Result {
        val lastNl = fullText.lastIndexOf('\n')

        if (lastNl < consumed) {
            // 没有新的完整行；尾部半截行更新
            tail = fullText.substring(consumed)
            return Result(stable.toString(), pending.toString() + tail)
        }

        val chunk = fullText.substring(consumed, lastNl + 1)
        consumed = lastNl + 1

        // ⚠️ chunk 一定以 \n 结尾，split 后最后一项是空串，必须丢掉
        val lines = chunk.split('\n').dropLast(1)

        for (line in lines) {
            val trimmed = line.trimStart()

            // ── 代码块围栏 ────────────────────────────────────────────
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val marker = trimmed.take(3)
                if (!inFence) {
                    inFence = true
                    fenceMarker = marker
                    pending.append(line).append('\n')
                } else if (marker == fenceMarker) {
                    // 闭合 → 整块落地
                    inFence = false
                    pending.append(line).append('\n')
                    stable.append(pending)
                    pending.setLength(0)
                } else {
                    // fence 内出现的其他围栏标记，当普通内容
                    pending.append(line).append('\n')
                }
                continue
            }

            // ── fence 内：一律攒着 ────────────────────────────────────
            if (inFence) {
                pending.append(line).append('\n')
                continue
            }

            // ── 表格行：连续 | 开头要一起渲染 ─────────────────────────
            if (trimmed.startsWith("|")) {
                pending.append(line).append('\n')
                continue
            }

            // 遇到非表格行 → 把攒的表格先落地
            if (pending.isNotEmpty()) {
                stable.append(pending)
                pending.setLength(0)
            }

            // ── 可独立渲染 → 立刻吐出 ─────────────────────────────────
            stable.append(line).append('\n')
        }

        tail = fullText.substring(lastNl + 1)
        return Result(stable.toString(), pending.toString() + tail)
    }

    /**
     * 收尾 —— **必须调用**（不变量 3）。
     *
     * 把 [pending] 与 [tail] 里剩的全部落地（哪怕 fence 没闭合）。
     * 不调用会导致末尾内容被吞 —— 这是 Node 版踩过的 bug。
     */
    fun flush(): String {
        if (pending.isNotEmpty()) {
            stable.append(pending)
            pending.setLength(0)
        }
        if (tail.isNotEmpty()) {
            stable.append(tail)
            tail = ""
        }
        inFence = false
        fenceMarker = ""
        return stable.toString()
    }

    /** 重置（新一轮开始） */
    fun reset() {
        stable.setLength(0)
        pending.setLength(0)
        tail = ""
        consumed = 0
        inFence = false
        fenceMarker = ""
    }

    /** 当前是否在未闭合的代码块内 */
    val isInFence: Boolean get() = inFence

    /**
     * @param stable  已定型、可安全渲染的累积文本
     * @param pending 仍在缓冲的尾部（未闭合 fence / 表格 / 半截行）
     */
    data class Result(val stable: String, val pending: String) {
        /** 把两段拼起来 —— 用于「不区分定型与否」的简单渲染 */
        val combined: String get() = stable + pending
    }
}
