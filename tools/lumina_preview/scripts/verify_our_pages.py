#!/usr/bin/env python3
"""校验 out/our_<page>.png：存在、尺寸、非全黑/全白（像素方差）。

用法：
    python3 scripts/verify_our_pages.py out
    python3 scripts/verify_our_pages.py out watch heart
"""
import os
import statistics
import sys

from PIL import Image, ImageStat

W, H = 390, 450
ALL_PAGES = [
    "watch", "apps", "heart", "spo2", "music", "timer", "sport",
    "sleep", "settings", "ai", "about", "alarm", "weather", "expr",
]


def check_one(path):
    if not os.path.isfile(path):
        return False, "missing", None

    im = Image.open(path).convert("RGB")
    if im.size != (W, H):
        return False, "size %dx%d != %dx%d" % (im.size[0], im.size[1], W, H), None

    stat = ImageStat.Stat(im)
    mean = sum(stat.mean) / 3.0
    std = sum(stat.stddev) / 3.0
    extrema = im.getextrema()
    colors = im.getcolors(maxcolors=1 << 20)
    ncolors = len(colors) if colors is not None else 1 << 20
    top_frac = (max(c for c, _ in colors) / float(W * H)) if colors else 0.0

    info = {
        "mean": mean,
        "std": std,
        "colors": ncolors,
        "top": top_frac,
        "extrema": extrema,
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


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: verify_our_pages.py <outdir> [page ...]")
    outdir = sys.argv[1]
    pages = sys.argv[2:] if len(sys.argv) > 2 else ALL_PAGES

    print()
    print("%-10s %-8s %8s %8s %8s %7s  %s" %
          ("page", "size", "mean", "stddev", "colors", "top%", "result"))
    print("-" * 78)
    failed = []
    for p in pages:
        path = os.path.join(outdir, "our_%s.png" % p)
        ok, msg, info = check_one(path)
        if ok:
            print("%-10s %-8s %8.1f %8.1f %8d %7.1f  OK" %
                  (p, "%dx%d" % (W, H), info["mean"], info["std"],
                   info["colors"], info["top"] * 100.0))
        else:
            if info is None:
                print("%-10s %-8s %8s %8s %8s %7s  FAIL  %s" %
                      (p, "-", "-", "-", "-", "-", msg))
            else:
                print("%-10s %-8s %8.1f %8.1f %8d %7.1f  FAIL  %s" %
                      (p, "%dx%d" % (W, H), info["mean"], info["std"],
                       info["colors"], info["top"] * 100.0, msg))
            failed.append(p)

    print("-" * 78)
    print("%d/%d pages passed" % (len(pages) - len(failed), len(pages)))
    if failed:
        print("FAILED: %s" % " ".join(failed))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
