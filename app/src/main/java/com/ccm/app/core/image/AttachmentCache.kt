package com.ccm.app.core.image

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 用户选图 → 拷进应用私有目录，返回**真实文件路径**（第18批，2026-09-28）。
 *
 * 为什么必须拷贝：PhotoPicker 返回的是 `content://` URI，
 * 而 AgentLoop 的 `readImageAsBase64(path)` / `ImageProcessor.loadOrNull(path)`
 * 都按文件路径读 —— 直接把 URI 传进去必然读失败。
 *
 * 【2026-10-11 从 cacheDir 改到 filesDir】
 * 原来存 `cacheDir/attachments/` —— 那是**系统可随时清理**的目录
 * （设置里的"清除缓存"、系统低存储时的自动回收都会删它）。
 * 用户报「重启 APP 时之前发的图片会不显示了」，根因之一就是这个：
 * 路径存进了会话文件，但文件本身已经被系统清掉。
 *
 * 改用 `filesDir`（应用私有持久目录）—— 只有卸载/用户主动清数据才会删。
 * 仍然保留 `pruneOld()` 的 7 天清理（自己控制生命周期，不依赖系统）。
 *
 * 拷贝是 IO，必须在**非主线程**调（调用方用 rememberCoroutineScope + Dispatchers.IO）。
 */
object AttachmentCache {

    private val MIME_EXT = mapOf(
        "image/png" to "png", "image/gif" to "gif",
        "image/webp" to "webp", "image/bmp" to "bmp",
    )

    /** 把 content URI 拷进 cache，返回绝对路径；失败返回 null。 */
    fun copyToCache(context: Context, uri: Uri): String? {
        // 块体而非表达式体 —— 中途要 return null（expression body 禁止 return）
        return try {
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            val ext = MIME_EXT[mime] ?: "jpg"
            val out = File(dir, "img_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { inp ->
                FileOutputStream(out).use { output -> inp.copyTo(output) }
            }
            out.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    /** 清理超过 7 天的附件（发送完的图留在 cache 里会无限涨）。 */
    fun pruneOld(context: Context) {
        try {
            val dir = File(context.filesDir, "attachments")
            val cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            dir.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
        } catch (_: Throwable) {
        }
    }
}
