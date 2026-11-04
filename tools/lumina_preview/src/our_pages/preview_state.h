#ifndef LUMINA_PREVIEW_STATE_H
#define LUMINA_PREVIEW_STATE_H

/* host 预览专用的小接口：把「页面处于什么状态」与「页面源码」解耦。
 *
 * 这些符号只由 src/our_pages/stubs.c 提供，固件构建不会编译到它们。
 * main.c 在页面 create 之前调用 preview_set_state()，之后可调用
 * preview_swipe() 模拟一次页面级手势。
 */

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* state 为 NULL/空串 = 现有 14 页默认空数据态（行为必须与旧版一致）。
 * 支持的取值见 scripts/preview_our_states.sh 的 STATE_SPECS。 */
void preview_set_state(const char *state);

/* 直接调用页面通过 ui_gesture_attach() 注册的回调。
 * direction 取 ui_gesture_dir_t（UI_GESTURE_SWIPE_UP = 1）。 */
void preview_swipe(int direction);

/* watchface_page.c 在 -DUI_SIM 下会调用这两个函数。
 * 默认态返回 -1 / 100，保证表盘仍显示 "--" 与固定 100% 电量。 */
long ui_sim_steps(void);
int  ui_sim_battery(void);

#ifdef __cplusplus
}
#endif

#endif /* LUMINA_PREVIEW_STATE_H */
