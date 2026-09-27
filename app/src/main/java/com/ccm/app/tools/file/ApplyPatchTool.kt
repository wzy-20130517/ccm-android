package com.ccm.app.tools.file

import com.ccm.app.core.tool.Tool
import com.ccm.app.core.tool.ToolContext
import com.ccm.app.core.tool.ToolResult
import com.ccm.app.core.tool.ToolSchema
import com.ccm.app.core.tool.ToolSchema.str
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * ApplyPatch —— 应用 unified diff（git diff 格式）到多个文件。
 *
 * 参照 Node 版 `core/tools-smart.mjs:ApplyPatchTool`（约 200 行）。
 *
 * ══════════════════════════════════════════════════════════════
 *  核心承诺：**批次原子性**
 * ══════════════════════════════════════════════════════════════
 *
 * 工具描述里写着「任一文件解析/应用失败则全部不改」。
 * 但 CCM 原实现是循环 `atomicWrite` —— **单文件原子 ≠ 批次原子**：
 * 第 N 个失败时前 N-1 个已落盘且无回滚，承诺是假的。
 *
 * 修法是两阶段提交（与 CCM 后来的修复一致）：
 * ```
 * 阶段 1 (prepare)：全部写 .tmp 临时文件 → 任一失败则清理全部 tmp、原文件零改动
 * 阶段 2 (commit) ：逐个 rename 覆盖 → 中途失败用 oldContent 回滚已 rename 的
 * ```
 *
 * ⚠️ 备份/undo 快照挪到 **prepare 之后、commit 之前** ——
 * 避免注定失败的 patch 污染回收站和 undo 历史。
 *
 * 【为什么不用 applyPatch 之外的方案】
 * 模型一次生成完整 patch 比多次 Edit 更省 token，且能表达
 * 「跨文件的重命名」「新增+删除+修改混合」这类操作。
 */
class ApplyPatchTool(
    private val trashStore: TrashStore,
    private val undoStore: UndoStore,
) : Tool() {

    override val name = "ApplyPatch"
    override val description =
        "应用 unified diff（git diff 格式）到多个文件。原子操作：任一文件解析/应用失败则全部不改。" +
            "支持新增/删除/修改文件。比多个 Edit 更适合大改动。"
    override val isDestructive = true
    override val isConcurrencySafe = false
    override val maxResultSizeChars = 5_000

    override val inputSchema: JsonObject = ToolSchema.objectSchema(
        "patch" to ToolSchema.string("unified diff 文本（git diff 输出格式，含 diff --git / --- / +++ / @@ hunk）"),
        "base_path" to ToolSchema.string("patch 内相对路径的基准目录（默认当前工作目录）"),
        "backup_note" to ToolSchema.string("（可选）这次修改的目的，写进回收站备份名"),
        required = listOf("patch"),
    )

    override fun validateInput(input: JsonObject): String? =
        if (input.str("patch").isNullOrBlank()) "patch is required (unified diff string)" else null

    /** 阶段 1 的暂存项 */
    private data class Staged(
        val target: File,
        val tmp: File,
        val oldContent: String?,   // null = 新文件（原来不存在）
        val existedBefore: Boolean,
        val isDelete: Boolean,
    )

    override suspend fun execute(input: JsonObject, ctx: ToolContext): ToolResult {
        val patchText = input.str("patch")!!
        val note = input.str("backup_note") ?: ""

        val baseDir = try {
            val raw = input.str("base_path")
            if (raw == null) {
                File(ctx.cwd)
            } else {
                PathGuard.resolveAllowed(File(ctx.cwd), ctx.extraDirs.map { File(it) }, raw)
            }
        } catch (e: Throwable) {
            return ToolResult.Error(e.message ?: "base_path 错误", ToolResult.INVALID_INPUT)
        }

        // ── 解析 ────────────────────────────────────────────────
        val patches = try {
            PatchParser.parse(patchText)
        } catch (e: Throwable) {
            return ToolResult.invalidInput("patch 解析失败：${e.message}")
        }
        if (patches.isEmpty()) return ToolResult.invalidInput("patch 中没有可应用的改动")

        // ── 阶段 1：prepare（全部写 tmp，任一失败则全撤）────────────
        val staged = mutableListOf<Staged>()
        val applied = mutableListOf<String>()

        try {
            for (fp in patches) {
                ctx.checkCancelled()

                val target = try {
                    PathGuard.resolveAllowed(File(ctx.cwd), ctx.extraDirs.map { File(it) }, fp.path)
                } catch (e: Throwable) {
                    throw IllegalStateException("路径越界 ${fp.path}：${e.message}")
                }

                // 并发写守卫（只对已有文件有意义）
                if (target.exists()) {
                    FileVersionTracker.staleReason(target)?.let {
                        throw IllegalStateException(it)
                    }
                }

                val existedBefore = target.exists()
                val oldContent = if (existedBefore) target.readText() else null

                // 计算新内容
                val newContent: String? = when {
                    fp.isDelete -> null   // 删除文件
                    fp.isNew || !existedBefore -> PatchParser.buildNewFile(fp.hunks)
                    else -> {
                        val base = oldContent
                            ?: throw IllegalStateException("${fp.path} 不存在，但 patch 不是新增")
                        PatchParser.applyHunks(base, fp.hunks)
                    }
                }

                if (newContent == null) {
                    // 删除：标记，commit 阶段直接删
                    staged += Staged(target, File(target.parentFile, ".${target.name}.patchdel"), oldContent, existedBefore, true)
                    applied += "删除 ${fp.path}"
                } else {
                    val tmp = File(target.parentFile, ".${target.name}.applypatch-${System.nanoTime().toString(36)}")
                    tmp.parentFile?.mkdirs()
                    tmp.writeText(newContent)
                    staged += Staged(target, tmp, oldContent, existedBefore, false)
                    applied += "${if (existedBefore) "修改" else "新增"} ${fp.path}（${fp.hunks.size} hunk）"
                }
            }
        } catch (e: Throwable) {
            // prepare 失败：清理所有 tmp，原文件零改动
            staged.forEach { runCatching { if (!it.isDelete) it.tmp.delete() } }
            return ToolResult.Error("ApplyPatch 准备阶段失败，未做任何改动：${e.message}", ToolResult.INVALID_INPUT)
        }

        // ── 备份 + undo 快照（在 prepare 成功后、commit 之前）────────
        staged.forEach { s ->
            if (s.existedBefore && s.oldContent != null) {
                runCatching {
                    undoStore.saveSnapshot(s.target, s.oldContent, note)
                }
                if (s.isDelete) {
                    runCatching { trashStore.backup(s.target, note) }
                } else {
                    runCatching { trashStore.backupBeforeOverwrite(s.target, s.tmp.readText(), note) }
                }
            }
        }

        // ── 阶段 2：commit（逐个 rename，失败则回滚已改的）──────────
        val committed = mutableListOf<Staged>()
        try {
            for (s in staged) {
                ctx.checkCancelled()
                if (s.isDelete) {
                    if (s.target.exists() && !s.target.delete()) {
                        throw IllegalStateException("删除失败：${s.target.absolutePath}")
                    }
                } else {
                    val ok = s.tmp.renameTo(s.target) || run {
                        // renameTo 在某些情况（目标存在）会失败，删掉再试
                        if (s.target.exists() && s.target.delete()) s.tmp.renameTo(s.target) else false
                    }
                    if (!ok) throw IllegalStateException("写入失败：${s.target.absolutePath}")
                    FileVersionTracker.track(s.target)
                }
                committed += s
            }
        } catch (e: Throwable) {
            // commit 中途失败：用 oldContent 回滚已 rename 的
            committed.forEach { s ->
                runCatching {
                    if (s.isDelete) {
                        s.oldContent?.let { s.target.writeText(it) }
                    } else {
                        s.oldContent?.let { s.target.writeText(it) }
                            ?: s.target.delete()   // 原本不存在 → 删掉刚创建的文件
                    }
                }
            }
            staged.filter { it !in committed }.forEach { runCatching { if (!it.isDelete) it.tmp.delete() } }
            return ToolResult.Error("ApplyPatch 提交阶段失败，已回滚：${e.message}", ToolResult.INTERNAL)
        }

        return ToolResult.ok("ApplyPatch 完成：${applied.joinToString("；")}\n已写 ${committed.size} 个文件")
    }
}
