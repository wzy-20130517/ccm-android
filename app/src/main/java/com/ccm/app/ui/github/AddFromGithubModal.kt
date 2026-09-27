package com.ccm.app.ui.github

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 从 GitHub 添加文件 —— 对齐 `AddFromGithubModal.tsx`（602 行）。
 *
 * ## 结构
 * ```
 * 遮罩 fixed inset-0 z-[200] bg-black/40 backdrop-blur-2px
 * └─ 卡片 w-[min(720px,92vw)] h-[min(680px,88vh)] rounded-2xl
 *     ├─ 头部 px-5 pt-5 pb-3：标题 15 semibold + 副标题 12 + 关闭按钮
 *     ├─ 仓库选择行 px-5 pb-3：仓库下拉（min-w-180 / py-1.5 / text-13）+ 粘贴按钮
 *     ├─ 主体 mx-5 mb-3 rounded-xl border bg-input（flex-1）
 *     │   ├─ 未连接 → 居中：提示 + 「连接 GitHub」按钮
 *     │   ├─ 空目录 → 居中：「此文件夹为空」
 *     │   └─ 列表 → 面包屑 + 条目行（文件夹 / 文件 + 勾选框）
 *     └─ 底部 px-5：已选 N 项 · 大小 + 「添加」按钮
 * ```
 *
 * ## ★ 关键尺寸（**不乘 0.92**）
 * 该弹窗在 `fixed z-[200]` 层 —— 与 DirectoryModal 同理，不受 `#root` 的 zoom 影响。
 * `w-[min(720px,92vw)]` 在 393px 屏上：92vw = **361.6**（比 720 小，取小值）。
 *
 * ## ★ 排序规则（源码 `loadPath`，别改）
 * 目录在前、文件在后；同类型按 `localeCompare` 字典序：
 * ```js
 * if (a.type !== b.type) return a.type === 'dir' ? -1 : 1;
 * return a.name.localeCompare(b.name);
 * ```
 * 这与「纯按名字排」在混合类型时顺序**完全不同**，是用户找文件夹的主要依据。
 *
 * ## ★ 文件夹大小是**算出来的**，不是接口给的
 * GitHub contents API 对目录不返回 size。源码遍历已加载的整棵树，
 * 把该前缀下所有 blob 的 size 累加（`getFolderSize`）。
 * 所以树没加载完时文件夹显示 0 —— 这是源码行为，不是 bug。
 */
@Composable
fun AddFromGithubModal(
    modifier: Modifier = Modifier,
    connected: Boolean? = true,
    entries: List<GhEntry> = emptyList(),
    breadcrumb: String = "",
    selectedCount: Int = 0,
    selectedSize: Long = 0L,
    confirming: Boolean = false,
    errorMessage: String? = null,
    onClose: () -> Unit = {},
    onConnect: () -> Unit = {},
    onConfirm: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
    onToggleEntry: (GhEntry) -> Unit = {},
) {
    val colors = CCMTheme.colors

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x66000000))          // bg-black/40
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(361.6.dp)                    // min(720, 92vw) @393 → 92vw
                .heightIn(max = 680.dp)             // h-[min(680px,88vh)]
                .clip(RoundedCornerShape(16.dp))    // rounded-2xl（fixed 层不乘 0.92）
                .background(colors.bgMain)
                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                .clickable { }                      // 阻止冒泡
                .padding(vertical = 20.dp),
        ) {
            // ── 头部 px-5 pt-5 pb-3 ─────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "从 GitHub 添加",
                        style = CCMText.body16.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = colors.textMain,
                    )
                    Spacer(Modifier.height(2.dp))   // mt-0.5
                    Text(
                        text = "选择仓库中的文件或文件夹加入上下文",
                        style = CCMText.body12.copy(fontSize = 12.sp),
                        color = colors.textSecondary,
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onClose)
                        .padding(4.dp),             // p-1
                    contentAlignment = Alignment.Center,
                ) {
                    CloseGlyph(color = colors.textSecondary, size = 16.dp)
                }
            }

            // ── 仓库选择行 px-5 pb-3 ────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RepoGlyph(color = colors.textMain, size = 16.dp)
                Row(
                    modifier = Modifier
                        .height(31.3.dp)            // py-1.5 + text-13
                        .clip(RoundedCornerShape(7.36.dp))
                        .background(colors.input)
                        .border(1.dp, colors.border, RoundedCornerShape(7.36.dp))
                        .padding(horizontal = 11.04.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "选择仓库",
                        style = CCMText.body13.copy(fontSize = 13.sp),
                        color = colors.textMain,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(160.dp),
                    )
                    ChevronDownGlyph(color = colors.textSecondary, size = 12.dp)
                }
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.36.dp))
                        .clickable { }
                        .padding(6.dp),             // p-1.5
                ) {
                    PasteGlyph(color = colors.textSecondary, size = 15.dp)
                }
            }

            // ── 主体 mx-5 mb-3 rounded-xl border bg-input ───────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 200.dp, max = 460.dp)
                    .clip(RoundedCornerShape(11.04.dp))
                    .background(colors.input)
                    .border(1.dp, colors.border, RoundedCornerShape(11.04.dp)),
            ) {
                when {
                    connected == null -> CenterNote("正在检测 GitHub 连接…")
                    connected == false -> NotConnectedPane(onConnect = onConnect)
                    entries.isEmpty() -> CenterNote("此文件夹为空")
                    else -> EntryList(
                        breadcrumb = breadcrumb,
                        entries = entries,
                        onNavigate = onNavigate,
                        onToggleEntry = onToggleEntry,
                    )
                }
            }

            // 错误条
            if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(7.36.dp))
                        .background(Color(0xFFFEF2F2))
                        .padding(8.dp),
                ) {
                    Text(
                        text = errorMessage,
                        style = CCMText.body12.copy(fontSize = 12.sp),
                        color = Color(0xFFDC2626),
                    )
                }
            }

            // ── 底部 px-5 ───────────────────────────────────────
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selectedCount == 0) {
                        "未选择任何文件"
                    } else {
                        "已选 $selectedCount 项 · ${formatSize(selectedSize)}"
                    },
                    style = CCMText.body12.copy(fontSize = 12.sp),
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.36.dp))
                        .background(if (selectedCount > 0) Color(0xFF000000) else Color(0x33000000))
                        .clickable(enabled = selectedCount > 0 && !confirming, onClick = onConfirm)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = if (confirming) "添加中…" else "添加",
                        style = CCMText.body13.copy(fontSize = 13.sp),
                        color = Color.White,
                    )
                }
            }
        }
    }
}

/** 居中提示 —— 对应 `flex-1 flex items-center justify-center px-6 text-center` */
@Composable
private fun CenterNote(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = CCMText.body13.copy(fontSize = 13.sp),
            color = CCMTheme.colors.textSecondary,
        )
    }
}

/** 未连接面板 —— 对应 `flex-1 flex flex-col items-center justify-center gap-3 p-6` */
@Composable
private fun NotConnectedPane(onConnect: () -> Unit) {
    val colors = CCMTheme.colors
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RepoGlyph(color = colors.textSecondary, size = 32.dp)
        Spacer(Modifier.height(12.dp))              // gap-3
        Text(
            text = "尚未连接 GitHub",
            style = CCMText.body13.copy(fontSize = 13.sp),
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(7.36.dp))
                .background(Color(0xFF000000))
                .clickable(onClick = onConnect)
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                text = "连接 GitHub",
                style = CCMText.body13.copy(fontSize = 13.sp),
                color = Color.White,
            )
        }
    }
}

/** 条目列表 —— 含面包屑 + 行 */
@Composable
private fun EntryList(
    breadcrumb: String,
    entries: List<GhEntry>,
    onNavigate: (String) -> Unit,
    onToggleEntry: (GhEntry) -> Unit,
) {
    val colors = CCMTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        // 面包屑（非空时显示）
        if (breadcrumb.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.72.dp, vertical = 7.36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "根目录",
                    style = CCMText.body12.copy(fontSize = 12.sp),
                    color = colors.textSecondary,
                    modifier = Modifier.clickable { onNavigate("") },
                )
                breadcrumb.split('/').filter { it.isNotEmpty() }.forEach { seg ->
                    Text(
                        text = "/",
                        style = CCMText.body12.copy(fontSize = 12.sp),
                        color = colors.textSecondary,
                    )
                    Text(
                        text = seg,
                        style = CCMText.body12.copy(fontSize = 12.sp),
                        color = colors.textMain,
                    )
                }
            }
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        }

        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            entries.forEach { entry ->
                GhEntryRow(
                    entry = entry,
                    onNavigate = onNavigate,
                    onToggle = { onToggleEntry(entry) },
                )
            }
        }
    }
}

/** 单条目行 —— `w-full text-left px-4 py-2 text-[13px]` */
@Composable
private fun GhEntryRow(entry: GhEntry, onNavigate: (String) -> Unit, onToggle: () -> Unit) {
    val colors = CCMTheme.colors
    val isDir = entry.type == GhEntryType.DIR

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (isDir) onNavigate(entry.path) else onToggle() }
            .padding(horizontal = 14.72.dp, vertical = 7.36.dp),   // px-4 py-2
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 勾选框：目录也能选（源码支持整目录添加）
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (entry.selected) colors.accent else Color.Transparent)
                .border(
                    width = 1.dp,
                    color = if (entry.selected) colors.accent else colors.border,
                    shape = RoundedCornerShape(3.dp),
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.selected) {
                CheckGlyph(color = Color.White, size = 10.dp)
            }
        }

        if (isDir) {
            FolderGlyph(color = colors.textSecondary, size = 15.dp)
        } else {
            FileGlyph(color = colors.textSecondary, size = 15.dp)
        }

        Text(
            text = entry.name,
            style = CCMText.body13.copy(fontSize = 13.sp),
            color = colors.textMain,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = if (isDir) "${entry.childCount} 项" else formatSize(entry.size),
            style = CCMText.body11.copy(fontSize = 11.sp),
            color = colors.textSecondary,
        )
    }
}

/** 字节数格式化 —— 对应源码 `formatSize` */
internal fun formatSize(n: Long): String = when {
    n >= 1024L * 1024 -> "%.1f MB".format(n / 1024.0 / 1024.0)
    n >= 1024L -> "%.1f KB".format(n / 1024.0)
    else -> "$n B"
}

/** 条目类别 */
enum class GhEntryType { DIR, FILE }

/** 条目 —— 对应源码 `GhEntry` */
data class GhEntry(
    val name: String,
    val path: String,
    val type: GhEntryType,
    val size: Long = 0L,
    val childCount: Int = 0,
    val selected: Boolean = false,
)

// ── 图标（Canvas 手绘）────────────────────────────────────────────

@Composable
private fun CloseGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val sw = 1.5.dp.toPx()
        drawLine(color, Offset(w * 0.25f, h * 0.25f), Offset(w * 0.75f, h * 0.75f), sw, StrokeCap.Round)
        drawLine(color, Offset(w * 0.75f, h * 0.25f), Offset(w * 0.25f, h * 0.75f), sw, StrokeCap.Round)
    }
}

@Composable
private fun ChevronDownGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.25f, h * 0.4f)
            lineTo(w * 0.5f, h * 0.65f)
            lineTo(w * 0.75f, h * 0.4f)
        }
        drawPath(p, color, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** GitHub 标记（圆 + 内点的简化近似） */
@Composable
private fun RepoGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawCircle(color, radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(color, radius = w * 0.14f, center = Offset(w * 0.5f, h * 0.55f))
    }
}

/** 粘贴（剪贴板）图标 */
@Composable
private fun PasteGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round)
        drawRect(
            color,
            topLeft = Offset(w * 0.2f, h * 0.2f),
            size = androidx.compose.ui.geometry.Size(w * 0.6f, h * 0.7f),
            style = s,
        )
        drawLine(color, Offset(w * 0.35f, h * 0.2f), Offset(w * 0.65f, h * 0.2f), s.width, StrokeCap.Round)
    }
}

/** 文件夹 */
@Composable
private fun FolderGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.1f, h * 0.25f)
            lineTo(w * 0.42f, h * 0.25f)
            lineTo(w * 0.5f, h * 0.38f)
            lineTo(w * 0.9f, h * 0.38f)
            lineTo(w * 0.9f, h * 0.8f)
            lineTo(w * 0.1f, h * 0.8f)
            close()
        }
        drawPath(p, color, style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 文件 */
@Composable
private fun FileGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.25f, h * 0.15f)
            lineTo(w * 0.6f, h * 0.15f)
            lineTo(w * 0.75f, h * 0.32f)
            lineTo(w * 0.75f, h * 0.85f)
            lineTo(w * 0.25f, h * 0.85f)
            close()
        }
        drawPath(p, color, style = Stroke(width = 1.3.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** 勾选 */
@Composable
private fun CheckGlyph(color: Color, size: Dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = Path().apply {
            moveTo(w * 0.15f, h * 0.5f)
            lineTo(w * 0.4f, h * 0.75f)
            lineTo(w * 0.85f, h * 0.25f)
        }
        drawPath(p, color, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
    }
}
