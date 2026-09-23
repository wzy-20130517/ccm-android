package com.ccm.app.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Proot 运行时 —— 启动并管理 Linux 环境里的进程。
 *
 * ══════════════════════════════════════════════════════════════
 *  ⚠️ 这份实现的所有参数都是在真机上实测验证过的，改动前请先读下面的说明
 * ══════════════════════════════════════════════════════════════
 *
 * 【原理】
 * proot 用 ptrace 拦截系统调用，把路径重定向到 rootfs 下，
 * 不需要 root 就能跑一个"看起来像完整 Linux"的环境。
 *
 * 【实测踩过的坑（每一条都是真机验证过的，改前先想清楚）】
 *
 * 1. **必须清掉 LD_PRELOAD** ← 最隐蔽的坑
 *    Termux 里 `LD_PRELOAD=libtermux-exec-ld-preload.so` 会导致 proot
 *    的 execve 报 "Function not implemented"。这个库拦截 exec 相关调用，
 *    与 proot 的 ptrace 机制冲突。子进程环境里必须 remove 它。
 *
 * 2. **必须用相对路径 --rootfs=.**
 *    proot-distro 就是这么做的。用绝对路径会报
 *    "can't chmod ...: Function not implemented"。
 *    所以 ProcessBuilder.directory() 必须设成 rootfs。
 *
 * 3. **必须设 PROOT_L2S_DIR 且目录预先创建**
 *    link2symlink 扩展的工作目录，位置在 rootfs 内部（<rootfs>/.l2s）。
 *
 * 4. **必须传 --link2symlink**
 *    Android 文件系统不支持硬链接，rootfs 里有大量硬链接（perl、gzip 等）。
 *
 * 5. **必须传 -L**
 *    修复 lstat 语义，否则 dpkg 报一堆 symlink 警告。
 *
 * 6. **必须传 --kill-on-exit**
 *    否则退出会话后残留进程阻塞。
 *
 * 7. **--kernel-release 要完整格式**
 *    `\Linux\<hostname>\<version>\<arch>\localdomain\-1\`
 *    写错会报 "can't find hwcap field"。
 *
 * 8. **rootfs 文件执行权限必须保留**
 *    解压后要恢复可执行位，否则 apt 的 http method 报 "Permission denied"。
 *
 * 9. **apt 源用 http 而非 https**
 *    proot 里 apt 的 https method 启动会失败（fork 受限）。
 *
 * 10. **绑定 /linkerconfig/ld.config.txt 等 Android 路径**
 *    否则某些动态链接场景会失败。
 */
class ProotRuntime(private val context: Context) {

    companion object {
        private const val TAG = "ProotRuntime"

        /** proot 可执行文件（jniLibs 打包，nativeLibraryDir 有 exec 权限） */
        private const val PROOT_LIB_NAME = "libproot.so"

        /** 伪造的内核版本（proot-distro 同款格式，改格式会报 hwcap 错误） */
        private const val FAKE_KERNEL =
            "\\Linux\\localhost\\6.17.0-PRoot-Distro\\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\\aarch64\\localdomain\\-1\\"
    }

    private val rootfs: File get() = File(context.filesDir, "rootfs")

    /** proot 二进制路径（从 nativeLibraryDir 取，那里有 exec 权限） */
    val prootBin: File
        get() = File(context.applicationInfo.nativeLibraryDir, PROOT_LIB_NAME)

    fun isReady(): Boolean = prootBin.exists() && rootfs.isDirectory

    fun rootfsDir(): File = rootfs

    /**
     * 构造 proot 命令行参数。
     *
     * ⚠️ 返回的是完整 argv（首元素是 proot 路径）。
     * 调用方必须配合：
     *   - ProcessBuilder.directory(rootfs)   ← 配合 --rootfs=.
     *   - 环境里 remove LD_PRELOAD
     *   - 环境里设 PROOT_L2S_DIR
     * 这三件事 buildProcess() 已处理好，建议直接用那个方法。
     */
    fun buildProotArgs(
        workDir: String = "/root",
        command: List<String>,
        bindExtra: List<String> = emptyList()
    ): List<String> {
        val args = mutableListOf<String>()

        args += prootBin.absolutePath

        // 退出时清理残留进程
        args += "--kill-on-exit"

        // 硬链接模拟（Android 不支持硬链接，必须）
        args += "--link2symlink"

        // SysV IPC
        args += "--sysvipc"

        // 伪造内核版本
        args += "--kernel-release=$FAKE_KERNEL"

        // 修复 lstat 语义（dpkg 需要）
        args += "-L"

        // 伪装 root
        args += "--change-id=0:0"

        // ⚠️ 相对路径！配合 ProcessBuilder.directory(rootfs)
        args += "--rootfs=."

        // 工作目录
        args += "--cwd=$workDir"

        // 基础挂载
        args += "--bind=/dev"
        args += "--bind=/proc"
        args += "--bind=/sys"
        args += "--bind=/dev/urandom:/dev/random"

        // Android 运行时路径
        listOf(
            "/apex", "/system", "/vendor", "/product", "/system_ext", "/odm",
            "/data/app", "/data/dalvik-cache",
            "/linkerconfig/ld.config.txt",
            "/linkerconfig/com.android.art/ld.config.txt",
        ).forEach { p ->
            if (File(p).exists()) args += "--bind=$p"
        }

        // 外部存储（AI 读写用户文件，App 私有外部目录无需权限）
        val extDir = context.getExternalFilesDir(null)
        if (extDir != null) {
            try { extDir.mkdirs() } catch (_: Throwable) {}
            args += "--bind=${extDir.absolutePath}:/mnt/ext"
        }

        // 额外挂载
        bindExtra.forEach { args += "--bind=$it" }

        args += command
        return args
    }

    /**
     * 构造配置好的 ProcessBuilder。
     *
     * 处理三个必须的环境设置：
     * 1. 工作目录 = rootfs（配合 --rootfs=.）
     * 2. 清掉 LD_PRELOAD（Termux 的 exec 拦截库会让 proot 失败）
     * 3. 设 PROOT_L2S_DIR（并确保目录存在）
     */
    fun buildProcess(
        workDir: String = "/root",
        command: List<String>,
        bindExtra: List<String> = emptyList(),
        extraEnv: Map<String, String> = emptyMap()
    ): ProcessBuilder {
        val args = buildProotArgs(workDir, command, bindExtra)
        val pb = ProcessBuilder(args)

        // ⚠️ 必须：工作目录 = rootfs（--rootfs=. 是相对路径）
        pb.directory(rootfs)
        pb.redirectErrorStream(true)

        val env = pb.environment()

        // ⚠️ 必须：清掉 LD_PRELOAD
        env.remove("LD_PRELOAD")

        // proot 依赖库路径（$ORIGIN 的兜底）
        env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

        // ⚠️ 必须：link2symlink 工作目录
        val l2sDir = File(rootfs, ".l2s")
        if (!l2sDir.exists()) l2sDir.mkdirs()
        env["PROOT_L2S_DIR"] = l2sDir.absolutePath

        // 终端
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"

        // Android 运行时变量（透传，部分工具需要）
        listOf(
            "ANDROID_ROOT", "ANDROID_DATA", "ANDROID_RUNTIME_ROOT",
            "ANDROID_TZDATA_ROOT", "ANDROID_ART_ROOT", "ANDROID_I18N_ROOT",
            "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "EXTERNAL_STORAGE",
        ).forEach { k ->
            System.getenv(k)?.let { env[k] = it }
        }

        extraEnv.forEach { (k, v) -> env[k] = v }

        return pb
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
            "usr/local/bin/node", "usr/bin/node", "opt/node/bin/node"
        )
        candidates.forEach { rel ->
            if (File(rootfs, rel).isFile) return "/$rel"
        }
        // nvm 风格
        val nvmDir = File(rootfs, "root/.nvm/versions/node")
        if (nvmDir.isDirectory) {
            nvmDir.listFiles()?.firstOrNull()?.let { v ->
                if (File(v, "bin/node").exists()) {
                    return "/root/.nvm/versions/node/${v.name}/bin/node"
                }
            }
        }
        return null
    }

    /** 环境是否已装 Node */
    fun hasNode(): Boolean = nodePath() != null

    /**
     * 在 rootfs 里执行一条命令，输出按行回调。
     * 用于安装流程（apt update / apt install）。
     */
    fun exec(
        command: List<String>,
        workDir: String = "/root",
        onLine: (String) -> Unit = {}
    ): Boolean {
        return try {
            val pb = buildProcess(workDir, command)
            val p = pb.start()
            val reader = p.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                onLine(line)
            }
            val code = p.waitFor()
            Log.i(TAG, "命令退出码: $code")
            code == 0
        } catch (t: Throwable) {
            Log.w(TAG, "执行失败: ${t.message}")
            onLine("执行失败: ${t.message}")
            false
        }
    }

    /**
     * 修复 rootfs 里文件的执行权限。
     *
     * 【为什么需要】
     * 如果 tar 解压没保留 mode，可执行文件会变成 644，
     * 导致 apt 的 http method 等程序报 "Permission denied"。
     * 真机实测：这一步不做，apt update 会静默失败。
     */
    fun fixPermissions() {
        try {
            val execDirs = listOf(
                "bin", "sbin", "usr/bin", "usr/sbin",
                "usr/local/bin", "usr/local/sbin",
                "usr/lib/apt/methods", "usr/lib/dpkg",
                "lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu",
            )
            var fixed = 0
            execDirs.forEach { d ->
                val dir = File(rootfs, d)
                if (!dir.isDirectory) return@forEach
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && !f.canExecute()) {
                        if (f.setExecutable(true, false)) fixed++
                    }
                }
            }
            Log.i(TAG, "权限修复：$fixed 个文件")
        } catch (t: Throwable) {
            Log.w(TAG, "权限修复失败", t)
        }
    }
}
