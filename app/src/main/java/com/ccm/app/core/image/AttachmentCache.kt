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
        "image/jpeg" to "jpg",
    )

    /**
     * 把 content URI 拷进 cache，返回绝对路径；失败返回 null。
     *
     * 【2026-10-12 保留原文件名 —— 这是个把整个文件功能搞坏的 bug】
     *
     * 原来：
     * ```kotlin
     * val ext = MIME_EXT[mime] ?: "jpg"                    // 非图片一律 .jpg
     * val out = File(dir, "img_${时间戳}_${随机}.$ext")     // 硬编码 img_ 前缀
     * ```
     *
     * 后果：**选任何文件都被改名成 `img_xxx.jpg`** —— 用户选 `main.mjs`
     * 得到 `img_1791808240561_7072.jpg`。连锁反应：
     *   ① `FileAttachment` 按扩展名判断，`.jpg` 不在文本扩展名里
     *      → 落到「二进制」分支 → 不读内容、只给路径
     *   ② 模型看到的是 `.jpg`，以为那是张图片
     *   ③ 用户气泡/文件卡显示的名字也是 `img_xxx.jpg`
     *
     * 现在：查 ContentResolver 的 DISPLAY_NAME 拿**原文件名**，
     * 存成 `原名.时间戳.扩展名`（前缀防重名，扩展名从原名取）。
     */
    fun copyToCache(context: Context, uri: Uri): String? {
        return try {
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val displayName = queryDisplayName(context, uri)
            val mime = context.contentResolver.getType(uri).orEmpty()

            // 扩展名：优先从原文件名取；取不到用 mime 映射；再取不到用 bin
            val extFromName = displayName?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val ext = extFromName.ifBlank { MIME_EXT[mime] ?: "bin" }

            // 保留原名（去掉扩展名部分，避免 `a.md.时间戳.md` 这种怪名）
            val base = displayName
                ?.substringBeforeLast('.', displayName)
                ?.replace(Regex("[^\\w\\u4e00-\\u9fff.-]"), "_")   // 去掉路径分隔符等
                ?.take(40)
                ?.takeIf { it.isNotBlank() }
                ?: "file"

            val out = File(dir, "${base}_${System.currentTimeMillis()}.$ext")
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { inp ->
                FileOutputStream(out).use { output -> inp.copyTo(output) }
            }
            out.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    /** 查 ContentResolver 拿原始文件名（拿不到返回 null）。 */
    private fun queryDisplayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    } catch (_: Throwable) {
        null
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
