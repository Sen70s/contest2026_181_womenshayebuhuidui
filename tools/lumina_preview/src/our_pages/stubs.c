/* 给「我们固件的页面」做 host 预览用的桩。
 * 只保留布局/绘制相关路径，把跟硬件服务耦合的部分打成空实现。 */
#include <lvgl/lvgl.h>
#include <stdio.h>
#include <string.h>
#include "ui_nav.h"
#include "ui_gesture.h"
#include "ui_icon_runtime.h"

/* 真机资产里已有的内置图标（assets/ui_icons.c）*/
extern const lv_image_dsc_t ui_icon_heart, ui_icon_step, ui_icon_weather,
    ui_icon_battery, ui_icon_bluetooth, ui_icon_app_heart, ui_icon_app_spo2,
    ui_icon_app_sport, ui_icon_app_music, ui_icon_app_sleep,
    ui_icon_app_settings, ui_icon_app_timer, ui_icon_app_light,
    ui_icon_arrow_up, ui_icon_arrow_down;

static const struct { const char *n; const lv_image_dsc_t *d; } T[] = {
  {"heart",&ui_icon_heart}, {"step",&ui_icon_step}, {"weather",&ui_icon_weather},
  {"battery",&ui_icon_battery}, {"bluetooth",&ui_icon_bluetooth},
  {"app_heart",&ui_icon_app_heart}, {"app_spo2",&ui_icon_app_spo2},
  {"app_sport",&ui_icon_app_sport}, {"app_music",&ui_icon_app_music},
  {"app_sleep",&ui_icon_app_sleep}, {"app_settings",&ui_icon_app_settings},
  {"app_timer",&ui_icon_app_timer}, {"app_light",&ui_icon_app_light},
  {"arrow_up",&ui_icon_arrow_up}, {"arrow_down",&ui_icon_arrow_down},
};

const lv_image_dsc_t *ui_icon(const char *name)
{
  for (unsigned i = 0; i < sizeof(T)/sizeof(T[0]); i++)
    if (strcmp(T[i].n, name) == 0) return T[i].d;
  return NULL;
}

int ui_icon_is_baked(const lv_image_dsc_t *dsc) { (void)dsc; return 1; }
void ui_icon_clear_cache(void) {}

static ui_page_t g_page = UI_PAGE_WATCHFACE;
void ui_nav_goto(ui_page_t page) { g_page = page; printf("[stub] nav -> %d\n", (int)page); }
ui_page_t ui_nav_current(void) { return g_page; }
void ui_nav_back(void) {}
void ui_nav_init(void) {}
void ui_nav_attach_back_swipe(lv_obj_t *p) { (void)p; }

void ui_gesture_attach(lv_obj_t *page, ui_gesture_cb_t cb) { (void)page; (void)cb; }
bool ui_gesture_was_drag(void) { return false; }

/* ui_str: 真机从 text.ini 取，host 直接用默认值 */
const char *ui_str(const char *key, const char *def) { (void)key; return def; }

/* 表盘页（watchface_page.c）在 host 上的依赖：心率读数、语音通道。
 * 前者返回 0（=从没测到过，表盘显示 "--"），后者全部空实现。 */
int hr_measure_last_bpm(void) { return 0; }
int voice_channel_is_recording(void) { return 0; }
int voice_channel_start(void) { return 0; }
int voice_channel_stop(void) { return 0; }

/* AI 页（pet_page.c）在 host 上的依赖：对话历史、宠物关怀调度、语音回合状态。
 * 全部返回"空/无"，预览只关心布局。 */
#include <stdbool.h>
#include <stddef.h>
int  lvgl_ui_history_count(void) { return 0; }
bool lvgl_ui_history_get(int newest_first, char *buf, size_t len, bool *is_user)
{
  (void)newest_first; (void)is_user;
  if (buf && len) buf[0] = '\0';
  return false;
}
void pet_care_note_activity(void) {}
int  voice_channel_turn_active(void) { return 0; }
