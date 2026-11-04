#!/usr/bin/env python3
"""扫描我们自己固件的 UI 文案，逐码位问真机字体「有没有这个字形」。

这是给「改文案 / 加页面」做回归用的：只要有人往 UI 里写了字体没烘焙的字，
真机上就是空白/豆腐块，而编译不会报错。本脚本把它变成一条命令 + 非 0 退出码，
以后可以直接接 CI。

扫描范围（只读，不改任何固件源码）：
  * <workspace>/packages/ai_agent/src/ui/**/*.c   里的字符串字面量（跳过注释）
  * <workspace>/packages/ai_agent/src/ui/assets/text.ini、theme.ini 的「= 右边」值

字体来源：直接解析真机字体 .c 文件里的 cmap 表（不编译、不链接 LVGL），
并沿 LVGL 的 .fallback 链合并 —— 因为 LVGL 9.1 的 lv_font_get_glyph_dsc() 会
逐级 fallback，掉到后备字体上的字**不会**显示成方块，但字号会不一致。

还有一个容易误报的点：源码里的中文字面量并不都会上屏。比如
`strstr(text, "❤")` 只是拿表情符号做匹配，屏幕上永远不画这个字，缺字形完全无害。
所以本脚本会判断字面量是否出现在「纯字符串比较/查找」调用（strstr/strcmp/…）的
实参位置，把这类标记为 NONRENDER 单独统计，不计入退出码。--include-nonrender
可以把它们也算进失败条件（对自己狠一点时用）。

三类结论：
  1) TOFU    ：cjk_24 及其 fallback 链（cjk_18）都没有，也没有任何备用字体
              -> 真机上是空白/方块                      ← 退出码非 0
  2) DEMOTED ：cjk_24 自身没烘焙、靠 fallback 到 cjk_18 才拿到字形
              -> 能显示，但本该 24px 的设计字变成 18px（版式会偏小/偏挤）
  3) OK      ：cjk_24 原生就有（真机 font_body 正常）

退出码：0 = 没有 TOFU；1 = 有 TOFU（可直接给 CI 用）。

用法：
    python3 scripts/check_our_fonts.py                  # 默认扫我们的固件 UI
    python3 scripts/check_our_fonts.py --ui-dir <dir>   # 换目录
    python3 scripts/check_our_fonts.py --quiet          # 只输出结论与计数
"""
import argparse
import os
import re
import sys

TOOL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO_ROOT = os.path.dirname(os.path.dirname(TOOL_DIR))
VENDOR_ROOT = os.path.dirname(REPO_ROOT)
DEFAULT_UI_DIR = os.path.join(VENDOR_ROOT, "packages", "ai_agent", "src", "ui")
DEFAULT_FONT_DIR = os.path.join(DEFAULT_UI_DIR, "assets")

MAIN_FONTS = ["ui_font_cjk_24", "ui_font_cjk_18"]
ADVISORY_FONTS = ["ui_font_sans_88_bold"]

# ---------------------------------------------------------------- 字体 cmap 解析

_CMAP_TYPE = re.compile(r'type\s*=\s*(LV_FONT_FMT_TXT_CMAP_\w+)')


def _parse_font(path):
    """返回 (码位集合, fallback 字体名或 None)。只认 FORMAT0_TINY / SPARSE_TINY 两种。"""
    s = open(path, encoding="utf-8", errors="ignore").read()

    # unicode_list_N 数组（SPARSE 模式用）
    lists = {}
    for m in re.finditer(r'const\s+uint16_t\s+(\w+)\s*\[\s*\]\s*=\s*\{([^}]*)\}', s, re.S):
        lists[m.group(1)] = [int(x, 0)
                             for x in re.findall(r'0x[0-9a-fA-F]+|\b\d+\b', m.group(2))]

    # cmaps[] 块：找到声明后按花括号配对截取
    m = re.search(r'lv_font_fmt_txt_cmap_t\s+cmaps\s*\[\s*\]\s*=\s*\{', s)
    if not m:
        sys.exit("%s: 找不到 cmaps[]（不是 LVGL fmt_txt 字体？）" % path)
    i = m.end()
    depth = 1
    while depth and i < len(s):
        if s[i] == '{':
            depth += 1
        elif s[i] == '}':
            depth -= 1
        i += 1
    block = s[m.end():i - 1]

    cps = set()
    for e in re.findall(r'\{[^{}]*\}', block, re.S):
        rs = int(re.search(r'range_start\s*=\s*(\d+)', e).group(1))
        rl = int(re.search(r'range_length\s*=\s*(\d+)', e).group(1))
        ul = re.search(r'unicode_list\s*=\s*(\w+)', e).group(1)
        ty = _CMAP_TYPE.search(e).group(1)
        if ty.endswith("FORMAT0_TINY"):
            cps.update(range(rs, rs + rl))
        elif ty.endswith("SPARSE_TINY"):
            if ul not in lists:
                sys.exit("%s: cmap 引用了未定义的 %s" % (path, ul))
            cps.update(rs + v for v in lists[ul])
        else:
            sys.exit("%s: 未处理的 cmap 类型 %s（这个脚本只支持 tiny 变体）"
                     % (path, ty))

    # fallback：只在字体结构体里找，避免误抓 dsc 里的字段
    fb = None
    mf = re.search(r'const\s+lv_font_t\s+\w+\s*=\s*\{', s)
    if mf:
        j = mf.end()
        depth = 1
        while depth and j < len(s):
            if s[j] == '{':
                depth += 1
            elif s[j] == '}':
                depth -= 1
            j += 1
        mm = re.search(r'\.fallback\s*=\s*&(\w+)', s[mf.end():j])
        if mm:
            fb = mm.group(1)
    return cps, fb


class FontSet:
    def __init__(self, font_dir):
        self.dir = font_dir
        self.cache = {}

    def get(self, name):
        if name not in self.cache:
            path = os.path.join(self.dir, name + ".c")
            if not os.path.isfile(path):
                sys.exit("字体源码不存在: %s" % path)
            self.cache[name] = _parse_font(path)
        return self.cache[name]

    def chain(self, name):
        """沿 fallback 链返回 [(字体名, 码位集合)]，最多 8 层防死循环。"""
        out = []
        cur, depth = name, 0
        while cur and depth < 8:
            cps, fb = self.get(cur)
            out.append((cur, cps))
            cur, depth = fb, depth + 1
        return out

    def effective(self, name):
        eff = set()
        for _, cps in self.chain(name):
            eff |= cps
        return eff


# ---------------------------------------------------------- C 字符串字面量扫描

_ESC = {'n': 10, 't': 9, 'r': 13, '\\': 92, '"': 34, "'": 39,
        '0': 0, 'a': 7, 'b': 8, 'f': 12, 'v': 11, '?': 63}


def scan_c_literals(text):
    """产出 (字节串, 字面量起始偏移)。跳过 // 与 /* */ 注释。

    为什么要按“字节”处理：我们源码里有 "\\xe6\\x89\\x93..." 这种转义 UTF-8 写法
    （key_input.c 的 demo 问句就是这么写的）。先按 C 转义规则还原成字节，
    再整体 UTF-8 解码，才能拿到真正会上屏的字。
    """
    i, n = 0, len(text)
    out = []
    pend = bytearray()
    inlit = False
    lit_start = 0
    while i < n:
        c = text[i]
        if c == '/' and i + 1 < n and text[i + 1] == '/':
            j = text.find('\n', i)
            i = n if j < 0 else j + 1
        elif c == '/' and i + 1 < n and text[i + 1] == '*':
            j = text.find('*/', i + 2)
            i = n if j < 0 else j + 2
        elif c == '"':
            if not inlit:
                pend = bytearray()
                inlit = True
                lit_start = i
            i += 1
            while i < n and text[i] != '"':
                if text[i] == '\\' and i + 1 < n:
                    e = text[i + 1]
                    if e in _ESC:
                        pend.append(_ESC[e])
                        i += 2
                    elif e == 'x':
                        mm = re.match(r'[0-9a-fA-F]{1,2}', text[i + 2:i + 4])
                        if mm:
                            pend.append(int(mm.group(0), 16))
                            i += 2 + len(mm.group(0))
                        else:
                            pend.extend(e.encode())
                            i += 2
                    elif e in '01234567':
                        mm = re.match(r'[0-7]{1,3}', text[i + 1:i + 4])
                        pend.append(int(mm.group(0), 8) & 0xFF)
                        i += 1 + len(mm.group(0))
                    else:
                        pend.extend(e.encode())
                        i += 2
                else:
                    pend.extend(text[i].encode("utf-8"))
                    i += 1
            i += 1
        else:
            if inlit and c not in ' \t\r\n':
                out.append((bytes(pend), lit_start))
                inlit = False
            i += 1
    if inlit:
        out.append((bytes(pend), lit_start))
    return out


def decode_bytes(b):
    try:
        return b.decode("utf-8")
    except UnicodeDecodeError:
        return b.decode("latin-1")   # 非 UTF-8 的字节串：不当作中文，但仍可查 ASCII


def line_of(text, offset):
    return text.count("\n", 0, offset) + 1


# 这些函数的字符串实参只参与比较/查找，不会上屏，缺字形无害
NONRENDER_CALLS = {
    "strstr", "strcmp", "strncmp", "strcasecmp", "strncasecmp",
    "strchr", "strrchr", "strspn", "strcspn", "memcmp", "strtok",
    "atoi", "atof", "sscanf",
}


def find_enclosing_call(text, offset, window=400):
    """从字面量起点往回找最内层未闭合调用的函数名（找不到返回 None）。

    做法：反向扫描，')' 深度 +1，'(' 深度 -1；括号第一次回落到 0 时，
    检查该 '(' 之前的标识符。这样 `strstr(a, "x"` 里 "x" 的包围调用就是 strstr。

    遇到 ';' '{' '}' 就停（那是上一条语句/上一个块，说明这个字面量不在任何实参里，
    典型情形是数组初始化 `{"a", "b"}` 或 `x = "..."`）。
    """
    depth = 0
    i = offset - 1
    lo = max(0, offset - window)
    while i >= lo:
        c = text[i]
        if c in ';{}':
            return None
        if c == ')':
            depth += 1
        elif c == '(':
            if depth == 0:
                j = i - 1
                while j >= 0 and text[j] in ' \t\r\n':
                    j -= 1
                k = j
                while k >= 0 and (text[k].isalnum() or text[k] == '_'):
                    k -= 1
                name = text[k + 1:j + 1]
                return name or None
            depth -= 1
        i -= 1
    return None


# ------------------------------------------------------------------ 文案收集

def collect_from_c(path):
    """返回 {字符: [(位置, 片段, 是否仅用于比较/查找), ...]}"""
    text = open(path, encoding="utf-8", errors="ignore").read()
    found = {}
    for raw, off in scan_c_literals(text):
        s = decode_bytes(raw)
        line = line_of(text, off)
        snippet = s.strip()[:48].replace("\n", " ")
        call = find_enclosing_call(text, off)
        nonrender = call in NONRENDER_CALLS
        for ch in s:
            cp = ord(ch)
            if cp < 0x20:
                continue
            found.setdefault(ch, []).append(
                ("%s:%d" % (os.path.basename(path), line), snippet,
                 nonrender, call or "", os.path.basename(path)))
    return found


def collect_from_ini(path):
    found = {}
    if not os.path.isfile(path):
        return found
    text = open(path, encoding="utf-8", errors="ignore").read()
    for lineno, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in line:
            continue
        val = line.split("=", 1)[1]
        # 值里的注释（# 之后）不算文案
        snippet = val.strip()[:48]
        for ch in val.split("#", 1)[0]:
            if ord(ch) < 0x20:
                continue
            found.setdefault(ch, []).append(
                ("%s:%d" % (os.path.basename(path), lineno), snippet,
                 False, "ini", os.path.basename(path)))
    return found


def merge(dst, src):
    for ch, locs in src.items():
        dst.setdefault(ch, []).extend(locs)


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--ui-dir", default=os.environ.get("LUMINA_DEVICE_UI_DIR") or DEFAULT_UI_DIR,
                    help="固件 UI 源码目录（默认 <workspace>/packages/ai_agent/src/ui）")
    ap.add_argument("--font-dir", default=None,
                    help="字体 .c 所在目录（默认 <ui-dir>/assets）")
    ap.add_argument("--quiet", action="store_true", help="只输出结论与计数")
    ap.add_argument("--max-show", type=int, default=0,
                    help="每类最多列多少个字（默认 0 = 全部列出）")
    ap.add_argument("--include-nonrender", action="store_true",
                    help="把仅用于 strstr/strcmp 的字面量也算作失败（默认不算）")
    args = ap.parse_args()

    ui_dir = args.ui_dir
    font_dir = args.font_dir or os.path.join(ui_dir, "assets")
    if not os.path.isdir(ui_dir):
        sys.exit("UI 目录不存在: %s\n  换个位置跑时用 --ui-dir 或 LUMINA_DEVICE_UI_DIR 指定"
                 % ui_dir)

    # ---- 收集文案
    found = {}
    c_files = []
    for root, _dirs, files in os.walk(ui_dir):
        if os.path.basename(root) == "assets":
            continue                      # 字体/图标 C 文件里是设计注释，不是文案
        for f in sorted(files):
            if f.endswith(".c"):
                c_files.append(os.path.join(root, f))
    for p in c_files:
        merge(found, collect_from_c(p))
    for ini in ("text.ini", "theme.ini"):
        merge(found, collect_from_ini(os.path.join(font_dir, ini)))

    # ---- 解析字体（主字体 + 仅作参考的 88px 时间字体）
    try:
        fs = FontSet(font_dir)
        chains = {n: fs.chain(n) for n in MAIN_FONTS}
        native = {n: fs.get(n)[0] for n in MAIN_FONTS}
        eff = {n: fs.effective(n) for n in MAIN_FONTS}
    except SystemExit as e:
        print(str(e), file=sys.stderr)
        return 1

    print("== 固件 UI 文案缺字检查")
    print("   扫描: %s" % ui_dir)
    print("   来源: %d 个 .c + text.ini/theme.ini，去重字符 %d 个（非 ASCII %d 个）"
          % (len(c_files), len(found), sum(1 for c in found if ord(c) >= 0x80)))
    for n in MAIN_FONTS:
        c = chains[n]
        print("   字体: %-18s 字形 %5d 个, fallback 链: %s"
              % (n, len(native[n]), " -> ".join(x[0] for x in c)))

    # ---- 间接上屏：字面量本身不直接进 label，但被游标/返回/全局转交给了别的落点
    # 例：key_input.c 的 g_demo_questions[] 是数组初始化（find_enclosing_call 返回
    # None，会被判成 NONRENDER），但它们的下标被 `question = g_demo_questions[idx]`
    # 取走、再 snprintf 成 KEY_THINKING 后传给 pet_page_update_response()，
    # 最后用 UI_FONT_SMALL 显示 —— 这类字**确实会上屏**，不能判成无害。
    # 规则：找「数组名[...]」或「return 数组名」的出现；数组若被下标/返回转交过，
    # 则该数组名下的所有字面量都按「会上屏」处理。
    escaped_arrays = set()
    for p_c in c_files:
        t = open(p_c, encoding="utf-8", errors="ignore").read()
        for m in re.finditer(r'(\w+)\s*\[\s*[^\]]*\]', t):
            name = m.group(1)
            if name in ("char", "int", "uint8_t", "uint16_t", "uint32_t", "lv_obj_t",
                        "static", "const", "extern", "unsigned", "signed", "long",
                        "short", "bool", "void", "float", "double", "size_t"):
                continue
            # 排除声明本体（前面紧跟类型名）
            before = t[max(0, m.start() - 40):m.start()]
            if re.search(r'(const|static|extern|\bchar|\bint|\blv_\w*)\s+$', before):
                continue
            escaped_arrays.add(name)
        for m in re.finditer(r'return\s+(\w+)\s*;', t):
            escaped_arrays.add(m.group(1))

    def literal_is_indirect(loc):
        """数组初始化字面量并且数组被下标/return 转交过 -> 视为会上屏。"""
        pos, snippet, nonrender, call, basename = loc
        if nonrender:
            return False
        # 数组初始化：包围调用为 None 且片段就是数组元素本身
        if call:
            return False
        t = open(os.path.join(ui_dir, basename), encoding="utf-8", errors="ignore").read()
        return any(re.search(r'[^\w]' + re.escape(a) + r'\s*[\[]', t)
                   or re.search(r'return\s+' + re.escape(a) + r'\s*;', t)
                   for a in escaped_arrays)

    # ---- 分类
    # 以「真机 font_body = ui_font_cjk_24」为主视角：
    #   TOFU     不在 cjk_24 有效字形集合里（也不在 fallback 里）-> 真机空白/方块
    #   DEMOTED  不在 cjk_24 原生表里，靠 fallback 到 cjk_18 拿到 -> 字号降级
    body, small = MAIN_FONTS[0], MAIN_FONTS[1]
    tofu, demoted, nonrender_only = {}, {}, {}

    for ch, locs in found.items():
        cp = ord(ch)
        rendered = [l for l in locs if not l[2] or literal_is_indirect(l)]
        if cp not in eff[body]:
            if rendered:
                tofu[ch] = rendered
            else:
                nonrender_only[ch] = locs       # 只用于比较/查找，不上屏 -> 无害
        elif cp not in native[body] and rendered:
            demoted[ch] = rendered

    def show(title, table, note):
        print("\n-- %s：%d 个%s" % (title, len(table), note))
        for ch in sorted(table, key=ord):
            cp = ord(ch)
            where = table[ch]
            head = "   %s U+%04X   %d 处" % (ch, cp, len(where))
            if not args.quiet and args.max_show > 0:
                head += "  " + " ; ".join("%s '%s'" % (l[0], l[1]) for l in where[:2])
            print(head)
        if args.max_show > 0 and len(table) > args.max_show:
            print("   ... 另有 %d 个未列出（--max-show 0 关闭明细）"
                  % (len(table) - args.max_show))

    if nonrender_only and not args.quiet:
        print("\n-- NONRENDER（只出现在 strstr/strcmp 等比较实参里，不上屏，缺字形无害）："
              "%d 个" % len(nonrender_only))
        for ch in sorted(nonrender_only, key=ord):
            print("   %s U+%04X   %s" % (ch, ord(ch),
                  " ; ".join("%s '%s'" % (l[0], l[1]) for l in nonrender_only[ch][:2])))

    show("DEMOTED（%s 无原生字形、降级到 %s -> 能显示但字号偏小）" % (body, small),
         demoted, " 字")
    show("TOFU（%s 与 fallback 都没有 -> 真机上是空白/方块）" % body, tofu, " 字")

    print("\n== 结论")
    print("   TOFU      : %d  （真机会显示空白/方块）" % len(tofu))
    print("   DEMOTED   : %d  （24px 设计字被降级成 18px）" % len(demoted))
    print("   NONRENDER : %d  （仅比较用，缺字形无害）" % len(nonrender_only))

    fail_count = len(tofu)
    if args.include_nonrender:
        fail_count = len(found) - 0
        fail_chars = set(tofu) | set(nonrender_only)
        fail_count = len(fail_chars)
    if fail_count:
        print("   -> 退出码 1：请把上面的字加进字体烘焙字符集（packages/ai_agent/tools/"
              "gen_font.py 的 EXTRA_CHARS），或用现成字替代")
        return 1
    print("   -> 退出码 0：所有会上屏的文案都有字形可用")
    return 0


if __name__ == "__main__":
    sys.exit(main())
