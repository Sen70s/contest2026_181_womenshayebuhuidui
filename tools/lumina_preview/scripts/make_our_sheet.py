#!/usr/bin/env python3
"""14 张固件页面 PNG -> 4x4 contact sheet，每格标注页名。

输入（均由 preview_our_pages.sh 生成）：
    out/our_watch.png ... out/our_expr.png
输出：
    out/our_pages_contact_sheet.png
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

W, H = 390, 450
SCALE = 0.58
TW, TH = int(W * SCALE), int(H * SCALE)

COLS, ROWS = 4, 4
PAD = 14
TITLE_H = 54
LABEL_H = 30
CELL_W = TW + PAD * 2
CELL_H = TH + LABEL_H + PAD
SHEET_W = COLS * CELL_W + PAD * 2
SHEET_H = TITLE_H + ROWS * CELL_H + PAD

PAGES = [
    ("our_watch.png",    "01 WATCHFACE 表盘"),
    ("our_apps.png",     "02 APPLIST 应用"),
    ("our_heart.png",    "03 HEART 心率"),
    ("our_spo2.png",     "04 SPO2 血氧"),
    ("our_music.png",    "05 MUSIC 音乐"),
    ("our_timer.png",    "06 TIMER 计时器"),
    ("our_sport.png",    "07 SPORT 运动"),
    ("our_sleep.png",    "08 SLEEP 休息"),
    ("our_settings.png", "09 SETTINGS 设置"),
    ("our_ai.png",       "10 AI 小云"),
    ("our_about.png",    "11 ABOUT 关于"),
    ("our_alarm.png",    "12 ALARM 闹钟"),
    ("our_weather.png",  "13 WEATHER 天气"),
    ("our_expr.png",     "14 EXPRESSION 表情"),
]

TITLE = "Team 181 · 固件真实 UI · 14 页 host LVGL 预览"
SUBTITLE = "390x450 RGB565 · flush_cb 直采 · 不含 SDL / 真机"

NOTES = [
    ("渲染方式", [
        "真机同一份 LVGL / 字体 / 图标 / ui_theme.c",
        "心率、血氧、步数 = 无传感器空状态",
        "天气 = 共享缓存演示值，不联网",
    ]),
    ("复现命令", [
        "bash tools/lumina_preview/scripts/",
        "  preview_our_pages.sh",
        "",
        "先跑过一次 run.sh 生成 host LVGL",
    ]),
]

FONT_CANDIDATES = [
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Bold.ttc",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
]


def pick_font():
    for p in FONT_CANDIDATES:
        if os.path.exists(p):
            return p
    sys.exit("找不到 Noto Sans CJK 字体（contact sheet 的中文标签需要它）")


def fit_font(font_path, text, max_width, start=17, minimum=10):
    size = start
    while size > minimum:
        font = ImageFont.truetype(font_path, size)
        if font.getlength(text) <= max_width:
            return font
        size -= 1
    return ImageFont.truetype(font_path, minimum)


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "out")
    font_cjk = pick_font()

    missing = [n for n, _ in PAGES if not os.path.isfile(os.path.join(outdir, n))]
    if missing:
        sys.exit("缺页面 PNG: %s" % missing)

    f_title = ImageFont.truetype(font_cjk, 23)
    f_sub = ImageFont.truetype(font_cjk, 13)
    f_note_title = ImageFont.truetype(font_cjk, 16)
    f_note = ImageFont.truetype(font_cjk, 12)

    sheet = Image.new("RGB", (SHEET_W, SHEET_H), (250, 251, 252))
    d = ImageDraw.Draw(sheet)
    d.text((PAD + 4, 10), TITLE, fill=(23, 42, 61), font=f_title)
    d.text((PAD + 5, 36), SUBTITLE, fill=(120, 130, 140), font=f_sub)

    for i, (fn, label) in enumerate(PAGES):
        row, col = divmod(i, COLS)
        x = PAD + col * CELL_W
        y = TITLE_H + row * CELL_H
        im = Image.open(os.path.join(outdir, fn)).convert("RGB")
        if im.size != (W, H):
            sys.exit("%s: 尺寸 %s，期望 %dx%d" % (fn, im.size, W, H))
        im = im.resize((TW, TH), Image.LANCZOS)

        # 卡片底 + 细边框
        d.rounded_rectangle(
            [x + 2, y + 2, x + 2 + TW + 3, y + 2 + TH + 3],
            radius=5, fill=(235, 240, 245))
        d.rounded_rectangle(
            [x + 1, y + 1, x + 1 + TW + 3, y + 1 + TH + 3],
            radius=5, outline=(180, 196, 208), width=1)
        sheet.paste(im, (x + 3, y + 3))

        f_label = fit_font(font_cjk, label, TW)
        d.text((x + 3, y + TH + 9), label, fill=(23, 42, 61), font=f_label)

    # 第 15/16 格：说明与复现命令，把 4x4 网格填满
    for j, (heading, lines) in enumerate(NOTES):
        idx = len(PAGES) + j
        row, col = divmod(idx, COLS)
        x = PAD + col * CELL_W + 4
        y = TITLE_H + row * CELL_H + 8
        d.text((x, y), heading, fill=(23, 42, 61), font=f_note_title)
        yy = y + 26
        for line in lines:
            d.text((x, yy), line, fill=(90, 104, 118), font=f_note)
            yy += 19

    out = os.path.join(outdir, "our_pages_contact_sheet.png")
    sheet.save(out)
    print("contact sheet %dx%d -> %s" % (SHEET_W, SHEET_H, out))


if __name__ == "__main__":
    main()
