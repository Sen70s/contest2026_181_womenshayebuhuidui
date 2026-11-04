#!/usr/bin/env python3
"""从仓内 LVGL 9.1 lv_conf_template.h 生成 host preview 用的 lv_conf.h。

SRC = <仓内 LVGL>/lv_conf_template.h
OUT = <tool>/build/lv_conf.h

只有本文件里的 OVERRIDES 是“host 预览”的选择，别的一律沿用模板默认值 ——
这样 LVGL 升级时只要模板宏名不变，本脚本就不用改。

注：真机固件用的是它自己的 lv_conf（在 packages/ai_agent 或 board defconfig 里），
这里生成的配置只服务 PC 预览，不代表真机配置。
"""
import argparse
import os
import re
import sys

# 默认路径由环境变量（run.sh 导出）或本文件位置推导，两者都找不到才报错
TOOL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def default_lvgl_dir():
    env = os.environ.get("LVGL_DIR")
    if env:
        return env
    # tool 位于 REPO/tools/lumina_preview，LVGL 在 workspace 的 apps/ 下
    repo = os.path.dirname(os.path.dirname(TOOL_DIR))
    vendor = os.path.dirname(repo)          # workspace（REPO 的上一级）
    return os.path.join(vendor, "apps", "graphics", "lvgl", "lvgl")


def default_out():
    env = os.environ.get("LV_CONF")
    if env:
        return env
    return os.path.join(TOOL_DIR, "build", "lv_conf.h")


OVERRIDES = {
    # 屏幕/内存
    "LV_COLOR_DEPTH": "16",
    "LV_MEM_SIZE": "(4 * 1024 * 1024U)",
    "LV_DEF_REFR_PERIOD": "16",
    # host 平台：无 OS、无 NuttX、无 SDL
    "LV_USE_OS": "LV_OS_NONE",
    "LV_USE_NUTTX": "0",
    "LV_USE_SDL": "0",
    "LV_USE_LOG": "0",
    "LV_USE_PERF_MONITOR": "0",
    "LV_USE_MEM_MONITOR": "0",
    # 标准库：用 clib，避免内置 malloc 池在 host 上反复申请 4MB
    "LV_USE_STDLIB_MALLOC": "LV_STDLIB_CLIB",
    "LV_USE_STDLIB_STRING": "LV_STDLIB_CLIB",
    "LV_USE_STDLIB_SPRINTF": "LV_STDLIB_CLIB",
    # SW 渲染
    "LV_USE_DRAW_SW": "1",
    # 字体
    "LV_FONT_MONTSERRAT_48": "1",
    "LV_FONT_SIMSUN_16_CJK": "1",
    "LV_FONT_DEFAULT": "&lv_font_montserrat_14",
    "LV_USE_FONT_PLACEHOLDER": "1",
    # 主题
    "LV_USE_THEME_DEFAULT": "1",
    "LV_USE_THEME_SIMPLE": "0",
    "LV_USE_FLEX": "1",
    "LV_USE_GRID": "1",
    "LV_USE_ASSERT_NULL": "1",
    "LV_USE_ASSERT_MALLOC": "1",
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=None,
                    help="lv_conf_template.h 路径（默认 <LVGL>/lv_conf_template.h）")
    ap.add_argument("--out", default=default_out(),
                    help="输出 lv_conf.h 路径（默认 <tool>/build/lv_conf.h）")
    args = ap.parse_args()

    lvgl = os.environ.get("LVGL_DIR") or default_lvgl_dir()
    src = args.src or os.path.join(lvgl, "lv_conf_template.h")
    if not os.path.isfile(src):
        sys.exit("找不到 lv_conf_template.h: %s\n"
                 "  换个位置跑时用 LUMINA_LVGL_DIR 指定 LVGL 源码目录" % src)

    s = open(src, encoding="utf-8").read()
    s = s.replace('#if 0 /*Set it to "1" to enable content*/',
                  '#if 1 /*Set it to "1" to enable content*/', 1)
    assert "#if 1" in s, "enable-content guard not patched (LVGL 模板结构变了?)"

    missing = []
    for name, val in OVERRIDES.items():
        pat = re.compile(r'(#define\s+' + name + r'\s+)(\S.*)')
        s2, n = pat.subn(lambda m: m.group(1) + val, s, count=1)
        if n != 1:
            missing.append(name)
            continue
        s = s2
    if missing:
        sys.exit("模板里找不到这些宏（LVGL 版本不匹配？）: %s" % missing)

    out = args.out
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with open(out, "w", encoding="utf-8") as fp:
        fp.write(s)
    print("生成 %s (%d bytes)" % (out, len(s)))


if __name__ == "__main__":
    main()
