#!/usr/bin/env python3
"""
Slash 命令一致性自检（2026-10-01）。

## 为什么需要它

slash 命令的表与实现分散在三处：
1. `SlashCommandHandler.kt` 四个分区（**真值源**，真正执行命令）
2. `ChatScreenConnected.kt` / `CcmApp.kt` 的老 when（基础命令 + 兜底）
3. `SlashCommands.kt` 的 `COMMON_SLASH_COMMANDS`（候选面板展示）

**表里列了但没人实现** = 用户敲了没反应（面板骗人）；
**实现了但表里没有** = 用户看不到（等于没做）。
两种漂移都是静默的 —— 没有编译错误，只有用户敲命令时才发现。

## 用法

    python3 tools/check_slash_consistency.py

退出码 0 = 一致；1 = 有漂移（列出具体命令）。

## 已知白名单

- `/clear-restore` `/compact-trash`：说明性占位（CLI 专属机制），刻意不进表
- `/temp`：`/temperature` 的别名，表里只列主名
- 老 when 的 11 个基础命令（/clear /help /model …）：由老 when 实现，不算缺实现
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HANDLER = ROOT / "app/src/main/java/com/ccm/app/ui/chat/SlashCommandHandler.kt"
TABLE = ROOT / "app/src/main/java/com/ccm/app/ui/chat/SlashCommands.kt"
OLD_WHEN = [
    ROOT / "app/src/main/java/com/ccm/app/ui/chat/ChatScreenConnected.kt",
    ROOT / "app/src/main/java/com/ccm/app/ui/shell/CcmApp.kt",
]

# 刻意不进表的（见文件头「已知白名单」）
ALLOW_HANDLER_ONLY = {"/clear-restore", "/compact-trash", "/temp"}


def handler_commands(src: str) -> set:
    """提取 SlashCommandHandler 里 when 的顶层分支命令（含别名）。"""
    # 顶层分支缩进是 8 空格；形如  "/a", "/b" ->  或  "effort" ->
    top = re.findall(r'^        ("[^"]+"(?:, "[^"]+")*) ->', src, re.M)
    out = set()
    for line in top:
        for name in re.findall(r'"([^"]+)"', line):
            out.add(name if name.startswith("/") else "/" + name)
    return out


def table_commands(src: str) -> set:
    """提取 COMMON_SLASH_COMMANDS 里的命令名。"""
    return set(re.findall(r'"(/[a-z0-9-]+)" to', src))


def old_when_commands() -> set:
    """提取老 when 里出现的所有 slash 命令（宽松匹配，只用于「有没人接」判断）。"""
    out = set()
    for f in OLD_WHEN:
        if not f.exists():
            continue
        out |= set(re.findall(r'"(/[a-z0-9-]+)"', f.read_text(encoding="utf-8")))
    return out


# 常见的短局部变量名（各分支独立定义，跨分支用就是 bug）
_WATCHED_LOCALS = ("st", "cfg", "loadR", "storage", "session", "a", "sub", "subCmd", "subArg")


def check_branch_locals(src: str):
    """
    检查每个 when 分支里用到的局部变量是否有本分支定义。

    ## 为什么需要（CI #237 的教训）
    `/context` 分支里写了 `AppConfig.load(st.configFile)`，但那个分支的变量
    叫 `storage`，`st` 是隔壁 `/effort` 分支的 —— 本地两个自检脚本都过了
    （它们只查括号和 import），CI 编译才报 Unresolved reference 'st'。

    做法：把源码按 `        "xxx" ->` 切成分支，逐分支检查。
    只查白名单里的短变量名，避免把 lambda 参数、for 变量误判。

    @return [(行号, 分支名, 变量名, 示例行), ...]
    """
    lines = src.split("\n")
    starts = []
    for i, l in enumerate(lines):
        m = re.match(r'^        ("[^"]+"(?:, "[^"]+")*) ->', l)
        if m:
            starts.append((i, m.group(1)))

    problems = []
    for bi, (start, name) in enumerate(starts):
        end = starts[bi + 1][0] if bi + 1 < len(starts) else len(lines)
        # ⚠️ 必须先剥注释再检查 —— 注释里提到 `session.state.value` 这类
        #    说明性文字不是真实引用（第一版没剥，5 处全是误报）。
        body = strip_comments(lines[start:end])
        body_text = "\n".join(body)
        for var in _WATCHED_LOCALS:
            # 该变量在本分支被使用？（排除 "xxx.st." 这种成员访问）
            if not re.search(rf'(?<![\w.]){var}\.', body_text):
                continue
            # 本分支有定义？（val/var 声明，或 lambda 参数、for 变量）
            defined = (
                re.search(rf'\b(?:val|var)\s+{var}\s*[:=]', body_text)
                or re.search(rf'\bfor\s*\(\s*{var}\s+in\b', body_text)
                or re.search(rf'\b{var}\s*->', body_text)          # lambda 参数
                or re.search(rf'\(\s*{var}\s*[,)]', body_text)      # 多参 lambda
            )
            if not defined:
                sample = next(
                    (l.strip() for l in body if re.search(rf'(?<![\w.]){var}\.', l)),
                    "",
                )
                problems.append((start + 1, name, var, sample[:70]))
    return problems


def strip_comments(lines):
    """
    去掉行注释与块注释（保留行数，便于定位）。

    只处理 `//` 与块注释；Kotlin 的三引号原始字符串在本文件里没用到，
    真用到时要补（否则字符串里的双斜杠会被误剥）。
    """
    out = []
    in_block = False
    for l in lines:
        if in_block:
            if "*/" in l:
                l = l.split("*/", 1)[1]
                in_block = False
            else:
                out.append("")
                continue
        if "/*" in l:
            before, _, after = l.partition("/*")
            if "*/" in after:
                l = before + after.split("*/", 1)[1]
            else:
                l = before
                in_block = True
        # 行注释：简单处理（字符串里的 // 极少见，本文件无 URL 字面量）
        if "//" in l:
            l = l.split("//", 1)[0]
        out.append(l)
    return out


def main() -> int:
    if not HANDLER.exists() or not TABLE.exists():
        print(f"找不到源文件：\n  {HANDLER}\n  {TABLE}")
        return 1

    handler_src = HANDLER.read_text(encoding="utf-8")
    h = handler_commands(handler_src)
    t = table_commands(TABLE.read_text(encoding="utf-8"))
    o = old_when_commands()

    print(f"handler 分支命令：{len(h)} 个")
    print(f"命令表（面板）：  {len(t)} 个")
    print(f"老 when 兜底：    {len(o)} 个")
    print()

    problems = 0

    # 方向 1：表里有 → 必须有人实现（handler 或老 when）
    dead = sorted(c for c in t - h - o)
    if dead:
        problems += len(dead)
        print("✗ 命令表里列了但无人实现（用户敲了没反应）：")
        for c in dead:
            print(f"    {c}")
        print()

    # 方向 2：handler 有 → 应该在表里（否则用户看不到）
    hidden = sorted(c for c in h - t - ALLOW_HANDLER_ONLY)
    if hidden:
        problems += len(hidden)
        print("✗ handler 实现了但命令表里没有（用户看不到）：")
        for c in hidden:
            print(f"    {c}")
        print()

    # 方向 3：白名单是否过期（handler 里已经没有这个命令了）
    stale = sorted(c for c in ALLOW_HANDLER_ONLY if c not in h)
    if stale:
        print("⚠ 白名单里有已不存在的命令（可清理）：")
        for c in stale:
            print(f"    {c}")
        print()

    # 方向 4：分支内变量未定义（CI #237 踩过的坑）
    #
    # 本地 check_kotlin.py 只查括号/注释，check_imports.py 只查 import ——
    # **都抓不到「分支里用了别的分支的局部变量」**。这类错误要等 CI 编译
    # 才暴露（#237：/context 分支里写了 `st.configFile`，但那个分支里
    # 变量叫 `storage`，`st` 是隔壁分支的）。
    #
    # 做法：每个 when 分支是独立作用域，检查分支内用到的短变量名
    # （st / cfg / loadR / storage 这类）是否有本分支的 val/var 定义。
    undef = check_branch_locals(handler_src)
    if undef:
        problems += len(undef)
        print("✗ 分支内用了未定义的局部变量（CI 会编译失败）：")
        for line_no, branch, var, sample in undef:
            print(f"    行 {line_no} [{branch}] 用了 `{var}` —— {sample}")
        print()

    if problems:
        print(f"共 {problems} 处问题。修法：补分支，或补命令表，或加进白名单。")
        return 1

    print("✓ 命令表与实现一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
