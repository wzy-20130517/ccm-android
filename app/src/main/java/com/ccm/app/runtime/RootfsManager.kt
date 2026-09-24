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

        /**
         * 下载地址。
         *
         * ⚠️ 指向**公开仓库** ccm-assets —— 代码仓库 ccm-android 是私有的，
         * 裸 URL 下载会 404（GitHub 私有 Release 必须带 token 才能下）。
         * 所以资源单独放一个公开仓库，代码保持私有。
         */
        const val ROOTFS_URL =
            "https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ubuntu-base-24.04-arm64.tar.gz"

        /** Node 内核包（core + web + 前端 + 配置） */
        const val KERNEL_URL =
            "https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ccm-node-kernel.tar.gz"

        /** 内核安装目标（rootfs 内） */
        const val KERNEL_DIR = "root/ccm"

        /** 国内加速（GitHub 直连慢时用） */
        private val MIRRORS = listOf(
            "https://ghfast.top/https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ubuntu-base-24.04-arm64.tar.gz",
            "https://gh-proxy.com/https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ubuntu-base-24.04-arm64.tar.gz",
            ROOTFS_URL,
        )

        private val KERNEL_MIRRORS = listOf(
            "https://ghfast.top/https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ccm-node-kernel.tar.gz",
            "https://gh-proxy.com/https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ccm-node-kernel.tar.gz",
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
        val tmpPath = File(context.filesDir, "rootfs.install.tmp")
        return try {
            // 1) 下载（已有就跳过）
            if (!hasArchive()) {
                if (!download(onProgress)) {
                    Log.e(TAG, "下载失败")
                    return false
                }
            }

            // 2) 解压到临时目录（老 rootfs 不动）
            if (tmpPath.exists()) tmpPath.deleteRecursively()
            tmpPath.mkdirs()
            onProgress("extract", 0, archiveFile.length())
            val ok = TarExtractor.extract(archiveFile, tmpPath) { done, total ->
                onProgress("extract", done, total)
            }
            if (!ok) {
                Log.e(TAG, "解压失败")
                tmpPath.deleteRecursively()
                return false
            }

            // 3) 配置（在 tmp 里做，用 rootfsPath 之外的路径）
            onProgress("config", 0, 1)
            setupBaseConfigIn(tmpPath)
            fixPermissionsIn(tmpPath)

            // 4) 写标记 —— 注意写进 tmp，随 mv 一起生效
            File(tmpPath, MARKER_FILE).apply {
                parentFile?.mkdirs()
                writeText(ROOTFS_VERSION)
            }

            // 5) 原子替换：这一步才动老数据
            if (rootfsPath.exists()) rootfsPath.deleteRecursively()
            if (!tmpPath.renameTo(rootfsPath)) {
                // renameTo 失败（跨文件系统等）→ 退回逐文件拷贝
                Log.w(TAG, "renameTo 失败，改用拷贝")
                if (!tmpPath.copyRecursively(rootfsPath, overwrite = true)) {
                    Log.e(TAG, "拷贝失败")
                    tmpPath.deleteRecursively()
                    return false
                }
                tmpPath.deleteRecursively()
            }

            // 6) 清掉压缩包省空间（28MB）—— 只有真装好了才删
            try { archiveFile.delete() } catch (_: Throwable) {}

            onProgress("config", 1, 1)
            Log.i(TAG, "rootfs 安装完成：${rootfsPath.absolutePath}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "安装 rootfs 失败", t)
            try { tmpPath.deleteRecursively() } catch (_: Throwable) {}
            false
        } finally {
            lock.release()
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

    /** 在指定目录做基础配置（DNS/apt 源/shell 配置/挂载点）。参数化是为了支持原子安装。 */
    private fun setupBaseConfigIn(target: File) {
        try {
            // DNS（Android 上 /etc/resolv.conf 不可写，proot 里用这个）
            File(target, "etc/resolv.conf").writeText(
                "nameserver 223.5.5.5\nnameserver 119.29.29.29\n"
            )

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

            // 【2026-09-23 加】先剔除已经装好的包。
            //
            // 用户反馈：「重新进入后又要下一遍不知道什么东西」—— 之前每次点安装
            // 都把全部包名丢给 apt，虽然 apt 对已装的会跳过，但：
            //   ① apt update + 解析依赖仍要跑几十秒，看着像"又下了一遍"
            //   ② 日志把已装的包也列出来，用户以为在重复下载
            // 现在先用 dpkg -s 筛一遍，只装真正缺的。
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
                onLine("")
                onLine("apt 包都已就绪，继续处理附加组件…")
            }
            onLine("  ↓ 待装 ${need.size} 个：${need.joinToString(" ")}")
            onLine("")
            val todoPackages = need

            // apt 是否成功。声明在 if 外面 —— 因为 need 为空时整段 apt 被跳过，
            // 但下面的 downloadSteps 还要看这个值决定要不要继续。
            //
            // 【初始值 true 而不是 false】need 为空 = 没有 apt 包要装 = apt 部分
            // 天然成功。如果初始化为 false，跳过 apt 时 ok 保持 false，
            // 下面 `if (ok && downloadSteps.isNotEmpty())` 就永远不成立 →
            // Node 还是装不上（这正是「只勾 Node.js」的场景）。
            var ok = true

            // 2) apt update（失败重试）
            //
            // 【2026-09-24】need 为空时整段 apt 都跳过 —— 没包要装还跑 apt update
            // 是纯浪费（几十秒），而且并发锁也白占。
            // 这种情况下直接进入下面的 downloadSteps 处理。
            // ⚠️ 注意 ok 必须声明在 if 外（否则 if 跳过时下面引用不到）。
            if (need.isNotEmpty()) {
                onLine("更新软件源…")
                var updated = false
                for (attempt in 1..3) {
                    updated = exec(
                        listOf("/bin/bash", "-lc",
                            "export DEBIAN_FRONTEND=noninteractive; " +
                            "apt-get update -o Acquire::Retries=3 2>&1 | tail -20"),
                        onLine
                    )
                    if (updated) break
                    onLine("  源更新失败，${attempt}/3 重试…")
                    try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                }
                if (!updated) onLine("⚠️ 软件源更新失败（网络问题？继续尝试安装）")

                // 3) 一次性装完所有包
                //
                // DEBIAN_FRONTEND=noninteractive 避免交互式提问卡住
                // （某些包会问时区、键盘布局等）
                val installCmd = buildString {
                    append("DEBIAN_FRONTEND=noninteractive apt-get install -y -q ")
                    append(todoPackages.joinToString(" "))
                }

                onLine("")
                onLine("开始安装（可能需要几分钟）…")
                for (attempt in 1..2) {
                    // ⚠️ 不要用 `/usr/bin/env -i` 清空环境再跑 apt！
                    //
                    // 原来这里传的是 ["/usr/bin/env","-i","HOME=/root",...]，本意是
                    // 「apt 别继承 Android 的奇怪变量」，但 env -i 会把
                    // ProcessBuilder 设好的 LD_LIBRARY_PATH / PROOT_L2S_DIR 一起丢掉 ——
                    // 而 proot 的 link2symlink 和它自己的 .so 都依赖那两个变量。
                    // 症状：apt update 成功（它没走 env -i），apt install 静默失败，
                    // 用户看到「完成」但一个包都没装上。
                    //
                    // 现在改为跟 apt update 同一种调用方式（/bin/bash -lc，继承环境），
                    // 非交互靠 DEBIAN_FRONTEND=noninteractive 单独设，不靠 env -i。
                    ok = exec(
                        listOf(
                            "/bin/bash", "-lc",
                            "export DEBIAN_FRONTEND=noninteractive TERM=dumb HOME=/root; $installCmd"
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
                // 所以装完真去问一次 dpkg，把没装上的名字报给用户。
                if (ok) {
                    onLine("")
                    onLine("校验安装结果…")
                    val missing = todoPackages.filterNot { pkg ->
                        exec(listOf("/bin/bash", "-lc", "dpkg -s $pkg >/dev/null 2>&1"), {})
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
            }  // end if (need.isNotEmpty())

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

            val extracted = TarExtractor.extract(archive, tmpDir) { done, total ->
                if (total > 0 && done % (5L * 1024 * 1024) < 256 * 1024) {
                    onLine("  解压 ${done * 100 / total}%")
                }
            }
            if (!extracted) {
                onLine("  ❌ 解压失败")
                tmpDir.deleteRecursively()
                return false
            }

            // 找到顶层目录（可能不止一个，取第一个目录）
            val topEntries = tmpDir.listFiles() ?: emptyArray()
            val sourceDir = if (step.stripComponents > 0 && topEntries.size == 1 && topEntries[0].isDirectory) {
                topEntries[0]
            } else {
                tmpDir
            }

            // 移动到目标位置（覆盖同名）
            var moved = 0
            sourceDir.listFiles()?.forEach { f ->
                val target = File(destRoot, f.name)
                try {
                    if (target.exists()) target.deleteRecursively()
                    if (f.renameTo(target) || f.copyRecursively(target, overwrite = true)) {
                        if (!f.exists()) moved++ else moved++
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "移动 ${f.name} 失败: ${t.message}")
                }
            }
            onLine("  已安装 $moved 个顶层条目到 /${step.extractTo}")

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

        // 顺手修正记录文件，避免每次都白跑一遍验证
        if (verified != claimed) {
            try {
                File(rootfsPath, "root/.ccm-toolchains").writeText(verified.joinToString(","))
                Log.i(TAG, "已修正工具链记录：${claimed.size} → ${verified.size}")
            } catch (t: Throwable) {
                Log.w(TAG, "修正记录失败", t)
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

        // 【并发锁】内核安装会 deleteRecursively + 重新解压 /root/ccm，
        // 两个任务同时跑必然坏（用户连点「更新内核」就会触发）。
        val lock = InstallLock(context, "kernel")
        if (!lock.acquire()) {
            Log.w(TAG, "另一个内核安装正在进行中")
            return false
        }

        return try {
            // 下载
            if (!archive.exists() || archive.length() < 100_000) {
                var ok = false
                // 多轮重试（同 rootfs 的策略：网络抖动时利用 .part 续传）
                outer@ for (round in 1..5) {
                    // 换 host 时丢弃 .part（理由同 rootfs 下载，见那边的注释）
                    var lastKernelHost: String? = null
                    for (url in KERNEL_MIRRORS) {
                        try {
                            val host = try { java.net.URI(url).host } catch (_: Throwable) { null }
                            if (host != null && lastKernelHost != null && host != lastKernelHost) {
                                val part = File(archive.parentFile, "${archive.name}.part")
                                if (part.exists()) { Log.i(TAG, "换镜像，丢弃内核 .part"); part.delete() }
                            }
                            lastKernelHost = host
                            Log.i(TAG, "内核下载 第 $round 轮: ${url.take(45)}…")
                            if (downloadTo(url, archive, onProgress)) { ok = true; break@outer }
                        } catch (t: Throwable) {
                            Log.w(TAG, "镜像失败: ${t.message}")
                        }
                    }
                    if (round < 5) {
                        Log.w(TAG, "第 $round 轮内核下载失败，10 秒后重试")
                        try { Thread.sleep(10_000) } catch (_: InterruptedException) {}
                    }
                }
                if (!ok) return false
            }

            // 解压到临时目录，成功后再原子替换 —— 跟 rootfs 安装同一套做法。
            //
            // 【为什么不能先删后解压】
            // 原来是这样：
            //   if (dest.exists()) dest.deleteRecursively()
            //   dest.mkdirs()
            //   TarExtractor.extract(archive, dest)
            // 解压失败（下载不完整、空间不够）就留下一个**残缺的 /root/ccm** ——
            // 而 isKernelInstalled() 只要 ccm-start.mjs 和 web/server.mjs 存在就返回 true，
            // 于是「更新内核失败」被显示成成功，用户点「启动 Node」才发现起不来。
            //
            // 现在：解压到 .tmp → 成功后删旧的 → rename。任何一步失败老内核都完好，
            // 用户还能继续用旧版。
            val dest = File(rootfsPath, KERNEL_DIR)
            val tmpDest = File(rootfsPath, "$KERNEL_DIR.install.tmp")
            if (tmpDest.exists()) tmpDest.deleteRecursively()
            tmpDest.mkdirs()

            val ok = TarExtractor.extract(archive, tmpDest)
            if (!ok) {
                Log.e(TAG, "内核解压失败，保留旧版本")
                tmpDest.deleteRecursively()
                return false
            }

            // 校验解压结果：关键文件必须在
            val hasStart = File(tmpDest, "ccm-start.mjs").exists()
            val hasServer = File(tmpDest, "web/server.mjs").exists()
            if (!hasStart || !hasServer) {
                Log.e(TAG, "内核解压不完整（ccm-start.mjs=$hasStart, web/server.mjs=$hasServer）")
                tmpDest.deleteRecursively()
                return false
            }

            // 原子替换
            if (dest.exists()) dest.deleteRecursively()
            if (!tmpDest.renameTo(dest)) {
                Log.w(TAG, "renameTo 失败，改用拷贝")
                try {
                    tmpDest.copyRecursively(dest, overwrite = true)
                    tmpDest.deleteRecursively()
                } catch (e: Throwable) {
                    Log.e(TAG, "拷贝失败: ${e.message}")
                    return false
                }
            }

            // 清理
            archive.delete()
            Log.i(TAG, "内核安装完成（${dest.absolutePath}）")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "内核安装失败", t)
            false
        } finally {
            lock.release()
        }
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

            // 2) apt update（失败重试 3 次，网络抖动常见）
            onLine("更新软件源…")
            var updated = false
            for (attempt in 1..3) {
                updated = exec(listOf("/bin/bash", "-lc", "apt-get update"), onLine)
                if (updated) break
                onLine("  源更新失败，${attempt}/3 重试…")
                try { Thread.sleep(3000) } catch (_: InterruptedException) {}
            }
            if (!updated) {
                onLine("⚠️ 软件源更新失败（网络问题？）")
            }

            // 3) 装 nodejs + 常用工具（同样重试）
            onLine("安装 Node.js 与基础工具…")
            var ok = false
            for (attempt in 1..3) {
                ok = exec(
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
                if (ok && hasNode()) break
                if (attempt < 3) {
                    onLine("  安装失败，${attempt}/3 重试…")
                    try { Thread.sleep(3000) } catch (_: InterruptedException) {}
                }
            }

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
    /** 修正式 rootfs 的执行权限 */
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
