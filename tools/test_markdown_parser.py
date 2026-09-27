#!/usr/bin/env python3
"""
Markdown 解析器逻辑回归测试。

## 为什么要这个（而不是 Kotlin 单测）
项目纪律：**禁止本地 kotlinc**（CPU 打满过两次）。所以 Kotlin 侧没有单测基建。

但 `MarkdownRenderer.kt` 的解析器是**纯函数**，算法一旦写错（比如代码块内的
`#` 被当标题），UI 上表现为「消息渲染错乱」，肉眼不一定立刻发现。
这里用 Python **复刻同一套算法**做回归 —— 不是替代单测，是在无法编译的
环境里守住算法正确性的下限。

## ⚠️ 维护契约（重要）
本文件与 `MarkdownRenderer.kt` 的 `parseMarkdown` / `isTableSeparator` /
`splitTableRow` / `isBlockStart` **必须逻辑同步**。
改 Kotlin 那份时，**同步改这里**，否则测试会给出虚假的绿灯 —— 比没有测试更糟。

用法：
    python3 tools/test_markdown_parser.py
"""
import re
import sys

# ── 以下四个函数与 MarkdownRenderer.kt 逐行对应 ──────────────────


HEADING_RE = re.compile(r'^#{1,6}\s+')
HEADING_LINE_RE = re.compile(r'^(#{1,6})\s+(.*)$')
UNORDERED_RE = re.compile(r'^\s*[-*+]\s+(.*)$')
ORDERED_RE = re.compile(r'^\s*\d+[.)]\s+(.*)$')


def is_table_separator(line: str) -> bool:
    """对应 Kotlin `isTableSeparator`：GFM 表格分隔行 `|---|---|` 或 `|:--|--:|`"""
    t = line.strip()
    if '-' not in t:
        return False
    return re.match(r'^\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?$', t) is not None


def split_table_row(line: str) -> list:
    """对应 Kotlin `splitTableRow`：去首尾管道后按 `|` 切分"""
    t = line.strip()
    if t.startswith('|'):
        t = t[1:]
    if t.endswith('|'):
        t = t[:-1]
    return [c.strip() for c in t.split('|')]


def is_block_start(lines: list, i: int) -> bool:
    """对应 Kotlin `isBlockStart`：段落扫描的终止条件"""
    l = lines[i]
    t = l.lstrip()
    if t.startswith('```') or t.startswith('~~~'):
        return True
    # ⚠️ 必须与标题解析的 `#{1,6}\s+` 一致 —— 只判 '#' 会与主循环不匹配导致死循环
    if HEADING_RE.match(t):
        return True
    if t.startswith('>'):
        return True
    if l.strip() in ('---', '***', '___'):
        return True
    if re.match(r'^\s*[-*+]\s+', l):
        return True
    if re.match(r'^\s*\d+[.)]\s+', l):
        return True
    if '|' in l and i + 1 < len(lines) and is_table_separator(lines[i + 1]):
        return True
    return False


def parse_markdown(src: str) -> list:
    """对应 Kotlin `parseMarkdown`：块级解析，单遍扫描"""
    lines = src.replace('\r\n', '\n').split('\n')
    out = []
    i = 0
    while i < len(lines):
        line = lines[i].rstrip()
        t = line.lstrip()

        # 代码围栏必须最先判 —— 否则代码里的 # / - / | 会被误解析
        if t.startswith('```') or t.startswith('~~~'):
            fence = '```' if t.startswith('```') else '~~~'
            lang = t[len(fence):].strip()
            body = []
            i += 1
            while i < len(lines) and not lines[i].lstrip().startswith(fence):
                body.append(lines[i])
                i += 1
            i += 1
            out.append(('code', lang, '\n'.join(body)))
            continue

        if not line.strip():
            i += 1
            continue

        if line.strip() in ('---', '***', '___'):
            out.append(('hr',))
            i += 1
            continue

        m = HEADING_LINE_RE.match(line)
        if m:
            out.append(('h', len(m.group(1)), m.group(2).strip()))
            i += 1
            continue

        if t.startswith('>'):
            buf = []
            while i < len(lines) and lines[i].lstrip().startswith('>'):
                buf.append(lines[i].lstrip()[1:].strip())
                i += 1
            out.append(('quote', '\n'.join(buf)))
            continue

        if '|' in line and i + 1 < len(lines) and is_table_separator(lines[i + 1]):
            header = split_table_row(line)
            i += 2
            rows = []
            while i < len(lines) and '|' in lines[i] and lines[i].strip():
                rows.append(split_table_row(lines[i]))
                i += 1
            out.append(('table', header, rows))
            continue

        um = UNORDERED_RE.match(line)
        om = ORDERED_RE.match(line)
        if um or om:
            ordered = om is not None
            items = []
            while i < len(lines):
                l = lines[i]
                a = UNORDERED_RE.match(l)
                b = ORDERED_RE.match(l)
                if not a and not b:
                    break
                items.append((a or b).group(1).strip())
                i += 1
            out.append(('list', ordered, items))
            continue

        buf = []
        while i < len(lines):
            l = lines[i]
            if not l.strip() or is_block_start(lines, i):
                break
            buf.append(l.strip())
            i += 1
        if buf:
            out.append(('p', '\n'.join(buf)))

        # 兜底：无论如何必须推进，否则死循环（与 Kotlin 侧同一安全网）
        if i < len(lines) and not buf:
            out.append(('p', lines[i].strip()))
            i += 1
    return out


# ── 测试用例 ──────────────────────────────────────────────────────
# 每条：(说明, 输入, 期望的块类型序列)
CASES = [
    # ★ 最高价值：代码块优先级（手写 Markdown 解析最常见的 bug）
    ("代码块内的 # 不被当标题", "# 真标题\n\n```python\n# 这是注释\nprint(1)\n```\n\n正文",
     ['h', 'code', 'p']),
    ("代码块内的 - 不被当列表", "- 真列表\n\n```\n- 假列表\n```", ['list', 'code']),
    ("代码块内的 | 不被当表格", "```\n| a | b |\n|---|---|\n```", ['code']),
    ("代码块内的 > 不被当引用", "```\n> 引用\n```", ['code']),

    # 表格
    ("表格识别", "| A | B |\n|---|---|\n| 1 | 2 |", ['table']),
    ("表格含冒号对齐", "| A | B |\n|:--|--:|\n| 1 | 2 |", ['table']),
    ("竖线但非表格（缺分隔行）", "a | b\n普通行", ['p']),
    ("表格后接段落", "| A |\n|---|\n| 1 |\n\n段落", ['table', 'p']),

    # 标题
    ("六级标题", "###### 六级", ['h']),
    ("井号后无空格不算标题", "#不是标题", ['p']),
    ("标题接段落", "# 标题\n正文", ['h', 'p']),

    # 列表
    ("无序列表", "- a\n- b\n- c", ['list']),
    ("有序列表", "1. a\n2. b", ['list']),
    ("任务列表", "- [ ] 未完成\n- [x] 已完成", ['list']),
    ("星号列表", "* a\n* b", ['list']),
    ("列表后接段落", "- 项\n\n段落", ['list', 'p']),

    # 其他块
    ("引用", "> 引用内容\n> 第二行", ['quote']),
    ("分割线（三连字符）", "上\n\n---\n\n下", ['p', 'hr', 'p']),
    ("分割线（三连星号）", "***", ['hr']),
    ("段落合并多行", "第一行\n第二行\n\n新段", ['p', 'p']),
    ("代码块带语言标签", "```js\nlet a=1\n```", ['code']),
    ("波浪围栏", "~~~\ncode\n~~~", ['code']),
    # ★ 死循环回归（2026-09-27 发现）：'#' 后无空格时 isBlockStart 与标题解析不一致
    ("井号后无空格不死循环", "#不是标题", ['p']),
    ("井号后无空格 + 后续段落", "#不是标题\n\n正文", ['p', 'p']),
    ("空输入", "", []),
    ("纯空行", "\n\n\n", []),
    ("CRLF 换行", "# 标题\r\n\r\n正文", ['h', 'p']),
]


def main() -> int:
    ok = fail = 0
    print("Markdown 解析器回归测试")
    print("─" * 60)
    for name, src, expect in CASES:
        got = [b[0] for b in parse_markdown(src)]
        if got == expect:
            ok += 1
            print(f"  ✓ {name}")
        else:
            fail += 1
            print(f"  ✗ {name}")
            print(f"      期望: {expect}")
            print(f"      实际: {got}")
    print("─" * 60)
    print(f"{ok} 通过 / {fail} 失败")
    if fail:
        print("\n⚠️ 若 Kotlin 侧刚改过解析器，请同步本文件的对应函数（见文件头契约）")
    return 1 if fail else 0


if __name__ == '__main__':
    sys.exit(main())
