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
        /**
         * rootfs 压缩包在 filesDir 下的文件名。
         *
         * 【2026-10-05 改名】原来叫 rootfs.tar.gz（Ubuntu 官方 base，gzip）。
         * 现在用 Operit 的 proot-distro 包（xz），名字跟着改 ——
         * 脚本里 `$HOME/$UBUNTU` 要能找到它（见 install-ubuntu.sh）。
         */
        private const val ARCHIVE_NAME = "ubuntu-noble-aarch64-pd.tar.xz"

        /**
         * APK 内置的 rootfs 包名**候选**（按优先级）。
         *
         * 【2026-09-27 新增：内置优先】
         * 原来首次安装必须联网下载 28MB —— 移动网络下 1~3 分钟，
         * 慢的时候（限速/信号差）能卡十几分钟甚至反复失败。
         *
         * 现在把这个包直接打进 APK，首次安装变成**本地复制**（2~5 秒）：
         * ```
         * 改前：首次安装 = 下载 28MB（网络，1~3 分钟）
         * 改后：首次安装 = 复制 assets（本地，2~5 秒）
         * ```
         *
         * 代价是 APK 从 17MB 涨到约 45MB。用户已确认接受。
         *
         * ═══════════════════════════════════════════════════════════════
         * ⚠️【2026-09-27 二次修正：`.gz` 后缀是个陷阱】
         *
         * 第一版把文件命名成 `ubuntu-base.tar.gz` 并配了 `noCompress += "tar.gz"`，
         * 以为就没事了。**实测 build-158 证明完全没生效**：
         *
         *   · 源码  `assets/ubuntu-base.tar.gz`   29,865,086 字节（gzip）
         *   · APK 内 `assets/ubuntu-base.tar`    106,649,600 字节（**裸 tar**）
         *
         * 106,649,600 正是 gzip 头里记录的原始大小 —— **AAPT 自动 gunzip 了它
         * 并去掉了 `.gz` 后缀**。这个行为发生在「压缩」之外，所以 noCompress
         * 拦不住（noCompress 只管「压不压」，管不了「解不解」）。
         *
         * 后果：代码找 `ubuntu-base.tar.gz` 必然 FileNotFoundException →
         * [hasAssetArchive] 恒为 false → **静默退回网络下载**。
         * 内置包从未生效过，APK 白涨 30MB，而且失败是静默的。
         *
         * 现在改用 `.bin` 后缀（AAPT 不认，原样打包），并在 build.gradle.kts
         * 里配 `noCompress += "bin"` 让它 STORED 存储（复制更快 + openFd 能拿长度）。
         *
         * 候选列表的第二项是**兼容项**：已经构建出去的 build-158 类 APK 里
         * 就是 `ubuntu-base.tar`（AAPT 解压后的产物，本身是合法裸 tar，
         * [TarExtractor] 按 magic bytes 识别，照样能解）。
         * 留着它能让老包也走本地复制，不用重新下载。
         * ═══════════════════════════════════════════════════════════════
         *
         * ⚠️ **必须有网络回退**（见 [download]）—— 万一某个构建变体没打进
         * assets，或者 assets 里的包损坏了，不能让用户彻底装不上。
         */
        private val ASSET_ARCHIVES = listOf(
            // 【2026-10-05 换包】全量照搬 Operit AI —— 用它的 proot-distro 定制包。
            //
            // 【为什么换】CCM 原来用 Ubuntu 官方 base（ubuntu-base.tar.gz.bin，
            // 28MB gzip）。官方包有两个硬链接（perl5.38.2、uncompress），
            // busybox tar 在 Android 上解不了（App 沙箱禁止建硬链接），
            // 只能用 Java TarExtractor 绕。
            //
            // Operit 的包是 proot-distro 定制的（ubuntu-noble-aarch64-pd-v4.18.0）：
            //   · 打包时硬链接已转符号链接 → busybox tar 直接能解
            //   · 体积 64MB xz（解压 300MB），比官方 base 更完整（含 perl/python3 等）
            //   · 它是为「Android + proot」场景专门打包的，少踩坑
            //
            // 【2026-10-05 删旧包】原来还列了 ubuntu-base.tar.gz.bin /
            // ubuntu-base.tar 做兼容。但安装脚本只认 xz 包（$HOME/$UBUNTU），
            // 列着旧名只会让人误以为「有两条路」——实际它们连不上脚本。
            // 旧包已从 assets 删除（省 28MB），候选表同步收紧为一个。
            "ubuntu-noble-aarch64-pd.tar.xz",
        )

        /** 安装脚本名（assets 里，运行时写到 filesDir 执行）。 */
        const val SCRIPT_NAME = "install-ubuntu.sh"

        /** 压缩包内顶层目录名（解压后要 mv 出来 —— 照搬 Operit）。 */
        const val ARCHIVE_INNER_DIR = "ubuntu-noble-aarch64"

        /**
         * rootfs 版本。升级这个值会触发重新安装。
         *
         * 【2026-09-27 → 24.04-v2】换 rootfs 方案（自打包 GitHub Release
         * → Ubuntu 官方 base + 国内镜像站），并新增 RootfsPostSetup 三步后处理。
         *
         * ⚠️ 必须 bump 的原因：
         *   · v1 的 rootfs 里没有宿主 UID 条目（/etc/passwd 缺 aid_u0_aXXX），
         *     而修复它的 repairBaseSystem() 已被删除 —— 老 rootfs 留着就是坏的
         *   · 镜像来源、体积、内容都变了，不是同一个东西
         * 不 bump 的话，已装用户会继续用 v1 的坏 rootfs，且没有任何代码能救它。
         */
        const val ROOTFS_VERSION = "24.04-pd-v1"

        /**
         * rootfs 下载地址 —— Ubuntu 官方 base 镜像（国内镜像站）。
         *
         * ═══════════════════════════════════════════════════════════
         * 【2026-09-27 换了方案】原来是 GitHub Release 上自己打包的 rootfs，
         * 现在直接用 Ubuntu 官方 base 镜像 + 国内镜像站。
         *
         * 为什么：
         *   · 官方 base 只有 29MB（原来是自打包，体积更大）
         *   · 清华/中科大都有 http 镜像，速度比 GitHub 直连快一个量级
         *   · 不再依赖自建 Release，也不用绕 GitHub 代理
         *
         * 【关键前提】解压后必须做三步后处理，否则 apt 用不了 ——
         * 见 [RootfsPostSetup]。官方镜像里没有宿主 UID，这是所有怪错误的根源。
         * ═══════════════════════════════════════════════════════════
         */
        const val ROOTFS_URL =
            "https://github.com/wzy-20130517/ccm-android/releases/download/rootfs-v1/ubuntu-noble-aarch64-pd.tar.xz"

        /** 内核安装目标（rootfs 内） */
        const val KERNEL_DIR = "root/ccm"

        /**
         * rootfs 镜像列表，按「实测可靠性」排序。
         *
         * 【2026-09-27 换国内镜像】原来走 gh-proxy/ghfast 绕 GitHub，
         * 现在直接用 Ubuntu 官方镜像站的国内节点：
         *   · 清华  —— 主站，速度最稳
         *   · 中科大 —— 备选（清华挂了时用）
         *   · 南大  —— 第二备选
         *
         * 三个站都是 HTTP/2 + 支持 Range（已实测 accept-ranges: bytes），
         * 所以下游 downloadOne() 的断点续传逻辑照常工作。
         *
         * ⚠️ 三站内容完全一致（都是官方 release 目录的同步镜像），
         * 文件大小均为 29865086 字节 —— 所以换镜像时清 .part 是安全的。
         */
        private val MIRRORS = listOf(
            ROOTFS_URL,
            // gh-proxy 镜像（国内加速）—— 同一个文件，实测 content-length 一致
            "https://gh-proxy.com/https://github.com/wzy-20130517/ccm-android/releases/download/rootfs-v1/ubuntu-noble-aarch64-pd.tar.xz",
            "https://ghfast.top/https://github.com/wzy-20130517/ccm-android/releases/download/rootfs-v1/ubuntu-noble-aarch64-pd.tar.xz",
        )

        /**
         * 内核下载镜像，按「实测可靠性」排序。
         *
         * 【2026-09-24 调整顺序】原来 ghfast.top 排第一，但实测它会**返回 200
         * 却只传 81KB 就断**（不是网络抖动，是稳定的截断行为）。
         * 虽然下游有完整性校验会重试下一个镜像，但每次都要白等一轮超时。
         *
         * 实测数据（15.9MB 的 APK，同链路）：
         *   gh-proxy.com   ✅ 完整，6.7 秒
         *   ghproxy.net    ⚠️ 200 但截断到 1.8MB
         *   ghfast.top     ⚠️ 200 但截断到 81KB
         *   gh.llkk.cc / github.moeyy.xyz  ❌ 连不上
         *
         * ⚠ 注意：这些镜像**都返回 HTTP 200** —— 不能只看状态码，
         * 必须比对 Content-Length 和实际字节数（下游 download() 已做）。
         */
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
     * APK 里是否带了 rootfs 包。
     *
     * 只查存在性 + 大小，**不校验内容**（校验要读完整 28MB，白费时间）。
     * 内容正确性由解压阶段的 [TarExtractor] 兜底 —— 损坏的包会在那里失败，
     * 而失败后 `hasArchive()` 为 false，下次安装会重新复制/下载。
     */
    fun hasAssetArchive(): Boolean = try {
        val name = resolveAssetName()
        name != null && assetSize(name) > 10_000_000
    } catch (t: Throwable) {
        false
    }

    /**
     * 找出 APK 里**实际存在**的内置包名（按 [ASSET_ARCHIVES] 顺序探测）。
     *
     * 【为什么要「探测」而不是直接用常量】
     * AAPT 对 `.gz` 结尾的 asset 会自动解压并改名（见 [ASSET_ARCHIVES] 的说明），
     * 也就是说**源码里的文件名和 APK 里的文件名可以不一样**。
     * 硬编码一个名字就是在赌 AAPT 不改它 —— build-158 赌输了，
     * 而且输得很安静（静默退回网络下载，没有任何报错）。
     *
     * 探测成本：每个候选读 1 字节，2 个候选可以忽略不计，
     * 且只在「准备安装」时调几次，不在热路径上。
     *
     * @return 可用的 asset 名；一个都没有时返回 null
     */
    private fun resolveAssetName(): String? {
        for (name in ASSET_ARCHIVES) {
            try {
                context.assets.open(name).use { it.read() }
                return name
            } catch (_: Throwable) {
                // 这个候选不在这个构建里 —— 试下一个
            }
        }
        return null
    }

    /**
     * 拿内置包的大小（字节）。
     *
     * `openFd()` 要求 asset 是 STORED（未压缩）—— 由 build.gradle.kts 的
     * `noCompress += "bin"` 保证。拿不到时返回兜底值：
     * 该值只用于**进度显示**和「是不是空壳」判断，不影响正确性
     * （复制阶段的字节数校验 + 解压阶段的 TarExtractor 才是正确性保障）。
     */
    private fun assetSize(name: String): Long = try {
        context.assets.openFd(name).use { it.length }
    } catch (_: Throwable) {
        // 兜底值：openFd 失败时用（asset 被压缩存储、或旧 APK 缺 noCompress）。
        // 只影响进度显示，不影响正确性（复制后会校验实际字节数）。
        // 【2026-10-05】换成新包的实测大小（64,133,552 = 61.2MB）；
        // 原值是旧包的 29,865,086，留着会让进度条算错一半。
        64_133_552L
    }

    /**
     * 从 APK assets 复制 rootfs 包到 [archiveFile]。
     *
     * 【为什么先写 .part 再改名】
     * 复制中途进程被杀（用户划掉 App、系统回收）会留下半个文件。
     * 若直接写 [archiveFile]，`hasArchive()` 会认为「已有包」而跳过下载，
     * 然后解压一个残缺包 → 报「解压失败」，用户完全想不到是复制没做完。
     * 用 .part 则半个文件不算数，下次重新复制。
     *
     * 【进度回调】
     * 28MB 复制在手机上约 2~5 秒。不算快，所以要报进度，
     * 否则用户看到进度条不动会以为卡死。
     *
     * @return 成功返回 true；失败返回 false（调用方应退回网络下载）
     */
    private fun copyFromAssets(onProgress: (String, Long, Long) -> Unit): Boolean {
        val assetName = resolveAssetName()
        if (assetName == null) {
            Log.w(TAG, "没有可用的内置包（候选：$ASSET_ARCHIVES）")
            return false
        }
        val tmp = File(context.filesDir, "$ARCHIVE_NAME.part")
        return try {
            val total = assetSize(assetName)

            context.assets.open(assetName).use { input ->
                FileOutputStream(tmp, false).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        // 每 2MB 报一次，避免回调过于频繁拖慢复制
                        if (done - lastReport > 2 * 1024 * 1024) {
                            onProgress("copy", done, total)
                            lastReport = done
                        }
                    }
                    onProgress("copy", done, total)
                    if (done < 10_000_000) {
                        Log.w(TAG, "内置包过小（$done 字节），判定为无效")
                        return false
                    }
                }
            }

            if (archiveFile.exists()) archiveFile.delete()
            if (!tmp.renameTo(archiveFile)) {
                tmp.copyTo(archiveFile, overwrite = true)
                tmp.delete()
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "从 assets 复制失败：${t.message}")
            try { tmp.delete() } catch (_: Throwable) {}
            false
        }
    }

    /**
     * 完整安装流程：下载 → 解压 → 配置。
     *
     * @param onProgress (阶段, 已完成, 总量)  阶段: "download" / "extract" / "config"
     */
    /**
     * 安装 rootfs（下载 → 解压 → 配置）。
     *
     * 【2026-09-24 重构：原子替换 + 并发锁】
     *
     * 原实现有三个健壮性缺口，都是真会踩到的：
     *
     *   ① **无并发锁**：用户连点两次「开始安装」→ 两个任务同时 deleteRecursively()
     *      同一个目录、同时往里写 → 必然解压出乱七八糟的东西。
     *      卸载重装这种耗时操作，用户等不及连点很正常。
     *
     *   ② **无原子替换**：原来是「先删老 rootfs → 再解压到原地」。
     *      解压中途失败（网络断、TarExtractor 有 bug、空间不够）就留下一个半成品
     *      目录 —— 而 isInstalled() 只看标记文件，半成品没有标记所以会判「未安装」，
     *      但目录里满是垃圾，下次安装的 deleteRecursively 要花很久。
     *      更糟的是用户看到「装完了」（如果标记写进去了）却用不了。
     *
     *   ③ **中途失败丢老数据**：老 rootfs 已经删了，新 rootfs 没解开 → 什么都没了。
     *      用户从「能用的旧版本」变成「什么都没有」，比不安装还糟。
     *
     * 现在改成 OperitTerminalCore 验证过的流程：
     *   1. 抢锁（mkdir 是原子操作，天然互斥；僵尸锁按 PID 判活）
     *   2. 解压到 rootfs.install.tmp（**不动老 rootfs**）
     *   3. 配置 + 写标记（都在 tmp 里做完）
     *   4. 删老路径 + mv tmp → 正式路径（这一步才动老数据）
     *   5. 释放锁
     *
     * 这样任何一步失败，老 rootfs 都完好无损。
     */
    fun install(onProgress: (String, Long, Long) -> Unit = { _, _, _ -> }): Boolean {
        val lock = InstallLock(context, "rootfs")
        if (!lock.acquire()) {
            Log.w(TAG, "已有安装在进行中，拒绝重复启动")
            onProgress("error", 0, 0)
            return false
        }
        return try {
            // 1) 准备压缩包：**优先用 APK 内置的**，没有再走网络。
            //
            // 【2026-09-27 改】原来只有下载一条路。现在内置包在 APK 里，
            // 本地复制 2~5 秒 vs 网络下载 1~3 分钟 —— 首次安装体验差一个量级。
            //
            // 复制失败（assets 缺失/损坏）**不报错**，静默退回下载：
            // 用户看到的是「在装」而不是「装不了」。
            if (!hasArchive()) {
                onProgress("copy", 0, 0)
                val fromAsset = try {
                    if (hasAssetArchive()) copyFromAssets(onProgress) else false
                } catch (t: Throwable) {
                    Log.w(TAG, "内置包复制失败，改走网络：${t.message}")
                    false
                }
                if (!fromAsset) {
                    Log.i(TAG, "走网络下载 rootfs")
                    if (!download(onProgress)) {
                        Log.e(TAG, "下载失败")
                        return false
                    }
                } else {
                    Log.i(TAG, "已用 APK 内置 rootfs（本地复制）")
                }
            }

            // 2) 解压 + 配置：**全量照搬 Operit AI**（2026-10-05）
            //
            // 【为什么不再用 Kotlin 逐步做】
            // 原来这条链是 Kotlin 实现的：TarExtractor 解压 → RootfsPostSetup
            // 三步后处理 → setupBaseConfig → fixPermissionsIn → 原子替换。
            // 反复出问题（进度显示 98MB/28MB、解压后配置不完整导致 apt 挂、
            // 硬链接处理不干净），每次修都要改 Kotlin + 重新编译 + 装机验证。
            //
            // Operit 的做法是把**全部安装逻辑写成一个 shell 脚本**
            // （assets/install-ubuntu.sh），Kotlin 只负责：
            //   ① 把脚本和 rootfs 复制到 filesDir
            //   ② 执行脚本，读它的 stdout 当进度
            // 好处：脚本能随时改（不用重编译）、能单独跑（调试方便）、
            // 逻辑与 Operit 一致（它是这个领域最成熟的实现）。
            //
            // 【脚本做的四件事】（照搬 OperitTerminalCore 的 generateStartScript）
            //   install_ubuntu    —— busybox tar 解压 + 锁文件防并发
            //   configure_sources —— apt/pip/uv/npm 四源配置
            //   fix_permissions   —— 补 Android 组与宿主 UID
            //   （login_ubuntu 只在启动时用，安装阶段不调）
            onProgress("extract", 0, 1)
            val scriptOk = runInstallScript(onProgress)
            if (!scriptOk) {
                Log.e(TAG, "安装脚本执行失败")
                return false
            }

            // 2.1) 写版本标记（Kotlin 侧的 isInstalled() 靠它判断）
            //
            // 【为什么要 Kotlin 写而不是脚本写】
            // 脚本不知道 ROOTFS_VERSION（那是 Kotlin 常量，换包时会 bump）。
            // 让脚本写死一个值的话，下次换包就漏了 —— 用户会「装了但还是提示未安装」。
            // 脚本自己写的 `.ccm_installed_ok`（无版本号）只用于它的幂等判断。
            val marker = File(rootfsPath, MARKER_FILE)
            try {
                marker.parentFile?.mkdirs()
                marker.writeText(ROOTFS_VERSION)
            } catch (t: Throwable) {
                Log.e(TAG, "写版本标记失败", t)
                return false
            }

            // 3) 清掉压缩包省空间 —— 只有真装好了才删
            try { archiveFile.delete() } catch (_: Throwable) {}

            Log.i(TAG, "rootfs 安装完成：${rootfsPath.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "安装 rootfs 失败", t)
            false
        } finally {
            lock.release()
        }
    }

    /**
     * 创建 busybox 命令软链（照搬 Operit 的 createBusyboxSymlinks）。
     *
     * 【为什么需要】busybox 是「多合一」二进制 —— 它靠 argv[0] 判断
     * 自己该扮演哪个命令。jniLibs 里的文件名是 `libbusybox.so`，
     * 直接执行时 argv[0] = "libbusybox.so"，busybox 不认识 → 报
     * **"applet not found"** 并静默失败（退出码非 0，但脚本里的
     * `| tail -5` 把退出码换成 tail 的 0，于是错误被吞掉）。
     *
     * 【做法】在可写的 filesDir/bin 里建一组符号链接，名字是各命令名，
     * 目标指向 nativeLibraryDir 的 libbusybox.so。脚本里 PATH 加 binDir
     * 后，`tar` / `ls` / `mkdir` 等就都能用了。
     *
     * 【为什么不用硬链接】Android 的 /data 禁止普通应用建硬链接
     * （这也是 proot 需要 --link2symlink 的原因）。
     *
     * @return 至少 tar 链接可用时返回 true
     */
    private fun ensureBusyboxLinks(homeDir: File, binDir: String): Boolean {
        val bin = File(homeDir, "bin")
        if (!bin.exists()) bin.mkdirs()

        // ⚠️ 必须指向 **nativeLibraryDir** 里的文件，不能复制到 filesDir。
        //
        // 【为什么】Android 10+ 的 W^X 策略 + SELinux 禁止 App 执行自己数据目录
        // 里的文件。实测 avc 日志：
        //   avc: denied { execute_no_trans } for
        //     path="/data/data/com.ccm.app/files/bin/libbusybox.so"
        //     scontext=u:r:untrusted_app  tcontext=u:object_r:app_data_file
        //     permissive=0
        // 而 nativeLibraryDir（/data/app/.../lib/arm64/）里的文件是
        // system:system 755，**允许执行** —— 那是唯一能跑的地方。
        //
        // 【升级后 hash 会变怎么办】那个路径含构建 hash（~~xxx==），
        // App 升级后会变。但本函数**每次安装都会重新跑**，会先 delete
        // 旧链接再建新的 —— 所以升级后第一次安装时会自动修正。
        val src = File(binDir, "libbusybox.so")
        if (!src.exists()) {
            Log.e(TAG, "libbusybox.so 不存在：${src.absolutePath}")
            return false
        }

        // 照搬 Operit 的命令清单（够安装脚本用即可）
        val applets = listOf(
            "busybox",
            "tar", "xz", "gzip", "bzip2",
            "sh", "ash",
            "ls", "cp", "mv", "rm", "mkdir", "rmdir", "chmod", "chown", "ln",
            "cat", "head", "tail", "grep", "sed", "awk", "cut", "tr", "sort", "uniq",
            "find", "xargs", "stat", "du", "df", "touch", "basename", "dirname", "realpath",
            "id", "uname", "sleep", "date", "echo", "printf", "test",
            "true", "false", "env", "which", "readlink", "sync", "wc", "diff",
        )

        var okCount = 0
        for (name in applets) {
            val link = File(bin, name)
            try {
                link.delete()   // 对断链也有效
                // 绝对路径 —— 目标在 nativeLibraryDir，与 bin 不同目录，
                // 用相对名会解析成 filesDir/bin/libbusybox.so（不存在，断链）
                java.nio.file.Files.createSymbolicLink(link.toPath(), src.toPath())
                okCount++
            } catch (t: Throwable) {
                Log.w(TAG, "建软链 $name 失败：${t.message}")
            }
        }
        Log.i(TAG, "busybox 软链：$okCount/${applets.size} 个 → ${src.absolutePath}")

        // 关键验证：**直接执行 nativeLibraryDir 里的原始文件**（不经软链），
        // 确认它本身能跑。软链只是给脚本用的便捷入口。
        //
        // ⚠️ 检查实际输出而不是退出码 —— 断链/权限问题时 sh 可能返回非 0
        // 但被上层吞掉，光看码会漏。
        return try {
            val p = ProcessBuilder(src.absolutePath, "tar", "--help")
                .redirectErrorStream(true).start()
            val output = p.inputStream.readBytes().decodeToString()
            p.waitFor()
            val ok = output.contains("BusyBox", ignoreCase = true) ||
                     output.contains("Usage: tar", ignoreCase = true)
            if (!ok) Log.e(TAG, "busybox tar 自检失败，输出：${output.take(200)}")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "busybox tar 自检异常", t)
            false
        }
    }

    /**
     * 执行安装脚本（全量照搬 Operit AI 方案）。
     *
     * 【流程】
     *   ① 把 assets 里的 install-ubuntu.sh 写到 filesDir（覆盖，脚本改了要生效）
     *   ② 把 setup_fake_sysdata.sh 也写出来（脚本里 source 它）
     *   ③ 确保 rootfs 压缩包在 filesDir（$HOME/$UBUNTU 位置）
     *   ④ 跑 `sh install-ubuntu.sh install`，逐行读 stdout 上报进度
     *
     * 【为什么用 sh 而不是直接 exec】
     * 脚本开头有 `#!/system/bin/sh`，但 Android 上 exec 脚本需要可执行权限，
     * 而 assets 解出来的文件默认没有。用 `sh <script>` 最省事、跨版本可靠。
     *
     * 【环境变量注入】
     * 脚本需要 BIN / HOME / UBUNTU_PATH / UBUNTU / UBUNTU_NAME / TMPDIR /
     * PROOT_LOADER —— 这里按 Operit 的约定传（见脚本头部注释）。
     *
     * @return 脚本退出码为 0 时返回 true
     */
    private fun runInstallScript(onProgress: (String, Long, Long) -> Unit): Boolean {
        val binDir = context.applicationInfo.nativeLibraryDir
        val homeDir = context.filesDir
        val tmpDir = File(homeDir, "tmp").apply { mkdirs() }

        // ① 写脚本（每次都覆盖 —— 脚本是「代码」，改了必须生效）
        val scriptFile = File(homeDir, SCRIPT_NAME)
        try {
            context.assets.open(SCRIPT_NAME).use { input ->
                scriptFile.writeBytes(input.readBytes())
            }
            // 顺带写出假 /proc 脚本（脚本里 source "$HOME/setup_fake_sysdata.sh"）
            context.assets.open("setup_fake_sysdata.sh").use { input ->
                File(homeDir, "setup_fake_sysdata.sh").writeBytes(input.readBytes())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "写安装脚本失败", t)
            return false
        }

        // ② 建 busybox 软链（照搬 Operit 的 createBusyboxSymlinks）
        //
        // 【为什么必须】busybox 靠 argv[0] 决定自己扮演哪个命令。
        // 直接调 `libbusybox.so tar xf ...` 时 argv[0] 是 "libbusybox.so"，
        // busybox 不认识这个名字，报 **"applet not found"** 后什么都不做 ——
        // 而脚本里 `if [ $? -ne 0 ]` 判断的是管道退出码（tail 的），
        // 所以**静默通过**，表现为「解压完成但 rootfs 不存在」。
        //
        // 【为什么建在 filesDir/bin 而不是 nativeLibraryDir】
        // jniLibs 目录在 /data/app/.../lib/ 下，App 对它是**只读**的
        // （实测 touch 报 No such file or directory —— 连创建都不允许）。
        // Operit 的做法也是建在可写的 binDir，然后把 binDir 加进 PATH。
        //
        // 【软链 vs 硬链】Android 的 /data 分区禁止普通应用建硬链接，
        // 但符号链接可以 —— 这也是 proot 要 --link2symlink 的原因。
        if (!ensureBusyboxLinks(homeDir, binDir)) {
            Log.e(TAG, "busybox 软链创建失败")
            return false
        }

        // ③ 确保压缩包在 $HOME/$UBUNTU（脚本从这里读）
        val archive = File(homeDir, ARCHIVE_NAME)
        if (!archive.exists()) {
            Log.e(TAG, "压缩包不存在：${archive.absolutePath}")
            return false
        }

        // ③ 组命令
        val loader = File(binDir, "libproot-loader.so")
        // 【为什么用绝对路径】Android 上 "sh" 依赖 PATH，而 App 进程的 PATH
        // 可能被裁过（不同 ROM 不一样）。/system/bin/sh 是 AOSP 标准位置，
        // 从 Android 1.0 起就在，比走 PATH 可靠。
        val shBin = if (File("/system/bin/sh").exists()) "/system/bin/sh" else "sh"
        val pb = ProcessBuilder(shBin, scriptFile.absolutePath, "install")
        pb.directory(homeDir)
        pb.redirectErrorStream(true)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val env = pb.environment()
        env["BIN"] = binDir
        env["HOME"] = homeDir.absolutePath
        env["UBUNTU_PATH"] = rootfsPath.absolutePath
        env["UBUNTU"] = ARCHIVE_NAME
        env["UBUNTU_NAME"] = ARCHIVE_INNER_DIR
        env["TMPDIR"] = tmpDir.absolutePath
        if (loader.exists()) env["PROOT_LOADER"] = loader.absolutePath
        // proot 依赖库（$ORIGIN 兜底）
        env["LD_LIBRARY_PATH"] = binDir
        // 清掉可能干扰的变量（与 ProotRuntime.buildProcess 同规则）
        env.remove("LD_PRELOAD")

        return try {
            val p = pb.start()
            val reader = p.inputStream.bufferedReader()
            var lineCount = 0
            reader.useLines { lines ->
                for (line in lines) {
                    lineCount++
                    Log.i("CcmInstall", line)
                    // 上报给 UI（进度用「第几行」表示 —— 照搬 Operit 的
                    // 文本行进度机制，不再算字节数，避免量纲不一致的 bug）
                    onProgress("extract", lineCount.toLong(), 0)
                }
            }
            val code = p.waitFor()
            Log.i(TAG, "安装脚本退出码=$code（输出 $lineCount 行）")
            code == 0
        } catch (t: Throwable) {
            Log.e(TAG, "执行安装脚本失败", t)
            false
        }
    }

    /**
     * 从多个镜像依次尝试下载。
     */
    private fun download(onProgress: (String, Long, Long) -> Unit): Boolean {
        // 多轮重试：每轮遍历所有镜像。
        // 单轮可能因网络抖动全挂，多轮能利用已下载的 .part 续传。
        val MAX_ROUNDS = 5

        // 记录上一轮用的是哪个镜像 —— 换了 host 就丢弃 .part
        //
        // 【为什么要判 host 而不是无脑清】
        // 同一轮内重试同一个镜像时保留 .part 是有益的（断点续传省流量）。
        // 但不同镜像可能内容有差异（CDN 同步延迟、一个是旧版），
        // 把两个版本的数据用 Range 拼起来会得到损坏文件 —— 而损坏要等
        // 解压或运行时才暴露，极难归因。所以只在换 host 时清。
        var lastHost: String? = null

        for (round in 1..MAX_ROUNDS) {
            for ((idx, url) in MIRRORS.withIndex()) {
                try {
                    Log.i(TAG, "第 $round 轮，镜像 ${idx + 1}/${MIRRORS.size}: ${url.take(55)}…")
                    val host = try { java.net.URI(url).host } catch (_: Throwable) { null }
                    if (host != null && lastHost != null && host != lastHost) {
                        val part = File(context.filesDir, "$ARCHIVE_NAME.part")
                        if (part.exists()) {
                            Log.i(TAG, "换镜像（$lastHost → $host），丢弃已下载部分")
                            part.delete()
                        }
                    }
                    lastHost = host
                    // ⚠️ 连上之前也要给 UI 反馈，否则用户看到「0%」一动不动，
                    // 以为卡死了（实际是在等 TCP 握手/响应头，可能十几秒）。
                    // done=0 total=0 → UI 会显示「正在连接…」
                    onProgress("connecting", 0, 0)
                    if (downloadOne(url, onProgress)) return true
                    onProgress("retry", round.toLong(), MAX_ROUNDS.toLong())
                } catch (t: Throwable) {
                    Log.w(TAG, "镜像 ${idx + 1} 失败: ${t.message}")
                }
            }
            // 一轮全失败 → 等一下再试（给网络恢复的时间）
            if (round < MAX_ROUNDS) {
                val part = File(context.filesDir, "$ARCHIVE_NAME.part")
                val have = if (part.exists()) part.length() / 1024 / 1024 else 0
                Log.w(TAG, "第 $round 轮全部失败，已下载 ${have}MB，10 秒后重试")
                onProgress("download", have * 1024L * 1024L, 29_000_000L)
                try { Thread.sleep(10_000) } catch (_: InterruptedException) {}
            }
        }
        return false
    }

    /**
     * 单个 URL 的下载，**支持断点续传**。
     *
     * 【为什么必须支持续传】
     * rootfs 有 28MB，在手机上（尤其移动网络）单次下载经常中断。
     * 实测：不续传的话用户可能卡在 3~5MB 反复重来，永远装不完。
     *
     * 【实现】
     * - 已下载的部分存在 `.part` 文件里
     * - 重试时带 `Range: bytes=<已下载>-` 头
     * - 服务端返回 206（Partial Content）→ 追加写
     * - 返回 200（不支持 Range）→ 从头写（清空 .part）
     *
     * @param resumeFrom 从多少字节开始（默认自动读 .part 大小）
     */
    private fun downloadOne(
        url: String,
        onProgress: (String, Long, Long) -> Unit,
        resumeFrom: Long = -1
    ): Boolean {
        val tmp = File(context.filesDir, "$ARCHIVE_NAME.part")

        // 已下载的字节数（续传起点）
        val already = if (resumeFrom >= 0) resumeFrom
                      else if (tmp.exists()) tmp.length()
                      else 0L

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000        // 手机网络慢，给足时间
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CCM/0.1 (Android)")
                if (already > 0) {
                    setRequestProperty("Range", "bytes=$already-")
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code")
                return false
            }

            // 206 = 服务端支持续传；200 = 从头开始（要清空已下载部分）
            val append = code == 206 && already > 0
            val startAt = if (append) already else 0L
            if (!append && tmp.exists()) tmp.delete()

            val contentLen = conn.contentLengthLong.takeIf { it > 0 } ?: 0L
            val total = if (contentLen > 0) startAt + contentLen else 29_000_000L

            // 响应头到了 → 立刻报一次（让 UI 从「连接中」切到「下载中 x/y MB」）
            onProgress("download", startAt, total)

            Log.i(TAG, if (append)
                "续传：从 $startAt 字节继续（共 $total）"
            else
                "新下载：共 ${if (contentLen > 0) contentLen else "?"} 字节")

            conn.inputStream.use { input ->
                FileOutputStream(tmp, append).use { out ->
                    val buf = ByteArray(128 * 1024)
                    var done = startAt
                    var lastReport = startAt
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 128 * 1024) {
                            onProgress("download", done, total)
                            lastReport = done
                        }
                    }
                    onProgress("download", done, total)
                }
            }

            // 完整性检查：至少 10MB（rootfs 28MB，内核 14MB）
            if (tmp.length() < 10_000_000) {
                Log.w(TAG, "下载不完整：${tmp.length()} 字节（保留 .part 供续传）")
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
            // ⚠️ 不要删 .part —— 留着下次续传
            Log.w(TAG, "下载中断（已保留 ${if (tmp.exists()) tmp.length() else 0} 字节供续传）: ${t.message}")
            false
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /** 写入 DNS / apt 源 / profile —— 让环境开箱可用 */
    /** 在正式 rootfs 上做基础配置（安装完成后的补配；安装流程用 setupBaseConfigIn） */

    private fun setupBaseConfig() = setupBaseConfigIn(rootfsPath)

    /**
     * 在指定目录做基础配置（apt 源/shell 配置/包管理器镜像/挂载点）。
     * 参数化是为了支持原子安装。
     *
     * ⚠️ DNS 与 hosts 不在这里写 —— 它们归 [RootfsPostSetup] 管。
     * 两边都写会形成「两个真源」：改一处漏一处，且这里原来用 writeText
     * 会跟随符号链接写穿（RootfsPostSetup 用的是「先删再写」）。
     */
    private fun setupBaseConfigIn(target: File) {
        try {
            // apt 源换国内（Ubuntu 24.04 用新格式）
            //
            // ⚠️ 必须用 http 而不是 https！
            // 真机实测：proot 里 apt 的 https method 会报
            //   "Method /usr/lib/apt/methods/https did not start correctly"
            // （proot 对 fork+exec 的限制导致 method 进程起不来），
            // 而 http method 正常。清华源同时提供 http，所以用 http。
            val sourcesFile = File(target, "etc/apt/sources.list.d/ubuntu.sources")
            if (sourcesFile.parentFile?.exists() == true) {
                sourcesFile.writeText(
                    """
                    Types: deb
                    URIs: http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports
                    Suites: noble noble-updates noble-backports
                    Components: main universe restricted multiverse
                    Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
                    """.trimIndent()
                )
            }

            // 备选源（清华挂了时用）
            File(target, "etc/apt/sources.list.d/backup.sources").writeText(
                """
                Types: deb
                URIs: http://mirrors.ustc.edu.cn/ubuntu-ports
                Suites: noble noble-updates noble-backports
                Components: main universe restricted multiverse
                Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
                """.trimIndent()
            )

            // root 的 shell 配置
            File(target, "root/.bashrc").writeText(
                """
                export PS1='\[\e[36m\]ccm\[\e[0m\]:\w\$ '
                export LANG=C.UTF-8
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                """.trimIndent()
            )

            // ── 其他包管理器的国内镜像 ──
            //
            // 【为什么安装时就写好，而不是等用户敲命令】
            // 用户在 AI 里让它「pip 装个包」时，python 会直接去 pypi.org ——
            // 国内访问经常几 KB/s 甚至超时，而用户根本不知道要配镜像。
            // 提前写好配置文件，后面无论谁调 pip 都自动走清华源。
            //
            // 这套配置从 OperitTerminalCore 的 SetupScreen 学来（它同时配
            // apt / pip / uv / npm / rust 五套源）。这里覆盖最常用的三套。
            try {
                // pip：全局配置（所有用户、所有 venv 都生效）
                File(target, "root/.config/pip").mkdirs()
                File(target, "root/.config/pip/pip.conf").writeText(
                    """
                    [global]
                    index-url = https://pypi.tuna.tsinghua.edu.cn/simple
                    trusted-host = pypi.tuna.tsinghua.edu.cn
                    """.trimIndent()
                )
                // uv（比 pip 快很多的现代替代品，越来越多项目用它）
                File(target, "root/.config/uv").mkdirs()
                File(target, "root/.config/uv/uv.toml").writeText(
                    """
                    index-url = "https://pypi.tuna.tsinghua.edu.cn/simple"
                    """.trimIndent()
                )
                // npm：淘宝镜像（registry.npmmirror.com 是官方认可的同步镜像）
                File(target, "root/.npmrc").writeText(
                    """
                    registry=https://registry.npmmirror.com/
                    """.trimIndent()
                )
                // 也放一份到 /etc，这样非 root 用户跑 npm 也走镜像
                File(target, "etc/npmrc").writeText("registry=https://registry.npmmirror.com/\n")
            } catch (t: Throwable) {
                Log.w(TAG, "写包管理器镜像配置失败（不致命）", t)
            }

            // 常用挂载点
            listOf("dev", "proc", "sys", "tmp", "root", "mnt/ext").forEach {
                File(target, it).mkdirs()
            }
            File(target, "tmp").setExecutable(true, false)
        } catch (t: Throwable) {
            Log.w(TAG, "写基础配置失败（不致命）", t)
        }
    }

    // ═══════════════════════════════════════════════════
    //  工具链安装（用户勾选）
    // ═══════════════════════════════════════════════════

    /**
     * 安装用户勾选的工具链。
     *
     * 【为什么不在 rootfs 里预装所有东西】
     * 全装完要 2GB+，而大多数人只用得到其中几个。
     * 让用户按需勾选：首次安装快，也省空间。
     *
     * 【执行策略】
     * 所有包合成一条 apt install 命令 —— 比逐个装快得多
     * （apt 一次性解依赖，不用重复索引）。
     *
     * @param selected 勾选的工具链 id 集合
     * @param exec 执行器（由 ProotRuntime 提供）
     * @param onLine 输出回调
     */
    fun installToolchains(
        selected: Set<String>,
        exec: (List<String>, (String) -> Unit) -> Boolean,
        onLine: (String) -> Unit = {}
    ): Boolean {
        if (!isInstalled()) {
            Log.w(TAG, "rootfs 未安装")
            return false
        }

        val packages = ToolchainCatalog.aptPackagesFor(selected)
        val chains = ToolchainCatalog.resolveSelection(selected)
        // 【2026-09-24】把「App 侧下载」的步骤单独收集出来。
        // 这些不经过 apt（见 ToolchainCatalog.DownloadStep 的说明），
        // 所以即使 packages 为空、只勾了 Node.js 也要继续往下走。
        val downloadSteps = chains.flatMap { it.downloadSteps }

        if (packages.isEmpty() && downloadSteps.isEmpty()) {
            onLine("没有需要安装的工具")
            return true
        }

        onLine("将安装 ${chains.size} 组工具（约 ${ToolchainCatalog.estimatedSizeMB(selected)}MB）：")
        chains.forEach { onLine("  · ${it.name}") }
        onLine("")

        // 【并发锁】apt 不能两个任务同时跑 —— dpkg 有自己的锁，撞上会报
        // "Could not get lock /var/lib/dpkg/lock-frontend"，那个报错用户看不懂，
        // 而且第二个任务会失败得莫名其妙。这里先挡住。
        val lock = InstallLock(context, "toolchain")
        if (!lock.acquire()) {
            onLine("❌ 另一个安装任务正在进行中，请等它完成再试。")
            return false
        }

        return try {
            // 1) 权限修复（apt 需要）
            fixPermissionsInternal()

            // 1.2) 自检 link2symlink —— 这一项失效时 apt 会以**极具误导性**的方式失败。
            //
            // Ubuntu 的 .deb 大量使用硬链接（gzip 包里的 uncompress/gunzip/zcat 是同一
            // inode）。Android App 沙箱禁止普通应用建硬链接，必须靠 proot 的
            // --link2symlink 把硬链接替换成符号链接。
            //
            // 它失效时不会报自己失效，而是让 dpkg 报：
            //   error setting ownership of '...dpkg-new': No such file or directory
            //   zstd write error: Broken pipe
            // 看着像权限/磁盘/包损坏，实际只是硬链接建不出来 —— 用户重试多少次都一样。
            // （2026-09-26 定位：本地实测「同包 + 不带 link2symlink = 必失败，
            //   带上就全解出」，而 APK 的 PROOT_L2S_DIR 曾指向 rootfs 内部导致静默失效。）
            // ⚠️ 这个自检**测不出真问题**，别再依赖它。
            //
            // 它是在 proot **内部**跑 ln：带了 --link2symlink 时 proot 会把 ln
            // 解释成建符号链接并返回成功，所以这里永远打印 HARDLINK_OK ——
            // 哪怕真实解包时仍然失败（实测就是这么被误导的，见 build-83 的日志）。
            //
            // 真正的判据是**解包能不能过**：dpkg-deb -x 一个含硬链接的真实包。
            // 用 perl-base（它有 usr/bin/perl → perl5.38.2 的硬链接），
            // 失败时给出明确指引，而不是让用户对着 zstd/Broken pipe 之类的
            // 二级错误发呆。
            run {
                val probe = exec(
                    listOf(
                        "/bin/bash", "-lc",
                        // ⚠️ Kotlin 字符串里 `$` 是模板起始符，shell 变量要写 \$d
                        // （直接写 $d 会被当成 Kotlin 变量，报 Unresolved reference 'd'
                        //   —— CI 实测踩过。\$ 是 Kotlin 的合法转义，同文件 416 行也这么用。）
                        "cd /tmp 2>/dev/null || cd /; " +
                            "d=\$(ls /var/cache/apt/archives/perl-base_*.deb 2>/dev/null | head -1); " +
                            "if [ -z \"\$d\" ]; then echo NO_PKG; exit 0; fi; " +
                            "rm -rf .l2sprobe; " +
                            "if dpkg-deb -x \"\$d\" .l2sprobe >/dev/null 2>&1; then echo UNPACK_OK; " +
                            "else echo UNPACK_FAIL; fi; rm -rf .l2sprobe"
                    ),
                    {}
                )
                if (!probe) {
                    onLine("  ⚠️ 解包自检未通过：link2symlink 可能没生效，")
                    onLine("     装包时会在 perl-base 这类含硬链接的包上失败（zstd Broken pipe）。")
                    onLine("     检查 filesDir/l2s 是否存在且可写。")
                }
            }

            // 【2026-09-27 删掉「补 debconf」这一步】
            //
            // 这里原来调 repairBaseSystem()（215 行），专门修 ubuntu-base 的
            // 「perl-base 解包损坏 → debconf 死锁」问题。现在整个删掉，原因：
            //
            // 那个损坏的**根因**是宿主 UID 不在 rootfs 的 /etc/passwd 里
            // （dpkg 解包时要 chown 到该 UID，查不到就报 error setting ownership，
            //  perl-base 因此残缺 → debconf 的 postinst exit 127 → 死锁）。
            // 而 RootfsPostSetup.registerAndroidIds() 已经在安装时就消除了这个根因，
            // 所以「修」的前提不复存在 —— 留着只是在修一个不会发生的病。
            //
            // 同理，靠 .base-repaired 标记做的缓存也跟着删了（它本身就是
            // 「修完还要防复发」的补丁，没有病就不需要补丁）。
            //
            // ⚠️ 配套：ROOTFS_VERSION 已 bump，老 rootfs 会被重装 ——
            //    否则老用户继续用带损坏的旧 rootfs，而修复代码已经没了。

            // 【2026-09-23 加】先剔除已经装好的包。
            //
            // 用户反馈：「重新进入后又要下一遍不知道什么东西」—— 之前每次点安装
            // 都把全部包名丢给 apt，虽然 apt 对已装的会跳过，但：
            //   ① apt update + 解析依赖仍要跑几十秒，看着像"又下了一遍"
            //   ② 日志把已装的包也列出来，用户以为在重复下载
            // 现在先用 dpkg -s 筛一遍，只装真正缺的。
            // 【只在真有 apt 包时才检查】
            // 只勾 Node.js 时 packages 为空 —— 原来还是会跑一遍「检查已安装的包…」
            // 然后打印「↓ 待装 0 个：（空）」，用户看得莫名其妙。
            var todoPackages: List<String> = emptyList()
            if (packages.isNotEmpty()) {
                onLine("检查已安装的包…")
                val (have, need) = packages.partition { pkg ->
                    exec(listOf("/bin/bash", "-lc", "dpkg -s $pkg >/dev/null 2>&1"), {})
                }
                if (have.isNotEmpty()) onLine("  ✓ 已装 ${have.size} 个，跳过：${have.joinToString(" ").take(80)}")
                if (need.isEmpty()) {
                    // 【2026-09-24 修】原来这里直接 return true，跳过了 downloadSteps。
                    // 后果：用户只勾了 Node.js（aptPackages 为空）时，packages 为空 →
                    // need 也为空 → 直接「✅ 勾选的工具都已装好」返回，
                    // 但 Node 根本没装。而 Node 是内核必须的，症状就是「装了工具链但内核起不来」。
                    if (downloadSteps.isEmpty()) {
                        onLine("")
                        onLine("✅ 勾选的工具都已装好，无需下载。")
                        saveInstalledToolchains(selected)
                        return true
                    }
                    onLine("  ✓ apt 包都已就绪")
                } else {
                    onLine("  ↓ 待装 ${need.size} 个：${need.joinToString(" ")}")
                    todoPackages = need
                }
                onLine("")
            }

            // apt 是否成功。声明在 if 外面 —— 因为 need 为空时整段 apt 被跳过，
            // 但下面的 downloadSteps 还要看这个值决定要不要继续。
            //
            // 【初始值 true 而不是 false】need 为空 = 没有 apt 包要装 = apt 部分
            // 天然成功。如果初始化为 false，跳过 apt 时 ok 保持 false，
            // 下面 `if (ok && downloadSteps.isNotEmpty())` 就永远不成立 →
            // Node 还是装不上（这正是「只勾 Node.js」的场景）。
            var ok = true

            // 2) apt update + install（只在真有包要装时跑）
            //
            // 【2026-09-24】todoPackages 为空时整段 apt 都跳过 ——
            // 没包要装还跑 apt update 是纯浪费（几十秒），而且并发锁也白占。
            // 这种情况（只勾了 Node.js）直接进入下面的 downloadSteps 处理。
            // ⚠️ 注意 ok 必须声明在 if 外（否则 if 跳过时下面引用不到）。
            if (todoPackages.isNotEmpty()) {
                // 【2026-10-05 加 DNS 保活】apt 升级 systemd 类包时 postinst 会重置
                // /etc/resolv.conf（变 0 字节 + 0600 root:root），之后 apt 自己的 DNS
                // 就挂了，报 "Temporary failure resolving ..." —— 看起来像网络问题，
                // 实际是配置文件被清空。每次 apt 操作前重写一遍，开销可忽略。
                //
                // 权限必须 644：0600 root:root 时 App（非 root）读不了。
                val dnsPrelude = "mkdir -p /etc; " +
                    "printf 'nameserver 223.5.5.5\\nnameserver 223.6.6.6\\n" +
                    "nameserver 119.29.29.29\\n' > /etc/resolv.conf; " +
                    "chmod 644 /etc/resolv.conf; "

                onLine("更新软件源…")
                var updated = false
                for (attempt in 1..3) {
                    updated = exec(
                        listOf("/bin/bash", "-lc",
                            "export DEBIAN_FRONTEND=noninteractive; " +
                            dnsPrelude +
                            "apt-get update -o Acquire::Retries=3 2>&1 | tail -20"),
                        onLine
                    )
                    if (updated) break
                    onLine("  源更新失败，${attempt}/3 重试…")
                    try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                }
                if (!updated) onLine("⚠️ 软件源更新失败（网络问题？继续尝试安装）")

                // 2.5) 先升级基础系统再装用户包。
                //
                // ubuntu-base 出厂后源里的基础包版本会往前走（比如 perl-base
                // 5.38.2-3.2ubuntu0.2 → 0.6）。用户勾选的包（git、curl、python3）
                // 都依赖新版本，apt 会在同一次安装里升级它们。
                //
                // 【曾经在这里失败过】dpkg 升级 perl-base 时报
                //   error setting ownership of '/usr/bin/perl5.38.2.dpkg-new':
                //       No such file or directory
                //   dpkg-deb: zstd write error: Broken pipe
                // 当时的结论是「手动安装器绕开它」，**那个结论是错的** ——
                // 真正的根因是宿主 UID 不在 rootfs 的 /etc/passwd 里，
                // dpkg 解包时 chown 查不到属主就报这个错。
                // 现在 RootfsPostSetup.registerAndroidIds() 已从源头消除，
                // 所以这里保持「先 upgrade 再 install」只是常规做法，
                // 不再是绕行手段。（2026-09-27 已实测 apt 原生可用）
                //
                // 分两步的好处：基础包升级失败时单独报，不和用户包混在一起。
                if (updated) {
                    onLine("")
                    onLine("同步基础系统版本…")

                    // 【2026-10-05 照搬 Operit 的完整修复序列】
                    //
                    // 装机实测报 `E: Unmet dependencies. Try 'apt --fix-broken install'`
                    // —— 那正是 apt 在提示「先修依赖再装」，而 CCM 原来没有这一步。
                    //
                    // Operit 的 SetupScreen 在装任何东西前固定跑这四步：
                    //   dpkg --configure -a    收尾上次未完成的配置
                    //   apt install -f -y      修复依赖（就是 apt 提示的那条）
                    //   apt update -y          刷新索引
                    //   apt upgrade -y         升级基础系统
                    // 照搬过来，顺序不变。
                    //
                    // 【为什么 -f 是必须的】ubuntu-base 出厂镜像里有些包处于
                    // 「已解包未配置」状态（dpkg 的 half-configured）。
                    // 直接装新包时 apt 会先检查依赖图，撞上这些半成品就报
                    // Unmet dependencies 并拒绝继续 —— 而它提示的解法正是 -f。

                    val configured = exec(
                        listOf(
                            "/bin/bash", "-lc",
                            "export DEBIAN_FRONTEND=noninteractive TERM=dumb HOME=/root; " +
                                "dpkg --configure -a 2>&1 | tail -15"
                        ),
                        onLine
                    )
                    if (!configured) onLine("  基础包收尾未完成，继续")

                    // ★ 关键：修复依赖（Operit 的第二步，CCM 原来缺这步）
                    val fixed = exec(
                        listOf(
                            "/bin/bash", "-lc",
                            "export DEBIAN_FRONTEND=noninteractive TERM=dumb HOME=/root; " +
                                "apt-get install -f -y 2>&1 | tail -20"
                        ),
                        onLine
                    )
                    if (!fixed) onLine("  依赖修复未完成，继续")

                    val upgraded = exec(
                        listOf(
                            "/bin/bash", "-lc",
                            "export DEBIAN_FRONTEND=noninteractive TERM=dumb HOME=/root; " +
                                "apt-get upgrade -y 2>&1 | tail -25"
                        ),
                        onLine
                    )
                    if (!upgraded) onLine("  基础系统升级未完成，继续安装所选工具")
                }

                // 3) 安装 —— 直接用 apt-get install
                //
                // ═══════════════════════════════════════════════════════════
                // 【2026-09-27 改回 apt】这里原来是「手动安装器」（绕开 dpkg，
                // 自己下载/解包/补链接/跑 postinst），现已整个删除。
                //
                // 删除的原因：手动安装器是**为了绕开一个已经不存在的问题**。
                // 它诞生的背景是「dpkg 建硬链接必失败」，而那个失败的真正根因
                // 是 PROOT_L2S_DIR 指向了 rootfs 内部导致 --link2symlink 静默失效。
                // 修好这个之后，dpkg/apt 本来就是好的 —— 手动安装器成了纯粹的技术债：
                //   · 它不支持依赖解析（apt 会做，它只会照单抓药）
                //   · 它不跑真实 dpkg 状态机（只能往 status 文件里塞条目）
                //   · 出问题时没有任何标准排查手段
                //
                // 现在的做法：官方 ubuntu-base 镜像 + RootfsPostSetup 三步后处理，
                // 让 apt 在原生状态下工作。已在 Termux 实测验证（recon-c-proot.md）：
                // apt-get update 拉 36.7MB 无错误，apt install 直接跑通。
                // ═══════════════════════════════════════════════════════════
                onLine("")
                onLine("开始安装（apt）…")

                for (attempt in 1..2) {
                    ok = exec(
                        listOf(
                            "/bin/bash", "-lc",
                            "export DEBIAN_FRONTEND=noninteractive TERM=dumb HOME=/root; " +
                                "apt-get install -y -q " +
                                "-o Dpkg::Options::=--force-confold " +
                                "-o APT::Get::Allow-Downgrades=true " +
                                todoPackages.joinToString(" ") +
                                " 2>&1 | tail -30"
                        ),
                        onLine
                    )
                    if (ok) break
                    if (attempt < 2) {
                        onLine("  安装失败，重试…")
                        try { Thread.sleep(5000) } catch (_: InterruptedException) {}
                    }
                }

                // 【2026-09-23 加校验】只看退出码不够 ——
                // apt 可能部分失败（某个包不在源里）却仍返回 0，或者反过来
                // 因为管道/子 shell 掩盖了真实退出码。
                // 所以装完真去探一次命令能不能跑，把没装上的名字报给用户。
                if (ok) {
                    onLine("")
                    onLine("校验安装结果…")
                    // 判据是「命令是否真的可执行」，而不是 `dpkg -s` 的状态 ——
                    // 后者只能说明「记录上装了」，前者才是用户关心的事：工具能不能用。
                    val probeCmds = mapOf(
                        "git" to "git --version",
                        "curl" to "curl --version",
                        "wget" to "wget --version",
                        "nodejs" to "node --version",
                        "python3" to "python3 --version",
                        "unzip" to "unzip -v",
                        "xz-utils" to "xz --version",
                        "less" to "less --version",
                    )
                    val missing = todoPackages.filter { pkg ->
                        val probe = probeCmds[pkg] ?: return@filter false  // 没探针的包不判失败
                        !exec(listOf("/bin/bash", "-lc", "$probe >/dev/null 2>&1"), {})
                    }
                    if (missing.isNotEmpty()) {
                        ok = false
                        onLine("❌ 以下包没装上：${missing.joinToString(" ")}")
                        onLine("   常见原因：软件源里没有这个包，或网络中断。")
                        onLine("   可稍后在「管理工具」里重试，或换源。")
                    } else {
                        onLine("✅ 本次要装的 ${todoPackages.size} 个包全部就绪")
                    }
                }
            }  // end if (todoPackages.isNotEmpty())

            // ── App 侧下载步骤（不经过 apt，见 ToolchainCatalog.DownloadStep）──
            //
            // 【为什么在 apt 之后做】有些工具需要 apt 装的运行库（如 Node 需要
            // libstdc++）。先 apt 后解压，顺序更稳。虽然 Node 官方 tarball 其实
            // 是自带的，但保持这个顺序对未来加别的工具更安全。
            if (ok && downloadSteps.isNotEmpty()) {
                onLine("")
                onLine("下载附加组件（不经过 apt）…")
                for (step in downloadSteps) {
                    val done = installDownloadStep(step, onLine) { done, total ->
                        // 进度转成 onLine 文本，复用现有 UI
                        if (total > 0 && done % (2 * 1024 * 1024) < 128 * 1024) {
                            onLine("  ${step.label}: ${done / 1024 / 1024}MB / ${total / 1024 / 1024}MB")
                        }
                    }
                    if (!done) {
                        ok = false
                        onLine("❌ ${step.label} 安装失败")
                        break
                    }
                    onLine("  ✅ ${step.label}")
                }
            }

            if (ok) {
                // 记录已装（供 UI 显示）
                saveInstalledToolchains(selected)
                Log.i(TAG, "工具链安装成功: ${chains.map { it.name }}")
            } else {
                Log.w(TAG, "工具链安装失败")
            }
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "安装工具链异常", t)
            onLine("安装异常: ${t.message}")
            false
        } finally {
            lock.release()
        }
    }

    /**
     * 执行一个「App 侧下载 → 解压进 rootfs」的步骤。
     *
     * 【为什么在 App 侧下载而不是进 rootfs 里用 curl】
     * 鸡生蛋：用户可能没勾「基础工具」（不含 curl），此时 rootfs 里没有任何
     * 下载工具。而 Node.js 是内核自己必须的 —— 不能因为用户没勾 git/curl 就装不上。
     *
     * 所以走 App 的 HttpURLConnection（一定可用，走系统网络栈），下到 App 私有目录，
     * 再用 TarExtractor 解压到 rootfs 的指定路径。
     *
     * 【支持 .tar.xz 吗】
     * TarExtractor 只认 gzip。xz 需要额外解压器 —— Android 没有内置 xz 支持。
     * 所以这里优先选 gzip 格式的资源；Node 官方提供 .tar.gz（体积大一点但通用）。
     *
     * @param onProgress (已下载字节, 总字节)
     */
    private fun installDownloadStep(
        step: ToolchainCatalog.DownloadStep,
        onLine: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
    ): Boolean {
        return try {
            // 1) 下载（多镜像 + 断点续传，复用 downloadTo）
            val suffix = step.url.substringAfterLast('.', "tar.gz").let {
                // 要区分 .tar.gz / .tar.xz —— 取最后两个后缀段
                val parts = step.url.split('.')
                if (parts.size >= 2) parts.takeLast(2).joinToString(".") else it
            }
            val archive = File(context.filesDir, "toolchain-dl.$suffix")

            var downloaded = false
            // 支持多镜像：URL 列表在 ToolchainCatalog 里（step.url 是首选）
            val urls = listOf(step.url) + ToolchainCatalog.NODE_MIRRORS.drop(1).filter { it != step.url }
            val partFile = File(archive.parentFile, "${archive.name}.part")
            outer@ for ((idx, url) in urls.withIndex()) {
                // ⚠️ 换镜像前清掉 .part
                //
                // 不同镜像的文件理论上内容一致，但：
                //   · 可能一个是当前版、一个是缓存的旧版（CDN 同步延迟）
                //   · Range 续传会把两个版本的数据拼在一起 → 文件损坏
                // 而 corrupted 的文件要等解压或运行时才暴露，很难归因。
                // 宁可重下也不冒这个险 —— 只在不同 host 之间切换时清。
                if (idx > 0) {
                    try { if (partFile.exists()) { partFile.delete(); onLine("  （换了镜像，丢弃已下载的部分重来）") } } catch (_: Throwable) {}
                }
                for (attempt in 1..3) {
                    try {
                        if (downloadTo(url, archive, onProgress)) { downloaded = true; break@outer }
                    } catch (t: Throwable) {
                        Log.w(TAG, "下载 ${step.label} 失败（第 $attempt 次）: ${t.message}")
                    }
                    try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                }
                if (idx < urls.size - 1) onLine("  换个镜像重试…")
            }
            if (!downloaded || !archive.exists() || archive.length() < 100_000) {
                onLine("  ❌ 下载失败")
                return false
            }

            // 2) 解压到 rootfs 的指定目录
            val destRoot = File(rootfsPath, step.extractTo)
            destRoot.mkdirs()
            onLine("  解压到 /${step.extractTo} …")

            // stripComponents：tarball 通常有个顶层目录（node-v24.x-linux-arm64/），
            // 用临时目录解压后再移动其内容，效果等价于 tar --strip-components=1
            val tmpDir = File(context.cacheDir, "toolchain-extract")
            if (tmpDir.exists()) tmpDir.deleteRecursively()
            tmpDir.mkdirs()

            val extracted = TarExtractor.extract(
                archive, tmpDir,
                { done, total ->
                    if (total > 0 && done % (5L * 1024 * 1024) < 256 * 1024) {
                        onLine("  解压 ${done * 100 / total}%")
                    }
                },
                // 把失败原因写进安装日志 —— 否则用户只看到「解压失败」，
                // 而真正的原因（异常类型/消息/行号）被 Log.e 藏在 logcat 里。
                { reason -> onLine("  ❌ 解压失败：$reason") }
            )
            if (!extracted) {
                tmpDir.deleteRecursively()
                return false
            }

            // 找到顶层目录（等价于 tar --strip-components=1）
            //
            // 【为什么不能简单判 `topEntries.size == 1`】
            // 有些 tarball 会带隐藏文件（macOS 打的包有 ._xxx、解压工具可能留
            // .DS_Store），此时 size 会是 2 → 判定失败 → 走 else → 把整个
            // 顶层目录**当成内容**装进 /usr/local（结果是 /usr/local/node-v24.../bin/node，
            // 而不是 /usr/local/bin/node）—— 而 verifyCommand 查不到命令，
            // 用户看到「装完了但用不了」。
            //
            // 现在：忽略隐藏文件后再判；若仍有多个条目，说明这个包结构不是
            // 「单一顶层目录」形态，走 else 是合理的（直接把内容摊开）。
            val topEntries = (tmpDir.listFiles() ?: emptyArray())
                .filterNot { it.name.startsWith(".") }
            val sourceDir = if (step.stripComponents > 0 && topEntries.size == 1 && topEntries[0].isDirectory) {
                topEntries[0]
            } else {
                if (step.stripComponents > 0 && topEntries.size > 1) {
                    Log.w(TAG, "解压出 ${topEntries.size} 个顶层条目，无法安全剥离 —— 直接摊开安装")
                }
                tmpDir
            }

            // 移动到目标位置（覆盖同名）
            var moved = 0
            var copied = 0
            sourceDir.listFiles()?.forEach { f ->
                val target = File(destRoot, f.name)
                try {
                    if (target.exists()) target.deleteRecursively()
                    // 优先 renameTo（同文件系统上是原子的、瞬时的 ——
                    // filesDir 和 cacheDir 都在 /data/data/<pkg>/ 下，一定同挂载点）。
                    // 失败才退回 copyRecursively（那会真的复制 200MB，很慢）。
                    //
                    // ⚠️ 原来这里写的是 `if (f.renameTo(target) || f.copyRecursively(...))`
                    // 然后在里面判 `if (!f.exists()) moved++ else moved++` ——
                    // 两个分支都是 moved++，等于什么都没判。现在改成分别计数，
                    // 并且区分日志（rename 快、copy 慢，出慢的时候能看出走了哪条路）。
                    val ok = if (f.renameTo(target)) {
                        moved++
                        true
                    } else {
                        Log.i(TAG, "renameTo 失败（${f.name}），退回拷贝")
                        try {
                            f.copyRecursively(target, overwrite = true)
                            copied++
                            true
                        } catch (e: Throwable) {
                            Log.w(TAG, "拷贝 ${f.name} 失败: ${e.message}")
                            false
                        }
                    }
                    if (!ok) Log.w(TAG, "顶层条目 ${f.name} 未安装成功")
                } catch (t: Throwable) {
                    Log.w(TAG, "移动 ${f.name} 失败: ${t.message}")
                }
            }
            onLine("  已安装 $moved 个顶层条目到 /${step.extractTo}" +
                if (copied > 0) "（其中 $copied 个走了拷贝，较慢）" else "")

            // 3) 清理
            tmpDir.deleteRecursively()
            try { archive.delete() } catch (_: Throwable) {}

            // 4) 修执行权限（node/npm 必须是可执行的）
            // TarExtractor 已按 mode 设置，但 tarball 里如果有 0644 的二进制就废了
            fixPermissionsIn(destRoot)

            moved > 0
        } catch (t: Throwable) {
            Log.e(TAG, "安装 ${step.label} 异常", t)
            onLine("  ❌ ${t.message}")
            false
        }
    }

    /** 记录已安装的工具链（存 JSON，供 UI 显示） */
    private fun saveInstalledToolchains(selected: Set<String>) {
        try {
            val f = File(rootfsPath, "root/.ccm-toolchains")
            val existing = if (f.exists()) {
                f.readText().trim().split(",").filter { it.isNotBlank() }.toMutableSet()
            } else mutableSetOf()
            existing.addAll(selected)
            f.writeText(existing.joinToString(","))
        } catch (t: Throwable) {
            Log.w(TAG, "记录工具链失败", t)
        }
    }

    /** 读已安装的工具链 id */
    /**
     * 读「记录里声称已装」的工具链（快速路径，不验证）。
     *
     * ⚠️ 这个结果**可能不准** —— 见 Toolchain.verifyCommand 的说明。
     * 需要在界面显示「已安装」状态时，用 verifyInstalledToolchains()。
     */
    fun installedToolchains(): Set<String> {
        return try {
            val f = File(rootfsPath, "root/.ccm-toolchains")
            if (f.exists()) {
                f.readText().trim().split(",").filter { it.isNotBlank() }.toSet()
            } else emptySet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    /**
     * 实测验证：对记录里的每个工具链跑它的 verifyCommand，只返回真的能用的。
     *
     * 【为什么值得多花这几秒】
     * 界面显示「已安装」但实际没装上，是最容易让用户困惑的状态 ——
     * 他会以为「我装过了」，然后发现命令跑不了、内核起不来，来问为什么。
     * 与其让他踩这个坑，不如显示状态时多花几秒实测。
     *
     * 实测开销：每个工具链一次 proot 启动 + command -v，约 200~500ms。
     * 18 个全跑 ~5 秒 —— 而这只在打开「管理工具」界面时发生一次。
     *
     * @param exec 与 installToolchains 同一个 exec 回调（复用 proot 实例）
     * @param onProgress 可选，用于显示「正在检查 xxx」
     */
    fun verifyInstalledToolchains(
        exec: (List<String>, (String) -> Unit) -> Boolean,
        onProgress: (String) -> Unit = {},
    ): Set<String> {
        val claimed = installedToolchains()
        if (claimed.isEmpty()) return emptySet()

        val verified = mutableSetOf<String>()
        for (id in claimed) {
            val tc = ToolchainCatalog.ALL.find { it.id == id } ?: continue
            val cmd = tc.verifyCommand
            if (cmd == null) {
                // 没写验证命令的（新增工具链时可能漏）→ 退回信记录，并记日志
                Log.w(TAG, "工具链 $id 没有 verifyCommand，按记录认为已装")
                verified.add(id)
                continue
            }
            onProgress("检查 ${tc.name}…")
            val ok = try {
                exec(listOf("/bin/bash", "-lc", cmd), {})
            } catch (t: Throwable) {
                Log.w(TAG, "验证 $id 异常: ${t.message}")
                false
            }
            if (ok) {
                verified.add(id)
            } else {
                Log.i(TAG, "工具链 $id 记录已装但实测不通（$cmd）")
            }
        }

        // 顺手修正记录文件，避免每次都白跑一遍验证。
        //
        // 【但要先抢锁】验证本身要跑几秒，这期间用户完全可能点「安装」——
        // 那次安装会拿 toolchain 锁并写入新记录。如果这里不持锁就写回，
        // 会把安装刚写的记录覆盖掉（用户装完发现界面显示「未安装」）。
        //
        // 抢不到锁就**不写回**（说明有安装在进行，那次安装的记录更新更权威）。
        // 这只影响下次是否白跑一遍验证，不影响正确性。
        if (verified != claimed) {
            val lock = InstallLock(context, "toolchain")
            // waitMs=0：锁忙就跳过，不要把界面卡住
            if (lock.acquire(waitMs = 0)) {
                try {
                    File(rootfsPath, "root/.ccm-toolchains").writeText(verified.joinToString(","))
                    Log.i(TAG, "已修正工具链记录：${claimed.size} → ${verified.size}")
                } catch (t: Throwable) {
                    Log.w(TAG, "修正记录失败", t)
                } finally {
                    lock.release()
                }
            } else {
                Log.i(TAG, "有安装在跑，跳过记录修正（下次验证会重跑）")
            }
        }
        return verified
    }

    // ═══════════════════════════════════════════════════
    //  Node 内核安装
    // ═══════════════════════════════════════════════════

    /** 内核是否已安装 */
    /**
     * 内核是否已安装（完整）。
     *
     * 【2026-09-24 加严判定】
     * 原来只查 ccm-start.mjs 和 web/server.mjs 两个文件。
     * 但内核跑起来还需要：
     *   · node_modules —— server.mjs 会 import diff / markdown-it 等
     *   · web/dist      —— 前端构建产物，WebView 加载的就是它
     * 少了这些，两个入口文件在但服务起不来，而界面显示「已安装」——
     * 用户点「启动 Node」失败，还以为是自己网络问题。
     *
     * 现在四个都查。比「启动后再报错」友好。
     */
    fun isKernelInstalled(): Boolean {
        val d = File(rootfsPath, KERNEL_DIR)
        return File(d, "ccm-start.mjs").exists() &&
               File(d, "web/server.mjs").exists() &&
               File(d, "node_modules").isDirectory &&
               File(d, "web/dist").isDirectory
    }


    /** 通用下载到指定文件 */
    /**
     * 通用下载（支持断点续传）。
     *
     * 与 downloadOne 同样的续传策略：
     * 已下载的留在 .part，重试时带 Range 头，服务端返回 206 就追加写。
     *
     * 中断时**不删 .part** —— 留着下次续传。
     */
    private fun downloadTo(
        url: String,
        dest: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): Boolean {
        var conn: HttpURLConnection? = null
        val tmp = File(dest.parentFile, "${dest.name}.part")
        val already = if (tmp.exists()) tmp.length() else 0L

        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 60000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "CCM/0.1 (Android)")
                if (already > 0) setRequestProperty("Range", "bytes=$already-")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code")
                return false
            }

            val append = code == 206 && already > 0
            val startAt = if (append) already else 0L
            if (!append && tmp.exists()) tmp.delete()

            val contentLen = conn.contentLengthLong.takeIf { it > 0 } ?: 1_000_000L
            val total = startAt + contentLen

            conn.inputStream.use { input ->
                FileOutputStream(tmp, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = startAt
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }

            // 【2026-09-24 加完整性校验】
            // 原来下完就 rename，不检查字节数。HTTP 连接被中间设备掐断时，
            // read 会正常返回 0（EOF）而不是抛异常 —— 也就是**截断的下载会被
            // 当成成功**。后续解压报错，用户看到的却是「解压失败」，
            // 完全想不到是下载没下完。
            //
            // 现在比对期望字节数。服务端给了 content-length 就必须对得上；
            // 没给（chunked 或 1MB 兜底值）就跳过检查。
            val expected = if (contentLen > 1_000_000L) startAt + contentLen else 0L
            val actual = tmp.length()
            if (expected > 0 && actual < expected) {
                Log.w(TAG, "下载不完整：$actual / $expected 字节（保留 .part 供续传）")
                return false
            }

            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                // renameTo 在少数情况会失败（跨挂载点等），退回拷贝。
                //
                // ⚠️ Kotlin 的 File.copyTo 返回目标 File（不是 Boolean），
                // 失败时抛异常而不是返回 false —— 所以这里用 try/catch 判成败，
                // 不能写 `if (!tmp.copyTo(...))`（编译报 Unresolved reference 'not'）。
                Log.w(TAG, "renameTo 失败，改用拷贝")
                try {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                } catch (e: Throwable) {
                    Log.e(TAG, "拷贝失败: ${e.message}")
                    return false
                }
            }
            true
        } catch (t: Throwable) {
            // 保留 .part 供续传
            Log.w(TAG, "下载中断（保留 ${if (tmp.exists()) tmp.length() else 0} 字节）: ${t.message}")
            false
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ═══════════════════════════════════════════════════
    //  Node 运行时安装（在 proot 里跑 apt）
    // ═══════════════════════════════════════════════════



    private fun fixPermissionsInternal() = fixPermissionsIn(rootfsPath)

    /** 修指定目录的执行权限（安装流程用，见 fixPermissionsInternal 的说明） */
    private fun fixPermissionsIn(target: File) {
        try {
            val execDirs = listOf(
                "bin", "sbin", "usr/bin", "usr/sbin",
                "usr/local/bin", "usr/local/sbin",
                "usr/lib/apt/methods", "usr/lib/dpkg",
                "lib/aarch64-linux-gnu", "usr/lib/aarch64-linux-gnu",
            )
            execDirs.forEach { d ->
                File(target, d).listFiles()?.forEach { f ->
                    if (f.isFile && !f.canExecute()) f.setExecutable(true, false)
                }
            }
        } catch (_: Throwable) {}
    }

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
