package com.ccm.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ccm.app.ui.theme.CCMText
import com.ccm.app.ui.theme.CCMTheme

/**
 * 待发文件卡片（2026-10-12 加，对齐 Web 的 `MessageAttachments` 文档卡）。
 *
 * ## 为什么不是文字条
 *
 * 原来首页的文件标签是「📄 文件名 ✕」一行文字 —— 与图片缩略图（56dp 方块）
 * 并排时大小/形状都不搭，用户报「标签样式很奇怪」。
 *
 * Web 的文件卡是 **方形卡片**（`w-28 h-28 rounded-xl border`），与图片
 * 缩略图同尺寸同圆角，两行内容：
 *
 * ```
 * ┌──────────────┐
 * │ 文件名         │  ← 13px，超长省略
 * │ 12.3KB        │  ← 11px 灰
 * │              │
 * │ [JS]      ✕  │  ← 扩展名徽章 + 删除按钮
 * └──────────────┘
 * ```
 *
 * ## 尺寸
 *
 * Web 是 `w-28 h-28`（112px）—— 但手机屏幕窄，与图片缩略图的 56dp 对齐
 * （图片那排就是 56dp）。同尺寸并排才整齐。
 *
 * @param name 文件名（含扩展名）
 * @param sizeBytes 文件字节数（null = 不显示大小）
 * @param onRemove 点 ✕ 移除（null = 不显示删除按钮）
 */
@Composable
fun FileChip(
    name: String,
    modifier: Modifier = Modifier,
    sizeBytes: Long? = null,
    onRemove: (() -> Unit)? = null,
) {
    val colors = CCMTheme.colors
    val ext = name.substringAfterLast('.', "").uppercase()

    Box(
        modifier = modifier
            .size(56.dp)                                   // 与图片缩略图同尺寸
            .clip(RoundedCornerShape(8.dp))
            .background(colors.input)
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .padding(5.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 文件名 + 大小
            Column {
                Text(
                    text = name,
                    style = CCMText.body11.copy(fontSize = 9.sp, lineHeight = 11.sp),
                    color = colors.textMain,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (sizeBytes != null && sizeBytes > 0) {
                    Text(
                        text = formatFileSize(sizeBytes),
                        style = CCMText.body11.copy(fontSize = 8.sp, lineHeight = 10.sp),
                        color = colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
            // 扩展名徽章
            if (ext.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.hover)
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = ext.take(4),
                        style = CCMText.body11.copy(fontSize = 7.sp, lineHeight = 9.sp),
                        color = colors.textSecondary,
                    )
                }
            }
        }
        // 删除按钮（右上角）
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(colors.textMain)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", style = CCMText.body10.copy(fontSize = 8.sp), color = colors.bgMain)
            }
        }
    }
}

/** 文件大小格式化（对齐 Web 的 `formatFileSize`）。 */
private fun formatFileSize(bytes: Long): String = when {
    bytes > 1024 * 1024 -> String.format("%.1fMB", bytes / 1024.0 / 1024.0)
    bytes > 1024 -> String.format("%.1fKB", bytes / 1024.0)
    else -> "${bytes}B"
}
