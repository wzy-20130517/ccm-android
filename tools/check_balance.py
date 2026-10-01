#!/usr/bin/env python3
"""Kotlin 括号平衡自检：剥掉字符串/注释后数 {} () []。

用于 CCM 项目改完 Kotlin 后的快速自检（不能替代 CI 编译，但能抓 90% 的手滑）。
用法：python3 tools/check_balance.py <file.kt> [...]
"""
import sys


def strip_kotlin(src: str) -> str:
    """剥掉字符串字面量、字符字面量、行注释、块注释（Kotlin 支持嵌套块注释）。"""
    out = []
    i = 0
    n = len(src)
    depth_block = 0
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''

        if depth_block > 0:
            # 块注释内：只找嵌套的开闭
            if c == '/' and nxt == '*':
                depth_block += 1
                i += 2
                continue
            if c == '*' and nxt == '/':
                depth_block -= 1
                i += 2
                continue
            i += 1
            continue

        # 行注释
        if c == '/' and nxt == '/':
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        # 块注释开始
        if c == '/' and nxt == '*':
            depth_block = 1
            i += 2
            continue
        # 原始字符串 """..."""
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            # 跳过（可能带 .trimIndent() 等后缀，不管）
            i = n if j < 0 else j + 3
            out.append('""')
            continue
        # 普通字符串（含转义）
        if c == '"':
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
            out.append('""')
            continue
        # 字符字面量
        if c == "'":
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == "'":
                    i += 1
                    break
                i += 1
            out.append("''")
            continue

        out.append(c)
        i += 1

    return ''.join(out)


PAIRS = {'}': '{', ')': '(', ']': '['}


def check(path: str) -> bool:
    with open(path, encoding='utf-8') as f:
        src = f.read()
    stripped = strip_kotlin(src)

    stack = []
    line = 1
    ok = True
    for ch in stripped:
        if ch == '\n':
            line += 1
        elif ch in '{([':
            stack.append((ch, line))
        elif ch in '})]':
            if not stack:
                print(f'  ✗ {path}:{line} 多余的 {ch!r}')
                ok = False
                continue
            top, tline = stack.pop()
            if top != PAIRS[ch]:
                print(f'  ✗ {path}:{line} {ch!r} 与 {tline} 行的 {top!r} 不匹配')
                ok = False

    if stack:
        for ch, ln in stack:
            print(f'  ✗ {path}:{ln} 未闭合的 {ch!r}')
        ok = False

    if ok:
        print(f'  ✓ {path} 括号平衡（{len(src)} 字符 / {line} 行）')
    return ok


if __name__ == '__main__':
    files = sys.argv[1:]
    if not files:
        print('用法: python3 check_balance.py <file.kt> [...]')
        sys.exit(2)
    all_ok = all(check(f) for f in files)
    sys.exit(0 if all_ok else 1)
