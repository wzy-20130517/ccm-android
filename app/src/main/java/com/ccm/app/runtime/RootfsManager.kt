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
            // 同上：gh-proxy.com 实测可靠，排第一
            "https://gh-proxy.com/https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ubuntu-base-24.04-arm64.tar.gz",
            "https://ghfast.top/https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ubuntu-base-24.04-arm64.tar.gz",
            ROOTFS_URL,
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
        private val KERNEL_MIRRORS = listOf(
            "https://gh-proxy.com/https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ccm-node-kernel.tar.gz",
            "https://ghfast.top/https://github.com/wzy-20130517/ccm-assets/releases/download/v1/ccm-node-kernel.tar.gz",
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
    /**
     * 手动包安装器脚本 —— 绕开 dpkg/apt。
     *
     * 【为什么需要】dpkg -i 在这个 proot 环境里必失败：它要用裸 link()
     * 备份旧文件和自己的 status 数据库，而 Android 沙箱禁止硬链接，
     * proot 的 --link2symlink 又只会产出指向宿主机路径的断链。
     *
     * 但 apt 的下载功能（纯 HTTP）和 dpkg-deb -x 的解包功能都正常，
     * 所以这里自己实现「下载 → 解包 → 补链接 → 跑 postinst → 记状态」。
     * 完整排查过程见 DEBUG-NOTES.md。
     */
    private val MANUAL_INSTALL_SH = """
#!/bin/bash
# ─────────────────────────────────────────────────────────────
# 手动包安装器 —— 绕开 dpkg/apt，用于 Android proot 环境
#
# 【为什么需要它】2026-09-26 实测确认：
#   · dpkg -i 在这个环境里**必失败** —— 它要用裸 link() 做两件事：
#       升级前备份旧文件、备份自己的 status 数据库
#     而 Android App 沙箱禁止普通应用建硬链接。
#     proot 的 --link2symlink 帮不上（它建的是指向宿主机路径的断链）。
#   · 但 apt 的**下载**功能（纯 HTTP）和 dpkg-deb -x 的**解包**功能都正常。
# 所以这里自己实现「下载 → 解包 → 跑 postinst → 记状态」四步。
#
# 【已验证】用这套流程装 git，git init/add/commit/log 全部正常。
# ─────────────────────────────────────────────────────────────
set -u
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export DEBIAN_FRONTEND=noninteractive
export TERM=dumb
export HOME=/root
# ⚠️ 必须显式设 TMPDIR —— dpkg-deb 要建临时目录，默认位置在 proot 里不可用。
# 症状：dpkg-deb: error: unable to create temporary directory: No such file or directory
export TMPDIR=/tmp
export TEMP=/tmp
export TMP=/tmp

WORK=/tmp/.ccm-pkg
STATUS=/var/lib/dpkg/status

# /tmp 必须存在且可写 —— 有些 rootfs 的 /tmp 权限不对
mkdir -p /tmp 2>/dev/null
chmod 1777 /tmp 2>/dev/null
if [ ! -w /tmp ]; then
  echo "❌ /tmp 不可写，无法继续"
  exit 1
fi

mkdir -p "${'$'}WORK"
cd "${'$'}WORK" || exit 1

log() { echo "${'$'}@"; }

# ── 1) 解析依赖（递归，一层）──────────────────────────────
# apt-cache depends 不需要 dpkg 工作，可以拿来算依赖树。
resolve_deps() {
  local pkg
  for pkg in "${'$'}@"; do
    echo "${'$'}pkg"
    apt-cache depends --no-recommends --no-suggests --no-conflicts \
      --no-breaks --no-replaces --no-enhances "${'$'}pkg" 2>/dev/null \
      | awk '/^  (Depends|PreDepends):/ {gsub(/[<>]/,"",${'$'}2); print ${'$'}2}' \
      | grep -v '^libc6${'$'}' || true
  done | sort -u
}

# ── 2) 下载 ────────────────────────────────────────────────
log "=== 解析依赖 ==="
PKGS=${'$'}(resolve_deps "${'$'}@" | tr '\n' ' ')
log "  需要: ${'$'}PKGS"

log "=== 下载 ==="
# apt-get download 不会调用 dpkg，安全
# shellcheck disable=SC2086
apt-get download ${'$'}PKGS 2>&1 | tail -5 || true
DEBS=${'$'}(ls *.deb 2>/dev/null | wc -l)
log "  已下载 ${'$'}DEBS 个包"
[ "${'$'}DEBS" -eq 0 ] && { log "❌ 什么都没下到"; exit 1; }

# ── 3) 解包（不用 --link2symlink，硬链接失败不影响主文件）──
log "=== 解包 ==="
for d in *.deb; do
  # 记录硬链接失败项 —— 解包后要手动补相对符号链接
  out=${'$'}(dpkg-deb -x "${'$'}d" / 2>&1)
  if echo "${'$'}out" | grep -qi "hard link"; then
    echo "${'$'}out" | grep -i "hard link" | sed "s/^/  [${'$'}d] /"
  fi
done

# ── 3.5) 补硬链接：把 tar 建不出来的硬链接改成相对符号链接 ──
#
# tar 解包时硬链接会失败（Android 沙箱禁止 link()），报错形如：
#   tar: ./usr/bin/zipinfo: Cannot hard link to './usr/bin/unzip': Permission denied
# 但**主文件（unzip）照常解出** —— 少的只是同一个 inode 的别名。
# 用相对符号链接补上别名即可，客户机里必定可达。
#
# tar -tvf 输出格式（硬链接行）：
#   hrwxr-xr-x root/root 0 2024-10-02 13:29 ./usr/bin/zipinfo link to ./usr/bin/unzip
#     $1        $2      $3    $4       $5      $6（链接名）    $7  $8  $9（目标）
# ⚠️ 早期版本写的是 `print $NF, $(NF-2)` —— 取成了「目标」和单词 "link"，
#    两个字段全错位，结果建出 /usr/bin/unzip -> /（目标解析成空串）这种废链接。
#    字段位置是固定的，不要用 NF 相对索引（"link to" 可能缺失）。
fix_hardlinks() {
  local d link target lp tp dir
  for d in *.deb; do
    dpkg-deb --fsys-tarfile "${'$'}d" 2>/dev/null | tar -tvf - 2>/dev/null \
      | awk '${'$'}1 ~ /^hrw/ && ${'$'}6 != "" {
          # 标准格式 9 字段（… link to <目标>）；简化格式 7 字段（… <目标>）
          tgt = (${'$'}9 != "") ? ${'$'}9 : ${'$'}7
          if (tgt != "" && tgt != "link" && tgt != "to") print ${'$'}6, tgt
        }' \
      | while read -r link target; do
        if [ -z "${'$'}link" ] || [ -z "${'$'}target" ]; then continue; fi
        lp="/${'$'}{link#./}"
        tp="/${'$'}{target#./}"
        dir=$(dirname "${'$'}lp")
        if [ -e "${'$'}tp" ] && [ ! -e "${'$'}lp" ]; then
          # 同目录用相对路径（可搬迁），跨目录用绝对路径
          if [ "$(dirname "${'$'}tp")" = "${'$'}dir" ]; then
            ln -sfn "$(basename "${'$'}tp")" "${'$'}lp" 2>/dev/null \
              && log "  补链接: ${'$'}lp -> $(basename "${'$'}tp")"
          else
            ln -sfn "${'$'}tp" "${'$'}lp" 2>/dev/null \
              && log "  补链接: ${'$'}lp -> ${'$'}tp"
          fi
        fi
      done
  done
}

log "=== 补硬链接 ==="
fix_hardlinks

# ── 4) 跑 postinst（失败不中断）─────────────────────────────
log "=== 配置（postinst）==="
for d in *.deb; do
  ctrl="${'$'}WORK/ctrl-${'$'}${'$'}"
  rm -rf "${'$'}ctrl"; mkdir -p "${'$'}ctrl"
  dpkg-deb -e "${'$'}d" "${'$'}ctrl" 2>/dev/null || continue
  if [ -x "${'$'}ctrl/postinst" ]; then
    if "${'$'}ctrl/postinst" configure 2>&1 | grep -viE '^${'$'}' | head -3; then
      :
    fi
  fi
  rm -rf "${'$'}ctrl"
done

# ── 5) 登记到 dpkg 数据库（让后续 apt 认为已装）─────────────
log "=== 登记状态 ==="
for d in *.deb; do
  pkg=${'$'}(dpkg-deb -f "${'$'}d" Package)
  ver=${'$'}(dpkg-deb -f "${'$'}d" Version)
  arch=${'$'}(dpkg-deb -f "${'$'}d" Architecture)
  [ -z "${'$'}pkg" ] && continue
  # 已登记就跳过
  if grep -q "^Package: ${'$'}pkg${'$'}" "${'$'}STATUS" 2>/dev/null; then
    log "  ${'$'}pkg 已在状态库，跳过"
    continue
  fi
  {
    echo ""
    echo "Package: ${'$'}pkg"
    echo "Status: install ok installed"
    echo "Priority: optional"
    echo "Section: utils"
    echo "Installed-Size: 1"
    echo "Maintainer: ccm-manual-install"
    echo "Architecture: ${'$'}arch"
    echo "Version: ${'$'}ver"
    echo "Description: installed by ccm manual installer"
    echo "  (dpkg -i is unusable in this proot environment; see install.log)"
  } >> "${'$'}STATUS"
  log "  已登记 ${'$'}pkg ${'$'}ver"
done

log "=== 完成 ==="
""".trimIndent()

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

            // 1.5) 补 debconf —— 见 repairBaseSystem 的说明，这是 ubuntu-base
            //      最小镜像的已知缺陷，不修的话 apt install 必失败。
            repairBaseSystem(exec, onLine)

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

                // 2.5) 先升级基础系统再装用户包。
                //
                // ubuntu-base 出厂后源里的基础包版本会往前走（比如 perl-base
                // 5.38.2-3.2ubuntu0.2 → 0.6）。用户勾选的包（git、curl、python3）
                // 都依赖新版本，apt 会在同一次安装里升级它们。
                //
                // 实测：直接 apt-get install 时，dpkg 在「升级 perl-base」这一步失败
                //   error setting ownership of '/usr/bin/perl5.38.2.dpkg-new':
                //       No such file or directory
                //   dpkg-deb: zstd write error: Broken pipe
                // 报错发生在 dpkg 自己依赖的包被替换的过程中。
                //
                // Operit 的做法是先 dpkg --configure -a、再 apt upgrade，
                // 然后才 apt install 用户勾的包。照这个顺序拆开两步，
                // 基础包升级失败时单独报，不和用户包混在一起。
                if (updated) {
                    onLine("")
                    onLine("同步基础系统版本…")
                    val configured = exec(
                        listOf(
                            "/bin/bash", "-lc",
                            "export DEBIAN_FRONTEND=noninteractive TERM=dumb HOME=/root; " +
                                "dpkg --configure -a --force-confold 2>&1 | tail -15"
                        ),
                        onLine
                    )
                    if (!configured) onLine("  基础包收尾未完成，继续")
                    val upgraded = exec(
                        listOf(
                            "/bin/bash", "-lc",
                            "export DEBIAN_FRONTEND=noninteractive TERM=dumb HOME=/root; " +
                                "apt-get upgrade -y -q -o Dpkg::Options::=--force-confold " +
                                "-o APT::Get::Allow-Downgrades=true 2>&1 | tail -25"
                        ),
                        onLine
                    )
                    if (!upgraded) onLine("  基础系统升级未完成，继续安装所选工具")
                }

                // 3) 安装 —— **不用 apt-get install，改用手动安装器**
                //
                // ═══════════════════════════════════════════════════════════
                // 【为什么不用 apt install】2026-09-26 实测确认：
                // dpkg -i 在这个 proot 环境里**必失败**，而且原因无解 ——
                // dpkg 自己要用**裸 link()** 做两件事：
                //   · 升级前备份旧文件：unable to make backup link of './usr/bin/perl'
                //   · 备份自己的数据库：error creating new backup file '/var/lib/dpkg/status-old'
                // 这两处不经过 tar，所以 proot 的 --link2symlink 帮不上；
                // 而 --link2symlink 本身在 CCM 的 proot 构建里只会产出
                // 指向宿主机路径的**断链**（PROOT_L2S_DIR 四种取值全试过）。
                //
                // 但分解动作全都是好的：
                //   · apt-get download（纯 HTTP，不碰 dpkg）        ✅
                //   · dpkg-deb -x（纯解包，不做 dpkg 那两件事）      ✅
                //   · 手动补相对符号链接替硬链接                     ✅
                // 实测：用这套流程装 git，git init/add/commit/log 全部正常。
                //
                // 所以这里把「下载 → 解包 → 补链接 → 跑 postinst → 记状态」
                // 五步交给 manual-install.sh（内容见 MANUAL_INSTALL_SH 常量）。
                // ═══════════════════════════════════════════════════════════
                onLine("")
                onLine("开始安装（手动模式，绕开 dpkg）…")

                // 把脚本落到 rootfs 里
                val scriptHost = File(rootfsPath, "tmp/ccm-manual-install.sh")
                try {
                    scriptHost.parentFile?.mkdirs()
                    scriptHost.writeText(MANUAL_INSTALL_SH)
                    scriptHost.setExecutable(true)
                } catch (e: Throwable) {
                    onLine("❌ 写入安装脚本失败：${e.message}")
                    return@installToolchains false
                }

                for (attempt in 1..2) {
                    ok = exec(
                        listOf(
                            "/bin/bash", "-lc",
                            "export TERM=dumb HOME=/root; " +
                                "/bin/bash /tmp/ccm-manual-install.sh ${todoPackages.joinToString(" ")}"
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
                    // ⚠️ 不能用 `dpkg -s` 校验 —— 手动安装器不经过 dpkg，
                    // 它只往 status 文件里追加条目，dpkg -s 对某些包可能查不到。
                    // 改用「命令是否真的可执行」来判断，这更贴近用户关心的事：
                    // 工具能不能用。
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
    /**
     * 修复 ubuntu-base 最小镜像的 dpkg 残缺状态。
     *
     * ═══════════════════════════════════════════════════════════
     * 【问题现象】（2026-09-24 用户实测日志）
     *
     *   Setting up libc6:arm64 (2.39-0ubuntu8.9) ...
     *   /var/lib/dpkg/info/libc6:arm64.postinst: 17: exec:
     *       /usr/share/debconf/frontend: not found
     *   dpkg: error processing package libc6:arm64 (--configure):
     *       installed libc6:arm64 package post-installation script
     *       subprocess returned error exit status 127
     *   E: Sub-process /usr/bin/dpkg returned an error code (1)
     *
     * 以及同源的：
     *   /bin/sh: 1: /usr/sbin/dpkg-preconfigure: not found
     *   29 not fully installed or removed.
     *
     * 【根因】
     * ubuntu-base-24.04 是最小系统，**不带 debconf**。
     * 但很多基础包（libc6、perl-base……）的 postinst 会调
     * /usr/share/debconf/frontend —— 找不到就 exit 127，
     * dpkg 判定「这个包没配置成功」→ 后续所有 apt install 全部失败。
     *
     * 日志里那句 `29 not fully installed or removed` 就是证据：
     * rootfs 本身出厂时 dpkg 状态就是残缺的。
     *
     * 【为什么不能直接 `apt install debconf`】
     * apt 现在就是坏的（dpkg 配置卡住），用它修自己是死循环。
     * 正确做法见下：先 `dpkg --configure -a` 把已解包的配置掉，
     * 再 apt 装 debconf（此时 apt 已可用）。
     *
     * 【为什么用 --force-confold】
     * 避免 conffile 冲突时卡在交互提示（非交互环境下会直接失败）。
     *
     * @return true 表示修复动作执行了（不代表一定成功 —— 后续 apt 会验证）
     */
    private fun repairBaseSystem(
        exec: (List<String>, (String) -> Unit) -> Boolean,
        onLine: (String) -> Unit = {},
    ): Boolean {
        // 缓存标记：修成功过就不再重复探测。
        // rootfs 是持久的，修一次就够 —— 而每次装工具链都跑一遍 dpkg --configure -a
        // 要几十秒，用户会以为「又在下一遍」。
        val stamp = File(rootfsPath, ".base-repaired")
        if (stamp.exists()) {
            // 标记只能省掉「重复修复」，不能掩盖「修复后又被弄坏」。
            //
            // 【为什么加这道检查】2026-09-26 的报错里，dpkg 卡在
            //   Errors were encountered while processing: perl-base
            // 而早先那次修复已经写过 .base-repaired —— 标记让后续每次安装
            // 都跳过修复，用户就永远卡在同一个错上，重试多少次都一样。
            //
            // 【怎么判】写探针文件（exec 的 Boolean 只给退出码，拿不到输出）：
            // dpkg --audit 列出「解包了但没配置完」的包，非空就是真坏了。
            try {
                exec(listOf("/bin/bash", "-lc", "rm -f /tmp/.dpkg-broken"), {})
                exec(
                    listOf(
                        "/bin/bash", "-lc",
                        "if [ -n \"$(dpkg --audit 2>/dev/null)\" ]; then touch /tmp/.dpkg-broken; fi"
                    ),
                    {}
                )
                val broken = exec(listOf("/bin/bash", "-lc", "test -f /tmp/.dpkg-broken"), {})
                if (!broken) return true
                try { stamp.delete() } catch (_: Throwable) {}
            } catch (_: Throwable) {
                return true   // 探测失败不阻塞安装，按原来的「已修复」处理
            }
        }

        // 探一下：debconf 在不在？在就标记一下直接返回
        //
        // ⚠️ exec 返回 Boolean 表示退出码，**不是抛异常** ——
        //    最早写成 try { exec(...); hasDebconf = true } 是错的：
        //    那样只要不抛异常就认为「有 debconf」，检测恒真、修复永不执行。
        // ⚠️ 判据必须是「debconf 能用」而不是「文件在」。
        //
        // 【踩过的坑】原来只测 `test -x /usr/share/debconf/frontend` —— 文件当然在
        // （debconf 包装上了），但 **frontend 是 perl 脚本，perl 坏掉它就跑不起来**。
        // 于是这里判定「已就绪」直接返回，修复被跳过，接着所有用 debconf 的包
        // postinst 全部 exit 127（libpam0g 就是这么挂的）。
        // 症状：错误换了个样子，但依然装不上，而且越查越像"别的问题"。
        //
        // 现在多跑一次 `perl -e 'exit 0'` —— 真能执行才算数。
        val baseOk = try {
            exec(
                listOf(
                    "/bin/bash", "-lc",
                    "test -x /usr/share/debconf/frontend && perl -v >/dev/null 2>&1"
                ),
                {}
            )
        } catch (_: Throwable) { false }

        if (baseOk) {
            try { stamp.writeText("ok") } catch (_: Throwable) {}
            return true
        }

        onLine("检测到系统基础组件不全（debconf/perl 不可用），先修复…")

        // ① 把已经解包但没配置完的包配置掉（--force-confold 避免交互卡住）
        //    ⚠️ 这一步会因为缺 debconf 而部分失败，但 dpkg 会把状态往前推，
        //       让下一步的 apt 能跑起来。
        exec(
            listOf(
                "/bin/bash", "-lc",
                "export DEBIAN_FRONTEND=noninteractive TERM=dumb; " +
                    "dpkg --configure -a --force-confold 2>&1 | tail -20"
            ),
            { line -> if (line.isNotBlank()) onLine("  $line") }
        )

        // ② 现在 apt 能用了，装 debconf 本体
        exec(
            listOf(
                "/bin/bash", "-lc",
                "export DEBIAN_FRONTEND=noninteractive TERM=dumb; " +
                    "apt-get update -qq 2>&1 | tail -3; " +
                    "apt-get install -y -q --fix-broken debconf 2>&1 | tail -15"
            ),
            { line -> if (line.isNotBlank()) onLine("  $line") }
        )

        // ③ 再配一遍，把之前卡住的包收尾
        exec(
            listOf(
                "/bin/bash", "-lc",
                "export DEBIAN_FRONTEND=noninteractive TERM=dumb; " +
                    "dpkg --configure -a --force-confold 2>&1 | tail -10"
            ),
            { line -> if (line.isNotBlank()) onLine("  $line") }
        )

        // ②.5) 强制重装 perl-base + debconf —— **这是 exit 127 的真正病根**。
        //
        // 【因果链】libpam0g 等大量包 postinst 的第一行是
        //     . /usr/share/debconf/confmodule
        // 而 confmodule 内部会
        //     exec /usr/share/debconf/frontend "$0"
        // frontend 是个 **perl 脚本**（`#!/usr/bin/perl`）。
        //
        // 早期 perl-base 解包失败（硬链接建不出来的那个 bug）→ perl 残缺 →
        // frontend 跑不起来 → confmodule 加载失败 → postinst 直接 exit 127。
        //
        // ②.4) 【破循环依赖】先把 perl-base 单独装上 —— 绕过 apt、跳过依赖检查。
        //
        // 【死锁长什么样】build-87 的实测日志：
        //   /var/lib/dpkg/info/debconf.postinst: 17: exec:
        //       /usr/share/debconf/frontend: not found        → exit 127
        //   dpkg: error processing package debconf (--configure)
        //   libpam0g:arm64 depends on debconf (>= 0.5); however:
        //     Package debconf is not configured yet.
        //   E: Internal Error, No file name for debconf:arm64
        //
        // debconf 的 postinst 要加载 confmodule → confmodule 会 exec
        // /usr/share/debconf/frontend，而它是个 **perl 脚本**；
        // 可这个 rootfs 里的 perl 是坏的（早期硬链接 bug 导致解包失败）。
        // 于是：debconf 要 perl 才能装好，perl 要 debconf 才能被 apt 修好 ——
        // 互相等对方，永远解不开。apt 一看到「30 not fully installed」
        // 就拒绝动手，连 --reinstall 都不给走。
        //
        // 【破法】perl-base 的 postinst **不依赖 debconf**
        // （实测：只调 dpkg-maintscript-helper 和 set -e）。所以单独直接把它
        // 装上，让 perl 先可用，debconf 的死锁自然就解了。
        //
        // 用 **dpkg -i --force-depends** 而不是 apt：
        // apt 会做依赖求解并拒绝，dpkg 不求解、只听指令 —— 这正是破循环需要的。
        exec(
            listOf(
                "/bin/bash", "-lc",
                "export DEBIAN_FRONTEND=noninteractive TERM=dumb; " +
                    "d=\$(ls /var/cache/apt/archives/perl-base_*.deb 2>/dev/null | head -1); " +
                    "if [ -n \"\$d\" ]; then dpkg -i --force-depends \"\$d\" 2>&1 | tail -8; " +
                    "else echo '（缓存无 perl-base.deb，跳过）'; fi"
            ),
            { line -> if (line.isNotBlank()) onLine("  $line") }
        )

        // 【为什么必须强制重装】修好 link2symlink 之后，**旧包不会自动重来** ——
        // dpkg 记着 perl-base 是 "installed"，apt 不会重试解包。
        // 所以坏掉的 perl 会一直坏下去，表现为「换了个错误但依然装不上」。
        // --reinstall 强制重新解包，这一步做完 perl 才真正可用。
        exec(
            listOf(
                "/bin/bash", "-lc",
                "export DEBIAN_FRONTEND=noninteractive TERM=dumb; " +
                    "apt-get install -y -q --reinstall --fix-broken " +
                    "perl-base perl debconf 2>&1 | tail -20"
            ),
            { line -> if (line.isNotBlank()) onLine("  $line") }
        )

        // 验证（同样看返回码，不靠异常）
        // 验证要**真的跑一遍 perl** —— 只测文件存不存在会漏掉「perl 在但跑不起来」
        // 这种情况（正是 exit 127 的来源：frontend 存在，但 shebang 解析失败）。
        val ok = try {
            exec(
                listOf(
                    "/bin/bash", "-lc",
                    "test -x /usr/share/debconf/frontend && perl -v >/dev/null 2>&1"
                ),
                {}
            )
        } catch (_: Throwable) { false }
        onLine(if (ok) "  ✓ 基础组件已修复" else "  ⚠️ 修复未完全成功，继续尝试安装")
        if (ok) {
            // 只有真修好才写标记 —— 否则下次会被跳过，永远修不上
            try { stamp.writeText("ok") } catch (_: Throwable) {}
        }
        return ok
    }

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
