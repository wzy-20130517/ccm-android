#!/usr/bin/env python3
"""
CCM 截图 diff 工具 —— 像素级零变化验证。

用法：
    python3 diff.py <baseline.png> <actual.png> [--threshold 0.1] [--heatmap out.png]

输出：
    差异像素数 / 总像素数 / 差异率 / 最大通道偏移 / 差异区域包围盒
    退出码：0=通过（差异率 <= threshold），1=不通过，2=错误

依赖：Pillow（Termux: pkg install python-pil 或 pip install pillow）
"""
import sys
import argparse
from pathlib import Path

try:
    from PIL import Image, ImageChops
except ImportError:
    print("错误：需要 Pillow。Termux 上跑 `pip install pillow`", file=sys.stderr)
    sys.exit(2)


def load_rgb(path):
    """统一转 RGB，去掉 alpha 通道干扰（Web 截图可能带 alpha）"""
    im = Image.open(path)
    if im.mode in ("RGBA", "LA", "P"):
        # 用白底合成，与浏览器渲染一致
        bg = Image.new("RGB", im.size, (255, 255, 255))
        im = im.convert("RGBA")
        bg.paste(im, mask=im.split()[-1])
        return bg
    return im.convert("RGB")


def diff_images(base_path, actual_path, threshold_pct=0.1, heatmap_path=None,
                ignore_alpha=True, per_channel_tol=0):
    """
    per_channel_tol: 单通道容差（0-255）。抗锯齿/字体 hinting 会产生 1-2 的抖动，
                    设成 2~3 能过滤掉"无意义"的差异。
    """
    base = load_rgb(base_path)
    actual = load_rgb(actual_path)

    if base.size != actual.size:
        return {
            "error": "尺寸不一致",
            "baseline_size": base.size,
            "actual_size": actual.size,
            "hint": "先确认两边视口/dpr 一致（Playwright CSS 393×852，真机 screencap 需换算）",
        }

    w, h = base.size
    total = w * h

    if per_channel_tol > 0:
        # 逐通道差值，超过容差的才算差异
        b = base.load()
        a = actual.load()
        diff_count = 0
        max_delta = 0
        minx, miny, maxx, maxy = w, h, -1, -1
        heat = Image.new("RGB", (w, h), (0, 0, 0)) if heatmap_path else None
        hp = heat.load() if heat else None
        for y in range(h):
            for x in range(w):
                pb, pa = b[x, y], a[x, y]
                d = max(abs(pb[0]-pa[0]), abs(pb[1]-pa[1]), abs(pb[2]-pa[2]))
                if d > max_delta:
                    max_delta = d
                if d > per_channel_tol:
                    diff_count += 1
                    if hp:
                        hp[x, y] = (255, min(255, d * 4), 0)
                    if x < minx: minx = x
                    if y < miny: miny = y
                    if x > maxx: maxx = x
                    if y > maxy: maxy = y
        if heat:
            heat.save(heatmap_path)
    else:
        d = ImageChops.difference(base, actual)
        # ⚠️ 不能直接 d.convert("L")：灰度权重是 0.299R+0.587G+0.114B，
        # 单通道的小差异会被四舍五入抹平（实测：蓝通道 -3 → 灰度差 0.34 → 0），
        # 而那正是零变化验证最需要检出的"细微色差"。改为逐通道取最大值。
        dr, dg, db = d.split()
        dmax = ImageChops.lighter(ImageChops.lighter(dr, dg), db)
        bbox = dmax.getbbox()
        hist = dmax.histogram()
        diff_count = total - hist[0]
        max_delta = max(i for i, c in enumerate(hist) if c > 0) if diff_count else 0
        minx, miny, maxx, maxy = bbox if bbox else (0, 0, 0, 0)
        if heatmap_path:
            dmax.point(lambda v: min(255, v * 4)).save(heatmap_path)

    pct = diff_count * 100.0 / total
    return {
        "size": f"{w}x{h}",
        "total_pixels": total,
        "diff_pixels": diff_count,
        "diff_pct": round(pct, 4),
        "max_channel_delta": max_delta,
        "bbox": [minx, miny, maxx, maxy] if diff_count else None,
        "passed": pct <= threshold_pct,
        "threshold_pct": threshold_pct,
    }


def main():
    ap = argparse.ArgumentParser(description="CCM 截图 diff")
    ap.add_argument("baseline")
    ap.add_argument("actual")
    ap.add_argument("--threshold", type=float, default=0.1,
                    help="差异率阈值%%，默认 0.1")
    ap.add_argument("--heatmap", help="输出差异热力图路径")
    ap.add_argument("--tol", type=int, default=0,
                    help="单通道容差(0-255)，抗锯齿抖动建议 2~3")
    args = ap.parse_args()

    for p in (args.baseline, args.actual):
        if not Path(p).exists():
            print(f"错误：文件不存在 {p}", file=sys.stderr)
            sys.exit(2)

    r = diff_images(args.baseline, args.actual, args.threshold,
                    args.heatmap, per_channel_tol=args.tol)

    if "error" in r:
        print(f"✗ {r['error']}")
        print(f"  baseline: {r['baseline_size']}")
        print(f"  actual  : {r['actual_size']}")
        print(f"  {r['hint']}")
        sys.exit(2)

    print(f"尺寸        : {r['size']} ({r['total_pixels']} px)")
    print(f"差异像素    : {r['diff_pixels']} ({r['diff_pct']}%)")
    print(f"最大通道偏移: {r['max_channel_delta']}")
    if r["bbox"]:
        print(f"差异包围盒  : {r['bbox']}")
    if args.heatmap:
        print(f"热力图      : {args.heatmap}")
    print(f"判定        : {'✓ 通过' if r['passed'] else '✗ 不通过'} (阈值 {r['threshold_pct']}%)")

    sys.exit(0 if r["passed"] else 1)


if __name__ == "__main__":
    main()
