#!/usr/bin/env python3
"""表情原图 PNG -> LVGL 9 RGB565A8 C 数组 + lv_image_dsc_t。

产物格式对照 packages/ai_agent/tools/gen_icons.py 的写法与
packages/ai_agent/src/ui/assets/ui_icons.c：

  - 数据布局：RGB565 平面（h*w 个小端 uint16，逐行）紧跟 A8 alpha 平面（h*w 字节）
              总字节 = w*h*3，RGB 平面 stride = w*2（ui_icons.c 里 .stride 就是这个）
  - lv_image_dsc_t：.header{magic=LV_IMAGE_HEADER_MAGIC, cf=RGB565A8, flags=0,
    w, h, stride=w*2, reserved_2=0}, .data_size, .data, .reserved=NULL, .reserved_2=NULL
  - LVGL 9.1 SW 渲染把 RGB565A8 当「RGB565 源 + A8 mask」混合，
    mask 起点 = src + stride*h（见 lv_draw_sw_img.c:210），mask_stride = stride/2 = w。
    所以**两个平面之间不能有任何行对齐填充**。

本文件从原 harness 搬入（原位置 /tmp/lumina/convert_expr.py），相对原文件只改了
路径参数：源目录默认 <tool>/assets/expressions，输出默认 <tool>/assets/generated。
图像算法（alpha bbox 裁剪 / 预乘 alpha 缩放 / RGB565A8 平面布局）与原文件逐字一致：
50 张 96/crop 产物 md5 与原 harness 相同（见 README「验证记录」）。

用法（一般由 run.sh 调用）：
    python3 scripts/convert_expr.py --size 96 --variant crop --all
        # -> <tool>/assets/generated/lumina_expressions.c（50 个 lv_image_dsc_t ui_expr_<id>）
    python3 scripts/convert_expr.py --size 96 --measure-only --all   # 只出字节表
"""
import argparse
import json
import os
import struct
import sys

# 工具根 = scripts/ 的上一级；路径可由 --src / --out 覆盖，不依赖 cwd
TOOL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_DIR = os.path.join(TOOL_DIR, "assets", "expressions")
OUT_DIR = os.path.join(TOOL_DIR, "assets", "generated")

# 原型先转 3 张代表性原图：
#   hai_xiu  900x900 ，alpha bbox 818x624（ratio 1.49 = 50 张的中位数）-> "标准方图"
#   jing_ya  2100x2100，alpha bbox 2002x1034（2100 组里最宽）      -> "大图"
#   le       900x900 ，alpha bbox 860x478（900 组里最扁，ratio 1.80）-> "内容很扁"
REPS = [
    ("hai_xiu", "emoji_cloud_hai_xiu.png"),
    ("jing_ya", "emoji_cloud_jing_ya.png"),
    ("le", "emoji_cloud_le.png"),
]


def load_rgba(path):
    from PIL import Image
    return Image.open(path).convert("RGBA")


def alpha_bbox(im, thr):
    """按 alpha>=thr 求墨迹外框；thr 用来甩掉抗锯齿边缘 alpha=1..7 的噪声。"""
    a = im.getchannel("A")
    if thr <= 1:
        return a.getbbox() or (0, 0, im.width, im.height)
    m = a.point(lambda v: 255 if v >= thr else 0)
    return m.getbbox() or (0, 0, im.width, im.height)


def resize_premul(im, dst_w, dst_h, box):
    """预乘 alpha 后缩放再除回来。

    原图透明区的 RGB 不是 0（实测 wei_xiao 透明像素 RGB 均值 190.6，max 255），
    直接对 RGBA 做 LANCZOS 会把透明区的白色 RGB 混进边缘，产生白边/灰边；
    先乘 alpha 再缩放，边缘就只剩真正有墨迹的像素贡献。缩放完必须除回来，
    因为 LVGL 按 straight alpha（非预乘）混合。
    """
    import numpy as np
    from PIL import Image

    a = np.asarray(im, dtype=np.float32)
    rgb = a[:, :, :3]
    al = a[:, :, 3:4] / 255.0
    prem = np.concatenate([rgb * al, a[:, :, 3:4]], axis=2)
    pim = Image.fromarray(prem.astype(np.uint8), "RGBA")
    pim = pim.resize((dst_w, dst_h), Image.LANCZOS)

    b = np.asarray(pim, dtype=np.float32)
    nb = b[:, :, 3:4] / 255.0
    with np.errstate(divide="ignore", invalid="ignore"):
        urgb = np.where(nb > 0.004, b[:, :, :3] / np.maximum(nb, 1e-6), 0.0)
    out = np.concatenate([np.clip(urgb, 0, 255), b[:, :, 3:4]], axis=2)
    return Image.fromarray(out.astype(np.uint8), "RGBA")


def resize_plain(im, dst_w, dst_h, box):
    # gen_icons.py 的做法（直接对 RGBA 缩），留着做对照
    from PIL import Image
    return im.resize((dst_w, dst_h), Image.LANCZOS)


def make_variant(im, size, variant, thr, resample):
    """返回最终要烘焙的 RGBA 图（可能不是正方形）。"""
    from PIL import Image

    resize = resize_premul if resample == "premul" else resize_plain

    if variant == "pad":
        # 等比缩进 size×size 画布，居中；和 gen_icons.py 的 convert() 一致
        w, h = im.size
        if w != h:
            im = im.copy()
            im.thumbnail((size, size), Image.LANCZOS)
            sc = max(im.width, im.height)
            im = resize(im, round(im.width * size / sc), round(im.height * size / sc), None)
            canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
            canvas.paste(im, ((size - im.width) // 2, (size - im.height) // 2))
            return canvas
        return resize(im, size, size, None)

    if variant == "crop":
        # 先按 alpha bbox 裁掉透明留白，再把长边缩到 size（保持宽高比）
        x0, y0, x1, y1 = alpha_bbox(im, thr)
        im = im.crop((x0, y0, x1, y1))
        bw, bh = im.size
        if bw >= bh:
            dw, dh = size, max(1, round(bh * size / bw))
        else:
            dw, dh = max(1, round(bw * size / bh)), size
        return resize(im, dw, dh, None)

    raise ValueError(variant)


def rgb565(r, g, b):
    return ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3)


def to_rgb565a8(im):
    """RGB565 平面（小端 uint16，逐行） + A8 平面。不预乘（LVGL 用 straight alpha）。"""
    w, h = im.size
    px = im.load()
    color = []
    alpha = []
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            color.append(rgb565(r, g, b))
            alpha.append(a)
    data = struct.pack("<%dH" % len(color), *color) + bytes(alpha)
    assert len(data) == w * h * 3
    return data, w, h


def emit_c(name, im):
    """照 ui_icons.c 逐字段生成。"""
    data, w, h = to_rgb565a8(im)
    total = len(data)

    L = []
    A = L.append
    A(f"/* {name}: {w}x{h} RGB565A8, {total} B */")
    A(f"static const uint8_t expr_{name}_map[{total}] = {{")
    for i in range(0, total, 16):
        A("    " + ", ".join("0x%02x" % b for b in data[i:i + 16]) + ",")
    A("};")
    A("")
    A(f"const lv_image_dsc_t ui_expr_{name} = {{")
    A("    .header = {")
    A("        .magic = LV_IMAGE_HEADER_MAGIC,")
    A("        .cf = LV_COLOR_FORMAT_RGB565A8,")
    A("        .flags = 0,")
    A(f"        .w = {w},")
    A(f"        .h = {h},")
    A(f"        .stride = {w * 2},")
    A("        .reserved_2 = 0,")
    A("    },")
    A(f"    .data_size = {total},")
    A(f"    .data = expr_{name}_map,")
    A("    .reserved = NULL,")
    A("    .reserved_2 = NULL,")
    A("};")
    A("")
    return "\n".join(L), total, (w, h)


def png_banner(extra=""):
    return "\n".join([
        "/****************************************************************************",
        " * lumina_expressions.c（构建中间件，run.sh 自动生成，勿手改）",
        " *",
        " * 来源: <tool>/assets/expressions/*.png（LuminaWatch CloudAvatars 原始表情图）",
        " * 生成: python3 scripts/convert_expr.py --size 96 --variant crop --all",
        " * 格式: RGB565A8（RGB565 平面 + A8 平面，stride = w*2），与 ui_icons.c 一致",
        f" * {extra}",
        " ****************************************************************************/",
        "",
        "#include <lvgl/lvgl.h>",
        "",
    ])


def measure(src_dir, files, sizes, variants, thr, resample, verbose=True):
    """所有 (size, variant) 的逐张字节数。

    注：原 harness 这里漏了 src_dir 形参（用了 main() 里的局部变量），
    --measure-all-sizes 路径会直接 NameError。搬移时补上（见 README「原 harness 的问题」）。
    """
    table = {}
    detail = {}
    for variant in variants:
        for size in sizes:
            key = f"{variant}{size}"
            tot = 0
            rows = []
            maxw = maxh = 0
            for fname in files:
                im = load_rgba(os.path.join(src_dir, fname))
                out = make_variant(im, size, variant, thr, resample)
                n = out.width * out.height * 3
                tot += n
                maxw = max(maxw, out.width)
                maxh = max(maxh, out.height)
                rows.append((fname, out.width, out.height, n))
            table[key] = tot
            detail[key] = {"total": tot, "max_w": maxw, "max_h": maxh, "rows": rows}
            if verbose:
                print(f"  {key:>8s}: 50 张合计 {tot:>9,d} B ({tot/1024:>8.1f} KiB), "
                      f"最大 {maxw}x{maxh}")
    return table, detail


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--size", type=int, default=120)
    ap.add_argument("--variant", choices=["pad", "crop"], default="pad")
    ap.add_argument("--src", default=SRC_DIR,
                    help="表情原图目录（默认 <tool>/assets/expressions）")
    ap.add_argument("--out", default=OUT_DIR,
                    help="输出目录（默认 <tool>/assets/generated）")
    ap.add_argument("--all", action="store_true", help="转全部 50 张（默认只转 3 张代表图）")
    ap.add_argument("--measure-only", action="store_true")
    ap.add_argument("--measure-all-sizes", action="store_true",
                    help="对 50 张同时实测 96/120/144 的 pad 与 crop 变体")
    ap.add_argument("--alpha-thr", type=int, default=8)
    ap.add_argument("--resample", choices=["premul", "plain"], default="premul")
    args = ap.parse_args()

    src_dir = args.src
    files = sorted(os.listdir(src_dir))
    files = [f for f in files if f.endswith(".png")]
    if not files:
        sys.exit(f"没有找到 PNG: {src_dir}")

    if args.measure_all_sizes:
        print(f"[实测] {len(files)} 张，resample={args.resample}, alpha_thr={args.alpha_thr}")
        table, detail = measure(src_dir, files, [96, 120, 144], ["pad", "crop"],
                                args.alpha_thr, args.resample)
        os.makedirs(args.out, exist_ok=True)
        with open(os.path.join(args.out, "sizes.json"), "w") as fp:
            json.dump({"table": table,
                       "detail": {k: {"total": v["total"], "max_w": v["max_w"],
                                      "max_h": v["max_h"],
                                      "rows": [[r[0], r[1], r[2], r[3]] for r in v["rows"]]}
                                  for k, v in detail.items()},
                       "png_count": len(files),
                       "resample": args.resample,
                       "alpha_thr": args.alpha_thr}, fp, indent=1)
        print(f"  -> {args.out}/sizes.json")
        return

    targets = [(f[12:-4].replace("emoji_cloud_", ""), f) for f in files] if args.all \
        else REPS
    os.makedirs(args.out, exist_ok=True)

    total = 0
    print(f"[{args.variant}{args.size}] resample={args.resample} "
          f"alpha_thr={args.alpha_thr} -> {args.out}")
    parts = []
    for name, fname in targets:
        im = load_rgba(os.path.join(src_dir, fname))
        out = make_variant(im, args.size, args.variant,
                           args.alpha_thr, args.resample)
        code, n, wh = emit_c(name, out)
        total += n
        parts.append(code)
        print(f"  {name:18s} {fname:34s} -> {wh[0]}x{wh[1]}  {n:7,d} B")

    if not args.measure_only:
        hdr = png_banner(f"变体={args.variant}{args.size} 张数={len(targets)} "
                         f"合计={total} B")
        p = os.path.join(args.out, "lumina_expressions.c")
        with open(p, "w") as fp:
            fp.write(hdr + "\n".join(parts))
        print(f"\n合计 {total:,d} B ({total/1024:.1f} KiB) -> {p}")
        print("  C 文件大小 %d B（含语法/缩进开销，flash 里只占前者）"
              % os.path.getsize(p))


if __name__ == "__main__":
    main()
