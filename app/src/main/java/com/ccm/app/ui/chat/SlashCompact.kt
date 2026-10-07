package com.ccm.app.ui.chat

import com.ccm.app.core.ChatSession
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `/compact` 子命令实现层（2026-10-07 对齐 CLI `core/commands/cmd-compact.mjs`）。
 *
 * ## 子命令语义（与 CLI 四分支一一对应）
 *
 * | 输入 | 行为 | API |
 * |---|---|---|
 * | `/compact`            | micro → 仍吃紧才真摘要（[ChatSession.compactNowSuspend]） | 可能 1 次 |
 * | `/compact status`     | 只显示压力/建议/micro 可回收量，**不执行** | 0 |
 * | `/compact micro`      | 只回收可再生工具输出（[ChatSession.compactNow]） | 0 |
 * | `/compact force [N]`  | 跳过软阈值直压，摘要保留最近 N 条（默认 10） | 1 次 |
 * | `/compact <N>`        | 同 force，keepLast = N | 1 次 |
 *
 * ## 为什么单独一个文件
 * 原来 `/compact` 精确匹配在 [ChatScreenConnected] 的老 when 里（只认无参形态），
 * 子命令全丢。现在解析与实现集中在这里，handler 的 query 分区只留一行转发。
 *
 * ## 与 CLI 的差异
 * - CLI 的 `micro [dry]` 支持 dry-run 参数；APK 的 status 分支自带
 *   micro 预估（[com.ccm.app.core.compact.Compactor.microCompact] 不改原历史，
 *   天然可当 dry-run），所以不再单列 `micro dry`。
 * - CLI 的 `force` 先 micro 再摘要（`compactService.compact` 内部如此）；
 *   APK 的 force 直接摘要（micro 的收益已包含在摘要里，多一步白写一次备份）。
 *   `micro + 摘要` 的完整流程在裸 `/compact` 里。
 */
internal object SlashCompact {

    /** 摘要默认保留尾部条数（对齐 CLI `keepLast` 默认 10）。 */
    private const val DEFAULT_KEEP_LAST = 10

    /**
     * 入口：解析子命令并分发。返回 null 不会发生（未识别时回 usage 提示）。
     *
     * 同步契约：命令分发是同步纯函数（[handleSlashCommand] 的契约），
     * 要发 API 的分支用 `appScope.launch` 起后台协程，结果 injectNotice 回屏
     * （与 `/summary` `/btw` 同一个模式）。
     */
    fun dispatch(ctx: SlashContext, arg: String): SlashResult {
        val session = ctx.session
            ?: return SlashResult.Notice("无会话，无法压缩。")
        val parts = arg.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val head = parts.firstOrNull()?.lowercase()

        return when {
            head == null -> bare(session)

            head == "status" -> status(session)

            head == "micro" -> micro(session)

            head == "force" -> {
                val n = parts.getOrNull(1)?.toIntOrNull()
                if (parts.size > 2 || (parts.size == 2 && n == null)) {
                    usage()
                } else {
                    force(session, n ?: DEFAULT_KEEP_LAST)
                }
            }

            head.toIntOrNull() != null -> {
                if (parts.size > 1) usage()
                else force(session, head.toInt())
            }

            else -> usage()
        }
    }

    private fun usage() = SlashResult.Notice(
        "**用法**\n\n" +
            "- `/compact` — 自动压缩（先回收工具输出，仍吃紧再摘要）\n" +
            "- `/compact status` — 只看压力与建议，不执行\n" +
            "- `/compact micro` — 只回收旧工具输出（零 API 调用）\n" +
            "- `/compact force [N]` — 跳过软阈值直压，保留最近 N 条（默认 10）\n" +
            "- `/compact <N>` — 同 force，保留最近 N 条\n\n" +
            "_压缩会把稳定前缀压成一段摘要，信息有损；压缩前自动备份到 compact-trash。_",
    )

    // ── status：只读，零副作用 ────────────────────────────────────────

    private fun status(session: ChatSession): SlashResult {
        val c = session.appContainer
        val history = c.agentLoop.getHistory()
        val ac = com.ccm.app.AppGraph.container?.autoCompact

        // micro 预估：microCompact 不改原历史，调完不 setHistory 即为 dry-run。
        val micro = try { c.compactor.microCompact(history) } catch (_: Throwable) { null }

        val sb = StringBuilder()
        sb.append("**压缩状态**\n\n")
        sb.append(session.contextReport()).append("\n\n")
        if (micro != null && micro.changed) {
            sb.append("- micro 可回收：约 ${micro.reclaimedTokens} tokens（零 API，`/compact micro` 执行）\n")
        } else {
            sb.append("- micro 可回收：无（旧工具输出都已在保护区）\n")
        }
        if (ac != null) {
            sb.append("- 自动压缩：").append(
                if (ac.isEnabled) {
                    val t = if (ac.tokenLimit > 0) "${ac.tokenLimit} tokens" else "关"
                    val m = if (ac.messageLimit > 0) "${ac.messageLimit} 条" else "关"
                    "开启（阈值 $t / $m）"
                } else {
                    "关闭（仅手动 /compact）"
                },
            ).append("\n")
            if (ac.isTripped) {
                sb.append("- ⚠ 自动压缩断路器已跳闸（连续 ${ac.failures} 次失败），需手动 `/compact` 恢复\n")
            }
        }
        sb.append("\n_说明：压缩生成一段普通 [历史摘要]，可整体重写；用 `/compact-trash` 可恢复压缩前的完整记录。_")
        return SlashResult.Notice(sb.toString())
    }

    // ── micro：零 API ─────────────────────────────────────────────────

    private fun micro(session: ChatSession): SlashResult =
        SlashResult.Notice(try { session.compactNow() } catch (t: Throwable) { "压缩失败：${t.message}" })

    // ── 裸 /compact：micro + 仍吃紧才摘要（挂起） ─────────────────────

    private fun bare(session: ChatSession): SlashResult {
        val scope = com.ccm.app.AppGraph.appScope
            ?: return SlashResult.Notice("应用作用域未就绪，无法压缩。")
        if (session.isRunning) {
            return SlashResult.Notice("正在执行任务，等这轮结束再压缩。")
        }
        scope.launch {
            val text = try {
                session.compactNowSuspend()
            } catch (t: Throwable) {
                "压缩失败：${t.message}"
            }
            try { session.injectNotice(text) } catch (_: Throwable) {}
        }
        return SlashResult.Notice("正在压缩…（结果稍后出现在对话里）")
    }

    // ── force / <N>：无条件摘要，保留最近 keepLast 条（挂起） ─────────

    private fun force(session: ChatSession, keepLast: Int): SlashResult {
        val scope = com.ccm.app.AppGraph.appScope
            ?: return SlashResult.Notice("应用作用域未就绪，无法压缩。")
        if (keepLast < 0) return SlashResult.Notice("保留条数不能为负：$keepLast")
        if (session.isRunning) {
            return SlashResult.Notice("正在执行任务，等这轮结束再压缩。")
        }
        scope.launch {
            val text = try {
                runForce(session, keepLast)
            } catch (t: Throwable) {
                "强制压缩失败：${t.message}"
            }
            try { session.injectNotice(text) } catch (_: Throwable) {}
        }
        return SlashResult.Notice(
            "正在强制压缩（保留最近 $keepLast 条）…（结果稍后出现在对话里）",
        )
    }

    /**
     * force 的实际执行：PreCompact hook → 备份 → split(keepLast) → 摘要请求
     * → assemble → setHistory → PostCompact hook。
     *
     * 逻辑照 [ChatSession.summarizeAndReplace]，差异两处：
     * 1. keepLast 可参数化（那个用默认 10）
     * 2. 压缩前写 compact-trash 备份（那个只在 micro 分支写）
     */
    private suspend fun runForce(session: ChatSession, keepLast: Int): String {
        // PreCompact hook（DENY 可阻止压缩，对齐 compactNowSuspend）
        try {
            val r = session.triggerHook("PreCompact")
            if (r?.deny == true) return "PreCompact hook 阻止了压缩：${r.message}"
        } catch (_: Throwable) {}

        val c = session.appContainer
        val history = c.agentLoop.getHistory()
        if (history.isEmpty()) return "历史为空，无需压缩。"

        // 压缩前备份（与 ChatSession.compactNow 同款）
        val backupName = try {
            val root = c.agentLoop.toolStorageRoot() ?: throw IllegalStateException("no storage")
            val trashDir = java.io.File(root, "compact-trash").apply { mkdirs() }
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val f = java.io.File(trashDir, "session-$ts.json")
            val arr = org.json.JSONArray()
            history.forEach { m ->
                arr.put(org.json.JSONObject().apply {
                    put("role", m.role)
                    put("text", m.text)
                    put("timestamp", m.timestamp)
                })
            }
            f.writeText(arr.toString())
            f.name
        } catch (_: Throwable) { null }

        val split = c.compactor.splitForSummary(history, keepLast)
            ?: return "历史仅 ${history.size} 条（≤ 保留 $keepLast 条），无需摘要压缩。"

        val input = c.compactor.buildSummaryInput(split.toSummarize)
        val prompt = c.compactor.buildSummaryPrompt(input)
        val sys = c.compactor.summarySystemPrompt()

        val userMsg = buildJsonObject {
            put("role", JsonPrimitive("user"))
            put("content", JsonPrimitive(prompt))
        }
        val raw = try {
            c.apiClient.chat(
                system = sys,
                messages = listOf(userMsg),
                effortOverride = "medium",   // 摘要固定 medium（防思考吃光预算）
            ).text
        } catch (t: Throwable) {
            return "摘要请求失败：${t.message}\n\n_（原历史未被改动）_"
        }

        val summary = c.compactor.formatSummary(c.compactor.extractSummary(raw))
        if (summary.isBlank()) return "摘要为空（模型未按格式返回）—— 原历史未被改动。"

        c.agentLoop.setHistory(c.compactor.assembleCompacted(split, summary))

        try { session.triggerHook("PostCompact") } catch (_: Throwable) {}

        return buildString {
            append("**已强制压缩**：${split.toSummarize.size} 条 → 1 段摘要，保留最近 ${split.toKeep.size} 条。")
            if (split.lostPaths.isNotEmpty()) {
                append("\n\n_已记录 ${split.lostPaths.size} 个读过的文件路径（摘要里，不用重读）。_")
            }
            if (backupName != null) {
                append("\n\n_压缩前备份：`compact-trash/$backupName`（可用 `/compact-trash` 查看）_")
            }
        }
    }
}
