package com.ccm.app.tools.task

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Skill 工具 —— 展开一个 skill，把它的正文注入当前上下文。
 *
 * 参照 Node 版 `core/skills.mjs`（651 行，含大量踩坑注释）。
 *
 * ══════════════════════════════════════════════════════════════
 *  目录约定（对齐 Node 版）
 * ══════════════════════════════════════════════════════════════
 *
 * 按顺序找，**先命中的优先**：
 * 1. `<cwd>/skills/`        项目级
 * 2. `~/.claude/skills/`    用户级
 *
 * 每个目录下支持**两种结构**：
 * · 扁平：`skills/foo.md`
 * · 仓库式：`skills/foo/SKILL.md`（带附属资源的 skill 用这种，同目录文件即资源）
 *
 * ══════════════════════════════════════════════════════════════
 *  frontmatter 解析：为什么手写而不是引 YAML 库
 * ══════════════════════════════════════════════════════════════
 *
 * skill 是**用户自己放的文件**，语法千奇百怪。Node 版实测遇到的两种情况都得吃：
 * ```
 * paths: ["＊＊.tsx", "src/＊＊"]        ← 内联数组
 * paths:                                ← YAML 列表
 *   - "＊＊.tsx"
 *   - src/＊＊
 * ```
 * （上面把 glob 通配符写成全角 ＊＊ —— 半角的星号斜杠组合会提前闭合本注释块，
 *   Kotlin 块注释可嵌套，编译器会报 Unclosed comment。这是本项目踩过的坑。）
 *
 * ══════════════════════════════════════════════════════════════
 *  两个 frontmatter 开关的语义（对齐官方）
 * ══════════════════════════════════════════════════════════════
 *
 * · `disable-model-invocation: true` —— **只许用户 `/名字` 触发，模型不能自己调**。
 *   用于「需要人拍板」的 skill，防止模型自动跑掉。本工具**必须拦**这条。
 * · `context: fork` —— 不在当前上下文展开，而是派子 agent 隔离执行。
 *   需要 forkRunner（本端未注入）→ 降级为内联展开并说明。
 *
 * @param globalDir 用户级 skills 目录。项目级在 execute 时由 `ctx.cwd` 推导 ——
 *   **不能构造时写死**：cwd 随会话变（/add-dir、切工作区），写死会让
 *   「切了目录但 skill 还找旧目录」这类错很难排查。
 */
class SkillTools(
    private val globalDir: File?,
) {

    /** 搜索目录：项目级（按运行时 cwd）在前，用户级在后 —— 同名时项目优先 */
    private fun rootDirsFor(cwd: String): List<File> {
        val out = mutableListOf<File>()
        if (cwd.isNotBlank()) out += File(cwd, "skills")
        globalDir?.let { out += it }
        return out
    }

    companion object {
        /** skill 正文注入上限（防一个巨大的 skill 把上下文顶满） */
        private const val MAX_BODY_CHARS = 100_000

        /**
         * 列表展示上限。
         *
         * Node 版实测：820 个全局 skill 全量注入 = 108KB ≈ 31K tokens，**每轮都发**，
         * 直接把上下文顶满。所以只列名字、超量截断，其余靠 Skill 工具按名检索。
         */
        private const val MAX_LIST = 300
    }

    // ══════════════════════════════════════════════════════════════
    //  frontmatter
    // ══════════════════════════════════════════════════════════════

    private data class Skill(
        val name: String,
        val body: String,
        val raw: String,
        val description: String,
        val scope: String,
        val baseDir: String?,
        val files: List<Pair<String, Long>>,
        val disableModelInvocation: Boolean,
        val context: String?,
        val model: String?,
        val effort: String?,
        val allowedTools: List<String>,
    )

    /** 去掉包裹的引号（`"x"` / `'x'` → `x`） */
    private fun unquote(s: String): String {
        val v = s.trim()
        return if ((v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) ||
            (v.startsWith("'") && v.endsWith("'") && v.length >= 2)
        ) v.substring(1, v.length - 1) else v
    }

    /**
     * 解析 `---` 包裹的 frontmatter。
     *
     * 返回 meta（键值对，值统一为 List<String> 以容纳两种数组写法）+ 正文。
     * **fail-open**：解析不出来的行直接跳过，不抛异常 —— skill 是用户文件，
     * 一个语法错不该让整个 skill 加载失败。
     */
    private fun parseFrontmatter(content: String): Pair<Map<String, List<String>>, String> {
        val text = content.removePrefix("\uFEFF")
        val lines = text.split("\n")
        if (lines.isEmpty() || lines[0].trim() != "---") return emptyMap<String, List<String>>() to content

        // 找闭合的 ---
        var end = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") { end = i; break }
        }
        if (end < 0) return emptyMap<String, List<String>>() to content

        val meta = mutableMapOf<String, List<String>>()
        var i = 1
        while (i < end) {
            val line = lines[i]
            val m = Regex("^([A-Za-z_][A-Za-z0-9_-]*)\\s*:(.*)$").find(line)
            if (m == null) { i++; continue }

            val key = m.groupValues[1].lowercase()
            val rest = m.groupValues[2].trim()

            when {
                // 内联数组 [a, b, c]
                rest.startsWith("[") && rest.endsWith("]") -> {
                    meta[key] = rest.substring(1, rest.length - 1)
                        .split(",").map { unquote(it) }.filter { it.isNotEmpty() }
                    i++
                }
                // 值为空 → 看下面是不是 YAML 列表
                rest.isEmpty() -> {
                    val items = mutableListOf<String>()
                    var j = i + 1
                    while (j < end && Regex("^\\s*-\\s+").containsMatchIn(lines[j])) {
                        items += unquote(lines[j].replace(Regex("^\\s*-\\s+"), ""))
                        j++
                    }
                    meta[key] = if (items.isNotEmpty()) items else listOf("")
                    i = if (items.isNotEmpty()) j else i + 1
                }
                else -> { meta[key] = listOf(unquote(rest)); i++ }
            }
        }

        // 正文 = 闭合 --- 之后的内容
        val body = lines.drop(end + 1).joinToString("\n")
        return meta to body
    }

    // ══════════════════════════════════════════════════════════════
    //  查找
    // ══════════════════════════════════════════════════════════════

    /** 非法名字（防目录穿越）—— skill 名来自模型，必须挡 `../` */
    private fun safeName(name: String): String? {
        val n = name.trim()
        if (n.isEmpty()) return null
        if (n.contains('/') || n.contains('\\') || n.contains("..") || n.contains('\u0000')) return null
        return if (n.endsWith(".md")) n else "$n.md"
    }

    private fun scopeOf(dir: File): String =
        if (globalDir != null && dir.absolutePath == globalDir.absolutePath) "global" else "project"

    /** 在搜索目录里定位 skill 文件（两种结构都试） */
    private fun resolveFile(safe: String, cwd: String): Triple<File, File, String>? {
        for (dir in rootDirsFor(cwd)) {
            if (!dir.exists() || !dir.isDirectory) continue
            // ① 扁平：skills/foo.md
            val flat = File(dir, safe)
            if (flat.isFile) return Triple(flat, dir, scopeOf(dir))
            // ② 仓库式：skills/foo/SKILL.md
            val nameDir = safe.removeSuffix(".md").removeSuffix(".MD")
            val nested = File(File(dir, nameDir), "SKILL.md")
            if (nested.isFile) return Triple(nested, dir, scopeOf(dir))
        }
        return null
    }

    private fun loadFrom(file: File, dir: File, scope: String, name: String): Skill {
        val raw = file.readText()
        val (meta, body) = parseFrontmatter(raw)

        // 仓库式结构的附属文件（同目录下的其他文件）
        val baseDir = file.parentFile
        val files = if (file.name.equals("SKILL.md", ignoreCase = true) && baseDir != null) {
            baseDir.listFiles()?.filter { it.isFile && it.name != file.name }
                ?.map { it.name to it.length() }?.sortedBy { it.first } ?: emptyList()
        } else emptyList()

        return Skill(
            name = name.removeSuffix(".md"),
            body = body,
            raw = raw,
            description = meta["description"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: body.lineSequence().firstOrNull { it.isNotBlank() }?.take(200) ?: "",
            scope = scope,
            baseDir = if (files.isEmpty()) null else baseDir?.absolutePath,
            files = files,
            disableModelInvocation = meta["disable-model-invocation"]?.firstOrNull()?.lowercase() == "true",
            context = meta["context"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            model = meta["model"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            effort = meta["effort"]?.firstOrNull()?.takeIf { it.isNotBlank() },
            allowedTools = meta["allowed-tools"] ?: meta["allowedtools"] ?: emptyList(),
        )
    }

    private fun find(name: String, cwd: String): Skill? {
        val safe = safeName(name) ?: return null
        val hit = resolveFile(safe, cwd) ?: return null
        return loadFrom(hit.first, hit.second, hit.third, safe)
    }

    /** 列出所有可用 skill 名（只扫文件名，不读正文 —— 性能考虑，见 MAX_LIST 注释） */
    private fun listNames(cwd: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()   // name → scope
        for (dir in rootDirsFor(cwd)) {
            if (!dir.exists() || !dir.isDirectory) continue
            val scope = scopeOf(dir)
            dir.listFiles()?.forEach { f ->
                when {
                    f.isFile && f.name.endsWith(".md", ignoreCase = true) ->
                        out.putIfAbsent(f.name.removeSuffix(".md"), scope)
                    f.isDirectory -> {
                        val skillMd = File(f, "SKILL.md")
                        if (skillMd.isFile) out.putIfAbsent(f.name, scope)
                    }
                }
            }
        }
        return out.entries.map { it.key to it.value }
    }

    // ══════════════════════════════════════════════════════════════
    //  Skill 工具
    // ══════════════════════════════════════════════════════════════

    inner class SkillTool : Tool() {
        override val name = "Skill"
        override val description =
            "调用一个 skill：读取它的正文并展开到当前上下文，获得该领域的专门知识/方法。" +
                "何时该调用写在每个 skill 的 description 里。支持项目 skills/ 与 ~/.claude/skills/，" +
                "同名时项目优先，名字支持模糊匹配。\n" +
                "不给 name 时列出所有可用 skill。"
        override val isReadOnly = true
        override val isConcurrencySafe = true
        override val maxResultSizeChars = 30_000

        override val inputSchema: JsonObject = ToolSchema.objectSchema(
            "name" to ToolSchema.string("skill 名（不含 .md），支持模糊匹配。省略 = 列出全部"),
            "args" to ToolSchema.string("可选参数，替换正文里的 {{args}} / {{query}}"),
        )

        override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult =
            withContext(Dispatchers.IO) {
                val name = input.str("name")?.trim()

                // ── 不给名字 → 列清单 ──────────────────────────────
                if (name.isNullOrBlank()) {
                    val all = listNames(ctx.cwd)
                    if (all.isEmpty()) {
                        return@withContext ToolResult.ok(
                            "没有找到任何 skill。\n搜索目录：\n" +
                                rootDirsFor(ctx.cwd).joinToString("\n") { "  · ${it.absolutePath}${if (it.exists()) "" else "（不存在）"}" } +
                                "\n把 skill 放成 `<目录>/名字.md` 或 `<目录>/名字/SKILL.md` 即可。",
                        )
                    }
                    val shown = all.take(MAX_LIST)
                    val more = if (all.size > MAX_LIST) "\n… 还有 ${all.size - MAX_LIST} 个（用 Skill({name}) 按名调用）" else ""
                    return@withContext ToolResult.ok(
                        "可用 skill（${all.size} 个）：\n" +
                            shown.joinToString("\n") { "  · ${it.first}  [${it.second}]" } + more,
                    )
                }

                // ── 按名查找（先精确，失败再模糊）────────────────────
                var skill = find(name, ctx.cwd)
                var fuzzyNote = ""
                if (skill == null) {
                    val all = listNames(ctx.cwd)
                    val lower = name.lowercase()
                    val cands = all.filter { it.first.lowercase().contains(lower) }
                        .ifEmpty { all.filter { lower.contains(it.first.lowercase()) } }
                    if (cands.size == 1) {
                        skill = find(cands[0].first, ctx.cwd)
                        fuzzyNote = "\n（模糊匹配到「${cands[0].first}」）"
                    } else if (cands.size > 1) {
                        return@withContext ToolResult.ok(
                            "「$name」没有精确匹配。你是不是要找：\n" +
                                cands.take(20).joinToString("\n") { "  · ${it.first}" },
                        )
                    }
                }
                if (skill == null) {
                    return@withContext ToolResult.failed(
                        "Skill \"$name\" not found（项目 skills/ 与 ~/.claude/skills/）。\n" +
                            "可用：${listNames(ctx.cwd).take(30).joinToString(", ") { it.first }}",
                    )
                }

                // ── 模型调用开关（对齐官方语义）─────────────────────
                if (skill.disableModelInvocation) {
                    return@withContext ToolResult.failed(
                        "Skill \"/${skill.name}\" 声明了 disable-model-invocation，" +
                            "只能由用户显式调用 /${skill.name}，模型不能自行触发。",
                    )
                }

                // ── 参数替换 + 附属资源说明 ────────────────────────
                val args = input.str("args")
                var body = skill.body
                if (!args.isNullOrEmpty()) {
                    body = body.replace("{{args}}", args).replace("{{query}}", args)
                }
                if (body.length > MAX_BODY_CHARS) {
                    body = body.take(MAX_BODY_CHARS) + "\n\n…（正文过长已截断）"
                }

                val filesNote = if (skill.files.isNotEmpty() && skill.baseDir != null) {
                    buildString {
                        append("本 skill 附带资源文件，目录：${skill.baseDir}\n")
                        skill.files.forEach { (n, sz) ->
                            append("  $n  (${if (sz >= 1024) "${sz / 1024}KB" else "${sz}B"})\n")
                        }
                        append("需要时用 Read 读取（路径 = 上面目录 + 文件名）。不要凭猜测编造这些文件的内容。\n\n")
                    }
                } else ""

                // ── context: fork（本端未注入 fork 执行器 → 降级）──
                if (skill.context == "fork") {
                    return@withContext ToolResult.ok(
                        "Skill /${skill.name} 声明了 context: fork，" +
                            "但当前环境未提供 fork 执行器，已降级为内联展开：" +
                            fuzzyNote + "\n\n" + filesNote + body,
                    )
                }

                val hints = mutableListOf<String>()
                skill.model?.let { hints += "建议模型 $it" }
                skill.effort?.let { hints += "思考强度 $it" }
                if (skill.allowedTools.isNotEmpty()) hints += "仅用工具 ${skill.allowedTools.joinToString("/")}"
                val hintLine = if (hints.isEmpty()) "" else "\n（本 skill 声明：${hints.joinToString(" · ")}）"

                val scopeLabel = if (skill.scope == "global") "全局" else "项目"
                ToolResult.ok(
                    "Skill /${skill.name}（$scopeLabel）展开:$hintLine$fuzzyNote\n\n" + filesNote + body,
                )
            }
    }
}
