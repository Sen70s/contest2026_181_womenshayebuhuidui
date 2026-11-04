/* host 预览我们固件自己的页面（批次 2/3）：应用入口 2x3 宫格、表情选择页。
 * 用真机同一份 LVGL + 同一份字体 + 同一份表情资产 + 同一份 ui_theme.c，
 * 渲染出 PNG 以在烧录前检查布局（不依赖任何硬件）。 */
#include <lvgl/lvgl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "ui_theme.h"
#include "applist_page.h"
#include "expression_page.h"

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

static void tick_cb(void *t)
{
  (void)t;
  lv_tick_inc(5);
}

static void write_ppm(const char *path)
{
  FILE *f = fopen(path, "wb");
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
}

int main(int argc, char **argv)
{
  const char *page = argc > 1 ? argv[1] : "apps";
  const char *out = argc > 2 ? argv[2] : "/tmp/ourpreview/out.ppm";

  lv_init();
  lv_tick_set_cb(tick_cb);

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

  if (strcmp(page, "apps") == 0)
    {
      applist_page_set_open_cb(NULL);
      applist_page_create();
    }
  else if (strcmp(page, "ai") == 0)
    {
      extern lv_obj_t *pet_page_create(void);
      pet_page_create();
    }
  else if (strcmp(page, "expr") == 0)
    {
      expression_page_create();
    }
  else if (strcmp(page, "watch") == 0)
    {
      /* 表盘页在 host 上编译：uORB/算法相关代码被 #ifdef CONFIG_UORB 排除，
       * 心率/语音用 stubs.c 里的空实现（返回值都是"没数据"）。 */
      extern lv_obj_t *watchface_page_create(void);
      watchface_page_create();
    }
  else
    {
      fprintf(stderr, "unknown page %s\n", page);
      return 2;
    }

  /* 跑若干帧，让布局与绘制完成 */
  for (int i = 0; i < 40; i++)
    {
      lv_timer_handler();
      usleep(5000);
    }

  write_ppm(out);
  printf("rendered '%s' -> %s (flushes=%d)\n", page, out, g_flush_count);
  return 0;
}
