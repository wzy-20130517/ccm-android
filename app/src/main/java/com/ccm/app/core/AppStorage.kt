package com.ccm.app.core

import java.io.File

/**
 * 应用私有存储的抽象。
 *
 * 【为什么是接口 + 纯 JVM 实现】
 * core 层有一条硬约束：**零 Android 依赖**，这样整套业务逻辑能在 JUnit 里直接跑
 * （CCM 那些血泪 bug 全都要写成回归测试，见 recon-a §5.3）。
 * 而存储需要 `Context.filesDir` —— 所以把「取根目录」这件事留在外面：
 * 调用方传一个 `File` 进来，本层只认 `File`。
 *
 * 目录布局对齐 CCM 的 `~/.claude-code-mobile/`：
 * ```
 * <root>/
 * ├── sessions/      会话存档（*.json）
 * ├── undo/          撤销快照
 * ├── trash/         回收站（大改动前的旧版本）
 * ├── traces/        运行 trace（*.jsonl）
 * ├── tool-output/   工具大输出落盘
 * ├── compact-trash/ 压缩前的完整会话备份
 * └── config.json    Provider 等配置
 * ```
 */
interface AppStorage {
    /** 应用私有根目录（Android 上通常是 `context.filesDir`）。 */
    val root: File

    val sessionsDir: File
    val undoDir: File
    val trashDir: File
    val tracesDir: File
    val toolOutputDir: File
    val compactTrashDir: File

    /** 配置文件路径（config.json）。 */
    val configFile: File

    /** 把相对路径解析成绝对文件（不做存在性检查）。 */
    fun resolve(relative: String): File = File(root, relative)

    /** 确保所有子目录存在。幂等，重复调用无副作用。 */
    fun ensureDirs() {
        listOf(sessionsDir, undoDir, trashDir, tracesDir, toolOutputDir, compactTrashDir)
            .forEach { it.mkdirs() }
    }
}

/**
 * 默认实现 —— 纯 `java.io.File`，可在 JVM 单测里指向任意临时目录。
 */
class FileAppStorage(override val root: File) : AppStorage {
    override val sessionsDir: File get() = File(root, "sessions")
    override val undoDir: File get() = File(root, "undo")
    override val trashDir: File get() = File(root, "trash")
    override val tracesDir: File get() = File(root, "traces")
    override val toolOutputDir: File get() = File(root, "tool-output")
    override val compactTrashDir: File get() = File(root, "compact-trash")
    override val configFile: File get() = File(root, "config.json")
}
