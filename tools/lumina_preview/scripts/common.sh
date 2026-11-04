#!/usr/bin/env bash
# 公共路径/环境解析。所有脚本 source 本文件，禁止各脚本自己写死绝对路径。
#
# 目录关系（以 REPO 根为基准）：
#   REPO/tools/lumina_preview/            <- TOOL_DIR
#   REPO/../apps/graphics/lvgl/lvgl       <- LVGL_DIR        (仓内 LVGL 源码)
#   REPO/../packages/ai_agent/src/ui      <- DEVICE_UI_DIR   (我们固件的 UI，只读)
#   REPO/../packages/ai_agent/src/ui/assets <- DEVICE_ASSETS (真机字体，只读)
#
# 所有路径都可以被同名环境变量覆盖，方便换仓/换机器：
#   LUMINA_PREVIEW_BUILD / LUMINA_PREVIEW_OUT / LUMINA_LVGL_DIR /
#   LUMINA_DEVICE_UI_DIR / LUMINA_DEVICE_ASSETS

set -euo pipefail

TOOL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$TOOL_DIR/../.." && pwd)"
VENDOR_ROOT="$(cd "$REPO_ROOT/.." && pwd)"

LVGL_DIR="${LUMINA_LVGL_DIR:-$VENDOR_ROOT/apps/graphics/lvgl/lvgl}"
LVGL_INC_ROOT="$(cd "$LVGL_DIR/.." && pwd)"          # 供 #include "lvgl/lvgl.h"
DEVICE_UI_DIR="${LUMINA_DEVICE_UI_DIR:-$VENDOR_ROOT/packages/ai_agent/src/ui}"
DEVICE_ASSETS="${LUMINA_DEVICE_ASSETS:-$DEVICE_UI_DIR/assets}"

TOOL_SRC="$TOOL_DIR/src"
TOOL_SCRIPTS="$TOOL_DIR/scripts"
TOOL_ASSETS="$TOOL_DIR/assets"
EXPR_SRC_DIR="$TOOL_ASSETS/expressions"
GEN_DIR="$TOOL_ASSETS/generated"
GOLDEN_DIR="$TOOL_ASSETS/golden"
BUILD_DIR="${LUMINA_PREVIEW_BUILD:-$TOOL_DIR/build}"
OUT_DIR="${LUMINA_PREVIEW_OUT:-$TOOL_DIR/out}"
LV_CONF="$BUILD_DIR/lv_conf.h"

# 屏幕尺寸：与固件一致（SF32LB52 的 390x450）
SCREEN_W=390
SCREEN_H=450

# 渲染 14 页（顺序即 page_01..page_14 的文件名顺序，来自 host_preview 的 PAGES[]）
PAGE_COUNT=14

die()  { printf '\033[31m[FAIL]\033[0m %s\n' "$*" >&2; exit 1; }
info() { printf '\033[36m[ .. ]\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m[ OK ]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[WARN]\033[0m %s\n' "$*"; }

require_dir() {
    [ -d "$1" ] || die "目录不存在: $1
  换个位置跑时用环境变量指定，比如 LUMINA_LVGL_DIR=/path/to/lvgl/lvgl"
}

require_file() {
    [ -f "$1" ] || die "文件不存在: $1"
}

# 公共编译参数
CFLAGS_COMMON="-O1 -g -std=gnu99 -fno-omit-frame-pointer"
# 注：LVGL 与资产用 -w（上游/生成代码告警不重要），我们自己的 preview 源码用 -Wall -Wextra
LVGL_CFLAGS="$CFLAGS_COMMON -w"
ASSET_CFLAGS="$CFLAGS_COMMON -w"
PREVIEW_CFLAGS="$CFLAGS_COMMON -Wall -Wextra"

LVGL_INC="-I$BUILD_DIR -I$LVGL_DIR -I$LVGL_INC_ROOT -DLV_CONF_INCLUDE_SIMPLE"
ASSET_INC="-I$BUILD_DIR -I$LVGL_DIR -I$LVGL_INC_ROOT -DLV_CONF_INCLUDE_SIMPLE"
PREVIEW_INC="$ASSET_INC -I$TOOL_SRC -I$BUILD_DIR"

export TOOL_DIR REPO_ROOT VENDOR_ROOT LVGL_DIR LVGL_INC_ROOT DEVICE_UI_DIR \
       DEVICE_ASSETS TOOL_SRC TOOL_SCRIPTS TOOL_ASSETS EXPR_SRC_DIR GEN_DIR \
       GOLDEN_DIR BUILD_DIR OUT_DIR LV_CONF SCREEN_W SCREEN_H PAGE_COUNT \
       LVGL_CFLAGS ASSET_CFLAGS PREVIEW_CFLAGS LVGL_INC ASSET_INC PREVIEW_INC
