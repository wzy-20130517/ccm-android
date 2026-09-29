package com.ccm.app.core.output

import java.io.File

/**
 * 输出风格（2026-09-29 与 CLI/Web 互通）。
 *
 * ## 三端约定
 * - 存储字段：config.json 的 `outputStyle`（CLI `/style` 存的、Web `server.mjs:2411`
 *   读的、这里也是**同一个字段名**）
 * - 风格 id：`default` / `explanatory` / `learning`（内置，对齐官方）；
 *   自定义 = `.claude/output-styles/<文件名>.md`（文件名即 id）
 * - 生效方式：拼进 systemPrompt（见 `AppContainer.assembleSystemPrompt`）
 *   —— 与会话重建同生命周期（改风格后切会话/重启生效）
 *
 * ## 自定义风格的读取
 * 与 CLI `core/output-styles.mjs` 同约定：扫 `<cwd>/.claude/output-styles/`
 * 与 `~/.claude/output-styles/` 下的 .md 文件（同名时项目级覆盖用户级）。
 * ⚠️ 注释里不能写 `星号点md` 序列 —— Kotlin 注释不嵌套，那个序列会被
 *    当成新注释的开始，报 "Unclosed comment"（本文件踩过）。
 * frontmatter（--- name/description ---）只取 name 供展示，正文即提示词。
 */
object OutputStyles {

    /** 一条风格记录。 */
    data class Style(
        val id: String,
        val name: String,
        val description: String,
        val prompt: String,
    )

    /**
     * 内置三件套 —— 提示词对齐官方 outputStyles（CLI 的 core/output-styles.mjs
     * 里那两个非默认风格的翻译版，措辞保持同一意图）。
     */
    private val BUILTIN = listOf(
        Style(
            id = "default",
            name = "默认",
            description = "标准回复方式",
            prompt = "",   // default = 不注入任何东西
        ),
        Style(
            id = "explanatory",
            name = "Explanatory（教学向）",
            description = "在回复里插入背景知识和「为什么这样」的说明",
            prompt = "## 输出风格：Explanatory\n\n" +
                "在完成用户请求的同时，适当补充解释性内容：\n" +
                "- 关键决策处说明「为什么这么做」（一句到三句，不长篇大论）\n" +
                "- 涉及用户可能不熟悉的术语/工具时给一行背景\n" +
                "- 解释要服务于任务本身，不要变成教科书",
        ),
        Style(
            id = "learning",
            name = "Learning（让你动手）",
            description = "把部分实现留给用户自己写，边做边学",
            prompt = "## 输出风格：Learning\n\n" +
                "这是一个学习场景。不要直接给出全部答案：\n" +
                "- 骨架/接口/关键注释由你写，核心逻辑留 `// TODO(你来写): ...` 让用户补\n" +
                "- 每留一处说明该写什么、为什么、验证方式\n" +
                "- 用户写完后帮审阅和解释，不要直接替换成你的版本",
        ),
    )

    @Volatile
    private var cache: List<Style>? = null

    /**
     * 全部可用风格：内置 + 自定义（自定义可覆盖同名内置，对齐 CLI 的加载顺序）。
     *
     * @param cwd 工作区目录（自定义风格从这里找）；null 只用内置
     */
    fun all(cwd: String? = null): List<Style> {
        // 自定义文件会变，缓存按 cwd 短期失效：简单起见每次扫（文件数 < 10，开销可忽略）
        val custom = mutableListOf<Style>()
        if (cwd != null) {
            for (dir in listOf(File(cwd, ".claude/output-styles"), File(System.getProperty("user.home") ?: "", ".claude/output-styles"))) {
                try {
                    dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { f ->
                        val id = f.name.removeSuffix(".md")
                        val raw = f.readText()
                        custom += Style(
                            id = id,
                            name = parseFrontmatter(raw, "name") ?: id,
                            description = parseFrontmatter(raw, "description") ?: "自定义风格",
                            prompt = stripFrontmatter(raw),
                        )
                    }
                } catch (_: Throwable) {}
            }
        }
        // 自定义覆盖同名内置（CLI 同约定）
        val merged = BUILTIN.filter { b -> custom.none { it.id == b.id } } + custom
        return merged
    }

    /** 取某风格的注入文本；default / 找不到 → null（不注入）。 */
    fun promptFor(styleId: String?, cwd: String? = null): String? {
        if (styleId.isNullOrBlank() || styleId == "default") return null
        val s = all(cwd).firstOrNull { it.id == styleId } ?: return null
        // 找不到该风格的提示（对齐 Web server.mjs 的回退告警语义，这里静默回退默认行为）
        return s.prompt.ifBlank { null }
    }

    /** frontmatter 取字段：`---\nname: xxx\n---`。 */
    private fun parseFrontmatter(text: String, key: String): String? {
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", 3)
        if (end == -1) return null
        val head = text.substring(3, end)
        return head.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key:") }
            ?.substringAfter(":")
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }
    }

    /** 去掉 frontmatter 只留正文（正文即提示词）。 */
    private fun stripFrontmatter(text: String): String {
        if (!text.startsWith("---")) return text.trim()
        val end = text.indexOf("\n---", 3)
        if (end == -1) return text.trim()
        return text.substring(end + 4).trim()
    }
}
