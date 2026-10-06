package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * Markdown 渲染器 —— 对齐 `MarkdownRenderer.tsx`（410 行）。
 *
 * ## ★ 为什么手写解析器
 * Web 用 `react-markdown` + `remark-gfm` + `remark-math` + `rehype-katex`（四个成熟库）。
 * Compose 没有等价生态，且项目约定「少依赖重工具链」。
 * 这里实现**消息场景够用的子集** —— 覆盖模型输出的 95%：
 *
 * | 语法 | 支持 | 说明 |
 * |---|---|---|
 * | 标题 `#`~`######` | ✅ | 六级字号照搬源码 |
 * | 段落 | ✅ | 空行分隔 |
 * | 无序列表 `- * +` | ✅ | |
 * | 有序列表 `1.` | ✅ | |
 * | 代码块 ``` ``` ``` | ✅ | 带语言标签 + 复制按钮 |
 * | 行内代码 `` ` `` | ✅ | |
 * | 粗体 `**` / 斜体 `*` | ✅ | |
 * | 删除线 `~~` | ✅ | GFM |
 * | 链接 `[]()` | ✅ | 蓝色下划线样式 |
 * | 分割线 `---` | ✅ | |
 * | 引用 `>` | ✅ | 左侧竖线 |
 * | 表格 | ✅ | GFM 管道表格 |
 * | 任务列表 `- [ ]` | ✅ | GFM |
 * | 数学公式 KaTeX | ❌ | 需 KaTeX 引擎，Compose 无等价物 |
 * | 引用角标 `[N]` | ✅ | 仅剥离标记，来源列表见 [MarkdownSourcesList] |
 *
 * ## ★ 关键尺寸（源码 Tailwind，**已 ×0.92**）
 * | 元素 | 类 | 屏幕值 |
 * |---|---|---|
 * | 正文 | `text-[16.5px] leading-[1.7]` | **15.18** / 行高 25.81 |
 * | h1 | `text-[25px] leading-[1.2]` | **23.0** / 27.6 · 上 6.44 下 2.76 |
 * | h2 | `text-[21px] leading-[1.25]` | **19.32** / 24.15 |
 * | h3 | `text-[18px] leading-[1.3]` | **16.56** / 21.53 |
 * | h4 | `text-[16.8px]` | **15.46** |
 * | h5 | `text-[15.8px]` | **14.54** |
 * | h6 | `text-[15px]` + `uppercase` | **13.8** |
 * | 段落间距 | `mb-2.5` | **2.3** |
 * | 代码块 | `p-1em rounded-8` | 内距 14.72 / 圆角 7.36 |
 * | 行内代码 | `px-1.5 rounded-md text-[14.5px]` | 5.52 / 圆角 5.52 / **13.34** |
 * | 表格 | `text-[14.5px]` | **13.34** |
 * | 分割线 | `my-6` | 上下 22.08 |
 *
 * ## 色值来源
 * 正文用 `--text-claude-model-body`（暗色下比 UI 文字偏暖），
 * 段落/标题/列表/引用/粗斜体**全部**用该色（源码 `.assistant-markdown` 统一覆盖）。
 */
@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    showSourcesList: Boolean = false,
    sources: List<CitationSource> = emptyList(),
) {
    val colors = CCMTheme.colors

    // 解析结果缓存 —— 流式渲染时每帧都会重算，不做缓存会卡
    val blocks = remember(content) { parseMarkdown(content) }

    // 【2026-10-06 问题15 修复】原来裸 Column —— Text 默认**不可选取**，
    // 长按只能触发外层 combinedClickable 的「全部复制」。
    // 用户要「选取复制」（选一段，不是复制整条）。
    // SelectionContainer 让所有子 Text 进入可选取模式：
    // 长按 → 系统选择手柄 + 复制菜单（Android 原生体验）。
    //
    // ⚠️ 与外层 combinedClickable(onLongClick=复制全部) 的关系：
    //   SelectionContainer 内部的长按会被它优先消费（文本选择优先），
    //   外层长按只在「长按非文本区」（如列表空白）时触发。
    //   两个入口共存，不冲突。
    androidx.compose.foundation.text.selection.SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            blocks.forEach { block -> MarkdownBlockView(block) }

            if (showSourcesList && sources.isNotEmpty()) {
                MarkdownSourcesList(sources = sources)
            }
        }
    }
}

/** 逐块渲染 */
@Composable
private fun MarkdownBlockView(block: MdBlock) {
    val colors = CCMTheme.colors
    val bodyColor = colors.textModelBody

    when (block) {
        is MdBlock.Heading -> {
            val (size, lineHeight, topPad, bottomPad, weight) = when (block.level) {
                1 -> HeadingSpec(23.0f, 27.6f, 6.44f, 2.76f, FontWeight.Bold)
                2 -> HeadingSpec(19.32f, 24.15f, 5.52f, 2.76f, FontWeight.Bold)
                3 -> HeadingSpec(16.56f, 21.53f, 4.60f, 2.30f, FontWeight.SemiBold)
                4 -> HeadingSpec(15.46f, 20.87f, 3.68f, 1.84f, FontWeight.SemiBold)
                5 -> HeadingSpec(14.54f, 20.36f, 3.22f, 1.84f, FontWeight.SemiBold)
                else -> HeadingSpec(13.8f, 19.32f, 2.76f, 1.84f, FontWeight.SemiBold)
            }
            Text(
                text = inlineMarkdown(block.text, bodyColor),
                style = CCMText.body16.copy(
                    fontSize = size.sp,
                    lineHeight = lineHeight.sp,
                    fontWeight = weight,
                    letterSpacing = if (block.level <= 2) (-0.02f * size).sp else 0.sp,
                ),
                color = bodyColor,
                modifier = Modifier.padding(top = topPad.dp, bottom = bottomPad.dp),
            )
        }

        is MdBlock.Paragraph -> {
            Text(
                text = inlineMarkdown(block.text, bodyColor),
                style = CCMText.body16.copy(
                    fontSize = 15.18.sp,
                    lineHeight = 25.81.sp,          // leading-[1.7]
                ),
                color = bodyColor,
                // 【2026-10-06 问题25 修复】原来是 2.3dp（mb-2.5 的近似）——
                // 但 Web 的 `.markdown-body p { margin-bottom: 0.6em }`，
                // 字号 15.18px → **9.1px**。2.3 太小，段落全挤在一起，
                // 这是「markdown 丑陋」的第一来源。
                modifier = Modifier.padding(bottom = 9.1.dp),
            )
        }

        is MdBlock.CodeBlock -> CodeBlockView(block)

        is MdBlock.ListBlock -> {
            Column(modifier = Modifier.padding(bottom = 5.52.dp)) {
                block.items.forEachIndexed { i, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 【2026-10-06 问题25】原来 bottom = 1.84dp —— 列表项
                            // 挤在一起。Web 的 li 有 `line-height: 1.5` 的行高
                            // 自然撑开，这里补 4.6dp（半个行高）更接近 Web 观感。
                            .padding(start = 22.08.dp, bottom = 4.6.dp),
                    ) {
                        // 项目符号：无序 `•`；有序 `1.`；任务列表用方框
                        val bullet = when {
                            item.checked != null -> if (item.checked) "☑" else "☐"
                            block.ordered -> "${i + 1}."
                            else -> "•"
                        }
                        Text(
                            text = bullet,
                            style = CCMText.body16.copy(
                                fontSize = 15.18.sp,
                                lineHeight = 25.81.sp,
                            ),
                            color = bodyColor,
                            modifier = Modifier.width(18.4.dp),
                        )
                        Text(
                            text = inlineMarkdown(item.text, bodyColor),
                            style = CCMText.body16.copy(
                                fontSize = 15.18.sp,
                                lineHeight = 25.81.sp,
                            ),
                            color = bodyColor,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        is MdBlock.Quote -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 5.52.dp),
            ) {
                // 左侧竖线 `border-l-4`
                Box(
                    modifier = Modifier
                        .width(3.68.dp)
                        .background(colors.border),
                )
                Spacer(Modifier.width(11.04.dp))
                Text(
                    text = inlineMarkdown(block.text, bodyColor),
                    style = CCMText.body16.copy(
                        fontSize = 15.18.sp,
                        lineHeight = 25.81.sp,
                    ),
                    color = bodyColor,
                )
            }
        }

        is MdBlock.Divider -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 22.08.dp)       // my-6
                    .height(1.dp)
                    .background(colors.border),
            )
        }

        is MdBlock.Table -> MarkdownTable(block)
    }
}

/** 标题规格（解构用） */
private data class HeadingSpec(
    val size: Float,
    val lineHeight: Float,
    val topPad: Float,
    val bottomPad: Float,
    val weight: FontWeight,
)

/** 代码块 —— `rounded-8 p-1em bg-input border`，头部含语言标签与复制按钮 */
@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val colors = CCMTheme.colors

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 5.52.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(7.36.dp)),
    ) {
        // 头部：语言标签 + 复制（仅当有语言标签或需要复制时）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 11.04.dp, vertical = 5.52.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = block.language.ifEmpty { "text" },
                style = CCMText.body11.copy(fontSize = 11.04.sp),
                color = colors.textSecondary,
            )
            // 【2026-10-06 问题25 修复】原来是个**假按钮** —— 只有文字没有
            // clickable，点了毫无反应（用户报「markdown 丑陋」的一部分）。
            // 现在真的能复制代码。
            val copyCtx = androidx.compose.ui.platform.LocalContext.current
            Text(
                text = "复制",
                style = CCMText.body11.copy(fontSize = 11.04.sp),
                color = colors.textSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable {
                        try {
                            val cm = copyCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("CCM", block.code))
                            android.widget.Toast.makeText(copyCtx, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (_: Throwable) {}
                    }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        // 代码体：等宽、可横向滚动（`overflow-x-auto`）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(14.72.dp),                 // p-1em
        ) {
            Text(
                text = block.code,
                style = CCMText.body14.copy(
                    fontSize = 13.34.sp,
                    lineHeight = 20.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                color = colors.textMain,
            )
        }
    }
}

/** GFM 表格 —— `text-[14.5px]`，横向滚动（`overflow-x-auto my-4`） */
@Composable
private fun MarkdownTable(block: MdBlock.Table) {
    val colors = CCMTheme.colors

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.72.dp)           // my-4
            .horizontalScroll(rememberScrollState()),
    ) {
        Column {
            // 表头：`border-b border-black` + `text-left py-2 pr-4 font-semibold`
            Row(
                modifier = Modifier
                    .background(colors.bgMain)
                    .padding(vertical = 7.36.dp),
            ) {
                block.header.forEach { cell ->
                    Text(
                        text = inlineMarkdown(cell, colors.textModelBody),
                        style = CCMText.body14.copy(
                            fontSize = 13.34.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = colors.textModelBody,
                        modifier = Modifier
                            .width(120.dp)
                            .padding(end = 14.72.dp),
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF000000)))
            block.rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .background(colors.bgMain)
                        .padding(vertical = 7.36.dp),
                ) {
                    row.forEach { cell ->
                        Text(
                            text = inlineMarkdown(cell, colors.textModelBody),
                            style = CCMText.body14.copy(fontSize = 13.34.sp),
                            color = colors.textModelBody,
                            modifier = Modifier
                                .width(120.dp)
                                .padding(end = 14.72.dp),
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF000000)))
            }
        }
    }
}

/**
 * 来源列表 —— 对应 `SourcesList`（源码 131-250 行）。
 *
 * 折叠态显示「N 个来源」，展开后列出序号 + 标题 + 域名。
 */
@Composable
fun MarkdownSourcesList(sources: List<CitationSource>, modifier: Modifier = Modifier) {
    val colors = CCMTheme.colors
    val deduped = remember(sources) {
        val seen = LinkedHashMap<String, CitationSource>()
        sources.forEach { if (!seen.containsKey(it.url)) seen[it.url] = it }
        seen.values.toList()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 11.04.dp)
            .clip(RoundedCornerShape(7.36.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(7.36.dp))
            .padding(11.04.dp),
    ) {
        Text(
            text = "${deduped.size} 个来源",
            style = CCMText.body12.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            color = colors.textMain,
        )
        Spacer(Modifier.height(5.52.dp))
        deduped.forEachIndexed { i, s ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.76.dp),
                horizontalArrangement = Arrangement.spacedBy(5.52.dp),
            ) {
                Text(
                    text = "${i + 1}",
                    style = CCMText.body11.copy(fontSize = 11.04.sp),
                    color = colors.textSecondary,
                )
                Text(
                    text = s.title.ifEmpty { hostOf(s.url) },
                    style = CCMText.body12.copy(fontSize = 12.sp),
                    color = colors.textMain,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 从 URL 取主机名（源码 `getSourceIndex` 的展示部分） */
private fun hostOf(url: String): String =
    url.removePrefix("https://").removePrefix("http://").substringBefore('/')

/** 引用来源 —— 对应 `CitationSource` */
data class CitationSource(
    val url: String,
    val title: String = "",
    val citedText: String? = null,
)

// ── 解析器 ────────────────────────────────────────────────────────

/** 块级元素 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
    data class ListBlock(val ordered: Boolean, val items: List<MdListItem>) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data object Divider : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
}

/** 列表项 —— [checked] 非空表示 GFM 任务项 */
data class MdListItem(val text: String, val checked: Boolean? = null)

/**
 * Markdown 块级解析 —— 单遍扫描，无正则回溯。
 *
 * ⚠️ **代码块优先**：解析器必须先识别 ``` 围栏并把内部整块吞掉，
 * 否则代码里的 `#` / `-` / `|` 会被当成标题、列表、表格解析 ——
 * 这是手写 Markdown 解析最常见的 bug。
 */
internal fun parseMarkdown(src: String): List<MdBlock> {
    val lines = src.replace("\r\n", "\n").split('\n')
    val out = mutableListOf<MdBlock>()
    var i = 0

    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trimEnd()

        // ── 代码围栏（必须最先判）───────────────────────────
        if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
            val fence = if (line.trimStart().startsWith("```")) "```" else "~~~"
            val lang = line.trimStart().removePrefix(fence).trim()
            val body = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                body.add(lines[i])
                i++
            }
            i++                                     // 跳过闭合围栏
            out.add(MdBlock.CodeBlock(lang, body.joinToString("\n")))
            continue
        }

        // ── 空行 ────────────────────────────────────────────
        if (line.isBlank()) { i++; continue }

        // ── 分割线 ──────────────────────────────────────────
        if (line.trim() in listOf("---", "***", "___")) {
            out.add(MdBlock.Divider); i++; continue
        }

        // ── 标题 ────────────────────────────────────────────
        val h = HEADING_LINE_RE.find(line)
        if (h != null) {
            out.add(MdBlock.Heading(h.groupValues[1].length, h.groupValues[2].trim()))
            i++; continue
        }

        // ── 引用 ────────────────────────────────────────────
        if (line.trimStart().startsWith(">")) {
            val buf = mutableListOf<String>()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                buf.add(lines[i].trimStart().removePrefix(">").trim())
                i++
            }
            out.add(MdBlock.Quote(buf.joinToString("\n")))
            continue
        }

        // ── 表格（GFM：表头 + 分隔行 + 数据行）──────────────
        if (line.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
            val header = splitTableRow(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                rows.add(splitTableRow(lines[i]))
                i++
            }
            out.add(MdBlock.Table(header, rows))
            continue
        }

        // ── 列表 ────────────────────────────────────────────
        val ulMatch = UNORDERED_RE.find(line)
        val olMatch = ORDERED_RE.find(line)
        if (ulMatch != null || olMatch != null) {
            val ordered = olMatch != null
            val items = mutableListOf<MdListItem>()
            while (i < lines.size) {
                val l = lines[i]
                val um = UNORDERED_RE.find(l)
                val om = ORDERED_RE.find(l)
                if (um == null && om == null) break
                val text = (um ?: om!!).groupValues[1].trim()
                // GFM 任务项 `- [ ]` / `- [x]`
                val task = Regex("^\\[([ xX])]\\s*(.*)$").find(text)
                items.add(
                    if (task != null) {
                        MdListItem(task.groupValues[2], task.groupValues[1].equals("x", ignoreCase = true))
                    } else {
                        MdListItem(text)
                    },
                )
                i++
            }
            out.add(MdBlock.ListBlock(ordered, items))
            continue
        }

        // ── 段落（连续非空行合并）──────────────────────────
        val buf = mutableListOf<String>()
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank() || isBlockStart(lines, i)) break
            buf.add(l.trim())
            i++
        }
        if (buf.isNotEmpty()) out.add(MdBlock.Paragraph(buf.joinToString("\n")))

        // ── 兜底：无论如何必须推进，否则死循环 ──────────────────
        // 正常路径下上面每个分支都消费了行。这行是**安全网**：
        // 万一将来新增了某个 isBlockStart 条件却忘了加对应分支，
        // 宁可把该行当普通段落显示，也不要整页卡死。
        if (i < lines.size && buf.isEmpty()) {
            out.add(MdBlock.Paragraph(lines[i].trim()))
            i++
        }
    }
    return out
}

/**
 * 该行是否是块级元素的起点（段落扫描的终止条件）。
 *
 * ## ⚠️ 致命约束：每个 `return true` 都必须有**对应分支消费该行**
 * 段落扫描遇到块起点就 break，控制权回到主循环；若主循环没有任何分支
 * 认领这一行，`i` 永不递增 → **无限循环**。
 *
 * 踩过的 bug：这里原本写 `t.startsWith("#")`（只看首字符），
 * 而标题解析要求 `#{1,6}\s+`（`#` 后必须跟空格）。
 * 输入 `#不是标题` 时 → 这里 true、标题分支不匹配、无其他分支认领 → 卡死。
 * **两处的判定条件必须逐字一致**，改一处务必改另一处。
 */
private fun isBlockStart(lines: List<String>, i: Int): Boolean {
    val l = lines[i]
    val t = l.trimStart()
    if (t.startsWith("```") || t.startsWith("~~~")) return true
    if (HEADING_RE.containsMatchIn(t)) return true
    if (t.startsWith(">")) return true
    if (l.trim() in listOf("---", "***", "___")) return true
    if (UNORDERED_RE.containsMatchIn(l)) return true
    if (ORDERED_RE.containsMatchIn(l)) return true
    if (l.contains('|') && i + 1 < lines.size && isTableSeparator(lines[i + 1])) return true
    return false
}

/** 标题：`#`~`######` + 至少一个空格（与 [isBlockStart] 共用，保证判定一致） */
private val HEADING_RE = Regex("^#{1,6}\\s+")

/** 标题行（含捕获组）：`#{1,6}` + 空格 + 内容 */
private val HEADING_LINE_RE = Regex("^(#{1,6})\\s+(.*)$")

/** 无序列表项：`- ` / `* ` / `+ ` */
private val UNORDERED_RE = Regex("^\\s*[-*+]\\s+(.*)$")

/** 有序列表项（含捕获组）：`1. ` / `1) ` */
private val ORDERED_RE = Regex("^\\s*\\d+[.)]\\s+(.*)$")

/** GFM 表格分隔行：`|---|---|` 或 `|:--|--:|` */
private fun isTableSeparator(line: String): Boolean {
    val t = line.trim()
    if (!t.contains('-')) return false
    return Regex("^\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?$").containsMatchIn(t)
}

/** 拆分表格行：去掉首尾管道，按 `|` 切分 */
private fun splitTableRow(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.substring(1)
    if (t.endsWith("|")) t = t.dropLast(1)
    return t.split('|').map { it.trim() }
}

/**
 * 行内 Markdown → AnnotatedString。
 *
 * 支持：`**粗体**` / `*斜体*` / `_斜体_` / `~~删除线~~` / `` `代码` `` / `[文字](url)`。
 *
 * ⚠️ 解析顺序：先扫代码（`）再扫其余 —— 否则 `` `a*b*c` `` 里的 `*` 会被误判成斜体。
 * 这是行内解析最容易错的地方。
 */
internal fun inlineMarkdown(text: String, baseColor: Color): AnnotatedString {
    // 先剥离引用角标 `[N]`（源码 `stripCiteTags`）
    val cleaned = Regex("\\[\\d+]").replace(text, "")

    return buildAnnotatedString {
        var i = 0
        while (i < cleaned.length) {
            when {
                // 行内代码 —— 最高优先级
                cleaned[i] == '`' -> {
                    val end = cleaned.indexOf('`', i + 1)
                    if (end > i) {
                        withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.34.sp,
                                background = Color(0x14000000),
                            ),
                        ) { append(cleaned.substring(i + 1, end)) }
                        i = end + 1
                    } else {
                        append(cleaned[i]); i++
                    }
                }
                // 粗体 `**` 或 `__`
                cleaned.startsWith("**", i) || cleaned.startsWith("__", i) -> {
                    val mark = cleaned.substring(i, i + 2)
                    val end = cleaned.indexOf(mark, i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(cleaned.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(cleaned[i]); i++
                    }
                }
                // 删除线 `~~`
                cleaned.startsWith("~~", i) -> {
                    val end = cleaned.indexOf("~~", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)) {
                            append(cleaned.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(cleaned[i]); i++
                    }
                }
                // 链接 `[文字](url)`
                cleaned[i] == '[' -> {
                    val closeBracket = cleaned.indexOf(']', i)
                    if (closeBracket > i && closeBracket + 1 < cleaned.length && cleaned[closeBracket + 1] == '(') {
                        val closeParen = cleaned.indexOf(')', closeBracket)
                        if (closeParen > closeBracket) {
                            withStyle(
                                SpanStyle(
                                    color = LinkBlue,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                                ),
                            ) { append(cleaned.substring(i + 1, closeBracket)) }
                            i = closeParen + 1
                        } else {
                            append(cleaned[i]); i++
                        }
                    } else {
                        append(cleaned[i]); i++
                    }
                }
                // 斜体 `*` 或 `_`（单字符，排除已处理的双字符）
                cleaned[i] == '*' || cleaned[i] == '_' -> {
                    val mark = cleaned[i]
                    val end = cleaned.indexOf(mark, i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(cleaned.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(cleaned[i]); i++
                    }
                }
                else -> {
                    append(cleaned[i]); i++
                }
            }
        }
    }
}

/** 链接色 `text-blue-500` */
private val LinkBlue = Color(0xFF3B82F6)
