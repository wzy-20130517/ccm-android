package com.ccm.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText

/**
 * 工具调用差异视图 —— 对齐 `ToolDiffView.tsx`（420 行）。
 *
 * ## 四种视图（按工具名分派）
 * | 工具 | 视图 | 说明 |
 * |---|---|---|
 * | `Edit` / `MultiEdit` | [DiffView] | LCS 行级 diff，带新旧行号 |
 * | `Write` | [WriteView] | 全文按行号列出（新增） |
 * | `Bash` | [BashView] | 命令 + 输出两段 |
 * | `Read` | [ReadView] | 文件内容（最多 200 行） |
 *
 * ## ★ diff 算法：LCS（最长公共子序列）
 * 源码用标准 DP 表 + 回溯，复杂度 O(m×n)。
 * **大输入降级**：`m * n > 500000` 时走 [simpleDiff]（全删 + 全加），
 * 避免 DP 表爆内存 —— 这是必要的保护，不是偷懒。
 *
 * 行号规则（源码 `numberedLines`）：
 * - context：新旧行号**都**递增
 * - removed：只递增旧行号
 * - added：只递增新行号
 *
 * ## ★ 配色（亮色，源码写死十六进制）
 * | 项 | 值 |
 * |---|---|
 * | 容器边框 | `#E5E5E5` |
 * | 容器底 | `#FCFCFA` |
 * | 头部底 | `#F5F5F0` |
 * | 文件名 | `#B35C2A`（暖橙） |
 * | 扩展名 | `#999999` |
 * | 新增行底 | `#E6FFEC` / 行号 `#1A7F37` / 内容 `#1A3A1A` |
 * | 删除行底 | `#FFEBE9` / 行号 `#CF222E` / 内容 `#3A1A1A` |
 * | 上下文行号 | `#555555` |
 * | 上下文内容 | `#555555` |
 *
 * 尺寸：`text-[12px]` → **11.04** · `px-3 py-1.5` → 11.04 × 5.52 ·
 * `rounded-md` → **5.52** · `max-h-[400px]` → 400（**不乘 0.92**，源码直接写 px）
 */
@Composable
fun ToolDiffView(
    toolName: String,
    modifier: Modifier = Modifier,
    oldString: String = "",
    newString: String = "",
    filePath: String = "",
    command: String = "",
    output: String = "",
) {
    when (toolName) {
        "Edit", "MultiEdit" -> {
            if (oldString.isEmpty() && newString.isEmpty()) return
            DiffView(oldString = oldString, newString = newString, filePath = filePath, modifier = modifier)
        }
        "Write" -> {
            if (newString.isEmpty()) return
            WriteView(content = newString, filePath = filePath, modifier = modifier)
        }
        "Bash" -> {
            if (command.isEmpty() && output.isEmpty()) return
            BashView(command = command, output = output, modifier = modifier)
        }
        "Read" -> {
            if (output.isEmpty()) return
            ReadView(content = output, filePath = filePath, modifier = modifier)
        }
        else -> Unit
    }
}

/** Edit 差异视图 */
@Composable
private fun DiffView(oldString: String, newString: String, filePath: String, modifier: Modifier) {
    // LCS 计算量大，按内容缓存
    val lines = remember(oldString, newString) {
        numberedDiff(oldString.split('\n'), newString.split('\n'))
    }

    DiffFrame(
        filePath = filePath,
        modifier = modifier,
        maxHeight = 400.dp,
    ) {
        lines.forEach { line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(line.rowBg),
            ) {
                // 旧行号（宽 40）
                LineNumber(line.oldNum, DiffTone.numFg)
                // 新行号
                LineNumber(line.newNum, DiffTone.numFg)
                // 前缀符号
                Box(modifier = Modifier.width(14.72.dp)) {
                    Text(
                        text = line.prefix,
                        style = codeStyle(),
                        color = line.prefixFg,
                    )
                }
                // 内容
                Text(
                    text = line.content,
                    style = codeStyle(),
                    color = line.contentFg,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Write 视图 —— 全文按行号列出，全部算「新增」 */
@Composable
private fun WriteView(content: String, filePath: String, modifier: Modifier) {
    val lines = remember(content) { content.split('\n') }
    DiffFrame(filePath = filePath, modifier = modifier, maxHeight = 400.dp) {
        lines.forEachIndexed { i, line ->
            Row(modifier = Modifier.fillMaxWidth().background(DiffTone.addedBg)) {
                LineNumber(null, DiffTone.numFg)
                LineNumber(i + 1, DiffTone.addedNum)
                Box(modifier = Modifier.width(14.72.dp)) {
                    Text("+", style = codeStyle(), color = DiffTone.addedNum)
                }
                Text(line, style = codeStyle(), color = DiffTone.addedFg, maxLines = 1)
            }
        }
    }
}

/** Bash 视图 —— 命令 + 输出两段 */
@Composable
private fun BashView(command: String, output: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.52.dp))
            .background(DiffTone.bg)
            .border(1.dp, DiffTone.border, RoundedCornerShape(5.52.dp)),
    ) {
        // 命令段：`$ 命令`
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DiffTone.headerBg)
                .padding(horizontal = 11.04.dp, vertical = 5.52.dp),
            horizontalArrangement = Arrangement.spacedBy(5.52.dp),
        ) {
            Text("$", style = codeStyle(), color = DiffTone.fileNameFg)
            Text(
                text = command,
                style = codeStyle(),
                color = DiffTone.contextFg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 输出段
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .verticalScroll(rememberScrollState())
                .padding(11.04.dp),
        ) {
            Text(
                text = output.ifEmpty { "（无输出）" },
                style = codeStyle(),
                color = DiffTone.contextFg,
            )
        }
    }
}

/** Read 视图 —— 最多 200 行，超出提示截断 */
@Composable
private fun ReadView(content: String, filePath: String, modifier: Modifier) {
    val all = remember(content) { content.split('\n') }
    val truncated = all.size > 200
    val shown = if (truncated) all.take(200) else all

    DiffFrame(filePath = filePath, modifier = modifier, maxHeight = 400.dp) {
        shown.forEachIndexed { i, line ->
            Row(modifier = Modifier.fillMaxWidth()) {
                LineNumber(i + 1, DiffTone.numFg)
                Text(
                    text = line,
                    style = codeStyle(),
                    color = DiffTone.contextFg,
                    maxLines = 1,
                )
            }
        }
        if (truncated) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DiffTone.headerBg)
                    .padding(horizontal = 11.04.dp, vertical = 5.52.dp),
            ) {
                Text(
                    text = "… 已截断，仅显示前 200 行（共 ${all.size} 行）",
                    style = codeStyle(),
                    color = DiffTone.extFg,
                )
            }
        }
    }
}

/** 差异视图外壳（头部 + 可滚动主体）—— 对应源码公共结构 */
@Composable
private fun DiffFrame(
    filePath: String,
    modifier: Modifier,
    maxHeight: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    val fileName = filePath.split('/', '\\').lastOrNull() ?: filePath
    val ext = getFileExtension(filePath)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.52.dp))
            .background(DiffTone.bg)
            .border(1.dp, DiffTone.border, RoundedCornerShape(5.52.dp)),
    ) {
        // 头部：文件名 + 扩展名 + 复制
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(DiffTone.headerBg)
                .padding(horizontal = 11.04.dp, vertical = 5.52.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fileName,
                    style = codeStyle(),
                    color = DiffTone.fileNameFg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ext.isNotEmpty()) {
                    Text(text = ext, style = codeStyle(), color = DiffTone.extFg)
                }
            }
            Text(text = "复制", style = codeStyle(), color = DiffTone.extFg)
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(DiffTone.border))

        // 主体：横向 + 纵向滚动（源码 `overflow-x-auto max-h-[400px] overflow-y-auto`）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 5.52.dp),
            ) {
                content()
            }
        }
    }
}

/** 行号列（宽 40，右对齐）—— 源码 `<td className="w-10 text-right pr-2">` */
@Composable
private fun LineNumber(num: Int?, color: Color) {
    Box(
        modifier = Modifier
            .width(36.8.dp)                          // w-10 = 40 × 0.92
            .padding(end = 7.36.dp),                 // pr-2
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = num?.toString() ?: "",
            style = codeStyle(),
            color = color,
        )
    }
}

/** 代码文字样式（`text-[12px] font-mono` → 11.04 / 等宽） */
@Composable
private fun codeStyle() = CCMText.body12.copy(
    fontSize = 11.04.sp,
    lineHeight = 16.sp,
    fontFamily = FontFamily.Monospace,
)

/** 带行号与配色的 diff 行 */
internal data class NumberedDiffLine(
    val type: DiffLineType,
    val content: String,
    val oldNum: Int?,
    val newNum: Int?,
) {
    val prefix: String get() = when (type) {
        DiffLineType.ADDED -> "+"
        DiffLineType.REMOVED -> "-"
        DiffLineType.CONTEXT -> " "
    }

    val rowBg: Color get() = when (type) {
        DiffLineType.ADDED -> DiffTone.addedBg
        DiffLineType.REMOVED -> DiffTone.removedBg
        DiffLineType.CONTEXT -> Color.Transparent
    }

    val prefixFg: Color get() = when (type) {
        DiffLineType.ADDED -> DiffTone.addedNum
        DiffLineType.REMOVED -> DiffTone.removedNum
        DiffLineType.CONTEXT -> DiffTone.numFg
    }

    val contentFg: Color get() = when (type) {
        DiffLineType.ADDED -> DiffTone.addedFg
        DiffLineType.REMOVED -> DiffTone.removedFg
        DiffLineType.CONTEXT -> DiffTone.contextFg
    }
}

/** 行类型 —— 对应源码 `DiffLine.type` */
enum class DiffLineType { ADDED, REMOVED, CONTEXT }

/** 原始 diff 行 */
internal data class DiffLine(val type: DiffLineType, val content: String)

/**
 * 计算 diff 并编号 —— 对应源码 `computeDiff` + `numberedLines`。
 *
 * ⚠️ 降级阈值 `m * n > 500000` 必须保留：DP 表是 O(m×n) 空间，
 * 两个 1000 行的文件就是 100 万格（约 8MB），手机上会 OOM。
 */
internal fun numberedDiff(oldLines: List<String>, newLines: List<String>): List<NumberedDiffLine> {
    val raw = computeDiff(oldLines, newLines)
    var oldNum = 1
    var newNum = 1
    return raw.map { line ->
        when (line.type) {
            DiffLineType.CONTEXT -> NumberedDiffLine(line.type, line.content, oldNum++, newNum++)
            DiffLineType.REMOVED -> NumberedDiffLine(line.type, line.content, oldNum++, null)
            DiffLineType.ADDED -> NumberedDiffLine(line.type, line.content, null, newNum++)
        }
    }
}

/** LCS diff —— 对应源码 `computeDiff` */
internal fun computeDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine> {
    val m = oldLines.size
    val n = newLines.size

    // 大输入降级（防止 DP 表爆内存）
    if (m.toLong() * n.toLong() > 500_000L) return simpleDiff(oldLines, newLines)

    // LCS 表
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    // 回溯
    val stack = ArrayDeque<DiffLine>()
    var i = m
    var j = n
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
            stack.addFirst(DiffLine(DiffLineType.CONTEXT, oldLines[i - 1]))
            i--; j--
        } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
            stack.addFirst(DiffLine(DiffLineType.ADDED, newLines[j - 1]))
            j--
        } else {
            stack.addFirst(DiffLine(DiffLineType.REMOVED, oldLines[i - 1]))
            i--
        }
    }
    return stack.toList()
}

/** 降级方案：全删 + 全加 —— 对应源码 `simpleDiff` */
internal fun simpleDiff(oldLines: List<String>, newLines: List<String>): List<DiffLine> =
    oldLines.map { DiffLine(DiffLineType.REMOVED, it) } +
        newLines.map { DiffLine(DiffLineType.ADDED, it) }

/** 取文件扩展名（不含点）—— 对应源码 `getFileExtension` */
internal fun getFileExtension(filePath: String): String {
    val name = filePath.split('/', '\\').lastOrNull() ?: return ""
    val idx = name.lastIndexOf('.')
    return if (idx > 0 && idx < name.length - 1) name.substring(idx + 1) else ""
}

/**
 * diff 视图配色 —— 源码写死十六进制（亮色主题）。
 *
 * | 用途 | 值 |
 * |---|---|
 * | 容器边框 | `#E5E5E5` |
 * | 容器底 | `#FCFCFA` |
 * | 头部底 | `#F5F5F0` |
 * | 文件名 | `#B35C2A` |
 * | 扩展名 | `#999999` |
 * | 新增底/行号/文字 | `#E6FFEC` / `#1A7F37` / `#1A3A1A` |
 * | 删除底/行号/文字 | `#FFEBE9` / `#CF222E` / `#3A1A1A` |
 * | 上下文行号/文字 | `#555555` |
 */
object DiffTone {
    val border = Color(0xFFE5E5E5)
    val bg = Color(0xFFFCFCFA)
    val headerBg = Color(0xFFF5F5F0)
    val fileNameFg = Color(0xFFB35C2A)
    val extFg = Color(0xFF999999)
    val numFg = Color(0xFF555555)
    val contextFg = Color(0xFF555555)

    val addedBg = Color(0xFFE6FFEC)
    val addedNum = Color(0xFF1A7F37)
    val addedFg = Color(0xFF1A3A1A)

    val removedBg = Color(0xFFFFEBE9)
    val removedNum = Color(0xFFCF222E)
    val removedFg = Color(0xFF3A1A1A)
}
