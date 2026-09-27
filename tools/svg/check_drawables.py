#!/usr/bin/env python3
"""
VectorDrawable AAPT 兼容性检查器（纯 Python，Termux 可跑）。

## 为什么需要它
Android SDK 的 aapt2 是 x86_64 二进制，Termux（arm64）跑不了。
但 AAPT 的**颜色属性校验规则**很简单，可以用 Python 复现 —— 这样在 push 前
就能发现「fillColor="black" 这类不合法值」，不用等 CI 3 分钟。

## 检查项
1. `fillColor` / `strokeColor` / `tint` / `color` 的值必须是：
   - `#RGB` / `#ARGB` / `#RRGGBB` / `#AARRGGBB`
   - `@color/xxx` / `@android:color/xxx` 资源引用
   - `?attr/xxx` 主题属性引用
   **不接受** CSS 颜色关键字（black / white / red…）
2. `android:pathData` 必须非空
3. 根元素必须是 `<vector>`
4. `viewportWidth` / `viewportHeight` 必须为正数

用法：
    python3 check_drawables.py <drawable目录>
"""
import re
import sys
import glob
import os
import xml.etree.ElementTree as ET

ANDROID_NS = 'http://schemas.android.com/apk/res/android'

# 颜色属性的合法格式
COLOR_RE = re.compile(r'^#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$')
REF_RE = re.compile(r'^[@?][\w:/]+$')

COLOR_ATTRS = ('fillColor', 'strokeColor', 'tint', 'color')


def check_color(value, path, attr, errors):
    """校验一个颜色属性值。"""
    if value is None:
        return
    v = value.strip()
    if COLOR_RE.match(v) or REF_RE.match(v):
        return
    # 常见错误：CSS 关键字
    if v.lower() in ('black', 'white', 'red', 'green', 'blue', 'yellow', 'gray',
                     'grey', 'transparent', 'none'):
        errors.append(
            f'{path}: android:{attr}="{v}" —— 颜色关键字不被 AAPT 接受，'
            f'请用十六进制（如 #000000）'
        )
    elif v.startswith('rgb') or v.startswith('hsl'):
        errors.append(
            f'{path}: android:{attr}="{v}" —— CSS 函数式颜色不被支持，请转成十六进制'
        )
    else:
        errors.append(f'{path}: android:{attr}="{v}" —— 无法识别的颜色格式')


def check_file(path):
    """检查单个 XML，返回错误列表。"""
    errors = []
    try:
        tree = ET.parse(path)
    except ET.ParseError as e:
        return [f'{path}: XML 解析失败 —— {e}']

    root = tree.getroot()
    if root.tag != 'vector':
        errors.append(f'{path}: 根元素是 <{root.tag}>，应为 <vector>')

    # viewport 尺寸
    for attr in ('viewportWidth', 'viewportHeight'):
        v = root.get(f'{{{ANDROID_NS}}}{attr}')
        if v is None:
            errors.append(f'{path}: 缺少 android:{attr}')
        else:
            try:
                if float(v) <= 0:
                    errors.append(f'{path}: android:{attr}="{v}" 必须为正数')
            except ValueError:
                errors.append(f'{path}: android:{attr}="{v}" 不是合法数字')

    # 逐 path 检查
    for i, elem in enumerate(root.iter()):
        if elem.tag not in ('path', 'group', 'clip-path'):
            continue
        for attr in COLOR_ATTRS:
            check_color(elem.get(f'{{{ANDROID_NS}}}{attr}'), path, attr, errors)
        if elem.tag == 'path':
            d = elem.get(f'{{{ANDROID_NS}}}pathData')
            if not d or not d.strip():
                errors.append(f'{path}: 第 {i} 个 <path> 的 pathData 为空')

    return errors


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(2)

    target = sys.argv[1]
    files = sorted(glob.glob(os.path.join(target, '*.xml')))
    if not files:
        print(f'未找到 XML 文件：{target}')
        sys.exit(2)

    all_errors = []
    for f in files:
        errs = check_file(f)
        if errs:
            all_errors.extend(errs)

    print(f'检查 {len(files)} 个 VectorDrawable')
    if all_errors:
        print(f'\n✗ 发现 {len(all_errors)} 个问题：')
        for e in all_errors[:40]:
            print(f'  {e}')
        if len(all_errors) > 40:
            print(f'  … 还有 {len(all_errors) - 40} 个')
        sys.exit(1)
    else:
        print('✓ 全部通过 AAPT 颜色/结构校验')
        sys.exit(0)


if __name__ == '__main__':
    main()
