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

    /**
     * 确保根目录与所有子目录存在。幂等，重复调用无副作用。
     *
     * 【2026-10-11 加 root.mkdirs()】数据根改到 `Download/Claude/` 后，
     * 根目录**不一定存在**（新装用户第一次进来时没有）。
     * 虽然 `File(root, "sessions").mkdirs()` 会连带建父目录，
     * 但**只调 `resolve()` 不调 ensureDirs 的路径**（如读 config.json）
     * 会失败 —— 显式建一次更稳。
     */
    fun ensureDirs() {
        // 先建根（Download/Claude 可能不存在）
        try { if (!root.exists()) root.mkdirs() } catch (_: Throwable) {}
        listOf(sessionsDir, undoDir, trashDir, tracesDir, toolOutputDir, compactTrashDir)
            .forEach { it.mkdirs() }
    }
}

/**
 * 解析 App 的**数据根目录**。
 *
 * ## 设计（2026-10-11 定）
 *
 * 数据根 = **`Download/Claude/`**（外部存储），**不是** App 私有目录。
 *
 * 这样做的理由：
 *   · 数据对用户可见（会话、终端环境、配置都能在文件管理器里看到/备份）
 *   · proot 容器与 Termux 通道**都能访问**（`/sdcard` 是唯一两边都通的空间）
 *   · 换机/重装 App 数据不丢
 *
 * ## 硬要求：必须授予「所有文件访问」
 *
 * **不做回落**。没授权时 App 无法工作（会话存不了、环境装不了），
 * 引导页会拦住用户要求授权。回落只会让用户以为能用、实际到处报错。
 *
 * @param context Android Context
 * @return `Download/Claude` 的 File（不保证存在，调用方 ensureDirs）
 */
fun resolveDataRoot(context: android.content.Context): File {
    // 【2026-10-11 学 Operit 的做法，不写死 /sdcard】
    //
    // Operit（同生态的 Android AI Agent）的 OperitPaths.kt：
    //   fun downloadsDir() = Environment.getExternalStoragePublicDirectory(
    //       Environment.DIRECTORY_DOWNLOADS)
    //   fun operitRootDir() = File(downloadsDir(), "Operit")
    //   fun workspaceDir() = File(operitRootDir(), "workspace")
    //
    // 关键点：**用系统 API 拿 Download 目录**，不硬编码 `/sdcard/...` ——
    // 不同设备/多用户/工作资料下路径不同（CLI 的 workspace.mjs 注释里
    // 也记着这条：「旧版把路径写死在常量里，换台设备就是错的」）。
    //
    // CCM 的结构（对齐 Operit）：
    //   Download/Claude/             ← 数据根（会话、配置、日志）
    //   Download/Claude/workspace/   ← 工作区（Agent 干活的默认目录）
    val downloads = android.os.Environment.getExternalStoragePublicDirectory(
        android.os.Environment.DIRECTORY_DOWNLOADS,
    )
    return File(downloads, "Claude")
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
