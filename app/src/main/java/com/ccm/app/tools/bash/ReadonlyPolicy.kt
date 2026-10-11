package com.ccm.app.tools.bash

/**
 * Bash 只读策略表 + 判定逻辑 —— 移植自 ZCode（Apache-2.0）。
 *
 * ══════════════════════════════════════════════════════════════
 *  来源与移植关系
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 *   ZCode（原版 TS）
 *     └─→ CLI 侧移植（~/claude-code-mobile/core/permission/bash-ast/policy/，20 文件 3090 行）
 *           └─→ 本文件（APK 侧 Kotlin 移植，2026-10-11）
 * ```
 *
 * 策略数据（命令表 / git 子命令表 / 多词命令表 / 46 条任意参数命令 / 1882 条安全 flag）
 * **由脚本从 CLI 侧 dump 生成**，不是手抄 —— 手抄 1882 条 flag 必然出错，
 * 且两边漂移后无法发现。生成脚本见交付简报。
 *
 * ══════════════════════════════════════════════════════════════
 *  三态语义（关键，来自 ZCode 源设计）
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 *   true      —— 确认只读
 *   false     —— 确认非只读（或明确危险）
 *   null      —— 无法判定（未知命令、命令在表里但参数没覆盖）
 * ```
 *
 * null 必须保守当「非只读」处理。调用方统一用 `=== true`（Kotlin 里 `== true`）
 * 把三态收敛成布尔，**不要**用 `!= false` —— 那会把所有未知命令放行。
 *
 * ══════════════════════════════════════════════════════════════
 *  flag 取值形态（SafeFlagValue）
 * ══════════════════════════════════════════════════════════════
 *
 * ```
 *   none           纯开关，不接值            -l
 *   string         必须接一个字符串值        -e 'pattern'
 *   optionalString 可接可不接                -o
 *   number         必须接数字                -n 5
 *   char           必须接单个字符
 *   {}             值必须字面等于 {}
 *   EOF            值必须字面等于 EOF
 * ```
 *
 * 表里只写**安全**的 flag。不在表里的 flag 一律判不安全（保守）。
 */
object ReadonlyPolicy {

    // ══════════════════════════════════════════════════════════════
    //  数据结构
    // ══════════════════════════════════════════════════════════════

    /** flag 取值形态 */
    enum class SafeFlagValue {
        NONE, STRING, OPTIONAL_STRING, NUMBER, CHAR, BRACES, EOF
    }

    /** 单条只读策略 */
    class Policy(
        /** 允许任意参数（命令本身只读，参数不影响） */
        val allowAnyArgs: Boolean = false,
        /** 仅命令本身，不接受任何参数 */
        val commandOnly: Boolean = false,
        /**
         * 允许紧凑数字计数 flag，如 `head -20` / `tail -5`。
         * 必须策略显式声明（对齐 CLI 的 `allowCompactNumericCountFlag`）。
         */
        val allowCompactNumericCountFlag: Boolean = false,
        /**
         * `--` 之后的参数是否继续按 flag 解析。
         * 默认 true（`--` 之后全是位置参数）；false 表示忽略 `--` 的特殊性。
         * 对齐 CLI 的 `respectsDoubleDash`。
         */
        val respectsDoubleDash: Boolean = true,
        /**
         * 整条命令文本必须匹配此正则才算通过（对齐 CLI 的 `regex` 字段）。
         * 例：hostname 要求 `^hostname(?:\s+(?:-[a-zA-Z]|--[a-zA-Z-]+))*\s*$`。
         */
        val regex: String? = null,
        /**
         * 安全 flag 表（JSON 字符串，懒解析）。
         *
         * 【为什么用 JSON 字符串而不是直接写 mapOf】
         * 1882 条 flag 若展开成 Kotlin `mapOf("a" to SafeFlagValue.STRING, ...)`，
         * 单个方法体会超过 JVM 的 64KB 字节码限制，且编译期内存占用高。
         * JSON 字符串是常量池里的一个字符串，运行时解析一次后缓存。
         */
        val safeFlagsJson: String? = null,
        /** 额外危险判定回调：返回 true 表示危险（即使 flag 表通过也拒绝） */
        val callback: ((List<String>) -> Boolean)? = null,
    ) {
        /** 解析后的 flag 表（懒加载 + 缓存） */
        val safeFlags: Map<String, SafeFlagValue> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            parseFlags(safeFlagsJson)
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  策略表（脚本生成）
    // ══════════════════════════════════════════════════════════════

    // ══════════ 策略表（脚本从 CLI 侧生成，勿手改）══════════
    private val COMMAND_POLICIES: Map<String, Policy> = buildMap {
        put("alias", Policy(commandOnly = true))
        put("base64", Policy(respectsDoubleDash = false, safeFlagsJson = "{\"-D\":\"none\",\"-b\":\"number\",\"-d\":\"none\",\"-h\":\"none\",\"-i\":\"string\",\"-w\":\"number\",\"--break\":\"number\",\"--decode\":\"none\",\"--help\":\"none\",\"--ignore-garbage\":\"none\",\"--input\":\"string\",\"--version\":\"none\",\"--wrap\":\"number\"}"))
        put("cat", Policy(safeFlagsJson = "{\"-A\":\"none\",\"-E\":\"none\",\"-T\":\"none\",\"-b\":\"none\",\"-e\":\"none\",\"-n\":\"none\",\"-s\":\"none\",\"-t\":\"none\",\"-u\":\"none\",\"-v\":\"none\",\"--number\":\"none\",\"--number-nonblank\":\"none\",\"--show-all\":\"none\",\"--show-ends\":\"none\",\"--show-nonprinting\":\"none\",\"--show-tabs\":\"none\",\"--squeeze-blank\":\"none\"}"))
        put("cd", Policy(allowAnyArgs = true))
        put("cut", Policy(allowAnyArgs = true))
        put("date", Policy(safeFlagsJson = "{\"-I\":\"none\",\"-R\":\"none\",\"-d\":\"string\",\"-r\":\"string\",\"-u\":\"none\",\"--date\":\"string\",\"--debug\":\"none\",\"--help\":\"none\",\"--iso-8601\":\"string\",\"--reference\":\"string\",\"--rfc-3339\":\"string\",\"--rfc-email\":\"none\",\"--universal\":\"none\",\"--utc\":\"none\",\"--version\":\"none\"}", callback = Callbacks::dateCommandIsDangerous))
        put("diff", Policy(allowAnyArgs = true))
        put("dirname", Policy(allowAnyArgs = true))
        put("du", Policy(allowAnyArgs = true))
        put("echo", Policy(allowAnyArgs = true))
        put("egrep", Policy(safeFlagsJson = "{\"-A\":\"number\",\"-B\":\"number\",\"-C\":\"number\",\"-E\":\"none\",\"-F\":\"none\",\"-G\":\"none\",\"-H\":\"none\",\"-I\":\"none\",\"-L\":\"none\",\"-P\":\"none\",\"-R\":\"none\",\"-a\":\"none\",\"-b\":\"none\",\"-c\":\"none\",\"-D\":\"string\",\"-d\":\"string\",\"-e\":\"string\",\"-f\":\"string\",\"-h\":\"none\",\"-i\":\"none\",\"-l\":\"none\",\"-m\":\"number\",\"-n\":\"none\",\"-o\":\"none\",\"-q\":\"none\",\"-r\":\"none\",\"-s\":\"none\",\"-T\":\"none\",\"-u\":\"none\",\"-U\":\"none\",\"-v\":\"none\",\"-V\":\"none\",\"-w\":\"none\",\"-x\":\"none\",\"-Z\":\"none\",\"-z\":\"none\",\"--after-context\":\"number\",\"--basic-regexp\":\"none\",\"--before-context\":\"number\",\"--binary\":\"none\",\"--binary-files\":\"string\",\"--byte-offset\":\"none\",\"--color\":\"string\",\"--colour\":\"string\",\"--context\":\"number\",\"--count\":\"none\",\"--dereference-recursive\":\"none\",\"--directories\":\"string\",\"--devices\":\"string\",\"--exclude\":\"string\",\"--exclude-dir\":\"string\",\"--exclude-from\":\"string\",\"--extended-regexp\":\"none\",\"--file\":\"string\",\"--files-with-matches\":\"none\",\"--files-without-match\":\"none\",\"--fixed-strings\":\"none\",\"--group-separator\":\"string\",\"--help\":\"none\",\"--ignore-case\":\"none\",\"--include\":\"string\",\"--initial-tab\":\"none\",\"--invert-match\":\"none\",\"--label\":\"string\",\"--line-buffered\":\"none\",\"--line-number\":\"none\",\"--line-regexp\":\"none\",\"--max-count\":\"number\",\"--no-group-separator\":\"none\",\"--no-filename\":\"none\",\"--no-ignore-case\":\"none\",\"--no-messages\":\"none\",\"--null\":\"none\",\"--null-data\":\"none\",\"--only-matching\":\"none\",\"--perl-regexp\":\"none\",\"--quiet\":\"none\",\"--recursive\":\"none\",\"--regexp\":\"string\",\"--silent\":\"none\",\"--text\":\"none\",\"--unix-byte-offsets\":\"none\",\"--version\":\"none\",\"--with-filename\":\"none\",\"--word-regexp\":\"none\"}"))
        put("false", Policy(commandOnly = true))
        put("fd", Policy(safeFlagsJson = "{\"-0\":\"none\",\"-1\":\"none\",\"-E\":\"string\",\"-F\":\"none\",\"-H\":\"none\",\"-I\":\"none\",\"-L\":\"none\",\"-S\":\"string\",\"-V\":\"none\",\"-a\":\"none\",\"-c\":\"string\",\"-d\":\"number\",\"-e\":\"string\",\"-g\":\"none\",\"-h\":\"none\",\"-i\":\"none\",\"-j\":\"number\",\"-o\":\"string\",\"-p\":\"none\",\"-q\":\"none\",\"-s\":\"none\",\"-t\":\"string\",\"--absolute-path\":\"none\",\"--and\":\"string\",\"--base-directory\":\"string\",\"--batch-size\":\"number\",\"--case-sensitive\":\"none\",\"--changed-before\":\"string\",\"--changed-within\":\"string\",\"--color\":\"string\",\"--exact-depth\":\"number\",\"--exclude\":\"string\",\"--extension\":\"string\",\"--fixed-strings\":\"none\",\"--follow\":\"none\",\"--format\":\"string\",\"--full-path\":\"none\",\"--glob\":\"none\",\"--help\":\"none\",\"--hidden\":\"none\",\"--hyperlink\":\"string\",\"--ignore-case\":\"none\",\"--ignore-file\":\"string\",\"--max-buffer-time\":\"string\",\"--max-depth\":\"number\",\"--max-results\":\"number\",\"--min-depth\":\"number\",\"--no-ignore\":\"none\",\"--no-ignore-parent\":\"none\",\"--no-ignore-vcs\":\"none\",\"--no-require-git\":\"none\",\"--one-file-system\":\"none\",\"--owner\":\"string\",\"--path-separator\":\"string\",\"--print0\":\"none\",\"--prune\":\"none\",\"--quiet\":\"none\",\"--regex\":\"none\",\"--search-path\":\"string\",\"--show-errors\":\"none\",\"--size\":\"string\",\"--strip-cwd-prefix\":\"none\",\"--threads\":\"number\",\"--type\":\"string\",\"--version\":\"none\"}"))
        put("file", Policy(safeFlagsJson = "{\"-0\":\"none\",\"-F\":\"string\",\"-L\":\"none\",\"-b\":\"none\",\"-c\":\"none\",\"-f\":\"string\",\"-h\":\"none\",\"-i\":\"none\",\"-k\":\"none\",\"-l\":\"none\",\"-m\":\"string\",\"-n\":\"none\",\"-p\":\"none\",\"-r\":\"none\",\"-s\":\"none\",\"-v\":\"none\",\"--apple\":\"none\",\"--brief\":\"none\",\"--check-encoding\":\"none\",\"--dereference\":\"none\",\"--exclude\":\"string\",\"--exclude-quiet\":\"string\",\"--help\":\"none\",\"--keep-going\":\"none\",\"--list\":\"none\",\"--magic-file\":\"string\",\"--mime\":\"none\",\"--mime-encoding\":\"none\",\"--mime-type\":\"none\",\"--no-buffer\":\"none\",\"--no-dereference\":\"none\",\"--preserve-date\":\"none\",\"--print0\":\"none\",\"--raw\":\"none\",\"--separator\":\"string\",\"--special-files\":\"none\",\"--version\":\"none\"}"))
        put("find", Policy(allowAnyArgs = true))
        put("fgrep", Policy(safeFlagsJson = "{\"-A\":\"number\",\"-B\":\"number\",\"-C\":\"number\",\"-E\":\"none\",\"-F\":\"none\",\"-G\":\"none\",\"-H\":\"none\",\"-I\":\"none\",\"-L\":\"none\",\"-P\":\"none\",\"-R\":\"none\",\"-a\":\"none\",\"-b\":\"none\",\"-c\":\"none\",\"-D\":\"string\",\"-d\":\"string\",\"-e\":\"string\",\"-f\":\"string\",\"-h\":\"none\",\"-i\":\"none\",\"-l\":\"none\",\"-m\":\"number\",\"-n\":\"none\",\"-o\":\"none\",\"-q\":\"none\",\"-r\":\"none\",\"-s\":\"none\",\"-T\":\"none\",\"-u\":\"none\",\"-U\":\"none\",\"-v\":\"none\",\"-V\":\"none\",\"-w\":\"none\",\"-x\":\"none\",\"-Z\":\"none\",\"-z\":\"none\",\"--after-context\":\"number\",\"--basic-regexp\":\"none\",\"--before-context\":\"number\",\"--binary\":\"none\",\"--binary-files\":\"string\",\"--byte-offset\":\"none\",\"--color\":\"string\",\"--colour\":\"string\",\"--context\":\"number\",\"--count\":\"none\",\"--dereference-recursive\":\"none\",\"--directories\":\"string\",\"--devices\":\"string\",\"--exclude\":\"string\",\"--exclude-dir\":\"string\",\"--exclude-from\":\"string\",\"--extended-regexp\":\"none\",\"--file\":\"string\",\"--files-with-matches\":\"none\",\"--files-without-match\":\"none\",\"--fixed-strings\":\"none\",\"--group-separator\":\"string\",\"--help\":\"none\",\"--ignore-case\":\"none\",\"--include\":\"string\",\"--initial-tab\":\"none\",\"--invert-match\":\"none\",\"--label\":\"string\",\"--line-buffered\":\"none\",\"--line-number\":\"none\",\"--line-regexp\":\"none\",\"--max-count\":\"number\",\"--no-group-separator\":\"none\",\"--no-filename\":\"none\",\"--no-ignore-case\":\"none\",\"--no-messages\":\"none\",\"--null\":\"none\",\"--null-data\":\"none\",\"--only-matching\":\"none\",\"--perl-regexp\":\"none\",\"--quiet\":\"none\",\"--recursive\":\"none\",\"--regexp\":\"string\",\"--silent\":\"none\",\"--text\":\"none\",\"--unix-byte-offsets\":\"none\",\"--version\":\"none\",\"--with-filename\":\"none\",\"--word-regexp\":\"none\"}"))
        put("fdfind", Policy(safeFlagsJson = "{\"-0\":\"none\",\"-1\":\"none\",\"-E\":\"string\",\"-F\":\"none\",\"-H\":\"none\",\"-I\":\"none\",\"-L\":\"none\",\"-S\":\"string\",\"-V\":\"none\",\"-a\":\"none\",\"-c\":\"string\",\"-d\":\"number\",\"-e\":\"string\",\"-g\":\"none\",\"-h\":\"none\",\"-i\":\"none\",\"-j\":\"number\",\"-o\":\"string\",\"-p\":\"none\",\"-q\":\"none\",\"-s\":\"none\",\"-t\":\"string\",\"--absolute-path\":\"none\",\"--and\":\"string\",\"--base-directory\":\"string\",\"--batch-size\":\"number\",\"--case-sensitive\":\"none\",\"--changed-before\":\"string\",\"--changed-within\":\"string\",\"--color\":\"string\",\"--exact-depth\":\"number\",\"--exclude\":\"string\",\"--extension\":\"string\",\"--fixed-strings\":\"none\",\"--follow\":\"none\",\"--format\":\"string\",\"--full-path\":\"none\",\"--glob\":\"none\",\"--help\":\"none\",\"--hidden\":\"none\",\"--hyperlink\":\"string\",\"--ignore-case\":\"none\",\"--ignore-file\":\"string\",\"--max-buffer-time\":\"string\",\"--max-depth\":\"number\",\"--max-results\":\"number\",\"--min-depth\":\"number\",\"--no-ignore\":\"none\",\"--no-ignore-parent\":\"none\",\"--no-ignore-vcs\":\"none\",\"--no-require-git\":\"none\",\"--one-file-system\":\"none\",\"--owner\":\"string\",\"--path-separator\":\"string\",\"--print0\":\"none\",\"--prune\":\"none\",\"--quiet\":\"none\",\"--regex\":\"none\",\"--search-path\":\"string\",\"--show-errors\":\"none\",\"--size\":\"string\",\"--strip-cwd-prefix\":\"none\",\"--threads\":\"number\",\"--type\":\"string\",\"--version\":\"none\"}"))
        put("grep", Policy(safeFlagsJson = "{\"-A\":\"number\",\"-B\":\"number\",\"-C\":\"number\",\"-E\":\"none\",\"-F\":\"none\",\"-G\":\"none\",\"-H\":\"none\",\"-I\":\"none\",\"-L\":\"none\",\"-P\":\"none\",\"-R\":\"none\",\"-a\":\"none\",\"-b\":\"none\",\"-c\":\"none\",\"-D\":\"string\",\"-d\":\"string\",\"-e\":\"string\",\"-f\":\"string\",\"-h\":\"none\",\"-i\":\"none\",\"-l\":\"none\",\"-m\":\"number\",\"-n\":\"none\",\"-o\":\"none\",\"-q\":\"none\",\"-r\":\"none\",\"-s\":\"none\",\"-T\":\"none\",\"-u\":\"none\",\"-U\":\"none\",\"-v\":\"none\",\"-V\":\"none\",\"-w\":\"none\",\"-x\":\"none\",\"-Z\":\"none\",\"-z\":\"none\",\"--after-context\":\"number\",\"--basic-regexp\":\"none\",\"--before-context\":\"number\",\"--binary\":\"none\",\"--binary-files\":\"string\",\"--byte-offset\":\"none\",\"--color\":\"string\",\"--colour\":\"string\",\"--context\":\"number\",\"--count\":\"none\",\"--dereference-recursive\":\"none\",\"--directories\":\"string\",\"--devices\":\"string\",\"--exclude\":\"string\",\"--exclude-dir\":\"string\",\"--exclude-from\":\"string\",\"--extended-regexp\":\"none\",\"--file\":\"string\",\"--files-with-matches\":\"none\",\"--files-without-match\":\"none\",\"--fixed-strings\":\"none\",\"--group-separator\":\"string\",\"--help\":\"none\",\"--ignore-case\":\"none\",\"--include\":\"string\",\"--initial-tab\":\"none\",\"--invert-match\":\"none\",\"--label\":\"string\",\"--line-buffered\":\"none\",\"--line-number\":\"none\",\"--line-regexp\":\"none\",\"--max-count\":\"number\",\"--no-group-separator\":\"none\",\"--no-filename\":\"none\",\"--no-ignore-case\":\"none\",\"--no-messages\":\"none\",\"--null\":\"none\",\"--null-data\":\"none\",\"--only-matching\":\"none\",\"--perl-regexp\":\"none\",\"--quiet\":\"none\",\"--recursive\":\"none\",\"--regexp\":\"string\",\"--silent\":\"none\",\"--text\":\"none\",\"--unix-byte-offsets\":\"none\",\"--version\":\"none\",\"--with-filename\":\"none\",\"--word-regexp\":\"none\"}"))
        put("head", Policy(safeFlagsJson = "{\"-c\":\"number\",\"-n\":\"number\",\"-q\":\"none\",\"-v\":\"none\",\"--bytes\":\"number\",\"--lines\":\"number\",\"--quiet\":\"none\",\"--silent\":\"none\",\"--verbose\":\"none\"}"))
        put("help", Policy(safeFlagsJson = "{\"-d\":\"none\",\"-m\":\"none\",\"-s\":\"none\"}"))
        put("hexdump", Policy(allowAnyArgs = true))
        put("hostname", Policy(regex = "^hostname(?:\\s+(?:-[a-zA-Z]|--[a-zA-Z-]+))*\\s*\$", safeFlagsJson = "{\"-A\":\"none\",\"-V\":\"none\",\"-a\":\"none\",\"-d\":\"none\",\"-f\":\"none\",\"-h\":\"none\",\"-i\":\"none\",\"-I\":\"none\",\"-s\":\"none\",\"-v\":\"none\",\"--alias\":\"none\",\"--all-fqdns\":\"none\",\"--all-ip-addresses\":\"none\",\"--domain\":\"none\",\"--fqdn\":\"none\",\"--help\":\"none\",\"--ip-address\":\"none\",\"--long\":\"none\",\"--short\":\"none\",\"--verbose\":\"none\",\"--version\":\"none\"}"))
        put("id", Policy(allowAnyArgs = true))
        put("jq", Policy(safeFlagsJson = "{\"-C\":\"none\",\"-M\":\"none\",\"-S\":\"none\",\"-c\":\"none\",\"-e\":\"none\",\"-n\":\"none\",\"-r\":\"none\",\"-s\":\"none\",\"--color-output\":\"none\",\"--compact-output\":\"none\",\"--exit-status\":\"none\",\"--indent\":\"number\",\"--monochrome-output\":\"none\",\"--null-input\":\"none\",\"--raw-output\":\"none\",\"--slurp\":\"none\",\"--sort-keys\":\"none\",\"--tab\":\"none\"}", callback = Callbacks::jqCommandIsDangerous))
        put("lsof", Policy(safeFlagsJson = "{\"-?\":\"none\",\"-A\":\"string\",\"-C\":\"none\",\"-E\":\"none\",\"-F\":\"none\",\"-H\":\"none\",\"-K\":\"none\",\"-L\":\"none\",\"-N\":\"none\",\"-O\":\"none\",\"-P\":\"none\",\"-Q\":\"none\",\"-R\":\"none\",\"-S\":\"none\",\"-T\":\"none\",\"-U\":\"none\",\"-V\":\"none\",\"-X\":\"none\",\"-a\":\"none\",\"-b\":\"none\",\"-c\":\"string\",\"-d\":\"string\",\"-e\":\"string\",\"-g\":\"none\",\"-h\":\"none\",\"-i\":\"none\",\"-k\":\"string\",\"-l\":\"none\",\"-n\":\"none\",\"-o\":\"none\",\"-p\":\"string\",\"-r\":\"none\",\"-s\":\"none\",\"-t\":\"none\",\"-u\":\"string\",\"-v\":\"none\",\"-x\":\"none\"}", callback = Callbacks::lsofCommandIsDangerous))
        put("ls", Policy(allowAnyArgs = true))
        put("man", Policy(safeFlagsJson = "{\"-S\":\"string\",\"-a\":\"none\",\"-d\":\"none\",\"-f\":\"none\",\"-h\":\"none\",\"-k\":\"none\",\"-s\":\"string\",\"-w\":\"none\",\"--all\":\"none\",\"--apropos\":\"none\",\"--whatis\":\"none\"}", callback = Callbacks::manCommandIsDangerous))
        put("md5sum", Policy(safeFlagsJson = "{\"-b\":\"none\",\"-c\":\"none\",\"-t\":\"none\",\"-w\":\"none\",\"-z\":\"none\",\"--binary\":\"none\",\"--check\":\"none\",\"--help\":\"none\",\"--ignore-missing\":\"none\",\"--quiet\":\"none\",\"--status\":\"none\",\"--strict\":\"none\",\"--tag\":\"none\",\"--text\":\"none\",\"--version\":\"none\",\"--warn\":\"none\",\"--zero\":\"none\"}"))
        put("netstat", Policy(safeFlagsJson = "{\"-I\":\"string\",\"-L\":\"none\",\"-a\":\"none\",\"-f\":\"string\",\"-g\":\"none\",\"-i\":\"none\",\"-l\":\"none\",\"-m\":\"none\",\"-n\":\"none\",\"-r\":\"none\",\"-s\":\"none\",\"-v\":\"none\"}"))
        put("nl", Policy(allowAnyArgs = true))
        put("pgrep", Policy(safeFlagsJson = "{\"-F\":\"string\",\"-G\":\"string\",\"-L\":\"none\",\"-O\":\"string\",\"-P\":\"string\",\"-U\":\"string\",\"-V\":\"none\",\"-a\":\"none\",\"-c\":\"none\",\"-d\":\"string\",\"-f\":\"none\",\"-g\":\"string\",\"-i\":\"none\",\"-l\":\"none\",\"-n\":\"none\",\"-o\":\"none\",\"-r\":\"string\",\"-s\":\"string\",\"-t\":\"string\",\"-u\":\"string\",\"-v\":\"none\",\"-w\":\"none\",\"-x\":\"none\",\"--count\":\"none\",\"--delimiter\":\"string\",\"--euid\":\"string\",\"--exact\":\"none\",\"--full\":\"none\",\"--group\":\"string\",\"--help\":\"none\",\"--ignore-case\":\"none\",\"--inverse\":\"none\",\"--lightweight\":\"none\",\"--list-full\":\"none\",\"--list-name\":\"none\",\"--logpidfile\":\"none\",\"--newest\":\"none\",\"--ns\":\"string\",\"--nslist\":\"string\",\"--older\":\"string\",\"--oldest\":\"none\",\"--parent\":\"string\",\"--pgroup\":\"string\",\"--pidfile\":\"string\",\"--runstates\":\"string\",\"--session\":\"string\",\"--terminal\":\"string\",\"--uid\":\"string\",\"--version\":\"none\"}"))
        put("ps", Policy(safeFlagsJson = "{\"-A\":\"none\",\"-C\":\"string\",\"-F\":\"none\",\"-G\":\"string\",\"-H\":\"none\",\"-L\":\"none\",\"-N\":\"none\",\"-T\":\"none\",\"-U\":\"string\",\"-V\":\"none\",\"-a\":\"none\",\"-c\":\"none\",\"-d\":\"none\",\"-e\":\"none\",\"-f\":\"none\",\"-g\":\"string\",\"-j\":\"none\",\"-l\":\"none\",\"-m\":\"none\",\"-n\":\"string\",\"-p\":\"string\",\"-q\":\"string\",\"-s\":\"string\",\"-t\":\"string\",\"-u\":\"string\",\"-w\":\"none\",\"-ww\":\"none\",\"-y\":\"none\",\"--deselect\":\"none\",\"--forest\":\"none\",\"--headers\":\"none\",\"--help\":\"none\",\"--info\":\"none\",\"--no-headers\":\"none\",\"--pid\":\"string\",\"--quick-pid\":\"string\",\"--sid\":\"string\",\"--sort\":\"string\",\"--tty\":\"string\",\"--user\":\"string\",\"--version\":\"none\",\"--width\":\"number\"}", callback = Callbacks::psCommandIsDangerous))
        put("pyright", Policy(respectsDoubleDash = false, safeFlagsJson = "{\"--dependencies\":\"none\",\"--level\":\"string\",\"--outputjson\":\"none\",\"--pythonplatform\":\"string\",\"--pythonversion\":\"string\",\"--stats\":\"none\",\"--verbose\":\"none\",\"--version\":\"none\",\"--warnings\":\"none\"}", callback = Callbacks::pyrightCommandIsDangerous))
        put("printf", Policy(allowAnyArgs = true))
        put("pwd", Policy(commandOnly = true))
        put("readlink", Policy(allowAnyArgs = true))
        put("realpath", Policy(allowAnyArgs = true))
        put("rev", Policy(allowAnyArgs = true))
        put("rg", Policy(safeFlagsJson = "{\"-A\":\"number\",\"-B\":\"number\",\"-C\":\"number\",\"-F\":\"none\",\"-H\":\"none\",\"-L\":\"none\",\"-N\":\"none\",\"-S\":\"none\",\"-T\":\"string\",\"-c\":\"none\",\"-e\":\"string\",\"-g\":\"string\",\"-h\":\"none\",\"-i\":\"none\",\"-l\":\"none\",\"-m\":\"number\",\"-n\":\"none\",\"-s\":\"none\",\"-t\":\"string\",\"-u\":\"none\",\"-v\":\"none\",\"-w\":\"none\",\"-x\":\"none\",\"--after-context\":\"number\",\"--before-context\":\"number\",\"--case-sensitive\":\"none\",\"--context\":\"number\",\"--count\":\"none\",\"--count-matches\":\"none\",\"--files\":\"none\",\"--files-with-matches\":\"none\",\"--fixed-strings\":\"none\",\"--follow\":\"none\",\"--glob\":\"string\",\"--hidden\":\"none\",\"--ignore-case\":\"none\",\"--ignore-file\":\"string\",\"--iglob\":\"string\",\"--invert-match\":\"none\",\"--json\":\"none\",\"--line-number\":\"none\",\"--line-regexp\":\"none\",\"--max-count\":\"number\",\"--max-depth\":\"number\",\"--max-filesize\":\"string\",\"--no-filename\":\"none\",\"--no-ignore\":\"none\",\"--no-ignore-parent\":\"none\",\"--no-ignore-vcs\":\"none\",\"--no-line-number\":\"none\",\"--pcre2\":\"none\",\"--regexp\":\"string\",\"--smart-case\":\"none\",\"--sort\":\"string\",\"--sortr\":\"string\",\"--type\":\"string\",\"--type-not\":\"string\",\"--unrestricted\":\"none\",\"--with-filename\":\"none\",\"--word-regexp\":\"none\"}"))
        put("sed", Policy(safeFlagsJson = "{\"-E\":\"none\",\"-e\":\"string\",\"-l\":\"number\",\"-n\":\"none\",\"-r\":\"none\",\"-s\":\"none\",\"-u\":\"none\",\"-z\":\"none\",\"--debug\":\"none\",\"--expression\":\"string\",\"--help\":\"none\",\"--line-length\":\"number\",\"--posix\":\"none\",\"--quiet\":\"none\",\"--regexp-extended\":\"none\",\"--separate\":\"none\",\"--silent\":\"none\",\"--unbuffered\":\"none\",\"--version\":\"none\",\"--zero-terminated\":\"none\"}", callback = Callbacks::sedCommandIsDangerous))
        put("sha1sum", Policy(safeFlagsJson = "{\"-b\":\"none\",\"-c\":\"none\",\"-t\":\"none\",\"-w\":\"none\",\"-z\":\"none\",\"--binary\":\"none\",\"--check\":\"none\",\"--help\":\"none\",\"--ignore-missing\":\"none\",\"--quiet\":\"none\",\"--status\":\"none\",\"--strict\":\"none\",\"--tag\":\"none\",\"--text\":\"none\",\"--version\":\"none\",\"--warn\":\"none\",\"--zero\":\"none\"}"))
        put("sha256sum", Policy(safeFlagsJson = "{\"-b\":\"none\",\"-c\":\"none\",\"-t\":\"none\",\"-w\":\"none\",\"-z\":\"none\",\"--binary\":\"none\",\"--check\":\"none\",\"--help\":\"none\",\"--ignore-missing\":\"none\",\"--quiet\":\"none\",\"--status\":\"none\",\"--strict\":\"none\",\"--tag\":\"none\",\"--text\":\"none\",\"--version\":\"none\",\"--warn\":\"none\",\"--zero\":\"none\"}"))
        put("sort", Policy(safeFlagsJson = "{\"-C\":\"none\",\"-M\":\"none\",\"-R\":\"none\",\"-S\":\"string\",\"-V\":\"none\",\"-b\":\"none\",\"-c\":\"none\",\"-d\":\"none\",\"-f\":\"none\",\"-g\":\"none\",\"-h\":\"none\",\"-i\":\"none\",\"-k\":\"string\",\"-m\":\"none\",\"-n\":\"none\",\"-r\":\"none\",\"-s\":\"none\",\"-t\":\"string\",\"-u\":\"none\",\"-z\":\"none\",\"--batch-size\":\"number\",\"--buffer-size\":\"string\",\"--check\":\"none\",\"--check-char-order\":\"none\",\"--dictionary-order\":\"none\",\"--field-separator\":\"string\",\"--general-numeric-sort\":\"none\",\"--help\":\"none\",\"--human-numeric-sort\":\"none\",\"--ignore-case\":\"none\",\"--ignore-leading-blanks\":\"none\",\"--ignore-nonprinting\":\"none\",\"--key\":\"string\",\"--merge\":\"none\",\"--month-sort\":\"none\",\"--numeric-sort\":\"none\",\"--parallel\":\"number\",\"--random-sort\":\"none\",\"--reverse\":\"none\",\"--sort\":\"string\",\"--stable\":\"none\",\"--unique\":\"none\",\"--version\":\"none\",\"--version-sort\":\"none\",\"--zero-terminated\":\"none\"}"))
        put("ss", Policy(safeFlagsJson = "{\"-0\":\"none\",\"-4\":\"none\",\"-6\":\"none\",\"-A\":\"string\",\"-E\":\"none\",\"-H\":\"none\",\"-M\":\"none\",\"-O\":\"none\",\"-S\":\"none\",\"-V\":\"none\",\"-Z\":\"none\",\"-a\":\"none\",\"-b\":\"none\",\"-d\":\"none\",\"-e\":\"none\",\"-f\":\"string\",\"-h\":\"none\",\"-i\":\"none\",\"-l\":\"none\",\"-m\":\"none\",\"-n\":\"none\",\"-o\":\"none\",\"-p\":\"none\",\"-s\":\"none\",\"-t\":\"none\",\"-u\":\"none\",\"-w\":\"none\",\"-x\":\"none\",\"-z\":\"none\",\"--all\":\"none\",\"--bpf\":\"none\",\"--cgroup\":\"none\",\"--contexts\":\"none\",\"--context\":\"none\",\"--dccp\":\"none\",\"--events\":\"none\",\"--extended\":\"none\",\"--family\":\"string\",\"--help\":\"none\",\"--inet-sockopt\":\"none\",\"--info\":\"none\",\"--ipv4\":\"none\",\"--ipv6\":\"none\",\"--listening\":\"none\",\"--memory\":\"none\",\"--mptcp\":\"none\",\"--no-header\":\"none\",\"--numeric\":\"none\",\"--oneline\":\"none\",\"--options\":\"none\",\"--packet\":\"none\",\"--processes\":\"none\",\"--query\":\"string\",\"--raw\":\"none\",\"--sctp\":\"none\",\"--socket\":\"string\",\"--summary\":\"none\",\"--tcp\":\"none\",\"--tipc\":\"none\",\"--tipcinfo\":\"none\",\"--tos\":\"none\",\"--udp\":\"none\",\"--unix\":\"none\",\"--version\":\"none\",\"--vsock\":\"none\"}", callback = Callbacks::ssCommandIsDangerous))
        put("stat", Policy(allowAnyArgs = true))
        put("strings", Policy(allowAnyArgs = true))
        put("tail", Policy(safeFlagsJson = "{\"-c\":\"number\",\"-n\":\"number\",\"-q\":\"none\",\"-v\":\"none\",\"--bytes\":\"number\",\"--lines\":\"number\",\"--quiet\":\"none\",\"--silent\":\"none\",\"--verbose\":\"none\"}"))
        put("test", Policy(respectsDoubleDash = false, safeFlagsJson = "{\"-b\":\"string\",\"-c\":\"string\",\"-d\":\"string\",\"-e\":\"string\",\"-ef\":\"string\",\"-eq\":\"string\",\"-f\":\"string\",\"-g\":\"string\",\"-G\":\"string\",\"-ge\":\"string\",\"-gt\":\"string\",\"-h\":\"string\",\"-k\":\"string\",\"-L\":\"string\",\"-le\":\"string\",\"-lt\":\"string\",\"-N\":\"string\",\"-n\":\"string\",\"-ne\":\"string\",\"-nt\":\"string\",\"-O\":\"string\",\"-ot\":\"string\",\"-p\":\"string\",\"-r\":\"string\",\"-s\":\"string\",\"-S\":\"string\",\"-t\":\"string\",\"-u\":\"string\",\"-w\":\"string\",\"-x\":\"string\",\"-z\":\"string\"}", callback = Callbacks::testCommandIsDangerous))
        put("tput", Policy(safeFlagsJson = "{\"-T\":\"string\",\"-V\":\"none\",\"-x\":\"none\"}", callback = Callbacks::tputCommandIsDangerous))
        put("tr", Policy(allowAnyArgs = true))
        put("tree", Policy(safeFlagsJson = "{\"-A\":\"none\",\"-C\":\"none\",\"-D\":\"none\",\"-F\":\"none\",\"-H\":\"string\",\"-I\":\"string\",\"-J\":\"none\",\"-L\":\"number\",\"-N\":\"none\",\"-P\":\"string\",\"-Q\":\"none\",\"-S\":\"none\",\"-T\":\"string\",\"-U\":\"none\",\"-X\":\"none\",\"-a\":\"none\",\"-c\":\"none\",\"-d\":\"none\",\"-f\":\"none\",\"-g\":\"none\",\"-h\":\"none\",\"-i\":\"none\",\"-l\":\"none\",\"-n\":\"none\",\"-p\":\"none\",\"-q\":\"none\",\"-r\":\"none\",\"-s\":\"none\",\"-t\":\"none\",\"-u\":\"none\",\"-v\":\"none\",\"-x\":\"none\",\"--authority\":\"string\",\"--charset\":\"string\",\"--device\":\"none\",\"--dirsfirst\":\"none\",\"--du\":\"none\",\"--fflinks\":\"none\",\"--filelimit\":\"number\",\"--filesfirst\":\"none\",\"--fromfile\":\"none\",\"--fromtabfile\":\"none\",\"--gitfile\":\"string\",\"--gitignore\":\"none\",\"--help\":\"none\",\"--hintro\":\"string\",\"--houtro\":\"string\",\"--hyperlink\":\"none\",\"--ignore-case\":\"none\",\"--inodes\":\"none\",\"--info\":\"none\",\"--infofile\":\"string\",\"--matchdirs\":\"none\",\"--metafirst\":\"none\",\"--nolinks\":\"none\",\"--noreport\":\"none\",\"--prune\":\"none\",\"--scheme\":\"string\",\"--si\":\"none\",\"--sort\":\"string\",\"--timefmt\":\"string\",\"--version\":\"none\"}"))
        put("true", Policy(commandOnly = true))
        put("uniq", Policy(allowAnyArgs = true))
        put("uname", Policy(allowAnyArgs = true))
        put("wc", Policy(safeFlagsJson = "{\"-L\":\"none\",\"-c\":\"none\",\"-l\":\"none\",\"-m\":\"none\",\"-w\":\"none\",\"--bytes\":\"none\",\"--chars\":\"none\",\"--lines\":\"none\",\"--max-line-length\":\"none\",\"--words\":\"none\"}"))
        put("which", Policy(allowAnyArgs = true))
        put("whoami", Policy(commandOnly = true))
        put("xargs", Policy(safeFlagsJson = "{\"-0\":\"none\",\"-E\":\"EOF\",\"-I\":\"{}\",\"-L\":\"number\",\"-P\":\"number\",\"-d\":\"char\",\"-n\":\"number\",\"-r\":\"none\",\"-s\":\"number\",\"-t\":\"none\",\"-x\":\"none\"}", callback = Callbacks::xargsCommandIsDangerous))
        put("[", Policy(respectsDoubleDash = false, safeFlagsJson = "{\"-b\":\"string\",\"-c\":\"string\",\"-d\":\"string\",\"-e\":\"string\",\"-ef\":\"string\",\"-eq\":\"string\",\"-f\":\"string\",\"-g\":\"string\",\"-G\":\"string\",\"-ge\":\"string\",\"-gt\":\"string\",\"-h\":\"string\",\"-k\":\"string\",\"-L\":\"string\",\"-le\":\"string\",\"-lt\":\"string\",\"-N\":\"string\",\"-n\":\"string\",\"-ne\":\"string\",\"-nt\":\"string\",\"-O\":\"string\",\"-ot\":\"string\",\"-p\":\"string\",\"-r\":\"string\",\"-s\":\"string\",\"-S\":\"string\",\"-t\":\"string\",\"-u\":\"string\",\"-w\":\"string\",\"-x\":\"string\",\"-z\":\"string\"}", callback = Callbacks::testCommandIsDangerous))
        put("[[", Policy(respectsDoubleDash = false, safeFlagsJson = "{\"-b\":\"string\",\"-c\":\"string\",\"-d\":\"string\",\"-e\":\"string\",\"-ef\":\"string\",\"-eq\":\"string\",\"-f\":\"string\",\"-g\":\"string\",\"-G\":\"string\",\"-ge\":\"string\",\"-gt\":\"string\",\"-h\":\"string\",\"-k\":\"string\",\"-L\":\"string\",\"-le\":\"string\",\"-lt\":\"string\",\"-N\":\"string\",\"-n\":\"string\",\"-ne\":\"string\",\"-nt\":\"string\",\"-O\":\"string\",\"-ot\":\"string\",\"-p\":\"string\",\"-r\":\"string\",\"-s\":\"string\",\"-S\":\"string\",\"-t\":\"string\",\"-u\":\"string\",\"-w\":\"string\",\"-x\":\"string\",\"-z\":\"string\"}", callback = Callbacks::testCommandIsDangerous))
    }

    private val GIT_SUBCOMMAND_POLICIES: Map<String, Policy> = buildMap {
        put("git blame", Policy(safeFlagsJson = "{\"--color\":\"none\",\"--no-color\":\"none\",\"-C\":\"none\",\"-L\":\"string\",\"-M\":\"none\",\"-e\":\"none\",\"-f\":\"none\",\"-l\":\"none\",\"-n\":\"none\",\"-p\":\"none\",\"-s\":\"none\",\"-t\":\"none\",\"-w\":\"none\",\"--abbrev\":\"number\",\"--date\":\"string\",\"--ignore-rev\":\"string\",\"--ignore-revs-file\":\"string\",\"--incremental\":\"none\",\"--line-porcelain\":\"none\",\"--porcelain\":\"none\",\"--root\":\"none\",\"--score-debug\":\"none\",\"--show-email\":\"none\",\"--show-name\":\"none\",\"--show-number\":\"none\",\"--show-stats\":\"none\"}"))
        put("git branch", Policy(safeFlagsJson = "{\"-a\":\"none\",\"-i\":\"none\",\"-l\":\"none\",\"-r\":\"none\",\"-v\":\"none\",\"-vv\":\"none\",\"--abbrev\":\"number\",\"--all\":\"none\",\"--color\":\"none\",\"--column\":\"none\",\"--contains\":\"string\",\"--ignore-case\":\"none\",\"--list\":\"none\",\"--merged\":\"none\",\"--no-abbrev\":\"none\",\"--no-color\":\"none\",\"--no-column\":\"none\",\"--no-contains\":\"string\",\"--no-merged\":\"none\",\"--points-at\":\"string\",\"--remotes\":\"none\",\"--show-current\":\"none\",\"--sort\":\"string\",\"--verbose\":\"none\"}", callback = Callbacks::gitBranchCommandIsDangerous))
        put("git cat-file", Policy(safeFlagsJson = "{\"-e\":\"none\",\"-p\":\"none\",\"-s\":\"none\",\"-t\":\"none\",\"--allow-undetermined-type\":\"none\",\"--batch-check\":\"none\"}"))
        put("git config --get", Policy(safeFlagsJson = "{\"-z\":\"none\",\"--bool\":\"none\",\"--bool-or-int\":\"none\",\"--default\":\"string\",\"--expiry-date\":\"none\",\"--global\":\"none\",\"--int\":\"none\",\"--local\":\"none\",\"--name-only\":\"none\",\"--null\":\"none\",\"--path\":\"none\",\"--show-origin\":\"none\",\"--show-scope\":\"none\",\"--system\":\"none\",\"--type\":\"string\",\"--worktree\":\"none\"}"))
        put("git describe", Policy(safeFlagsJson = "{\"--abbrev\":\"number\",\"--always\":\"none\",\"--broken\":\"none\",\"--candidates\":\"number\",\"--contains\":\"none\",\"--dirty\":\"none\",\"--exclude\":\"string\",\"--exact-match\":\"none\",\"--first-match\":\"none\",\"--long\":\"none\",\"--match\":\"string\",\"--tags\":\"none\"}"))
        put("git diff", Policy(safeFlagsJson = "{\"-B\":\"none\",\"-C\":\"none\",\"-D\":\"none\",\"-G\":\"string\",\"-M\":\"none\",\"-O\":\"string\",\"-R\":\"none\",\"-S\":\"string\",\"-l\":\"none\",\"-p\":\"none\",\"-s\":\"none\",\"-u\":\"none\",\"--cached\":\"none\",\"--check\":\"none\",\"--color\":\"none\",\"--color-words\":\"none\",\"--diff-algorithm\":\"string\",\"--diff-filter\":\"string\",\"--dirstat\":\"none\",\"--exit-code\":\"none\",\"--find-copies\":\"none\",\"--find-copies-harder\":\"none\",\"--find-renames\":\"none\",\"--full-index\":\"none\",\"--function-context\":\"none\",\"--histogram\":\"none\",\"--ignore-all-space\":\"none\",\"--ignore-blank-lines\":\"none\",\"--ignore-space-at-eol\":\"none\",\"--ignore-space-change\":\"none\",\"--inter-hunk-context\":\"number\",\"--irreversible-delete\":\"none\",\"--minimal\":\"none\",\"--name-only\":\"none\",\"--name-status\":\"none\",\"--no-ext-diff\":\"none\",\"--no-index\":\"none\",\"--no-renames\":\"none\",\"--no-color\":\"none\",\"--numstat\":\"none\",\"--patch-with-stat\":\"none\",\"--patience\":\"none\",\"--pickaxe-all\":\"none\",\"--pickaxe-regex\":\"none\",\"--quiet\":\"none\",\"--relative\":\"string\",\"--shortstat\":\"none\",\"--staged\":\"none\",\"--stat\":\"none\",\"--summary\":\"none\",\"--word-diff\":\"none\",\"--word-diff-regex\":\"string\",\"--ws-error-highlight\":\"string\"}"))
        put("git for-each-ref", Policy(safeFlagsJson = "{\"--contains\":\"string\",\"--count\":\"number\",\"--format\":\"string\",\"--merged\":\"string\",\"--no-contains\":\"string\",\"--no-merged\":\"string\",\"--points-at\":\"string\",\"--sort\":\"string\"}", callback = Callbacks::gitRevisionFormatCommandIsDangerous))
        put("git grep", Policy(safeFlagsJson = "{\"-A\":\"number\",\"-B\":\"number\",\"-C\":\"number\",\"-E\":\"none\",\"-F\":\"none\",\"-G\":\"none\",\"-H\":\"none\",\"-L\":\"none\",\"-P\":\"none\",\"-c\":\"none\",\"-e\":\"string\",\"-h\":\"none\",\"-i\":\"none\",\"-l\":\"none\",\"-n\":\"none\",\"-o\":\"none\",\"-q\":\"none\",\"-v\":\"none\",\"-w\":\"none\",\"--after-context\":\"number\",\"--and\":\"none\",\"--basic-regexp\":\"none\",\"--break\":\"none\",\"--cached\":\"none\",\"--color\":\"none\",\"--context\":\"number\",\"--count\":\"none\",\"--extended-regexp\":\"none\",\"--files-with-matches\":\"none\",\"--files-without-match\":\"none\",\"--fixed-strings\":\"none\",\"--full-name\":\"none\",\"--heading\":\"none\",\"--ignore-case\":\"none\",\"--invert-match\":\"none\",\"--line-number\":\"none\",\"--max-depth\":\"number\",\"--no-color\":\"none\",\"--no-index\":\"none\",\"--not\":\"none\",\"--only-matching\":\"none\",\"--or\":\"none\",\"--perl-regexp\":\"none\",\"--quiet\":\"none\",\"--recurse-submodules\":\"none\",\"--threads\":\"number\",\"--untracked\":\"none\",\"--word-regexp\":\"none\"}"))
        put("git log", Policy(allowCompactNumericCountFlag = true, safeFlagsJson = "{\"--date\":\"string\",\"--decorate\":\"none\",\"--graph\":\"none\",\"--no-decorate\":\"none\",\"--oneline\":\"none\",\"--relative-date\":\"none\",\"--all\":\"none\",\"--branches\":\"none\",\"--remotes\":\"none\",\"--tags\":\"none\",\"--after\":\"string\",\"--before\":\"string\",\"--since\":\"string\",\"--until\":\"string\",\"-n\":\"number\",\"--max-count\":\"number\",\"--name-only\":\"none\",\"--name-status\":\"none\",\"--numstat\":\"none\",\"--shortstat\":\"none\",\"--stat\":\"none\",\"--color\":\"none\",\"--no-color\":\"none\",\"-p\":\"none\",\"-s\":\"none\",\"--no-ext-diff\":\"none\",\"--no-patch\":\"none\",\"--patch\":\"none\",\"-G\":\"string\",\"-S\":\"string\",\"--abbrev-commit\":\"none\",\"--ancestry-path\":\"none\",\"--author\":\"string\",\"--author-date-order\":\"none\",\"--boundary\":\"none\",\"--cherry-mark\":\"none\",\"--cherry-pick\":\"none\",\"--committer\":\"string\",\"--date-order\":\"none\",\"--dense\":\"none\",\"--diff-filter\":\"string\",\"--first-parent\":\"none\",\"--format\":\"string\",\"--full-history\":\"none\",\"--grep\":\"string\",\"--left-right\":\"none\",\"--max-age\":\"number\",\"--min-age\":\"number\",\"--merges\":\"none\",\"--no-max-parents\":\"none\",\"--no-merges\":\"none\",\"--no-min-parents\":\"none\",\"--no-walk\":\"none\",\"--pickaxe-all\":\"none\",\"--pickaxe-regex\":\"none\",\"--pretty\":\"string\",\"--reverse\":\"none\",\"--simplify-merges\":\"none\",\"--skip\":\"number\",\"--source\":\"none\",\"--sparse\":\"none\",\"--topo-order\":\"none\",\"--walk-reflogs\":\"none\"}", callback = Callbacks::gitRevisionFormatCommandIsDangerous))
        put("git ls-files", Policy(safeFlagsJson = "{\"-c\":\"none\",\"-d\":\"none\",\"-f\":\"none\",\"-i\":\"none\",\"-k\":\"none\",\"-m\":\"none\",\"-o\":\"none\",\"-s\":\"none\",\"-t\":\"none\",\"-u\":\"none\",\"-v\":\"none\",\"-x\":\"string\",\"-X\":\"string\",\"-z\":\"none\",\"--abbrev\":\"number\",\"--cached\":\"none\",\"--debug\":\"none\",\"--deleted\":\"none\",\"--directory\":\"none\",\"--eol\":\"none\",\"--error-unmatch\":\"none\",\"--exclude\":\"string\",\"--exclude-from\":\"string\",\"--exclude-per-directory\":\"string\",\"--exclude-standard\":\"none\",\"--full-name\":\"none\",\"--ignored\":\"none\",\"--killed\":\"none\",\"--modified\":\"none\",\"--no-empty-directory\":\"none\",\"--others\":\"none\",\"--recurse-submodules\":\"none\",\"--stage\":\"none\",\"--unmerged\":\"none\"}"))
        put("git ls-remote", Policy(safeFlagsJson = "{\"-b\":\"none\",\"-h\":\"none\",\"-q\":\"none\",\"-t\":\"none\",\"--branches\":\"none\",\"--exit-code\":\"none\",\"--get-url\":\"none\",\"--heads\":\"none\",\"--quiet\":\"none\",\"--refs\":\"none\",\"--sort\":\"string\",\"--symref\":\"none\",\"--tags\":\"none\"}", callback = Callbacks::gitLsRemoteCommandIsDangerous))
        put("git merge-base", Policy(safeFlagsJson = "{\"--all\":\"none\",\"--fork-point\":\"none\",\"--independent\":\"none\",\"--is-ancestor\":\"none\",\"--octopus\":\"none\"}"))
        put("git reflog", Policy(safeFlagsJson = "{\"--date\":\"string\",\"--decorate\":\"none\",\"--graph\":\"none\",\"--no-decorate\":\"none\",\"--oneline\":\"none\",\"--relative-date\":\"none\",\"--all\":\"none\",\"--branches\":\"none\",\"--remotes\":\"none\",\"--tags\":\"none\",\"--after\":\"string\",\"--before\":\"string\",\"--since\":\"string\",\"--until\":\"string\",\"-n\":\"number\",\"--max-count\":\"number\",\"--name-only\":\"none\",\"--name-status\":\"none\",\"--numstat\":\"none\",\"--shortstat\":\"none\",\"--stat\":\"none\",\"--grep\":\"string\"}", callback = Callbacks::gitReflogCommandIsDangerous))
        put("git remote show", Policy(safeFlagsJson = "{\"-n\":\"none\"}", callback = Callbacks::gitRemoteShowCommandIsDangerous))
        put("git remote", Policy(safeFlagsJson = "{\"-v\":\"none\",\"--verbose\":\"none\"}", callback = Callbacks::gitRemoteCommandIsDangerous))
        put("git rev-list", Policy(safeFlagsJson = "{\"--all\":\"none\",\"--branches\":\"none\",\"--remotes\":\"none\",\"--tags\":\"none\",\"--after\":\"string\",\"--before\":\"string\",\"--since\":\"string\",\"--until\":\"string\",\"-n\":\"number\",\"--max-count\":\"number\",\"--abbrev\":\"number\",\"--abbrev-commit\":\"none\",\"--ancestry-path\":\"none\",\"--count\":\"none\",\"--dense\":\"none\",\"--first-parent\":\"none\",\"--format\":\"string\",\"--full-history\":\"none\",\"--graph\":\"none\",\"--grep\":\"string\",\"--max-age\":\"number\",\"--max-parents\":\"number\",\"--merges\":\"none\",\"--min-age\":\"number\",\"--min-parents\":\"number\",\"--no-max-parents\":\"none\",\"--no-merges\":\"none\",\"--no-min-parents\":\"none\",\"--oneline\":\"none\",\"--pretty\":\"string\",\"--reverse\":\"none\",\"--skip\":\"number\",\"--source\":\"none\",\"--sparse\":\"none\",\"--walk-reflogs\":\"none\"}", callback = Callbacks::gitRevisionFormatCommandIsDangerous))
        put("git rev-parse", Policy(safeFlagsJson = "{\"--abbrev-ref\":\"none\",\"--absolute-git-dir\":\"none\",\"--git-common-dir\":\"none\",\"--git-dir\":\"none\",\"--is-bare-repository\":\"none\",\"--is-inside-git-dir\":\"none\",\"--is-inside-work-tree\":\"none\",\"--is-shallow-repository\":\"none\",\"--is-shallow-update\":\"none\",\"--path-prefix\":\"none\",\"--short\":\"string\",\"--show-cdup\":\"none\",\"--show-prefix\":\"none\",\"--show-superproject-working-tree\":\"none\",\"--show-toplevel\":\"none\",\"--symbolic\":\"none\",\"--symbolic-full-name\":\"none\",\"--verify\":\"none\"}"))
        put("git shortlog", Policy(safeFlagsJson = "{\"--all\":\"none\",\"--branches\":\"none\",\"--remotes\":\"none\",\"--tags\":\"none\",\"--after\":\"string\",\"--before\":\"string\",\"--since\":\"string\",\"--until\":\"string\",\"-c\":\"none\",\"-e\":\"none\",\"-n\":\"none\",\"-s\":\"none\",\"--author\":\"string\",\"--committer\":\"none\",\"--email\":\"none\",\"--format\":\"string\",\"--group\":\"string\",\"--no-merges\":\"none\",\"--numbered\":\"none\",\"--summary\":\"none\"}", callback = Callbacks::gitRevisionFormatCommandIsDangerous))
        put("git show", Policy(safeFlagsJson = "{\"--date\":\"string\",\"--decorate\":\"none\",\"--graph\":\"none\",\"--no-decorate\":\"none\",\"--oneline\":\"none\",\"--relative-date\":\"none\",\"--name-only\":\"none\",\"--name-status\":\"none\",\"--numstat\":\"none\",\"--shortstat\":\"none\",\"--stat\":\"none\",\"--color\":\"none\",\"--no-color\":\"none\",\"-p\":\"none\",\"-s\":\"none\",\"--no-ext-diff\":\"none\",\"--no-patch\":\"none\",\"--patch\":\"none\",\"-m\":\"none\",\"--abbrev-commit\":\"none\",\"--color-words\":\"none\",\"--diff-filter\":\"string\",\"--first-parent\":\"none\",\"--format\":\"string\",\"--pretty\":\"string\",\"--quiet\":\"none\",\"--raw\":\"none\",\"--word-diff\":\"none\",\"--word-diff-regex\":\"string\"}", callback = Callbacks::gitRevisionFormatCommandIsDangerous))
        put("git stash list", Policy(safeFlagsJson = "{\"--date\":\"string\",\"--decorate\":\"none\",\"--graph\":\"none\",\"--no-decorate\":\"none\",\"--oneline\":\"none\",\"--relative-date\":\"none\",\"--all\":\"none\",\"--branches\":\"none\",\"--remotes\":\"none\",\"--tags\":\"none\",\"-n\":\"number\",\"--max-count\":\"number\"}"))
        put("git stash show", Policy(safeFlagsJson = "{\"--name-only\":\"none\",\"--name-status\":\"none\",\"--numstat\":\"none\",\"--shortstat\":\"none\",\"--stat\":\"none\",\"--color\":\"none\",\"--no-color\":\"none\",\"-p\":\"none\",\"-s\":\"none\",\"--no-ext-diff\":\"none\",\"--no-patch\":\"none\",\"--patch\":\"none\",\"--abbrev\":\"number\",\"--diff-filter\":\"string\",\"--word-diff\":\"none\",\"--word-diff-regex\":\"string\"}"))
        put("git status", Policy(safeFlagsJson = "{\"-b\":\"none\",\"-M\":\"string\",\"-s\":\"none\",\"-u\":\"string\",\"--ahead-behind\":\"none\",\"--branch\":\"none\",\"--column\":\"none\",\"--find-renames\":\"string\",\"--ignore-submodules\":\"string\",\"--ignored\":\"none\",\"--long\":\"none\",\"--no-ahead-behind\":\"none\",\"--no-column\":\"none\",\"--no-renames\":\"none\",\"--porcelain\":\"none\",\"--renames\":\"none\",\"--short\":\"none\",\"--untracked-files\":\"string\",\"--verbose\":\"none\",\"-v\":\"none\"}"))
        put("git tag", Policy(safeFlagsJson = "{\"-i\":\"none\",\"-l\":\"none\",\"-n\":\"number\",\"--column\":\"none\",\"--contains\":\"string\",\"--format\":\"string\",\"--ignore-case\":\"none\",\"--list\":\"none\",\"--merged\":\"string\",\"--no-column\":\"none\",\"--no-contains\":\"string\",\"--no-merged\":\"string\",\"--points-at\":\"string\",\"--sort\":\"string\"}", callback = Callbacks::gitTagCommandIsDangerous))
        put("git worktree list", Policy(safeFlagsJson = "{\"-v\":\"none\",\"--expire\":\"string\",\"--porcelain\":\"none\",\"--verbose\":\"none\"}"))
    }

    private val MULTIWORD_POLICIES: Map<String, Policy> = buildMap {
        put("docker inspect", Policy(safeFlagsJson = "{\"-f\":\"string\",\"-s\":\"none\",\"--format\":\"string\",\"--size\":\"none\",\"--type\":\"string\"}", callback = Callbacks::dockerCommandIsDangerous))
        put("docker logs", Policy(safeFlagsJson = "{\"-f\":\"none\",\"-n\":\"string\",\"-t\":\"none\",\"--details\":\"none\",\"--follow\":\"none\",\"--since\":\"string\",\"--tail\":\"string\",\"--timestamps\":\"none\",\"--until\":\"string\"}", callback = Callbacks::dockerCommandIsDangerous))
        put("gh auth status", Policy(safeFlagsJson = "{\"-a\":\"none\",\"-h\":\"string\",\"--active\":\"none\",\"--hostname\":\"string\",\"--json\":\"string\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh issue list", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"-s\":\"string\",\"--app\":\"string\",\"--assignee\":\"string\",\"--author\":\"string\",\"--label\":\"string\",\"--milestone\":\"string\",\"--search\":\"string\",\"--state\":\"string\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh issue status", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh issue view", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"--comments\":\"none\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh label list", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"-S\":\"string\",\"--order\":\"string\",\"--search\":\"string\",\"--sort\":\"string\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh pr checks", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"--fail-fast\":\"none\",\"--interval\":\"number\",\"--required\":\"none\",\"--watch\":\"none\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh pr diff", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--color\":\"string\",\"--name-only\":\"none\",\"--patch\":\"none\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh pr list", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"-s\":\"string\",\"--app\":\"string\",\"--assignee\":\"string\",\"--author\":\"string\",\"--base\":\"string\",\"--draft\":\"none\",\"--head\":\"string\",\"--label\":\"string\",\"--search\":\"string\",\"--state\":\"string\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh pr status", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-c\":\"none\",\"--conflict-status\":\"none\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh pr view", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"--comments\":\"none\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh release list", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"-O\":\"string\",\"--exclude-drafts\":\"none\",\"--exclude-pre-releases\":\"none\",\"--order\":\"string\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh release view", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh repo view", Policy(safeFlagsJson = "{\"--json\":\"string\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh run list", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"-b\":\"string\",\"-c\":\"string\",\"-e\":\"string\",\"-s\":\"string\",\"-u\":\"string\",\"-w\":\"string\",\"--branch\":\"string\",\"--commit\":\"string\",\"--created\":\"string\",\"--event\":\"string\",\"--status\":\"string\",\"--user\":\"string\",\"--workflow\":\"string\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh run view", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-a\":\"number\",\"-j\":\"string\",\"-v\":\"none\",\"--attempt\":\"number\",\"--exit-status\":\"none\",\"--job\":\"string\",\"--log\":\"none\",\"--log-failed\":\"none\",\"--verbose\":\"none\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh search code", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"--extension\":\"string\",\"--filename\":\"string\",\"--language\":\"string\",\"--match\":\"string\",\"--owner\":\"string\",\"--size\":\"string\"}"))
        put("gh search commits", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"--author\":\"string\",\"--author-date\":\"string\",\"--author-email\":\"string\",\"--author-name\":\"string\",\"--committer\":\"string\",\"--committer-date\":\"string\",\"--committer-email\":\"string\",\"--committer-name\":\"string\",\"--hash\":\"string\",\"--merge\":\"none\",\"--order\":\"string\",\"--owner\":\"string\",\"--parent\":\"string\",\"--sort\":\"string\",\"--tree\":\"string\",\"--visibility\":\"string\"}"))
        put("gh search issues", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"--app\":\"string\",\"--assignee\":\"string\",\"--author\":\"string\",\"--closed\":\"string\",\"--commenter\":\"string\",\"--comments\":\"string\",\"--created\":\"string\",\"--include-prs\":\"none\",\"--interactions\":\"string\",\"--involves\":\"string\",\"--label\":\"string\",\"--language\":\"string\",\"--locked\":\"none\",\"--match\":\"string\",\"--mentions\":\"string\",\"--milestone\":\"string\",\"--no-assignee\":\"none\",\"--no-label\":\"none\",\"--no-milestone\":\"none\",\"--no-project\":\"none\",\"--order\":\"string\",\"--owner\":\"string\",\"--project\":\"string\",\"--reactions\":\"string\",\"--sort\":\"string\",\"--state\":\"string\",\"--team-mentions\":\"string\",\"--updated\":\"string\",\"--visibility\":\"string\"}"))
        put("gh search prs", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"-B\":\"string\",\"-H\":\"string\",\"--app\":\"string\",\"--assignee\":\"string\",\"--author\":\"string\",\"--base\":\"string\",\"--checks\":\"string\",\"--closed\":\"string\",\"--commenter\":\"string\",\"--comments\":\"string\",\"--created\":\"string\",\"--draft\":\"none\",\"--head\":\"string\",\"--interactions\":\"string\",\"--involves\":\"string\",\"--label\":\"string\",\"--language\":\"string\",\"--locked\":\"none\",\"--match\":\"string\",\"--mentions\":\"string\",\"--merged\":\"none\",\"--merged-at\":\"string\",\"--milestone\":\"string\",\"--no-assignee\":\"none\",\"--no-label\":\"none\",\"--no-milestone\":\"none\",\"--no-project\":\"none\",\"--order\":\"string\",\"--owner\":\"string\",\"--project\":\"string\",\"--reactions\":\"string\",\"--review\":\"string\",\"--review-requested\":\"string\",\"--reviewed-by\":\"string\",\"--sort\":\"string\",\"--state\":\"string\",\"--team-mentions\":\"string\",\"--updated\":\"string\",\"--visibility\":\"string\"}"))
        put("gh search repos", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"--archived\":\"none\",\"--created\":\"string\",\"--followers\":\"string\",\"--forks\":\"string\",\"--good-first-issues\":\"string\",\"--help-wanted-issues\":\"string\",\"--include-forks\":\"string\",\"--language\":\"string\",\"--license\":\"string\",\"--match\":\"string\",\"--number-topics\":\"string\",\"--order\":\"string\",\"--owner\":\"string\",\"--size\":\"string\",\"--sort\":\"string\",\"--stars\":\"string\",\"--topic\":\"string\",\"--updated\":\"string\",\"--visibility\":\"string\"}"))
        put("gh workflow list", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"--json\":\"string\",\"-L\":\"number\",\"--limit\":\"number\",\"-a\":\"none\",\"--all\":\"none\"}", callback = Callbacks::ghCommandIsDangerous))
        put("gh workflow view", Policy(safeFlagsJson = "{\"-R\":\"string\",\"--repo\":\"string\",\"-r\":\"string\",\"-y\":\"none\",\"--ref\":\"string\",\"--yaml\":\"none\"}", callback = Callbacks::ghCommandIsDangerous))
    }

    private val ALLOW_ANY_ARG_COMMANDS: Set<String> = setOf(
        "basename", "cal", "cat", "cmp", "column",
        "comm", "cut", "df", "diff", "dirname",
        "du", "expand", "expr", "false", "fmt",
        "fold", "free", "groups", "head", "hexdump",
        "id", "locale", "nl", "nproc", "numfmt",
        "od", "paste", "pr", "readlink", "realpath",
        "rev", "seq", "sleep", "stat", "strings",
        "tac", "tail", "tr", "true", "tsort",
        "type", "uname", "unexpand", "uptime", "wc",
        "which",
    )

    private val ALLOW_ANY_ARG_PREFIXES: List<List<String>> = listOf(
        listOf("docker", "images"),
        listOf("docker", "ps"),
    )

    private val GIT_GLOBAL_DANGEROUS_FLAGS: Set<String> = setOf(
        "-c", "-C", "--attr-source", "--bare", "--config-env",
        "--exec-path", "--git-dir", "--namespace", "--shallow-file", "--super-prefix",
        "--work-tree",
    )

    private val GIT_GLOBAL_NO_VALUE_FLAGS: Set<String> = setOf("--no-pager", "--paginate")

    private val GIT_GLOBAL_VALUE_FLAGS: Set<String> = setOf()

    // ══════════════════════════════════════════════════════════════
    //  公开入口
    // ══════════════════════════════════════════════════════════════

    /**
     * 判断一条完整命令行是否「纯只读」。
     *
     * 判定为只读的条件（全部满足）：
     * 1. 解析无错误
     * 2. 无动态词（`$(...)`、反引号、未引用 glob 都可能藏副作用）
     * 3. 无不支持的语法（子 shell / 复合语句 / 函数定义）
     * 4. 至少有一个命令
     * 5. 每个命令都 `== true`
     */
    fun isReadonlyCommand(commandText: String): Boolean {
        val text = commandText.trim()
        if (text.isEmpty()) return false
        val analysis = try {
            BashCommandAnalyzer.analyze(text)
        } catch (e: Throwable) {
            return false
        }
        if (analysis.hasParseErrors) return false
        if (analysis.hasDynamicWords) return false
        if (analysis.hasUnsupportedSyntax) return false
        if (analysis.commands.isEmpty()) return false
        return analysis.commands.all { evaluate(it) == true }
    }

    /**
     * 带理由的判定（给权限系统用，方便写进拒绝消息）。
     */
    fun classify(commandText: String): Verdict {
        val text = commandText.trim()
        if (text.isEmpty()) return Verdict(false, "空命令")

        val analysis = try {
            BashCommandAnalyzer.analyze(text)
        } catch (e: Throwable) {
            return Verdict(false, "解析失败：${e.message}")
        }

        if (analysis.hasParseErrors) {
            val types = analysis.unsupportedNodeTypes.joinToString(", ").ifEmpty { "未知" }
            return Verdict(false, "命令含无法解析的语法（$types）")
        }
        if (analysis.hasUnsupportedSyntax) {
            val types = analysis.unsupportedNodeTypes.joinToString(", ").ifEmpty { "未知" }
            return Verdict(false, "命令含不支持的语法结构（$types）")
        }
        if (analysis.hasDynamicWords) {
            return Verdict(false, "命令含动态内容（\$(...)、反引号或 glob）")
        }
        if (analysis.commands.isEmpty()) {
            return Verdict(false, "未解析出任何命令")
        }

        for (c in analysis.commands) {
            val r = evaluate(c)
            if (r != true) {
                val name = c.commandText.ifEmpty { c.name ?: "?" }
                return Verdict(false, "命令「$name」${if (r == false) "含写操作" else "无法确认是否只读"}")
            }
        }
        return Verdict(true, null)
    }

    /** 判定结果 */
    data class Verdict(val readonly: Boolean, val reason: String?)

    // ══════════════════════════════════════════════════════════════
    //  单命令判定（三态）
    // ══════════════════════════════════════════════════════════════

    /**
     * 判定单个命令是否只读。
     *
     * @return true = 确认只读；false = 确认写；null = 无法判定
     */
    private fun evaluate(cmd: BashCommandAnalyzer.CommandInvocation): Boolean? {
        // 环境变量赋值：只允许白名单内的（防止 `LD_PRELOAD=x cmd` 这类注入）
        if (!areEnvAssignmentsAllowed(cmd)) return false
        // 重定向：只允许 `<` / `<<` / `<<<` / `>&N` / `/dev/null`
        if (!areRedirectsAllowed(cmd)) return false

        // 剥掉 command / builtin / noglob 包装
        val argv = stripSafeCommandWrappers(cmd.argv)
        if (argv.isEmpty()) return false

        // 任一 argv 是 Windows UNC 路径 → 拒绝（对齐 CLI 的 `argv.some(isUnsafeWindowsUncPath)`）
        if (argv.any { isUnsafeWindowsUncPath(it) }) return false

        // git 单独处理（子命令表）
        if (argv[0] == "git") {
            return isGitReadOnlyCommand(argv)
        }

        // 直接判定（find / printf / history / arch / ifconfig / docker ps 等）
        val direct = evaluateDirectReadonlyArgv(argv)
        if (direct != null) return direct

        val name = argv[0]

        // ⚠ 顺序关键：`READONLY_ALLOW_ANY_ARG_COMMANDS` 必须在策略表**之前**判断。
        //
        // 对齐 CLI 的 `evaluateBashReadonlyPolicy`：
        //   ... → evaluateReadonlyPrefixPolicy → ALLOW_ANY_ARG → 策略表
        //
        // 为什么重要：`tail` / `head` / `wc` 这些**同时在两个表里** ——
        // 策略表里有一条带 safeFlags 的定义，但 allowAnyArg 集合里也有它。
        // CLI 先查 allowAnyArg 直接放行，策略表那条永远走不到。
        // 若顺序反了，`tail -f f.txt`（`-f` 不在 flag 表里）会被误判为非只读。
        // 实测确认：CLI 的 `isArgvAllowedByPolicy(["tail","-f","f.txt"], ...)` 返回 false，
        // 但 `evaluateBashReadonlyPolicy` 返回 true —— 正是被 allowAnyArg 短路了。
        if (name in ALLOW_ANY_ARG_COMMANDS) return true
        for (prefix in ALLOW_ANY_ARG_PREFIXES) {
            if (argv.size >= prefix.size && prefix.indices.all { argv[it] == prefix[it] }) {
                return !Callbacks.hasDangerousDockerOption(argv)
            }
        }

        // 多词命令（`docker inspect` / `gh pr view`）—— 长的优先
        for ((prefix, policy) in multiwordPoliciesSorted) {
            val words = prefix.split(' ')
            if (!words.all { argv.getOrNull(words.indexOf(it)) == it }) continue
            // 前缀匹配成功
            if (policy.callback?.invoke(argv.drop(words.size)) == true) return false
            if (!isArgvAllowedByPolicy(argv, policy, name, words.size).let { it == true }) return false
            if (policy.regex != null && !Regex(policy.regex).containsMatchIn(cmd.commandText)) return false
            return true
        }

        // 普通命令策略表
        val policy = COMMAND_POLICIES[name]
        if (policy != null) {
            // cd 带超过 1 个参数（如 `cd a b`）→ 拒绝（对齐 CLI 特例）
            if (name == "cd" && argv.size > 2) return false
            if (policy.callback?.invoke(argv.drop(1)) == true) return false
            if (isArgvAllowedByPolicy(argv, policy, name, 1) != true) return false
            if (policy.regex != null && !Regex(policy.regex).containsMatchIn(cmd.commandText)) return false
            return true
        }

        // 未知命令 → 无法判定
        return null
    }

    /** 多词策略按前缀词数降序（长前缀优先，避免 `git remote` 抢先于 `git remote show`） */
    private val multiwordPoliciesSorted: List<Pair<String, Policy>> by lazy {
        MULTIWORD_POLICIES.entries.sortedByDescending { it.key.split(' ').size }.map { it.key to it.value }
    }

    // ══════════════════════════════════════════════════════════════
    //  argv 校验
    // ══════════════════════════════════════════════════════════════

    private fun isArgvAllowedByPolicy(
        argv: List<String>,
        policy: Policy,
        commandName: String,
        startIndex: Int,
    ): Boolean? {
        if (argv.isEmpty()) return false
        if (policy.allowAnyArgs) return true
        if (policy.commandOnly) return argv.size == startIndex
        if (policy.safeFlagsJson != null) {
            return flagsAndPositionalsAllowed(argv, policy, commandName, startIndex)
        }
        return null
    }

    /**
     * flag + 位置参数校验（逐行对齐 CLI 的 `flagsAndPositionalsAllowed`）。
     *
     * 覆盖的形态：
     * ```
     *   -l                     纯开关
     *   -n 5                   独立值
     *   --limit=5              内联值（= 形式）
     *   -I{}                   短 flag 紧贴值（parseShortFlagWithAttachedValue）
     *   -la                    短 flag 簇（每个字母都得是 none 类）
     *   -5                     head/tail 的紧凑计数（-5 == -n 5）
     *   --                     之后全是位置参数
     * ```
     */
    private fun flagsAndPositionalsAllowed(argv: List<String>, policy: Policy, commandName: String, startIndex: Int): Boolean? {
        val safeFlags = policy.safeFlags
        var index = startIndex
        while (index < argv.size) {
            val word = argv[index]
            if (word.isEmpty()) {
                index++
                continue
            }

            // xargs 特例：第一个非 flag 词必须是白名单目标命令
            if (commandName == "xargs" && (!word.startsWith("-") || word == "--")) {
                val targetCommand = if (word == "--") argv.getOrNull(index + 1) else word
                if (targetCommand == null || targetCommand !in Callbacks.XARGS_TARGET_COMMANDS) return false
                break
            }

            if (word == "--") {
                if (!policy.respectsDoubleDash) {
                    index++
                    continue
                }
                break
            }

            // head/tail 的 `-5` 紧凑计数（无条件允许，对齐 CLI isHeadTailCompactCountFlag）
            if ((commandName == "head" || commandName == "tail") && Regex("^-\\d+$").matches(word)) {
                index++
                continue
            }
            // 策略显式声明的紧凑数字计数（如 git log -5）
            if (policy.allowCompactNumericCountFlag && Regex("^-\\d+$").matches(word)) {
                index++
                continue
            }

            if (word.startsWith("-") && word.length > 1 && OPTION_PATTERN.containsMatchIn(word)) {
                val parsed = parseOptionWord(word)
                val kind = safeFlags[parsed.flag]
                if (kind == null) {
                    // 短 flag 紧贴值：`-I{}` → flag=-I, value={}
                    val attached = parseShortFlagWithAttachedValue(word, safeFlags)
                    if (attached != null) {
                        if (!isOptionLikeStringValueAllowed(attached.value, commandName, attached.flag)) return false
                        if (!matchesFlagValueKind(attached.value, attached.kind)) return false
                        index++
                        continue
                    }
                    // 短 flag 簇：`-la` → -l + -a，每个都必须是 none 类
                    if (isShortFlagClusterAllowed(parsed.flag, safeFlags)) {
                        index++
                        continue
                    }
                    return false
                }
                if (kind == SafeFlagValue.NONE) {
                    if (parsed.hasInlineValue) return false
                    index++
                    continue
                }
                if (kind == SafeFlagValue.OPTIONAL_STRING) {
                    // 只拦截独立 argv 里看起来像 option 的字符串，避免误吞下一段参数。
                    index++
                    continue
                }
                val value = if (parsed.hasInlineValue) parsed.inlineValue else argv.getOrNull(index + 1)
                if (value == null) return false
                if (kind == SafeFlagValue.STRING && !parsed.hasInlineValue &&
                    !isOptionLikeStringValueAllowed(value, commandName, parsed.flag)
                ) {
                    return false
                }
                if (!matchesFlagValueKind(value, kind)) return false
                index += if (parsed.hasInlineValue) 1 else 2
                continue
            }
            index++
        }
        return true
    }

    private class ParsedOption(val flag: String, val hasInlineValue: Boolean, val inlineValue: String)

    private fun parseOptionWord(word: String): ParsedOption {
        val eq = word.indexOf('=')
        if (eq < 0) return ParsedOption(word, false, "")
        return ParsedOption(word.substring(0, eq), true, word.substring(eq + 1))
    }

    private class AttachedValue(val flag: String, val kind: SafeFlagValue, val value: String)

    /** `-I{}` / `-n5` 这类「短 flag 紧贴值」 */
    private fun parseShortFlagWithAttachedValue(word: String, safeFlags: Map<String, SafeFlagValue>): AttachedValue? {
        if (!word.startsWith("-") || word.startsWith("--") || word.length <= 2) return null
        val flag = word.substring(0, 2)
        val kind = safeFlags[flag] ?: return null
        if (kind == SafeFlagValue.NONE) return null
        return AttachedValue(flag, kind, word.substring(2))
    }

    /** `-la` 这类短 flag 簇：拆开后每个都得是 none 类 */
    private fun isShortFlagClusterAllowed(flag: String, safeFlags: Map<String, SafeFlagValue>): Boolean {
        if (!flag.startsWith("-") || flag.startsWith("--") || flag.length <= 2) return false
        for (ch in flag.substring(1)) {
            if (safeFlags["-$ch"] != SafeFlagValue.NONE) return false
        }
        return true
    }

    /** 值不能是「看起来像选项」的字符串（避免 `--sort -x` 被当成值） */
    private fun isOptionLikeStringValueAllowed(value: String, commandName: String, flag: String): Boolean {
        if (!value.startsWith("-") || value.length <= 1 || !OPTION_PATTERN.containsMatchIn(value)) return true
        return commandName == "git" && flag == "--sort" && Regex("^-[a-zA-Z]").containsMatchIn(value)
    }

    private fun matchesFlagValueKind(value: String, kind: SafeFlagValue): Boolean = when (kind) {
        SafeFlagValue.NONE -> false
        SafeFlagValue.NUMBER -> value.isNotEmpty() && value.all { it.isDigit() }
        SafeFlagValue.OPTIONAL_STRING, SafeFlagValue.STRING -> true
        SafeFlagValue.CHAR -> value.length == 1
        SafeFlagValue.BRACES -> value == "{}"
        SafeFlagValue.EOF -> value == "EOF"
    }

    /** 对齐 CLI flags.mjs 的 `OPTION_PATTERN = /^-[a-zA-Z0-9_-]/` */
    private val OPTION_PATTERN = Regex("^-[a-zA-Z0-9_-]")

    // ══════════════════════════════════════════════════════════════
    //  直接判定（不查 flag 表）
    // ══════════════════════════════════════════════════════════════

    private val FIND_WRITE_OPTIONS = setOf(
        "-delete", "-exec", "-execdir", "-files0-from", "-fls",
        "-fprint", "-fprint0", "-fprintf", "-ok", "-okdir",
    )

    private val FIND_VALUE_OPTIONS = setOf(
        "-Bmin", "-Bnewer", "-Btime", "-D", "-amin", "-anewer", "-atime",
        "-cmin", "-cnewer", "-context", "-ctime", "-f", "-flags", "-fstype",
        "-gid", "-group", "-ilname", "-iname", "-inum", "-ipath", "-iregex",
        "-iwholename", "-lname", "-links", "-maxdepth", "-mindepth", "-mmin",
        "-mnewer", "-mtime", "-name", "-newer", "-path", "-perm", "-printf",
        "-regex", "-regextype", "-samefile", "-size", "-type", "-used",
        "-user", "-wholename", "-xattrname", "-xtype", "-uid",
    )

    private val READONLY_EXACT_ARGV_COMMANDS = listOf(
        listOf("ip", "addr"),
        listOf("node", "-v"),
        listOf("node", "--version"),
        listOf("python", "--version"),
        listOf("python3", "--version"),
    )

    /** @return true / false / null（null = 不适用，交给后面的逻辑） */
    private fun evaluateDirectReadonlyArgv(argv: List<String>): Boolean? {
        if (READONLY_EXACT_ARGV_COMMANDS.any { it.size == argv.size && it.indices.all { i -> argv[i] == it[i] } }) {
            return true
        }
        if (argv[0] == "docker" && ALLOW_ANY_ARG_PREFIXES.any { p -> argv.size >= p.size && p.indices.all { argv[it] == p[it] } }) {
            return !Callbacks.hasDangerousDockerOption(argv)
        }
        if (argv[0] == "printf") return isSafePrintfArgv(argv)
        if (argv[0] == "find") return isSafeFindArgv(argv)
        if (argv[0] == "history") {
            return argv.size == 1 || (argv.size == 2 && argv[1].all { it.isDigit() })
        }
        if (argv[0] == "arch") {
            return argv.size == 1 || (argv.size == 2 && (argv[1] == "-h" || argv[1] == "--help"))
        }
        if (argv[0] == "ifconfig") {
            return argv.size == 1 || (argv.size == 2 && argv[1].firstOrNull()?.isLetter() == true)
        }
        return null
    }

    private fun isSafeFindArgv(argv: List<String>): Boolean {
        var index = 1
        while (index < argv.size) {
            val word = argv[index]
            if (word in FIND_WRITE_OPTIONS) return false
            if (word in FIND_VALUE_OPTIONS || Regex("^-newer[aBcm][aBcmt]$").matches(word)) {
                index += 2
                continue
            }
            index++
        }
        return true
    }

    private fun isSafePrintfArgv(argv: List<String>): Boolean {
        if (argv.size > 1 && argv[1].startsWith("-") && argv[1] != "--") return false
        val formatIndex = if (argv.size > 1 && argv[1] == "--") 2 else 1
        val format = argv.getOrNull(formatIndex) ?: ""
        if (format.contains('$')) return false
        val normalizedFormat = format.replace("%%", "")
        if (Regex("%[^%a-zA-Z]*(?:hh|ll|[lLhqjzZt])?\\\\[0-7xX]").containsMatchIn(normalizedFormat)) return false
        if (Regex("\\\\[uU]").containsMatchIn(normalizedFormat)) return false
        val numericFormat = Regex("%[-+ 0#']*[0-9.*]*(?:hh|ll|[lLhqjzZt])?[diouxXeEfFgGaAn]").containsMatchIn(normalizedFormat)
        if (numericFormat || Regex("%[^%a-zA-Z]*\\*").containsMatchIn(normalizedFormat)) {
            for (i in formatIndex + 1 until argv.size) {
                val value = argv[i]
                if (value.contains('[') || value.contains('`') || value.contains("\$(")) return false
                if (!Regex("^[-+]?(0[xX][0-9a-fA-F]+|[0-9]+#[0-9a-zA-Z]+|[0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?)$").matches(value)) {
                    return false
                }
            }
        }
        return true
    }

    // ══════════════════════════════════════════════════════════════
    //  git 判定
    // ══════════════════════════════════════════════════════════════

    private fun isGitReadOnlyCommand(argv: List<String>): Boolean? {
        val normalized = normalizeGitArgv(argv) ?: return false
        for ((prefix, policy) in gitPoliciesSorted) {
            val words = prefix.split(' ')
            if (!words.indices.all { normalized.getOrNull(it) == words[it] }) continue
            if (policy.callback?.invoke(normalized.drop(words.size)) == true) return false
            return isArgvAllowedByPolicy(normalized, policy, "git", words.size)
        }
        return false
    }

    private val gitPoliciesSorted: List<Pair<String, Policy>> by lazy {
        GIT_SUBCOMMAND_POLICIES.entries.sortedByDescending { it.key.split(' ').size }.map { it.key to it.value }
    }

    /** 剥离 git 全局选项，返回 `["git", 子命令, ...]`；遇到危险全局选项返回 null */
    private fun normalizeGitArgv(argv: List<String>): List<String>? {
        val normalized = mutableListOf("git")
        var index = 1
        while (index < argv.size) {
            val word = argv[index]
            if (word.isEmpty()) {
                index++
                continue
            }
            if (word in GIT_GLOBAL_NO_VALUE_FLAGS) {
                index++
                continue
            }
            if (hasDangerousGitGlobalOptionWord(word)) return null
            if (word in GIT_GLOBAL_VALUE_FLAGS) {
                index += 2
                if (index > argv.size) return null
                continue
            }
            if (word.startsWith("-")) return null
            normalized.addAll(argv.subList(index, argv.size))
            return normalized
        }
        return null
    }

    private fun hasDangerousGitGlobalOptionWord(word: String): Boolean {
        if (hasDangerousAttachedGitShortOptionWord(word)) return true
        if (word in GIT_GLOBAL_DANGEROUS_FLAGS) return true
        return GIT_GLOBAL_DANGEROUS_FLAGS.any { word.startsWith("$it=") }
    }

    private fun hasDangerousAttachedGitShortOptionWord(word: String): Boolean {
        return listOf("-c", "-C").any { flag ->
            word.length > flag.length && word.startsWith(flag) &&
                (flag == "-C" || word[flag.length] != '-')
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  环境变量 / 重定向
    // ══════════════════════════════════════════════════════════════

    private val SAFE_ENV_ASSIGNMENTS = setOf(
        "ANTHROPIC_API_KEY", "BLOCK_SIZE", "BLOCKSIZE", "CGO_ENABLED", "CHARSET",
        "CI", "CLICOLOR", "CLICOLOR_FORCE", "COLORTERM", "COLUMNS",
        "DEBIAN_FRONTEND", "FORCE_COLOR", "GCC_COLORS", "GIT_TERMINAL_PROMPT",
        "GO111MODULE", "GOARCH", "GOEXPERIMENT", "GOOS", "GREP_COLOR",
        "GREP_COLORS", "LANG", "LANGUAGE", "LC_ALL", "LC_CTYPE", "LC_TIME",
        "LINES", "LSCOLORS", "LS_COLORS", "NO_COLOR", "NODE_ENV",
        "PYTEST_DEBUG", "PYTEST_DISABLE_PLUGIN_AUTOLOAD", "PYTHONDONTWRITEBYTECODE",
        "PYTHONUNBUFFERED", "RUST_BACKTRACE", "RUST_LOG", "TERM", "TIME_STYLE", "TZ",
    )

    private fun areEnvAssignmentsAllowed(cmd: BashCommandAnalyzer.CommandInvocation): Boolean {
        return cmd.envAssignments.all { it.name.isNotEmpty() && it.name in SAFE_ENV_ASSIGNMENTS }
    }

    private val SAFE_INPUT_REDIRECTS = setOf("<", "<<", "<&", "<<<")

    private fun areRedirectsAllowed(cmd: BashCommandAnalyzer.CommandInvocation): Boolean {
        for (r in cmd.redirects) {
            if (Regex("^/dev/(?:tcp|udp)/").containsMatchIn(r.target)) return false
            // `>&1` / `2>&1`：fd 复制，安全
            if (r.operator == ">&" || r.operator == "<&") {
                val digits = r.target.removePrefix("&")
                if (digits.isNotEmpty() && digits.all { it.isDigit() }) continue
            }
            // 输出到 /dev/null（含 `>` `>>` `&>` `2>` 各种写法）
            if (r.target == "/dev/null") continue
            // 输入重定向：`<` `<<` `<<<` `<&`
            if (r.operator in SAFE_INPUT_REDIRECTS) {
                if (isUnsafeWindowsUncPath(r.target)) return false
                continue
            }
            return false
        }
        return true
    }

    private fun isUnsafeWindowsUncPath(value: String): Boolean {
        return value.startsWith("//") || value.startsWith("\\\\")
    }

    /** 剥掉 `command` / `builtin` / `noglob` 包装 */
    private fun stripSafeCommandWrappers(argv: List<String>): List<String> {
        var stripped = argv
        while (true) {
            if (stripped.firstOrNull() == "command") {
                var index = 1
                while (stripped.getOrNull(index) != null && Regex("^-p+$").matches(stripped[index])) index++
                if (stripped.getOrNull(index) == "--") index++
                if (index >= stripped.size || stripped[index].startsWith("-")) return stripped
                stripped = stripped.drop(index)
                continue
            }
            if (stripped.firstOrNull() == "builtin") {
                val index = if (stripped.getOrNull(1) == "--") 2 else 1
                if (index >= stripped.size) return stripped
                stripped = stripped.drop(index)
                continue
            }
            if (stripped.firstOrNull() == "noglob") {
                if (stripped.size <= 1) return stripped
                stripped = stripped.drop(1)
                continue
            }
            return stripped
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  flag 表常量（脚本生成，见简报）
    // ══════════════════════════════════════════════════════════════

    // （原 flag 常量段已内联进上方策略表，见生成脚本）

    // ══════════════════════════════════════════════════════════════
    //  flag 表解析
    // ══════════════════════════════════════════════════════════════

    private fun parseFlags(json: String?): Map<String, SafeFlagValue> {
        if (json.isNullOrEmpty() || json == "{}") return emptyMap()
        return try {
            val obj = org.json.JSONObject(json)
            val out = HashMap<String, SafeFlagValue>(obj.length() * 2)
            for (key in obj.keys()) {
                out[key] = when (obj.getString(key)) {
                    "none" -> SafeFlagValue.NONE
                    "string" -> SafeFlagValue.STRING
                    "optionalString" -> SafeFlagValue.OPTIONAL_STRING
                    "number" -> SafeFlagValue.NUMBER
                    "char" -> SafeFlagValue.CHAR
                    "{}" -> SafeFlagValue.BRACES
                    "EOF" -> SafeFlagValue.EOF
                    else -> SafeFlagValue.NONE
                }
            }
            out
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  危险判定回调（19 个，逐一移植自 callbacks.mjs）
    // ══════════════════════════════════════════════════════════════

    internal object Callbacks {

        // ── sed ─────────────────────────────────────────────────
        fun isSedInPlaceOption(word: String): Boolean =
            word.startsWith("-i") || word == "--in-place" || word.startsWith("--in-place=")

        private fun sedScriptWritesToFile(script: String): Boolean =
            Regex("(?:^|[;{\\n])\\s*(?:[0-9,\$!+~-]+)?\\s*w(?:\\s|\$)").containsMatchIn(script)

        fun sedCommandIsDangerous(args: List<String>): Boolean {
            var firstScriptSeen = false
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (arg.isEmpty()) { index++; continue }
                if (isSedInPlaceOption(arg)) return true
                if (arg == "-e" || arg == "--expression") {
                    if (sedScriptWritesToFile(args.getOrNull(index + 1) ?: "")) return true
                    index += 2
                    continue
                }
                if (arg.startsWith("--expression=")) {
                    if (sedScriptWritesToFile(arg.substring("--expression=".length))) return true
                    index++
                    continue
                }
                if (arg == "-l" || arg == "--line-length") { index += 2; continue }
                if (arg.startsWith("--line-length=")) { index++; continue }
                if (arg == "--") {
                    return sedScriptWritesToFile(args.getOrNull(index + 1) ?: "")
                }
                if (!arg.startsWith("-") && !firstScriptSeen) {
                    firstScriptSeen = true
                    if (sedScriptWritesToFile(arg)) return true
                }
                index++
            }
            return false
        }

        // ── jq ──────────────────────────────────────────────────
        private fun isJqDangerousOption(word: String): Boolean =
            word == "-f" || word.startsWith("-f") ||
                word == "-L" || word.startsWith("-L") ||
                word == "--argfile" || word.startsWith("--argfile=") ||
                word == "--from-file" || word.startsWith("--from-file=") ||
                word == "--library-path" || word.startsWith("--library-path=") ||
                word == "--rawfile" || word.startsWith("--rawfile=") ||
                word == "--run-tests" || word.startsWith("--run-tests=") ||
                word == "--slurpfile" || word.startsWith("--slurpfile=")

        private fun jqFilterIsDangerous(filter: String): Boolean =
            Regex("\\\$ENV\\b").containsMatchIn(filter) ||
                Regex("(^|[^A-Za-z0-9_\$.])env(?=\$|[^A-Za-z0-9_])").containsMatchIn(filter) ||
                Regex("(^|[^A-Za-z0-9_])(?:include|import)(?=\$|[^A-Za-z0-9_])").containsMatchIn(filter)

        fun jqCommandIsDangerous(args: List<String>): Boolean {
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (arg.isEmpty()) { index++; continue }
                if (isJqDangerousOption(arg)) return true
                if (arg == "--") return jqFilterIsDangerous(args.getOrNull(index + 1) ?: "")
                if (arg == "--indent") { index += 2; continue }
                if (arg.startsWith("-")) { index++; continue }
                return jqFilterIsDangerous(arg)
            }
            return false
        }

        // ── date ────────────────────────────────────────────────
        fun dateCommandIsDangerous(args: List<String>): Boolean {
            val valueFlags = setOf("-d", "--date", "-r", "--reference", "--rfc-3339")
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (arg.startsWith("--") && arg.contains('=')) { index++; continue }
                if (arg.startsWith("-")) {
                    index += if (arg in valueFlags) 2 else 1
                    continue
                }
                if (!arg.startsWith("+")) return true
                index++
            }
            return false
        }

        // ── ps ──────────────────────────────────────────────────
        fun psCommandIsDangerous(args: List<String>): Boolean =
            args.any { !it.startsWith("-") && Regex("^[a-zA-Z]*e[a-zA-Z]*$").matches(it) }

        // ── pyright ─────────────────────────────────────────────
        fun pyrightCommandIsDangerous(args: List<String>): Boolean =
            args.any { it == "--watch" || it == "-w" }

        // ── man ─────────────────────────────────────────────────
        fun manCommandIsDangerous(args: List<String>): Boolean {
            val aproposFlags = setOf("-k", "-f", "--apropos", "--whatis")
            val valueFlags = setOf("-S", "-s")
            var isApropos = false
            var afterDoubleDash = false
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (!afterDoubleDash && arg == "--") { afterDoubleDash = true; index++; continue }
                if (!afterDoubleDash && arg.startsWith("-") && arg != "-") {
                    if (arg in aproposFlags) isApropos = true
                    index += if (arg in valueFlags) 2 else 1
                    continue
                }
                afterDoubleDash = true
                if (arg.contains('/')) return !isApropos
                index++
            }
            return false
        }

        // ── lsof ────────────────────────────────────────────────
        fun lsofCommandIsDangerous(args: List<String>): Boolean {
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (arg == "+m" || arg.startsWith("+m")) return true
                if (Regex("^-[a-zA-Z]*i\\S*@").containsMatchIn(arg)) {
                    val host = arg.substringAfter('@').substringBefore(':')
                    if (host.any { it.isLetter() }) return true
                }
                if (Regex("^-[a-zA-Z]*i$").matches(arg)) {
                    val next = args.getOrNull(index + 1) ?: ""
                    if (next.contains('@')) {
                        val host = next.substringAfter('@').substringBefore(':')
                        if (host.any { it.isLetter() }) return true
                    }
                }
                index++
            }
            return false
        }

        // ── tput ────────────────────────────────────────────────
        private val TPUT_DANGEROUS_CAPABILITIES = setOf(
            "clear", "flash", "if", "init", "iprog", "is1", "is2", "is3",
            "mc0", "mc4", "mc5", "mc5i", "mc5p", "pfkey", "pfloc", "pfx",
            "pfxl", "reset", "rf", "rmcup", "rs1", "rs2", "rs3", "smcup",
        )

        fun tputCommandIsDangerous(args: List<String>): Boolean {
            var afterDoubleDash = false
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (arg == "--") { afterDoubleDash = true; index++; continue }
                if (!afterDoubleDash && arg.startsWith("-")) {
                    if (arg == "-S") return true
                    if (!arg.startsWith("--") && arg.length > 2 && arg.contains('S')) return true
                    index += if (arg == "-T") 2 else 1
                    continue
                }
                if (arg in TPUT_DANGEROUS_CAPABILITIES) return true
                index++
            }
            return false
        }

        // ── ss ──────────────────────────────────────────────────
        private val SS_KEYWORDS = Regex("^(dst|src|dport|sport|and|or|not|eq|ne|ge|le|gt|lt|autobound|state|exclude|dev|fwmark|cgroup)$")
        private val SS_VALUE_KEYWORDS = Regex("^(state|exclude|dport|sport|dev|fwmark|cgroup)$")
        private val SS_VALUE_FLAGS = Regex("^(-f|--family|-A|--query|--socket)$")

        fun ssCommandIsDangerous(args: List<String>): Boolean {
            val positional = mutableListOf<String>()
            var afterDoubleDash = false
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (!afterDoubleDash && arg == "--") { afterDoubleDash = true; index++; continue }
                if (!afterDoubleDash && arg.startsWith("-")) {
                    index += if (SS_VALUE_FLAGS.matches(arg)) 2 else 1
                    continue
                }
                positional.add(arg)
                index++
            }
            val tokens = positional.joinToString(" ").split(Regex("[\\s()=!<>&|,]+")).filter { it.isNotEmpty() }
            var skipValue = false
            for (token in tokens) {
                if (skipValue) { skipValue = false; continue }
                if (SS_KEYWORDS.matches(token)) {
                    skipValue = SS_VALUE_KEYWORDS.matches(token)
                    continue
                }
                if (Regex("[g-zG-Z]").containsMatchIn(token) ||
                    (Regex("[a-fA-F]").containsMatchIn(token) && (token.contains('.') || !token.contains(':')))
                ) {
                    return true
                }
            }
            return false
        }

        // ── test ────────────────────────────────────────────────
        private val TEST_NUMERIC_OPERATORS = setOf("-eq", "-ne", "-lt", "-le", "-gt", "-ge")
        private val TEST_SAFE_NUMBER = Regex("^-?(0[xX][0-9a-fA-F]+|[0-9]+#[0-9a-zA-Z]+|[0-9]+)$")

        fun testCommandIsDangerous(args: List<String>): Boolean {
            if (args.any { it == "-v" || it == "-R" || it == "-a" || it == "-o" || it.contains('[') }) return true
            for (index in args.indices) {
                val arg = args[index]
                if (arg in TEST_NUMERIC_OPERATORS) {
                    for (value in listOf(args.getOrNull(index - 1), args.getOrNull(index + 1))) {
                        if (value != null && !TEST_SAFE_NUMBER.matches(value)) return true
                    }
                }
                if (arg == "-t") {
                    val value = args.getOrNull(index + 1)
                    if (value != null && !TEST_SAFE_NUMBER.matches(value)) return true
                }
            }
            return false
        }

        // ── xargs ───────────────────────────────────────────────
        internal val XARGS_TARGET_COMMANDS = setOf(
            "echo", "printf", "wc", "grep", "egrep", "fgrep", "head", "tail",
        )
        private val XARGS_VALUE_FLAGS = setOf("-I", "-n", "-P", "-L", "-s", "-E", "-d")

        fun xargsCommandIsDangerous(args: List<String>): Boolean {
            var index = 0
            while (index < args.size) {
                var arg = args[index]
                if (arg.isEmpty()) { index++; continue }
                if (arg == "--" && index + 1 < args.size) {
                    index++
                    arg = args[index]
                }
                if (arg.startsWith("-") && arg != "-") {
                    if (arg in XARGS_VALUE_FLAGS) index += 2 else index++
                    continue
                }
                return arg !in XARGS_TARGET_COMMANDS
            }
            return false
        }

        // ── git 系列 ────────────────────────────────────────────
        fun gitRevisionFormatCommandIsDangerous(args: List<String>): Boolean {
            for (index in args.indices) {
                val arg = args[index]
                val value = if (arg.contains('=')) arg.substringAfter('=') else args.getOrNull(index + 1)
                if ((arg == "--format" || arg == "--pretty" ||
                        arg.startsWith("--format=") || arg.startsWith("--pretty=")) && !value.isNullOrEmpty()
                ) {
                    if (Regex("%[-+ ]?G|%\\(\\*?signature").containsMatchIn(value)) return true
                }
            }
            return false
        }

        fun gitReflogCommandIsDangerous(args: List<String>): Boolean {
            val allowedSubcommands = setOf("show", "list")
            val dangerousSubcommands = setOf("expire", "delete", "exists", "drop", "write")
            val firstPositional = args.firstOrNull { it.isNotEmpty() && !it.startsWith("-") }
            if (firstPositional != null && firstPositional !in allowedSubcommands) return true
            return args.any { it in dangerousSubcommands }
        }

        fun gitLsRemoteCommandIsDangerous(args: List<String>): Boolean {
            var afterDoubleDash = false
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (!afterDoubleDash && arg == "--") { afterDoubleDash = true; index++; continue }
                if (!afterDoubleDash && (arg.startsWith("-") || arg.isEmpty())) {
                    index += if (arg == "--sort") 2 else 1
                    continue
                }
                return true
            }
            return false
        }

        /**
         * `git remote`（不带 show 子命令）—— CLI 侧是**内联箭头函数**：
         * `args => args.some(arg => arg !== '-v' && arg !== '--verbose')`
         *
         * 语义：只允许裸 `git remote` 或带 `-v` / `--verbose`（列远程仓库）。
         * 出现任何其他参数（如 `add` / `remove` / `set-url`）都判危险。
         */
        fun gitRemoteCommandIsDangerous(args: List<String>): Boolean =
            args.any { it != "-v" && it != "--verbose" }

        fun gitRemoteShowCommandIsDangerous(args: List<String>): Boolean {
            val doubleDashIndex = args.indexOf("--")
            val optionArgs = if (doubleDashIndex == -1) args else args.subList(0, doubleDashIndex)
            val positionalArgs = (
                (if (doubleDashIndex == -1) emptyList() else args.subList(doubleDashIndex + 1, args.size)) +
                    optionArgs.filter { it != "-n" }
                )
            if ("-n" !in optionArgs) return true
            if (positionalArgs.size != 1) return true
            return !Regex("^[a-zA-Z0-9_][a-zA-Z0-9_-]*$").matches(positionalArgs[0])
        }

        fun gitTagCommandIsDangerous(args: List<String>): Boolean =
            gitListLikeCommandIsDangerous(
                args,
                setOf("--contains", "--no-contains", "--merged", "--no-merged", "--points-at", "--sort", "--format", "-n"),
            )

        fun gitBranchCommandIsDangerous(args: List<String>): Boolean =
            gitListLikeCommandIsDangerous(args, setOf("--contains", "--no-contains", "--points-at", "--sort"))

        private fun gitListLikeCommandIsDangerous(args: List<String>, valueFlags: Set<String>): Boolean {
            var hasList = false
            var afterDoubleDash = false
            var previousFlag = ""
            var index = 0
            while (index < args.size) {
                val arg = args[index]
                if (arg.isEmpty()) { index++; continue }
                if (arg == "--" && !afterDoubleDash) {
                    afterDoubleDash = true
                    previousFlag = ""
                    index++
                    continue
                }
                if (!afterDoubleDash && arg.startsWith("-")) {
                    if (arg == "--list" || arg == "-l" || (arg.startsWith("-") && !arg.startsWith("--") && arg.substring(1).contains('l'))) {
                        hasList = true
                    }
                    previousFlag = if (arg.contains('=')) arg.substringBefore('=') else arg
                    index += if (!arg.contains('=') && previousFlag in valueFlags) 2 else 1
                    continue
                }
                if (!hasList && previousFlag != "--merged" && previousFlag != "--no-merged") return true
                index++
            }
            return false
        }

        // ── gh ──────────────────────────────────────────────────
        fun ghCommandIsDangerous(args: List<String>): Boolean {
            for (arg in args) {
                if (arg.isEmpty()) continue
                var value = arg
                if (arg.startsWith("-")) {
                    val eq = arg.indexOf('=')
                    if (eq == -1) continue
                    value = arg.substring(eq + 1)
                    if (value.isEmpty()) continue
                }
                if (!value.contains('/') && !value.contains("://") && !value.contains('@')) continue
                if (value.contains("://") || value.contains('@')) return true
                if (value.count { it == '/' } >= 2) return true
            }
            return false
        }

        // ── docker ──────────────────────────────────────────────
        private val DOCKER_DANGEROUS_GLOBAL_FLAGS = listOf(
            "-H", "-c", "--config", "--context", "--host",
            "--tlscacert", "--tlscert", "--tlskey",
        )
        private val DOCKER_DANGEROUS_SHORT_FLAGS = DOCKER_DANGEROUS_GLOBAL_FLAGS
            .filter { it.length == 2 }.map { it[1] }.toSet()

        fun hasDangerousDockerOption(args: List<String>): Boolean {
            return args.any { arg ->
                if (DOCKER_DANGEROUS_GLOBAL_FLAGS.any { flag ->
                        arg == flag || arg.startsWith("$flag=") ||
                            (flag.length == 2 && arg.length > 2 && arg.startsWith(flag))
                    }
                ) {
                    return@any true
                }
                val m = Regex("^-([A-Za-z]+)").find(arg)
                val shortFlags = m?.groupValues?.get(1)
                if (shortFlags != null && shortFlags.length >= 2) {
                    for (flag in shortFlags) if (flag in DOCKER_DANGEROUS_SHORT_FLAGS) return@any true
                }
                false
            }
        }

        fun dockerCommandIsDangerous(args: List<String>): Boolean = hasDangerousDockerOption(args)
    }
}
