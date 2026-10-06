package com.ccm.app.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 本地文件缩略图（第20批，2026-09-28）—— 项目没有 Coil/Glide，
 * 用 BitmapFactory 两段解码：先 `inJustDecodeBounds` 量尺寸算 inSampleSize
 * （不解像素），再按采样率解 —— 一张 12MP 原图降到 ~512px 只要几 ms。
 *
 * 解码在 IO 线程（LaunchedEffect 内 Dispatchers.IO），主线程只做一次
 * `Image()` 贴图。文件不存在/解码失败显示灰色占位，**不抛异常**。
 *
 * @param path 绝对路径（AttachmentCache 拷出来的那份）
 */
@Composable
fun ThumbImage(
    path: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    var bitmap by remember(path) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(path) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) {
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
    }

    Box(
        modifier = modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius))
            .background(Color(0xFFE8E7E3)),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            // 占位：加载中/失败都是这个（尺寸由外部 modifier 定）
            Box(modifier = Modifier.fillMaxSize())
        }
    }
}
