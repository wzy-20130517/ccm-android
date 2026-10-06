package com.ccm.app.core.mcp

import android.content.Context
import android.util.Log
import com.ccm.app.runtime.ProotRuntime
import java.io.File

/**
 * MCP 自动安装器 —— 让用户「点一下就装好」，不用手动配。
 *
 * ═══════════════════════════════════════════════════════════════
 * 【2026-10-06 问题40 新建】
 *
 * ## 为什么需要
 *
 * MCP server（如 mail-qq）是 Node 脚本，而 APK 里没有 Node 运行时。
 * 让用户自己装 Node、自己配路径 = 不可能完成的体验。
 *
 * 本类做全自动：
 * 1. **检测** rootfs 有没有 node
 * 2. **没有就装**（apt install nodejs，约 30MB）
 * 3. **部署 server**（由 MarketClient 解压 tar.gz 到 rootfs）
 * 4. **返回可用的 command**（proot 包装的完整命令行）
 *
 * ## 为什么用 rootfs 的 node 而不是打包 Node 二进制
 *
 * - Node 官方**不发 Android 版**（只有 linux-arm64，需 proot）
 * - 自己编译 Node 工作量大（要交叉编译工具链）
 * - rootfs 的 apt nodejs（18.19.1）够用，且 apt 一条命令就装好
 *
 * ## 安装位置
 *
 * - Node：`/usr/bin/node`（apt 装）
 * - Server：`/root/.ccm/mcp/<名字>/`（从 assets 解压）
 * ═══════════════════════════════════════════════════════════════
 */
class McpInstaller(
    private val context: Context,
    private val runtime: ProotRuntime,
) {

    companion object {
        private const val TAG = "McpInstaller"

        /** Server 在 rootfs 里的部署根。 */
        private const val DEPLOY_ROOT = "/root/.ccm/mcp"
    }

    /** 安装进度回调。 */
    fun interface Progress {
        fun onStep(step: String)
    }

    /**
     * 确保 node 可用（没有就装）。
     *
     * @return 成功与否
     */
    suspend fun ensureNode(onProgress: Progress? = null): Boolean {
        // 1. 检测
        if (hasNode()) {
            Log.i(TAG, "node 已就绪")
            return true
        }

        // 2. apt 装
        onProgress?.onStep("正在安装 Node.js（约 30MB）…")
        Log.i(TAG, "开始 apt install nodejs")

        var ok = false
        try {
            runtime.execWithTimeout(
                command = listOf(
                    "/bin/bash", "-c",
                    "export DEBIAN_FRONTEND=noninteractive; " +
                        "apt-get update -qq 2>&1 | tail -2; " +
                        "apt-get install -y -qq nodejs 2>&1 | tail -5; " +
                        "command -v node && node --version",
                ),
                workDir = "/root",
                onLine = { line ->
                    if (line.contains("/usr/bin/node") || line.contains("v18")) ok = true
                },
                timeoutMs = 10 * 60_000L,
                idleMs = 3 * 60_000L,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "装 node 失败：${t.message}", t)
        }

        // 3. 复查
        val result = hasNode()
        Log.i(TAG, "node 安装${if (result) "成功" else "失败"}")
        return result
    }

    /** rootfs 里有没有 node。 */
    fun hasNode(): Boolean = try {
        var found = false
        runtime.execWithTimeout(
            command = listOf("/bin/bash", "-c", "command -v node || true"),
            workDir = "/root",
            onLine = { line -> if (line.trim().endsWith("/node")) found = true },
            timeoutMs = 10_000,
            idleMs = 5_000,
        )
        found
    } catch (_: Throwable) { false }

/**
     * 生成 proot 包装的完整命令行（给 McpStdioTransport 用）。
     *
     * ⚠️ 关键：MCP server 跑在 rootfs 里，但 **McpStdioTransport 用
     * ProcessBuilder 直接跑**（不经过 proot）—— 所以这里要生成
     * 「proot 包装 + node 脚本」的完整命令。
     *
     * @param serverPath rootfs 里的入口脚本路径（如 /root/.ccm/mcp/mail-qq/server.mjs）
     * @param env 环境变量
     * @return 可直接给 ProcessBuilder 的 argv
     */
    fun buildProotCommand(serverPath: String, env: Map<String, String> = emptyMap()): List<String> {
        val rootfs = runtime.rootfsDir().absolutePath
        val libDir = context.applicationInfo.nativeLibraryDir

        val cmd = mutableListOf<String>()
        cmd += "$libDir/libproot.so"
        cmd += "--kill-on-exit"
        cmd += "--link2symlink"
        cmd += "--sysvipc"
        cmd += "--kernel-release=5.15.0"
        cmd += "-L"
        cmd += "-0"
        cmd += "-r"
        cmd += rootfs
        cmd += "--cwd=/root"
        cmd += "--bind=/dev"
        cmd += "--bind=/proc"
        cmd += "--bind=/sys"
        cmd += "/usr/bin/env"
        cmd += "-i"
        cmd += "HOME=/root"
        cmd += "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        // 用户配的环境变量
        env.forEach { (k, v) -> cmd += "$k=$v" }
        cmd += "/usr/bin/node"
        cmd += serverPath
        return cmd
    }

    /** 递归列 assets 里的文件（相对路径）。 */
    private fun listAssets(assets: android.content.res.AssetManager, path: String): List<String> {
        val out = mutableListOf<String>()
        fun walk(p: String, prefix: String) {
            val items = try { assets.list(p) } catch (_: Throwable) { null } ?: return
            for (item in items) {
                val child = if (p.isEmpty()) item else "$p/$item"
                val childPrefix = if (prefix.isEmpty()) item else "$prefix/$item"
                // assets.list 对目录返回子项，对文件返回空数组 —— 用「有没有子项」判断
                val sub = try { assets.list(child) } catch (_: Throwable) { null }
                if (sub.isNullOrEmpty()) {
                    out += childPrefix
                } else {
                    walk(child, childPrefix)
                }
            }
        }
        walk(path, "")
        return out
    }
}
