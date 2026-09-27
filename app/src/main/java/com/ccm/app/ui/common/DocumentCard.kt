package com.ccm.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.R
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 文档卡片 —— 对齐 `web/src/components/DocumentCard.tsx`。
 *
 * ## ★ 移动端不渲染预览图
 * Web 的预览卡片用了 `absolute top-0 hidden h-full overflow-hidden md:block`：
 * **`hidden md:block`** = `<768px` 完全不渲染。
 * 所以 Compose（锁定移动端）**不需要实现** `DocumentPreviewCard` ——
 * 那段 100 行的旋转预览卡（`rotate-[5deg]`、5px 字号、`#C5C4C3` 边框）
 * 在手机上根本不可见。
 *
 * ## 实测尺寸（MEASURED.md + 源码）
 * | 属性 | Web | 屏幕值 |
 * |---|---|---|
 * | 高度 | `h-[82px]` | **75.44dp** |
 * | 圆角 | `rounded-[12px]`（无移动端覆盖） | **11.04dp** |
 * | 边框 | `border-claude-border` | 1dp |
 * | 内边距 | `px-[16px]` | **14.72dp** |
 * | 标题 | `text-[14px] leading-[1.6]` | 11.39 / 18.22 |
 * | 副标题 | `text-[14px] text-claude-textSecondary` | 同色 textSecondary |
 *
 * ## 交互
 * - 整卡可点 → 打开文档
 * - hover 时右上角出现下载按钮（**手机无 hover**，Web 用 `opacity-0 group-hover:opacity-100`）
 *   → Compose 侧改为**常显**（手机没有 hover，藏起来就点不到了）
 *
 * @param title    文档标题（Web 取 `document.title`）
 * @param subtitle 副标题（`Document · MARKDOWN` 这种，用 [DocumentMeta] 生成）
 * @param onOpen   点击卡片
 * @param onDownload 点击下载（`null` = 不显示下载按钮）
 */
@Composable
fun DocumentCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit = {},
    onDownload: (() -> Unit)? = null,
) {
    val colors = CCMTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(75.44.dp)                        // h-[82px] × 0.92
            .clip(RoundedCornerShape(11.04.dp))      // rounded-[12px] × 0.92
            .border(1.dp, colors.border, RoundedCornerShape(11.04.dp))
            .background(Color.Transparent)
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.72.dp),         // px-[16px] × 0.92
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ── 文字区 ────────────────────────────────────────────────────
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                // Web: text-[14px] leading-[1.6] → 11.39 × 1.6 = 18.22 行高
                style = CCMText.body14.copy(lineHeight = 18.22.sp),
                color = colors.textMain,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = CCMText.body14,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // ── 下载按钮 ──────────────────────────────────────────────────
        // Web: absolute right-[6px] opacity-0 group-hover:opacity-100
        // 手机无 hover → 常显
        if (onDownload != null) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.36.dp))   // rounded-lg
                    .clickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                PainterIcon(
                    R.drawable.ic_download,
                    size = 14.72.dp,                      // size={16} × 0.92
                    tint = colors.textSecondary,
                )
            }
        }
    }
}

/**
 * 产物面板 —— 对齐 `ArtifactsPanel.tsx`（48 行，最简单的一个）。
 *
 * 结构：标题栏（「产物」+ 关闭按钮）+ 可滚动列表 / 空态。
 */
@Composable
fun ArtifactsPanel(
    documents: List<DocumentItem>,
    onClose: () -> Unit,
    onOpenDocument: (DocumentItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = CCMTheme.colors

    Column(modifier = modifier.fillMaxSize()) {
        // ── 标题栏（px-5 py-4）────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.4.dp, vertical = 14.72.dp),   // px-5 py-4
                .height(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "产物",
                style = CCMText.body16.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textMain,
            )
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(7.36.dp))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                // X 图标 —— 用 Material Icons 的 Close（lucide 的 X 是两条对角线）
                PainterIcon(
                    R.drawable.ic_close,
                    size = 16.55.dp,                     // size={18} × 0.92
                    tint = colors.textSecondary,
                )
            }
        }

        // ── 列表 / 空态 ───────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).padding(horizontal = 14.72.dp)) {
            if (documents.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 36.8.dp),   // mt-10
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "此对话还没有生成产物。",
                        style = CCMText.body14,
                        color = colors.textSecondary,
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(11.04.dp)) {  // space-y-3
                    documents.forEach { doc ->
                        DocumentCard(
                            title = doc.title,
                            subtitle = DocumentMeta.getDocumentSubtitle(doc.format),
                            onOpen = { onOpenDocument(doc) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 文档条目 —— 对应 Web 的 `DocumentInfo` 接口。
 *
 * 只保留 UI 需要的字段；`slides` / `sheets` / `sections` 这些
 * 只有预览卡用（而预览卡在移动端不渲染），暂不移植。
 */
data class DocumentItem(
    val id: String,
    val title: String,
    val filename: String = "",
    val url: String = "",
    val content: String = "",
    /** 原始 format 字段（可能是空的泛化值，用 [resolvedFormat] 取真实格式） */
    val format: String = "",
) {
    /** 真实格式 —— 等价于 JS 的 `resolveDocumentFormat(document)` */
    val resolvedFormat: String
        get() = DocumentMeta.resolveDocumentFormat(format, filename.ifEmpty { title }, content)

    /** 副标题 —— `Document · MARKDOWN` */
    val subtitle: String get() = DocumentMeta.getDocumentSubtitle(resolvedFormat)
}
