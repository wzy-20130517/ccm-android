package com.ccm.app.tools.file

import java.io.File
import java.io.IOException

/**
 * 路径解析与安全边界检查。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么需要「越界检查」
 * ══════════════════════════════════════════════════════════════
 *
 * 模型给的路径可能包含 `../../etc/passwd` 这类相对穿越，
 * 或者 ApplyPatch 里带 `a/../../outside.txt`。
 * 不检查的话，一次 patch 就能写到工作区之外。
 *
 * 规则（与 CCM `tools-smart.mjs:safeResolve` 一致）：
 * 解析后的绝对路径必须**等于**基准目录，或在基准目录**之下**。
 *
 * ⚠️ 已知局限：基于字符串前缀比较，不解析符号链接。
 *    若工作区内有指向外部的符号链接，仍可穿越 —— 但 APK 场景下
 *    工作区是 App 私有目录，用户自己建的链接，风险可接受。
 */
object PathGuard {

    /**
     * 把 [target] 相对 [base] 解析成绝对路径，并校验不越界。
     *
     * @throws IOException 越界时抛出
     */
    @Throws(IOException::class)
    fun resolveIn(base: File, target: String): File {
        val root = base.canonicalFile
        val full = if (File(target).isAbsolute) File(target) else File(root, target)
        val fullCanonical = try {
            full.canonicalFile
        } catch (_: Throwable) {
            // canonicalFile 在文件不存在时也能算出来；这里兜底用 absoluteFile
            full.absoluteFile
        }
        if (fullCanonical != root && !fullCanonical.path.startsWith(root.path + File.separator)) {
            throw IOException("路径越界: $target（只能操作 ${root.path} 内的文件）")
        }
        return fullCanonical
    }

    /**
     * 宽松解析：允许 [extraDirs] 里的额外目录（对应 /add-dir）。
     *
     * ══════════════════════════════════════════════════════════════
     *  【2026-10-06 用户报·重要改动】去掉了「只能在工作区内」的限制
     *
     *  用户原话：「除了工作区之外都不能动。我们 cli 完全没这样的限制」。
     *
     *  CLI 的实现（`file-tools.mjs:8`）就是一行：
     *    `resolve(ctx.cwd || process.cwd(), filePath)`
     *  —— 相对路径基于 cwd 解析，**绝对路径直接用，零边界检查**。
     *
     *  为什么 APK 原来加了这个限制：早期怕模型乱写（`../../etc/passwd`）。
     *  但那个担忧放错了地方 ——
     *   · **相对路径穿越**才是真风险（模型算错层级），这里仍然防：
     *     相对路径解析后必须在 cwd 或 extraDirs 内
     *   · **绝对路径**是用户/模型**明确指定**的目标，拦住它只会让人
     *     「想改 /sdcard 上的文件都改不了」，与 CLI 行为严重不一致
     *
     *  现在语义（与 CLI 对齐）：
     *   · 相对路径 → 基于 cwd 解析，越界（含 `..` 穿越）则拒绝
     *   · 绝对路径 → 直接放行
     * ══════════════════════════════════════════════════════════════
     */
    @Throws(IOException::class)
    fun resolveAllowed(cwd: File, extraDirs: List<File>, target: String): File {
        // 【2026-10-06】没有工作区（cwd 为空）时，**相对路径直接拒绝** ——
        // File("") 会解析成进程当前目录（通常是 /），模型写个 "a.txt"
        // 就会落到 /a.txt（既没权限又莫名其妙）。
        // 绝对路径仍放行（用户明确指定，不受工作区配置影响）。
        if (cwd.path.isBlank() && !File(target).isAbsolute) {
            throw IOException(
                "没有工作区，相对路径无法解析。\n" +
                    "两种解决办法：\n" +
                    "  · 用绝对路径（如 /sdcard/Download/claude-workspace/a.txt）\n" +
                    "  · 或先用 /workspace <路径> 设置工作区"
            )
        }

        // 绝对路径：直接放行（对齐 CLI —— 用户明确指定的目标不该拦）
        if (File(target).isAbsolute) {
            return try {
                File(target).canonicalFile
            } catch (_: Throwable) {
                File(target).absoluteFile
            }
        }

        // 相对路径：基于 cwd 解析，越界（`..` 穿越）则拒绝 —— 这部分保留
        return try {
            resolveIn(cwd, target)
        } catch (e: IOException) {
            // 再试 extraDirs
            extraDirs.forEach { dir ->
                try {
                    return resolveIn(dir, target)
                } catch (_: IOException) {
                    // 继续试下一个
                }
            }
            throw e
        }
    }

    private fun isUnder(base: File, target: File): Boolean {
        val root = try {
            base.canonicalFile
        } catch (_: Throwable) {
            base.absoluteFile
        }
        return target == root || target.path.startsWith(root.path + File.separator)
    }
}

/**
 * Unified diff 解析与应用 —— ApplyPatch 工具的核心。
 *
 * ══════════════════════════════════════════════════════════════
 *  格式支持
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 * diff --git a/src/x.kt b/src/x.kt
 * --- a/src/x.kt
 * +++ b/src/x.kt
 * @@ -10,7 +10,8 @@
 *  context line
 * -removed line
 * +added line
 * ```
 *
 * 支持：新增文件、删除文件、修改文件、多个 hunk、`\ No newline at end of file`。
 *
 * ══════════════════════════════════════════════════════════════
 *  关键设计：从后往前应用 hunk
 * ══════════════════════════════════════════════════════════════
 *
 * 若从前往后应用，第一个 hunk 插入/删除行后，后续 hunk 的**行号全部偏移**，
 * 必须动态修正 —— 一旦算错就写坏文件。
 * 从后往前应用时，后面的行号不受前面改动影响，天然免修正。
 */
object PatchParser {

    /** 一个 hunk */
    data class Hunk(
        val oldStart: Int,
        val oldCount: Int,
        val newStart: Int,
        val newCount: Int,
        val oldLines: List<String>,
        val newLines: List<String>,
    )

    /** 一个文件的 patch 段 */
    data class FilePatch(
        val path: String,          // 规范化后的相对路径（去掉 a/ b/ 前缀）
        val oldPath: String?,
        val newPath: String?,
        val hunks: List<Hunk>,
        val isNew: Boolean,
        val isDelete: Boolean,
    )

    private val HUNK_HEADER = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

    /**
     * 解析整份 patch。
     *
     * @throws IOException 找不到任何 `diff --git` 段时抛出
     */
    @Throws(IOException::class)
    fun parse(patchText: String): List<FilePatch> {
        val lines = patchText.replace("\r\n", "\n").split("\n")
        val result = mutableListOf<FilePatch>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].startsWith("diff --git ")) {
                val (fp, next) = parseFilePatch(lines, i)
                if (fp.path.isEmpty()) {
                    throw IOException("无法解析 patch 文件路径（${lines[i]}）")
                }
                result += fp
                i = next
            } else {
                i++
            }
        }
        if (result.isEmpty()) {
            throw IOException("patch 中未找到 diff --git 段，请提供完整 git diff 格式")
        }
        return result
    }

    private fun parseFilePatch(lines: List<String>, startIdx: Int): Pair<FilePatch, Int> {
        var i = startIdx + 1
        var oldPath: String? = null
        var newPath: String? = null
        var isNew = false
        var isDelete = false
        val hunks = mutableListOf<Hunk>()
        var curHunk: Hunk? = null
        var inHunk = false

        // 累计当前 hunk 的行缓冲
        var oldLines = mutableListOf<String>()
        var newLines = mutableListOf<String>()

        fun flushHunk() {
            curHunk?.let { h ->
                hunks += h.copy(oldLines = oldLines.toList(), newLines = newLines.toList())
            }
            oldLines = mutableListOf()
            newLines = mutableListOf()
        }

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("diff --git ")) break

            when {
                line.startsWith("new file mode") -> isNew = true
                line.startsWith("deleted file mode") -> isDelete = true
                line.startsWith("--- ") -> {
                    val p = line.substring(4).trim()
                    oldPath = p.takeIf { it != "/dev/null" }
                    i++
                    continue
                }
                line.startsWith("+++ ") -> {
                    val p = line.substring(4).trim()
                    newPath = p.takeIf { it != "/dev/null" }
                    i++
                    continue
                }
                line.startsWith("@@ ") -> {
                    flushHunk()
                    val m = HUNK_HEADER.find(line)
                    curHunk = Hunk(
                        oldStart = m?.groupValues?.get(1)?.toIntOrNull() ?: 1,
                        oldCount = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1,
                        newStart = m?.groupValues?.get(3)?.toIntOrNull() ?: 1,
                        newCount = m?.groupValues?.get(4)?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 1,
                        oldLines = emptyList(),
                        newLines = emptyList(),
                    )
                    inHunk = true
                    i++
                    continue
                }
            }

            if (inHunk) {
                when {
                    line.startsWith(" ") -> {
                        oldLines += line.substring(1)
                        newLines += line.substring(1)
                    }
                    line.startsWith("-") -> oldLines += line.substring(1)
                    line.startsWith("+") -> newLines += line.substring(1)
                    line.startsWith("\\ No newline") -> { /* 忽略 */ }
                    else -> inHunk = false
                }
            }
            i++
        }
        flushHunk()

        // 从 +++ / --- 解析路径（去掉 a/ b/ 前缀）
        var path = newPath ?: oldPath ?: ""
        path = path.replace(Regex("^[ab]/"), "")

        return FilePatch(path, oldPath, newPath, hunks, isNew, isDelete) to i
    }

    /**
     * 把 hunks 应用到旧内容，返回新内容。
     *
     * @throws IOException hunk 上下文不匹配时抛出（这是**保护**：宁可失败也不写坏文件）
     */
    @Throws(IOException::class)
    fun applyHunks(oldContent: String, hunks: List<Hunk>): String {
        val lines = oldContent.split("\n").toMutableList()
        // 文件以 \n 结尾时 split 会多出一个空串，去掉它
        if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1)

        // 从后往前应用：后面的行号不受前面改动影响
        for (h in hunks.indices.reversed()) {
            val hunk = hunks[h]
            val start = hunk.oldStart - 1   // 转 0-based
            val oldLen = hunk.oldLines.size

            // 验证旧内容匹配 —— 不匹配说明 patch 与当前文件不同步
            for (k in 0 until oldLen) {
                val actual = lines.getOrNull(start + k)
                val expected = hunk.oldLines[k]
                if (actual != expected) {
                    val exp = expected.take(60)
                    val act = (actual ?: "<行不存在>").take(60)
                    throw IOException(
                        "hunk @-${hunk.oldStart} 上下文不匹配：" +
                            "第 ${start + k + 1} 行期望 \"$exp\"，实际 \"$act\""
                    )
                }
            }
            // 替换 oldLen 行为 newLines
            repeat(oldLen) { if (start < lines.size) lines.removeAt(start) }
            lines.addAll(start, hunk.newLines)
        }

        return lines.joinToString("\n") + "\n"
    }

    /** 全新文件：把所有 hunk 的 + 行拼起来 */
    fun buildNewFile(hunks: List<Hunk>): String {
        val lines = mutableListOf<String>()
        hunks.forEach { lines += it.newLines }
        return lines.joinToString("\n") + "\n"
    }
}
