package com.ccm.app.tools.file

import java.io.File

/**
 * Glob 模式匹配（`**` `*` `?` 支持），以及目录遍历。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么不用 Java 自带的 PathMatcher
 * ══════════════════════════════════════════════════════════════
 *
 * `FileSystems.getDefault().getPathMatcher()` 可用，但有三个坑：
 *   1. 语义与 shell glob 有细微差异（`**` 的匹配范围、`.` 开头文件的处理）
 *   2. 某些模式会抛 `PatternSyntaxException`，需要额外兜底
 *   3. 与 CCM 的 JS 实现行为**对不上** —— 而我们要的是「模型侧行为一致」
 *
 * 所以自己实现一份，逐条对齐 CCM `core/search-tools.mjs` 的 `_matchGlob()`：
 *   · `**` → 匹配任意字符（含 `/`）；当 `**` 后紧跟 `/` 时特殊处理，允许零级目录
 *   · `*`  → 匹配除 `/` 外任意字符
 *   · `?`  → 匹配单个非 `/` 字符
 *   · 其余正则元字符全部转义
 *
 * 【相对路径】匹配针对「相对于搜索根的路径」，不是绝对路径 ——
 * 这样以 `**` 开头的模式才能同时匹配根目录与子目录里的文件。
 *
 * ⚠️ 注释里不要写出「星号+斜杠」或「斜杠+星号」的字面组合：
 * Kotlin 支持**嵌套块注释**，这类序列会意外开/闭注释，导致整份文件语法错误。
 */
object GlobMatcher {

    /**
     * 把 glob 编译成 Regex。
     *
     * 失败时返回 null（不抛错）—— 让调用方退回「不匹配」，而不是崩掉整个搜索。
     */
    fun compile(pattern: String): Regex? {
        val sb = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                // `**` → 任意字符（含路径分隔符）。
                // 若 `**` 后紧跟斜杠，要额外吞掉那个斜杠：让 `**` 开头的模式
                // 能匹配根目录下的文件（否则要求至少一级目录，与 shell/CCM 行为不符）。
                c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                    sb.append(".*")
                    i += 2
                    if (i < pattern.length && pattern[i] == '/') i++
                }
                c == '*' -> {
                    sb.append("[^/]*")
                    i++
                }
                c == '?' -> {
                    sb.append("[^/]")
                    i++
                }
                c in "\\^$.+()[]{}|" -> {
                    sb.append('\\').append(c)
                    i++
                }
                c == '/' -> {
                    sb.append('/')
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        sb.append('$')
        return try {
            Regex(sb.toString())
        } catch (_: Throwable) {
            null
        }
    }

    /** 便捷匹配：模式非法时返回 false（不抛错） */
    fun matches(relativePath: String, pattern: String): Boolean {
        val re = compile(pattern) ?: return false
        return re.containsMatchIn(relativePath)
    }
}

/**
 * 目录遍历器 —— 各搜索工具共用。
 *
 * 【跳过规则】（与 CCM 一致，这是「搜索不卡死」的关键）
 *   · 隐藏文件/目录（`.` 开头）
 *   · `node_modules`
 * 两者都是「文件数巨大但几乎从不关心」的典型。不跳过的话，
 * 一次 Grep 可能扫十几万个文件，手机上要几十秒。
 *
 * 【为什么不用 `File.walk()`】Kotlin 标准库的 `walk()` 是序列，
 * 但它在遇到权限错误时会抛异常中断整个遍历。这里需要「跳过无权限目录、
 * 继续遍历其他分支」，所以手写递归 + try/catch。
 */
object FileWalker {

    /** 默认跳过的目录名 */
    private val SKIP_DIRS = setOf("node_modules", ".git", "build", ".gradle", "__pycache__")

    /**
     * 遍历目录，对每个文件调用 [onFile]。
     *
     * @param root 起始目录
     * @param maxFiles 最多访问多少个文件（防止超大目录卡死），达到后停止
     * @param onFile 回调，返回 false 表示「够了，停止遍历」
     * @return 实际访问的文件数
     */
    fun walk(
        root: File,
        maxFiles: Int = 20000,
        skipDirs: Set<String> = SKIP_DIRS,
        onFile: (File, String) -> Boolean,
    ): Int {
        var visited = 0

        fun recurse(dir: File, relativePrefix: String) {
            if (visited >= maxFiles) return
            val entries = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: return

            for (entry in entries) {
                if (visited >= maxFiles) return
                val name = entry.name
                // 隐藏项一律跳过（含 .git/.gradle 等）
                if (name.startsWith('.')) continue

                val rel = if (relativePrefix.isEmpty()) name else "$relativePrefix/$name"
                if (entry.isDirectory) {
                    if (name in skipDirs) continue
                    recurse(entry, rel)
                } else if (entry.isFile) {
                    visited++
                    if (!onFile(entry, rel)) return
                }
            }
        }

        if (root.isDirectory) {
            recurse(root, "")
        } else if (root.isFile) {
            // ⚠️ 支持「root 就是单个文件」—— CCM 踩过这个坑：
            // 原实现无条件 readdirSync，对文件路径抛 ENOTDIR 被吞掉，
            // 静默返回 "(no matches)"，用户以为「没匹配」，实际是根本没搜。
            visited++
            onFile(root, root.name)
        }
        return visited
    }
}
