#!/usr/bin/env bash
# =============================================================================
# preview_our_pages.sh —— 在 PC 上渲染**我们固件自己的**全部 14 个页面
#
# 为什么需要它：
#   烧录要人拔插 USB、一次要几分钟，而布局错了（元素超出屏幕、图片不缩放、
#   字体没生效）在真机上才发现，返工成本高。这个脚本用**真机同一份** LVGL、
#   字体、表情/图标资产、ui_theme.c 在我们自己的页面源码上跑一遍，直接出 PNG。
#
#   它能验证：布局是否越界/重叠、lv_image_set_scale 是否真的缩了、字体是否
#   有字、配色是否落在浅色主题上。
#   它**不能**验证：触摸手势、定时器行为、真实数据（心率/步数/天气在这里
#   是空状态或固定演示值）、真实帧率。
#
# 前置条件：先跑过一次 run.sh（需要 build/lvgl_*.o 那批 host LVGL 目标文件）。
#
# 用法：
#   bash tools/lumina_preview/scripts/preview_our_pages.sh              # 14 页全渲
#   bash tools/lumina_preview/scripts/preview_our_pages.sh watch        # 只渲表盘
#   bash tools/lumina_preview/scripts/preview_our_pages.sh heart spo2   # 指定多页
#
# 页面名（顺序与 ui_nav.h 的 ui_page_t 一致）：
#   watch apps heart spo2 music timer sport sleep settings ai about alarm
#   weather expr
#
# 产物：
#   out/our_<page>.png                     14 张页面截图
#   out/our_pages_contact_sheet.png        4x4 拼图（带页名标签）
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

ALL_PAGES=(watch apps heart spo2 music timer sport sleep settings ai about alarm weather expr)

PAGES=("$@")
FULL_RUN=0
if [ ${#PAGES[@]} -eq 0 ]; then
    PAGES=("${ALL_PAGES[@]}")
    FULL_RUN=1
fi

# 校验页面名，避免把拼写错误当成渲染失败
for p in "${PAGES[@]}"; do
    found=0
    for q in "${ALL_PAGES[@]}"; do
        [ "$p" = "$q" ] && found=1
    done
    [ "$found" = 1 ] || die "未知页面 '$p'。可选：${ALL_PAGES[*]}"
done

# 构建逻辑统一在 build_our_pages.sh（preview_our_states.sh 也复用它）
"$TOOL_SCRIPTS/build_our_pages.sh"
OBJ_DIR="$BUILD_DIR/our_pages"
mkdir -p "$OUT_DIR"

RENDER_FAILED=()
for p in "${PAGES[@]}"; do
    ppm="$OUT_DIR/our_$p.ppm"
    png="$OUT_DIR/our_$p.png"
    if ! "$OBJ_DIR/our_pages_preview" "$p" "$ppm"; then
        warn "[our_pages] 页面 '$p' 渲染失败（见上方错误）"
        RENDER_FAILED+=("$p")
        rm -f "$ppm"
        continue
    fi
    python3 - "$ppm" "$png" <<'PY'
import sys
from PIL import Image
Image.open(sys.argv[1]).save(sys.argv[2])
PY
    rm -f "$ppm"
    ok "[our_pages] out/our_$p.png"
done

# 全量模式才生成 contact sheet / 做完整校验；指定单页时只校验该页。
if [ "$FULL_RUN" = 1 ]; then
    if [ ${#RENDER_FAILED[@]} -ne 0 ]; then
        warn "[our_pages] 有 ${#RENDER_FAILED[@]} 页渲染失败：${RENDER_FAILED[*]}"
    fi
    python3 "$TOOL_SCRIPTS/make_our_sheet.py" "$OUT_DIR"
    python3 "$TOOL_SCRIPTS/verify_our_pages.py" "$OUT_DIR" \
        | tee "$OUT_DIR/our_pages_verify.txt"
else
    python3 "$TOOL_SCRIPTS/verify_our_pages.py" "$OUT_DIR" "${PAGES[@]}" \
        | tee "$OUT_DIR/our_pages_verify.txt"
fi

if [ ${#RENDER_FAILED[@]} -ne 0 ]; then
    exit 1
fi
