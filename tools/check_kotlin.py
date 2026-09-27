#!/usr/bin/env python3
"""
Kotlin 文件静态自检（Termux 友好，不依赖编译）。

检查项：
1. 未使用的 import（排除 by 委托需要的 getValue/setValue）
2. 括号平衡（**正确跳过 KDoc 与行内注释**）
3. KDoc 里的块注释嵌套（Kotlin 块注释可嵌套，会导致 Unclosed comment）

用法：
    python3 check_kotlin.py <目录或文件>...
"""
import re
import sys
import glob
import os

DELEGATE_OPS = {'getValue', 'setValue', 'provideDelegate'}


def strip_comments_and_strings(src):
    """去掉注释与字符串，只留代码 —— 用于括号计数。

    ⚠️ 关键：KDoc 里会出现中文引号包裹的 `{`（如「参数 { } 的用法」），
    也会出现 `/*` 序列（glob 路径），必须先剔除注释再计数，否则误报。
    """
    out = []
    i = 0
    n = len(src)
    in_block = 0          # 块注释嵌套深度
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if in_block > 0:
            if c == '/' and nxt == '*':
                in_block += 1
                i += 2
                continue
            if c == '*' and nxt == '/':
                in_block -= 1
                i += 2
                continue
            i += 1
            continue
        # 行注释
        if c == '/' and nxt == '/':
            while i < n and src[i] != '\n':
                i += 1
            continue
        # 块注释
        if c == '/' and nxt == '*':
            in_block += 1
            i += 2
            continue
        # 字符串（含三引号）
        if c == '"':
            if src[i:i + 3] == '"""':
                j = src.find('"""', i + 3)
                i = (j + 3) if j != -1 else n
                continue
            i += 1
            while i < n:
                if src[i] == '\\':
                    i += 2
                    continue
                if src[i] == '"':
                    i += 1
                    break
                i += 1
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
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def check_file(path):
    src = open(path, encoding='utf-8').read()
    lines = src.split('\n')
    errors = []

    # ── 1. 未使用 import ──────────────────────────────────────────
    imports = [(i + 1, l) for i, l in enumerate(lines) if l.startswith('import ')]
    body = '\n'.join(l for l in lines if not l.startswith('import '))
    uses_delegate = re.search(r'\bby\s+(remember|mutableStateOf|animateFloatAsState|\w+\.current)', body) is not None
    unused = []
    for ln, imp in imports:
        m = re.match(r'import\s+([\w.]+)(?:\s+as\s+(\w+))?', imp)
        if not m:
            continue
        sym = m.group(2) or m.group(1).split('.')[-1]
        if sym in DELEGATE_OPS and uses_delegate:
            continue
        if not re.search(r'\b' + re.escape(sym) + r'\b', body):
            unused.append(f'{sym}(行{ln})')
    if unused:
        errors.append(f'未使用的 import: {", ".join(unused)}')

    # ── 2. 括号平衡（用去注释后的代码）─────────────────────────────
    code = strip_comments_and_strings(src)
    for op, cl, name in (('{', '}', '花括号'), ('(', ')', '圆括号'), ('[', ']', '方括号')):
        d = code.count(op) - code.count(cl)
        if d != 0:
            errors.append(f'{name}不平衡: {d:+d}')

    # ── 3. KDoc 块注释嵌套 ────────────────────────────────────────
    nested = []
    for i, l in enumerate(lines, 1):
        m = re.match(r'^\s*\*(.*)$', l)
        if m and m.group(1).strip() != '/':
            if '*/' in m.group(1) or '/*' in m.group(1):
                nested.append(f'行{i}')
    if nested:
        errors.append(f'KDoc 内有块注释标记（会导致 Unclosed comment）: {", ".join(nested)}')

    return errors, len(lines)


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)

    files = []
    for arg in sys.argv[1:]:
        if os.path.isdir(arg):
            files.extend(glob.glob(os.path.join(arg, '**', '*.kt'), recursive=True))
        else:
            files.extend(glob.glob(arg))
    files = sorted(set(files))

    if not files:
        print('未找到 .kt 文件')
        sys.exit(2)

    total_lines = 0
    bad = 0
    for f in files:
        errs, n = check_file(f)
        total_lines += n
        rel = f.replace(os.getcwd() + '/', '')
        if errs:
            bad += 1
            print(f'✗ {rel}  ({n} 行)')
            for e in errs:
                print(f'    {e}')
        else:
            print(f'✓ {rel}  ({n} 行)')

    print(f'\n{len(files)} 个文件 / {total_lines} 行，' +
          ('✓ 全部通过' if bad == 0 else f'✗ {bad} 个文件有问题'))
    sys.exit(0 if bad == 0 else 1)


if __name__ == '__main__':
    main()
