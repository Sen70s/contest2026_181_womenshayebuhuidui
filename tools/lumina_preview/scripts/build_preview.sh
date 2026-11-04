#!/usr/bin/env bash
# 编译 host preview harness（lumina UI + harness + 资源 + LVGL 全部 .o 链接成可执行）。
#
# 我们的源码用 -Wall -Wextra（能看见问题），但不用 -Werror：上游 lumina UI 未按
# -Werror 开发，加 -Werror 只会让搬移工作变成改别人的代码（本任务明令不改固件源码）。
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

require_file "$LV_CONF"
require_file "$GEN_DIR/lumina_expressions.c"
require_file "$TOOL_SRC/host_preview.c"
mkdir -p "$BUILD_DIR"

# 上游 lumina UI 源码用 -w 编：它们不是我们的代码，只为渲染服务
for f in lumina_watch_ui lumina_expressions; do
    printf '  ui   %-22s ' "$f"
    # shellcheck disable=SC2086
    gcc $ASSET_CFLAGS $PREVIEW_INC -c "$TOOL_SRC/$f.c" -o "$BUILD_DIR/ui_$f.o" \
        2>"$BUILD_DIR/preview_warnings_$f.txt"
    echo "-> $(stat -c %s "$BUILD_DIR/ui_$f.o") B ($(wc -l <"$BUILD_DIR/preview_warnings_$f.txt") 条告警)"
done

# 我们自己的 harness 代码：-Wall -Wextra，告警留档
printf '  host %-22s ' "host_preview"
# shellcheck disable=SC2086
gcc $PREVIEW_CFLAGS $PREVIEW_INC -c "$TOOL_SRC/host_preview.c" -o "$BUILD_DIR/ui_host_preview.o" \
    2>"$BUILD_DIR/preview_warnings_host_preview.txt"
echo "-> $(stat -c %s "$BUILD_DIR/ui_host_preview.o") B ($(wc -l <"$BUILD_DIR/preview_warnings_host_preview.txt") 条告警)"

# 表情 id -> lv_image_dsc_t 指针表
printf '  gen  %-22s ' "expr_dsc_map.c"
# shellcheck disable=SC2086
gcc $ASSET_CFLAGS $PREVIEW_INC -c "$GEN_DIR/expr_dsc_map.c" -o "$BUILD_DIR/ui_expr_dsc_map.o"
echo "-> $(stat -c %s "$BUILD_DIR/ui_expr_dsc_map.o") B"

info "[preview] 链接 host_preview..."
# 只链接 host_preview 需要的 .o（coverage 有自己的 main，单独链接）
# shellcheck disable=SC2086
gcc -o "$BUILD_DIR/host_preview" \
    "$BUILD_DIR"/ui_lumina_watch_ui.o "$BUILD_DIR"/ui_lumina_expressions.o \
    "$BUILD_DIR"/ui_expr_dsc_map.o "$BUILD_DIR"/ui_host_preview.o \
    "$BUILD_DIR"/asset_*.o "$BUILD_DIR"/lvgl_*.o -lm
ok "[preview] -> $BUILD_DIR/host_preview ($(stat -c %s "$BUILD_DIR/host_preview") B)"

# 缺字交叉验证小工具（不参与 host_preview 链接，单独可执行文件）
printf '  chk  %-22s ' "coverage.c"
# shellcheck disable=SC2086
gcc $PREVIEW_CFLAGS $PREVIEW_INC -c "$TOOL_SRC/coverage.c" -o "$BUILD_DIR/chk_coverage.o" \
    2>"$BUILD_DIR/preview_warnings_coverage.txt"
# shellcheck disable=SC2086
gcc -o "$BUILD_DIR/coverage" "$BUILD_DIR/chk_coverage.o" \
    "$BUILD_DIR"/asset_ui_font_*.o "$BUILD_DIR"/lvgl_*.o -lm
echo "-> $BUILD_DIR/coverage"
ok "[preview] 链接完成"
