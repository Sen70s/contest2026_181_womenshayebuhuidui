#!/usr/bin/env python3
"""14 页 PNG -> 3x5 contact sheet，每格左上角写页面名。

版式与原型 harness 完全一致（缩放 0.38、3 列 5 行、Noto Sans CJK Bold 标签），
这样 contact_sheet.png 也能与参考图对齐。
"""
import argparse
import os
import sys

W, H = 390, 450
SCALE = 0.38
TW, TH = int(W * SCALE), int(H * SCALE)
COLS, ROWS = 3, 5
PAD, TITLE, LABEL = 14, 44, 26
CELL_W = TW + PAD * 2
CELL_H = TH + LABEL + PAD * 2

NAMES = [
    ("page_01_watchface.png",   "01 表盘 WATCHFACE"),
    ("page_02_apps.png",        "02 应用入口 APPS"),
    ("page_03_heart.png",       "03 心率 HEART"),
    ("page_04_spo2.png",        "04 血氧 SPO2"),
    ("page_05_sport.png",       "05 运动 SPORT"),
    ("page_06_rest.png",        "06 休息 REST"),
    ("page_07_alarm.png",       "07 闹钟 ALARM"),
    ("page_08_timer.png",       "08 计时器 TIMER"),
    ("page_09_weather.png",     "09 天气 WEATHER"),
    ("page_10_settings.png",    "10 设置 SETTINGS"),
    ("page_11_about.png",       "11 关于 ABOUT"),
    ("page_12_ai.png",          "12 小云 AI"),
    ("page_13_expressions.png", "13 表情 EXPRESSIONS"),
    ("page_14_music.png",       "14 音乐 MUSIC"),
]

TITLE_TXT = "Lumina Watch UI · LVGL 9.1 host preview"

FONT_CANDIDATES = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc",
]


def pick_font():
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            return p
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("outdir", nargs="?", default=None, help="PNG 所在目录，也是输出目录")
    args = ap.parse_args()

    outdir = args.outdir or os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "out")
    font_cjk = pick_font()
    if font_cjk is None:
        sys.exit("找不到 Noto Sans CJK Bold（contact sheet 的中文标签需要它）:\n  %s"
                 % "\n  ".join(FONT_CANDIDATES))

    from PIL import Image, ImageDraw, ImageFont

    f_label = ImageFont.truetype(font_cjk, 17)
    f_title = ImageFont.truetype(font_cjk, 24)
    f_small = ImageFont.truetype(font_cjk, 13)

    missing = [n for n, _ in NAMES if not os.path.isfile(os.path.join(outdir, n))]
    if missing:
        sys.exit("缺页面 PNG: %s" % missing)

    _probe = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    _tw = _probe.textlength(TITLE_TXT, font=f_title)
    SW = int(max(COLS * CELL_W + PAD, _tw + PAD * 2))
    X0 = int((SW - COLS * CELL_W) // 2)
    SH = TITLE + ROWS * CELL_H + PAD
    sheet = Image.new("RGB", (SW, SH), (250, 251, 252))
    d = ImageDraw.Draw(sheet)
    d.text((X0 + 6, 10), TITLE_TXT, fill=(23, 42, 61), font=f_title)

    for i, (fn, label) in enumerate(NAMES):
        r, c = divmod(i, COLS)
        x = X0 + c * CELL_W
        y = TITLE + r * CELL_H
        im = Image.open(os.path.join(outdir, fn)).convert("RGB").resize((TW, TH), Image.LANCZOS)
        d.rounded_rectangle([x + 6, y + 6, x + 6 + TW + 1, y + 6 + TH + 1],
                            radius=4, outline=(180, 196, 208))
        sheet.paste(im, (x + 7, y + 7))
        # 标签按单元格宽度自动缩字号，避免相邻格文字互撞
        fs = 17
        while fs > 10 and _probe.textlength(
                label, font=ImageFont.truetype(font_cjk, fs)) > TW - 2:
            fs -= 1
        d.text((x + 7, y + 7 + TH + 4), label, fill=(23, 42, 61),
               font=ImageFont.truetype(font_cjk, fs))

    note_y = SH - 18
    d.text((X0 + 6, note_y - 12),
           "示意数据，非真机读数 · 字体为真机子集近似 · 无 SDL：flush 直采 RGB565",
           fill=(120, 130, 140), font=f_small)
    out = os.path.join(outdir, "contact_sheet.png")
    sheet.save(out)
    print("contact_sheet.png %dx%d -> %s" % (sheet.size[0], sheet.size[1], out))


if __name__ == "__main__":
    main()
