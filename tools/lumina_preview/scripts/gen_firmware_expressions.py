#!/usr/bin/env python3
"""把表达式 PNG 烘焙成固件用的 ui_expressions.c（RGB565A8 C 数组）。

这是「设计资产 -> 固件资产」这一步的唯一入口：批次 3 的 8 张表情就是用它
生成的，全量 50 张也走同一条路。产物直接落到
packages/ai_agent/src/ui/assets/ui_expressions.c。

为什么不用 LVGL 运行时解码：
  本构建未开 LV_USE_FS_* 也没有 PNG 解码器，固件只能引用编译期常量位图。
  格式与 assets/ui_icons.c 一致（RGB565 平面 + A8 平面，stride = w*2），
  渲染路径零解码、零 PSRAM。

★ 索引顺序 = CloudAvatars.kt 的顺序（= 本仓
  tools/lumina_preview/assets/reference/lumina_expressions_order.txt 或
  原型 firmware-ui/lumina_expressions.c 的顺序）。**不要按文件名排序**，
  也不要"挑好看的几个"：表盘、表情页、/data/expression 里存的数字都依赖
  这个顺序；将来扩到 50 款时只在尾部追加，已存索引不迁移。

用法：
    # 前 8 款（当前固件出货的批次 3 形态）
    python3 tools/lumina_preview/scripts/gen_firmware_expressions.py --count 8

    # 全量 50 款（体积约 1.4 MB，需确认 flash 余量）
    python3 tools/lumina_preview/scripts/gen_firmware_expressions.py --all

    # 只预览不写盘
    python3 tools/lumina_preview/scripts/gen_firmware_expressions.py --count 8 --dry-run
"""
import argparse
import os
import subprocess
import sys

TOOL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
WORKSPACE = os.path.abspath(os.path.join(TOOL_DIR, '..', '..', '..'))
FIRMWARE_OUT = os.path.join(WORKSPACE, 'packages', 'ai_agent', 'src', 'ui',
                            'assets', 'ui_expressions.c')

HEADER = '''/****************************************************************************
 * ui_expressions.c - 小云表情位图（RGB565A8，编译期常量）
 *
 * 来源: LuminaWatch 的 CloudAvatars 原始 PNG（emoji_cloud_<id>.png）
 * 生成: contest2026_181_womenshayebuhuidui/tools/lumina_preview/scripts/gen_firmware_expressions.py
 *       本质调用 convert_expr.py: --size {size} --variant crop
 *       （alpha bbox 裁掉透明留白后把长边缩到 {size}px）
 * 格式: RGB565A8 —— RGB565 平面(小端 uint16, stride=w*2) 紧跟 A8 平面；
 *       与 assets/ui_icons.c 完全一致的布局，运行时零解码。
 *
 * ⚠️ 本文件是**构建产物**，不要手改。要换尺寸/换图：
 *    1. 把新 PNG 放进 tools/lumina_preview/assets/expressions/
 *    2. 跑上面的 gen_firmware_expressions.py
 *
 * 本文件内容: 前 {count} 款（CloudAvatars 顺序），合计 {total:,} B（{kib:.1f} KiB）。
 ****************************************************************************/
'''


def reference_order():
    """从原型表里读 CloudAvatars 顺序（不复用文件名排序）。

    原型位置优先 /tmp/lumina/watch-ui-v1/firmware-ui/lumina_expressions.c，
    其次 tools/lumina_preview/assets/reference/lumina_expressions.c。
    """
    cands = [
        os.path.join(TOOL_DIR, 'assets', 'reference_lumina_expressions.c'),
        '/tmp/lumina/watch-ui-v1/firmware-ui/lumina_expressions.c',
    ]
    for path in cands:
        if not os.path.exists(path):
            continue
        ids = []
        for line in open(path, encoding='utf-8'):
            line = line.strip()
            if line.startswith('{"'):
                ids.append(line.split('"')[1])
        if ids:
            return ids, path
    return None, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--count', type=int, default=8, help='烘焙前 N 款（默认 8）')
    ap.add_argument('--all', action='store_true', help='烘焙全部 50 款')
    ap.add_argument('--size', type=int, default=120, help='长边像素（默认 120）')
    ap.add_argument('--out', default=FIRMWARE_OUT, help='产物路径')
    ap.add_argument('--dry-run', action='store_true')
    args = ap.parse_args()

    order, src = reference_order()
    if not order:
        print('ERROR: 找不到 CloudAvatars 顺序参考表（原型 firmware-ui/lumina_expressions.c）')
        return 2
    print('顺序参考: %s (%d 款)' % (src, len(order)))

    ids = order if args.all else order[:args.count]
    src_dir = os.path.join(TOOL_DIR, 'assets', 'expressions')
    missing = [i for i in ids if not os.path.exists(os.path.join(src_dir, 'emoji_cloud_%s.png' % i))]
    if missing:
        print('ERROR: 缺源图: %s' % missing)
        return 2

    tmp_src = '/tmp/lumina_gen_expr_src'
    tmp_out = '/tmp/lumina_gen_expr_out'
    subprocess.run(['rm', '-rf', tmp_src, tmp_out], check=True)
    os.makedirs(tmp_src)
    os.makedirs(tmp_out)
    for i in ids:
        subprocess.run(['cp', os.path.join(src_dir, 'emoji_cloud_%s.png' % i), tmp_src], check=True)

    conv = os.path.join(TOOL_DIR, 'scripts', 'convert_expr.py')
    r = subprocess.run([sys.executable, conv, '--size', str(args.size),
                        '--variant', 'crop', '--src', tmp_src,
                        '--out', tmp_out, '--all'],
                       capture_output=True, text=True)
    sys.stdout.write(r.stdout)
    if r.returncode != 0:
        sys.stderr.write(r.stderr)
        return r.returncode

    produced = os.path.join(tmp_out, 'lumina_expressions.c')
    body = open(produced, encoding='utf-8').read()
    # 去掉 convert_expr 自己的横幅（到第一个 #include 之前）
    inc = body.find('#include <lvgl/lvgl.h>')
    if inc < 0:
        print('ERROR: 产物里找不到 #include')
        return 2
    body = body[inc:]

    # 统计总字节数（从 "合计 N B" 一行里取）
    total = 0
    for line in r.stdout.splitlines():
        if line.strip().startswith('合计') and ' B ' in line:
            raw = line.split('合计')[1].split('B')[0].strip().replace(',', '')
            total = int(raw)
            break

    out = HEADER.format(size=args.size, count=len(ids), total=total,
                        kib=total / 1024.0) + body

    if args.dry_run:
        print('[dry-run] 会写 %s (%d 字节)' % (args.out, len(out)))
        return 0

    with open(args.out, 'w', encoding='utf-8') as f:
        f.write(out)
    print('已写入 %s（%d 款，%d B）' % (args.out, len(ids), total))
    return 0


if __name__ == '__main__':
    sys.exit(main())
