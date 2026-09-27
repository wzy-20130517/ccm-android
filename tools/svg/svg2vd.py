#!/usr/bin/env python3
"""
SVG → Android VectorDrawable 转换器（CCM 专用）。

针对本项目 SVG 的特点：
- 单/多 <path>，fill 用 var(--fill-0, #XXXXXX) 或直接色值
- 无渐变/滤镜（hero-star 有 filter 但可忽略）
- viewBox 用浮点数（非整数）

用法：
    python3 svg2vd.py <in.svg> <out.xml> --name ic_xxx [--size 24] [--fill #7B7974]
"""
import re, sys, argparse
from pathlib import Path

def parse_svg(text):
    vb = re.search(r'viewBox="([^"]+)"', text)
    if not vb:
        raise ValueError('no viewBox')
    parts = [float(x) for x in re.split(r'[\s,]+', vb.group(1).strip())]
    vw, vh = parts[2], parts[3]
    paths = []
    for m in re.finditer(r'<path\b([^>]*)/?>', text, re.S):
        attrs = m.group(1)
        d = re.search(r'\bd="([^"]+)"', attrs)
        if not d:
            continue
        fill = None
        fm = re.search(r'fill="([^"]+)"', attrs)
        if fm:
            fill = fm.group(1)
        # var(--fill-0, #7B7974) → #7B7974
        if fill:
            vm = re.search(r'var\([^,]+,\s*([^)]+)\)', fill)
            if vm:
                fill = vm.group(1).strip()
        opacity = None
        om = re.search(r'fill-opacity="([^"]+)"', attrs)
        if om:
            opacity = om.group(1)
        # fill-rule
        fr = re.search(r'fill-rule="([^"]+)"', attrs)
        paths.append({'d': d.group(1).strip(), 'fill': fill, 'opacity': opacity,
                      'fillRule': fr.group(1) if fr else None})
    return vw, vh, paths

def to_android_path(d):
    """SVG path → Android pathData（主要差异：Android 不支持隐式重复命令的某些写法，但基本兼容）"""
    # Android 的 pathData 与 SVG 语法基本一致，直接透传
    # 唯一要注意的是极小的浮点精度，保留原样
    return d

def convert(svg_path, out_path, name, size=24, default_fill='#000000'):
    text = Path(svg_path).read_text(encoding='utf-8')
    vw, vh, paths = parse_svg(text)
    if not paths:
        raise ValueError('no paths found')

    # viewport 用原始 viewBox 尺寸（保持浮点，避免坐标缩放误差）
    lines = []
    lines.append('<?xml version="1.0" encoding="utf-8"?>')
    lines.append('<!--')
    lines.append(f'  自动生成，请勿手改 —— 由 tools/svg/svg2vd.py 从')
    lines.append(f'  web/src/assets/{Path(svg_path).parent.name}/{Path(svg_path).name} 转换而来。')
    lines.append('')
    lines.append(f'  viewBox: {vw} x {vh}（保留原始浮点，不做取整以免坐标偏移）')
    lines.append('-->')
    lines.append(f'<vector xmlns:android="http://schemas.android.com/apk/res/android"')
    lines.append(f'    android:width="{size}dp"')
    lines.append(f'    android:height="{size}dp"')
    lines.append(f'    android:viewportWidth="{vw}"')
    lines.append(f'    android:viewportHeight="{vh}">')

    for p in paths:
        fill = p['fill'] or default_fill
        if fill in ('none', 'transparent'):
            continue
        attrs = [f'        android:pathData="{p["d"]}"',
                 f'        android:fillColor="{fill}"']
        if p['fillRule'] == 'evenodd':
            attrs.append('        android:fillType="evenOdd"')
        if p['opacity']:
            attrs.append(f'        android:fillAlpha="{p["opacity"]}"')
        lines.append('    <path')
        lines.append('\n'.join(attrs) + ' />')

    lines.append('</vector>')
    Path(out_path).write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return len(paths)

if __name__ == '__main__':
    ap = argparse.ArgumentParser()
    ap.add_argument('svg'); ap.add_argument('out')
    ap.add_argument('--name', required=True)
    ap.add_argument('--size', type=int, default=24)
    ap.add_argument('--fill', default='#000000')
    a = ap.parse_args()
    n = convert(a.svg, a.out, a.name, a.size, a.fill)
    print(f'✓ {a.out}  ({n} paths)')
