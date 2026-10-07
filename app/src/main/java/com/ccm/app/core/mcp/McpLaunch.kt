package com.ccm.app.core.mcp

import android.util.Log
import com.ccm.app.runtime.ProotRuntime
import java.io.File

/**
 * MCP stdio 启动器 —— 把 mcp.json 里的 command/args 翻译成「可执行的 argv」。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-07 新增】差距报告 Top 10 第 1 条的落地层
 *
 * ## 为什么需要这一层
 *
 * APK 的 Node **不在 Android 侧**，而在 rootfs（proot Ubuntu）里。
 * 所以 mcp.json 里写的 `command: "node"` 在 Android 侧根本找不到可执行文件 ——
 * 必须翻译成「proot 包装 + rootfs 内的 node」才能跑起来。
 *
 * 翻译要做两件事：
 *
 * **1. 包 proot**
 * ```
 * node /path/server.mjs
 *   ↓
 * <libproot.so> --kill-on-exit … /usr/bin/env -i HOME=/root PATH=… /usr/bin/node /path/server.mjs
 * ```
 * 用 `env -i` 清掉宿主环境再起 node —— 否则宿主侧的 `LD_LIBRARY_PATH`
 * （指向 APK 的 nativeLibraryDir）会漏进 rootfs，让 node 加载到错误的库。
 * 这是 McpInstaller.buildProotCommand 已经踩过的坑，此处沿用同一姿势。
 *
 * **2. 路径翻译**（宿主视角 ↔ 客户机视角）
 *
 * | 形态 | 例子 | 处理 |
 * |---|---|---|
 * | 宿主机绝对路径 | `/data/user/0/com.ccm.app/files/rootfs/root/.ccm/mcp/x/server.mjs` | 去掉 rootfs 前缀 |
 * | 客户机路径 | `/root/.ccm/mcp/x/server.mjs` | 原样 |
 * | Termux 路径 | `/data/data/com.termux/files/home/…` | 原样（APK 沙箱读不到，让错误自然暴露） |
 *
 * ## 为什么还要兜「市场安装写的配置」
 *
 * `MarketClient` 写 mcp.json 时调的是
 * `McpInstaller.buildProotCommand(entry.absolutePath, env)` ——
 * 传进去的是**宿主机路径**（`File(rootfs, "root/.ccm/mcp/<id>")` 的 absolutePath），
 * 而那个方法的契约是「客户机路径」。于是生成的 argv 末尾挂着宿主机路径，
 * proot 里的 node 按客户机视角解析不到它。
 *
 * 本层统一兜底：**无论配置是谁写的、写的哪种路径，出口处都规整一遍**。
 * 思路与 CLI 的 `normalizeToolSchema` 一致 —— 跨来源的兼容问题在唯一出口收口，
 * 不指望每个写入方都写对。
 * ═══════════════════════════════════════════════════════════════
 */
object McpLaunch {

    private const val TAG = "McpLaunch"

    /** 客户机内的默认 PATH（与 McpInstaller.buildProotCommand 保持一致）。 */
    private const val GUEST_PATH =
        "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    /**
     * proot 里「选项后跟独立值」的参数名。
     *
     * 只用于 [normalizeProotArgv] 跳过值元素（那些值是**宿主视角**路径，
     * 翻译了反而错）。列全我们生成的形态用到的即可，多列无害。
     */
    private val PROOT_VALUE_OPTS = setOf(
        "-r", "--rootfs",
        "-b", "--bind",
        "-w", "--cwd",
        "-k", "--kernel-release",
        "-i", "--change-id",
    )

    /** 启动规格 —— 翻译的产物。 */
    data class Spec(
        /** 实际交给 ProcessBuilder 的 argv（首元素 = 可执行文件）。 */
        val argv: List<String>,
        /** true = 需要给 ProcessBuilder 补 proot 环境变量（PROOT_TMP_DIR 等）。 */
        val needsProotEnv: Boolean,
        /** 裸跑时要注入的环境变量（proot 形态已并入 argv，故为空）。 */
        val processEnv: Map<String, String> = emptyMap(),
    ) {
        /** argv 为空 = 这条配置没法启动（调用方据此报错）。 */
        val isEmpty: Boolean get() = argv.isEmpty()
    }

    /**
     * 把 mcp.json 的 command/args 翻译成可执行的 [Spec]。
     *
     * @param runtime proot 运行时（null 时退化为裸跑 —— 只在测试等场景出现）
     * @param commandLine `command` + `args` 拼成的命令行
     * @param env 用户配的环境变量
     */
    fun build(
        runtime: ProotRuntime?,
        commandLine: List<String>,
        env: Map<String, String> = emptyMap(),
    ): Spec {
        if (commandLine.isEmpty()) return Spec(emptyList(), false)

        val exe = commandLine[0]
        val rest = commandLine.drop(1)

        // ── ① 已经是 proot 形态（市场安装写的）→ 只做路径规整 ──────────
        if (runtime != null && isProotCommand(runtime, exe)) {
            return Spec(normalizeProotArgv(runtime, commandLine), true)
        }

        // ── ② node 家族（node / nodejs / npx / npm）→ 包 proot ────────
        if (runtime != null && isNodeFamily(exe)) {
            val guestBin = resolveGuestBinary(runtime, exe)
            if (guestBin == null) {
                Log.w(TAG, "rootfs 里找不到「$exe」—— 这条配置无法启动")
                return Spec(emptyList(), false)
            }
            val inner = mutableListOf(
                "/usr/bin/env", "-i",
                "HOME=/root",
                "PATH=$GUEST_PATH",
            )
            env.forEach { (k, v) -> inner += "$k=$v" }
            inner += guestBin
            rest.forEach { inner += toGuestPath(runtime, it) }

            return Spec(runtime.buildProotArgs(workDir = "/root", command = inner), true)
        }

        // ── ③ 其他 → 裸跑（宿主侧可执行文件，如用户自放的静态二进制）──
        return Spec(commandLine, false, processEnv = env)
    }

    /**
     * 宿主机路径 → 客户机路径（去掉 rootfs 前缀）。
     *
     * 幂等：不以 rootfs 开头的字符串原样返回。
     */
    fun toGuestPath(runtime: ProotRuntime, path: String): String {
        val rootfs = runtime.rootfsDir().absolutePath.trimEnd('/')
        return if (path.startsWith("$rootfs/")) path.removePrefix(rootfs) else path
    }

    /** 这条 argv 是不是已经把我们自己的 proot 放在首位了。 */
    private fun isProotCommand(runtime: ProotRuntime, exe: String): Boolean {
        if (exe == runtime.prootBin.absolutePath) return true
        return exe.endsWith("libproot.so")
    }

    /** node / nodejs / npx / npm —— 都需要包 proot（Android 侧没有这些）。 */
    private fun isNodeFamily(exe: String): Boolean {
        val base = exe.substringAfterLast('/')
        return base == "node" || base == "nodejs" || base == "npx" || base == "npm"
    }

    /**
     * 规整已经是 proot 形态的 argv —— **只翻译「命令部分」的路径**。
     *
     * 【为什么只翻命令部分】proot 自己的参数（如 `-r /data/user/0/…/rootfs`）
     * 是**宿主机视角**的，翻译了反而错。分界点：跳过 argv[0]（proot 自身，
     * 它不以 `-` 开头、不能当判据）之后，第一个不以 `-` 开头的元素即命令开始处
     * （proot 的参数全部以 `-` 开头）。
     */
    private fun normalizeProotArgv(runtime: ProotRuntime, argv: List<String>): List<String> {
        if (argv.isEmpty()) return argv
        val out = ArrayList<String>(argv.size)
        // argv[0] = proot 可执行文件本身，原样保留且不参与「命令开始」判定
        out += argv[0]
        var i = 1
        var inCommand = false
        while (i < argv.size) {
            val a = argv[i]
            if (inCommand) {
                out += toGuestPath(runtime, a)
                i++
                continue
            }
            out += a
            // 带独立值的选项 → 它的值原样保留（宿主视角）
            if (a in PROOT_VALUE_OPTS && i + 1 < argv.size) {
                out += argv[i + 1]
                i += 2
                continue
            }
            if (!a.startsWith("-")) inCommand = true
            i++
        }
        return fixupExecutable(runtime, out)
    }

    /**
     * 命令部分的可执行文件若在 rootfs 里不存在，按 PATH 重解析一次。
     *
     * 【为什么必须】市场安装链（`McpInstaller.buildProotCommand`）把
     * `/usr/bin/node` **写死**进 argv。而 rootfs 里的 node 可能已被升级到
     * `/usr/local/bin/node`（apt 的 18 太旧，装插件宿主时升到 24）——
     * 绝对路径绕过 PATH，于是升级过的 node 用不上。
     *
     * DshHostManager 真机踩过同款（`host.log` 仍报 v18 的 SyntaxError），
     * 它在自己那侧做了替换；这里对「外部写入的 proot 形态配置」统一兜底 ——
     * 与 [normalizeToolSchema] 同思路：**出口处收口，不指望每个写入方都写对**。
     *
     * 只处理 `env -i K=V…` 之后的那个元素；解析不到就保持原样
     * （让错误自然暴露，不静默改写用户配置）。
     */
    private fun fixupExecutable(runtime: ProotRuntime, argv: List<String>): List<String> {
        // 定位命令部分的起点（跳过 argv[0] 与所有 proot 选项）
        var i = 1
        while (i < argv.size) {
            val a = argv[i]
            if (a in PROOT_VALUE_OPTS) { i += 2; continue }
            if (a.startsWith("-")) { i++; continue }
            break
        }
        if (i >= argv.size) return argv

        // 跳过 `env -i`（及其 -i/-u 之类短选项）与随后的 K=V
        var j = i
        if (argv[j].substringAfterLast('/') == "env") {
            j++
            while (j < argv.size && (argv[j].startsWith("-") || argv[j].contains('='))) j++
        }
        if (j >= argv.size) return argv

        val exe = argv[j]
        if (!exe.startsWith("/")) return argv          // 裸命令名 → 交给 PATH，不动
        val rel = exe.trimStart('/')
        if (File(runtime.rootfsDir(), rel).exists()) return argv   // 存在 → 不动

        val base = exe.substringAfterLast('/')
        val resolved = GUEST_PATH.split(':')
            .filter { it.isNotBlank() }
            .map { "/" + it.trimStart('/') + "/" + base }
            .firstOrNull { File(runtime.rootfsDir(), it.trimStart('/')).exists() }
            ?: return argv                              // 找不到 → 保持原样

        Log.i(TAG, "可执行文件 $exe 在 rootfs 里不存在，改用 $resolved")
        val fixed = ArrayList(argv)
        fixed[j] = resolved
        return fixed
    }

    /**
     * 解析「客户机视角的可执行文件路径」。
     *
     * 三种输入形态：
     * - 宿主机路径（含 rootfs 前缀）→ 翻译
     * - 客户机绝对路径 → 校验存在后原样
     * - 裸命令名 → 按客户机 PATH 顺序探测
     *
     * @return 客户机路径；找不到返回 null
     */
    private fun resolveGuestBinary(runtime: ProotRuntime, exe: String): String? {
        val rootfs = runtime.rootfsDir()
        val rootfsPath = rootfs.absolutePath.trimEnd('/')

        // ① 宿主机路径 → 翻译（同时校验真实存在）
        if (exe.startsWith("$rootfsPath/")) {
            return if (File(exe).exists()) exe.removePrefix(rootfsPath) else null
        }

        // ② 客户机绝对路径 → 校验 rootfs 内真实存在
        if (exe.startsWith("/")) {
            return if (File(rootfs, exe.removePrefix("/")).exists()) exe else null
        }

        // ③ 裸命令名 → 按 PATH 顺序探测（/usr/local/bin 优先，与 DshHostManager 一致）
        for (dir in GUEST_PATH.split(":")) {
            if (dir.isBlank()) continue
            val rel = dir.trimStart('/') + "/" + exe
            if (File(rootfs, rel).exists()) return "/$rel"
        }
        return null
    }
}
