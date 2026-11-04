/* host 预览我们固件自己的 14 个页面。
 *
 * 用真机同一份 LVGL + 同一份字体 + 同一份表情/图标资产 + 同一份 ui_theme.c，
 * 通过 flush_cb 直接把 RGB565 帧抓下来写成 PPM/PNG（不依赖 SDL / 板子）。
 *
 * 页面清单与 packages/ai_agent/src/ui/ui_nav.h 的 ui_page_t 一致：
 *   watch / apps / heart / spo2 / music / timer / sport / sleep /
 *   settings / ai / about / alarm / weather / expr
 *
 * 与硬件耦合的部分全部在 stubs.c 里做空实现；本文件只负责：
 *   1. 初始化 host display（390x450、RGB565、PARTIAL）
 *   2. 按参数创建对应页面，并在 create 前注入状态夹具
 *   3. 用 lv_obj_send_event / ui_gesture 回调 / lv_tick_inc 复现关键状态
 *   4. 把帧缓冲写成 PPM
 *
 * 用法：
 *   our_pages_preview <page> <out.ppm> [state]
 *   state 省略 = 旧版默认空数据态。
 */
#include <lvgl/lvgl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "preview_state.h"
#include "ui_gesture.h"
#include "ui_theme.h"
#include "applist_page.h"
#include "expression_page.h"
#include "watchface_page.h"
#include "pet_page.h"
#include "heart_page.h"
#include "spo2_page.h"
#include "music_page.h"
#include "timer_page.h"
#include "sport_page.h"
#include "sleep_page.h"
#include "settings_page.h"
#include "about_page.h"
#include "alarm_page.h"
#include "weather_page.h"

#define W 390
#define H 450
#define MAX_BUF (W * 60)

static uint16_t g_drawbuf[MAX_BUF];
static uint16_t g_fb[W * H];
static int g_flush_count;

static void flush_cb(lv_display_t *disp, const lv_area_t *area, uint8_t *px_map)
{
  int w = area->x2 - area->x1 + 1;
  int h = area->y2 - area->y1 + 1;
  const uint16_t *src = (const uint16_t *)px_map;
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++)
      {
        int dx = area->x1 + x, dy = area->y1 + y;
        if (dx >= 0 && dx < W && dy >= 0 && dy < H)
          g_fb[dy * W + dx] = src[y * w + x];
      }
  g_flush_count++;
  lv_display_flush_ready(disp);
}

/* 推进模拟时间并处理 LVGL 定时器。每次最多推进 1s：lv_timer_handler 对
 * 每个到期定时器只跑一次，分步推进才能让 1Hz 定时器看到逐秒变化。 */
static void pump_ms(uint32_t ms)
{
  while (ms > 0)
    {
      uint32_t step = (ms > 1000) ? 1000 : ms;
      lv_tick_inc(step);
      lv_timer_handler();
      ms -= step;
    }
}

/* 真实等待 + 5ms 模拟 tick。天气 worker 在另一个 pthread 里，必须给它
 * 真实 CPU 时间；纯 lv_tick_inc 推进不会让 worker 跑起来。 */
static void settle(uint32_t real_ms)
{
  uint32_t elapsed;
  for (elapsed = 0; elapsed < real_ms; elapsed += 5)
    {
      lv_tick_inc(5);
      lv_timer_handler();
      usleep(5000);
    }
}

static int write_ppm(const char *path)
{
  FILE *f = fopen(path, "wb");
  if (f == NULL)
    {
      perror(path);
      return -1;
    }
  fprintf(f, "P6\n%d %d\n255\n", W, H);
  for (int i = 0; i < W * H; i++)
    {
      uint16_t v = g_fb[i];
      unsigned r = ((v >> 11) & 0x1f) * 255 / 31;
      unsigned g = ((v >> 5) & 0x3f) * 255 / 63;
      unsigned b = (v & 0x1f) * 255 / 31;
      fputc(r, f); fputc(g, f); fputc(b, f);
    }
  fclose(f);
  return 0;
}

/* applist 的打开回调在真机上由 ui_nav 注入；host 上不需要真跳转。 */
static lv_obj_t *create_apps(void)
{
  applist_page_set_open_cb(NULL);
  return applist_page_create();
}

static const struct
{
  const char *name;
  lv_obj_t *(*create)(void);
} PAGES[] = {
  { "watch",    watchface_page_create },
  { "apps",     create_apps },
  { "heart",    heart_page_create },
  { "spo2",     spo2_page_create },
  { "music",    music_page_create },
  { "timer",    timer_page_create },
  { "sport",    sport_page_create },
  { "sleep",    sleep_page_create },
  { "settings", settings_page_create },
  { "ai",       pet_page_create },
  { "about",    about_page_create },
  { "alarm",    alarm_page_create },
  { "weather",  weather_page_create },
  { "expr",     expression_page_create },
};

static void usage(const char *argv0)
{
  fprintf(stderr, "usage: %s <page> <out.ppm> [state]\n", argv0);
  fprintf(stderr, "pages:");
  for (unsigned i = 0; i < sizeof(PAGES) / sizeof(PAGES[0]); i++)
    fprintf(stderr, " %s", PAGES[i].name);
  fprintf(stderr, "\nstate: page2 history measuring failed result last running "
                  "finished list editor editor_error loading error stale "
                  "history_empty history_list reply data dnd_on\n");
}

/* ── 点击注入：按屏幕上的 label 文本找可点击祖先 ─────────────────────
 *
 * 不模拟触摸 indev，直接给按钮发 LV_EVENT_CLICKED。ui_gesture_was_drag()
 * 在 host 恒 false，所以页面自己的防误触闸门不会拦。 */
static lv_obj_t *find_label(lv_obj_t *obj, const char *text)
{
  if (lv_obj_check_type(obj, &lv_label_class))
    {
      const char *t = lv_label_get_text(obj);
      if (t != NULL && strcmp(t, text) == 0)
        {
          return obj;
        }
    }

  uint32_t n = lv_obj_get_child_count(obj);
  for (uint32_t i = 0; i < n; i++)
    {
      lv_obj_t *hit = find_label(lv_obj_get_child(obj, i), text);
      if (hit != NULL)
        {
          return hit;
        }
    }
  return NULL;
}

static bool click_text(const char *text)
{
  lv_obj_t *obj = find_label(lv_screen_active(), text);
  if (obj == NULL)
    {
      fprintf(stderr, "preview: click target '%s' not found\n", text);
      return false;
    }

  while (obj != NULL && !lv_obj_has_flag(obj, LV_OBJ_FLAG_CLICKABLE))
    {
      obj = lv_obj_get_parent(obj);
    }

  if (obj == NULL)
    {
      fprintf(stderr, "preview: clickable ancestor for '%s' not found\n", text);
      return false;
    }

  lv_obj_send_event(obj, LV_EVENT_CLICKED, NULL);
  return true;
}

/* ── 状态动作 ─────────────────────────────────────────────────────── */

static void apply_state(const char *page, const char *state)
{
  if (state == NULL || state[0] == '\0')
    {
      return;
    }

  /* 应用列表 / 表情页第 2 页：走页面自己注册的手势回调 */
  if (strcmp(state, "page2") == 0)
    {
      preview_swipe(UI_GESTURE_SWIPE_UP);
      settle(300);
      return;
    }

  if (strcmp(page, "heart") == 0)
    {
      if (strcmp(state, "measuring") == 0)
        {
          click_text("开始测量");
          settle(120);
        }
      else if (strcmp(state, "failed") == 0)
        {
          click_text("开始测量");
          pump_ms(1100);   /* 1Hz refresh_cb 看到 is_running=false -> 信号弱 */
          settle(60);
        }
      return;
    }

  if (strcmp(page, "spo2") == 0)
    {
      if (strcmp(state, "result") == 0)
        {
          click_text("开始测量");
          pump_ms(600);    /* 500ms 刷新周期收结果 */
          settle(60);
        }
      return;
    }

  if (strcmp(page, "timer") == 0)
    {
      if (strcmp(state, "running") == 0)
        {
          click_text("开始");
          pump_ms(1100);   /* 跨过 1s tick，显示 04:59 */
          settle(120);
        }
      else if (strcmp(state, "finished") == 0)
        {
          click_text("开始");
          pump_ms(301000); /* 5 分钟 + 1 秒，触发 on_finished */
          settle(120);
        }
      return;
    }

  if (strcmp(page, "alarm") == 0)
    {
      if (strcmp(state, "editor") == 0 || strcmp(state, "editor_error") == 0)
        {
          click_text("+ 新增闹钟");
          settle(80);
          if (strcmp(state, "editor_error") == 0)
            {
              click_text("确定");
              settle(80);
            }
        }
      return;
    }

  if (strcmp(page, "weather") == 0)
    {
      if (strcmp(state, "loading") == 0)
        {
          settle(1200);    /* worker 阻塞中，画面停在「获取中…」 */
        }
      else if (strcmp(state, "error") == 0)
        {
          settle(900);     /* 等 worker 失败 + 200ms 渲染 tick */
        }
      else if (strcmp(state, "stale") == 0)
        {
          settle(1200);    /* 旧数据 + 「刷新失败」 */
        }
      return;
    }

  if (strcmp(page, "ai") == 0)
    {
      if (strcmp(state, "history_empty") == 0 ||
          strcmp(state, "history_list") == 0)
        {
          pet_page_open_history();
          settle(150);
        }
      else if (strcmp(state, "reply") == 0)
        {
          pet_page_update_response(
              "今天北京多云，24 度，风不大。傍晚转晴，适合散步。"
              "记得多喝水，别久坐。晚上如果降温，出门可以带一件薄外套。"
              "空气质量不错，适合开窗通风。");
          settle(120);
        }
      return;
    }
}

int main(int argc, char **argv)
{
  const char *page = argc > 1 ? argv[1] : "apps";
  const char *out = argc > 2 ? argv[2] : "/tmp/ourpreview/out.ppm";
  const char *state = argc > 3 ? argv[3] : NULL;
  lv_obj_t *(*create)(void) = NULL;

  for (unsigned i = 0; i < sizeof(PAGES) / sizeof(PAGES[0]); i++)
    if (strcmp(page, PAGES[i].name) == 0)
      {
        create = PAGES[i].create;
        break;
      }

  if (create == NULL)
    {
      fprintf(stderr, "unknown page '%s'\n", page);
      usage(argv[0]);
      return 2;
    }

  memset(g_fb, 0, sizeof(g_fb));

  lv_init();

  lv_display_t *disp = lv_display_create(W, H);
  lv_display_set_color_format(disp, LV_COLOR_FORMAT_RGB565);
  lv_display_set_flush_cb(disp, flush_cb);
  lv_display_set_buffers(disp, g_drawbuf, NULL, sizeof(g_drawbuf),
                         LV_DISPLAY_RENDER_MODE_PARTIAL);

  /* 真机启动顺序：ui_theme_defaults() -> 交给页面；这里同样先落默认值 */
  ui_theme_defaults();

  /* 根屏幕底色 = 页面底色（真机上由 lvgl_ui_channel 设 CLR_BG，页面自己再涂一层） */
  lv_obj_set_style_bg_color(lv_screen_active(), lv_color_hex(0xf8fbfd), 0);
  lv_obj_set_style_bg_opa(lv_screen_active(), LV_OPA_COVER, 0);

  /* 状态夹具必须在 create 之前注入（页面 create 里会读历史/缓存/待机时长） */
  preview_set_state(state);

  lv_obj_t *root = create();
  if (root == NULL)
    {
      fprintf(stderr, "page '%s': create returned NULL\n", page);
      return 3;
    }

  /* 首屏排版/字体/状态栏绘制完成（60 帧 ≈ 300ms 模拟时间） */
  settle(300);

  apply_state(page, state);

  if (write_ppm(out) != 0)
    return 4;

  printf("rendered '%s'%s%s -> %s (flushes=%d)\n",
         page,
         (state != NULL && state[0] != '\0') ? " state=" : "",
         (state != NULL) ? state : "",
         out, g_flush_count);
  return 0;
}
