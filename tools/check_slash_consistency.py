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


def main() -> int:
    if not HANDLER.exists() or not TABLE.exists():
        print(f"找不到源文件：\n  {HANDLER}\n  {TABLE}")
        return 1

    h = handler_commands(HANDLER.read_text(encoding="utf-8"))
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

    if problems:
        print(f"共 {problems} 处漂移。修法：补分支，或补命令表，或加进白名单。")
        return 1

    print("✓ 命令表与实现一致")
    return 0


if __name__ == "__main__":
    sys.exit(main())
