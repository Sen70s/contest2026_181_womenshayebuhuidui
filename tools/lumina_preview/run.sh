#!/usr/bin/env bash
# =============================================================================
# lumina_preview —— 一条命令在 PC 上把 lumina 手表 UI 的 14 个页面渲染成 PNG
#
#   bash tools/lumina_preview/run.sh              # 正常跑（增量，编过就跳过）
#   bash tools/lumina_preview/run.sh --clean      # 先删 build/out/生成物再全量编译
#   bash tools/lumina_preview/run.sh --no-fonts   # 跳过「我们固件缺字检查」
#   bash tools/lumina_preview/run.sh --no-verify  # 跳过与 assets/golden 的像素比对
#
# 步骤：依赖检查 -> 生成 lv_conf.h -> 编 LVGL -> 生成表情资产 -> 编资产 ->
#       编 UI+harness -> 渲染 14 页 -> raw->PNG -> contact sheet -> 像素比对
#       -> 缺字检查
#
# 幂等：LVGL 编过（build/.lvgl_done 存在）就跳过；其余步骤都很快，每次重跑。
# 产物全部落在 build/ 与 out/（已被 tools/lumina_preview/.gitignore 覆盖），
# 唯一例外是 assets/generated/（构建中间件，同样被忽略）。
# =============================================================================
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/scripts/common.sh"

DO_CLEAN=0
DO_FONTS=1
DO_VERIFY=1
for arg in "$@"; do
    case "$arg" in
        --clean)     DO_CLEAN=1 ;;
        --no-fonts)  DO_FONTS=0 ;;
        --no-verify) DO_VERIFY=0 ;;
        -h|--help)
            sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
            exit 0 ;;
        *) die "未知参数: $arg（用 --help 看用法）" ;;
    esac
done

T0=$SECONDS
STEP_T0=$SECONDS
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; STEP_T0=$SECONDS; }
step_done() { printf '\033[32m   (%ss)\033[0m\n' "$((SECONDS - STEP_T0))"; }

# ---------------------------------------------------------------- 0 依赖检查
step "0/10 依赖检查"
missing=0
command -v gcc >/dev/null 2>&1 || { warn "缺 gcc：sudo apt install -y build-essential"; missing=1; }
command -v python3 >/dev/null 2>&1 || { warn "缺 python3：sudo apt install -y python3"; missing=1; }
if command -v python3 >/dev/null 2>&1; then
    python3 -c 'import PIL' 2>/dev/null || {
        warn "缺 Pillow：python3 -m pip install --user pillow"; missing=1; }
    python3 -c 'import numpy' 2>/dev/null || {
        warn "缺 numpy（convert_expr.py 预乘 alpha 缩放要用）：python3 -m pip install --user numpy"; missing=1; }
    python3 -c 'import fontTools' 2>/dev/null || true   # 仅 gen_font.py 需要，预览不用
fi
# contact sheet 需要 Noto Sans CJK（无它也能出 PNG，只是没有中文标签）
if ! ls /usr/share/fonts/*/noto/NotoSansCJK-Bold.ttc >/dev/null 2>&1; then
    warn "找不到 NotoSansCJK-Bold.ttc（contact sheet 中文标签会用拼音占位）："
    warn "  sudo apt install -y fonts-noto-cjk"
    missing=1
fi
if [ "$missing" -ne 0 ]; then
    die "依赖不全，先装上面列出的包再重跑（也可以只跑某一步：bash scripts/build_lvgl.sh）"
fi
require_dir "$LVGL_DIR"
require_file "$LVGL_DIR/lv_conf_template.h"
require_dir "$DEVICE_ASSETS"
require_dir "$EXPR_SRC_DIR"
ok "依赖齐全"
ok "   LVGL : $LVGL_DIR"
ok "   字体 : $DEVICE_ASSETS"
step_done

# ---------------------------------------------------------------- 0.5 清理
if [ "$DO_CLEAN" -eq 1 ]; then
    step "0.5/10 清理构建产物（--clean）"
    rm -rf "$BUILD_DIR" "$OUT_DIR" "$GEN_DIR"
    ok "已删除 build/ out/ assets/generated/"
    step_done
fi

mkdir -p "$BUILD_DIR" "$OUT_DIR" "$GEN_DIR"

# ---------------------------------------------------------------- 1 lv_conf
step "1/10 生成 lv_conf.h（host 配置）"
if [ -f "$LV_CONF" ] && [ "$LV_CONF" -nt "$LVGL_DIR/lv_conf_template.h" ]; then
    ok "已是最新，跳过: $LV_CONF"
else
    python3 "$TOOL_SCRIPTS/gen_conf.py" --src "$LVGL_DIR/lv_conf_template.h" --out "$LV_CONF"
    ok "lv_conf.h -> $LV_CONF"
fi
step_done

# ---------------------------------------------------------------- 2 LVGL
step "2/10 编译 LVGL 核心（$(find "$LVGL_DIR/src" -name '*.c' | wc -l | tr -d ' ') 个源文件）"
bash "$TOOL_SCRIPTS/build_lvgl.sh"
step_done

# ---------------------------------------------------------------- 3 资产
step "3/10 生成表情资产（50 张 PNG -> RGB565A8）"
n_png=$(find "$EXPR_SRC_DIR" -name '*.png' | wc -l | tr -d ' ')
[ "$n_png" -eq 50 ] || warn "assets/expressions 里有 $n_png 张 PNG（期望 50）"
if [ -f "$GEN_DIR/lumina_expressions.c" ]; then
    ok "已存在，跳过（要重生成用 --clean）: $GEN_DIR/lumina_expressions.c"
else
    python3 "$TOOL_SCRIPTS/convert_expr.py" --size 96 --variant crop --all \
        --src "$EXPR_SRC_DIR" --out "$GEN_DIR"
fi
python3 "$TOOL_SCRIPTS/gen_expr_map.py" \
    --ui-src "$TOOL_SRC/lumina_expressions.c" \
    --asset "$GEN_DIR/lumina_expressions.c" \
    --out "$GEN_DIR/expr_dsc_map.c"
step_done

# ---------------------------------------------------------------- 4 编译
step "4/10 编译真机字体 + 表情资产"
bash "$TOOL_SCRIPTS/build_assets.sh"
step_done

step "5/10 编译 lumina UI + harness"
bash "$TOOL_SCRIPTS/build_preview.sh"
step_done

# ---------------------------------------------------------------- 6 渲染
step "6/10 渲染 14 页（RGB565 raw）"
"$BUILD_DIR/host_preview" "$OUT_DIR" | tee "$OUT_DIR/render.log" | tail -3
grep -q 'TOTAL_ISSUES=' "$OUT_DIR/scan.txt" || die "渲染没产出 scan.txt"
ok "对象树扫描: $(grep 'TOTAL_ISSUES=' "$OUT_DIR/scan.txt") -> $OUT_DIR/scan.txt"
step_done

# ---------------------------------------------------------------- 7 PNG
step "7/10 raw -> PNG"
python3 "$TOOL_SCRIPTS/raw2png.py" "$OUT_DIR" "$OUT_DIR" --manifest "$OUT_DIR/page_manifest.txt" \
    | tail -3
n_png_out=$(find "$OUT_DIR" -maxdepth 1 -name 'page_*.png' | wc -l | tr -d ' ')
[ "$n_png_out" -eq "$PAGE_COUNT" ] || die "只生成了 $n_png_out 张 PNG，期望 $PAGE_COUNT"
ok "$n_png_out/$PAGE_COUNT 张 PNG -> $OUT_DIR"
step_done

# ---------------------------------------------------------------- 8 sheet
step "8/10 contact sheet"
python3 "$TOOL_SCRIPTS/make_sheet.py" "$OUT_DIR"
step_done

# ---------------------------------------------------------------- 9 像素比对
VERIFY_RC=0
if [ "$DO_VERIFY" -eq 1 ]; then
    step "9/10 与 assets/golden 参考图逐像素比对"
    set +e
    python3 "$TOOL_SCRIPTS/verify_golden.py" "$OUT_DIR" 2>&1 | tee "$OUT_DIR/golden_diff.txt"
    VERIFY_RC=${PIPESTATUS[0]}
    set -e
    if [ "$VERIFY_RC" -ne 0 ]; then
        warn "与参考图不一致（详见 $OUT_DIR/golden_diff.txt）。"
        warn "换编译器/Pillow 版本可能有少量抗锯齿差异；确认不是回归再更新 assets/golden。"
    fi
    step_done
else
    step "9/10 像素比对（--no-verify 跳过）"
fi

# ---------------------------------------------------------------- 10 缺字
FONT_RC=0
if [ "$DO_FONTS" -eq 1 ]; then
    step "10/10 我们固件的字体缺字检查（不含在预览里，是对我们自己 UI 的回归）"
    set +e
    python3 "$TOOL_SCRIPTS/check_our_fonts.py" 2>&1 | tee "$OUT_DIR/our_font_coverage.txt"
    FONT_RC=${PIPESTATUS[0]}
    set -e
    if [ "$FONT_RC" -ne 0 ]; then
        warn "我们固件 UI 有字体里没有的字（详见上面 TOFU 段）。"
        warn "这不会让预览失败，但会让 run.sh 退出码非 0，方便以后接 CI。"
    else
        ok "我们固件 UI 无 TOFU 缺字"
    fi
    step_done
else
    step "10/10 缺字检查（--no-fonts 跳过）"
fi

# ---------------------------------------------------------------- 汇总
step "完成"
printf '  页面 PNG   : %s/page_XX_*.png（%d 张）\n' "$OUT_DIR" "$PAGE_COUNT"
printf '  contact    : %s/contact_sheet.png\n' "$OUT_DIR"
printf '  缺陷扫描   : %s/scan.txt\n' "$OUT_DIR"
printf '  缺字报告   : %s/our_font_coverage.txt\n' "$OUT_DIR"
printf '  像素比对   : %s/golden_diff.txt\n' "$OUT_DIR"
printf '  总耗时     : %ss（LVGL 首次全量本机 9s（20 核），二次增量 4s（核心少会更慢））\n' "$((SECONDS - T0))"
RC=0
[ "$VERIFY_RC" -ne 0 ] && RC=1
[ "$FONT_RC" -ne 0 ] && RC=1
if [ "$RC" -ne 0 ]; then
    printf '\n\033[33m退出码 %s（像素比对 %s / 固件缺字 %s），预览产物已全部生成。\033[0m\n' \
        "$RC" "$VERIFY_RC" "$FONT_RC"
fi
exit "$RC"
