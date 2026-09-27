#!/usr/bin/env python3
"""
Kotlin 跨文件符号引用检查（Termux 友好，不编译）。

## 为什么要这个脚本
`check_kotlin.py` 查「未使用 import」和「括号平衡」，
`check_imports.py` 查「缺 import」，
但**都不查「符号根本不存在」** —— 例如：

    AdminSpacing.gap2      ← AdminSpacing 里没定义 gap2
    AdminColors.green100   ← AdminColors 里没定义 green100

这类错误的 CI 表现是 `Unresolved reference 'gap2'`，
本地无编译环境下**完全看不出来**（我是连着两次 push 才发现的）。

## 检查原理
1. 扫描所有 .kt 文件，收集 `object X { val y / fun y }` 的成员表
2. 扫描所有 `X.y` 形式的**限定引用**（限定符首字母大写，排除局部变量）
3. 若 `X` 是本项目定义过的 object/class/enum，但 `y` 不在其成员表里 → 报错

## 已知局限
- 只查「首字母大写的限定符」（项目内的 object 命名习惯），小写限定符会漏
- 枚举项、data class 属性、接口方法不单独收集（只收 object/class/enum 的直接成员）
- 属性访问链 `a.b.c` 只取最后一段，可能误报（罕见）

用法：
    python3 check_symbols.py <目录或文件>...
"""
import re
import sys
import os
import glob

# 这些是 Compose / Kotlin 标准库的命名空间，不检查
EXTERNAL_QUALIFIERS = {
    'Modifier', 'Alignment', 'Arrangement', 'FontWeight', 'FontStyle', 'FontFamily',
    'Color', 'Offset', 'Size', 'StrokeCap', 'StrokeJoin', 'PathEffect', 'Brush',
    'SolidColor', 'TextAlign', 'TextOverflow', 'TextDecoration', 'ContentScale',
    'KeyboardOptions', 'KeyboardActions', 'ImeAction', 'KeyboardType',
    'VisualTransformation', 'PasswordVisualTransformation', 'FocusRequester',
    'HapticFeedbackType', 'Dispatchers', 'Job', 'CoroutineScope', 'WindowInsets',
    'PaddingValues', 'BoxScope', 'ColumnScope', 'RowScope', 'DrawScope',
    'TextStyle', 'TextUnit', 'Dp', 'ContentDescription', 'MaterialTheme',
    'LocalContentColor', 'LocalDensity', 'LocalContext', 'LocalConfiguration',
    'LocalFocusManager', 'LocalSoftwareKeyboardController', 'LocalHapticFeedback',
    'SnackbarHostState', 'MutableInteractionSource', 'LayoutModifier',
    'LazyListState', 'ScrollState', 'TextFieldValue', 'ImageBitmap',
    'AnimatedVisibility', 'EnterTransition', 'ExitTransition', 'tween', 'spring',
    'Path', 'Stroke', 'Fill', 'Saver', 'CoroutineStart',
    # kotlin.Result（标准库，有 success/failure 工厂）
    'Result',
}

# 枚举/类内置属性（Kotlin 语言自带，不在成员表里但合法）
BUILTIN_MEMBERS = {
    'entries',      # Kotlin 1.9+ EnumClass.entries（替代 values()）
    'values',       # 枚举 values()
    'valueOf',      # 枚举 valueOf()
    'name', 'ordinal', 'companion', 'Companion',
    'hashCode', 'toString', 'equals', 'copy', 'component1',
    'javaClass', 'instance',
    # kotlinx.serialization 编译期生成（源码里看不到定义，但一定存在）
    'serializer', 'descriptor',
    # kotlin.Result 伴生对象工厂
    'success', 'failure',
}

# Kotlin 语言关键字/内置，看到就跳过
LANG_KEYWORDS = {
    'Int', 'Long', 'Double', 'Float', 'Boolean', 'Char', 'String', 'Byte', 'Short',
    'Any', 'Unit', 'Nothing', 'List', 'Map', 'Set', 'Array', 'Pair', 'Triple',
    'Math', 'System', 'Runtime', 'Thread', 'Exception', 'Throwable', 'StringBuilder',
    'Regex', 'Random', 'Comparable', 'Iterable', 'Sequence', 'Number', 'Enum',
    'StringBuffer', 'Class', 'Field', 'Method',
}


def strip_comments_and_strings(src):
    """去掉注释与字符串，只留代码。"""
    out = []
    i = 0
    n = len(src)
    in_block = 0
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
        if c == '/' and nxt == '/':
            while i < n and src[i] != '\n':
                i += 1
            continue
        if c == '/' and nxt == '*':
            in_block = 1
            i += 2
            continue
        if src[i:i + 3] == '"""':
            i += 3
            while i < n and src[i:i + 3] != '"""':
                i += 1
            i += 3
            continue
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                if src[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                if src[i] == '\\':
                    i += 1
                i += 1
            i += 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def find_object_members(raw):
    """找出文件里所有 object/class/enum/interface 及其直接成员。

    返回 {类型名: {成员名, ...}}

    做法：定位 `object X {` / `class X(` / `enum class X {`，
    然后从该位置起做**花括号配平**，在配平范围内用固定缩进深度找成员。
    比正则跨行匹配稳（正则容易被字符串/注释里的花括号带偏，虽然这里已 strip 过）。
    """
    result = {}
    # 定位所有类型声明
    decl_pat = re.compile(
        r'\b(?:enum\s+class|sealed\s+class|data\s+class|value\s+class|'
        r'object|class|interface)\s+([A-Z][A-Za-z0-9_]*)'
    )
    for m in decl_pat.finditer(raw):
        name = m.group(1)
        # 找到声明后的第一个 `{`
        i = m.end()
        # 跳过主构造参数（可能含 `{` 默认值，但极少；用括号配平跳过）
        depth_paren = 0
        while i < len(raw):
            c = raw[i]
            if c == '(':
                depth_paren += 1
            elif c == ')':
                depth_paren -= 1
            elif c == '{' and depth_paren <= 0:
                break
            elif c in '\n;' and depth_paren <= 0:
                break
            i += 1
        if i >= len(raw) or raw[i] != '{':
            # 无 body（如 `class Foo(val x: Int)`）→ 收集主构造参数里的 val/var
            seg = raw[m.end():m.end() + 400]
            members = set(re.findall(r'\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)', seg))
            if members:
                result.setdefault(name, set()).update(members)
            continue

        # 花括号配平找 body 结束
        depth = 0
        j = i
        while j < len(raw):
            if raw[j] == '{':
                depth += 1
            elif raw[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        body = raw[i + 1:j]

        # 收集 body 里的成员：val / var / fun（含嵌套一层也没关系，宁可多收不误报）
        members = set()
        for mm in re.finditer(r'\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)', body):
            members.add(mm.group(1))
        for mm in re.finditer(r'\bfun\s+(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)', body):
            members.add(mm.group(1))
        # 枚举项：body 里 `NAME(` 或 `NAME,` / `NAME;` 且全大写
        for mm in re.finditer(r'^\s*([A-Z][A-Z0-9_]{1,})\s*[(:,;]', body, re.M):
            members.add(mm.group(1))
        # 嵌套 object/class 名
        for mm in re.finditer(r'\b(?:object|class|interface)\s+([A-Z][A-Za-z0-9_]*)', body):
            members.add(mm.group(1))

        result.setdefault(name, set()).update(members)
        # 同名类型可能分布在多文件 → 合并（setdefault + update 已处理）
    return result


EXTENSION_NAMES = set()


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    quiet = '--quiet' in sys.argv
    if not args:
        print("用法: check_symbols.py <目录或文件>...")
        return 1

    files = []
    for a in args:
        if os.path.isdir(a):
            files.extend(sorted(glob.glob(os.path.join(a, '**', '*.kt'), recursive=True)))
        elif a.endswith('.kt'):
            files.append(a)
    if not files:
        print("未找到 .kt 文件")
        return 1

    # 第一遍：收集所有类型成员 + 全局扩展函数名
    global EXTENSION_NAMES
    for f in files:
        try:
            with open(f, encoding='utf-8') as fh:
                raw = fh.read()
        except Exception:
            continue
        # `fun Receiver.name(` / `val Receiver.name` / `fun Receiver<T>.name(`
        for mm in re.finditer(
            r'\b(?:fun|val|var)\s+(?:<[^>]*>\s*)?'
            r'[A-Za-z_][A-Za-z0-9_]*(?:<[^>]*>)?\.([A-Za-z_][A-Za-z0-9_]*)',
            raw,
        ):
            EXTENSION_NAMES.add(mm.group(1))

    all_members = {}
    for f in files:
        try:
            with open(f, encoding='utf-8') as fh:
                raw = fh.read()
        except Exception:
            continue
        for k, v in find_object_members(raw).items():
            all_members.setdefault(k, set()).update(v)

    # 第二遍：检查限定引用
    total = 0
    for f in files:
        try:
            with open(f, encoding='utf-8') as fh:
                raw = fh.read()
        except Exception:
            continue
        code = strip_comments_and_strings(raw)

        # 收集本文件定义的局部名（避免把局部变量当类型名）
        local_defs = set(re.findall(r'\b(?:val|var|fun|class|object|interface)\s+([A-Za-z_][A-Za-z0-9_]*)', code))
        local_defs |= set(all_members.keys())
        # ★ 扩展函数/属性（`fun Qualifier.name` / `val Qualifier.name`）：
        #   定义在被扩展类型**之外**的文件里，成员表里查不到 → 必须单独收集，
        #   否则每个扩展函数都会误报成 Unresolved（踩过：CCMColors.toMaterialColorScheme）
        local_defs |= EXTENSION_NAMES

        errors = []
        seen = set()
        # 形如 `Qualifier.member`，Qualifier 首字母大写
        for m in re.finditer(r'(?<![A-Za-z0-9_.])([A-Z][A-Za-z0-9_]*)\.([a-z][A-Za-z0-9_]*)', code):
            qual, member = m.group(1), m.group(2)
            if qual in EXTERNAL_QUALIFIERS or qual in LANG_KEYWORDS:
                continue
            if qual not in all_members:
                continue        # 不是本项目已知类型（可能是外部库）
            if member in all_members[qual]:
                continue
            if member in BUILTIN_MEMBERS:
                continue
            # 该类型可能通过 `import X.Y` 引入的外部扩展；项目里 object 也可能有扩展函数
            # 保守起见：只要**全项目任何地方**定义过同名 fun/val 就放过
            if member in local_defs:
                continue
            key = (qual, member)
            if key in seen:
                continue
            seen.add(key)
            errors.append(f"Unresolved: {qual}.{member} —— {qual} 里没有 {member}")

        if errors:
            total += len(errors)
            print(f"❌ {os.path.relpath(f)}")
            for e in errors:
                print(f"     {e}")
        elif not quiet:
            print(f"✓ {os.path.relpath(f)}")

    print()
    print(f"{len(files)} 个文件 · 未解析引用 {total} 处")
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main())
