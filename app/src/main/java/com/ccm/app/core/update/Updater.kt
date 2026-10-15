package com.ccm.app.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * APK 自更新（2026-10-15）。
 *
 * ## 为什么 APK 需要单独的更新通道
 *
 * CLI 的 `/update` 是「从镜像下载 tar.gz，就地覆盖源码文件」—— 那是脚本形态
 * 才有的做法。APK 是**编译产物**，设备上根本没有可覆盖的源码，唯一的更新方式
 * 是「下载新安装包 → 交给系统安装器覆盖安装」。
 *
 * 好在应用私有数据（会话/配置/工作区索引）不在 APK 里，覆盖安装**不丢数据**。
 *
 * ## 通道选择
 *
 * 1. **版本发现**：`api.github.com` 的 `releases/latest` —— 实测国内可达
 *    （github.com:443 常被墙，api 子域通常没问题，与 CLI 的选择一致）。
 * 2. **下载**：走 `gh-proxy.com` 镜像前缀。GitHub Release 的大文件直连国内
 *    极慢甚至超时（89MB 的 APK），镜像能跑满带宽。
 *
 * ## 版本比较
 *
 * 本项目每次 CI 构建都会发一个 release，tag 形如 `build-252`，数字就是
 * CI 的 run number，与 APK 的 `versionCode`（由 `CCM_VERSION_CODE` 环境变量
 * 注入）**完全一致**。所以比较逻辑就是「远端 build 号 > 本机 versionCode」，
 * 比解析 semver 更准确。
 *
 * ⚠️ 不能用 `versionName`（它是 `0.1.0` 这种，CI 没注入时还是默认值）。
 */
object Updater {

    /** 仓库坐标。与 CLI 的 REPO 是两个仓库，不要混用。 */
    const val REPO = "wzy-20130517/ccm-android"

    /**
     * 下载镜像前缀。
     *
     * gh-proxy.com 的用法是「前缀 + 原始完整 URL」，如
     * `https://gh-proxy.com/https://github.com/.../CCM-253.apk`。
     */
    const val MIRROR = "https://gh-proxy.com"

    /** 超时：探测 20s（只是个 API 请求）；下载 10 分钟（89MB 走镜像）。 */
    private const val PROBE_TIMEOUT_S = 20L
    private const val DOWNLOAD_TIMEOUT_S = 600L

    /**
     * 一次可选的更新。
     *
     * @param buildNumber 远端构建号（= Release tag 里的数字）
     * @param downloadUrl GitHub 原始下载地址（下载时会加镜像前缀）
     * @param sizeBytes   APK 字节数，用于显示与完整性校验
     * @param notes       Release 描述（CI 写的是 commit sha）
     */
    data class UpdateInfo(
        val buildNumber: Int,
        val tag: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val notes: String?,
    )

    /** 检查结果：要么有新版，要么已是最新，要么出错。 */
    sealed class CheckResult {
        data class HasUpdate(val info: UpdateInfo) : CheckResult()
        data class UpToDate(val current: Int, val latest: Int) : CheckResult()
        data class Failed(val message: String) : CheckResult()
    }

    // ── JSON 模型（只取需要的字段，多余字段忽略）──────────────────────

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String = "",
        val body: String? = null,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
        val size: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun probeClient() = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    private fun downloadClient() = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    // ── 版本 ───────────────────────────────────────────────────────

    /**
     * 本机构建号（`versionCode`）。
     *
     * 从 PackageManager 真值读 —— 不用 BuildConfig，因为 AGP 8 默认不生成
     * 该类（SlashCommandHandler 里的同名注释记过这个坑）。
     */
    fun currentBuildNumber(context: Context): Int = try {
        context.packageManager.getPackageInfo(context.packageName, 0)?.let {
            @Suppress("DEPRECATION")
            it.versionCode
        } ?: 0
    } catch (_: Throwable) { 0 }

    /** 从 `build-252` 提取 252。解析不出返回 0。 */
    private fun parseBuildNumber(tag: String): Int =
        Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // ── 检查 ───────────────────────────────────────────────────────

    /**
     * 查最新 release 并与本机比较。
     *
     * 网络异常一律返回 [CheckResult.Failed]，由调用方决定怎么提示 ——
     * 更新检查失败不该打断任何正常使用。
     */
    suspend fun check(context: Context): CheckResult = withContext(Dispatchers.IO) {
        val current = currentBuildNumber(context)
        val url = "https://api.github.com/repos/$REPO/releases/latest"
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ccm-android-updater")
                .build()
            val text = probeClient().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext CheckResult.Failed("HTTP ${resp.code}（检查更新失败）")
                }
                resp.body?.string() ?: ""
            }
            val one = json.decodeFromString<ReleaseDto>(text)
            val latestBuild = parseBuildNumber(one.tagName)
            if (latestBuild <= 0) {
                return@withContext CheckResult.Failed("无法解析 Release 版本号（tag=${one.tagName}）")
            }
            if (latestBuild <= current) {
                return@withContext CheckResult.UpToDate(current, latestBuild)
            }
            // 优先取 .apk 资产；没有就退回按 tag 拼地址
            val apk = one.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            val downloadUrl = apk?.url
                ?: "https://github.com/$REPO/releases/download/${one.tagName}/CCM-$latestBuild.apk"
            CheckResult.HasUpdate(
                UpdateInfo(
                    buildNumber = latestBuild,
                    tag = one.tagName,
                    downloadUrl = downloadUrl,
                    sizeBytes = apk?.size ?: 0L,
                    notes = one.body?.trim()?.takeIf { it.isNotEmpty() },
                )
            )
        } catch (t: Throwable) {
            CheckResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    // ── 下载 ───────────────────────────────────────────────────────

    /**
     * 下载 APK 到 `cache/updates/`。
     *
     * @param onProgress 已下载字节数 / 总字节数 → 用于 UI 进度条。在 IO 线程回调。
     * @return 下载好的文件；失败抛异常（由调用方 catch 成提示）
     */
    suspend fun download(
        context: Context,
        info: UpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // 文件名带 build 号，避免和上一个残留包混淆
        val target = File(dir, "CCM-${info.buildNumber}.apk")
        if (target.exists() && info.sizeBytes > 0 && target.length() == info.sizeBytes) {
            return@withContext target   // 已经下好了（上次失败后重试的情形）
        }

        val mirrorUrl = "$MIRROR/${info.downloadUrl}"
        val req = Request.Builder()
            .url(mirrorUrl)
            .header("User-Agent", "ccm-android-updater")
            .build()

        downloadClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("下载失败：HTTP ${resp.code}")
            val total = resp.body?.contentLength()?.takeIf { it > 0 } ?: info.sizeBytes
            resp.body?.byteStream()?.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        done += n
                        // 每 256KB 报一次，避免 UI 状态更新过密
                        if (done - lastReport >= 256 * 1024) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
        }

        // 完整性校验：GitHub 给了大小就比对，防止镜像截断
        // （实测踩过 49748858 字节 vs 实际 89201058，装的时候才报 PARSE_FAILED）
        if (info.sizeBytes > 0 && target.length() != info.sizeBytes) {
            val got = target.length()
            target.delete()
            throw IllegalStateException("下载不完整（$got / ${info.sizeBytes} 字节），已删除，请重试")
        }
        target
    }

    // ── 安装 ───────────────────────────────────────────────────────

    /**
     * 启动系统安装器。
     *
     * 两步：
     *  1. 检查「安装未知应用」权限（Android 8+ 是 per-app 开关，不是 Manifest 权限）。
     *     没开就跳系统设置页，让用户手动开 —— 不能静默申请。
     *  2. FileProvider 把 cache 里的 APK 暴露成 content:// URI 交给安装器。
     *     （Android 7+ 禁止用 file:// URI，会抛 FileUriExposedException）
     *
     * @return true = 已拉起安装器；false = 缺权限，已跳转设置页
     */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            // 跳「安装未知应用」授权页
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            } catch (_: Throwable) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) { /* 实在打不开就算了 */ }
            }
            return false
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    /** 清理已下载的安装包（装完或用户取消后调用，避免占 cache）。 */
    fun clearCache(context: Context) {
        try {
            File(context.cacheDir, "updates").listFiles()?.forEach { it.delete() }
        } catch (_: Throwable) { }
    }
}
