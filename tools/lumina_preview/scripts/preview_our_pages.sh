#!/usr/bin/env bash
# =============================================================================
# preview_our_pages.sh —— 在 PC 上渲染**我们固件自己的**页面（不是 lumina 原型）
#
# 为什么需要它：
#   烧录要人拔插 USB、一次要几分钟，而布局错了（元素超出屏幕、图片不缩放、
#   字体没生效）在真机上才发现，返工成本高。这个脚本用**真机同一份** LVGL、
#   字体、表情资产、ui_theme.c 在我们自己的页面源码上跑一遍，直接出 PNG。
#
#   它能验证：布局是否越界/重叠、lv_image_set_scale 是否真的缩了、字体是否
#   有字、配色是否落在浅色主题上。
#   它**不能**验证：触摸手势、定时器行为、真实数据（心率/步数/天气在这里
#   全是"--"）、真实帧率。
#
# 前置条件：先跑过一次 run.sh（需要 build/lvgl_*.o 那批 host LVGL 目标文件）。
#
# 用法：
#   bash tools/lumina_preview/scripts/preview_our_pages.sh            # 渲染四页
#   bash tools/lumina_preview/scripts/preview_our_pages.sh watch      # 只渲表盘
#   bash tools/lumina_preview/scripts/preview_our_pages.sh apps expr ai  # 指定多页
#
# 四页：watch(表盘) / apps(应用入口 2x3) / expr(表情选择) / ai(小云对话页)
#
# 产物：out/our_<page>.png（被 tools/lumina_preview/.gitignore 覆盖）
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

PAGES=("$@")
if [ ${#PAGES[@]} -eq 0 ]; then
    PAGES=(watch apps expr ai)
fi

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
require_file "$OUR_SRC/main.c"
require_file "$OUR_SRC/stubs.c"

OBJ_DIR="$BUILD_DIR/our_pages"
FAKE_INC="$OBJ_DIR/fakeinc"
mkdir -p "$OBJ_DIR" "$FAKE_INC/nuttx" "$OUT_DIR"

# 固件源码里有 #include <nuttx/config.h>；host 上给一个空头。
# 故意不定义任何 CONFIG_*：预览走"最小功能"分支（无 uORB/算法/传感器），
# 这正好对应"板子上没有数据"的渲染结果。
cat > "$FAKE_INC/nuttx/config.h" <<'EOF'
/* 自动生成（preview_our_pages.sh）。空 NuttX 配置头，只让固件源码能编译。 */
#ifndef __PREVIEW_FAKE_NUTTX_CONFIG_H
#define __PREVIEW_FAKE_NUTTX_CONFIG_H
#endif
EOF

OUR_INC="-I$FAKE_INC -I$BUILD_DIR -I$LVGL_DIR -I$LVGL_INC_ROOT -DLV_CONF_INCLUDE_SIMPLE
         -I$VENDOR_ROOT/packages/ai_agent/src -I$DEVICE_UI_DIR"
OUR_CFLAGS="-O1 -g -std=gnu99 -w"

info "[our_pages] 编译我们固件的页面源码"
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
compile_one "$DEVICE_ASSETS/ui_expressions.c"  "$OBJ_DIR/ui_expressions.o"
compile_one "$DEVICE_ASSETS/ui_icons.c"        "$OBJ_DIR/ui_icons.o"
compile_one "$OUR_SRC/stubs.c"                 "$OBJ_DIR/stubs.o"
compile_one "$OUR_SRC/main.c"                  "$OBJ_DIR/main.o"

printf '  link                 '
gcc -o "$OBJ_DIR/our_pages_preview" "$OBJ_DIR"/*.o \
    "$BUILD_DIR/asset_ui_font_cjk_18.o" \
    "$BUILD_DIR/asset_ui_font_cjk_24.o" \
    "$BUILD_DIR/asset_ui_font_sans_88_bold.o" \
    "$BUILD_DIR"/lvgl_*.o -lm -lpthread
echo "-> $OBJ_DIR/our_pages_preview"

ok "[our_pages] 编译完成"

for p in "${PAGES[@]}"; do
    ppm="$OUT_DIR/our_$p.ppm"
    png="$OUT_DIR/our_$p.png"
    "$OBJ_DIR/our_pages_preview" "$p" "$ppm"
    python3 - "$ppm" "$png" <<'PY'
import sys
from PIL import Image
Image.open(sys.argv[1]).save(sys.argv[2])
PY
    rm -f "$ppm"
    ok "[our_pages] out/our_$p.png"
done
