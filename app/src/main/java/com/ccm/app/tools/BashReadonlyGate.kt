package com.ccm.app.tools

import com.ccm.app.tools.bash.ReadonlyPolicy

/**
 * plan 模式的 Bash 只读闸门 —— 把 AST 判定接到 [ToolPermissions.Decision] 上。
 *
 * ══════════════════════════════════════════════════════════════
 *  为什么单独抽一层
 * ══════════════════════════════════════════════════════════════
 *
 * [ToolPermissions] 在 `com.ccm.app.tools` 包，[ReadonlyPolicy] 在
 * `com.ccm.app.tools.bash`。`Decision` 是前者的内部类型，后者不该反向依赖它
 * —— 策略层保持纯粹（只回答「只读吗」+「为什么」），
 * 由这一层翻译成权限裁决。
 *
 * 这样测试也只依赖策略层，不需要构造 `ToolPermissions`（它要 configDir）。
 *
 * ══════════════════════════════════════════════════════════════
 *  从正则到 AST：修了什么
 * ══════════════════════════════════════════════════════════════
 *
 * 旧实现是 `BASH_WRITE_PATTERN` 正则 `\b(rm|mv|cp|...)\b`，可被这些绕过：
 *
 * | 绕过手法 | 旧正则 | 现在 |
 * |---|---|---|
 * | `/bin/rm x` | 漏（`\brm\b` 在 `/bin/rm` 里其实能匹配，但换个写法就漏） | 拦（argv 归一化） |
 * | `bash -c "rm x"` | 误报（命中字符串里的 rm） | 拦（`bash` 不在只读表） |
 * | `find . -delete` | **完全漏**（没有 rm 字样） | 拦（find 写选项表） |
 * | `sed -i s/a/b/ f` | 漏（`sed\s+-i` 只匹配空格分隔） | 拦（sed 回调） |
 * | `echo $(rm x)` | 漏 | 拦（动态词） |
 * | `ls;rm x` | 漏（无空格） | 拦（AST 分号分隔） |
 *
 * 反过来，旧正则还会**误杀**正常只读命令（如 `grep -n "cp" file` 里的 cp），
 * AST 判定不会。
 */
internal object BashReadonlyGate {

    /**
     * 校验一条 Bash 命令在 plan 模式下是否放行。
     *
     * @return 允许时返回 [ToolPermissions.Decision.ALLOW]，否则返回带理由的 deny
     */
    fun check(command: String): ToolPermissions.Decision {
        val cmd = command.trim()
        if (cmd.isEmpty()) {
            return ToolPermissions.Decision.deny("plan 模式：Bash 命令为空")
        }

        val verdict = try {
            ReadonlyPolicy.classify(cmd)
        } catch (t: Throwable) {
            // 判定过程本身出问题（解析器内部异常等）→ 保守拒绝
            return ToolPermissions.Decision.deny(
                "plan 模式：Bash 只读判定失败（${t.javaClass.simpleName}: ${t.message}），保守拒绝"
            )
        }

        if (verdict.readonly) return ToolPermissions.Decision.ALLOW

        val why = verdict.reason ?: "无法确认是否只读"
        return ToolPermissions.Decision.deny("plan 模式禁止非只读 Bash：$why\n  命令：$cmd")
    }
}
