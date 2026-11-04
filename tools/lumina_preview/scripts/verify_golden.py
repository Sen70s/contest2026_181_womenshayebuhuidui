#!/usr/bin/env python3
"""把本次渲染结果与 assets/golden/ 的参考图逐像素比较。

golden 是原型 harness 在同一台机器（gcc 11.4 / Ubuntu 22.04 / PIL 9.0.1）上
渲染出的参考图，已验证与我们完全一致（0 像素差异）。换编译器/换 Pillow 版本
可能出现少量浮点/抗锯齿差异，所以默认容忍 --tolerance 0.1%（每页独立计），
超过就退出码 1。

用法：
    python3 scripts/verify_golden.py <outdir> [--tolerance 0.001] [--max-delta 8]
"""
import argparse
import os
import sys

TOOL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GOLDEN = os.path.join(TOOL_DIR, "assets", "golden")
PAGES = [
    "page_01_watchface.png", "page_02_apps.png", "page_03_heart.png",
    "page_04_spo2.png", "page_05_sport.png", "page_06_rest.png",
    "page_07_alarm.png", "page_08_timer.png", "page_09_weather.png",
    "page_10_settings.png", "page_11_about.png", "page_12_ai.png",
    "page_13_expressions.png", "page_14_music.png",
]
EXTRA = ["contact_sheet.png"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("outdir", nargs="?", default=None)
    ap.add_argument("--tolerance", type=float, default=0.001,
                    help="允许的差异像素比例（默认 0.001 = 0.1%%）")
    ap.add_argument("--max-delta", type=int, default=255,
                    help="单像素最大允许色差（RGB 之和，默认 255 = 不限）")
    args = ap.parse_args()

    outdir = args.outdir or os.path.join(TOOL_DIR, "out")
    if not os.path.isdir(GOLDEN):
        print("没有 golden 参考图目录：%s（跳过验证）" % GOLDEN)
        return 0

    try:
        from PIL import Image
        import numpy as np
    except ImportError as e:
        print("缺依赖（Pillow/numpy）：%s" % e)
        return 2

    tot_diff = tot_px = 0
    rows = []
    for name in PAGES + EXTRA:
        a_path = os.path.join(outdir, name)
        b_path = os.path.join(GOLDEN, name)
        if not os.path.isfile(a_path):
            print("缺渲染结果: %s" % a_path)
            return 1
        if not os.path.isfile(b_path):
            continue
        a = np.asarray(Image.open(a_path).convert("RGB"), dtype=np.int16)
        b = np.asarray(Image.open(b_path).convert("RGB"), dtype=np.int16)
        if a.shape != b.shape:
            print("%-24s 尺寸不一致 %s vs %s" % (name, a.shape, b.shape))
            return 1
        d = np.abs(a - b).sum(axis=2)
        nd = int((d > 0).sum())
        mx = int(d.max())
        px = a.shape[0] * a.shape[1]
        tot_diff += nd
        tot_px += px
        rows.append((name, nd, px, mx))
        status = "IDENTICAL" if nd == 0 else "%.4f%% (maxdelta=%d)" % (100 * nd / px, mx)
        print("  %-24s %s" % (name, status))

    print("\n合计: %d/%d 像素不同 = %.6f%%" % (tot_diff, tot_px,
                                              (100 * tot_diff / tot_px) if tot_px else 0))
    bad = []
    for name, nd, px, mx in rows:
        ratio = nd / px if px else 0
        if ratio > args.tolerance or mx > args.max_delta:
            bad.append((name, ratio, mx))
    if bad:
        print("\n超出容忍度（tolerance=%.4f%%, max_delta=%d）:"
              % (100 * args.tolerance, args.max_delta))
        for name, ratio, mx in bad:
            print("  %-24s %.4f%% maxdelta=%d" % (name, 100 * ratio, mx))
        return 1
    print("-> 与 golden 一致（在容忍范围内）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
