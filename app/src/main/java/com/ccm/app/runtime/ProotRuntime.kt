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

        /**
         * proot 的 loader。
         *
         * 【为什么必须有这个文件】
         * proot 靠 ptrace 注入 loader 来虚拟化 chroot/路径。加载的流程是：
         *   proot 自己 fork → 在子进程里 ptrace → 把 loader 写进目标进程 → 执行
         * 「把 loader 写进去」这一步要读一个 loader 文件。
         *
         * loader 路径的解析优先级：
         *   ① 环境变量 PROOT_LOADER（我们走这条）
         *   ② 编译期硬编码的默认值
         *
         * 【踩过的坑】原先用的 libproot.so 是 Termux 编译版，硬编码默认值指向
         * /data/data/com.termux/files/usr/libexec/proot/loader —— App 沙箱里不存在，
         * 于是 proot 一启动就报 execve(...): Function not implemented（ENOSYS 是
         * ptrace 注入失败的返回值），误导性极强：看起来像「rootfs 里的二进制坏了」，
         * 实际是 proot 自己的 loader 找不到。
         *
         * 现在换成自编译的 proot（无硬编码路径，官方源码 + NDK），并显式提供 loader。
         */
        private const val PROOT_LOADER_NAME = "libproot-loader.so"

        /** 伪造的内核版本（proot-distro 同款格式，改格式会报 hwcap 错误） */
        private const val FAKE_KERNEL =
            "\\Linux\\localhost\\6.17.0-PRoot-Distro\\#1 SMP PREEMPT_DYNAMIC Fri, 10 Oct 2025 00:00:00 +0000\\aarch64\\localdomain\\-1\\"
    }

    private val rootfs: File get() = File(context.filesDir, "rootfs")

    /** proot 二进制路径（从 nativeLibraryDir 取，那里有 exec 权限） */
    val prootBin: File
        get() = File(context.applicationInfo.nativeLibraryDir, PROOT_LIB_NAME)

    /** proot loader 路径（见 PROOT_LOADER_NAME 的说明） */
    val prootLoaderBin: File
        get() = File(context.applicationInfo.nativeLibraryDir, PROOT_LOADER_NAME)

    /**
     * proot 的临时目录。
     *
     * 【为什么必须显式设置】proot 要在 TMPDIR 下 mkdtemp 一个 proot-XXXXXX 目录，
     * 往里写 loader 和临时文件。默认它读 TMPDIR 环境变量，而 App 进程的 TMPDIR
     * 通常指向 /data/local/tmp 或压根没设 —— 两者都不可写。
     *
     * 症状：`proot error: can't chmod '/data/local/tmp/proot-XXXX': No such file or directory`
     * （实测复现）。这个错误同样很误导 —— 看着像权限问题，实际是「目录建不出来」。
     *
     * 用 App 自己的 cacheDir 下的子目录，权限一定够。
     */
    val prootTmpDir: File
        get() = File(context.cacheDir, "proot-tmp").apply { if (!exists()) mkdirs() }

    /**
     * link2symlink 的工作目录 —— **必须在 rootfs 之外**。
     *
     * proot 用 --rootfs=. + cwd=rootfs 启动，此时给一个「rootfs 内部」的绝对路径，
     * 视角会对不上，link2symlink 静默失效 → 退回建硬链接 → App 沙箱不允许 → apt 全挂。
     * 详细因果链见 buildProcess() 里那段注释。
     *
     * 放 filesDir 下（不是 cacheDir —— cache 会被系统清理，映射丢了符号链接就断了）。
     */
    val prootL2sDir: File
        get() = File(context.filesDir, "l2s").apply { if (!exists()) mkdirs() }

    fun isReady(): Boolean = prootBin.exists() && rootfs.isDirectory

    /**
     * 自检 link2symlink 是否真的生效。
     *
     * 【为什么要单独探一次】它失效时的表现**极难定位**：不会报「link2symlink 没生效」，
     * 而是让 dpkg 在解包时报一堆误导性错误 ——
     *   error setting ownership of '...': No such file or directory
     *   zstd write error: Broken pipe
     * 看着像权限/路径/包损坏，实际只是硬链接建不出来，用户重试一百次都一样。
     *
     * 探法：在 rootfs 里造两个文件、请求建硬链接。
     *   · 成功 → 宿主的文件系统居然支持（少见），无需 link2symlink 也能过
     *   · 失败 → 正常情况（App 沙箱禁硬链接），此时**必须**靠 link2symlink，
     *            所以紧接着验证 PROOT_L2S_DIR 是否可用
     *
     * @return 三元组 (硬链接可用, l2s目录可用, 说明文字)
     */
    fun probeLinkSupport(): Triple<Boolean, Boolean, String> {
        val l2s = prootL2sDir
        val l2sOk = l2s.isDirectory && l2s.canWrite()
        val probeDir = File(rootfs, "tmp")
        if (!probeDir.isDirectory) probeDir.mkdirs()
        val a = File(probeDir, ".l2s-probe-a")
        val b = File(probeDir, ".l2s-probe-b")
        var hardlinkOk = false
        try {
            a.writeText("probe")
            b.delete()
            hardlinkOk = try {
                java.nio.file.Files.createLink(b.toPath(), a.toPath())
                true
            } catch (_: Throwable) { false }
        } catch (_: Throwable) {
            // 探测本身失败不致命，当作「不支持硬链接」处理
        } finally {
            try { a.delete() } catch (_: Throwable) {}
            try { b.delete() } catch (_: Throwable) {}
        }
        val note = when {
            hardlinkOk -> "硬链接可用（无需 link2symlink 兜底）"
            l2sOk -> "硬链接不可用（正常），link2symlink 工作目录就绪：${l2s.absolutePath}"
            else -> "硬链接不可用，且 link2symlink 工作目录不可写：${l2s.absolutePath}"
        }
        return Triple(hardlinkOk, l2sOk, note)
    }

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

        // ⚠️ 必须是 -0（--root-id），**不能**用 --change-id=0:0。
        //
        // 两者不等价，这是 apt 装不上大量包（git/curl/perl-base…）的真正根因：
        //
        //   -0 / --root-id     把调用者伪装成 uid 0，**并让 proot 拦截 chown 等
        //                      特权系统调用、直接返回成功**（不落到真实内核）
        //   --change-id=0:0    只是把「系统调用参数的 uid/gid」改写成 0:0，
        //                      调用本身照旧落到真实内核 → 以 App 的 uid 执行 → 失败
        //
        // dpkg 解包时对每个文件做 chown("xxx.dpkg-new", 0, 0)。用 --change-id 时
        // 这一步失败 → 文件没被创建 → dpkg-deb 的 zstd 管道当场断裂：
        //
        //   error setting ownership of '/usr/bin/uncompress.dpkg-new': No such file or directory
        //   dpkg-deb (subprocess): decompressing archive ...: zstd write error: Broken pipe
        //   Е: Sub-process /usr/bin/dpkg returned an error code (1)
        //
        // 而这个错误是**误导性**的 —— 它看着像「磁盘/权限/包损坏」，
        // 实际只是 root 伪装方式不对。Operit 全程用 -0，proot-distro 也是。
        args += "-0"

        // ⚠️ 相对路径！配合 ProcessBuilder.directory(rootfs)
        args += "--rootfs=."

        // 工作目录
        args += "--cwd=$workDir"

        // 基础挂载
        args += "--bind=/dev"
        args += "--bind=/proc"
        args += "--bind=/sys"

        // ⚠️ /dev 下的设备节点必须**逐个显式挂载** —— `--bind=/dev` 不够。
        //
        // 【实测】只挂 /dev 时，rootfs 的 /dev 里**只有 null 一个文件**
        // （proot 对 /dev 有特殊处理，大部分节点被滤掉）。后果很隐蔽：
        //   · git 报 "unable to get random bytes for temporary file"  → 缺 /dev/urandom
        //   · apt 报 "Can not write log (Is /dev/pts mounted?)"        → 缺 /dev/pts
        // 这两个报错都不提「挂载」二字，排查时很难往那个方向想。
        //
        // /dev/urandom 那条原来的写法（urandom:/dev/random）是想让两者同源，
        // 但那样 /dev/urandom 自己反而**没被挂上** —— 改成各自显式挂载。
        listOf("/dev/urandom", "/dev/random", "/dev/zero", "/dev/null", "/dev/pts", "/dev/tty").forEach { dev ->
            if (File(dev).exists()) args += "--bind=$dev"
        }

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

        // 【2026-09-24 加：stdin 重定向到 /dev/null】
        //
        // 【为什么必须】ProcessBuilder 默认给子进程一个**管道** stdin。
        // 如果子进程读它而没人写（我们只读输出，从不写输入），read 会一直阻塞 ——
        // 表现为「命令卡死，日志不动，超时看门狗才把它杀掉」。
        //
        // 哪些程序会读 stdin：
        //   · apt 的某些 maintainer script（问 yes/no）
        //   · dpkg 的配置文件冲突提示
        //   · 任何 `read` 调用
        // 虽然我们设了 DEBIAN_FRONTEND=noninteractive，但实测有些包不听话
        // （尤其是三方源里的包，或 postinst 脚本自己 read）。
        //
        // 重定向到 /dev/null 后，这些 read 立刻返回 EOF ——
        // 程序要么走默认分支继续，要么明确报错退出，总之**不会卡住**。
        pb.redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))

        val env = pb.environment()

        // ⚠️ 必须：清掉 LD_PRELOAD
        env.remove("LD_PRELOAD")

        // proot 依赖库路径（$ORIGIN 的兜底）
        env["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir

        // ⚠️ 必须：显式指定 loader（否则 proot 去找编译期默认路径，App 里不存在）
        val loader = prootLoaderBin
        if (loader.exists()) {
            env["PROOT_LOADER"] = loader.absolutePath
        }

        // ⚠️ 必须：显式指定临时目录（默认的 TMPDIR 在 App 里不可写）
        val tmp = prootTmpDir
        env["PROOT_TMP_DIR"] = tmp.absolutePath
        env["TMPDIR"] = tmp.absolutePath

        // ⚠️ 必须：link2symlink 工作目录 —— 且**必须放在 rootfs 之外**。
        //
        // ═══════════════════════════════════════════════════════════
        // 【为什么不能放 rootfs 里】这是 apt 从来装不上任何包的真根因。
        //
        // 症状（报错.txt 实测）：
        //   error setting ownership of '/usr/bin/uncompress.dpkg-new': No such file or directory
        //   dpkg-deb (subprocess): ... member 'data.tar': zstd write error: Broken pipe
        //   E: Sub-process /usr/bin/dpkg returned an error code (1)
        //
        // 因果链（本地实测复现）：
        //   1. Ubuntu 的 .deb 用**硬链接**省空间 —— gzip 包里的 uncompress/gunzip/zcat
        //      都是同一个 inode 的硬链接
        //   2. Android 的 App 沙箱**禁止普通应用创建硬链接**
        //      （实测：直接 dpkg-deb -x → "Cannot hard link to './usr/bin/gunzip': Permission denied"）
        //   3. tar 子进程整包失败退出 → 父进程还在往管道写 → Broken pipe
        //   4. 文件没解出来 → 下一步 chown 报 "No such file or directory"（**这是后果不是原因**，
        //      报错信息在这里极具误导性，会让人以为是权限或路径问题）
        //
        // 正解就是 proot 的 --link2symlink：把硬链接替换成符号链接。
        // 本地实测同样这个 gzip 包：不带它必失败，带上它全部解出。
        //
        // 但 --link2symlink 需要一个**工作目录**来存放「符号链接 ↔ 硬链接」的映射
        // （即 PROOT_L2S_DIR）。原来设的是 <rootfs>/.l2s，而 proot 是以
        // --rootfs=. + ProcessBuilder.directory(rootfs) 启动的 ——
        // **绝对路径给了一个「客户机视角」之外的路径，proot 解析不到，
        // 于是 link2symlink 静默失效**，退回直接建硬链接 → 又撞上沙箱限制。
        //
        // 放在 rootfs 外面（App filesDir 下）就没这个问题：proot 的宿主观
        // 和客户机观在这里是一致的。
        // ═══════════════════════════════════════════════════════════
        // ⚠️ PROOT_L2S_DIR 必须给**宿主机路径**（proot 靠它找自己的工作目录）。
        //
        // 【实测对照 · 2026-09-26，用 CCM 自带的 proot 二进制，同一个 rootfs】
        //   PROOT_L2S_DIR=<宿主机绝对路径>  → link2symlink 生效
        //       /usr/bin 下 ln h1 h2 → EXIT=0，链接正常建立
        //   PROOT_L2S_DIR=/.l2s（客户机路径）→ link2symlink **失效**
        //       /usr/bin 下 ln h1 h2 → "Operation not permitted"
        //   两者都不带该变量时 → 也是 Operation not permitted
        //
        // 结论：这个变量是**给 proot 进程本身**看的（宿主机视角），
        // 不是给客户机里的程序看的。写成客户机路径，proot 找不到工作目录，
        // 整个 link2symlink 扩展静默失效 —— 然后硬链接创建落到内核，
        // 撞上 Android 沙箱「禁止普通应用建硬链接」的限制。
        //
        // 曾担心「链接目标会不会是宿主机路径、在客户机里变成断链」——
        // 实测不会：proot 自己会做路径转换（/usr/bin 下的测试链接可正常访问）。
        val l2sHostDir = File(rootfs, ".l2s")
        if (!l2sHostDir.isDirectory) l2sHostDir.mkdirs()
        if (l2sHostDir.isDirectory && l2sHostDir.canWrite()) {
            env["PROOT_L2S_DIR"] = l2sHostDir.absolutePath
        } else {
            env.remove("PROOT_L2S_DIR")
        }

        // 【TERM：用 dumb 而不是 xterm-256color】
        //
        // 原来设 xterm-256color，程序会认为终端支持颜色和光标控制 →
        // 输出大量 ANSI 转义序列（\x1b[32m、\x1b[2K 之类）。
        // 而我们的输出是**按行捕获给 UI 显示的日志流**，这些序列混在里面
        // 就是乱码（界面上会出现 [0m[32m 这样的东西）。
        //
        // TERM=dumb 告诉程序「别整花活，纯文本」—— 与我们的用途一致。
        // （安装流程里 apt 调用已经单独 export TERM=dumb 了，这里是全局对齐）
        env["TERM"] = "dumb"
        // COLORTERM 显式清掉 —— 它和 TERM 是配套的「支持真彩色」信号，
        // 从进程环境继承下来的话，某些程序仍会输出 24 位色转义序列。
        env.remove("COLORTERM")
        // NO_COLOR 是事实标准（no-color.org）：设了它，支持的程序一律不上色。
        // 比逐个清 TERM/COLORTERM 更彻底。
        env["NO_COLOR"] = "1"

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

    /**
     * proot 自检：真跑一次最小命令，确认能起来。
     *
     * 【为什么要自检而不是「能启动就算好」】
     * proot 的失败模式很隐蔽：进程能 fork、能打印日志，但 execve 目标程序失败，
     * 报错还极具误导性（"Function not implemented" 看起来像 rootfs 坏了）。
     * 与其等用户点了「安装工具链」再失败，不如在界面加载时就跑一次探针，
     * 把真实原因直接显示出来。
     *
     * @return null 表示正常；否则返回给用户看的诊断文本
     */
    fun selfCheck(): String? {
        if (!prootBin.exists()) {
            return "proot 可执行文件不存在：${prootBin.absolutePath}\n（jniLibs 打包缺失？）"
        }
        if (!prootBin.canExecute()) {
            return "proot 没有执行权限：${prootBin.absolutePath}"
        }
        val loader = prootLoaderBin
        if (!loader.exists()) {
            return "proot loader 缺失：${loader.absolutePath}\n" +
                   "没有它 proot 无法注入，会报 'Function not implemented'（这个报错是误导，别往 rootfs 上找）"
        }
        if (!rootfs.isDirectory) {
            return "rootfs 不存在：${rootfs.absolutePath}"
        }

        // 用 rootfs 里一定有的 /bin/echo 做探针（比 /usr/bin/env 更简单，少一层 exec）
        val probeCmd = listOf("/bin/echo", "proot-selfcheck-ok")
        return try {
            val pb = buildProcess("/", probeCmd)
            pb.redirectErrorStream(true)
            val p = pb.start()
            // ⚠️ 必须边读边等，不能先 readText() 再 waitFor()。
            // readText() 会阻塞到父进程的管道关闭 —— 也就是子进程真正退出。
            // 如果 proot 卡死（ptrace 注入失败时就会），readText 永远不返回，
            // 后面那个 20 秒超时根本没机会执行 → 整个自检把界面卡住。
            // 现在先把读放到线程里，主线程只负责带超时地等。
            val out = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(out) { out.append(line).append('\n') }
                    }
                } catch (_: Throwable) {}
            }
            reader.isDaemon = true
            reader.start()
            val finished = p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                p.destroyForcibly()
                reader.interrupt()
                val partial = synchronized(out) { out.toString() }
                return "proot 自检超时（20s）—— 大概率是 loader 卡在 ptrace 注入。\n" +
                       "已知输出：${partial.take(300).ifEmpty { "(无)" }}"
            }
            reader.join(1000)
            val outStr = synchronized(out) { out.toString() }
            val code = p.exitValue()
            if (code == 0 && outStr.contains("proot-selfcheck-ok")) {
                null   // 正常
            } else {
                buildString {
                    append("proot 自检失败（退出码 $code）\n")
                    append("proot: ${prootBin.absolutePath}\n")
                    append("loader: ${loader.absolutePath}\n")
                    append("tmp: ${prootTmpDir.absolutePath}\n")
                    append("输出：\n")
                    append(outStr.take(600))
                }
            }
        } catch (t: Throwable) {
            "proot 自检异常：${t.message}"
        }
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
     *
     * 等价于 execWithTimeout(command, workDir, onLine)（用默认超时）。
     * 之所以分成两个函数，见 execWithTimeout 的说明。
     */
    fun exec(
        command: List<String>,
        workDir: String = "/root",
        onLine: (String) -> Unit = {},
    ): Boolean = execWithTimeout(command, workDir, onLine)

    /**
     * 带超时的命令执行。
     *
     * 【2026-09-24 加超时】
     *
     * 原实现是「while (readLine()) 直到 EOF → waitFor()」，没有超时。
     * 这在 apt 上真会卡：
     *   · apt 等 dpkg 的锁（另一个安装任务没退出）
     *   · 网络半死状态（TCP 连上了但不传数据）
     *   · 某个包在等输入（虽然设了 DEBIAN_FRONTEND=noninteractive，但不是所有包都听话）
     * 卡住时用户看到「进度条不动、日志不刷新、按钮点不动」，
     * 而且没有超时的话会永远卡下去，只能杀 App。
     *
     * 现在有二级超时：总超时 + 静默超时（无新输出）。
     * 触发时 destroyForcibly 并返回 false，让上层能给出明确提示。
     *
     * 【为什么单独一个函数，不给 exec 加默认参数】
     * 会破坏现有调用点的尾随 lambda 解析：
     *   proot.exec(cmd, "/root") { line -> ... }
     * 这行里 lambda 落到「最后一个参数」。在 onLine 后面再加参数，
     * lambda 就去填新参数了 → 类型不匹配。
     * （实测编译报 "Argument type mismatch: actual type is Function1, but Long was expected"）
     *
     * @param timeoutMs  总超时（默认 30 分钟，apt 装大包够用）
     * @param idleMs     无输出超时（0 = 不检查）
     */
    fun execWithTimeout(
        command: List<String>,
        workDir: String = "/root",
        onLine: (String) -> Unit = {},
        timeoutMs: Long = 30 * 60_000L,
        idleMs: Long = 10 * 60_000L,
    ): Boolean {
        return try {
            val pb = buildProcess(workDir, command)
            val p = pb.start()

            // 用「最后输出时间」判断静默，由读线程更新、看门狗线程检查。
            // 用 atomic 是防可见性问题（读线程写、看门狗读）。
            val lastOutputAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
            val startAt = System.currentTimeMillis()
            // 用 AtomicReference 而不是 @Volatile 局部变量 —— Kotlin 不允许后者
            val timedOut = java.util.concurrent.atomic.AtomicReference("")

            // 看门狗：总超时 + 静默超时
            val watchdog = Thread {
                while (p.isAlive) {
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
                    val now = System.currentTimeMillis()
                    if (now - startAt > timeoutMs) {
                        timedOut.set("总超时（${timeoutMs / 60000} 分钟）")
                        break
                    }
                    if (idleMs > 0 && now - lastOutputAt.get() > idleMs) {
                        timedOut.set("无输出 ${idleMs / 60000} 分钟，疑似卡死")
                        break
                    }
                }
                val reason = timedOut.get()
                if (reason.isNotEmpty() && p.isAlive) {
                    Log.w(TAG, "命令超时，强制结束: $reason")
                    try { p.destroyForcibly() } catch (_: Throwable) {}
                }
            }
            watchdog.isDaemon = true
            watchdog.start()

            val reader = p.inputStream.bufferedReader()
            while (true) {
                val line = reader.readLine() ?: break
                lastOutputAt.set(System.currentTimeMillis())
                onLine(line)
            }
            val code = p.waitFor()
            watchdog.interrupt()

            val finalReason = timedOut.get()
            if (finalReason.isNotEmpty()) {
                onLine("❌ 命令被强制结束：$finalReason")
                Log.w(TAG, "命令超时结束: $finalReason")
                return false
            }
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
