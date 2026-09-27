package com.ccm.app.tools.file

/**
 * Hashline 锚点系统 —— 用「行内容哈希」做编辑锚点，编辑前验证行未被改动。
 *
 * 参照 Node 版 `core/hashline.mjs`（137 行）。设计来源是 Grok Build 的 hashline。
 *
 * ══════════════════════════════════════════════════════════════
 *  解决什么问题
 * ══════════════════════════════════════════════════════════════
 *
 * 传统「按行号编辑」的致命问题：**行号会漂移**。
 * ```
 * AI 读到第 50 行是要改的那行
 *   → 期间别的地方插入了 3 行
 *   → AI 按「第 50 行」去改，实际改到了别的行
 *   → 静默改错，没有报错
 * ```
 *
 * Hashline 的做法：锚点 = `行号:内容哈希`（如 `50:abcde`）。
 * 编辑前重新算第 50 行的哈希，**对不上就拒绝编辑**并返回当前的新锚点。
 * 这样「改错行」变成「明确的报错 + 可恢复」。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 两个关键设计（改之前先读）
 * ══════════════════════════════════════════════════════════════
 *
 * **1. 空白归一化**（缩进变化不影响锚点）
 * 哈希前先 `trim` 并把内部连续空白折叠成单个空格。
 * 这样「整段代码缩进多了一层」不会让所有锚点失效 ——
 * 否则缩进调整后满屏 stale，工具就没法用了。
 *
 * **2. 哈希编码用 5 个小写字母**（不是 3 个）
 * `26^5 ≈ 1188 万` 空间。原版用 3 字母（`26^3 ≈ 1.7 万`）——
 * 大文件里不同行的锚点**容易碰撞**，碰撞的后果是「验证通过但改错了行」，
 * 正是这套系统想避免的事情。所以这里用 5 位。
 *
 * 【编码方式】用**除余**取位而不是位运算取字节 ——
 * 32 bit 的熵能更均匀地铺到 5 个字母上（位运算会让高位参与不足）。
 */
object Hashline {

    // FNV-1a 32-bit 常量
    private const val FNV_OFFSET = 0x811c9dc5.toInt()
    private const val FNV_PRIME = 0x01000193

    /** 锚点里哈希部分的长度 */
    const val HASH_LEN = 5

    /**
     * FNV-1a 32 位哈希。
     *
     * 选 FNV-1a 的理由：实现短、分布好、无依赖（不需要引入 crypto）。
     * 这里只用于「检测行是否变了」，不是密码学用途。
     */
    fun fnv1a32(data: ByteArray): Int {
        var h = FNV_OFFSET
        for (b in data) {
            h = h xor (b.toInt() and 0xFF)
            h *= FNV_PRIME
        }
        return h
    }

    /**
     * 行哈希（**空白归一化**）。
     *
     * 规则：先 trim，再把内部连续空白折叠成单个空格，然后 FNV-1a。
     *
     * ⚠️ 这是「缩进变化不失效」的关键。去掉它的话，用户调整一次缩进，
     * 全文件锚点一起 stale —— 工具直接不可用。
     */
    fun lineHash(line: String): Int {
        var h = FNV_OFFSET
        var prevWs = false
        var i = 0
        val n = line.length

        // 跳过前导空白
        while (i < n && isSpace(line[i])) i++

        // 处理主体
        while (i < n) {
            val c = line[i]
            if (isSpace(c)) {
                if (!prevWs) {
                    h = h xor 0x20
                    h *= FNV_PRIME
                    prevWs = true
                }
            } else {
                h = h xor c.code
                h *= FNV_PRIME
                prevWs = false
            }
            i++
        }
        // 尾部空白已经在上面被折叠成单个空格 —— 需要去掉它才算「trim 后」
        // 简化做法：若有尾随空白，回退一次（重新算一遍更简单，见下）
        return if (prevWs) trimTailHash(line) else h
    }

    /** 尾部有空白时的重算（少走一次循环，逻辑更清晰） */
    private fun trimTailHash(line: String): Int {
        var end = line.length
        while (end > 0 && isSpace(line[end - 1])) end--
        var h = FNV_OFFSET
        var prevWs = false
        var i = 0
        while (i < end && isSpace(line[i])) i++
        while (i < end) {
            val c = line[i]
            if (isSpace(c)) {
                if (!prevWs) {
                    h = h xor 0x20
                    h *= FNV_PRIME
                    prevWs = true
                }
            } else {
                h = h xor c.code
                h *= FNV_PRIME
                prevWs = false
            }
            i++
        }
        return h
    }

    private fun isSpace(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\u000B' || c == '\u000C' || c == '\r'

    /**
     * 把 32 位哈希编码成 [len] 个小写字母。
     *
     * 用除余（`%26`）而不是位运算 —— 32 bit 的熵更均匀地铺到每一位字母上。
     * 位运算取字节会让高位参与不足（低几位字母熵少，碰撞概率不均）。
     */
    fun encodeHash(hash: Int, len: Int = HASH_LEN): String {
        val sb = StringBuilder(len)
        var h = hash.toLong() and 0xFFFFFFFFL   // 当无符号处理
        repeat(len) {
            sb.append(('a'.code + (h % 26).toInt()).toChar())
            h /= 26
        }
        return sb.toString()
    }

    /** 生成锚点：`行号:哈希` */
    fun anchor(line: String, lineNum: Int): String =
        "$lineNum:${encodeHash(lineHash(line))}"

    /** 锚点 */
    data class Anchor(val line: Int, val local: String) {
        override fun toString(): String = "$line:$local"
    }

    /**
     * 解析锚点字符串。
     *
     * 容忍 `"50:abcde"`；非法（缺冒号、行号非数字/为 0、哈希非纯小写字母）返回 null。
     *
     * ⚠️ 必须容忍 null 输入 —— 上游可能传 undefined（如 `edit.anchor` 字段缺失），
     * 那种情况下应该报「参数缺失」而不是崩。
     */
    fun parseAnchor(str: String?): Anchor? {
        if (str == null) return null
        val parts = str.split(':')
        if (parts.size < 2) return null
        val line = parts[0].toIntOrNull() ?: return null
        if (line <= 0) return null
        val local = parts[1]
        if (local.isEmpty() || !local.all { it in 'a'..'z' }) return null
        return Anchor(line, local)
    }

    /** 验证结果 */
    enum class Validity {
        /** 锚点匹配，可以编辑 */
        VALID,

        /** 行内容已变（锚点过期） */
        STALE,

        /** 行号越界 */
        OUT_OF_RANGE,
    }

    /** 验证锚点是否匹配当前行 */
    fun validate(anchor: Anchor, lines: List<String>): Validity {
        val idx = anchor.line - 1
        if (idx < 0 || idx >= lines.size) return Validity.OUT_OF_RANGE
        val expected = encodeHash(lineHash(lines[idx]))
        return if (anchor.local == expected) Validity.VALID else Validity.STALE
    }

    /**
     * 搜索「漂移后的锚点」—— 在原位置 ±radius 范围内找内容匹配的行。
     *
     * 用途：锚点 stale 时，告诉调用方「你要改的那行现在在第 N 行」，
     * 而不是让它重新读整个文件。
     */
    sealed class ShiftResult {
        /** 唯一匹配 */
        data class Found(val line: Int) : ShiftResult()

        /** 多处匹配（**不能自动选** —— 可能改错地方） */
        data class Ambiguous(val lines: List<Int>) : ShiftResult()

        /** 找不到（内容真的变了） */
        object NotFound : ShiftResult()
    }

    fun findShifted(anchor: Anchor, lines: List<String>, searchRadius: Int = 15): ShiftResult {
        val origIdx = anchor.line - 1
        val start = maxOf(0, origIdx - searchRadius)
        val end = minOf(lines.size, origIdx + searchRadius + 1)
        val candidates = mutableListOf<Int>()

        for (idx in start until end) {
            if (idx == origIdx) continue   // 跳过原位置（已验证失败）
            val local = encodeHash(lineHash(lines[idx]))
            if (local != anchor.local) continue
            candidates += idx + 1
        }

        return when (candidates.size) {
            0 -> ShiftResult.NotFound
            1 -> ShiftResult.Found(candidates[0])
            else -> ShiftResult.Ambiguous(candidates)
        }
    }

    /**
     * 把文件内容格式化成 hashline 形式：每行 `行号:哈希→内容`。
     *
     * @param offset 起始行（1-based）
     * @param limit 最多返回多少行（null = 全部）
     */
    fun format(content: String, offset: Int? = null, limit: Int? = null): String {
        val allLines = content.split("\n").toMutableList()
        // 文件以 \n 结尾时 split 会多出一个空串，去掉它
        if (allLines.size > 1 && allLines.last().isEmpty()) allLines.removeAt(allLines.size - 1)

        val skip = maxOf(0, (offset ?: 1) - 1)
        val take = limit ?: allLines.size

        val sb = StringBuilder()
        var i = skip
        val end = minOf(skip + take, allLines.size)
        while (i < end) {
            val lineNum = i + 1
            sb.append(anchor(allLines[i], lineNum)).append('→').append(allLines[i])
            if (i < end - 1) sb.append('\n')
            i++
        }
        return sb.toString()
    }

    /** 按行拆分（与 [format] 同一套「去掉尾部空行」规则） */
    fun splitLines(content: String): MutableList<String> {
        val lines = content.split("\n").toMutableList()
        if (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines
    }
}
