package com.ccm.app.ui.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.common.PainterIcon
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 工具调用卡片 —— 对齐 Web `MainContent.tsx:1240-1300`。
 *
 * ## 实测结构（源码 + 截图）
 * ```
 * ┌─ 外层：bg-black/5 dark:bg-black/20 · rounded-lg · border-black/5 ─┐
 * │ 头部（可点折叠）px-3 py-2                                        │
 * │   ├─ 图标：Bash 用 `>_` 等宽字，其他用 FileText（14）             │
 * │   ├─ 预览：font-mono text-[12px] truncate                        │
 * │   │        格式：`Read a.mjs` / `Bash npm test`                  │
 * │   └─ 右侧：+N/-N 统计 · Running... · Failed · ChevronDown         │
 * └──────────────────────────────────────────────────────────────────┘
 * 展开态：mt-2 ml-1 pl-4 border-l-2（左边框竖线）+ 结果区
 * ```
 *
 * ## 状态映射（源码 `realStatus`）
 * | 状态 | 图标 | 文字 |
 * |---|---|---|
 * | running | FileText + `animate-pulse` | `Running...`（shimmer） |
 * | 完成 | Check | 无 |
 * | 错误 | 红色 `✗` | `Failed` |
 * | canceled（stale） | 同完成 | 无 |
 *
 * ## 折叠逻辑（源码）
 * ```js
 * 展开 = msg.isToolCallsExpanded ?? (isCurrentlyStreaming || !allDone)
 * ```
 * 即：**流式中或未跑完时默认展开**，跑完后默认收起。
 *
 * ## 预览格式（源码 `inputPreview` 的拼法）
 * ```js
 * actionLabel = { Read:'Read', Write:'Write', Edit:'Edit', Bash:'', Grep:'Search', Glob:'Find' }
 * prefix = actionLabel[name] ?? name
 * fileOrCmd = shortPath || command || inputStr.slice(0,80)
 * preview = prefix + ' ' + fileOrCmd
 * ```
 * > ⚠️ dev-core 的 `ToolCard.preview` 是**另一种格式**（`path="a.mjs", limit=50`）。
 * > 两者都可用，这里**优先用 Web 格式**（截图对齐），
 * > 若 `preview` 非空且想用 dev-core 的格式，传 `useAgentPreview = true`。
 *
 * @param card           工具卡片数据
 * @param defaultExpanded 默认是否展开（`null` = 用「流式中或未完成时展开」规则）
 */
@Composable
fun ToolCard(
    card: ChatToolCard,
    modifier: Modifier = Modifier,
    defaultExpanded: Boolean? = null,
    useAgentPreview: Boolean = false,
) {
    val colors = CCMTheme.colors

    // 展开态：默认按 Web 规则（跑完收起，未跑完展开）
    var expanded by remember(card.id) {
        mutableStateOf(defaultExpanded ?: (card.running || card.result.isBlank()))
    }

    val isRunning = card.running
    val isError = card.isError

    // 卡片底色 —— Web: bg-black/5 dark:bg-black/20
    val cardBg = if (CCMTheme.isDark) Color.Black.copy(alpha = 0.20f) else Color.Black.copy(alpha = 0.05f)
    val cardBorder = if (CCMTheme.isDark) Color.White.copy(alpha = 0.05f) else Color.Black.copy(alpha = 0.05f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)                 // mx-1
            .clip(RoundedCornerShape(7.36.dp))          // rounded-lg
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(7.36.dp)),
    ) {
        // ── 头部（可点折叠）px-3 py-2 ────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 11.04.dp, vertical = 7.36.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左：图标 + 预览
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.36.dp),
            ) {
                if (card.name == "Bash") {
                    // Bash 用 `>_` 等宽符号（Web: font-mono font-bold）
                    Text(
                        text = ">_",
                        style = CCMText.body13.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = colors.textSecondary,
                    )
                } else {
                    // 其他工具用 FileText 图标
                    ToolStatusIcon(isRunning = isRunning, isError = isError)
                }

                Text(
                    text = toolPreview(card, useAgentPreview),
                    // font-mono text-[12px]
                    style = CCMText.body12.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.04.sp,
                    ),
                    color = colors.textMain,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 右：状态
            Row(
                modifier = Modifier.padding(start = 14.72.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.36.dp),
            ) {
                when {
                    // ★ 2026-10-01：Running... 静态文字 → shimmer 扫光
                    //   （对齐 Web `animate-shimmer-text`；TextShimmer 已在
                    //    AssistantThinkingChain.kt 里实现，复用而不是重写）
                    isRunning -> TextShimmer(
                        text = "Running...",
                        style = CCMText.body12,
                    )
                    isError -> Text(
                        text = "Failed",
                        style = CCMText.body12,
                        color = Color(0xFFF87171),      // text-red-400/80
                    )
                }

                // ChevronDown（展开时转 180°）
                PainterIcon(
                    R.drawable.ic_chevron_down,
                    size = 12.88.dp,
                    tint = colors.textSecondary,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f),
                )
            }
        }

        // ── 展开态：左边框竖线 + 结果 ────────────────────────────────
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 7.36.dp),
            ) {
                // 左侧竖线 —— Web: border-l-2 border-claude-border
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .heightIn(min = 20.dp)
                        .background(colors.border),
                )
                Spacer(Modifier.width(14.72.dp))        // pl-4

                Column(modifier = Modifier.weight(1f)) {
                    // ★ 工具入参可视化（2026-09-27）：Edit 显示 diff、
                    //   Bash 显示命令、Read/Write 显示文件内容。
                    //   之前 467 行的 ToolDiffView 写好了零调用。
                    //   input 空（老事件/非标准工具）→ 组件内部 return，退化为原样。
                    if (card.input.isNotBlank()) {
                        ToolDiffView(
                            toolName = card.name,
                            oldString = inputField(card.input, "old_string"),
                            newString = inputField(card.input, "new_string"),
                            filePath = inputField(card.input, "file_path"),
                            command = inputField(card.input, "command"),
                            // Bash/Read 的内容来自执行结果，不是入参
                            output = if (card.name == "Bash" || card.name == "Read") card.result else "",
                        )
                        Spacer(Modifier.height(5.52.dp))
                    }

                    // 进度（覆盖式 —— 只显示最新一行）
                    if (isRunning && card.progress.isNotBlank()) {
                        Text(
                            text = card.progress,
                            style = CCMText.body12.copy(fontFamily = FontFamily.Monospace),
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(5.52.dp))
                    }

                    // 结果区 —— Web: font-mono text-[12px] max-h-[400px] 可滚动
                    if (card.result.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 368.dp)         // max-h-[400px] × 0.92
                                .clip(RoundedCornerShape(5.52.dp))
                                .background(
                                    if (CCMTheme.isDark) Color.Black.copy(alpha = 0.4f)
                                    else Color.Black.copy(alpha = 0.05f),
                                )
                                .padding(7.36.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            Text(
                                text = card.result,
                                style = CCMText.body12.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.04.sp,
                                    lineHeight = 16.56.sp,
                                ),
                                color = colors.textSecondary,
                            )
                        }
                    } else if (!isRunning) {
                        Text(
                            text = "(Empty output)",
                            style = CCMText.body12.copy(fontFamily = FontFamily.Monospace),
                            color = colors.textSecondary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 工具状态图标 —— running 时用脉冲效果，完成用勾，错误用 ✗。
 *
 * Web 用 lucide 的 FileText / Check + `animate-pulse`。
 * Compose 侧：running 用 alpha 呼吸动画近似 pulse。
 */
@Composable
private fun ToolStatusIcon(isRunning: Boolean, isError: Boolean) {
    val colors = CCMTheme.colors

    when {
        isError -> Text(
            text = "✗",
            style = CCMText.body13,
            color = Color(0xFFF87171),
        )
        isRunning -> {
            // animate-pulse 近似：alpha 在 0.4~1.0 之间循环
            val transition = rememberInfiniteTransition(label = "pulse")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulseAlpha",
            )
            // ★ 2026-10-01：▤ 文字符号 → 真 FileText 矢量图标
            //   （lucide FileText，Web 同款；文字符号在 14px 下辨识度差）
            PainterIcon(
                R.drawable.ic_file_text,
                size = 16.dp,
                tint = colors.textSecondary.copy(alpha = alpha),
            )
        }
        else -> PainterIcon(
            R.drawable.ic_check,
            size = 14.72.dp,
            tint = colors.textSecondary,
        )
    }
}

/**
 * 生成预览文字 —— 对齐 Web 的 `inputPreview` 拼法。
 *
 * ```
 * prefix = actionLabel[name] ?? name
 * fileOrCmd = shortPath || command || inputStr.slice(0,80)
 * preview = "$prefix $fileOrCmd"
 * ```
 *
 * @param useAgentPreview `true` 时直接用 dev-core 给的 `card.preview`
 *                        （格式为 `path="a.mjs", limit=50`）
 */
private fun toolPreview(card: ChatToolCard, useAgentPreview: Boolean): String {
    if (useAgentPreview && card.preview.isNotBlank()) return card.preview
    if (card.preview.isNotBlank() && card.displayName.isBlank()) return card.preview
    return card.displayName.ifBlank { card.name }
}

/** 工具动作标签 —— 对应 Web 的 `actionLabel` 映射表 */
private val ToolActionLabel = mapOf(
    "Read" to "Read",
    "Write" to "Write",
    "Edit" to "Edit",
    "MultiEdit" to "Edit",
    "Bash" to "",
    "Grep" to "Search",
    "Glob" to "Find",
    "ListDir" to "List",
    "Skill" to "Skill",
)

/**
 * 生成 Web 风格的预览 —— `Read a.mjs` / `Bash npm test`。
 *
 * 与 [toolPreview] 的区别：这个需要原始 input（JSON），
 * 而 [ChatToolCard] 只有 dev-core 格式化好的 `preview`。
 * 保留此函数供将来接上原始 input 时使用。
 */
fun buildWebStylePreview(toolName: String, filePath: String?, command: String?, rawInput: String?): String {
    val prefix = ToolActionLabel[toolName] ?: toolName
    val shortPath = filePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: ""
    val fileOrCmd = shortPath.ifBlank {
        command ?: rawInput?.take(80)?.let { if (rawInput.length > 80) "$it..." else it } ?: ""
    }
    return listOf(prefix, fileOrCmd).filter { it.isNotBlank() }.joinToString(" ")
}


/**
 * 从工具入参 JSON 里取一个字符串字段（ToolDiffView 用）。
 *
 * 解析失败返回空串 —— diff 组件对空字段直接 return，退化为原结果展示，
 * 不会因为一条畸形入参崩掉整个卡片。
 */
private fun inputField(json: String, key: String): String = try {
    val obj = kotlinx.serialization.json.Json.parseToJsonElement(json)
        as? kotlinx.serialization.json.JsonObject ?: return ""
    (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
} catch (_: Throwable) {
    ""
}
