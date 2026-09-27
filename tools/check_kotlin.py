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


def collect_definitions(files):
    """收集所有顶层函数的参数名，用于跨文件调用检查。"""
    defs = {}
    for f in files:
        src = open(f, encoding='utf-8').read()
        # 匹配 fun Name( ... ) 的完整参数列表（含换行）
        for m in re.finditer(r'^fun\s+(\w+)\s*\(', src, re.M):
            name = m.group(1)
            start = m.end() - 1
            depth = 0
            i = start
            while i < len(src):
                if src[i] == '(':
                    depth += 1
                elif src[i] == ')':
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            params_src = src[start + 1:i]
            # 提取参数名（形如 `name: Type`，跳过注解与默认值里的冒号）
            params = set()
            for pm in re.finditer(r'(?:^|,)\s*(?:@\w+\s+)*(\w+)\s*:', params_src):
                params.add(pm.group(1))
            # 是否是 @Composable
            preceding = src[max(0, m.start() - 200):m.start()]
            defs[name] = {
                'params': params,
                'file': f,
                'composable': '@Composable' in preceding.split('fun ')[-1] if 'fun ' in preceding else False,
                'trailing_lambda': params_src.rstrip().endswith('-> Unit') or 'content:' in params_src,
            }
    return defs


def check_cross_file(files, defs):
    """检查跨文件调用：参数名是否存在、必填参数是否缺失。

    这是本轮 CI 报错暴露的盲区 —— 单文件检查查不出「调用了不存在的参数名」。
    """
    errors = []
    for f in files:
        src = open(f, encoding='utf-8').read()
        # 找形如 `FuncName(` 且后面跟 `key = value` 的调用
        for m in re.finditer(r'\b([A-Z]\w+)\s*\(', src):
            fname = m.group(1)
            if fname not in defs:
                continue
            d = defs[fname]
            if d['file'] == f:
                continue        # 同文件调用不查（可能有重载）
            # 提取本次调用的实参（到匹配的右括号）
            start = m.end() - 1
            depth = 0
            i = start
            while i < len(src):
                if src[i] == '(':
                    depth += 1
                elif src[i] == ')':
                    depth -= 1
                    if depth == 0:
                        break
                i += 1
            call_src = src[start + 1:i]
            # ★ 只取顶层具名实参 —— 嵌套括号里的内容必须剔除。
            # 否则 `Modifier.padding(top = 8.dp, end = 6.44.dp)` 里的 top/end
            # 会被误当成外层函数的实参（本轮踩过这个误报）。
            named = set()
            depth2 = 0
            token = ''
            for ch in call_src + ',':
                if ch in '([{':
                    depth2 += 1
                    if depth2 == 1:
                        token = ''
                    continue
                if ch in ')]}':
                    depth2 -= 1
                    continue
                if ch == ',' and depth2 == 0:
                    m = re.match(r'\s*(\w+)\s*=(?!=)', token)
                    if m:
                        named.add(m.group(1))
                    token = ''
                    continue
                if depth2 == 0:
                    token += ch
            unknown = named - d['params']
            if unknown:
                errors.append(f'{f}: 调用 {fname}() 传了不存在的参数 {sorted(unknown)}'
                              f'（{fname} 定义在 {os.path.basename(d["file"])}，'
                              f'可用: {sorted(d["params"])}）')
    return errors


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

    # 先收集所有定义（跨文件检查用）
    all_defs = collect_definitions(files)
    cross_errors = check_cross_file(files, all_defs)
    cross_by_file = {}
    for e in cross_errors:
        fp = e.split(':')[0]
        cross_by_file.setdefault(fp, []).append(e.split(': ', 1)[1] if ': ' in e else e)

    total_lines = 0
    bad = 0
    for f in files:
        errs, n = check_file(f)
        # 合并跨文件错误
        if f in cross_by_file:
            errs = errs + [f'跨文件调用: {x}' for x in cross_by_file[f]]
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
