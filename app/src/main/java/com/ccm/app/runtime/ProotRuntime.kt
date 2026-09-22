package com.ccm.app.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Proot 运行时 —— 启动并管理 Linux 环境里的进程。
 *
 * 【原理】
 * proot 用 ptrace 拦截系统调用，把路径重定向到 rootfs 下，
 * 不需要 root 就能跑一个"看起来像完整 Linux"的环境。
 *
 * 【Android 上的两个坑】
 *
 * 1. **exec 权限**：Android 10+ 对 App 私有目录（filesDir）的
 *    W^X 限制 —— 但实际测试表明 filesDir 里的可执行文件**可以** exec，
 *    真正受限的是 sdcard。所以 rootfs 放 filesDir。
 *
 * 2. **linker 路径**：rootfs 里的 ELF 用 /lib/ld-linux-aarch64.so.1 作为解释器，
 *    而 Android 的 linker 在 /system/bin/linker64。proot 的 -q 参数可以指定，
 *    但更简单的方式是让 proot 用 rootfs 自己的 linker（proot 会处理路径重定向）。
 *
 * 【启动命令】
 * proot -r <rootfs> -0 -w /root -b /dev -b /proc -b /sys \
 *       -b /sdcard:/mnt/sdcard \
 *       /usr/bin/env -i HOME=/root PATH=... TERM=xterm-256color \
 *       /bin/bash -l
 */
class ProotRuntime(private val context: Context) {

    companion object {
        private const val TAG = "ProotRuntime"

        /** proot 可执行文件在 APK 里的位置（jniLibs 打包，见 build.gradle） */
        private const val PROOT_LIB_NAME = "libproot.so"
        private const val PROOT_LOADER_NAME = "libproot_loader.so"
    }

    private val rootfs: File get() = File(context.filesDir, "rootfs")

    /** proot 二进制路径（从 nativeLibraryDir 取，那里有 exec 权限） */
    val prootBin: File
        get() = File(context.applicationInfo.nativeLibraryDir, PROOT_LIB_NAME)

    val prootLoader: File
        get() = File(context.applicationInfo.nativeLibraryDir, PROOT_LOADER_NAME)

    fun isReady(): Boolean = prootBin.exists() && rootfs.isDirectory

    /**
     * 构造 proot 命令行参数。
     *
     * @param workDir rootfs 内的工作目录
     * @param command 要执行的命令（rootfs 内的路径）
     * @param bindExtra 额外挂载，格式 "宿主路径:容器路径"
     */
    fun buildProotArgs(
        workDir: String = "/root",
        command: List<String>,
        bindExtra: List<String> = emptyList()
    ): List<String> {
        val args = mutableListOf<String>()

        args += prootBin.absolutePath

        // -r: rootfs 根目录
        args += "-r"; args += rootfs.absolutePath

        // -0: 伪装成 root（uid 0）—— 很多程序要求非 root 会拒绝启动
        args += "-0"

        // -w: 工作目录
        args += "-w"; args += workDir

        // -q: 用 rootfs 里的 qemu（同架构时不需要，跳过）

        // 链接器：让 proot 用 rootfs 自己的 linker
        // Android 的 linker64 与 glibc 的 ld-linux 不兼容，必须用 rootfs 里的
        val loader = File(rootfs, "lib/ld-linux-aarch64.so.1")
        if (loader.exists()) {
            args += "-q"; args += loader.absolutePath
        }

        // 挂载点：/dev /proc /sys 必须挂，否则很多命令不能用
        args += "-b"; args += "/dev"
        args += "-b"; args += "/proc"
        args += "-b"; args += "/sys"

        // /sdcard —— 让 AI 能读写用户的文件
        // Android 11+ 上 App 拿不到 /sdcard 直通，但 termux 的 /sdcard 可用
        // 这里挂 App 自己的外部目录（无需权限）
        val extDir = context.getExternalFilesDir(null)
        if (extDir != null && extDir.exists()) {
            args += "-b"; args += "${extDir.absolutePath}:/mnt/ext"
        }

        // 额外挂载
        bindExtra.forEach { args += "-b"; args += it }

        // 环境变量：用 env -i 清空 Android 继承的环境（避免污染）
        args += "/usr/bin/env"
        args += "-i"
        args += "HOME=/root"
        args += "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        args += "TERM=xterm-256color"
        args += "LANG=C.UTF-8"
        args += "LC_ALL=C.UTF-8"
        args += "SHELL=/bin/bash"
        args += "USER=root"
        args += "LOGNAME=root"
        args += "TMPDIR=/tmp"
        args += "ANDROID_ROOT=/system"

        args += command
        return args
    }

    /** 检查 rootfs 里有没有装某个命令 */
    fun hasCommand(name: String): Boolean {
        val paths = listOf(
            "usr/bin/$name", "usr/local/bin/$name", "bin/$name",
            "usr/sbin/$name", "sbin/$name"
        )
        return paths.any { File(rootfs, it).exists() }
    }

    /** rootfs 里 Node 的路径（如果装了） */
    fun nodePath(): String? {
        val candidates = listOf(
            "usr/local/bin/node", "usr/bin/node", "opt/node/bin/node",
            "root/.nvm/versions/node"
        )
        candidates.forEach { rel ->
            val f = File(rootfs, rel)
            if (f.isFile) return "/$rel"
            if (f.isDirectory) {
                // nvm 风格：找第一个版本
                f.listFiles()?.firstOrNull()?.let { v ->
                    val node = File(v, "bin/node")
                    if (node.exists()) return "/$rel/${v.name}/bin/node"
                }
            }
        }
        return null
    }

    /** 环境是否已装 Node */
    fun hasNode(): Boolean = nodePath() != null

    fun rootfsDir(): File = rootfs
}
