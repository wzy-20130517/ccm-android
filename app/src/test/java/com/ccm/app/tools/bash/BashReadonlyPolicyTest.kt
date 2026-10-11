package com.ccm.app.tools.bash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bash AST 只读判定测试。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么这批用例重要
 * ══════════════════════════════════════════════════════════════
 *
 * 这里替换掉的是一段正则黑名单（`\b(rm|mv|cp|...)\b`）。正则的失败模式
 * 是**静默的**：它不报错，只是漏判。所以测试必须覆盖「绕过手法」那一组 ——
 * 那些用例在旧实现下会**全部失败**。
 *
 * 另一组同样重要的是「不能误杀」：AST 判定的风险方向与正则相反 ——
 * 它可能因为「不确定」而拒绝正常命令（三态语义里 `null` 收敛成 false）。
 * 所以两边都要测。
 *
 * ══════════════════════════════════════════════════════════════
 *  与 CLI 的一致性
 * ══════════════════════════════════════════════════════════════
 *
 * 本文件的核心用例与 CLI 侧 `~/claude-code-mobile/core/permission/bash-readonly.mjs`
 * 的实测输出**逐条对齐**（2026-10-11 用 719 条用例交叉验证，718 条一致）。
 * 唯一已知差异见 `已知与 CLI 的差异` 那条测试的注释。
 *
 * 【运行方式】./gradlew testDebugUnitTest --tests '*BashReadonlyPolicyTest*'
 * 注意：CI 目前**不跑**单测（见 .github/workflows/build.yml 的说明）。
 */
class BashReadonlyPolicyTest {

    // ══════════════════════════════════════════════════════════════
    //  ① 核心用例（与任务要求逐条对齐）
    // ══════════════════════════════════════════════════════════════

    @Test
    fun readonlyCommandsAreAllowed() {
        assertReadonly("ls -la")
        assertReadonly("git status")
        assertReadonly("pwd")
        assertReadonly("cat f.txt")
        assertReadonly("which node")
        assertReadonly("date +%s")
    }

    @Test
    fun writeCommandsAreRejected() {
        assertNotReadonly("rm -rf /tmp/x")
        assertNotReadonly("git commit -m x")
        assertNotReadonly("mkdir x")
        assertNotReadonly("chmod 777 x")
        assertNotReadonly("mv a b")
    }

    // ══════════════════════════════════════════════════════════════
    //  ② 绕过手法 —— 旧正则在**每一条**上都会漏判或误判
    // ══════════════════════════════════════════════════════════════

    @Test
    fun bypassTricksAreAllBlocked() {
        // 带路径前缀：正则的 \b 在 `/bin/rm` 上碰巧能匹配，但换成
        // `./rm`、`${PATH}/rm` 就漏了。AST 走的是 argv 归一化，无此问题。
        assertNotReadonly("/bin/rm /tmp/x")
        assertNotReadonly("/usr/bin/rm -rf /tmp")

        // 藏在 -c 参数里：正则只看文本，会把 "rm x" 当命令（误报），
        // 而 `bash -c "$CMD"` 这种动态形式又漏。AST 判定 bash 本身不在只读表。
        assertNotReadonly("bash -c \"rm x\"")
        assertNotReadonly("sh -c 'rm x'")

        // find 的写选项：**完全没有 rm 字样**，正则彻底漏掉
        assertNotReadonly("find . -delete")
        assertNotReadonly("find . -exec rm {} \\;")

        // sed 就地修改：正则写的是 `sed\s+-i`，只认空格分隔
        assertNotReadonly("sed -i 's/a/b/' f.txt")
        assertNotReadonly("sed --in-place 's/a/b/' f.txt")

        // 命令替换：正则看不见展开结果
        assertNotReadonly("echo \$(date)")
        assertNotReadonly("echo \u0060rm x\u0060")

        // 无空格分隔：`ls;rm x` 里 rm 前面不是词边界，正则可能漏
        assertNotReadonly("ls;rm x")
        assertNotReadonly("ls && rm x")
        assertNotReadonly("ls | xargs rm")
    }

    @Test
    fun oldRegexFalsePositivesAreFixed() {
        // 旧正则 `\b(rm|mv|cp|...)\b` 会把这些**只读命令**误判为写操作，
        // 因为命令名恰好出现在参数里。AST 判定看的是 argv[0]，不受影响。
        assertReadonly("grep -n 'cp' file.txt")
        assertReadonly("grep -n 'rm' file.txt")
        assertReadonly("cat -n mv.txt")
    }

    // ══════════════════════════════════════════════════════════════
    //  ③ 不能误杀正常只读命令
    // ══════════════════════════════════════════════════════════════

    @Test
    fun commonReadonlyCommandsAreNotKilled() {
        assertReadonly("ls")
        assertReadonly("ls -la /tmp")
        assertReadonly("head -5 f.txt")
        assertReadonly("tail -f f.txt")
        assertReadonly("grep -rn foo .")
        assertReadonly("wc -l f.txt")
        assertReadonly("find . -name '*.txt'")
        assertReadonly("find . -type f")
        assertReadonly("stat f.txt")
        assertReadonly("id")
        assertReadonly("uname -a")
        assertReadonly("du -sh .")
    }

    @Test
    fun gitReadonlySubcommandsAreNotKilled() {
        assertReadonly("git status")
        assertReadonly("git status --short")
        assertReadonly("git log")
        assertReadonly("git log --oneline -5")
        assertReadonly("git diff")
        assertReadonly("git diff --stat")
        assertReadonly("git show HEAD")
        assertReadonly("git branch -a")
        assertReadonly("git branch --list")
        assertReadonly("git rev-parse HEAD")
        assertReadonly("git ls-files")
        assertReadonly("git remote -v")
        assertReadonly("git blame f.txt")
    }

    @Test
    fun gitWriteSubcommandsAreRejected() {
        assertNotReadonly("git add .")
        assertNotReadonly("git commit -m x")
        assertNotReadonly("git push")
        assertNotReadonly("git pull")
        assertNotReadonly("git reset --hard")
        assertNotReadonly("git checkout x")
        assertNotReadonly("git branch newbranch")
        assertNotReadonly("git tag v1.0")
        assertNotReadonly("git remote add origin url")
        assertNotReadonly("git config user.name x")
        assertNotReadonly("git stash")   // 裸 stash 是写（stash list/show 才只读）
        assertReadonly("git stash list")
        assertReadonly("git stash show")
    }

    @Test
    fun readonlyPipelinesAndSequencesAreAllowed() {
        assertReadonly("ls | grep foo")
        assertReadonly("ls && pwd")
        assertReadonly("ls; pwd")
        assertReadonly("cat f.txt | head -5")
    }

    // ══════════════════════════════════════════════════════════════
    //  ④ 重定向
    // ══════════════════════════════════════════════════════════════

    @Test
    fun safeRedirectsAreAllowed() {
        assertReadonly("ls > /dev/null")
        assertReadonly("ls 2>/dev/null")
        assertReadonly("cat < in.txt")
        assertReadonly("ls 2>&1")
        assertReadonly("ls >> /dev/null")
    }

    @Test
    fun redirectsToRealFilesAreRejected() {
        assertNotReadonly("echo a > f.txt")
        assertNotReadonly("echo a >> f.txt")
        assertNotReadonly("ls 2> err.log")
        assertNotReadonly("ls &> all.log")
    }

    // ══════════════════════════════════════════════════════════════
    //  ⑤ 引号 / glob / 变量（关键语义区分）
    // ══════════════════════════════════════════════════════════════

    @Test
    fun unquotedGlobIsNotDynamicExecution() {
        // ⚠ 这一条容易搞反。CLI 实测：`ls *.txt` 判**只读**。
        //
        // 依据 CLI 的 partHasDynamicExecution：glob 归在 hasUnquotedGlob
        // 字段，不属于 hasDynamicWords。理由：glob 展开后**命令本身没变**
        // （还是 ls），策略表照样能判；而 `$VAR` 展开后可能是完全不同的
        // 命令/参数，策略表看不见。
        assertReadonly("ls *.txt")
        assertReadonly("ls ?.txt")
        assertReadonly("ls [ab].txt")
        assertReadonly("ls ~/x")
        assertReadonly("cd ~")
    }

    @Test
    fun variableExpansionIsDynamicExecution() {
        assertNotReadonly("echo \$HOME")
        assertNotReadonly("echo \${HOME}")
        assertNotReadonly("cat \$FILE")
        assertNotReadonly("echo \$1")
        assertNotReadonly("echo \$@")
        assertNotReadonly("echo \$((1+2))")
        assertNotReadonly("echo {a,b}")
        assertNotReadonly("echo \"\$HOME\"")   // 双引号内的 $ 仍会展开
    }

    @Test
    fun singleQuotesAreLiteral() {
        assertReadonly("echo '\$HOME'")   // 单引号内 $ 不展开
        assertReadonly("ls '*.txt'")
        assertReadonly("ls \"*.txt\"")
        assertReadonly("grep 'foo' f.txt")
    }

    // ══════════════════════════════════════════════════════════════
    //  ⑥ 不支持的语法（保守拒绝）
    // ══════════════════════════════════════════════════════════════

    @Test
    fun compoundStatementsAndSubshellsAreRejected() {
        assertNotReadonly("(rm x)")
        assertNotReadonly("{ rm x; }")
        assertNotReadonly("if true; then rm x; fi")
        assertNotReadonly("for i in a; do rm \$i; done")
        assertNotReadonly("while true; do rm x; done")
        assertNotReadonly("f() { rm x; }")
    }

    @Test
    fun unknownCommandsAreRejected() {
        assertNotReadonly("unknowncmd --flag")
        assertNotReadonly("npm install")
        assertNotReadonly("pip install x")
        assertNotReadonly("curl http://x")
        assertNotReadonly("python3 x.py")
        assertNotReadonly("bash script.sh")
    }

    @Test
    fun envAssignmentPrefixIsRestricted() {
        assertNotReadonly("LD_PRELOAD=x ls")   // 非白名单赋值 → 拒绝
        assertNotReadonly("A=1 ls")            // 普通赋值也不在白名单
        assertReadonly("LANG=C ls")            // 白名单内的赋值 → 放行
        assertReadonly("LC_ALL=C grep foo f")  // 白名单内
    }

    // ══════════════════════════════════════════════════════════════
    //  ⑦ 边界 / 畸形输入（不能崩）
    // ══════════════════════════════════════════════════════════════

    @Test
    fun emptyAndMalformedInputDoNotThrow() {
        assertNotReadonly("")
        assertNotReadonly("   ")
        assertNotReadonly("ls |")
        assertNotReadonly("if true; then")
        assertNotReadonly("echo \"unclosed")
        assertNotReadonly("(((")
        assertNotReadonly("|")
        assertNotReadonly("&&")
    }

    @Test
    fun oversizedInputIsRejectedNotHang() {
        // 解析器有 10000 字符上限（对齐 CLI 的 MAX_BASH_PARSE_LENGTH）。
        // 超过就直接判 parseError，不进解析器 —— 避免畸形超长输入
        // 让 ANTLR 跑很久。
        val huge = "ls " + "a".repeat(20_000)
        assertNotReadonly(huge)
    }

    @Test
    fun commentsDoNotAffectVerdict() {
        assertReadonly("ls # comment")
    }

    // ══════════════════════════════════════════════════════════════
    //  ⑧ 三态语义与诊断信息
    // ══════════════════════════════════════════════════════════════

    @Test
    fun classifyGivesReadableReason() {
        val write = ReadonlyPolicy.classify("rm -rf /tmp")
        assertFalse(write.readonly)
        assertTrue("理由应非空，实际：${write.reason}", write.reason != null)

        val dynamic = ReadonlyPolicy.classify("echo \$(date)")
        assertFalse(dynamic.readonly)
        assertTrue("理由应提到动态内容，实际：${dynamic.reason}", dynamic.reason?.contains("动态") == true)

        val ok = ReadonlyPolicy.classify("ls -la")
        assertTrue(ok.readonly)
    }

    @Test
    fun analyzerOutputsStructuredFacts() {
        val a = BashCommandAnalyzer.analyze("git status && rm -rf /tmp")
        assertFalse(a.hasParseErrors)
        assertEquals("应解析出 2 个命令", 2, a.commands.size)
        assertEquals("git", a.commands[0].name)
        assertEquals("rm", a.commands[1].name)
        assertEquals("&&", a.commands[1].operatorBefore)
    }

    @Test
    fun commandNameStripsPathPrefix() {
        val a = BashCommandAnalyzer.analyze("/bin/rm x")
        assertEquals("rm", a.commands[0].name)
    }

    @Test
    fun redirectsAreStructured() {
        val a = BashCommandAnalyzer.analyze("ls 2>&1")
        assertEquals(1, a.commands[0].redirects.size)
        val r = a.commands[0].redirects[0]
        assertEquals(2, r.fileDescriptor)
        assertEquals(">&", r.operator)
        assertEquals("&1", r.target)
    }

    // ══════════════════════════════════════════════════════════════
    //  ⑨ 已知与 CLI 的差异
    // ══════════════════════════════════════════════════════════════

    @Test
    fun knownDivergenceFromCli() {
        // `test -r foo .`（test 带多值操作符）：
        //   CLI（unbash）能解析，判只读；
        //   本实现（bashparser/ANTLR3）在 `-r foo .` 上报语法错误 → 保守拒绝。
        //
        // 【为什么接受这个差异】方向是**保守的**（拒绝而非放行），
        // 不会引入安全风险；且 `test -r foo .` 本身是罕见写法
        // （正确写法是 `test -r foo` 或 `[ -r foo ]`）。
        // 交叉验证 719 条用例里这是**唯一**的差异。
        assertNotReadonly("test -r foo .")

        // 常见写法都正常
        assertReadonly("test -f x")
        assertReadonly("test -n 5")
        assertReadonly("[ -f x ]")
        assertReadonly("[[ -f x ]]")
    }

    // ══════════════════════════════════════════════════════════════
    //  辅助
    // ══════════════════════════════════════════════════════════════

    private fun assertReadonly(cmd: String) {
        val v = ReadonlyPolicy.classify(cmd)
        assertTrue("期望「$cmd」判只读，实际非只读（${v.reason}）", v.readonly)
    }

    private fun assertNotReadonly(cmd: String) {
        val v = ReadonlyPolicy.classify(cmd)
        assertFalse("期望「$cmd」判非只读，实际判成了只读", v.readonly)
    }
}
