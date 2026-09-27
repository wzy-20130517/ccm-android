package com.ccm.app.core.tool

import com.ccm.app.core.AppStorage
import java.io.File

/**
 * 工具侧的应用存储入口。
 *
 * ## 为什么不直接用 [AppStorage]
 * [AppStorage] 是 core 层的通用存储抽象（会话、回收站、trace…），
 * 而工具需要的是一份**能直接用的 File 对象** —— 工具是 dev-tools 写的，
 * 不该要求它们关心 `AppStorage` 接口的演进。
 * 这里做一个窄接口，把工具真正要用的四类目录暴露出来。
 *
 * ## 与 Node 版的对应
 * Node 版工具通过 `homedir() + '.claude-code-mobile/xxx'` 直接拼路径。
 * Kotlin 版不能这么干（Android 没有 `homedir()`），所以由调用方注入根目录。
 *
 * ## 可空性
 * [ToolContext.storage] 是可空的 —— 纯计算工具（如 JSON 格式化）不需要存储，
 * 单测里也常常不注入。**需要存储的工具必须处理 null**，
 * 不能假定它一定存在（否则单测会崩）。
 */
interface ToolStorage {

    /** 应用私有存储根目录。 */
    val rootDir: File

    /** 回收站 —— 大改动前的旧版本备份。 */
    val trashDir: File

    /** 撤销快照 —— `/undo` 用。 */
    val undoDir: File

    /** 工具大输出落盘目录（超过 maxResultSizeChars 的结果写这里）。 */
    val outputDir: File

    /** 会话存档目录。 */
    val sessionDir: File

    /**
     * 确保所有子目录存在。幂等。
     *
     * 工具在写文件前调一次即可 —— 不要假定目录已存在
     * （App 首次启动时可能还没建）。
     */
    fun ensureDirs() {
        listOf(rootDir, trashDir, undoDir, outputDir, sessionDir).forEach { it.mkdirs() }
    }

    /** 便捷：在 [outputDir] 下建一个文件（不创建）。 */
    fun outputFile(name: String): File = File(outputDir, name)

    /** 便捷：在 [trashDir] 下建一个文件（不创建）。 */
    fun trashFile(name: String): File = File(trashDir, name)
}

/**
 * [ToolStorage] 的默认实现 —— 桥接 [AppStorage]。
 *
 * 这样 core 层只需要维护一份目录定义（[AppStorage]），工具侧拿到的是一份
 * 视图，两边不会漂移。
 */
class AppBackedToolStorage(private val app: AppStorage) : ToolStorage {
    override val rootDir: File get() = app.root
    override val trashDir: File get() = app.trashDir
    override val undoDir: File get() = app.undoDir
    override val outputDir: File get() = app.toolOutputDir
    override val sessionDir: File get() = app.sessionsDir
}

/**
 * 纯内存 / 临时目录实现 —— 单测与无 Context 场景用。
 *
 * @param root 任意可写目录（测试里通常是 `createTempDirectory()`）
 */
class FileToolStorage(override val rootDir: File) : ToolStorage {
    override val trashDir: File get() = File(rootDir, "trash")
    override val undoDir: File get() = File(rootDir, "undo")
    override val outputDir: File get() = File(rootDir, "tool-output")
    override val sessionDir: File get() = File(rootDir, "sessions")
}
