#!/usr/bin/env python3
"""RGB565（小端）raw -> PNG。

LVGL 的 16bit 帧缓冲是 RGB565，主机小端存放：host_preview 直接把 g_fb 原样
fwrite 出来，所以这里按小端解析。展开方式 5bit -> 8bit 用「*255//31」，
这是原型 harness 的做法；换成 (v<<3)|(v>>2) 会让低位结果差 1..3 个色阶，
所以要与参考 PNG 像素级对齐就必须保留这个展开公式。

默认由 run.sh 调用：
    python3 scripts/raw2png.py <rawdir> <outdir> --manifest <file>
"""
import argparse
import os
import sys

W_DEFAULT, H_DEFAULT = 390, 450


def convert(rawpath, pngpath, w, h):
    from PIL import Image
    data = open(rawpath, "rb").read()
    want = w * h * 2
    if len(data) != want:
        sys.exit("%s: 期望 %d 字节 (%dx%d RGB565)，实际 %d"
                 % (rawpath, want, w, h, len(data)))
    im = Image.new("RGB", (w, h))
    px = im.load()
    for i in range(w * h):
        v = data[i * 2] | (data[i * 2 + 1] << 8)
        px[i % w, i // w] = (((v >> 11) & 0x1F) * 255 // 31,
                             ((v >> 5) & 0x3F) * 255 // 63,
                             (v & 0x1F) * 255 // 31)
    im.save(pngpath)
    return im


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("rawdir", nargs="?", default=None, help=".raw 所在目录")
    ap.add_argument("outdir", nargs="?", default=None, help="PNG 输出目录")
    ap.add_argument("--manifest", default=None,
                    help="页面清单（默认 <rawdir>/page_manifest.txt）")
    ap.add_argument("--w", type=int, default=W_DEFAULT)
    ap.add_argument("--h", type=int, default=H_DEFAULT)
    args = ap.parse_args()

    if args.rawdir is None:
        sys.exit("用法: raw2png.py <rawdir> <outdir> [--manifest FILE]")
    rawdir = args.rawdir
    outdir = args.outdir or rawdir
    manifest = args.manifest or os.path.join(rawdir, "page_manifest.txt")
    if not os.path.isfile(manifest):
        sys.exit("找不到页面清单: %s" % manifest)

    os.makedirs(outdir, exist_ok=True)
    names = open(manifest, encoding="utf-8").read().split()
    if not names:
        sys.exit("页面清单是空的: %s" % manifest)

    for i, name in enumerate(names, 1):
        # 清单里是 page_01_watchface.png，raw 文件是 page_watchface.raw
        base = name.replace("page_", "").rsplit(".png", 1)[0]
        page = base.split("_", 1)[1]
        src = os.path.join(rawdir, "page_%s.raw" % page)
        dst = os.path.join(outdir, "page_%02d_%s.png" % (i, page))
        convert(src, dst, args.w, args.h)
        print(dst)


if __name__ == "__main__":
    main()
