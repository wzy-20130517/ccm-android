package com.ccm.app.ui.common

import android.graphics.Bitmap
import android.util.LruCache

/**
 * 缩略图内存缓存（2026-10-06 加）。
 *
 * ══════════════════════════════════════════════════════════════
 *  解决什么问题
 * ══════════════════════════════════════════════════════════════
 *
 * ThumbImage / ImageViewerDialog 原来都是「LaunchedEffect(path) 里
 * 直接 decodeFile → 写进局部 state」，**没有缓存层**：
 *   · 组件滑出屏幕被回收、再滑回来 → remember 重建 → bitmap 归 null
 *     → 重新走一遍磁盘解码（用户看到「闪一下再出现」）
 *   · 同一张图在「待发送区」和「气泡里」都出现时，各解码一次
 *
 * 项目未引 Coil/Glide（CLAUDE.md 约定「少依赖重工具链」），所以这里
 * 用 Android 自带的 LruCache 做一个极简共享缓存 —— 两个组件共用。
 *
 * ══════════════════════════════════════════════════════════════
 *  容量
 * ══════════════════════════════════════════════════════════════
 *
 * 取可用堆内存的 1/8（Android 官方推荐的图片缓存比例）。
 * 缩略图已按 512px 采样，单张约 1MB，1/8 堆通常能存几十张。
 *
 * ⚠️ 只缓存**解码后的 Bitmap**，不缓存原图字节（原图在磁盘上，
 *    需要时现读）。缓存 key 用路径 + 目标尺寸，避免不同尺寸互相覆盖。
 */
object ThumbCache {

    /** key = "路径@目标尺寸"（同图不同尺寸分开存，避免互相污染）。 */
    private val lru: LruCache<String, Bitmap> by lazy {
        val maxKb = try {
            (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
        } catch (_: Throwable) {
            4 * 1024   // 兜底 4MB
        }
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    fun key(path: String, maxSize: Int): String = "$path@$maxSize"

    fun get(path: String, maxSize: Int): Bitmap? = try {
        lru.get(key(path, maxSize))
    } catch (_: Throwable) { null }

    fun put(path: String, maxSize: Int, bmp: Bitmap) {
        try {
            // 同 key 已在缓存时不要重复放（LruCache 会替换，但先判一下省一次 sizeOf）
            if (lru.get(key(path, maxSize)) == null) lru.put(key(path, maxSize), bmp)
        } catch (_: Throwable) { /* 缓存失败不影响功能 */ }
    }

    /** 清空（内存紧张或用户手动清理时用）。 */
    fun clear() {
        try { lru.evictAll() } catch (_: Throwable) {}
    }
}
