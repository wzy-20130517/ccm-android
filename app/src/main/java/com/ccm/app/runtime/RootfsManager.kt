package com.ccm.app.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Rootfs 管理器 —— 负责 Linux 根文件系统的安装与校验。
 *
 * 【设计】
 * rootfs 不打进 APK，而是首次启动时从 assets 解包（或从网络下载）。
 * 理由：APK 体积敏感（62MB 的 rootfs 会让 APK 膨胀到 100MB+），
 * 而 assets 里的压缩包可以在安装后按需解压。
 *
 * 【目录布局】
 * filesDir/rootfs/           ← 解压后的 Ubuntu 根
 *   ├── bin/  usr/  lib/ ...
 *   └── root/.ccm-installed  ← 安装完成标记（含版本号）
 * filesDir/rootfs.tar.gz     ← 原始压缩包（解压后可删，保留用于重装）
 *
 * 【为什么用 filesDir 而不是 sdcard】
 * - filesDir 是 App 私有目录，不需要存储权限
 * - 但 proot 需要执行权限，而 Android 11+ 对 App 私有目录的 exec 有限制
 *   → 见 ProotRuntime 里的处理（用 linker 显式加载 或 复制到可执行位置）
 */
class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "RootfsManager"
        private const val ROOTFS_DIR = "rootfs"
        private const val MARKER_FILE = "root/.ccm-installed"
        private const val ASSET_ARCHIVE = "ubuntu-base-arm64.tar.gz"

        /** 当前 rootfs 版本。升级这个值会触发重新安装。 */
        const val ROOTFS_VERSION = "24.04-v1"
    }

    val rootfsPath: File get() = File(context.filesDir, ROOTFS_DIR)

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

    /**
     * 从 assets 解包 rootfs。
     *
     * @param onProgress 进度回调 (已解压字节, 总字节)
     * @return 成功与否
     */
    fun installFromAssets(onProgress: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        return try {
            val archive = File(context.filesDir, ASSET_ARCHIVE)

            // 1) 把 assets 里的压缩包复制到 filesDir（assets 不能直接解压）
            if (!archive.exists() || archive.length() == 0L) {
                val total = context.assets.open(ASSET_ARCHIVE).use { it.available().toLong() }
                context.assets.open(ASSET_ARCHIVE).use { input ->
                    FileOutputStream(archive).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            copied += n
                            onProgress(copied, total)
                        }
                    }
                }
            }

            // 2) 解压（tar.gz，用系统 tar）
            if (rootfsPath.exists()) rootfsPath.deleteRecursively()
            rootfsPath.mkdirs()

            val ok = TarExtractor.extract(archive, rootfsPath) { done, total ->
                onProgress(done, total)
            }
            if (!ok) {
                Log.e(TAG, "解压失败")
                return false
            }

            // 3) 写标记
            File(rootfsPath, MARKER_FILE).apply {
                parentFile?.mkdirs()
                writeText(ROOTFS_VERSION)
            }

            // 4) 预置基础配置
            setupBaseConfig()

            Log.i(TAG, "rootfs 安装完成：${rootfsPath.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "安装 rootfs 失败", t)
            false
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
        } catch (t: Throwable) {
            Log.w(TAG, "写基础配置失败（不致命）", t)
        }
    }

    /** 删除 rootfs（用于重装 / 释放空间） */
    fun uninstall(): Boolean {
        return try {
            rootfsPath.deleteRecursively()
            File(context.filesDir, ASSET_ARCHIVE).delete()
            true
        } catch (t: Throwable) {
            Log.e(TAG, "卸载失败", t)
            false
        }
    }

    /** 已占用空间（字节） */
    fun usedBytes(): Long = rootfsPath.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
