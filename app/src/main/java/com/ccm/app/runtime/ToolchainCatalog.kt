package com.ccm.app.runtime

/**
 * 可安装的工具链清单。
 *
 * 【设计】
 * rootfs 只带最基础的 Ubuntu（28MB），用户按需勾选要装什么。
 * 每个条目是一个「组」（含若干 apt 包），组之间有依赖关系。
 *
 * 【为什么分组而不是单包】
 * 用户想的是「我要 Python」而不是「我要 python3 + python3-pip + python3-venv」。
 * 组是用户视角的单位，apt 包是实现细节。
 *
 * 【大小是估算】
 * Installed-Size 是 apt 报的安装后占用（KB），实际还要算依赖。
 * 这里给的是「装完大约占多少」的量级，用于让用户有预期。
 */
object ToolchainCatalog {

    /** 单个工具链组 */
    data class Toolchain(
        val id: String,
        val name: String,
        val description: String,
        val aptPackages: List<String>,
        /** 估算大小（MB），含依赖 */
        val sizeMB: Int,
        /** 默认勾选？ */
        val defaultChecked: Boolean = false,
        /** 依赖的其他工具链 id */
        val dependsOn: List<String> = emptyList(),
    )

    /**
     * 全部可选项。
     *
     * 顺序按「用户可能需要的概率」排：基础 → 语言 → 工具 → 服务。
     */
    val ALL: List<Toolchain> = listOf(
        // ═══ 基础（默认必装，不可取消）═══
        Toolchain(
            id = "base",
            name = "基础工具",
            description = "git / curl / wget / ca-certificates —— AI 干活的最低配置",
            aptPackages = listOf("git", "curl", "wget", "ca-certificates", "less", "unzip", "xz-utils"),
            sizeMB = 60,
            defaultChecked = true,
        ),

        // ═══ 语言运行时 ═══
        Toolchain(
            id = "nodejs",
            name = "Node.js",
            description = "AI 内核自己要用，无法取消",
            aptPackages = listOf("nodejs"),
            sizeMB = 40,
            defaultChecked = true,
        ),
        Toolchain(
            id = "python",
            name = "Python 3",
            description = "python3 + pip + venv，跑脚本、数据处理、爬虫",
            aptPackages = listOf("python3", "python3-pip", "python3-venv", "python3-dev"),
            sizeMB = 120,
            defaultChecked = true,
        ),
        Toolchain(
            id = "php",
            name = "PHP",
            description = "php-cli，跑 PHP 脚本",
            aptPackages = listOf("php-cli", "php-mbstring", "php-curl", "php-xml"),
            sizeMB = 60,
            defaultChecked = false,
        ),
        Toolchain(
            id = "rust",
            name = "Rust",
            description = "rustc + cargo，编译 Rust 项目（较大）",
            aptPackages = listOf("rustc", "cargo"),
            sizeMB = 400,
            defaultChecked = false,
        ),
        Toolchain(
            id = "go",
            name = "Go",
            description = "golang-go，编译 Go 项目",
            aptPackages = listOf("golang-go"),
            sizeMB = 200,
            defaultChecked = false,
        ),
        Toolchain(
            id = "java",
            name = "Java (JDK)",
            description = "default-jdk-headless，编译/运行 Java",
            aptPackages = listOf("default-jdk-headless"),
            sizeMB = 250,
            defaultChecked = false,
        ),

        // ═══ 编译工具 ═══
        Toolchain(
            id = "build",
            name = "编译工具链",
            description = "gcc / g++ / make —— 编译 C/C++ 项目、装需要编译的 pip 包",
            aptPackages = listOf("gcc", "g++", "make", "pkg-config"),
            sizeMB = 250,
            defaultChecked = true,
        ),
        Toolchain(
            id = "cmake",
            name = "CMake",
            description = "cmake，构建复杂 C/C++ 项目",
            aptPackages = listOf("cmake"),
            sizeMB = 120,
            defaultChecked = false,
            dependsOn = listOf("build"),
        ),

        // ═══ 网络 / 远程 ═══
        Toolchain(
            id = "ssh",
            name = "SSH 客户端",
            description = "openssh-client —— 连远程服务器、git over ssh",
            aptPackages = listOf("openssh-client"),
            sizeMB = 30,
            defaultChecked = true,
        ),
        Toolchain(
            id = "sshd",
            name = "SSH 服务端",
            description = "openssh-server —— 让别的设备连进来（需手动启动）",
            aptPackages = listOf("openssh-server"),
            sizeMB = 30,
            defaultChecked = false,
        ),

        // ═══ 命令行工具 ═══
        Toolchain(
            id = "cli-tools",
            name = "命令行增强",
            description = "ripgrep / fd / jq / tree —— 搜索与 JSON 处理，AI 常用",
            aptPackages = listOf("ripgrep", "fd-find", "jq", "tree", "file"),
            sizeMB = 30,
            defaultChecked = true,
        ),
        Toolchain(
            id = "editors",
            name = "终端编辑器",
            description = "vim / nano —— 手动改文件时用",
            aptPackages = listOf("vim", "nano"),
            sizeMB = 30,
            defaultChecked = false,
        ),
        Toolchain(
            id = "tmux",
            name = "tmux",
            description = "终端复用，长任务放后台不被断",
            aptPackages = listOf("tmux"),
            sizeMB = 10,
            defaultChecked = true,
        ),
        Toolchain(
            id = "htop",
            name = "htop",
            description = "进程/资源监控",
            aptPackages = listOf("htop", "procps"),
            sizeMB = 10,
            defaultChecked = false,
        ),

        // ═══ 媒体处理 ═══
        Toolchain(
            id = "ffmpeg",
            name = "FFmpeg",
            description = "音视频处理（转码、抽帧、合成）",
            aptPackages = listOf("ffmpeg"),
            sizeMB = 150,
            defaultChecked = true,
        ),
        Toolchain(
            id = "imagemagick",
            name = "ImageMagick",
            description = "convert / identify —— 图片处理",
            aptPackages = listOf("imagemagick"),
            sizeMB = 80,
            defaultChecked = false,
        ),

        // ═══ 数据库客户端 ═══
        Toolchain(
            id = "db-clients",
            name = "数据库客户端",
            description = "sqlite3 / redis-tools —— 连数据库、操作本地库",
            aptPackages = listOf("sqlite3", "redis-tools"),
            sizeMB = 40,
            defaultChecked = false,
        ),
    )

    /** 按 id 查 */
    fun byId(id: String): Toolchain? = ALL.find { it.id == id }

    /** 默认勾选的 id 集合 */
    fun defaultSelection(): Set<String> = ALL.filter { it.defaultChecked }.map { it.id }.toSet()

    /**
     * 解析依赖：给定用户勾选的集合，补齐所有依赖。
     * 返回按依赖顺序排好的列表（被依赖的在前）。
     */
    fun resolveSelection(selected: Set<String>): List<Toolchain> {
        val result = mutableListOf<Toolchain>()
        val visited = mutableSetOf<String>()

        fun add(id: String) {
            if (id in visited) return
            visited.add(id)
            val tc = byId(id) ?: return
            // 先加依赖
            tc.dependsOn.forEach { add(it) }
            result.add(tc)
        }

        // 按 ALL 的顺序处理，保证稳定输出
        ALL.forEach { if (it.id in selected) add(it.id) }
        return result
    }

    /** 把所有勾选项的 apt 包合并成一条 apt install 命令的参数 */
    fun aptPackagesFor(selected: Set<String>): List<String> {
        return resolveSelection(selected)
            .flatMap { it.aptPackages }
            .distinct()
    }

    /** 估算总大小（MB） */
    fun estimatedSizeMB(selected: Set<String>): Int {
        return resolveSelection(selected).sumOf { it.sizeMB }
    }
}
