#!/usr/bin/env python3
"""生成表情 id -> lv_image_dsc_t 指针表（expr_dsc_map.c）。

素材关系：
  * <tool>/src/lumina_expressions.c（上游 UI 自带）
       按索引定义 50 个 {id, label, source_png}
  * <tool>/assets/generated/lumina_expressions.c（convert_expr.py 产物）
       定义 50 个 const lv_image_dsc_t ui_expr_<id>

本脚本按索引把两者绑定，供 lumina_ui_port_t.expression_image 查表。
上游的数组顺序是「唯一的真相」：id 顺序错了表情就错位，所以这里做硬校验
（数量必须是 50、每个 id 必须有对应 dsc），不匹配直接退出非 0。
"""
import argparse
import os
import re
import sys

TOOL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_UI = os.path.join(TOOL_DIR, "src", "lumina_expressions.c")
GEN_ASSET = os.path.join(TOOL_DIR, "assets", "generated", "lumina_expressions.c")
OUT = os.path.join(TOOL_DIR, "assets", "generated", "expr_dsc_map.c")
EXPECTED = 50


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ui-src", default=SRC_UI)
    ap.add_argument("--asset", default=GEN_ASSET)
    ap.add_argument("--out", default=OUT)
    args = ap.parse_args()

    if not os.path.isfile(args.ui_src):
        sys.exit("找不到上游表情表: %s" % args.ui_src)
    if not os.path.isfile(args.asset):
        sys.exit("找不到表情资产: %s（先跑 scripts/convert_expr.py）" % args.asset)

    src = open(args.ui_src, encoding="utf-8").read()
    ids = re.findall(r'\{"([a-z_0-9]+)", "([^"\n]+)"', src)
    have = set(re.findall(
        r'const lv_image_dsc_t ui_expr_([a-z_0-9]+) =',
        open(args.asset, encoding="utf-8").read()))

    if len(ids) != EXPECTED:
        sys.exit("解析到 %d 个表情，期望 %d（上游 lumina_expressions.c 变了吗？）"
                 % (len(ids), EXPECTED))
    missing = [eid for eid, _ in ids if eid not in have]
    if missing:
        sys.exit("这些 id 在表情资产里没有对应 lv_image_dsc_t: %s" % missing)

    L = [
        "/* 自动生成：表情 id -> crop96 lv_image_dsc_t 指针表（勿手改） */",
        "/* 生成脚本: scripts/gen_expr_map.py；顺序来自 src/lumina_expressions.c */",
        '#include "lvgl.h"',
        '#include "lumina_expressions.h"',
        "",
        "extern const lv_image_dsc_t " + ",\n    ".join("ui_expr_" + eid for eid, _ in ids) + ";",
        "",
        "/* 顺序与 lumina_expressions.c 的索引一一对应 */",
        "const lv_image_dsc_t *const lumina_expr_dsc[LUMINA_EXPRESSION_COUNT] = {",
    ]
    for i, (eid, label) in enumerate(ids):
        L.append('    [%2d] = &ui_expr_%s,   /* %-18s %s */' % (i, eid, eid, label))
    L.append("};")
    L.append("")
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as fp:
        fp.write("\n".join(L) + "\n")
    print("OK: %d 个表情映射 -> %s" % (len(ids), args.out))


if __name__ == "__main__":
    main()
