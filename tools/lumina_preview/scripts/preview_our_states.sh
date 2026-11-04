#!/usr/bin/env bash
# =============================================================================
# preview_our_states.sh —— 渲染 22 张「关键状态」截图（host LVGL，不碰真机）
#
# 与 preview_our_pages.sh 的区别：
#   preview_our_pages.sh  渲染 14 页**默认空数据态**
#   本脚本                渲染 22 个**关键状态**（有数据/进行中/错误/第 2 页/编辑区）
#
# 状态清单在 scripts/state_screens.tsv；每行 page<TAB>state<TAB>label。
# 注入方式全部在 host harness 内（不改固件源码）：
#   - 数据夹具：src/our_pages/stubs.c 的 preview_set_state()
#   - 点击：main.c 按 label 文本找可点击祖先，lv_obj_send_event(CLICKED)
#   - 手势：stubs.c 记录 ui_gesture 回调，main.c 调 preview_swipe()
#   - 时间跳变：lv_tick_inc() + lv_timer_handler()
#
# 用法：
#   bash tools/lumina_preview/scripts/preview_our_states.sh
#   bash tools/lumina_preview/scripts/preview_our_states.sh apps__page2
#   bash tools/lumina_preview/scripts/preview_our_states.sh apps__page2 timer__finished
#
# 产物（均在 tools/lumina_preview/out/）：
#   state_<page>__<state>.png        22 张
#   state_screens_contact_sheet.png  5x5 拼图（标注页名+状态）
#   state_screens_verify.txt         校验报告
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

BIN="$BUILD_DIR/our_pages/our_pages_preview"
MANIFEST="$TOOL_SCRIPTS/state_screens.tsv"
mkdir -p "$OUT_DIR"

if [ ! -f "$MANIFEST" ]; then
    die "缺状态清单: $MANIFEST"
fi

# 先统一构建（包含 -DUI_SIM 与最新的 stubs/main）
"$TOOL_SCRIPTS/build_our_pages.sh"

# 可选过滤：参数形如 apps__page2
declare -A WANT=()
for a in "$@"; do
    WANT["$a"]=1
done

RENDER_FAILED=()
RENDERED=()

while IFS=$'\t' read -r page state label; do
    case "$page" in
        ''|'#'*) continue ;;
    esac
    [ -n "${state:-}" ] || continue

    key="${page}__${state}"
    if [ ${#WANT[@]} -ne 0 ] && [ -z "${WANT[$key]:-}" ]; then
        continue
    fi

    ppm="$OUT_DIR/state_${key}.ppm"
    png="$OUT_DIR/state_${key}.png"
    if ! "$BIN" "$page" "$ppm" "$state"; then
        warn "[states] $key 渲染失败"
        RENDER_FAILED+=("$key")
        rm -f "$ppm"
        continue
    fi

    python3 - "$ppm" "$png" <<'PY'
import sys
from PIL import Image
Image.open(sys.argv[1]).save(sys.argv[2])
PY
    rm -f "$ppm"
    RENDERED+=("$key")
    ok "[states] out/state_${key}.png  (${label})"
done < "$MANIFEST"

if [ ${#RENDERED[@]} -eq 0 ]; then
    die "没有渲染任何状态（过滤参数不匹配？）"
fi

python3 "$TOOL_SCRIPTS/make_state_sheet.py" "$OUT_DIR"
if [ ${#WANT[@]} -ne 0 ]; then
    # 过滤运行：只校验本次真的渲出来的那几张，避免把没渲的旧图算进来
    python3 "$TOOL_SCRIPTS/verify_state_screens.py" "$OUT_DIR" "${RENDERED[@]}" \
        | tee "$OUT_DIR/state_screens_verify.txt"
else
    python3 "$TOOL_SCRIPTS/verify_state_screens.py" "$OUT_DIR" \
        | tee "$OUT_DIR/state_screens_verify.txt"
fi

if [ ${#RENDER_FAILED[@]} -ne 0 ]; then
    warn "[states] 有 ${#RENDER_FAILED[@]} 张渲染失败：${RENDER_FAILED[*]}"
    exit 1
fi

ok "[states] 完成：${#RENDERED[@]} 张状态截图"
