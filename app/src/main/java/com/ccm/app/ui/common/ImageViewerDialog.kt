package com.ccm.app.ui.common

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

/**
 * 图片全屏查看器（第21批，2026-09-28）—— 点用户气泡里的缩略图放大看。
 *
 * - 解码上限 1024px（`inSampleSize` 按需降）：手机屏显示足够，
 *   又不至于把 12MP 原图整张解进内存（OOM 风险）。
 * - 深色全屏底 + `ContentScale.Fit`（缩略图是 Crop，这里要看全图）。
 * - 点任意处关闭；文件已删（7 天清理）显示占位文案。
 */
@Composable
fun ImageViewerDialog(
    path: String,
    onDismiss: () -> Unit,
) {
    // 【2026-10-06 加缓存】同 ThumbImage —— 全屏图也从共享 LRU 取
    // （尺寸 key 用 1024，与缩略图的 512 分开存）
    var bitmap by remember(path) { mutableStateOf(ThumbCache.get(path, 1024)) }
    var loadFailed by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        if (bitmap != null) return@LaunchedEffect
        val decoded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    path,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } catch (_: Throwable) {
                null
            }
        }
        if (decoded != null) ThumbCache.put(path, 1024, decoded)
        bitmap = decoded
        loadFailed = decoded == null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6000000))          // 90% 黑底
            .clickable(onClick = onDismiss),         // 点任意处关闭
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentScale = ContentScale.Fit,
            )
            loadFailed -> Text(
                text = "图片已不可用（附件保留 7 天）",
                color = Color(0xFFB7B5B0),
                fontSize = 14.sp,
            )
            else -> Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF2A2A28))
                    .padding(48.dp),
            )   // 加载中占位
        }

        // 关闭提示（右上角 ✕ 视觉锚点）
        Text(
            text = "✕ 点击关闭",
            color = Color(0xB3FFFFFF),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 16.dp)
                .clickable(onClick = onDismiss),
        )
    }
}
