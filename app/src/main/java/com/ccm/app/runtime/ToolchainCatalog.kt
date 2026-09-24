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

    /**
     * Node.js 版本。
     *
     * 【为什么固定版本号而不是「latest」】
     * 可重现。用户装出来的东西应该跟你测试过的一致 ——
     * 用 latest 的话今天装的和下个月装的可能是两个大版本，
     * 出问题时无法复现。升级是一个显式动作（改这一行）。
     *
     * v24 是当前 LTS（代号 Krypton），2026-09-07 发布。
     */
    const val NODE_VERSION = "v24.21.0"

    /**
     * Node 官方 tarball 地址。
     *
     * 【镜像策略】官方优先，国内慢就切 npmmirror（淘宝维护，内容一致，CDN 在国内）。
     *
     * 【为什么用 .tar.xz 而不是 .tar.gz】
     * xz 是 29MB，gz 是 55MB —— 差 26MB，手机流量下不是小数目。
     * Android 没内置 xz 解压器，所以 app/build.gradle.kts 里加了
     * org.tukaani:xz（纯 Java，165KB）。TarExtractor 按魔数自动识别格式。
     */
    val NODE_MIRRORS = listOf(
        "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz",
        "https://registry.npmmirror.com/-/binary/node/$NODE_VERSION/node-$NODE_VERSION-linux-arm64.tar.xz",
    )

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
        /**
         * 自定义安装脚本（shell）。非空时**替代** aptPackages，不再走 apt install。
         *
         * 【为什么需要这个】
         * 有些东西 apt 装不到想要的版本：
         *   · Node.js —— noble 源里是 18.19.1（2025-04 已 EOL），
         *     而 AI 内核大量使用 AbortSignal.timeout（Node 17.3+）等较新 API，
         *     18 能跑但不是长久之计，安全更新也停了。
         *     官方做法是加 NodeSource 源，但那需要 rootfs 里先有 curl + gnupg ——
         *     用户如果没勾「基础工具」就会失败（鸡生蛋）。
         *   · 有些工具只有官方安装脚本（如 rustup、uv）
         *
         * 【为什么不用 apt 的「添加第三方源」方式】
         * 见上：依赖 curl/gnupg。而我们的安装流程是「App 侧下载好 → 塞进 rootfs」，
         * 不依赖 rootfs 里有没有下载工具。更稳。
         */
        val customScript: String? = null,
        /** 自定义脚本的下载步骤（App 侧执行，见 RootfsManager.installToolchains） */
        val downloadSteps: List<DownloadStep> = emptyList(),
        /**
         * 验证命令：装完后跑它，退出码 0 = 真的装上了。
         *
         * 【为什么不能只靠 /root/.ccm-toolchains 记录】
         * 那是个纯文本记录，写进去了不代表东西真在。可能的情况：
         *   · 安装中途失败但记录已经写了（早期版本有这个问题）
         *   · 用户手动删了 /usr/local/bin/node
         *   · rootfs 被部分损坏
         * 只读记录的后果：界面显示「已安装」，用户点安装却不装，
         * 然后来问「为什么勾了没反应」。
         *
         * 这个字段从 OperitTerminalCore 学来：它的 checkPackageInstalled()
         * 对每个包用定制命令实测（command -v / dpkg -s / node -v），
         * 而不是查它自己的记录文件。
         */
        val verifyCommand: String? = null,
    )

    /**
     * 一个「App 侧下载 → 写进 rootfs」的步骤。
     *
     * 用途：避开「rootfs 里还没装 curl」的鸡生蛋问题。
     * App 用 HttpURLConnection 下载，直接解压/落地到 rootfs 对应位置。
     */
    data class DownloadStep(
        val url: String,
        /** 解压到 rootfs 的哪个相对路径（如 "usr/local"） */
        val extractTo: String,
        /** 解压后要去掉的顶层目录名（tarball 通常有个 node-v24.x-linux-arm64 前缀） */
        val stripComponents: Int = 1,
        /** 人类可读的说明，显示在日志里 */
        val label: String,
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
            verifyCommand = "command -v git curl wget",
            defaultChecked = true,
        ),

        // ═══ 语言运行时 ═══
        Toolchain(
            id = "nodejs",
            name = "Node.js 24",
            description = "AI 内核自己要用（官方 LTS，约 200MB）",
            aptPackages = emptyList(),
            // 【2026-09-24 修正】原来写 50MB，实际差 4.6 倍。
            // 实测 Node v24 官方 tarball：
            //   压缩后 30MB（.tar.xz）
            //   解压后 ~200MB（单个 node 二进制就 122MB，带 debug_info 没 strip）
            // 安装峰值 = 30（压缩）+ 200（解压）= 230MB。
            // 低估的后果：用户看着「50MB」点下去，结果吃了 230MB 存储 ——
            // 存储紧张的手机会中途失败（解压到一半 no space left）。
            sizeMB = 230,
            verifyCommand = "command -v node npm",
            defaultChecked = true,
            // 【为什么不用 apt 的 nodejs】
            // noble 源里是 18.19.1 —— 2025-04 已 EOL，安全更新停止。
            // 官方做法（NodeSource）需要 rootfs 先有 curl + gnupg，会鸡生蛋。
            // 这里改成 App 侧直接下官方 tarball，解压到 /usr/local（在 PATH 里，
            // 且优先于 /usr/bin 的 apt 版）。
            downloadSteps = listOf(
                DownloadStep(
                    url = NODE_MIRRORS.first(),
                    extractTo = "usr/local",
                    stripComponents = 1,
                    label = "Node.js ${NODE_VERSION} (官方 tarball)",
                ),
            ),
        ),
        Toolchain(
            id = "python",
            name = "Python 3",
            description = "python3 + pip + venv，跑脚本、数据处理、爬虫",
            aptPackages = listOf("python3", "python3-pip", "python3-venv", "python3-dev"),
            sizeMB = 120,
            verifyCommand = "command -v python3 pip3",
            defaultChecked = true,
        ),
        Toolchain(
            id = "php",
            name = "PHP",
            description = "php-cli，跑 PHP 脚本",
            aptPackages = listOf("php-cli", "php-mbstring", "php-curl", "php-xml"),
            sizeMB = 60,
            verifyCommand = "command -v php",
            defaultChecked = false,
        ),
        Toolchain(
            id = "rust",
            name = "Rust",
            description = "rustc + cargo，编译 Rust 项目（较大）",
            aptPackages = listOf("rustc", "cargo"),
            sizeMB = 400,
            verifyCommand = "command -v rustc cargo",
            defaultChecked = false,
        ),
        Toolchain(
            id = "go",
            name = "Go",
            description = "golang-go，编译 Go 项目",
            aptPackages = listOf("golang-go"),
            // 实测 golang-go + 依赖约 250MB，留余量
            sizeMB = 280,
            verifyCommand = "command -v go",
            defaultChecked = false,
        ),
        Toolchain(
            id = "java",
            name = "Java (JDK)",
            description = "default-jdk-headless，编译/运行 Java",
            aptPackages = listOf("default-jdk-headless"),
            // openjdk-headless + 依赖约 300MB，留余量
            sizeMB = 320,
            verifyCommand = "command -v java",
            defaultChecked = false,
        ),

        // ═══ 编译工具 ═══
        Toolchain(
            id = "build",
            name = "编译工具链",
            description = "gcc / g++ / make —— 编译 C/C++ 项目、装需要编译的 pip 包",
            aptPackages = listOf("gcc", "g++", "make", "pkg-config"),
            sizeMB = 250,
            verifyCommand = "command -v gcc make",
            defaultChecked = true,
        ),
        Toolchain(
            id = "cmake",
            name = "CMake",
            description = "cmake，构建复杂 C/C++ 项目",
            aptPackages = listOf("cmake"),
            sizeMB = 120,
            verifyCommand = "command -v cmake",
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
            verifyCommand = "command -v ssh",
            defaultChecked = true,
        ),
        Toolchain(
            id = "sshd",
            name = "SSH 服务端",
            description = "openssh-server —— 让别的设备连进来（需手动启动）",
            aptPackages = listOf("openssh-server"),
            sizeMB = 30,
            verifyCommand = "command -v sshd",
            defaultChecked = false,
        ),

        // ═══ 命令行工具 ═══
        Toolchain(
            id = "cli-tools",
            name = "命令行增强",
            description = "ripgrep / fd / jq / tree —— 搜索与 JSON 处理，AI 常用",
            aptPackages = listOf("ripgrep", "fd-find", "jq", "tree", "file", "fzf"),
            sizeMB = 32,
            // ⚠️ 两个容易错的点：
            //   ① fd-find 装出来的命令是 `fdfind` 不是 `fd` ——
            //      Debian/Ubuntu 为避免与旧的 fd 命令冲突改了名。
            //      所以验证用 fdfind。（很多教程直接写 fd，会验证失败）
            //   ② 原来验证命令里查了 fzf，但 aptPackages 里根本没装 fzf ——
            //      这会让 verifyInstalledToolchains 永远判定「未安装」，
            //      用户重装也修不好（装的东西和验证的东西不匹配）。
            //      现在把 fzf 加进 aptPackages，两边对齐。
            // ⚠️ 命令名和包名不一致的三个：
            //   ripgrep  → 命令是 `rg`（不是 ripgrep！）
            //   fd-find  → 命令是 `fdfind`（不是 fd！）
            //   fzf      → 命令是 `fzf` ✅
            // 写错的话验证永远失败，用户重装也修不好。
            verifyCommand = "command -v jq tree rg fzf fdfind",
            defaultChecked = true,
        ),
        Toolchain(
            id = "editors",
            name = "终端编辑器",
            description = "vim / nano —— 手动改文件时用",
            aptPackages = listOf("vim", "nano"),
            sizeMB = 30,
            verifyCommand = "command -v vim nano",
            defaultChecked = false,
        ),
        Toolchain(
            id = "tmux",
            name = "tmux",
            description = "终端复用，长任务放后台不被断",
            aptPackages = listOf("tmux"),
            sizeMB = 10,
            verifyCommand = "command -v tmux",
            defaultChecked = true,
        ),
        Toolchain(
            id = "htop",
            name = "htop",
            description = "进程/资源监控",
            aptPackages = listOf("htop", "procps"),
            sizeMB = 10,
            verifyCommand = "command -v htop",
            defaultChecked = false,
        ),

        // ═══ 媒体处理 ═══
        Toolchain(
            id = "ffmpeg",
            name = "FFmpeg",
            description = "音视频处理（转码、抽帧、合成）",
            aptPackages = listOf("ffmpeg"),
            sizeMB = 150,
            verifyCommand = "command -v ffmpeg ffprobe",
            defaultChecked = true,
        ),
        Toolchain(
            id = "imagemagick",
            name = "ImageMagick",
            description = "convert / identify —— 图片处理",
            aptPackages = listOf("imagemagick"),
            sizeMB = 80,
            verifyCommand = "command -v convert identify",
            defaultChecked = false,
        ),

        // ═══ 数据库客户端 ═══
        Toolchain(
            id = "db-clients",
            name = "数据库客户端",
            description = "sqlite3 / redis-tools —— 连数据库、操作本地库",
            // ⚠️ 验证命令里原来还查了 mysql 和 psql —— 那需要
            // default-mysql-client 和 postgresql-client，而这两个没装。
            // 后果：verifyInstalledToolchains 永远判定「未安装」，
            // 用户重装也修不好（装的东西和验证的东西不匹配）。
            //
            // 选择：把 mysql/psql 也装上（体积 +60MB），还是从验证里去掉？
            // 决定：去掉。理由 —— 这个条目的描述就是「连数据库、操作本地库」，
            // 主要用途是本地 sqlite + redis 调试；真需要连 MySQL/PG 的用户
            // 会自己 apt install。不为了「验证命令看起来全」而多装 60MB。
            aptPackages = listOf("sqlite3", "redis-tools"),
            sizeMB = 40,
            verifyCommand = "command -v sqlite3 redis-cli",
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
