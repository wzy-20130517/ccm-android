package com.ccm.app.ui.chat

import com.ccm.app.tools.bash.BashChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * `/review` 工作区审查 —— 本地静态分析，**零 API 调用**（2026-10-07 对齐 CLI `cmdReview`）。
 *
 * ## 与 CLI 版的差异
 *
 * | 检查项 | CLI（`cmd-extensions.mjs:1293`） | APK |
 * |---|---|---|
 * | Git 状态 | `git status --porcelain` | 同（走 [BashChannel]，通道不可用则跳过） |
 * | .gitignore | 读文件检查 node_modules / .env | 同 |
 * | CLAUDE.md | 存在性 | 同（APK 落在存储根） |
 * | config.json | 解析 + 字段完整性 | 同（APK 是 providers/current 结构） |
 * | 敏感文件 | 未跟踪的 .env/key/pem 提醒 | 同 |
 *
 * 输出格式也照 CLI（`✓` / `!` / `⚠` / `•` 前缀 + 分区）。
 */
internal object SlashReview {

    /** 审查结果（分区块，便于 UI 直接拼接）。 */
    suspend fun run(
        channel: BashChannel?,
        storageRoot: File?,
        configFile: File?,
        cwd: String,
    ): String =
        withContext(Dispatchers.IO) {
            val lines = mutableListOf<String>()
            lines += "**工作区审查报告**"
            lines += "━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

            // ── 1) Git 状态 ──────────────────────────────────────────────
            var gitOk = false
            if (channel == null || !channel.isAvailable()) {
                lines += "• Bash 通道不可用（跳过 Git 检查）"
                lines += "  _${channel?.unavailableReason() ?: "未装配"}_"
            } else {
                val res = try {
                    channel.execute("git status --porcelain", cwd.ifBlank { null }, 30_000) {}
                } catch (t: Throwable) {
                    null
                }
                if (res == null || res.exitCode != 0) {
                    lines += "• 非 Git 仓库（跳过 Git 检查）"
                } else {
                    gitOk = true
                    val status = res.stdout.trim()
                    if (status.isEmpty()) {
                        lines += "✓ Git 工作区干净（无未提交改动）"
                    } else {
                        val mods = status.split("\n").filter { it.isNotBlank() }
                        val staged = mods.count { it.length > 0 && it[0] != ' ' && it[0] != '?' }
                        val unstaged = mods.count { it.length > 1 && it[1] != ' ' && it[0] != '?' }
                        val untracked = mods.count { it.startsWith("?") }
                        lines += "! Git 有 ${mods.size} 项改动"
                        lines += "  已暂存: $staged  未暂存: $unstaged  未跟踪: $untracked"
                        if (untracked > 5) lines += "  ⚠ 未跟踪文件较多（$untracked），考虑加 .gitignore"

                        // 疑似敏感文件（未跟踪）
                        val suspicious = mods.filter { it.startsWith("?") }
                            .map { it.removePrefix("?? ").trim() }
                            .filter { f ->
                                f.matches(Regex(".*\\.(env|key|pem|ssh|token)$", RegexOption.IGNORE_CASE)) ||
                                    f.contains("secret", ignoreCase = true) ||
                                    f.contains("password", ignoreCase = true) ||
                                    f.contains("credential", ignoreCase = true)
                            }
                            .take(5)
                        if (suspicious.isNotEmpty()) {
                            lines += "  ⚠ 疑似敏感文件未跟踪："
                            suspicious.forEach { lines += "     $it" }
                        }
                    }
                }
            }

            // ── 2) .gitignore 检查（仅 git 仓库） ─────────────────────────
            if (gitOk) {
                val gi = File(cwd, ".gitignore")
                if (!gi.exists()) {
                    lines += "⚠ 缺少 .gitignore — 推荐加一个"
                } else {
                    val text = try { gi.readText() } catch (_: Throwable) { "" }
                    if (!text.contains("node_modules")) lines += "⚠ .gitignore 没有 node_modules"
                    if (!text.contains(".env")) lines += "⚠ .gitignore 没有 .env — 可能泄露环境变量"
                }
            }

            // ── 3) CLAUDE.md（APK 落在存储根） ───────────────────────────
            if (storageRoot != null) {
                val mem = File(storageRoot, "CLAUDE.md")
                if (!mem.exists()) {
                    lines += "• 无 CLAUDE.md — 用 `/memory init` 创建项目记忆"
                } else {
                    lines += "✓ CLAUDE.md 存在（${mem.length()} 字节）"
                }
            }

            // ── 4) config.json 健康 ──────────────────────────────────────
            if (configFile != null) {
                if (!configFile.exists()) {
                    lines += "• 无 config.json"
                } else {
                    val r = com.ccm.app.core.provider.AppConfig.load(configFile)
                    if (r.error != null) {
                        lines += "✗ config.json 解析失败：`${r.error}`"
                    } else {
                        val p = r.config.currentProvider
                        if (p == null) {
                            lines += "⚠ config.json 的 current=${r.config.current} 但该 Provider 不存在"
                        } else {
                            val miss = buildList {
                                if (p.url.isBlank()) add("url")
                                if (p.apiKey.isNullOrBlank() && p.apiKeys.isNullOrEmpty()) add("apiKey")
                                if (p.model.isBlank()) add("model")
                            }
                            if (miss.isEmpty()) {
                                lines += "✓ config.json 健康（Provider: ${p.name.ifBlank { p.id }}）"
                            } else {
                                lines += "⚠ 当前 Provider 缺字段：${miss.joinToString(", ")}"
                            }
                        }
                    }
                }
            }

            // ── 5) 工作区目录 ────────────────────────────────────────────
            if (cwd.isNotBlank()) {
                val d = File(cwd)
                lines += if (d.isDirectory) "✓ 工作区可访问：`$cwd`" else "⚠ 工作区不存在：`$cwd`"
            } else {
                lines += "• 工作区未设置（用 `/workspace <路径>` 设置）"
            }

            lines.joinToString("\n")
        }
}
