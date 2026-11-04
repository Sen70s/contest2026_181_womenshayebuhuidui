#!/usr/bin/env bash
# =============================================================================
# build_our_pages.sh —— 编「我们固件自己的 14 个页面」host 预览可执行文件
#
# 这是 preview_our_pages.sh 与 preview_our_states.sh 共用的唯一构建入口。
# 只读固件源码（packages/ai_agent/src/ui），不写任何固件文件。
#
# 前置条件：先跑过一次 run.sh（需要 build/lvgl_*.o 那批 host LVGL 目标文件）。
#
# 产物：build/our_pages/our_pages_preview
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

require_file "$LV_CONF"
require_dir "$BUILD_DIR"
if ! ls "$BUILD_DIR"/lvgl_*.o >/dev/null 2>&1; then
    die "build/lvgl_*.o 不存在 —— 先跑一次 bash tools/lumina_preview/run.sh（它会编 host LVGL）"
fi
for f in ui_font_cjk_18 ui_font_cjk_24 ui_font_sans_88_bold; do
    require_file "$BUILD_DIR/asset_$f.o"
done
require_file "$DEVICE_UI_DIR/ui_theme.c"
require_file "$DEVICE_UI_DIR/expression.c"
require_file "$DEVICE_ASSETS/ui_expressions.c"
require_file "$DEVICE_ASSETS/ui_icons.c"

OUR_SRC="$TOOL_SRC/our_pages"
FAKE_SRC="$OUR_SRC/fakeinc"
require_file "$OUR_SRC/main.c"
require_file "$OUR_SRC/stubs.c"
require_file "$FAKE_SRC/cJSON.h"
require_file "$FAKE_SRC/nuttx/lcd/lcd_dev.h"

OBJ_DIR="$BUILD_DIR/our_pages"
FAKE_INC="$OBJ_DIR/fakeinc"
mkdir -p "$OBJ_DIR" "$FAKE_INC/nuttx" "$FAKE_INC/nuttx/lcd" "$OUT_DIR"

# 固件源码里有 #include <nuttx/config.h>；host 上给一个空头。
# 不定义任何会改变页面逻辑的 CONFIG_*：预览走"最小功能"分支（无 uORB/
# 算法/传感器），这正好对应"板子上没有数据"的渲染结果。
# OK/ERROR 是 NuttX 常量；CONFIG_LCD_MAXCONTRAST 只被设置页的亮度换算用到。
# 通过 -include 强制包含，因为 alarm_page.c -> cron_service.h -> agent_compat.h
# 这条链本身不 include <nuttx/config.h>。
cat > "$FAKE_INC/nuttx/config.h" <<'EOF'
/* 自动生成（build_our_pages.sh）。空 NuttX 配置头，只让固件源码能编译。 */
#ifndef __PREVIEW_FAKE_NUTTX_CONFIG_H
#define __PREVIEW_FAKE_NUTTX_CONFIG_H

#ifndef OK
#define OK 0
#endif
#ifndef ERROR
#define ERROR -1
#endif

/* 设置页亮度档位换算：真机 NuttX 里是 0..255 */
#ifndef CONFIG_LCD_MAXCONTRAST
#define CONFIG_LCD_MAXCONTRAST 255
#endif

#endif
EOF

OUR_INC="-I$FAKE_INC -I$FAKE_SRC -I$BUILD_DIR -I$LVGL_DIR -I$LVGL_INC_ROOT -DLV_CONF_INCLUDE_SIMPLE
         -I$VENDOR_ROOT/packages/ai_agent/src -I$VENDOR_ROOT/packages/ai_agent/include
         -I$DEVICE_UI_DIR"
# -DUI_SIM：watchface_page.c 的步数分支改走 ui_sim_steps()（stubs.c 提供）。
# 默认态返回 -1，所以 14 页默认截图仍然是 "--"，不改变既有产物。
OUR_CFLAGS="-O1 -g -std=gnu99 -w -DUI_SIM -include $FAKE_INC/nuttx/config.h"

info "[our_pages] 编译我们固件的 14 个页面源码"
compile_one() {
    local src=$1 obj=$2
    printf '  %-22s ' "$(basename "$src")"
    # shellcheck disable=SC2086
    gcc $OUR_CFLAGS $OUR_INC -c "$src" -o "$obj"
    echo "-> $(stat -c %s "$obj") B"
}

compile_one "$DEVICE_UI_DIR/ui_theme.c"        "$OBJ_DIR/ui_theme.o"
compile_one "$DEVICE_UI_DIR/expression.c"      "$OBJ_DIR/expression.o"
compile_one "$DEVICE_UI_DIR/expression_page.c" "$OBJ_DIR/expression_page.o"
compile_one "$DEVICE_UI_DIR/applist_page.c"    "$OBJ_DIR/applist_page.o"
compile_one "$DEVICE_UI_DIR/watchface_page.c"  "$OBJ_DIR/watchface_page.o"
compile_one "$DEVICE_UI_DIR/pet_page.c"        "$OBJ_DIR/pet_page.o"
compile_one "$DEVICE_UI_DIR/heart_page.c"      "$OBJ_DIR/heart_page.o"
compile_one "$DEVICE_UI_DIR/spo2_page.c"       "$OBJ_DIR/spo2_page.o"
compile_one "$DEVICE_UI_DIR/music_page.c"      "$OBJ_DIR/music_page.o"
compile_one "$DEVICE_UI_DIR/timer_page.c"      "$OBJ_DIR/timer_page.o"
compile_one "$DEVICE_UI_DIR/sport_page.c"      "$OBJ_DIR/sport_page.o"
compile_one "$DEVICE_UI_DIR/sleep_page.c"      "$OBJ_DIR/sleep_page.o"
compile_one "$DEVICE_UI_DIR/settings_page.c"   "$OBJ_DIR/settings_page.o"
compile_one "$DEVICE_UI_DIR/about_page.c"      "$OBJ_DIR/about_page.o"
compile_one "$DEVICE_UI_DIR/alarm_page.c"      "$OBJ_DIR/alarm_page.o"
compile_one "$DEVICE_UI_DIR/weather_page.c"    "$OBJ_DIR/weather_page.o"
compile_one "$DEVICE_ASSETS/ui_expressions.c"  "$OBJ_DIR/ui_expressions.o"
compile_one "$DEVICE_ASSETS/ui_icons.c"        "$OBJ_DIR/ui_icons.o"
compile_one "$OUR_SRC/stubs.c"                 "$OBJ_DIR/stubs.o"
compile_one "$OUR_SRC/main.c"                  "$OBJ_DIR/main.o"

printf '  link                   '
gcc -o "$OBJ_DIR/our_pages_preview" "$OBJ_DIR"/*.o \
    "$BUILD_DIR/asset_ui_font_cjk_18.o" \
    "$BUILD_DIR/asset_ui_font_cjk_24.o" \
    "$BUILD_DIR/asset_ui_font_sans_88_bold.o" \
    "$BUILD_DIR"/lvgl_*.o -lm -lpthread
echo "-> $OBJ_DIR/our_pages_preview"

ok "[our_pages] 编译完成"
