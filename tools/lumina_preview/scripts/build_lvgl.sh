#!/usr/bin/env bash
# 编译 LVGL 9.1 核心（host / gcc / 无 OS / 无 SDL）。
#
# 幂等：编过就跳过（标记 $BUILD_DIR/.lvgl_done）。想强制重编：
#   rm -f build/.lvgl_done && bash scripts/build_lvgl.sh
#
# 为什么 .o 名不是简单的 basename：LVGL 源码里有重名 basename
# （lv_draw_sw_vector.c / lv_draw_vector.c、lv_sdl_mouse.c 与
# lv_sdl_mouse_gesture.c 等都只是目录不同），扁平化到同一个 build/ 目录会互相
# 覆盖，链接时随机缺符号。所以 .o 名取「相对 LVGL/src 的路径的 md5 前 12 位 +
# basename」，这个方案在原型 harness 上实测有效（358 个 .o 对应 358 个源文件）。
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

require_file "$LV_CONF"
require_dir "$LVGL_DIR/src"
mkdir -p "$BUILD_DIR"

if [ -f "$BUILD_DIR/.lvgl_done" ]; then
    ok "[lvgl] 已编译（$BUILD_DIR/.lvgl_done），跳过"
    exit 0
fi

mapfile -t SRCS < <(find "$LVGL_DIR/src" -name '*.c' | sort)
[ "${#SRCS[@]}" -gt 0 ] || die "[lvgl] $LVGL_DIR/src 下没有 .c"
info "[lvgl] 编译 ${#SRCS[@]} 个源文件 -> $BUILD_DIR"

compile_one() {
    local f="$1" root="$2" out="$3" inc="$4" cflags="$5" log="$6"
    local key name obj
    key=$(printf '%s' "${f#$root/}" | md5sum | cut -c1-12)
    name=$(basename "$f" .c)
    obj="$out/lvgl_${key}_${name}.o"
    # shellcheck disable=SC2086
    gcc $cflags $inc -c "$f" -o "$obj" 2>>"$log"
}

export -f compile_one

: > "$BUILD_DIR/lvgl_compile.log"
printf '%s\0' "${SRCS[@]}" | \
    xargs -0 -P"$(nproc)" -I{} bash -c \
        'compile_one "$1" "$2" "$3" "$4" "$5" "$6"' _ \
        {} "$LVGL_DIR" "$BUILD_DIR" "$LVGL_INC" "$LVGL_CFLAGS" \
        "$BUILD_DIR/lvgl_compile.log"

if grep -q 'error:' "$BUILD_DIR/lvgl_compile.log" 2>/dev/null; then
    echo "[lvgl] 编译错误（前 20 条）:" >&2
    grep 'error:' "$BUILD_DIR/lvgl_compile.log" | head -20 >&2
    rm -f "$BUILD_DIR/.lvgl_done"
    exit 1
fi

n=$(ls "$BUILD_DIR"/lvgl_*.o 2>/dev/null | wc -l | tr -d ' ')
[ "$n" -eq "${#SRCS[@]}" ] || die "[lvgl] .o 数量 $n != 源文件数 ${#SRCS[@]}（重名 basename 冲突？见脚本注释）"

touch "$BUILD_DIR/.lvgl_done"
ok "[lvgl] 生成 $n 个 .o（期望 ${#SRCS[@]}）"
