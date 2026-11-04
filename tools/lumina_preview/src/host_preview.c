/**
 * host_preview.c — 在 PC 上把 lumina watch UI 的 14 个页面渲染成 raw 帧（无 SDL、无真机）
 *
 * 设计：
 *   1. lv_init() + 390x450 全屏 RGB565 draw buffer（LV_DISPLAY_RENDER_MODE_FULL）
 *   2. flush_cb 把像素拷进全局帧缓冲（不碰任何 SDL / framebuffer 驱动）
 *   3. lumina_ui_init / set_state / show 走真实代码路径
 *   4. 每页跑若干次 lv_timer_handler() 直到没有新的 invalidate，再把 RGB565 写成 .raw
 *   5. 对象树缺陷扫描（缺字/截断/重叠/触控区/越屏）写 scan.txt
 *   6. Python/PIL 负责 RGB565(小端) -> PNG 与 contact sheet
 *
 * 用法: ./host_preview <outdir>
 *   outdir 由 run.sh 传入（通常是 <tool>/out），本程序不硬编码任何绝对路径。
 *   输出 <outdir>/page_XX_NAME.raw（390*450*2 字节，RGB565 小端）
 *        <outdir>/scan.txt（对象树缺陷扫描报告）
 *        <outdir>/page_manifest.txt（页面名 -> 文件对应，供 raw2png.py 使用）
 */
#include "lvgl.h"
#include "lumina_watch_ui.h"
#include "lumina_expressions.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#define W 390
#define H 450

extern const lv_font_t lv_font_simsun_16_cjk;
extern const lv_font_t lv_font_montserrat_48;
extern const lv_font_t ui_font_cjk_24;
extern const lv_font_t ui_font_cjk_18;
extern const lv_font_t ui_font_sans_88_bold;
extern const lv_image_dsc_t *const lumina_expr_dsc[LUMINA_EXPRESSION_COUNT];

/* ---------------- 帧缓冲 ---------------- */
static uint16_t g_fb[W * H];
static uint32_t g_flush_count;
static bool g_flush_seen;

static void flush_cb(lv_display_t *disp, const lv_area_t *area, uint8_t *px_map)
{
    int32_t w = lv_area_get_width(area);
    int32_t h = lv_area_get_height(area);
    for (int32_t y = 0; y < h; y++) {
        int32_t dy = area->y1 + y;
        if (dy < 0 || dy >= H) continue;
        int32_t x0 = area->x1 < 0 ? 0 : area->x1;
        int32_t x1 = area->x2 >= W ? W - 1 : area->x2;
        const uint16_t *src = (const uint16_t *)(px_map + (size_t)y * w * 2);
        for (int32_t x = x0; x <= x1; x++)
            g_fb[dy * W + x] = src[x - area->x1];
    }
    g_flush_count++;
    g_flush_seen = true;
    lv_display_flush_ready(disp);
}

/* ---------------- port 适配层 ---------------- */
static const void *port_expression_image(uint8_t index, bool thumbnail, void *ctx)
{
    (void)thumbnail; (void)ctx;
    if (index >= LUMINA_EXPRESSION_COUNT) index = 0;
    return lumina_expr_dsc[index];
}

static unsigned g_action_count;
static void port_action(lumina_action_t action, int32_t value, void *ctx)
{
    (void)ctx;
    g_action_count++;
    printf("[action] %d value=%d\n", (int)action, (int)value);
}

/* ---------------- 示意数据（非真机读数！） ---------------- */
static char g_date[32], g_time[16], g_weather[64], g_weather_upd[32];
static char g_ai_text[64], g_care_reason[64], g_fw[32], g_device[32];
static char g_heart_upd[32], g_spo2_upd[32], g_rest[64], g_music[64];

static void fill_demo_state(lumina_ui_state_t *s)
{
    memset(s, 0, sizeof(*s));
    strcpy(g_time, "10:28");
    strcpy(g_date, "11月2日 · 周一");
    strcpy(g_weather, "多云 18°C 湿度 62%");
    strcpy(g_weather_upd, "10:20 更新");
    strcpy(g_ai_text, "我在听，今天想聊什么？");
    strcpy(g_care_reason, "久坐 48 分钟，起身走一走");
    strcpy(g_fw, "Vela OS 1.4.2 · build 1102");
    strcpy(g_device, "小云手表 · SF32LB52");
    strcpy(g_heart_upd, "10:14 测量");
    strcpy(g_spo2_upd, "10:15 测量");
    strcpy(g_rest, "昨夜休息 7 小时 12 分，深睡 1 小时 48 分");
    strcpy(g_music, "晴天 - 周杰伦");

    s->date = g_date;
    s->time = g_time;
    s->weather_text = g_weather;
    s->weather_updated = g_weather_upd;
    s->ai_text = g_ai_text;
    s->care_reason = g_care_reason;
    s->firmware_version = g_fw;
    s->device_name = g_device;
    s->heart_updated = g_heart_upd;
    s->spo2_updated = g_spo2_upd;
    s->rest_text = g_rest;
    s->music_title = g_music;

    s->heart_bpm = 72;
    s->spo2_percent = 97;
    s->steps = 2418;
    s->distance_m = 1730;
    s->active_minutes = 36;
    s->timer_seconds = 300;
    s->brightness_percent = 70;
    s->selected_expression = 0;
    s->connected = true;
    s->dnd = false;
    s->timer_running = false;
    s->music_playing = false;
    s->care_pending = true;
    s->heart_status = LUMINA_STATUS_OK;
    s->spo2_status = LUMINA_STATUS_OK;
    s->weather_status = LUMINA_STATUS_STALE;   /* 用 STALE 才能看到「离线缓存」提示 */
    s->ai_status = LUMINA_STATUS_LOADING;      /* 用 LOADING 才能看到「小云正在想…」 */

    s->alarm_count = 4;
    const char *at[] = {"07:10", "08:05", "21:30", "22:15"};
    const char *al[] = {"起床", "上班", "散步", "睡前"};
    bool en[] = {true, true, false, false};
    for (int i = 0; i < 4; i++) { s->alarms[i].time = at[i]; s->alarms[i].label = al[i]; s->alarms[i].enabled = en[i]; }
}

/* ---------------- 对象树缺陷扫描 ----------------
 * 三类真实缺陷：
 *   A 文本截断：按控件宽度重新排版（LV_LABEL_LONG_DOT 会吃掉末尾），
 *     若排版后高度 > 控件高度 => 文字被截断；同时报缺字（字库无字形 => 豆腐块）
 *   B 内容重叠：用「实际绘制范围」而不是控件框（label=排版后文本范围，
 *     image 考虑 LV_IMAGE_ALIGN_CENTER 的居中/裁剪）
 *   C 触控区 < 60x60
 */
#define MAX_OBJ 256
typedef struct {
    int type;                  /* 0=label 1=image 2=other */
    const char *text;
    const lv_font_t *font;
    lv_area_t box;             /* 控件框 */
    lv_area_t draw;            /* 实际绘制范围 */
    bool clickable;
} rec_t;

typedef struct {
    FILE *out;
    uint32_t issues;
    rec_t rec[MAX_OBJ];
    int n;
} scan_t;

static void collect(scan_t *sc, lv_obj_t *o)
{
    uint32_t n = lv_obj_get_child_count(o);
    for (uint32_t i = 0; i < n; i++) {
        lv_obj_t *ch = lv_obj_get_child(o, (int32_t)i);
        if (sc->n < MAX_OBJ) {
            rec_t *r = &sc->rec[sc->n];
            memset(r, 0, sizeof(*r));
            r->type = 2;
            lv_obj_get_coords(ch, &r->box);
            r->draw = r->box;
            r->clickable = lv_obj_has_flag(ch, LV_OBJ_FLAG_CLICKABLE);
            if (lv_obj_check_type(ch, &lv_label_class)) {
                r->type = 0;
                r->text = lv_label_get_text(ch);
                r->font = lv_obj_get_style_text_font(ch, LV_PART_MAIN);
                if (r->text && r->text[0]) {
                    lv_point_t sz;
                    /* 以控件宽度为 max_width 重新排版，得到真实绘制范围 */
                    lv_text_get_size(&sz, r->text, r->font,
                                     lv_obj_get_style_text_letter_space(ch, LV_PART_MAIN),
                                     lv_obj_get_style_text_line_space(ch, LV_PART_MAIN),
                                     lv_obj_get_width(ch), LV_TEXT_FLAG_NONE);
                    r->draw.x2 = r->draw.x1 + LV_MIN(sz.x, lv_obj_get_width(ch)) - 1;
                    r->draw.y2 = r->draw.y1 + sz.y - 1;
                }
            } else if (lv_obj_check_type(ch, &lv_image_class)) {
                const lv_image_dsc_t *d = lv_image_get_src(ch);
                if (d && d->header.magic == LV_IMAGE_HEADER_MAGIC) {
                    r->type = 1;
                    int32_t ow = lv_obj_get_width(ch), oh = lv_obj_get_height(ch);
                    int32_t iw = d->header.w, ih = d->header.h;
                    int32_t cw, chh;
                    /* 默认 LV_IMAGE_ALIGN_CENTER：不缩放，尺寸取 min，居中 */
                    if (lv_image_get_inner_align(ch) == LV_IMAGE_ALIGN_STRETCH) { cw = ow; chh = oh; }
                    else { cw = LV_MIN(ow, iw); chh = LV_MIN(oh, ih); }
                    r->draw.x1 = r->box.x1 + (ow - cw) / 2;
                    r->draw.y1 = r->box.y1 + (oh - chh) / 2;
                    r->draw.x2 = r->draw.x1 + cw - 1;
                    r->draw.y2 = r->draw.y1 + chh - 1;
                    if (iw != ow || ih != oh) {
                        fprintf(sc->out,
                                "IMAGE_NOT_SCALED page_box=%dx%d asset=%dx%d => draw=%dx%d "
                                "(lv_obj_set_size 对 lv_image 只裁剪/居中，不缩放)\n",
                                (int)ow, (int)oh, (int)iw, (int)ih, (int)cw, (int)chh);
                        sc->issues++;
                    }
                }
            }
            sc->n++;
        }
        collect(sc, ch);
    }
}

static bool overlap(const lv_area_t *a, const lv_area_t *b)
{
    return !(a->x2 < b->x1 || b->x2 < a->x1 || a->y2 < b->y1 || b->y2 < a->y1);
}

static void analyse(scan_t *sc, const char *page)
{
    int i, j;
    for (i = 0; i < sc->n; i++) {
        rec_t *r = &sc->rec[i];
        if (r->type == 0 && r->text && r->text[0]) {
            /* A 文本截断：排版高度超过控件高度 */
            int32_t boxh = lv_area_get_height(&r->box);
            int32_t drawh = lv_area_get_height(&r->draw);
            if (drawh > boxh) {
                fprintf(sc->out, "TEXT_TRUNCATED page=%s text='%s' layout=%dx%d box=%dx%d at (%d,%d)\n",
                        page, r->text, (int)lv_area_get_width(&r->draw), (int)drawh,
                        (int)lv_area_get_width(&r->box), (int)boxh,
                        (int)r->box.x1, (int)r->box.y1);
                sc->issues++;
            }
            /* A2 缺字 */
            const char *q = r->text;
            while (*q) {
                uint32_t cp;
                if ((q[0] & 0x80) == 0) { cp = (uint8_t)q[0]; q += 1; }
                else if ((q[0] & 0xE0) == 0xC0) { cp = ((q[0] & 0x1F) << 6) | (q[1] & 0x3F); q += 2; }
                else if ((q[0] & 0xF0) == 0xE0) { cp = ((q[0] & 0x0F) << 12) | ((q[1] & 0x3F) << 6) | (q[2] & 0x3F); q += 3; }
                else { cp = ((q[0] & 0x07) << 18) | ((q[1] & 0x3F) << 12) | ((q[2] & 0x3F) << 6) | (q[3] & 0x3F); q += 4; }
                lv_font_glyph_dsc_t gd;
                if (!lv_font_get_glyph_dsc(r->font, &gd, cp, 0) || gd.is_placeholder) {
                    fprintf(sc->out, "MISSING_GLYPH page=%s U+%04X in '%s' font_lh=%d\n",
                            page, cp, r->text, (int)r->font->line_height);
                    sc->issues++;
                }
            }
        }
        /* D 越屏 */
        if (r->draw.x2 > W || r->draw.y2 > H || r->draw.x1 < 0 || r->draw.y1 < 0) {
            fprintf(sc->out, "OUT_OF_SCREEN page=%s %s draw=(%d,%d)-(%d,%d)\n",
                    page, r->type == 0 ? r->text : (r->type == 1 ? "<image>" : "<rect>"),
                    (int)r->draw.x1, (int)r->draw.y1, (int)r->draw.x2, (int)r->draw.y2);
            sc->issues++;
        }
    }
    /* B 内容重叠（label 与 label/image） */
    for (i = 0; i < sc->n; i++) {
        rec_t *a = &sc->rec[i];
        if (a->type != 0 || !a->text || !a->text[0]) continue;
        for (j = i + 1; j < sc->n; j++) {
            rec_t *b = &sc->rec[j];
            if (b->type != 0 && b->type != 1) continue;
            if (overlap(&a->draw, &b->draw)) {
                lv_area_t in = {
                    LV_MAX(a->draw.x1, b->draw.x1), LV_MAX(a->draw.y1, b->draw.y1),
                    LV_MIN(a->draw.x2, b->draw.x2), LV_MIN(a->draw.y2, b->draw.y2)
                };
                fprintf(sc->out,
                        "CONTENT_OVERLAP page=%s '%s' vs %s overlap %dx%d at (%d,%d)\n",
                        page, a->text, b->type == 0 ? (b->text && b->text[0] ? b->text : "<label>") : "<image>",
                        (int)(in.x2 - in.x1 + 1), (int)(in.y2 - in.y1 + 1), (int)in.x1, (int)in.y1);
                sc->issues++;
            }
        }
    }
    /* C 触控区 */
    for (i = 0; i < sc->n; i++) {
        rec_t *r = &sc->rec[i];
        if (!r->clickable) continue;
        int32_t w = lv_area_get_width(&r->box), h = lv_area_get_height(&r->box);
        if (w < 60 || h < 60) {
            fprintf(sc->out, "TOUCH_TARGET_SMALL page=%s obj=%dx%d at (%d,%d)\n",
                    page, (int)w, (int)h, (int)r->box.x1, (int)r->box.y1);
            sc->issues++;
        }
    }
}

/* ---------------- 页面表 ---------------- */
typedef struct { const char *name; lumina_page_t page; } page_def_t;
static const page_def_t PAGES[] = {
    {"watchface",   LUMINA_PAGE_WATCHFACE},
    {"apps",        LUMINA_PAGE_APPS},
    {"heart",       LUMINA_PAGE_HEART},
    {"spo2",        LUMINA_PAGE_SPO2},
    {"sport",       LUMINA_PAGE_SPORT},
    {"rest",        LUMINA_PAGE_REST},
    {"alarm",       LUMINA_PAGE_ALARM},
    {"timer",       LUMINA_PAGE_TIMER},
    {"weather",     LUMINA_PAGE_WEATHER},
    {"settings",    LUMINA_PAGE_SETTINGS},
    {"about",       LUMINA_PAGE_ABOUT},
    {"ai",          LUMINA_PAGE_AI},
    {"expressions", LUMINA_PAGE_EXPRESSIONS},
    {"music",       LUMINA_PAGE_MUSIC},
};

static int pump_display(lv_display_t *disp_unused, int max_iter)
{
    (void)disp_unused;
    int i;
    for (i = 0; i < max_iter; i++) {
        g_flush_seen = false;
        lv_timer_handler();
        lv_tick_inc((uint32_t)LV_DEF_REFR_PERIOD);
        if (!g_flush_seen) break;     /* 没有新的 flush => 已稳定 */
    }
    return i;
}

static int save_raw(const char *dir, const char *name)
{
    char path[512];
    snprintf(path, sizeof(path), "%s/page_%s.raw", dir, name);
    FILE *fp = fopen(path, "wb");
    if (!fp) { perror(path); return -1; }
    fwrite(g_fb, sizeof(uint16_t), W * H, fp);
    fclose(fp);
    printf("  %s  (%dx%d, flushes=%u)\n", path, W, H, (unsigned)g_flush_count);
    return 0;
}

int main(int argc, char **argv)
{
    const char *outdir = (argc > 1) ? argv[1] : ".";
    lv_init();

    lv_display_t *disp = lv_display_create(W, H);
    lv_display_set_flush_cb(disp, flush_cb);
    static uint16_t draw_buf[W * H];  /* 全屏 draw buffer */
    lv_display_set_buffers(disp, draw_buf, NULL, W * H * 2, LV_DISPLAY_RENDER_MODE_FULL);
    lv_display_set_default(disp);

    lumina_ui_port_t port = {
        .font_time  = &ui_font_sans_88_bold,   /* 近似：真机 88px 数字字体 */
        .font_body  = &ui_font_cjk_24,         /* 近似：真机 24px CJK 子集 */
        .font_small = &ui_font_cjk_18,         /* 近似：真机 18px CJK 子集 */
        .expression_image = port_expression_image,
        .action = port_action,
        .ctx = NULL,
    };
    lumina_ui_init(&port);
    lumina_ui_state_t st;
    fill_demo_state(&st);
    lumina_ui_set_state(&st);

    char manifest_path[512], scan_path[512];
    snprintf(manifest_path, sizeof(manifest_path), "%s/page_manifest.txt", outdir);
    snprintf(scan_path, sizeof(scan_path), "%s/scan.txt", outdir);
    FILE *manifest = fopen(manifest_path, "w");
    FILE *scan = fopen(scan_path, "w");
    if (!manifest || !scan) { perror("fopen"); return 1; }
    scan_t sc = { .out = scan, .issues = 0 };

    for (size_t i = 0; i < sizeof(PAGES)/sizeof(PAGES[0]); i++) {
        lumina_ui_show(PAGES[i].page);
        int iters = pump_display(disp, 12);
        /* 对象树扫描（按页重置计数） */
        uint32_t before = sc.issues;
        fprintf(scan, "\n--- page %s (page=%d) ---\n", PAGES[i].name, (int)PAGES[i].page);
        sc.n = 0;
        collect(&sc, lv_screen_active());
        analyse(&sc, PAGES[i].name);
        printf("[%2zu/14] %-12s page=%-2d pump=%d issues=%u\n",
               i + 1, PAGES[i].name, (int)PAGES[i].page, iters, sc.issues - before);
        if (save_raw(outdir, PAGES[i].name) != 0) return 1;
        fprintf(manifest, "page_%02zu_%s.png\n", i + 1, PAGES[i].name);
    }

    fprintf(scan, "\nTOTAL_ISSUES=%u\n", sc.issues);
    fclose(scan);
    fclose(manifest);
    printf("\n总缺陷计数: %u（详见 scan.txt）\n", sc.issues);
    return 0;
}
