#!/usr/bin/env python3
"""
Kotlin import 完整性检查（Termux 友好，不编译）。

## 为什么要这个脚本
`tools/check_kotlin.py` 只查「未使用的 import」（多余的），不查「缺失的 import」（要命的）。
一个漏 import 的扩展函数（如 `horizontalScroll`）在 CI 上就是 `Unresolved reference`，
而本地无编译环境下**完全看不出来** —— 只能靠静态扫描。

## 检查原理
1. 维护「必须 import 的 Compose/Kotlin 符号」清单，**按调用形态分两类**：
   - `EXTENSION_SYMBOLS`：以 `Modifier.xxx(...)` / `value.xxx(...)` 形式调用 →
     正则允许前缀是点号（`.Sym(` 或 `Sym(`）
   - `TOPLEVEL_SYMBOLS`：顶层函数/类，如 `Text(...)` / `Color(...)` →
     正则**禁止**前缀是 `.`（避免把 `foo.Text()` 这类误判）
2. 对每个 .kt 文件：扫描代码区（去掉注释与字符串）里出现的符号
3. 如果出现，但文件既没 import 它、也没在同包/同文件定义它 → 报错
4. 额外：同包内自定义函数不需要 import，脚本会收集全批文件的定义名来排除

⚠️ **分类错误 = 漏检**（踩过）：早期版本对所有符号都禁止 `.` 前缀，
结果 `Modifier.horizontalScroll(hScroll)` 这种**最常见的扩展调用被整批漏掉**，
脚本报「0 处缺失」而实际有 3 个文件没 import。别把两类混成一个清单。

## 已知局限（别当万灵药）
- 只覆盖清单里的符号，清单外的漏检
- 同名局部变量会误报（罕见）
- 用 `--all` 时会把「同包内自定义函数」也算进来，误报率略升

用法：
    python3 check_imports.py <目录或文件>...
    python3 check_imports.py --quiet <目录>     # 只报错，不列通过项
"""
import re
import sys
import os
import glob

# ── 扩展函数/属性符号 → 所在包 ────────────────────────────────────
# 调用形态：`Modifier.xxx(...)` / `value.xxx(...)` —— 前面**可以**是点号。
EXTENSION_SYMBOLS = {
    'horizontalScroll': 'androidx.compose.foundation',
    'verticalScroll': 'androidx.compose.foundation',
    'rememberScrollState': 'androidx.compose.foundation',
    'background': 'androidx.compose.foundation',
    'border': 'androidx.compose.foundation',
    'clickable': 'androidx.compose.foundation',
    'scrollable': 'androidx.compose.foundation',
    'basicMarquee': 'androidx.compose.foundation',
    'focusable': 'androidx.compose.foundation',
    # layout 扩展
    'fillMaxWidth': 'androidx.compose.foundation.layout',
    'fillMaxHeight': 'androidx.compose.foundation.layout',
    'fillMaxSize': 'androidx.compose.foundation.layout',
    'width': 'androidx.compose.foundation.layout',
    'height': 'androidx.compose.foundation.layout',
    'size': 'androidx.compose.foundation.layout',
    'sizeIn': 'androidx.compose.foundation.layout',
    'widthIn': 'androidx.compose.foundation.layout',
    'heightIn': 'androidx.compose.foundation.layout',
    'padding': 'androidx.compose.foundation.layout',
    'paddingFromBaseline': 'androidx.compose.foundation.layout',
    'wrapContentSize': 'androidx.compose.foundation.layout',
    'defaultMinSize': 'androidx.compose.foundation.layout',
    'aspectRatio': 'androidx.compose.foundation.layout',
    'offset': 'androidx.compose.foundation.layout',
    'absoluteOffset': 'androidx.compose.foundation.layout',
    'imePadding': 'androidx.compose.foundation.layout',
    'systemBarsPadding': 'androidx.compose.foundation.layout',
    'statusBarsPadding': 'androidx.compose.foundation.layout',
    'navigationBarsPadding': 'androidx.compose.foundation.layout',
    'safeDrawingPadding': 'androidx.compose.foundation.layout',
    'windowInsetsPadding': 'androidx.compose.foundation.layout',
    # draw / graphics 扩展
    'clip': 'androidx.compose.ui.draw',
    'clipToBounds': 'androidx.compose.ui.draw',
    'alpha': 'androidx.compose.ui.draw',
    'rotate': 'androidx.compose.ui.draw',
    'scale': 'androidx.compose.ui.draw',
    'shadow': 'androidx.compose.ui.draw',
    'drawBehind': 'androidx.compose.ui.draw',
    'drawWithContent': 'androidx.compose.ui.draw',
    'graphicsLayer': 'androidx.compose.ui.graphics',
    # ui 扩展
    'onSizeChanged': 'androidx.compose.ui.layout',
    'onGloballyPositioned': 'androidx.compose.ui.layout',
    'semantics': 'androidx.compose.ui.semantics',
    'contentDescription': 'androidx.compose.ui.semantics',
    'testTag': 'androidx.compose.ui.platform',
    'pointerInput': 'androidx.compose.ui.input.pointer',
    'onFocusChanged': 'androidx.compose.ui.focus',
    'focusRequester': 'androidx.compose.ui.focus',
    'pointerHoverIcon': 'androidx.compose.ui.input.pointer',
}

# ── 顶层函数 / 类 / 单例符号 → 所在包 ──────────────────────────────
# 调用形态：`xxx(...)` 或 `xxx<...>(...)` —— 前面**不能**是点号。
TOPLEVEL_SYMBOLS = {
    # foundation 布局容器
    'Arrangement': 'androidx.compose.foundation.layout',
    'Box': 'androidx.compose.foundation.layout',
    'Column': 'androidx.compose.foundation.layout',
    'Row': 'androidx.compose.foundation.layout',
    'Spacer': 'androidx.compose.foundation.layout',
    'BoxScope': 'androidx.compose.foundation.layout',
    'ColumnScope': 'androidx.compose.foundation.layout',
    'RowScope': 'androidx.compose.foundation.layout',
    'PaddingValues': 'androidx.compose.foundation.layout',
    'FlowRow': 'androidx.compose.foundation.layout',
    'FlowColumn': 'androidx.compose.foundation.layout',
    'WindowInsets': 'androidx.compose.foundation.layout',
    'BoxWithConstraints': 'androidx.compose.foundation.layout',
    # foundation
    'Canvas': 'androidx.compose.foundation',
    'rememberLazyListState': 'androidx.compose.foundation.lazy',
    'LazyColumn': 'androidx.compose.foundation.lazy',
    'LazyRow': 'androidx.compose.foundation.lazy',
    'items': 'androidx.compose.foundation.lazy',
    'MutableInteractionSource': 'androidx.compose.foundation.interaction',
    'RoundedCornerShape': 'androidx.compose.foundation.shape',
    'CircleShape': 'androidx.compose.foundation.shape',
    'CutCornerShape': 'androidx.compose.foundation.shape',
    'GenericShape': 'androidx.compose.foundation.shape',
    'BasicTextField': 'androidx.compose.foundation.text',
    'KeyboardActions': 'androidx.compose.foundation.text',
    'KeyboardOptions': 'androidx.compose.foundation.text',
    'SelectionContainer': 'androidx.compose.foundation.text',
    'detectTapGestures': 'androidx.compose.foundation.gestures',
    'detectDragGestures': 'androidx.compose.foundation.gestures',
    'detectVerticalDragGestures': 'androidx.compose.foundation.gestures',
    'rememberScrollableState': 'androidx.compose.foundation.gestures',
    'swipeable': 'androidx.compose.foundation.gestures',
    'awaitPointerEventScope': 'androidx.compose.ui.input.pointer',
    # material3
    'Text': 'androidx.compose.material3',
    'Surface': 'androidx.compose.material3',
    'Icon': 'androidx.compose.material3',
    'Divider': 'androidx.compose.material3',
    'HorizontalDivider': 'androidx.compose.material3',
    'VerticalDivider': 'androidx.compose.material3',
    'TextButton': 'androidx.compose.material3',
    'IconButton': 'androidx.compose.material3',
    'Button': 'androidx.compose.material3',
    'OutlinedButton': 'androidx.compose.material3',
    'Card': 'androidx.compose.material3',
    'Switch': 'androidx.compose.material3',
    'Checkbox': 'androidx.compose.material3',
    'RadioButton': 'androidx.compose.material3',
    'Slider': 'androidx.compose.material3',
    'CircularProgressIndicator': 'androidx.compose.material3',
    'LinearProgressIndicator': 'androidx.compose.material3',
    'MaterialTheme': 'androidx.compose.material3',
    'Scaffold': 'androidx.compose.material3',
    'DropdownMenu': 'androidx.compose.material3',
    'DropdownMenuItem': 'androidx.compose.material3',
    'AlertDialog': 'androidx.compose.material3',
    'ModalBottomSheet': 'androidx.compose.material3',
    'rememberModalBottomSheetState': 'androidx.compose.material3',
    'SnackbarHost': 'androidx.compose.material3',
    'SnackbarHostState': 'androidx.compose.material3',
    'LocalContentColor': 'androidx.compose.material3',
    # runtime
    'Composable': 'androidx.compose.runtime',
    'remember': 'androidx.compose.runtime',
    'mutableStateOf': 'androidx.compose.runtime',
    'rememberCoroutineScope': 'androidx.compose.runtime',
    'LaunchedEffect': 'androidx.compose.runtime',
    'DisposableEffect': 'androidx.compose.runtime',
    'derivedStateOf': 'androidx.compose.runtime',
    'getValue': 'androidx.compose.runtime',
    'setValue': 'androidx.compose.runtime',
    'produceState': 'androidx.compose.runtime',
    'rememberUpdatedState': 'androidx.compose.runtime',
    'snapshotFlow': 'androidx.compose.runtime',
    'CompositionLocalProvider': 'androidx.compose.runtime',
    'staticCompositionLocalOf': 'androidx.compose.runtime',
    'compositionLocalOf': 'androidx.compose.runtime',
    'SideEffect': 'androidx.compose.runtime',
    'key': 'androidx.compose.runtime',
    'rememberSaveable': 'androidx.compose.runtime.saveable',
    'Saver': 'androidx.compose.runtime.saveable',
    # ui 核心
    'Modifier': 'androidx.compose.ui',
    'Alignment': 'androidx.compose.ui',
    'Color': 'androidx.compose.ui.graphics',
    'Offset': 'androidx.compose.ui.geometry',
    'Size': 'androidx.compose.ui.geometry',
    'StrokeCap': 'androidx.compose.ui.graphics',
    'StrokeJoin': 'androidx.compose.ui.graphics',
    'Path': 'androidx.compose.ui.graphics',
    'PathEffect': 'androidx.compose.ui.graphics',
    'Brush': 'androidx.compose.ui.graphics',
    'SolidColor': 'androidx.compose.ui.graphics',
    'ImageBitmap': 'androidx.compose.ui.graphics',
    'DrawScope': 'androidx.compose.ui.graphics.drawscope',
    'Stroke': 'androidx.compose.ui.graphics.drawscope',
    'Fill': 'androidx.compose.ui.graphics.drawscope',
    'FontWeight': 'androidx.compose.ui.text.font',
    'FontStyle': 'androidx.compose.ui.text.font',
    'FontFamily': 'androidx.compose.ui.text.font',
    'TextAlign': 'androidx.compose.ui.text.style',
    'TextOverflow': 'androidx.compose.ui.text.style',
    'TextDecoration': 'androidx.compose.ui.text.style',
    'TextUnit': 'androidx.compose.ui.unit',
    'Dp': 'androidx.compose.ui.unit',
    'dp': 'androidx.compose.ui.unit',
    'sp': 'androidx.compose.ui.unit',
    'em': 'androidx.compose.ui.unit',
    'DpOffset': 'androidx.compose.ui.unit',
    'IntOffset': 'androidx.compose.ui.unit',
    'IntSize': 'androidx.compose.ui.unit',
    'ContentScale': 'androidx.compose.ui.layout',
    'ContentDescription': 'androidx.compose.ui.semantics',
    'Layout': 'androidx.compose.ui.layout',
    'LayoutModifier': 'androidx.compose.ui.layout',
    'LocalDensity': 'androidx.compose.ui.platform',
    'LocalContext': 'androidx.compose.ui.platform',
    'LocalConfiguration': 'androidx.compose.ui.platform',
    'LocalFocusManager': 'androidx.compose.ui.platform',
    'LocalSoftwareKeyboardController': 'androidx.compose.ui.platform',
    'LocalHapticFeedback': 'androidx.compose.ui.platform',
    'HapticFeedbackType': 'androidx.compose.ui.hapticfeedback',
    'FocusRequester': 'androidx.compose.ui.focus',
    'TextFieldValue': 'androidx.compose.ui.text.input',
    'KeyboardType': 'androidx.compose.ui.text.input',
    'ImeAction': 'androidx.compose.ui.text.input',
    'PasswordVisualTransformation': 'androidx.compose.ui.text.input',
    'VisualTransformation': 'androidx.compose.ui.text.input',
    'TextStyle': 'androidx.compose.ui.text',
    # kotlin 标准库顶层函数
    'min': 'kotlin.math',
    'max': 'kotlin.math',
    'abs': 'kotlin.math',
    'roundToInt': 'kotlin.math',
    'ceil': 'kotlin.math',
    'floor': 'kotlin.math',
    'sqrt': 'kotlin.math',
    'pow': 'kotlin.math',
    'log': 'kotlin.math',
    'exp': 'kotlin.math',
    'PI': 'kotlin.math',
    'coerceIn': 'kotlin.ranges',
    'coerceAtLeast': 'kotlin.ranges',
    'coerceAtMost': 'kotlin.ranges',
    'delay': 'kotlinx.coroutines',
    'launch': 'kotlinx.coroutines',
    'async': 'kotlinx.coroutines',
    'withContext': 'kotlinx.coroutines',
    'CoroutineScope': 'kotlinx.coroutines',
    'Job': 'kotlinx.coroutines',
    'Dispatchers': 'kotlinx.coroutines',
    'flow': 'kotlinx.coroutines.flow',
    'collect': 'kotlinx.coroutines.flow',
    'MutableStateFlow': 'kotlinx.coroutines.flow',
    'StateFlow': 'kotlinx.coroutines.flow',
    'Flow': 'kotlinx.coroutines.flow',
    'asStateFlow': 'kotlinx.coroutines.flow',
}

# 这些符号是「自动可用」的（Kotlin 默认导入），不需要 import
KOTLIN_DEFAULT = {
    'println', 'listOf', 'mapOf', 'setOf', 'mutableListOf', 'mutableMapOf',
    'mutableSetOf', 'arrayOf', 'emptyList', 'emptyMap', 'let', 'run', 'with',
    'apply', 'also', 'takeIf', 'takeUnless', 'require', 'check', 'error',
    'TODO', 'lazy', 'Pair', 'Triple', 'to', 'until', 'downTo', 'step',
    'String', 'Int', 'Long', 'Double', 'Float', 'Boolean', 'Char', 'Byte',
    'Short', 'Any', 'Unit', 'Nothing', 'List', 'Map', 'Set', 'Array',
    'Iterable', 'Sequence', 'Comparable', 'Throwable', 'Exception',
    'RuntimeException', 'IllegalArgumentException', 'IllegalStateException',
    'maxOf', 'minOf', 'maxOrNull', 'minOrNull', 'sumOf', 'repeat',
    'buildString', 'buildList', 'buildMap', 'synchronized', 'assert',
}


def strip_comments_and_strings(src):
    """去掉注释与字符串，只留代码。与 check_kotlin.py 同一套状态机。"""
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
        # raw string
        if c == '"' and src[i:i + 3] == '"""':
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


def collect_defined_names(src):
    """收集文件里定义的顶层/局部符号名（fun/val/var/class/object/enum）。"""
    names = set()
    for m in re.finditer(r'\b(?:fun|val|var|class|object|interface|enum\s+class)\s+'
                         r'(?:<[^>]*>\s*)?([A-Za-z_][A-Za-z0-9_]*)', src):
        names.add(m.group(1))
    # 扩展函数的接收者形式：fun Modifier.xxx(
    for m in re.finditer(r'\bfun\s+(?:[A-Za-z_][A-Za-z0-9_.<>]*\.)([A-Za-z_][A-Za-z0-9_]*)\s*\(', src):
        names.add(m.group(1))
    return names


def check_file(path, quiet=False):
    with open(path, encoding='utf-8') as f:
        raw = f.read()
    code = strip_comments_and_strings(raw)

    imports = set()
    for m in re.finditer(r'^\s*import\s+([A-Za-z0-9_.*]+)', raw, re.M):
        full = m.group(1)
        imports.add(full)
        imports.add(full.split('.')[-1])          # 简单名

    defined = collect_defined_names(raw)
    # 同包内的其他文件（同目录）也算「已定义」—— 交由调用方通过 extra_defined 传入
    defined |= EXTRA_DEFINED

    errors = []

    # ── 1. 缺失 import ─────────────────────────────────────
    # ⚠️ 两类必须分开匹配（分类错误 = 漏检，见文件头注释）：
    #   - 扩展符号：`Modifier.horizontalScroll(` / `horizontalScroll(` 都算
    #   - 顶层符号：只算 `Text(`，不算 `foo.Text(`
    for sym, pkg in EXTENSION_SYMBOLS.items():
        if sym in KOTLIN_DEFAULT or sym in imports or sym in defined:
            continue
        if re.search(r'(?<![A-Za-z0-9_])' + re.escape(sym) + r'\s*[(<]', code):
            errors.append(f"缺 import: {sym}  →  import {pkg}.{sym}")

    for sym, pkg in TOPLEVEL_SYMBOLS.items():
        if sym in KOTLIN_DEFAULT or sym in imports or sym in defined:
            continue
        if re.search(r'(?<![A-Za-z0-9_.])' + re.escape(sym) + r'\s*[(<]', code):
            errors.append(f"缺 import: {sym}  →  import {pkg}.{sym}")

    # ── 2. 未使用的 import ─────────────────────────────────
    # ⚠️ 扩展函数调用形如 `Modifier.background(`，简单名前面**有点号**，
    #    所以搜索时不能禁止 `.` 前缀 —— 否则每个扩展 import 都误报「未使用」。
    unused = []
    for m in re.finditer(r'^\s*import\s+([A-Za-z0-9_.]+)\s*$', raw, re.M):
        full = m.group(1)
        simple = full.split('.')[-1]
        if simple in ('getValue', 'setValue', 'provideDelegate'):
            continue
        if simple == '*':
            continue
        if not re.search(r'(?<![A-Za-z0-9_])' + re.escape(simple) + r'(?![A-Za-z0-9_])', code):
            unused.append(full)

    return errors, unused


EXTRA_DEFINED = set()


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    quiet = '--quiet' in sys.argv
    if not args:
        print("用法: check_imports.py <目录或文件>...")
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

    # 收集所有文件的定义名（跨文件引用同包函数时不误报）
    global EXTRA_DEFINED
    for f in files:
        try:
            with open(f, encoding='utf-8') as fh:
                EXTRA_DEFINED |= collect_defined_names(fh.read())
        except Exception:
            pass

    total_err = 0
    total_unused = 0
    for f in files:
        try:
            errors, unused = check_file(f, quiet)
        except Exception as e:
            print(f"⚠️  {f}: 解析失败 {e}")
            continue
        rel = os.path.relpath(f)
        if errors:
            total_err += len(errors)
            print(f"❌ {rel}")
            for e in errors:
                print(f"     {e}")
        elif not quiet:
            print(f"✓ {rel}")
        if unused:
            total_unused += len(unused)
            print(f"   ⚠️ 未使用 import ({len(unused)}): {', '.join(u.split('.')[-1] for u in unused)}")

    print()
    print(f"{len(files)} 个文件 · 缺失 import {total_err} 处 · 未使用 import {total_unused} 处")
    return 1 if total_err else 0


if __name__ == '__main__':
    sys.exit(main())
