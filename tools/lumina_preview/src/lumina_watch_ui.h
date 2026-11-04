#ifndef LUMINA_WATCH_UI_H
#define LUMINA_WATCH_UI_H

#include <stdbool.h>
#include <stdint.h>
#include "lvgl.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    LUMINA_PAGE_WATCHFACE,
    LUMINA_PAGE_APPS,
    LUMINA_PAGE_HEART,
    LUMINA_PAGE_SPO2,
    LUMINA_PAGE_SPORT,
    LUMINA_PAGE_REST,
    LUMINA_PAGE_ALARM,
    LUMINA_PAGE_TIMER,
    LUMINA_PAGE_WEATHER,
    LUMINA_PAGE_SETTINGS,
    LUMINA_PAGE_ABOUT,
    LUMINA_PAGE_AI,
    LUMINA_PAGE_EXPRESSIONS,
    LUMINA_PAGE_MUSIC,
    LUMINA_PAGE_COUNT
} lumina_page_t;

typedef enum {
    LUMINA_ACTION_MEASURE_HEART,
    LUMINA_ACTION_MEASURE_SPO2,
    LUMINA_ACTION_START_TIMER,
    LUMINA_ACTION_STOP_TIMER,
    LUMINA_ACTION_ADD_ALARM,
    LUMINA_ACTION_ACK_ALARM,
    LUMINA_ACTION_AI_TALK,
    LUMINA_ACTION_AI_LATER,
    LUMINA_ACTION_BRIGHTNESS,
    LUMINA_ACTION_DND,
    LUMINA_ACTION_SCREEN_OFF,
    LUMINA_ACTION_SELECT_EXPRESSION,
    LUMINA_ACTION_MUSIC_TOGGLE,
    LUMINA_ACTION_TOGGLE_ALARM,
    LUMINA_ACTION_COUNT
} lumina_action_t;

typedef enum {
    LUMINA_STATUS_EMPTY,
    LUMINA_STATUS_LOADING,
    LUMINA_STATUS_OK,
    LUMINA_STATUS_ERROR,
    LUMINA_STATUS_STALE
} lumina_status_t;

typedef struct {
    const char *time;               /* e.g. "07:30" */
    const char *label;
    bool enabled;
} lumina_alarm_t;

typedef struct {
    /* All strings remain owned by the caller and must remain valid until the next
       lumina_ui_set_state() call. Copy dynamic strings in the platform adapter. */
    const char *date;                /* e.g. "11月2日 · 周一" */
    const char *time;                /* e.g. "10:28" */
    const char *weather_text;
    const char *weather_updated;
    const char *ai_text;
    const char *care_reason;
    const char *firmware_version;
    const char *device_name;
    const char *heart_updated;
    const char *spo2_updated;
    const char *rest_text;
    const char *music_title;
    uint16_t heart_bpm;
    uint8_t spo2_percent;
    uint32_t steps;
    uint16_t distance_m;
    uint16_t active_minutes;
    uint16_t timer_seconds;
    uint8_t alarm_count;
    lumina_alarm_t alarms[15];
    uint8_t brightness_percent;
    uint8_t selected_expression;    /* index into lumina_expressions[50] */
    bool connected;
    bool dnd;
    bool timer_running;
    bool music_playing;
    bool care_pending;
    lumina_status_t heart_status;
    lumina_status_t spo2_status;
    lumina_status_t weather_status;
    lumina_status_t ai_status;
} lumina_ui_state_t;

typedef struct {
    const lv_font_t *font_time;      /* numeric time font, optional */
    const lv_font_t *font_body;      /* Chinese 24 px subset */
    const lv_font_t *font_small;     /* Chinese 18 px fallback */
    const void *(*expression_image)(uint8_t index, bool thumbnail, void *ctx);
    void (*action)(lumina_action_t action, int32_t value, void *ctx);
    void *ctx;
} lumina_ui_port_t;

/* Must be called from the LVGL/UI thread after LVGL and display initialization. */
void lumina_ui_init(const lumina_ui_port_t *port);
void lumina_ui_set_state(const lumina_ui_state_t *state);
void lumina_ui_show(lumina_page_t page);
lumina_page_t lumina_ui_current_page(void);

/* Feed the existing platform gesture recognizer: +1 = up, -1 = down.
   No horizontal or multi-touch gesture is used. */
void lumina_ui_swipe(int direction);

/* Map physical keys in the platform layer; this helper sends KEY1 to AI. */
void lumina_ui_key1(void);

#ifdef __cplusplus
}
#endif
#endif
