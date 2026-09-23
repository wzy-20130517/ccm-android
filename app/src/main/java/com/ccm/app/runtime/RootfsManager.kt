package com.ccm.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Rootfs 管理器 —— 负责 Linux 根文件系统的安装与校验。
 *
 * 【为什么从网络下载而不是打包进 APK】
 * 28MB 的 rootfs 打进 APK 会让体积膨胀到 45MB+，且每次改代码都要重传。
 * 放 GitHub Release 上，首次启动下载一次即可，之后走本地缓存。
 *
 * 而且用户可以选择跳过 —— 只当 WebView 壳用（连接 Termux 里的 Node）也行。
 *
 * 【目录布局】
 * filesDir/rootfs/           ← 解压后的 Ubuntu 根
 *   ├── bin/  usr/  lib/ ...
 *   └── root/.ccm-installed  ← 安装完成标记（含版本号）
 * filesDir/rootfs.tar.gz     ← 下载的压缩包（解压后可删）
 *
 * 【Android exec 权限】
 * filesDir 里的文件在 Android 10+ 默认不可 exec，但 proot 不需要 exec rootfs 里的文件
 * （proot 是宿主进程，它只读取 rootfs 内容并用 ptrace 重定向路径）。
 * 只有 proot 自己需要 exec 权限 —— 它放在 nativeLibraryDir（那里可 exec）。
 */
class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "RootfsManager"
        private const val ROOTFS_DIR = "rootfs"
        private const val MARKER_FILE = "root/.ccm-installed"
        private const val ARCHIVE_NAME = "rootfs.tar.gz"

        /** rootfs 版本。升级这个值会触发重新安装。 */
        const val ROOTFS_VERSION = "24.04-v1"

        /** 下载地址（GitHub Release） */
        const val ROOTFS_URL =
            "https://github.com/wzy-20130517/ccm-android/releases/download/rootfs-v1/ubuntu-base-24.04-arm64.tar.gz"

        /** Node 内核包（core + web） */
        const val KERNEL_URL =
            "https://github.com/wzy-20130517/ccm-android/releases/download/rootfs-v1/ccm-node-kernel.tar.gz"

        /** 内核安装目标（rootfs 内） */
        const val KERNEL_DIR = "root/ccm"

        /** 国内加速（GitHub 直连慢时用） */
        private val MIRRORS = listOf(
            "https://ghfast.top/https://github.com/wzy-20130517/ccm-android/releases/download/rootfs-v1/ubuntu-base-24.04-arm64.tar.gz",
            "https://gh-proxy.com/https://github.com/wzy-20130517/ccm-android/releases/download/rootfs-v1/ubuntu-base-24.04-arm64.tar.gz",
            ROOTFS_URL,
        )

        private val KERNEL_MIRRORS = listOf(
            "https://ghfast.top/https://github.com/wzy-20130517/ccm-android/releases/download/rootfs-v1/ccm-node-kernel.tar.gz",
            "https://gh-proxy.com/https://github.com/wzy-20130517/ccm-android/releases/download/rootfs-v1/ccm-node-kernel.tar.gz",
            KERNEL_URL,
        )
    }

    val rootfsPath: File get() = File(context.filesDir, ROOTFS_DIR)
    private val archiveFile: File get() = File(context.filesDir, ARCHIVE_NAME)

    /** 是否已安装（且版本匹配） */
    fun isInstalled(): Boolean {
        val marker = File(rootfsPath, MARKER_FILE)
        if (!marker.exists()) return false
        val installed = marker.readText().trim()
        if (installed != ROOTFS_VERSION) {
            Log.i(TAG, "rootfs 版本不匹配：已装=$installed 期望=$ROOTFS_VERSION")
            return false
        }
        // 关键目录存在性校验 —— 标记文件可能在解压中途被写入
        return File(rootfsPath, "bin").isDirectory &&
               File(rootfsPath, "usr/bin").isDirectory
    }

    /** 已占用空间（字节） */
    fun usedBytes(): Long = try {
        rootfsPath.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    } catch (t: Throwable) { 0 }

    /** 是否已有下载好的压缩包 */
    fun hasArchive(): Boolean = archiveFile.exists() && archiveFile.length() > 1_000_000

    /**
     * 完整安装流程：下载 → 解压 → 配置。
     *
     * @param onProgress (阶段, 已完成, 总量)  阶段: "download" / "extract" / "config"
     */
    fun install(onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }): Boolean {
        return try {
            // 1) 下载（已有就跳过）
            if (!hasArchive()) {
                if (!download(onProgress)) {
                    Log.e(TAG, "下载失败")
                    return false
                }
            }

            // 2) 解压
            if (rootfsPath.exists()) rootfsPath.deleteRecursively()
            rootfsPath.mkdirs()
            onProgress("extract", 0, archiveFile.length())
            val ok = TarExtractor.extract(archiveFile, rootfsPath) { done, total ->
                onProgress("extract", done, total)
            }
            if (!ok) {
                Log.e(TAG, "解压失败")
                return false
            }

            // 3) 配置
            onProgress("config", 0, 1)
            setupBaseConfig()

            // 4) 写标记
            File(rootfsPath, MARKER_FILE).apply {
                parentFile?.mkdirs()
                writeText(ROOTFS_VERSION)
            }

            // 5) 清掉压缩包省空间（28MB）
            try { archiveFile.delete() } catch (_: Throwable) {}

            onProgress("config", 1, 1)
            Log.i(TAG, "rootfs 安装完成：${rootfsPath.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "安装 rootfs 失败", t)
            false
        }
    }

    /**
     * 从多个镜像依次尝试下载。
     */
    private fun download(onProgress: (String, Long, Long) -> Unit): Boolean {
        for ((idx, url) in MIRRORS.withIndex()) {
            try {
                Log.i(TAG, "尝试下载（${idx + 1}/${MIRRORS.size}）: ${url.take(60)}…")
                if (downloadOne(url, onProgress)) return true
            } catch (t: Throwable) {
                Log.w(TAG, "镜像 ${idx + 1} 失败: ${t.message}")
            }
        }
        return false
    }

    private fun downloadOne(url: String, onProgress: (String, Long, Long) -> Unit): Boolean {
        val tmp = File(context.filesDir, "$ARCHIVE_NAME.part")
        if (tmp.exists()) tmp.delete()

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CCM/0.1 (Android)")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code")
                return false
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 29_000_000L

            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 512 * 1024) {
                            onProgress("download", done, total)
                            lastReport = done
                        }
                    }
                    onProgress("download", done, total)
                }
            }

            // 完整性检查：至少 10MB
            if (tmp.length() < 10_000_000) {
                Log.w(TAG, "下载不完整：${tmp.length()} 字节")
                tmp.delete()
                return false
            }

            if (archiveFile.exists()) archiveFile.delete()
            val ok = tmp.renameTo(archiveFile)
            if (!ok) {
                tmp.copyTo(archiveFile, overwrite = true)
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "下载异常: ${t.message}")
            try { tmp.delete() } catch (_: Throwable) {}
            false
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** 写入 DNS / apt 源 / profile —— 让环境开箱可用 */
    private fun setupBaseConfig() {
        try {
            // DNS（Android 上 /etc/resolv.conf 不可写，proot 里用这个）
            File(rootfsPath, "etc/resolv.conf").writeText(
                "nameserver 223.5.5.5\nnameserver 119.29.29.29\n"
            )

            // apt 源换国内（Ubuntu 24.04 用新格式）
            val sourcesFile = File(rootfsPath, "etc/apt/sources.list.d/ubuntu.sources")
            if (sourcesFile.parentFile?.exists() == true) {
                sourcesFile.writeText(
                    """
                    Types: deb
                    URIs: https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
                    Suites: noble noble-updates noble-backports
                    Components: main universe restricted multiverse
                    Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
                    """.trimIndent()
                )
            }

            // root 的 shell 配置
            File(rootfsPath, "root/.bashrc").writeText(
                """
                export PS1='\[\e[36m\]ccm\[\e[0m\]:\w\$ '
                export LANG=C.UTF-8
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                """.trimIndent()
            )

            // 常用挂载点
            listOf("dev", "proc", "sys", "tmp", "root", "mnt/ext").forEach {
                File(rootfsPath, it).mkdirs()
            }
            File(rootfsPath, "tmp").setExecutable(true, false)
        } catch (t: Throwable) {
            Log.w(TAG, "写基础配置失败（不致命）", t)
        }
    }

    // ═══════════════════════════════════════════════════
    //  Node 内核安装
    // ═══════════════════════════════════════════════════

    /** 内核是否已安装 */
    fun isKernelInstalled(): Boolean {
        return File(rootfsPath, "$KERNEL_DIR/ccm-start.mjs").exists() &&
               File(rootfsPath, "$KERNEL_DIR/web/server.mjs").exists()
    }

    /**
     * 安装 Node 内核（core + web 源码，约 700KB）。
     * 依赖 rootfs 已安装。
     */
    fun installKernel(onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        if (!isInstalled()) {
            Log.w(TAG, "rootfs 未安装，无法装内核")
            return false
        }
        val archive = File(context.filesDir, "kernel.tar.gz")

        return try {
            // 下载
            if (!archive.exists() || archive.length() < 100_000) {
                var ok = false
                for (url in KERNEL_MIRRORS) {
                    try {
                        Log.i(TAG, "下载内核: ${url.take(50)}…")
                        if (downloadTo(url, archive, onProgress)) { ok = true; break }
                    } catch (t: Throwable) {
                        Log.w(TAG, "镜像失败: ${t.message}")
                    }
                }
                if (!ok) return false
            }

            // 解压到 rootfs/root/ccm
            val dest = File(rootfsPath, KERNEL_DIR)
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()

            val ok = TarExtractor.extract(archive, dest)
            if (!ok) return false

            // 清理
            archive.delete()
            Log.i(TAG, "内核安装完成")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "内核安装失败", t)
            false
        }
    }

    /** 通用下载到指定文件 */
    private fun downloadTo(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean {
        var conn: HttpURLConnection? = null
        val tmp = File(dest.parentFile, "${dest.name}.part")
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CCM/0.1 (Android)")
            }
            if (conn.responseCode !in 200..299) return false
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: 1_000_000L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
        } catch (t: Throwable) {
            Log.w(TAG, "下载失败: ${t.message}")
            tmp.delete()
            false
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ═══════════════════════════════════════════════════
    //  Node 运行时安装（在 proot 里跑 apt）
    // ═══════════════════════════════════════════════════

    /**
     * 在 rootfs 里安装 Node。
     *
     * 【为什么用 apt 而不是打包二进制】
     * Node 官方 arm64 二进制 23MB，打进 rootfs 包会让它翻倍。
     * 而 Ubuntu 24.04 自带 nodejs 18.19.1，一条 apt 命令搞定，
     * 且用户网络通常没问题（rootfs 本来就是联网下载的）。
     *
     * 【执行方式】
     * proot -r rootfs -0 /usr/bin/env ... /bin/bash -c "apt-get install -y nodejs"
     * 注意要先把 apt 源换成国内（setupBaseConfig 里已做）。
     *
     * @param onLine 每行输出回调（给 UI 显示进度）
     */
    fun installNode(
        exec: (List<String>, (String) -> Unit) -> Boolean,
        onLine: (String) -> Unit = {}
    ): Boolean {
        if (!isInstalled()) {
            Log.w(TAG, "rootfs 未安装")
            return false
        }
        if (hasNode()) {
            Log.i(TAG, "Node 已存在，跳过安装")
            return true
        }

        return try {
            // 1) 修复执行权限（不做这步 apt 会静默失败）
            onLine("修复文件权限…")
            fixPermissionsInternal()

            // 2) apt update
            onLine("更新软件源…")
            exec(listOf("/bin/bash", "-lc", "apt-get update"), onLine)

            // 3) 装 nodejs + 常用工具
            onLine("安装 Node.js 与基础工具…")
            val ok = exec(
                listOf(
                    "/usr/bin/env", "-i",
                    "HOME=/root",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "DEBIAN_FRONTEND=noninteractive",
                    "/bin/bash", "-lc",
                    "apt-get install -y nodejs git curl ca-certificates"
                ),
                onLine
            )

            if (ok && hasNode()) {
                Log.i(TAG, "Node 安装成功")
                true
            } else {
                Log.w(TAG, "Node 安装失败")
                false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "安装 Node 异常", t)
            false
        }
    }

    /** 修复执行权限（apt 的 http method 等需要） */
    private fun fixPermissionsInternal() {
        try {
            val execDirs = listOf(
                "bin", "sbin", "usr/bin", "usr/sbin",
                "usr/local/bin", "usr/local/sbin",
                "usr/lib/apt/methods", "usr/lib/dpkg",
                "lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu",
            )
            execDirs.forEach { d ->
                File(rootfsPath, d).listFiles()?.forEach { f ->
                    if (f.isFile && !f.canExecute()) f.setExecutable(true, false)
                }
            }
        } catch (_: Throwable) {}
    }

    /** 检查 rootfs 里有没有 Node */
    fun hasNode(): Boolean {
        val candidates = listOf(
            "usr/bin/node", "usr/local/bin/node", "bin/node"
        )
        return candidates.any { File(rootfsPath, it).exists() }
    }

    /** 删除 rootfs（用于重装 / 释放空间） */
    fun uninstall(): Boolean {
        return try {
            rootfsPath.deleteRecursively()
            archiveFile.delete()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "卸载失败", t)
            false
        }
    }
}
