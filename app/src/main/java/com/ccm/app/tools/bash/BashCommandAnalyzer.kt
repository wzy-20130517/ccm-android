package com.ccm.app.tools.bash

import org.antlr.runtime.ANTLRStringStream
import org.antlr.runtime.CommonToken
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTree
import org.antlr.runtime.tree.Tree
import org.gentoo.libbash.java_libbashLexer
import org.gentoo.libbash.java_libbashParser

/**
 * Bash 命令结构分析器 —— 把命令行解析成 AST，再遍历成结构化事实。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么不用正则
 * ══════════════════════════════════════════════════════════════
 *
 * plan 模式原本用 `\b(rm|mv|cp|...)\b` 正则拦写操作，可被这些绕过：
 *
 * ```
 *   /bin/rm x          —— 带路径前缀，\brm\b 匹配不到 /bin/rm 的 rm？能匹配，
 *                          但 /bin/rm 的写法说明「路径变体」这条路是通的
 *   bash -c "rm x"     —— rm 藏在字符串里，正则会命中（误报），
 *                          但 `bash -c "$CMD"` 就漏了
 *   find . -delete     —— 没有 rm 字样，正则完全漏掉
 *   ls && rm x         —— 能命中，但 `ls;rm x`（无空格）会漏
 *   $(...)             —— 动态内容，正则无法判断展开后是什么
 * ```
 *
 * 正则的根本问题是**看不见结构**：它不知道 `rm` 是命令名还是参数，
 * 不知道命令被 `&&` 串了几个，也不知道哪里是字符串字面量。
 * AST 解析后这些都成了明确的事实。
 *
 * ══════════════════════════════════════════════════════════════
 *  与 CLI 侧的关系
 * ══════════════════════════════════════════════════════════════
 *
 * CLI（`~/claude-code-mobile/core/permission/bash-ast/`）用 unbash（JS 解析器）
 * 得到 BashCommandAnalysis，本文件是 **APK 侧的等价实现**，但底层解析器不同：
 * 用的是 NitorCreations/bashparser（ANTLR3 生成的完整 bash 语法，Java）。
 *
 * 两边的 AST 结构完全不同，所以遍历层必须自己写：
 *
 * ```
 *   unbash（CLI）                bashparser（APK）
 *   ─────────────                ────────────────
 *   { type: "Command" }          COMMAND 节点
 *   { type: "AndOr" }            && / || 节点（二元，不是列表！）
 *   { type: "Pipeline" }         | 节点（二元）
 *   word.value（已去引号）        STRING 节点的叶子文本拼接 + 源文本切片
 * ```
 *
 * 【关键差异】bashparser 把 `-la` 拆成叶子 `-` + `la`，把 `/bin/rm` 拆成
 * `/` `bin` `/` `rm`，把 `'a b'` 拆成带引号的叶子 `'a b'`。所以取值方式是
 * **递归拼接叶子文本**，而不是读父节点。
 *
 * 【引号信息】bashparser 保留了引号字面量（SINGLE_QUOTED_STRING 叶子文本含引号），
 * 所以「这个词是不是被引用过」可以从叶子类型 + 源文本切片判断 —— 这正是
 * 区分 `ls *.txt`（glob，动态）和 `ls '*.txt'`（字面量，安全）的关键。
 *
 * ══════════════════════════════════════════════════════════════
 *  线程安全
 * ══════════════════════════════════════════════════════════════
 *
 * 无共享可变状态（每次调用新建 lexer/parser），可多线程并发调用。
 */
object BashCommandAnalyzer {

    /** 单次解析的字符上限（对齐 CLI 的 MAX_BASH_PARSE_LENGTH） */
    private const val MAX_PARSE_LENGTH = 10_000

    // ══════════════════════════════════════════════════════════════
    //  ANTLR token 类型常量（取自 java_libbashParser 的 public static final int）
    // ══════════════════════════════════════════════════════════════
    //
    // 【为什么硬编码数字而不是引用 java_libbashParser.XXX】
    // 那些常量是 public static final int，可以直接引用 —— 但这样做的话
    // 每个引用点都会生成一次静态字段读取。这里只在遍历时比较，用局部常量
    // 更直观，且**编译期就内联成数字**（Java 的 static final int 是编译期常量）。
    //
    // 数字来源：java_libbashParser.java 顶部的常量定义，2026-10-11 核对。

    private const val T_LIST = 28
    private const val T_STRING = 29
    private const val T_COMMAND = 30
    private const val T_FILE_DESCRIPTOR = 31
    private const val T_REDIR = 33
    private const val T_COMMAND_SUB = 9
    private const val T_VAR_REF = 26
    private const val T_SUBSHELL = 12
    private const val T_CURRENT_SHELL = 13
    private const val T_DOUBLE_QUOTED_STRING = 56
    private const val T_SINGLE_QUOTED_STRING = 57
    private const val T_ARITHMETIC_EXPRESSION = 35
    private const val T_ARITHMETIC = 36
    private const val T_ARRAY = 6
    private const val T_FUNCTION = 94
    private const val T_IF_STATEMENT = 19
    private const val T_KEYWORD_TEST = 37
    private const val T_BUILTIN_TEST = 38
    private const val T_VARIABLE_DEFINITIONS = 58
    private const val T_PROCESS_SUBSTITUTION = 25
    private const val T_BRACE_EXP = 8
    private const val T_CASE_COMMAND = 10
    private const val T_FOR = 129
    private const val T_WHILE = 141
    private const val T_SELECT = 137
    private const val T_EQUALS = 124
    private const val T_LOGICAND = 100
    private const val T_LOGICOR = 101
    private const val T_PIPE = 103
    private const val T_TIME = 104
    private const val T_BANG = 102
    private const val T_AMP = 98
    private const val T_TILDE = 145
    private const val T_SLASH = 154
    private const val T_DOT = 158
    private const val T_SQUOTE = 175
    private const val T_DQUOTE = 126
    private const val T_QMARK = 173
    private const val T_TIMES = 172
    private const val T_LSQUARE = 122
    private const val T_RSQUARE = 123
    private const val T_COMMAND_SUBSTITUTION_PAREN = 176
    private const val T_COMMAND_SUBSTITUTION_TICK = 177
    private const val T_ANSI_C_QUOTING = 4
    /** `<<<` here-string（是命令的直接子节点，不是 REDIR） */
    private const val T_HERE_STRING_OP = 112
    /** 条件表达式容器（`test` / `[` / `[[`） */
    private const val T_COMPOUND_COND = 14

    // ══════════════════════════════════════════════════════════════
    //  公开数据结构
    // ══════════════════════════════════════════════════════════════

    /** 重定向（`>` `>>` `<` `2>&1` 等） */
    data class Redirect(
        /** 文件描述符（`2>` 里的 2）；未指定为 null */
        val fileDescriptor: Int?,
        /** 操作符：`>` `>>` `<` `<<` `>&` 等 */
        val operator: String,
        /** 目标：文件名、`/dev/null`、`&1` 等 */
        val target: String,
    )

    /** 环境变量赋值前缀（`A=1 ls` 里的 A=1） */
    data class EnvAssignment(val name: String, val value: String)

    /** 一个命令调用（`ls -la` 或 `git status`） */
    data class CommandInvocation(
        /** 词面值列表（已按 bash 规则去引号，但保留动态标记） */
        val argv: List<String>,
        /** 命令名（去掉路径前缀，如 `/bin/rm` → `rm`）；无法判定时为 null */
        val name: String?,
        /** 该命令的原始文本（用于错误提示） */
        val commandText: String,
        /** 环境变量赋值前缀 */
        val envAssignments: List<EnvAssignment>,
        /** 是否含动态词（`$VAR`、`$(...)`、反引号、未引用 glob） */
        val hasDynamicWords: Boolean,
        /** 重定向列表 */
        val redirects: List<Redirect>,
        /** 该命令之前的操作符（`&&` / `||` / `|` / `sequence`）；第一个为 null */
        val operatorBefore: String?,
    )

    /** 整条命令行的分析结果 */
    data class Analysis(
        val commands: List<CommandInvocation>,
        /**
         * 是否存在任何**动态执行**的词。
         *
         * ⚠ 语义与直觉不同（对齐 CLI 的 `hasDynamicWords`）：**glob 不算**。
         * 只有这些算：`$VAR` / `${...}` 参数展开、`$(...)` / 反引号命令替换、
         * `$((...))` 算术、`{a,b}` brace、进程替换、ANSI-C 引用。
         *
         * 为什么 glob 不算：`ls *.txt` 展开成一堆文件名，但**命令本身没变**，
         * 策略表照样能判（ls 是 allowAnyArgs）。而 `$VAR` 展开后可能是
         * 完全不同的命令/参数，策略表看不见，只能拒绝。
         * 参考 CLI 实测：`ls *.txt` 判只读，`echo $HOME` 判非只读。
         */
        val hasDynamicWords: Boolean,
        /** 是否有语法错误 */
        val hasParseErrors: Boolean,
        /** 是否含重定向 */
        val hasRedirects: Boolean,
        /** 是否含不支持的语法（子 shell / 复合语句 / 函数定义等） */
        val hasUnsupportedSyntax: Boolean,
        /** 不支持的具体节点类型（诊断用） */
        val unsupportedNodeTypes: Set<String>,
    ) {
        /** 整体是否「结构上可静态判定」—— 解析无错、无动态词、无不支持语法 */
        val isStaticallyAnalyzable: Boolean
            get() = !hasParseErrors && !hasDynamicWords && !hasUnsupportedSyntax
    }

    // ══════════════════════════════════════════════════════════════
    //  主入口
    // ══════════════════════════════════════════════════════════════

    /**
     * 分析一条完整命令行。
     *
     * **不会抛异常**：任何解析失败都收敛成 `hasParseErrors = true`。
     * 调用方只需检查 `hasParseErrors` / `hasUnsupportedSyntax` / `hasDynamicWords`，
     * 三者任一为真就该保守处理（拒绝）。
     */
    fun analyze(command: String): Analysis {
        val src = command.trim()
        if (src.isEmpty()) {
            return Analysis(emptyList(), false, hasParseErrors = false, false, false, emptySet())
        }
        if (src.length > MAX_PARSE_LENGTH) {
            return Analysis(emptyList(), false, hasParseErrors = true, false, false, emptySet())
        }

        val tree: CommonTree
        val syntaxErrors: Int
        try {
            val lexer = java_libbashLexer(ANTLRStringStream(src))
            val parser = java_libbashParser(CommonTokenStream(lexer))
            val start = parser.start()
            tree = start.tree as? CommonTree
                ?: return Analysis(emptyList(), false, true, false, false, emptySet())
            syntaxErrors = parser.numberOfSyntaxErrors
        } catch (e: Throwable) {
            // 极端畸形输入（lexer/parser 内部异常）→ 保守拒绝
            return Analysis(emptyList(), false, hasParseErrors = true, false, false, emptySet())
        }

        val ctx = Ctx(src)
        val root = Ctx.unwrap(tree)
        if (root != null) {
            collectNode(root, ctx, null)
        }

        return Analysis(
            commands = ctx.commands,
            hasDynamicWords = ctx.hasDynamicWords,
            hasParseErrors = syntaxErrors > 0,
            hasRedirects = ctx.hasRedirects,
            hasUnsupportedSyntax = ctx.unsupported.isNotEmpty(),
            unsupportedNodeTypes = ctx.unsupported,
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  遍历上下文
    // ══════════════════════════════════════════════════════════════

    private class Ctx(val src: String) {
        val commands = mutableListOf<CommandInvocation>()
        var hasDynamicWords = false
        var hasRedirects = false
        val unsupported = mutableSetOf<String>()

        fun markUnsupported(node: Tree) {
            unsupported.add(nodeText(node))
        }

        companion object {
            /** 跳过 ANTLR 的 nil 根节点，返回第一个真实子节点 */
            fun unwrap(t: Tree?): Tree? {
                var cur = t ?: return null
                while (cur.childCount == 1 && cur.getChild(0) != null && isNil(cur)) {
                    cur = cur.getChild(0)
                }
                return cur
            }

            /** ANTLR 虚拟根（`<DOWN>` 之类的 nil token） */
            private fun isNil(t: Tree): Boolean = t.type == 0 || t.type == org.antlr.runtime.Token.INVALID_TOKEN_TYPE
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  节点遍历
    // ══════════════════════════════════════════════════════════════

    /**
     * 收集节点里的命令。
     *
     * @param operatorBefore 该节点之前的操作符（`&&` / `||` / `|` / `sequence`）
     */
    private fun collectNode(node: Tree, ctx: Ctx, operatorBefore: String?) {
        when (node.type) {
            T_LIST -> collectList(node, ctx, operatorBefore)

            // && / || —— bashparser 是**二元节点**（左子树、右子树），不是列表。
            // 这是与 unbash 最大的结构差异：unbash 的 AndOr 带 commands[] + operators[]，
            // bashparser 是嵌套的二元树 `a && b && c` = `&&(&&(a,b),c)`。
            T_LOGICAND -> collectBinary(node, ctx, "&&", operatorBefore)
            T_LOGICOR -> collectBinary(node, ctx, "||", operatorBefore)

            // 管道同理是二元的
            T_PIPE -> collectBinary(node, ctx, "|", operatorBefore)

            // 命令分隔符 `;` —— bashparser 把 `ls; pwd` 表示成 LIST 下两个 COMMAND，
            // 中间没有显式的 `;` 节点（LIST 的子节点直接是两条命令）。
            // 所以「同层多个子节点」就意味着 `;` 分隔，operatorBefore 记作 "sequence"。
            T_COMMAND -> collectSimpleCommand(node, ctx, operatorBefore)

            // ⚠ `test` / `[` / `[[` 会被 bashparser 当成**条件表达式语法**
            // （COMPOUND_COND → BUILTIN_TEST / KEYWORD_TEST），而不是普通命令。
            // 但策略表里它们就是普通只读命令（有 flag 表 + 危险回调），
            // 所以这里把它们**还原成命令调用**，否则 `test -f x` 会解析成 0 个命令。
            T_COMPOUND_COND -> collectCompoundCondition(node, ctx, operatorBefore)

            // 不支持的结构 —— 保守拒绝
            T_SUBSHELL, T_CURRENT_SHELL, T_ARITHMETIC_EXPRESSION, T_ARITHMETIC,
            T_ARRAY, T_FUNCTION, T_IF_STATEMENT, T_KEYWORD_TEST, T_BUILTIN_TEST,
            T_VARIABLE_DEFINITIONS, T_PROCESS_SUBSTITUTION, T_BRACE_EXP,
            T_CASE_COMMAND, T_FOR, T_WHILE, T_SELECT ->
                ctx.markUnsupported(node)

            else -> {
                // 未知节点：如果它含子节点就递归看看，但标记为不支持（保守）
                if (node.childCount > 0) {
                    ctx.markUnsupported(node)
                }
            }
        }
    }

    /** LIST 节点：顺序排列的子节点，等价于 `;` 分隔 */
    private fun collectList(node: Tree, ctx: Ctx, operatorBefore: String?) {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val childOp = if (i == 0) operatorBefore else "sequence"
            collectNode(child, ctx, childOp)
        }
    }

    /** 二元操作符节点（&& / || / |）：左右子树各是一个命令（或嵌套的二元节点） */
    private fun collectBinary(node: Tree, ctx: Ctx, op: String, operatorBefore: String?) {
        val left = node.getChild(0)
        val right = node.getChild(1)
        if (left == null) {
            ctx.markUnsupported(node)
            return
        }
        collectNode(left, ctx, operatorBefore)
        if (right == null) {
            // `ls |` 这类残缺表达式 —— 语法错误已在 parser 层被计入
            return
        }
        collectNode(right, ctx, op)
    }

    /**
     * 条件表达式（`test` / `[` / `[[`）。
     *
     * bashparser 的 AST 形态（实测）：
     * ```
     *   test -n 5   → COMPOUND_COND → BUILTIN_TEST → [L 'n'(带子)] → STRING '5'
     *   test f.txt  → COMPOUND_COND → BUILTIN_TEST → STRING 'f.txt'
     *   [ -f x ]    → COMPOUND_COND → BUILTIN_TEST → [L 'f'(带子)] → STRING 'x'
     *   [[ -f x ]]  → COMPOUND_COND → KEYWORD_TEST → [L 'f'(带子)] → STRING 'x'
     * ```
     *
     * 【为什么特殊处理】策略表里 `test` / `[` / `[[` 是普通只读命令，
     * 带 31 条安全 flag 和一个危险回调。若当「不支持的语法」拒绝，
     * 所有 `test -f x` 都会误判（CLI 实测判只读）。
     *
     * 【还原难点】flag 的短横被吃掉了 —— `-n` 变成叶子 `n`（带子节点 5），
     * `-f` 变成叶子 `f`（带子节点 x）。所以这里要**补回 `-` 前缀**。
     */
    private fun collectCompoundCondition(node: Tree, ctx: Ctx, operatorBefore: String?) {
        val inner = node.getChild(0)
        if (inner == null || (inner.type != T_BUILTIN_TEST && inner.type != T_KEYWORD_TEST)) {
            ctx.markUnsupported(node)
            return
        }
        // 命令名：BUILTIN_TEST 可能是 `test` 也可能是 `[`，看源文本
        val srcText = buildCommandText(node, ctx)
        val name = when {
            srcText.startsWith("[[") -> "[["
            srcText.startsWith("[") -> "["
            else -> "test"
        }
        val argv = mutableListOf(name)
        for (i in 0 until inner.childCount) {
            val c = inner.getChild(i) ?: continue
            when (c.type) {
                T_STRING -> buildWord(c, ctx)?.let { argv.add(it.value) }
                else -> {
                    // 操作符叶子：`-n` 的 `n`、`-f` 的 `f`、`-eq` 的 `eq` 等
                    // 单字符/双字符且带子节点 → 是带值的操作符
                    val opText = c.text ?: ""
                    if (opText.isNotEmpty()) {
                        argv.add("-" + opText)
                        // 操作符后面的值
                        for (j in 0 until c.childCount) {
                            val v = c.getChild(j) ?: continue
                            if (v.type == T_STRING) buildWord(v, ctx)?.let { argv.add(it.value) }
                            else argv.add(v.text ?: "")
                        }
                    }
                }
            }
        }
        ctx.commands.add(
            CommandInvocation(
                argv = argv,
                name = name,
                commandText = srcText,
                envAssignments = emptyList(),
                hasDynamicWords = false,
                redirects = emptyList(),
                operatorBefore = operatorBefore,
            )
        )
    }

    // ══════════════════════════════════════════════════════════════
    //  简单命令
    // ══════════════════════════════════════════════════════════════

    private fun collectSimpleCommand(node: Tree, ctx: Ctx, operatorBefore: String?) {
        val words = mutableListOf<Word>()
        val redirects = mutableListOf<Redirect>()
        val envAssignments = mutableListOf<EnvAssignment>()
        var cmdDynamic = false

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            when (child.type) {
                T_STRING -> {
                    val w = buildWord(child, ctx)
                    if (w != null) words.add(w)
                }

                T_REDIR -> {
                    val r = buildRedirect(child, ctx)
                    if (r != null) {
                        redirects.add(r)
                        ctx.hasRedirects = true
                        // 重定向目标是动态词（`> $FILE`）→ 整体动态
                        if (redirectTargetIsDynamic(child)) ctx.hasDynamicWords = true
                    } else {
                        ctx.hasDynamicWords = true
                    }
                }

                // 环境变量赋值前缀：`A=1 ls` 的 `A=1` 是 COMMAND 的直接子节点（type=EQUALS）
                T_EQUALS -> {
                    val name = child.getChild(0)?.text ?: ""
                    val value = buildWord(child.getChild(1) ?: child, ctx)?.value ?: ""
                    envAssignments.add(EnvAssignment(name, value))
                }

                // `time` / `!` 前缀关键字（叶子，不是 STRING 包装）
                T_TIME, T_BANG -> {
                    val text = child.text ?: ""
                    if (text.isNotEmpty()) {
                        words.add(Word(text, dynamic = false, hasUnquotedGlob = false, quoted = false))
                    }
                }

                T_FILE_DESCRIPTOR -> {
                    // 单独出现的 fd（少见）—— 忽略，不视为命令词
                }

                // ⚠ `test` / `[` / `[[` 的 COMPOUND_COND 是 **COMMAND 的子节点**
                // （AST: LIST → COMMAND → COMPOUND_COND → BUILTIN_TEST），
                // 所以这里也要处理，否则会掉进 else 分支被标成 unsupported。
                T_COMPOUND_COND -> {
                    collectCompoundCondition(child, ctx, operatorBefore)
                    return
                }

                // `<<<` here-string：**不是 REDIR 节点**，而是命令的直接子节点
                // （type=112，实测 `ls <<< here`）。按重定向处理（策略层允许 `<<<`）。
                T_HERE_STRING_OP -> {
                    val targetWord = child.getChild(0)
                    val t = if (targetWord != null) buildWord(targetWord, ctx)?.value ?: "" else ""
                    redirects.add(Redirect(null, "<<<", t))
                    ctx.hasRedirects = true
                    if (targetWord != null && nodeHasDynamic(targetWord)) ctx.hasDynamicWords = true
                }

                else -> {
                    // 命令里出现非预期节点 → 保守标记
                    ctx.markUnsupported(child)
                }
            }
        }

        for (w in words) if (w.dynamic) cmdDynamic = true

        // ⚠ 必须同步到整体标记：CLI 侧的只读判定读的是 analysis.hasDynamicWords，
        // 只把 cmdDynamic 塞进 CommandInvocation 是不够的（2026-10-11 踩过：
        // `echo $(date)` 因此被判成只读 —— COMMAND_SUB 节点确实被识别成动态词了，
        // 但标记没往上冒泡，整体判定看不到）。
        if (cmdDynamic) ctx.hasDynamicWords = true

        // 命令名 = 第一个非赋值词；argv 也从这个词开始算
        // （bash 语义：赋值前缀不属于 argv，但我们要把它单独记录）
        val nameWord = words.firstOrNull()
        val name = nameWord?.let { commandBasename(it) }

        val argv = words.map { it.value }

        // 命令文本：从第一个词的源位置到最后一个词/重定向的结束
        val commandText = buildCommandText(node, ctx)

        ctx.commands.add(
            CommandInvocation(
                argv = argv,
                name = name,
                commandText = commandText,
                envAssignments = envAssignments,
                hasDynamicWords = cmdDynamic,
                redirects = redirects,
                operatorBefore = operatorBefore,
            )
        )
    }

    /**
     * 从一个 STRING 节点构造 Word —— 递归拼接叶子文本，同时判定动态性。
     *
     * bashparser 的 STRING 结构（实测）：
     * ```
     *   ls          → [L] 'ls'
     *   -la         → [L] '-' [L] 'la'
     *   /bin/rm     → [L] '/' [L] 'bin' [L] '/' [L] 'rm'
     *   'a b'       → [L] (SINGLE_QUOTED_STRING) "'a b'"    ← 含引号字面量！
     *   "c d"       → [N] DOUBLE_QUOTED_STRING: [L] 'c' [L] ' '
     *   $HOME       → [N] VAR_REF: [L] 'HOME'
     *   $(date)     → [N] COMMAND_SUB: [L] '$(date)'
     *   *.txt       → [L] '*' [L] '.' [L] 'txt'
     * ```
     *
     * 【引号处理】单引号叶子（SINGLE_QUOTED_STRING）的文本**含引号**，
     * 直接拿去比较会失败（`'a b'` != `a b`），所以要去掉。
     * 双引号是独立节点（DOUBLE_QUOTED_STRING），其子叶子的文本不含引号。
     */
    private fun buildWord(node: Tree, ctx: Ctx): Word? {
        val sb = StringBuilder()
        var dynamic = false
        var hasUnquotedGlob = false
        var quoted = false

        fun walk(t: Tree, inSingleQuote: Boolean, inDoubleQuote: Boolean) {
            when (t.type) {
                // 单引号字符串：叶子文本含引号，剥掉
                T_SINGLE_QUOTED_STRING -> {
                    quoted = true
                    val raw = t.text ?: ""
                    // 叶子可能是 SINGLE_QUOTED_STRING_TOKEN 且文本是 `'a b'`（含两端引号）
                    sb.append(stripOuterQuotes(raw, '\''))
                    return
                }

                T_DOUBLE_QUOTED_STRING -> {
                    quoted = true
                    // 双引号节点：递归子节点，内部仍可展开变量 → 动态
                    for (i in 0 until t.childCount) {
                        val c = t.getChild(i) ?: continue
                        walk(c, inSingleQuote = false, inDoubleQuote = true)
                    }
                    return
                }

                T_VAR_REF -> {
                    dynamic = true
                    // ⚠ 节点自身的 text 是 token 名（"VAR_REF"），真实内容在子叶子里。
                    // 直接用 t.text 会让 argv 里出现 "VAR_REF" 这种噪音。
                    val inner = if (t.childCount > 0) t.getChild(0)?.text ?: "" else t.text ?: ""
                    sb.append('$').append(inner)
                    return
                }

                T_COMMAND_SUB, T_COMMAND_SUBSTITUTION_PAREN, T_COMMAND_SUBSTITUTION_TICK -> {
                    dynamic = true
                    // 子叶子文本已含 `$(...)` / `` `...` `` 外壳，直接拼
                    if (t.childCount > 0) {
                        for (i in 0 until t.childCount) sb.append(t.getChild(i)?.text ?: "")
                    } else {
                        sb.append(t.text ?: "")
                    }
                    return
                }

                T_ARITHMETIC, T_ARITHMETIC_EXPRESSION, T_PROCESS_SUBSTITUTION, T_BRACE_EXP -> {
                    dynamic = true
                    sb.append(t.text ?: "")
                    return
                }

                // 未加引号的 glob 字符：**只记 hasUnquotedGlob，不算 dynamic**。
                // 依据 CLI 的 partHasDynamicExecution：Literal / SingleQuoted /
                // AnsiCQuoted 都返回 false，glob 归在 hasUnquotedGlob 字段里，
                // 由策略层（而非解析层）决定要不要拒。实测 CLI：`ls *.txt` 判只读。
                T_TIMES, T_QMARK, T_LSQUARE -> {
                    if (!inSingleQuote) hasUnquotedGlob = true
                    sb.append(t.text ?: "")
                    return
                }

                // `~` 同理不算动态（CLI 实测 `cd ~` / `ls ~/x` 都判只读）
                T_TILDE -> {
                    if (!inSingleQuote) hasUnquotedGlob = true
                    sb.append(t.text ?: "")
                    return
                }

                // 引号字符本身（在双引号内出现的 " 或转义）—— 跳过
                T_DQUOTE, T_SQUOTE -> {
                    return
                }

                else -> {
                    if (t.childCount == 0) {
                        // 叶子：拼接文本
                        sb.append(t.text ?: "")
                    } else {
                        // 容器节点（如 STRING 内嵌 STRING）—— 递归
                        for (i in 0 until t.childCount) {
                            val c = t.getChild(i) ?: continue
                            walk(c, inSingleQuote, inDoubleQuote)
                        }
                    }
                }
            }
        }

        // 顶层：先看是否直接是引号节点
        when (node.type) {
            T_SINGLE_QUOTED_STRING -> {
                quoted = true
                // 单引号节点本身可能有子叶子（SINGLE_QUOTED_STRING_TOKEN 含引号文本）
                if (node.childCount == 0) {
                    sb.append(stripOuterQuotes(node.text ?: "", '\''))
                } else {
                    // 递归：子叶子的文本含引号，统一剥
                    for (i in 0 until node.childCount) {
                        val c = node.getChild(i) ?: continue
                        if (c.childCount == 0) {
                            sb.append(stripOuterQuotes(c.text ?: "", '\''))
                        } else {
                            walk(c, inSingleQuote = true, inDoubleQuote = false)
                        }
                    }
                }
            }

            T_DOUBLE_QUOTED_STRING -> {
                quoted = true
                for (i in 0 until node.childCount) {
                    val c = node.getChild(i) ?: continue
                    walk(c, inSingleQuote = false, inDoubleQuote = true)
                }
            }

            else -> {
                // 混合词（如 `'a'"b"`）：逐子节点走
                if (node.childCount == 0) {
                    sb.append(node.text ?: "")
                } else {
                    for (i in 0 until node.childCount) {
                        val c = node.getChild(i) ?: continue
                        walk(c, inSingleQuote = false, inDoubleQuote = false)
                    }
                }
            }
        }

        val value = sb.toString()
        if (value.isEmpty() && !quoted && !dynamic) return null
        return Word(value, dynamic, hasUnquotedGlob, quoted)
    }

    /** 去掉字符串两端的引号（bashparser 的单引号叶子文本含引号） */
    private fun stripOuterQuotes(s: String, q: Char): String {
        if (s.length >= 2 && s.first() == q && s.last() == q) {
            return s.substring(1, s.length - 1)
        }
        return s
    }

    /** 命令名 = 最后一个路径段（`/bin/rm` → `rm`）；动态词返回 null */
    private fun commandBasename(w: Word): String? {
        if (w.dynamic) return null
        val v = w.value
        if (v.isEmpty()) return null
        val idx = v.lastIndexOf('/')
        return if (idx >= 0) v.substring(idx + 1) else v
    }

    /** 重定向目标是否含动态词 */
    private fun redirectTargetIsDynamic(redirNode: Tree): Boolean {
        // REDIR 的子节点：[fd?] operator [target]
        // target 是最后一个 STRING 子节点
        for (i in redirNode.childCount - 1 downTo 0) {
            val c = redirNode.getChild(i) ?: continue
            if (c.type == T_STRING) {
                return nodeHasDynamic(c)
            }
        }
        return false
    }

    private fun nodeHasDynamic(node: Tree): Boolean {
        when (node.type) {
            // ⚠ 只有「动态执行」才算（对齐 CLI partHasDynamicExecution）：
            // 参数展开 / 命令替换 / 算术 / 进程替换 / brace。
            // glob（TIMES/QMARK/LSQUARE）与 ~ **不算**。
            T_VAR_REF, T_COMMAND_SUB, T_COMMAND_SUBSTITUTION_PAREN, T_COMMAND_SUBSTITUTION_TICK,
            T_ARITHMETIC, T_ARITHMETIC_EXPRESSION, T_PROCESS_SUBSTITUTION, T_BRACE_EXP ->
                return true
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            if (nodeHasDynamic(c)) return true
        }
        return false
    }

    /**
     * 构造重定向对象。
     *
     * REDIR 的 children 形态（实测）：
     * ```
     *   > out      → [L t=108] '>'   [STRING] 'out'
     *   >> out     → [L t=20]  '>>'  [STRING] 'out'      ← 组合操作符是 OP token
     *   2> err     → [L t=111] '2'   [L t=108] '>'  [STRING] 'err'
     *   2>&1       → [L t=111] '2'   [L t=20] '>&'  [FILE_DESCRIPTOR] '1'
     *   &> all     → [L t=20]  '&>'  [STRING] 'all'
     *   < in       → [L t=107] '<'   [STRING] 'in'
     *   <<< here   → [L t=112] '<<<' [STRING] 'here'   ← 注意：这是重定向**节点本身**，不是 REDIR
     * ```
     *
     * 【关键】单字符操作符（`>` `<`）是独立 token（108/107），
     * 而多字符组合（`>>` `>&` `&>` `<&`）统一是 **OP token（type=20）**，
     * 其 text 才是真实操作符。所以判定要用「文本是否是重定向符号」而非 type。
     */
    private fun buildRedirect(node: Tree, ctx: Ctx): Redirect? {
        var fd: Int? = null
        var op = ""
        var target = ""

        val children = (0 until node.childCount).mapNotNull { node.getChild(it) }

        // 找操作符：文本由 `>` `<` `&` 组成且含 `>` 或 `<`
        for (c in children) {
            val t = c.text ?: ""
            if (t.isNotEmpty() && t.all { it == '>' || it == '<' || it == '&' } && t.any { it == '>' || it == '<' }) {
                op = t
                break
            }
        }
        // 找 fd：纯数字叶子且不是 target
        val targetNode = children.lastOrNull { it.type == T_STRING || it.type == T_FILE_DESCRIPTOR }
        for (c in children) {
            if (c === targetNode) continue
            val t = c.text ?: ""
            if (t.length == 1 && t[0].isDigit()) {
                fd = t.toIntOrNull()
                break
            }
        }
        // target：FILE_DESCRIPTOR（`2>&1` 的 `1`）要还原成 `&1`，
        // 这样策略层的 `operator == ">&" && target 全数字` 分支才认得出来。
        //
        // ⚠ FILE_DESCRIPTOR 节点的 text 是 token 名（"FILE_DESCRIPTOR"），
        // 真实数字在它的子叶子里（实测 `2>&1` → FILE_DESCRIPTOR → [L t=111] '1'）。
        target = when {
            targetNode?.type == T_FILE_DESCRIPTOR -> {
                val inner = if (targetNode.childCount > 0) targetNode.getChild(0)?.text ?: "" else ""
                "&" + inner
            }
            targetNode != null -> buildWord(targetNode, ctx)?.value ?: ""
            else -> ""
        }

        if (op.isEmpty()) return null
        return Redirect(fd, op, target)
    }

    /** 命令的源文本（从第一个子节点的起始到最后一个子节点的结束） */
    private fun buildCommandText(node: Tree, ctx: Ctx): String {
        var minStart = Int.MAX_VALUE
        var maxStop = -1
        fun scan(t: Tree) {
            val tk = (t as? CommonTree)?.token as? CommonToken
            if (tk != null) {
                val s = tk.startIndex
                val e = tk.stopIndex
                if (s in 0 until Int.MAX_VALUE && e >= s) {
                    if (s < minStart) minStart = s
                    if (e > maxStop) maxStop = e
                }
            }
            for (i in 0 until t.childCount) t.getChild(i)?.let { scan(it) }
        }
        scan(node)
        if (minStart == Int.MAX_VALUE || maxStop < minStart) return ""
        if (maxStop >= ctx.src.length) maxStop = ctx.src.length - 1
        return ctx.src.substring(minStart, maxStop + 1)
    }

    /** 内部：带动态标记的词 */
    private data class Word(
        val value: String,
        val dynamic: Boolean,
        val hasUnquotedGlob: Boolean,
        val quoted: Boolean,
    )

    private fun nodeText(t: Tree): String = t.text ?: "?"
}
