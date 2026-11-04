#include "lumina_watch_ui.h"
#include "lumina_expressions.h"
#include <stdio.h>
#include <string.h>
#include <stdint.h>

#define W 390
#define H 450
#define C_BG      0xF8FBFD
#define C_TEXT    0x172A3D
#define C_MUTED   0x53697E
#define C_CARD    0xE8F0F5
#define C_BLUE    0x087FA9
#define C_PALE    0xE1F2F8
#define C_RED     0xBA3452
#define C_AMBER   0xA86700

static lumina_ui_port_t g_port;
static lumina_ui_state_t g_state;
static lv_obj_t *g_screen;
static lumina_page_t g_page = LUMINA_PAGE_WATCHFACE;
static uint8_t g_apps_page;
static uint8_t g_expr_page;
static uint8_t g_alarm_page;
static bool g_ready;

static const char *safe(const char *s, const char *fallback)
{
    return s && s[0] ? s : fallback;
}

static lv_obj_t *box(lv_obj_t *parent, int x, int y, int w, int h,
                     uint32_t color, int radius)
{
    lv_obj_t *o = lv_obj_create(parent);
    lv_obj_set_pos(o, x, y);
    lv_obj_set_size(o, w, h);
    lv_obj_set_style_bg_color(o, lv_color_hex(color), 0);
    lv_obj_set_style_bg_opa(o, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(o, 0, 0);
    lv_obj_set_style_radius(o, radius, 0);
    lv_obj_set_style_pad_all(o, 0, 0);
    lv_obj_clear_flag(o, LV_OBJ_FLAG_SCROLLABLE);
    return o;
}

static lv_obj_t *txt(lv_obj_t *parent, const char *value, int x, int y,
                     int w, int h, uint32_t color, const lv_font_t *font)
{
    lv_obj_t *o = lv_label_create(parent);
    lv_label_set_text(o, value ? value : "");
    lv_obj_set_pos(o, x, y);
    lv_obj_set_size(o, w, h);
    lv_obj_set_style_text_color(o, lv_color_hex(color), 0);
    if (font) lv_obj_set_style_text_font(o, font, 0);
    lv_label_set_long_mode(o, LV_LABEL_LONG_DOT);
    return o;
}

static lv_obj_t *small(lv_obj_t *p, const char *s, int x, int y, int w, int h,
                       uint32_t c)
{
    return txt(p, s, x, y, w, h, c, g_port.font_small);
}

static lv_obj_t *body(lv_obj_t *p, const char *s, int x, int y, int w, int h,
                      uint32_t c)
{
    return txt(p, s, x, y, w, h, c, g_port.font_body);
}

static void send(lumina_action_t action, int32_t value)
{
    if (g_port.action) g_port.action(action, value, g_port.ctx);
}

static void on_page(lv_event_t *e)
{
    lumina_ui_show((lumina_page_t)(intptr_t)lv_event_get_user_data(e));
}

static void on_action(lv_event_t *e)
{
    int32_t packed = (int32_t)(intptr_t)lv_event_get_user_data(e);
    lumina_action_t action = (lumina_action_t)(packed & 0xff);
    int32_t value = packed >> 8;
    send(action, value);
}

static void clickable(lv_obj_t *o, lv_event_cb_t cb, intptr_t data)
{
    lv_obj_add_flag(o, LV_OBJ_FLAG_CLICKABLE);
    lv_obj_add_event_cb(o, cb, LV_EVENT_CLICKED, (void *)data);
}

static lv_obj_t *button(lv_obj_t *p, const char *label, int x, int y, int w,
                        int h, bool primary, lv_event_cb_t cb, intptr_t data)
{
    lv_obj_t *o = box(p, x, y, w, h, primary ? C_BLUE : C_CARD, 20);
    lv_obj_set_style_bg_color(o, lv_color_hex(primary ? 0x086A8F : 0xD2E4EC),
                              LV_STATE_PRESSED);
    lv_obj_t *t = small(o, label, 0, 0, w, h, primary ? 0xFFFFFF : C_TEXT);
    lv_obj_set_style_text_align(t, LV_TEXT_ALIGN_CENTER, 0);
    lv_obj_center(t);
    clickable(o, cb, data);
    return o;
}

static void action_button(lv_obj_t *p, const char *label, int x, int y, int w,
                          bool primary, lumina_action_t a, int32_t value)
{
    button(p, label, x, y, w, 60, primary, on_action,
           (intptr_t)((value << 8) | (int32_t)a));
}

static void image_or_fallback(lv_obj_t *p, uint8_t index, bool thumbnail,
                              int x, int y, int w, int h)
{
    const void *src = g_port.expression_image ?
        g_port.expression_image(index, thumbnail, g_port.ctx) : NULL;
    if (src) {
        lv_obj_t *im = lv_image_create(p);
        lv_image_set_src(im, src);
        lv_obj_set_pos(im, x, y);
        lv_obj_set_size(im, w, h);
    } else {
        lv_obj_t *cloud = box(p, x, y, w, h, C_PALE, h / 2);
        lv_obj_t *t = small(cloud, "小云", 0, h / 2 - 13, w, 30, C_BLUE);
        lv_obj_set_style_text_align(t, LV_TEXT_ALIGN_CENTER, 0);
    }
}

static void topbar(const char *title)
{
    small(g_screen, title, 20, 9, 270, 30, C_MUTED);
    small(g_screen, safe(g_state.time, "--:--"), 303, 9, 68, 30, C_MUTED);
}

static void heading(const char *title, const char *subtitle)
{
    topbar("返回表盘");
    button(g_screen, "返回", 18, 40, 72, 44, false, on_page,
           LUMINA_PAGE_WATCHFACE);
    body(g_screen, title, 22, 91, 330, 44, C_TEXT);
    if (subtitle) small(g_screen, subtitle, 22, 134, 342, 30, C_MUTED);
}

static void render_watchface(void)
{
    char n[40];
    topbar(safe(g_state.date, "日期未同步"));
    small(g_screen, g_state.connected ? "● 已连接" : "○ 未连接",
          240, 9, 132, 30, g_state.connected ? C_BLUE : C_MUTED);
    box(g_screen, 18, 42, 354, 286, 0xEEF8FB, 30);
    small(g_screen, "上午好，今天也要开心", 34, 56, 320, 30, C_MUTED);
    txt(g_screen, safe(g_state.time, "--:--"), 34, 91, 318, 100,
        C_TEXT, g_port.font_time ? g_port.font_time : g_port.font_body);
    image_or_fallback(g_screen, g_state.selected_expression, false,
                      174, 154, 170, 150);
    small(g_screen, "小云一直在这里", 36, 287, 200, 28, C_BLUE);

    lv_obj_t *a = box(g_screen, 18, 340, 172, 85, C_CARD, 20);
    small(a, "♥ 最近心率", 14, 8, 145, 25, C_RED);
    if (g_state.heart_status == LUMINA_STATUS_OK) {
        snprintf(n, sizeof(n), "%u 次/分", g_state.heart_bpm);
    } else strcpy(n, "未测量");
    body(a, n, 14, 38, 148, 38, C_TEXT);
    lv_obj_set_style_bg_color(a, lv_color_hex(0xD5E9F0), LV_STATE_PRESSED);
    clickable(a, on_page, LUMINA_PAGE_HEART);

    lv_obj_t *b = box(g_screen, 200, 340, 172, 85, C_CARD, 20);
    small(b, "◉ 今日步数", 14, 8, 145, 25, C_BLUE);
    snprintf(n, sizeof(n), "%lu 步", (unsigned long)g_state.steps);
    body(b, n, 14, 38, 148, 38, C_TEXT);
    lv_obj_set_style_bg_color(b, lv_color_hex(0xD5E9F0), LV_STATE_PRESSED);
    clickable(b, on_page, LUMINA_PAGE_SPORT);
}

static const struct { const char *label; lumina_page_t page; uint32_t color; }
apps[] = {
    {"心率", LUMINA_PAGE_HEART, 0xFFF0F2},
    {"小云", LUMINA_PAGE_AI, C_PALE},
    {"运动", LUMINA_PAGE_SPORT, 0xE8F5EE},
    {"闹钟", LUMINA_PAGE_ALARM, 0xFFF3E3},
    {"血氧", LUMINA_PAGE_SPO2, C_PALE},
    {"设置", LUMINA_PAGE_SETTINGS, C_CARD},
    {"天气", LUMINA_PAGE_WEATHER, C_PALE},
    {"计时器", LUMINA_PAGE_TIMER, 0xFFF3E3},
    {"休息", LUMINA_PAGE_REST, C_CARD},
    {"关于", LUMINA_PAGE_ABOUT, C_CARD},
    {"表情", LUMINA_PAGE_EXPRESSIONS, C_PALE}
};

static void render_apps(void)
{
    unsigned start = g_apps_page * 6;
    unsigned i;
    heading("所有应用", "点击打开 · 上滑查看更多");
    for (i = 0; i < 6 && start + i < sizeof(apps)/sizeof(apps[0]); ++i) {
        unsigned idx = start + i;
        int x = 22 + (i % 3) * 122;
        int y = 175 + (i / 3) * 112;
        lv_obj_t *o = box(g_screen, x, y, 104, 82, apps[idx].color, 23);
        if (apps[idx].page == LUMINA_PAGE_AI) {
            image_or_fallback(o, 2, true, 23, 4, 58, 58);
        } else {
            small(o, apps[idx].label, 4, 24, 96, 34, C_TEXT);
        }
        small(g_screen, apps[idx].label, x, y + 84, 104, 28, C_TEXT);
        lv_obj_set_style_bg_color(o, lv_color_hex(0xD2E9F2), LV_STATE_PRESSED);
        clickable(o, on_page, apps[idx].page);
    }
    small(g_screen, g_apps_page ? "第 2 页 · 下滑回表盘" :
          "第 1 页 · 上滑看更多", 65, 407, 270, 28, C_MUTED);
}

static void metric_page(bool oxygen)
{
    char n[42];
    lumina_status_t status = oxygen ? g_state.spo2_status : g_state.heart_status;
    const char *updated = oxygen ? g_state.spo2_updated : g_state.heart_updated;
    heading(oxygen ? "血氧" : "心率", safe(updated, "暂无测量时间"));
    lv_obj_t *card = box(g_screen, 20, 180, 350, 166, C_CARD, 27);
    if (status == LUMINA_STATUS_OK) {
        snprintf(n, sizeof(n), oxygen ? "%u%%" : "%u 次/分",
                 oxygen ? g_state.spo2_percent : g_state.heart_bpm);
        body(card, n, 20, 23, 310, 75, C_TEXT);
        small(card, "本次测量有效", 20, 115, 310, 30, C_MUTED);
    } else if (status == LUMINA_STATUS_LOADING) {
        body(card, "测量中…", 20, 25, 310, 55, C_TEXT);
        small(card, oxygen ? "请保持手腕静止" : "请保持佩戴稳定",
              20, 105, 310, 50, C_MUTED);
    } else {
        body(card, status == LUMINA_STATUS_ERROR ? "测量失败" : "尚无数据",
             20, 25, 310, 55, C_TEXT);
        small(card, "请确认佩戴后重试", 20, 105, 310, 40, C_MUTED);
    }
    if (status != LUMINA_STATUS_LOADING)
        action_button(g_screen, "开始测量", 20, 365, 350, true,
                      oxygen ? LUMINA_ACTION_MEASURE_SPO2 :
                               LUMINA_ACTION_MEASURE_HEART, 0);
}

static void render_sport(void)
{
    char n[48];
    heading("运动", "今天的活动记录");
    lv_obj_t *a = box(g_screen, 20, 179, 350, 145, 0xE8F5EE, 28);
    snprintf(n, sizeof(n), "%lu", (unsigned long)g_state.steps);
    body(a, n, 23, 23, 300, 65, C_TEXT);
    small(a, "今日步数", 23, 99, 300, 30, C_MUTED);
    snprintf(n, sizeof(n), "距离约 %u 米", g_state.distance_m);
    small(g_screen, n, 29, 345, 340, 32, C_TEXT);
    snprintf(n, sizeof(n), "活动 %u 分钟", g_state.active_minutes);
    small(g_screen, n, 29, 386, 340, 32, C_MUTED);
}

static void render_rest(void)
{
    heading("休息", "依据静止与体动估算");
    box(g_screen, 20, 181, 350, 151, C_CARD, 28);
    body(g_screen, safe(g_state.rest_text, "暂无休息记录"),
         42, 220, 310, 65, C_TEXT);
    small(g_screen, "仅供自我了解，不代表睡眠分期或医疗结论",
          25, 355, 340, 62, C_MUTED);
}

static void render_alarm(void)
{
    char n[48];
    unsigned start = g_alarm_page * 3;
    unsigned i;
    heading("闹钟", "到点提醒你");
    if (!g_state.alarm_count) {
        box(g_screen, 20, 179, 350, 140, C_CARD, 28);
        body(g_screen, "还没有闹钟", 44, 218, 300, 62, C_TEXT);
    } else for (i = 0; i < 3 && start + i < g_state.alarm_count &&
                      start + i < 15; ++i) {
        unsigned idx = start + i;
        int y = 174 + (int)i * 69;
        lv_obj_t *row = box(g_screen, 20, y, 350, 62, C_CARD, 17);
        small(row, safe(g_state.alarms[idx].time, "--:--"),
              17, 8, 130, 30, C_TEXT);
        small(row, safe(g_state.alarms[idx].label, "闹钟"),
              148, 9, 105, 30, C_MUTED);
        small(row, g_state.alarms[idx].enabled ? "开启" : "关闭",
              273, 9, 62, 30, C_BLUE);
        clickable(row, on_action,
                  (intptr_t)((idx << 8) | LUMINA_ACTION_TOGGLE_ALARM));
    }
    snprintf(n, sizeof(n), "已设置 %u 个 · 第 %u 页",
             g_state.alarm_count, g_alarm_page + 1);
    small(g_screen, n, 26, 386, 260, 28, C_MUTED);
    action_button(g_screen, "+", 305, 376, 64, true,
                  LUMINA_ACTION_ADD_ALARM, 0);
}

static void render_timer(void)
{
    char n[32];
    heading("计时器", g_state.timer_running ? "倒计时进行中" : "选择时长后开始");
    snprintf(n, sizeof(n), "%02u:%02u", g_state.timer_seconds / 60,
             g_state.timer_seconds % 60);
    txt(g_screen, n, 40, 188, 310, 95, C_TEXT,
        g_port.font_time ? g_port.font_time : g_port.font_body);
    if (g_state.timer_running)
        action_button(g_screen, "停止", 20, 351, 350, false,
                      LUMINA_ACTION_STOP_TIMER, 0);
    else {
        action_button(g_screen, "5 分钟", 20, 351, 170, false,
                      LUMINA_ACTION_START_TIMER, 300);
        action_button(g_screen, "10 分钟", 200, 351, 170, true,
                      LUMINA_ACTION_START_TIMER, 600);
    }
}

static void render_weather(void)
{
    heading("天气", safe(g_state.weather_updated, "更新时间未知"));
    box(g_screen, 20, 180, 350, 160, C_PALE, 28);
    if (g_state.weather_status == LUMINA_STATUS_LOADING)
        body(g_screen, "正在获取天气…", 43, 223, 300, 60, C_TEXT);
    else if (g_state.weather_status == LUMINA_STATUS_ERROR)
        body(g_screen, "暂时无法获取", 43, 223, 300, 60, C_TEXT);
    else if (g_state.weather_status == LUMINA_STATUS_EMPTY)
        body(g_screen, "暂无天气数据", 43, 223, 300, 60, C_TEXT);
    else
        body(g_screen, safe(g_state.weather_text, "天气未知"),
             43, 223, 300, 60, C_TEXT);
    if (g_state.weather_status == LUMINA_STATUS_STALE)
        small(g_screen, "离线缓存 · 可能已过时", 40, 365, 315, 30, C_AMBER);
}

static void render_ai(void)
{
    heading("小云", "想聊聊吗？");
    image_or_fallback(g_screen, g_state.selected_expression, false,
                      105, 163, 180, 150);
    if (g_state.ai_status == LUMINA_STATUS_LOADING)
        small(g_screen, "小云正在想…", 45, 315, 300, 35, C_MUTED);
    else if (g_state.ai_status == LUMINA_STATUS_ERROR)
        small(g_screen, "连接失败，可稍后重试", 45, 315, 300, 35, C_AMBER);
    else small(g_screen, safe(g_state.ai_text, "我在这里，随时可以聊聊"),
               45, 315, 300, 35, C_MUTED);
    action_button(g_screen, "开始说话", 20, 369, 350, true,
                  LUMINA_ACTION_AI_TALK, 0);
}

static void render_expressions(void)
{
    unsigned i;
    char n[48];
    heading("小云表情", "选择你喜欢的小云");
    for (i = 0; i < 6; ++i) {
        unsigned idx = g_expr_page * 6 + i;
        int x = 20 + (i % 3) * 123;
        int y = 172 + (i / 3) * 104;
        lv_obj_t *o;
        if (idx >= LUMINA_EXPRESSION_COUNT) break;
        o = box(g_screen, x, y, 107, 89,
                idx == g_state.selected_expression ? C_PALE : C_CARD, 18);
        image_or_fallback(o, (uint8_t)idx, true, 23, 2, 60, 59);
        small(o, lumina_expression((uint8_t)idx)->label,
              4, 63, 98, 24, C_TEXT);
        lv_obj_set_style_bg_color(o, lv_color_hex(0xC6E9F4), LV_STATE_PRESSED);
        clickable(o, on_action,
                  (intptr_t)((idx << 8) | LUMINA_ACTION_SELECT_EXPRESSION));
    }
    snprintf(n, sizeof(n), "第 %u / 9 页 · 共 50 款", g_expr_page + 1);
    small(g_screen, n, 91, 397, 225, 31, C_MUTED);
}

static void settings_row(const char *name, const char *value, int y,
                         lumina_action_t action, int32_t v)
{
    lv_obj_t *o = box(g_screen, 20, y, 350, 61, C_CARD, 17);
    small(o, name, 18, 18, 218, 32, C_TEXT);
    small(o, value, 248, 18, 88, 32, C_BLUE);
    if (action != LUMINA_ACTION_COUNT) clickable(o, on_action,
        (intptr_t)((v << 8) | (int32_t)action));
}

static void render_settings(void)
{
    char n[24];
    heading("设置", "设备与显示");
    snprintf(n, sizeof(n), "%u%%", g_state.brightness_percent);
    settings_row("亮度", n, 175, LUMINA_ACTION_BRIGHTNESS, 0);
    settings_row("勿扰", g_state.dnd ? "已开启" : "已关闭",
                 244, LUMINA_ACTION_DND, 0);
    lv_obj_t *link = box(g_screen, 20, 313, 350, 61, C_CARD, 17);
    clickable(link, on_page, LUMINA_PAGE_EXPRESSIONS);
    small(link, "小云表情     >", 18, 18, 310, 30, C_TEXT);
    action_button(g_screen, "息屏", 20, 384, 350, false,
                  LUMINA_ACTION_SCREEN_OFF, 0);
}

static void render_about(void)
{
    heading("关于", "设备信息");
    box(g_screen, 20, 180, 350, 160, C_CARD, 28);
    body(g_screen, safe(g_state.device_name, "小云手表"),
         42, 207, 315, 53, C_TEXT);
    small(g_screen, safe(g_state.firmware_version, "固件版本未知"),
          42, 276, 315, 37, C_MUTED);
}

static void render_music(void)
{
    heading("音乐", "可选页面，默认不在应用入口显示");
    box(g_screen, 20, 178, 350, 150, C_CARD, 28);
    body(g_screen, safe(g_state.music_title, "暂无播放内容"),
         40, 220, 310, 60, C_TEXT);
    action_button(g_screen, g_state.music_playing ? "暂停" : "播放",
                  20, 355, 350, true, LUMINA_ACTION_MUSIC_TOGGLE, 0);
}

static void render(void)
{
    if (!g_ready || !g_screen) return;
    lv_obj_clean(g_screen);
    switch (g_page) {
    case LUMINA_PAGE_WATCHFACE: render_watchface(); break;
    case LUMINA_PAGE_APPS: render_apps(); break;
    case LUMINA_PAGE_HEART: metric_page(false); break;
    case LUMINA_PAGE_SPO2: metric_page(true); break;
    case LUMINA_PAGE_SPORT: render_sport(); break;
    case LUMINA_PAGE_REST: render_rest(); break;
    case LUMINA_PAGE_ALARM: render_alarm(); break;
    case LUMINA_PAGE_TIMER: render_timer(); break;
    case LUMINA_PAGE_WEATHER: render_weather(); break;
    case LUMINA_PAGE_SETTINGS: render_settings(); break;
    case LUMINA_PAGE_ABOUT: render_about(); break;
    case LUMINA_PAGE_AI: render_ai(); break;
    case LUMINA_PAGE_EXPRESSIONS: render_expressions(); break;
    case LUMINA_PAGE_MUSIC: render_music(); break;
    default: break;
    }
}

void lumina_ui_init(const lumina_ui_port_t *port)
{
    if (port) g_port = *port;
    else memset(&g_port, 0, sizeof(g_port));
    memset(&g_state, 0, sizeof(g_state));
    g_screen = lv_obj_create(NULL);
    lv_obj_set_size(g_screen, W, H);
    lv_obj_set_style_bg_color(g_screen, lv_color_hex(C_BG), 0);
    lv_obj_set_style_bg_opa(g_screen, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(g_screen, 0, 0);
    lv_obj_set_style_pad_all(g_screen, 0, 0);
    lv_obj_clear_flag(g_screen, LV_OBJ_FLAG_SCROLLABLE);
    g_page = LUMINA_PAGE_WATCHFACE;
    g_ready = true;
    render();
    lv_screen_load(g_screen);
}

void lumina_ui_set_state(const lumina_ui_state_t *state)
{
    if (!state) return;
    g_state = *state;
    if (g_state.selected_expression >= LUMINA_EXPRESSION_COUNT)
        g_state.selected_expression = 0;
    if (g_state.alarm_count == 0 || g_alarm_page * 3 >= g_state.alarm_count)
        g_alarm_page = 0;
    render();
}

void lumina_ui_show(lumina_page_t page)
{
    if (page >= LUMINA_PAGE_COUNT || !g_ready) return;
    g_page = page;
    render();
}

lumina_page_t lumina_ui_current_page(void) { return g_page; }

void lumina_ui_swipe(int direction)
{
    if (!g_ready) return;
    if (direction > 0) {
        if (g_page == LUMINA_PAGE_WATCHFACE) lumina_ui_show(LUMINA_PAGE_APPS);
        else if (g_page == LUMINA_PAGE_APPS && g_apps_page < 1) {
            ++g_apps_page; render();
        } else if (g_page == LUMINA_PAGE_EXPRESSIONS && g_expr_page < 8) {
            ++g_expr_page; render();
        } else if (g_page == LUMINA_PAGE_ALARM &&
                   (g_alarm_page + 1) * 3 < g_state.alarm_count) {
            ++g_alarm_page; render();
        }
    } else if (direction < 0) {
        if (g_page == LUMINA_PAGE_EXPRESSIONS && g_expr_page > 0) {
            --g_expr_page; render();
        } else if (g_page == LUMINA_PAGE_ALARM && g_alarm_page > 0) {
            --g_alarm_page; render();
        } else if (g_page == LUMINA_PAGE_APPS && g_apps_page > 0) {
            --g_apps_page; render();
        } else lumina_ui_show(LUMINA_PAGE_WATCHFACE);
    }
}

void lumina_ui_key1(void) { lumina_ui_show(LUMINA_PAGE_AI); }
