#!/usr/bin/env bash
# 编译「真机同一份」字体与表情资产（只读仓内文件，产物落 $BUILD_DIR）。
#
# 字体三份必须与固件里链接的是同一份源码：
#   packages/ai_agent/src/ui/assets/ui_font_cjk_24.c    (font_body，24px CJK 子集)
#   packages/ai_agent/src/ui/assets/ui_font_cjk_18.c    (font_small，18px CJK 全集)
#   packages/ai_agent/src/ui/assets/ui_font_sans_88_bold.c (font_time，88px 数字)
# host_preview.c 里的 extern 与真机符号名完全一致，这样“预览里缺字 = 真机缺字”，
# 而不是被预览器自己的替代字体掩盖。
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/common.sh"

require_file "$LV_CONF"
require_file "$GEN_DIR/lumina_expressions.c"
require_dir "$DEVICE_ASSETS"
mkdir -p "$BUILD_DIR"

for f in ui_font_cjk_24 ui_font_cjk_18 ui_font_sans_88_bold; do
    require_file "$DEVICE_ASSETS/$f.c"
    printf '  font %-22s ' "$f"
    # shellcheck disable=SC2086
    gcc $ASSET_CFLAGS $ASSET_INC -c "$DEVICE_ASSETS/$f.c" -o "$BUILD_DIR/asset_$f.o"
    echo "-> $(stat -c %s "$BUILD_DIR/asset_$f.o") B"
done

printf '  expr %-22s ' "lumina_expressions (50 张 crop96)"
# shellcheck disable=SC2086
gcc $ASSET_CFLAGS $ASSET_INC -c "$GEN_DIR/lumina_expressions.c" \
    -o "$BUILD_DIR/asset_lumina_expressions.o"
echo "-> $(stat -c %s "$BUILD_DIR/asset_lumina_expressions.o") B"

ok "[assets] $(ls "$BUILD_DIR"/asset_*.o | wc -l | tr -d ' ') 个 .o"
