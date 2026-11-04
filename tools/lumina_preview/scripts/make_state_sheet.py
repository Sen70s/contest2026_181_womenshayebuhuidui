#!/usr/bin/env python3
"""22 张关键状态 PNG -> 5x5 contact sheet，每格标注 page__state + 中文状态。

输入：
    out/state_<page>__<state>.png   （清单：scripts/state_screens.tsv）
    out/our_<page>.png              （可选，用于校验时对比默认态）
输出：
    out/state_screens_contact_sheet.png
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

W, H = 390, 450
SCALE = 0.50
TW, TH = int(W * SCALE), int(H * SCALE)

COLS, ROWS = 5, 5
PAD = 12
TITLE_H = 62
LABEL_H = 40
CELL_W = TW + PAD * 2
CELL_H = TH + LABEL_H + PAD
SHEET_W = COLS * CELL_W + PAD * 2
SHEET_H = TITLE_H + ROWS * CELL_H + PAD

TITLE = "Team 181 · 固件真实 UI · 22 个关键状态 host LVGL 预览"
SUBTITLE = ("390x450 RGB565 · 数据为占位/夹具；不碰真机、不烧录 · "
            "对应 docs_ble/91 §9 P1")

NOTES = [
    ("注入方式", [
        "状态夹具：preview_set_state()",
        "点击：lv_obj_send_event(CLICKED)",
        "手势：ui_gesture 回调记录 + preview_swipe()",
        "时间：lv_tick_inc() + lv_timer_handler()",
    ]),
    ("数据说明", [
        "心率/血氧/步数/休息均为夹具",
        "天气：缓存命中 / 失败 / 旧缓存三态",
        "闹钟：内存 cron 任务表，不落盘",
        "AI 记录：内存 4 条，不读真实历史",
    ]),
    ("复现命令", [
        "bash tools/lumina_preview/scripts/",
        "  preview_our_states.sh",
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


def fit_font(font_path, text, max_width, start=16, minimum=9):
    size = start
    while size > minimum:
        font = ImageFont.truetype(font_path, size)
        if font.getlength(text) <= max_width:
            return font
        size -= 1
    return ImageFont.truetype(font_path, minimum)


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


def main():
    outdir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "out")
    tool_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    font_cjk = pick_font()
    rows = read_manifest(tool_dir)
    if len(rows) != 22:
        sys.exit("state_screens.tsv 应为 22 行，实际 %d 行" % len(rows))

    f_title = ImageFont.truetype(font_cjk, 22)
    f_sub = ImageFont.truetype(font_cjk, 12)
    f_note_title = ImageFont.truetype(font_cjk, 15)
    f_note = ImageFont.truetype(font_cjk, 11)

    sheet = Image.new("RGB", (SHEET_W, SHEET_H), (250, 251, 252))
    d = ImageDraw.Draw(sheet)
    d.text((PAD + 4, 10), TITLE, fill=(23, 42, 61), font=f_title)
    d.text((PAD + 5, 38), SUBTITLE, fill=(120, 130, 140), font=f_sub)

    missing = []
    for i, (page, state, label) in enumerate(rows):
        row, col = divmod(i, COLS)
        x = PAD + col * CELL_W
        y = TITLE_H + row * CELL_H
        key = "%s__%s" % (page, state)
        src = os.path.join(outdir, "state_%s.png" % key)

        d.rounded_rectangle(
            [x + 1, y + 1, x + 1 + TW + 4, y + 1 + TH + 4],
            radius=5, fill=(235, 240, 245))
        d.rounded_rectangle(
            [x + 1, y + 1, x + 1 + TW + 4, y + 1 + TH + 4],
            radius=5, outline=(180, 196, 208), width=1)

        if os.path.isfile(src):
            im = Image.open(src).convert("RGB")
            if im.size != (W, H):
                sys.exit("%s: 尺寸 %s，期望 %dx%d" % (src, im.size, W, H))
            im = im.resize((TW, TH), Image.LANCZOS)
            sheet.paste(im, (x + 3, y + 3))
        else:
            missing.append(key)
            d.rectangle([x + 3, y + 3, x + 3 + TW, y + 3 + TH],
                        fill=(255, 240, 240))
            f_miss = fit_font(font_cjk, "MISSING", TW, start=18)
            d.text((x + 3 + 18, y + 3 + TH // 2 - 10), "MISSING",
                   fill=(180, 40, 40), font=f_miss)

        f_key = fit_font(font_cjk, key, TW, start=13, minimum=9)
        d.text((x + 3, y + TH + 7), key, fill=(23, 42, 61), font=f_key)
        f_label = fit_font(font_cjk, label, TW, start=12, minimum=9)
        d.text((x + 3, y + TH + 23), label, fill=(90, 104, 118), font=f_label)

    # 最后 3 格：说明
    for j, (heading, lines) in enumerate(NOTES):
        idx = len(rows) + j
        row, col = divmod(idx, COLS)
        x = PAD + col * CELL_W + 4
        y = TITLE_H + row * CELL_H + 8
        d.text((x, y), heading, fill=(23, 42, 61), font=f_note_title)
        yy = y + 25
        for line in lines:
            d.text((x, yy), line, fill=(90, 104, 118), font=f_note)
            yy += 17

    out = os.path.join(outdir, "state_screens_contact_sheet.png")
    sheet.save(out)
    print("contact sheet %dx%d -> %s" % (SHEET_W, SHEET_H, out))
    if missing:
        print("WARNING missing %d: %s" % (len(missing), " ".join(missing)))


if __name__ == "__main__":
    main()
