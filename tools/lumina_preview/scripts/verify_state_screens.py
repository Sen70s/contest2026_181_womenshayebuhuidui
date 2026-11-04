#!/usr/bin/env python3
"""校验 out/state_<page>__<state>.png：存在、390x450、非全黑/全白/纯色。

额外给出「与 out/our_<page>.png 默认态的差异像素占比」，用来发现
"状态注入没生效、画面和默认态一模一样"的情况（只报告，不作为失败判据）。

用法：
    python3 scripts/verify_state_screens.py out
    python3 scripts/verify_state_screens.py out apps__page2 timer__finished
"""
import os
import sys

from PIL import Image, ImageChops, ImageStat

W, H = 390, 450


def read_manifest(tool_dir):
    path = os.path.join(tool_dir, "scripts", "state_screens.tsv")
    rows = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 3:
                continue
            rows.append((parts[0], parts[1], parts[2]))
    return rows


def check_one(path):
    if not os.path.isfile(path):
        return False, "missing", None

    im = Image.open(path).convert("RGB")
    if im.size != (W, H):
        return False, "size %dx%d != %dx%d" % (im.size[0], im.size[1], W, H), None

    stat = ImageStat.Stat(im)
    mean = sum(stat.mean) / 3.0
    std = sum(stat.stddev) / 3.0
    colors = im.getcolors(maxcolors=1 << 20)
    ncolors = len(colors) if colors is not None else 1 << 20
    top_frac = (max(c for c, _ in colors) / float(W * H)) if colors else 0.0

    info = {
        "mean": mean,
        "std": std,
        "colors": ncolors,
        "top": top_frac,
    }

    if mean < 3.0 and std < 2.0:
        return False, "all black (mean %.1f std %.1f)" % (mean, std), info
    if mean > 252.0 and std < 2.0:
        return False, "all white (mean %.1f std %.1f)" % (mean, std), info
    if std < 2.0:
        return False, "flat / no content (std %.1f)" % std, info
    if ncolors < 16:
        return False, "too few colors (%d)" % ncolors, info
    if top_frac > 0.999:
        return False, "single color covers %.1f%%" % (top_frac * 100.0), info

    return True, "ok", info


def diff_vs_default(outdir, page, im):
    ref_path = os.path.join(outdir, "our_%s.png" % page)
    if not os.path.isfile(ref_path):
        return None
    ref = Image.open(ref_path).convert("RGB")
    if ref.size != im.size:
        return None
    diff = ImageChops.difference(ref, im)
    # 任一通道差 > 8 的像素算"肉眼可见差异"
    mask = diff.convert("L").point(lambda v: 255 if v > 8 else 0)
    changed = sum(1 for v in mask.getdata() if v)
    return changed * 100.0 / float(W * H)


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: verify_state_screens.py <outdir> [key ...]")
    outdir = sys.argv[1]
    wanted = set(sys.argv[2:])

    tool_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    rows = read_manifest(tool_dir)
    if wanted:
        rows = [r for r in rows if "%s__%s" % (r[0], r[1]) in wanted]

    print()
    print("%-24s %-8s %8s %8s %8s %8s  %s" %
          ("state", "size", "mean", "stddev", "colors", "diff%", "result"))
    print("-" * 92)
    failed = []
    for page, state, _label in rows:
        key = "%s__%s" % (page, state)
        path = os.path.join(outdir, "state_%s.png" % key)
        ok, msg, info = check_one(path)
        diff = None
        if ok:
            im = Image.open(path).convert("RGB")
            diff = diff_vs_default(outdir, page, im)
        if ok:
            print("%-24s %-8s %8.1f %8.1f %8d %8s  OK" %
                  (key, "%dx%d" % (W, H), info["mean"], info["std"],
                   info["colors"],
                   "-" if diff is None else "%.1f" % diff))
        else:
            if info is None:
                print("%-24s %-8s %8s %8s %8s %8s  FAIL  %s" %
                      (key, "-", "-", "-", "-", "-", msg))
            else:
                print("%-24s %-8s %8.1f %8.1f %8d %8s  FAIL  %s" %
                      (key, "%dx%d" % (W, H), info["mean"], info["std"],
                       info["colors"],
                       "-" if diff is None else "%.1f" % diff, msg))
            failed.append((key, msg))

    print("-" * 92)
    print("%d/%d state screenshots passed" % (len(rows) - len(failed), len(rows)))
    if failed:
        print("FAILED:")
        for key, msg in failed:
            print("  %-24s %s" % (key, msg))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
