/* 给「我们固件的 14 个页面」做 host 预览用的桩。
 *
 * 原则：只保留布局/绘制相关路径；跟硬件、网络、持久化、后台线程耦合的
 * 部分全部打成空实现或固定数据。固定数据在本文件里显式标注 PREVIEW-FIXTURE，
 * 截图仅供布局审查，不是真机读数。
 *
 * 2026-11-04（状态截图扩展）：
 *   preview_set_state() 在页面 create 之前注入一组夹具，让「有数据 / 测量中 /
 *   到点 / 编辑区 / 天气失败」等状态也能在 host 上复现。
 *   空 state 时所有夹具保持「没有传感器数据」的旧行为，14 页默认态不变。
 *
 * 真机资产里已有的内置图标（assets/ui_icons.c）。
 */
#include <lvgl/lvgl.h>
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#include "preview_state.h"
#include "ui_nav.h"
#include "ui_gesture.h"
#include "ui_icon_runtime.h"
#include "ui_theme.h"
#include "health/hr_measure.h"
#include "health/spo2_measure.h"
#include "health/pedometer.h"
#include "health/rest_track.h"
#include "music_local.h"
#include "music_worker.h"
#include "infra/cron_service.h"
#include "infra/weather_cache.h"
#include "infra/vela_tls.h"
#include "cJSON.h"

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

/*******************************************
 * PREVIEW-FIXTURE：先声明默认值（= 空数据）
 *******************************************/

static const char *s_state = "";
static ui_gesture_cb_t s_gesture_cb;

/* max30102.c 暴露给血氧页的全局结果；定义在文件后半段。 */
extern float g_max30102_spo2_pct;
extern int   g_max30102_spo2_ok;

static bool s_hr_running;
static bool s_hr_last_ok;
static int  s_hr_bpm;
static int  s_hr_hist_count;
static int  s_hr_hist_bpm[30];
static int  s_hr_fixed_minute = -1;   /* v: 固定"最近测量"时刻（分钟），-1=用当前时间 */

static bool     s_spo2_busy;

static bool     s_pedo_alive;
static uint32_t s_steps;
static uint32_t s_cadence;
static uint32_t s_still_s;
static uint32_t s_active_s;

static bool     s_rest_has;
static uint32_t s_rest_start;
static uint32_t s_rest_end;
static uint32_t s_rest_dur;

static bool s_dnd;

static int s_hist_count;
static const char *s_hist_text[4];
static bool s_hist_is_user[4];

static int  s_cron_count;
static bool s_add_fail;

/* 0 = 新鲜缓存（默认成功态）；1 = 无缓存 + 网络失败；2 = 无缓存 + 网络阻塞；
 * 3 = 旧缓存 + 网络失败。 */
static int s_wx_mode;

/* 表盘 -DUI_SIM 步数；-1 = 没数据（与旧默认态一致）。 */
static int s_sim_steps = -1;

static bool is_state(const char *s)
{
  return s_state[0] != '\0' && strcmp(s_state, s) == 0;
}

/* 最近一次 06:40（本地时间）。用固定的 06:40 做"昨夜结束"，
 * 这样 23:10 - 06:40 的画面对任何运行时刻都成立（除非现在正好 < 06:40，
 * 则取昨天 06:40，仍在 24 小时窗口内）。 */
static void fixture_last_night(void)
{
  time_t now = time(NULL);
  struct tm tmv;
  time_t end;
  time_t start;

  if (localtime_r(&now, &tmv) == NULL)
    {
      return;
    }

  tmv.tm_hour = 6;
  tmv.tm_min  = 40;
  tmv.tm_sec  = 0;
  tmv.tm_isdst = -1;
  end = mktime(&tmv);
  if (end > now)
    {
      end -= 86400;
    }

  start = end - (7 * 3600 + 32 * 60);   /* 7 时 32 分 */

  s_rest_start = (uint32_t)start;
  s_rest_end   = (uint32_t)end;
  s_rest_dur   = (uint32_t)(end - start);
  s_rest_has   = true;
}

void preview_set_state(const char *state)
{
  s_state = (state != NULL) ? state : "";

  /* 先全部回到默认空数据态 */
  s_hr_running   = false;
  s_hr_last_ok   = false;
  s_hr_bpm       = 0;
  s_hr_hist_count = 0;
  s_hr_fixed_minute = -1;

  s_spo2_busy = false;
  g_max30102_spo2_pct = 0.0f;
  g_max30102_spo2_ok  = 0;

  s_pedo_alive = false;
  s_steps      = 0;
  s_cadence    = 0;
  s_still_s    = 0;
  s_active_s   = 0;

  s_rest_has   = false;
  s_rest_start = 0;
  s_rest_end   = 0;
  s_rest_dur   = 0;

  s_dnd = false;

  s_hist_count = 0;
  memset(s_hist_text, 0, sizeof(s_hist_text));
  memset(s_hist_is_user, 0, sizeof(s_hist_is_user));

  s_cron_count = 0;
  s_add_fail   = false;

  s_wx_mode = 0;
  s_sim_steps = -1;

  /* ── 心率页 ── */
  if (is_state("history"))
    {
      s_hr_last_ok = true;
      s_hr_bpm = 72;

      for (int i = 0; i < 30; i++)
        {
          /* 有起伏的曲线；最后一条 = 72，与主值一致 */
          s_hr_hist_bpm[i] = (i == 29) ? 72 : (66 + (i % 12));
        }
      s_hr_hist_count = 30;
    }
  else if (is_state("device"))
    {
      /* 对齐真机照片：71 BPM、"上次有效值 · 最近测量 10:24"、曲线、开始测量按钮 */
      s_hr_last_ok = false;
      s_hr_bpm     = 0;
      /* 对齐真机照片的柱形：高低起伏 + 两个低谷（0 -> 仅显示基线小点） */
      static const int bars[30] = {
          70, 78, 64, 82, 58, 72, 66, 80, 60, 74,
          0,  68, 76, 56, 84, 62, 73, 67, 79, 61,
          70, 0,  75, 65, 81, 59, 77, 63, 74, 71
      };
      for (int i = 0; i < 30; i++)
        {
          s_hr_hist_bpm[i] = bars[i];
        }
      s_hr_hist_count  = 30;
      s_hr_fixed_minute = 10 * 60 + 24;
    }
  else if (is_state("measuring"))
    {
      s_hr_running = true;
    }
  else if (is_state("failed"))
    {
      /* 点按钮后 is_running=false + last_ok=false -> 1 秒后显示"信号弱" */
    }

  /* ── 血氧页 ── */
  else if (is_state("result") || is_state("last"))
    {
      g_max30102_spo2_pct = 98.0f;
      g_max30102_spo2_ok  = 1;
    }

  /* ── 运动 / 休息 / 表盘「有数据」 ── */
  else if (is_state("data"))
    {
      s_pedo_alive = true;
      s_steps      = 8432;
      s_cadence    = 96;
      s_still_s    = 45 * 60;
      s_active_s   = 90 * 60;

      fixture_last_night();

      s_hr_last_ok = true;
      s_hr_bpm     = 72;
      s_sim_steps  = 8432;
    }

  /* ── 闹钟页 ── */
  else if (is_state("list") || is_state("editor") || is_state("editor_error"))
    {
      s_cron_count = 3;
      s_add_fail   = is_state("editor_error");
    }

  /* ── 设置页 ── */
  else if (is_state("dnd_on"))
    {
      s_dnd = true;
    }

  /* ── 天气页 ── */
  else if (is_state("error"))
    {
      s_wx_mode = 1;
    }
  else if (is_state("loading"))
    {
      s_wx_mode = 2;
    }
  else if (is_state("stale"))
    {
      s_wx_mode = 3;
    }

  /* ── AI 对话记录浮层 ── */
  else if (is_state("history_list"))
    {
      /* hist_rebuild 从 n-1 到 0 依次建气泡，所以 index 0 = 最新（屏幕
       * 最下方），index 3 = 最早（屏幕最上方）。 */
      s_hist_count = 4;
      s_hist_text[0] = "不用带伞，傍晚转晴。记得多喝水呀。";
      s_hist_text[1] = "那我要带伞吗？";
      s_hist_text[2] = "今天北京多云，24 度，风不大。";
      s_hist_text[3] = "今天天气怎么样？";
      s_hist_is_user[0] = false;
      s_hist_is_user[1] = true;
      s_hist_is_user[2] = false;
      s_hist_is_user[3] = true;
    }

  /* dnd_on / history_empty / reply / page2 / running / finished：
   * 不需要数据夹具，由 main.c 的点击/手势/定时器注入完成。 */
}

void preview_swipe(int direction)
{
  if (s_gesture_cb != NULL)
    {
      s_gesture_cb((ui_gesture_dir_t)direction);
    }
}

long ui_sim_steps(void) { return (long)s_sim_steps; }
int  ui_sim_battery(void) { return 100; }

/*******************************************
 * 导航 / 手势 / 文案
 *******************************************/

static ui_page_t g_page = UI_PAGE_WATCHFACE;
void ui_nav_goto(ui_page_t page) { g_page = page; printf("[stub] nav -> %d\n", (int)page); }
ui_page_t ui_nav_current(void) { return g_page; }
void ui_nav_back(void) {}
void ui_nav_init(void) {}
void ui_nav_attach_back_swipe(lv_obj_t *p) { (void)p; }

/* 记录页面自己注册的回调（ui_nav_attach_back_swipe 在 host 上是空实现，
 * 所以这里拿到的一定是页面真正关心的手势回调）。 */
void ui_gesture_attach(lv_obj_t *page, ui_gesture_cb_t cb)
{
  (void)page;
  s_gesture_cb = cb;
}
bool ui_gesture_was_drag(void) { return false; }

/* ui_str: 真机从 text.ini 取，host 直接用默认值 */
const char *ui_str(const char *key, const char *def) { (void)key; return def; }

/*******************************************
 * 表盘 / AI 页
 *******************************************/

/* 表盘页（watchface_page.c）：心率读数、语音通道。
 * 默认 0（=从没测到过，表盘显示 "--"）。 */
int hr_measure_last_bpm(void) { return s_hr_bpm; }
int voice_channel_is_recording(void) { return 0; }
int voice_channel_start(void) { return 0; }
int voice_channel_stop(void) { return 0; }

/* AI 页（pet_page.c）：对话历史、宠物关怀调度、语音回合状态。 */
int  lvgl_ui_history_count(void) { return s_hist_count; }
bool lvgl_ui_history_get(int index, char *buf, size_t len, bool *is_user)
{
  if (index < 0 || index >= s_hist_count || buf == NULL || len == 0)
    {
      return false;
    }

  snprintf(buf, len, "%s", s_hist_text[index]);
  if (is_user != NULL)
    {
      *is_user = s_hist_is_user[index];
    }
  return true;
}
void pet_care_note_activity(void) {}
int  voice_channel_turn_active(void) { return 0; }

/*******************************************
 * 心率 / 血氧页
 *******************************************/

/* 默认无数据：页面显示 "--" / "尚未测量"，与真机刚开机一致。 */
int  hr_measure_start(void) { return 0; }
bool hr_measure_is_running(void) { return s_hr_running; }
bool hr_measure_last_ok(void) { return s_hr_last_ok; }
int  hr_measure_history_snapshot(uint32_t *ts, int *bpm, int max)
{
  int n;
  int start;
  time_t now;

  if (s_hr_hist_count <= 0 || max <= 0 || ts == NULL || bpm == NULL)
    {
      return 0;
    }

  n = (s_hr_hist_count < max) ? s_hr_hist_count : max;
  start = s_hr_hist_count - n;   /* 只取最近 n 条 */
  now = time(NULL);
  time_t ts_last = now;

  if (s_hr_fixed_minute >= 0)
    {
      struct tm tmv;
      if (localtime_r(&now, &tmv) != NULL)
        {
          tmv.tm_hour = s_hr_fixed_minute / 60;
          tmv.tm_min  = s_hr_fixed_minute % 60;
          tmv.tm_sec  = 0;
          ts_last = mktime(&tmv);
        }
    }

  for (int i = 0; i < n; i++)
    {
      ts[i]  = (uint32_t)(ts_last - (time_t)(n - 1 - i) * 300);
      bpm[i] = s_hr_hist_bpm[start + i];
    }
  return n;
}

float g_max30102_spo2_pct;
int   g_max30102_spo2_ok;
int   spo2_measure_start(void) { return 0; }
bool  spo2_measure_busy(void) { return s_spo2_busy; }

/* preview_set_state 里会写 g_max30102_*，这里不用重复。 */

/*******************************************
 * 运动 / 休息页
 *******************************************/

/* 没有 /dev/mpu0 时页面显示"不可用"，这是诚实的空状态。 */
uint32_t pedometer_get_steps(void) { return s_steps; }
uint32_t pedometer_get_cadence(void) { return s_cadence; }
uint32_t pedometer_get_still_seconds(void) { return s_still_s; }
uint32_t pedometer_get_active_seconds(void) { return s_active_s; }
bool     pedometer_is_alive(void) { return s_pedo_alive; }

int      rest_track_start(void) { return 0; }
int      rest_track_get_state(void)
{
  return s_rest_has ? REST_STATE_NIGHT : REST_STATE_AWAKE;
}
uint32_t rest_track_current_quiet_s(void) { return s_still_s; }
int      rest_track_get_last_night(uint32_t *start, uint32_t *end, uint32_t *dur_s)
{
  if (!s_rest_has)
    {
      return 0;
    }

  if (start != NULL) *start = s_rest_start;
  if (end   != NULL) *end   = s_rest_end;
  if (dur_s != NULL) *dur_s = s_rest_dur;
  return 1;
}

/*******************************************
 * 音乐页
 *******************************************/

int music_local_scan(char names[][MUSIC_LOCAL_NAMELEN], int max)
{
  (void)names; (void)max;
  return 0;   /* 空列表 -> 页面显示"暂无本地音乐"的空状态 */
}

int music_worker_init(void) { return 0; }
int music_worker_post(music_cmd_t cmd, const char *arg)
{
  (void)cmd; (void)arg;
  return 0;
}

/* PREVIEW-FIXTURE：给出合法的 IDLE 快照，让音量行显示 50% 而不是 "--"。 */
void music_worker_snapshot(music_snapshot_t *out)
{
  if (out == NULL) return;
  memset(out, 0, sizeof(*out));
  strcpy(out->state, "IDLE");
  out->position_ms = 0;
  out->duration_ms = -1;
  out->volume      = 50;
  out->valid       = true;
}

void pcm_file_stop(void) {}

/*******************************************
 * 设置页
 *******************************************/

int  lvgl_ui_post(lv_async_cb_t cb, void *data)
{
  (void)cb; (void)data;
  return 0;
}
void pet_care_set_dnd(bool on) { s_dnd = on; }
bool pet_care_get_dnd(void) { return s_dnd; }

/*******************************************
 * 闹钟页
 *******************************************/

static const char *const s_cron_names[] = { "早起闹钟", "喝水提醒", "会议提醒" };

int  cron_list_jobs(cron_job_t *out, int max_count)
{
  time_t now;
  int n;

  if (out == NULL || max_count <= 0 || s_cron_count <= 0)
    {
      return 0;
    }

  n = (s_cron_count < max_count) ? s_cron_count : max_count;
  now = time(NULL);

  for (int i = 0; i < n; i++)
    {
      memset(&out[i], 0, sizeof(out[i]));
      snprintf(out[i].id, sizeof(out[i].id), "%08x", 0xa000u + (unsigned)i);
      snprintf(out[i].name, sizeof(out[i].name), "%s",
               s_cron_names[i % (int)(sizeof(s_cron_names) / sizeof(s_cron_names[0]))]);
      out[i].enabled = true;
      out[i].kind = CRON_KIND_AT;
      out[i].at_epoch = (int64_t)now + (int64_t)(i + 1) * 1800;
      out[i].next_run = out[i].at_epoch;
      out[i].delete_after_run = true;
      snprintf(out[i].message, sizeof(out[i].message), "%s", "闹钟");
      snprintf(out[i].channel, sizeof(out[i].channel), "%s", "system");
      snprintf(out[i].chat_id, sizeof(out[i].chat_id), "%s", "cron");
    }
  return n;
}

int  cron_add_job(cron_job_t *job)
{
  (void)job;
  return s_add_fail ? ERROR : OK;
}
int  cron_remove_job(const char *job_id) { (void)job_id; return ERROR; }

/*******************************************
 * 天气页
 *******************************************/

/* PREVIEW-FIXTURE：默认给共享缓存一份 1 分钟前的"多云 24.0°C / 12.0 km/h"数据。
 * 天气页进页面会先查共享缓存（age < TTL 直接渲染，不发起网络请求），
 * 于是截图稳定、不依赖网络；温度/风速是演示值，不是真机读数。 */
static void wx_fixture_fresh(weather_cache_s *out)
{
  memset(out, 0, sizeof(*out));
  out->valid    = true;
  out->last_ok  = true;
  out->temp_c   = 24.0f;
  out->code     = 1;      /* Open-Meteo: mainly clear */
  out->wind_kmh = 12.0f;
  out->stamp    = time(NULL) - 60;
  strcpy(out->source, "preview");
}

static void wx_fixture_stale(weather_cache_s *out)
{
  memset(out, 0, sizeof(*out));
  out->valid    = true;
  out->last_ok  = false;
  out->temp_c   = 18.5f;
  out->code     = 3;      /* overcast */
  out->wind_kmh = 9.0f;
  out->stamp    = time(NULL) - 3 * 3600;
  strcpy(out->source, "preview-stale");
}

bool weather_cache_get(weather_cache_s *out)
{
  if (s_wx_mode == 1 || s_wx_mode == 2)
    {
      if (out != NULL) memset(out, 0, sizeof(*out));
      return false;
    }

  if (out == NULL) return true;

  if (s_wx_mode == 3) wx_fixture_stale(out);
  else                wx_fixture_fresh(out);
  return true;
}

int  weather_cache_age_s(void)
{
  if (s_wx_mode == 3) return 3 * 3600;
  if (s_wx_mode == 0) return 60;
  return -1;
}
void weather_cache_init(void) {}
void weather_cache_update(float temp_c, int code, float wind_kmh, const char *source)
{
  (void)temp_c; (void)code; (void)wind_kmh; (void)source;
}
void weather_cache_mark_fail(void) {}

int vela_https_get(const char *host, const char *port, const char *path,
                   char *resp_buf, size_t resp_cap)
{
  (void)host; (void)port; (void)path;
  if (resp_buf && resp_cap) resp_buf[0] = '\0';

  /* loading 态：worker 卡在"网络请求"里，UI 保持在 WX_BUSY 的"获取中…"。
   * 进程退出时 worker 会被一起结束，不需要真的等 10 秒；留足够长的余量，
   * 保证慢机器上截图时请求也一定还没返回。 */
  if (s_wx_mode == 2)
    {
      usleep(10 * 1000 * 1000);
    }
  return VELA_TLS_ERR_CONNECT;
}

/* 天气页只在缓存失效时才会走到 cJSON；host 预览用缓存命中路径，
 * 这里只需让链接通过（不实现 JSON 解析）。 */
cJSON *cJSON_Parse(const char *value) { (void)value; return NULL; }
cJSON *cJSON_GetObjectItem(const cJSON *object, const char *string)
{
  (void)object; (void)string;
  return NULL;
}
int cJSON_IsNumber(const cJSON *item) { (void)item; return 0; }
void cJSON_Delete(cJSON *item) { (void)item; }
