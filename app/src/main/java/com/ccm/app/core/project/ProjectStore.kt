package com.ccm.app.core.project

import com.ccm.app.core.AppStorage
import java.io.File

/**
 * 项目 = workspace 下的一个子目录（2026-10-01 落地）。
 *
 * ## 为什么这么做
 * Web 的项目走独立后端（/api/projects + 数据库），APK 没有后端。
 * 但「项目」对用户的价值很朴素：**把相关对话和文件放一起**。
 * workspace 子目录就是最直接的映射：
 *   - 新建项目 = mkdir <workspace>/<名字>
 *   - 打开项目 = 把该目录设为新会话的工作目录（模型 Read/Write 的基准）
 *   - 项目列表 = workspace 的子目录扫描（排除隐藏目录）
 *
 * ## 与 Web 的差距
 * Web 有「项目说明（instructions）」「项目内对话列表」等元数据；
 * 这里先用目录本身承载 —— 说明可以放 `README.md`（模型可读），
 * 对话归属按「会话创建时选的 cwd」约定，暂不做反向索引。
 */
class ProjectStore(private val storage: AppStorage) {

    /** workspace 根目录（不存在则建）。 */
    val workspaceRoot: File
        get() = File(storage.root, "workspace").apply { mkdirs() }

    data class Project(
        val id: String,
        val name: String,
        val path: String,
        val fileCount: Int,
    )

    /** 列出全部项目（workspace 下的可见子目录）。 */
    fun list(): List<Project> = try {
        workspaceRoot.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith('.') }
            ?.map { dir ->
                Project(
                    id = dir.name,
                    name = dir.name,
                    path = dir.absolutePath,
                    fileCount = (dir.listFiles()?.size ?: 0),
                )
            }
            ?.sortedByDescending { it.fileCount }
            ?: emptyList()
    } catch (_: Throwable) { emptyList() }

    /** 新建项目（目录名做校验：不能含路径分隔符）。 */
    fun create(name: String): Result<Project> {
        val n = name.trim()
        if (n.isEmpty()) return Result.failure(IllegalArgumentException("名字不能为空"))
        if (n.contains('/') || n.contains('\\') || n.startsWith('.')) {
            return Result.failure(IllegalArgumentException("名字不能含 / 或 \\，不能以 . 开头"))
        }
        val dir = File(workspaceRoot, n)
        if (dir.exists()) return Result.failure(IllegalArgumentException("已存在同名项目"))
        return try {
            if (!dir.mkdirs()) return Result.failure(IllegalStateException("创建目录失败"))
            Result.success(Project(id = n, name = n, path = dir.absolutePath, fileCount = 0))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    /** 删除项目（仅删空目录 / 或连内容删 —— 这里只删目录本身，非空则拒绝）。 */
    fun delete(name: String): Result<Unit> {
        val dir = File(workspaceRoot, name)
        if (!dir.exists()) return Result.failure(IllegalArgumentException("项目不存在"))
        val ok = try { dir.delete() } catch (_: Throwable) { false }
        return if (ok) Result.success(Unit)
        else Result.failure(IllegalStateException("目录非空或无法删除（先清空内容）"))
    }
}
