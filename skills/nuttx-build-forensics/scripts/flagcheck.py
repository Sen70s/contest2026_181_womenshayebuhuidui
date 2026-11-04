#!/usr/bin/env python3
"""查"构建实际用了什么选项" —— 而不是"我以为用了什么选项"。

两个高频陷阱:
  1. **.config 改了但 build.ninja 没重建**。NuttX 里 .config **不是** ninja 的依赖,
     直接改 .config 或 defconfig 后跑 ninja, 构建系统不会重新生成规则 ——
     你新开的 CONFIG_ 一个规则都不会出现, 却毫无报错。
     正确做法是显式重跑: cmake -S <nuttx> -B <builddir>
  2. **第三方库自己设了编译选项, 绕过 NuttX 的统一 FLAGS**。
     典型是 vendor 的 CMakeLists 用 target_compile_options() 覆盖, 于是
     整个库以 GCC 默认的 -O0 编译 —— 功能正常但慢几倍, 而且没人会怀疑。

用法:
    flagcheck.py <builddir> --config-check
    flagcheck.py <builddir> <源文件路径片段>

例:
    flagcheck.py out/nuttx_contest_board_ai_agent --config-check
    flagcheck.py out/nuttx_contest_board_ai_agent external/opus
"""

import argparse
import os
import re
import sys
import time

OPT_RE = re.compile(r"-O([0-3sgz])\b")


def mtime(path):
    try:
        return os.path.getmtime(path)
    except OSError:
        return None


def config_check(builddir):
    cfg = os.path.join(builddir, ".config")
    nj = os.path.join(builddir, "build.ninja")

    tc, tn = mtime(cfg), mtime(nj)
    if tc is None or tn is None:
        print("  (缺少 .config 或 build.ninja, 跳过)")
        return 0

    print("  .config      %s" % time.strftime("%m-%d %H:%M:%S", time.localtime(tc)))
    print("  build.ninja  %s" % time.strftime("%m-%d %H:%M:%S", time.localtime(tn)))

    if tc > tn:
        print()
        print("[结论] STALE_CONFIG —— .config 比 build.ninja 新 %.0f 秒。" % (tc - tn))
        print("  NuttX 里 .config **不是** ninja 的依赖: 改了它直接 ninja,")
        print("  构建规则不会重新生成, 新开的 CONFIG_ 一个都不会生效且不报错。")
        print("[建议] 显式重跑 cmake, 然后确认新规则真的出现了:")
        print("  cmake -S <nuttx源树> -B %s" % builddir)
        print("  # 再验证: grep -c '<新库>' %s/build.ninja" % builddir)
        return 2

    print()
    print("[结论] OK —— build.ninja 不早于 .config, 配置应当是生效的。")
    return 0


def parse_edges(ninja_path):
    """把 build.ninja 切成 [(head, [var lines])]。"""
    edges = []
    head = None
    body = []
    with open(ninja_path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("build "):
                if head is not None:
                    edges.append((head, body))
                head, body = line.rstrip("\n"), []
            elif line.startswith((" ", "\t")) and head is not None:
                body.append(line.rstrip("\n"))
            else:
                if head is not None:
                    edges.append((head, body))
                head, body = None, []
    if head is not None:
        edges.append((head, body))
    return edges


def flag_check(builddir, needle):
    ninja = os.path.join(builddir, "build.ninja")
    if not os.path.exists(ninja):
        sys.exit("找不到 " + ninja)

    edges = parse_edges(ninja)
    hits = []
    for head, body in edges:
        if needle not in head:
            continue
        # 只看 C/C++ 源文件: 汇编(.S/.s)本来就不该有 -O, 统计进来纯属噪声,
        # 会把它误报成"没优化"。
        srcs = [t for t in head.split(":", 1)[-1].split()
                if t.endswith((".c", ".cc", ".cpp", ".cxx"))]
        if not srcs:
            continue
        flags = ""
        for b in body:
            if b.strip().startswith("FLAGS ="):
                flags = b.split("=", 1)[1].strip()
        hits.append((head, flags))

    if not hits:
        print("[结论] NO_RULE —— build.ninja 里没有包含 '%s' 的编译规则。" % needle)
        print("  说明它根本没被编进这个构建(或者你忘了重跑 cmake, 见 --config-check)。")
        return 1

    print("=== 匹配到 %d 条编译规则 ===" % len(hits))
    opt_counter = {}
    for head, flags in hits:
        src = head.split(":", 1)[0].replace("build ", "").strip()
        m = OPT_RE.search(flags)
        opt = ("-O" + m.group(1)) if m else "(无 -O 选项)"
        opt_counter[opt] = opt_counter.get(opt, 0) + 1
        if len(hits) <= 8:
            print("  %s" % src[:110])
            print("      优化级别: %s" % opt)

    print()
    print("=== 优化级别分布 ===")
    for opt, cnt in sorted(opt_counter.items(), key=lambda kv: -kv[1]):
        print("  %-14s %d 条" % (opt, cnt))

    total = sum(opt_counter.values())
    no_opt = opt_counter.get("(无 -O 选项)", 0)
    o0 = opt_counter.get("-O0", 0)

    # 判定口径: 显式 -O0 一定可疑; "没写 -O" 只有占比显著才算 ——
    # 个别规则本来就可能不写, 拿它告警就是误报。
    if o0 > 0 or no_opt * 5 > total:
        print()
        print("[结论] SUSPECT_O0 —— %d/%d 条 C/C++ 规则没有优化(-O0 或未指定)。"
              % (o0 + no_opt, total))
        print("  如果这是一个对性能敏感的库(编解码/加密/DSP), 它很可能被 vendor 的")
        print("  CMakeLists 用 target_compile_options() 覆盖了 NuttX 的统一 FLAGS。")
        print("[建议] 在库的 CMakeLists 里把优化级别补回去(例: list(APPEND CFLAGS -O2)),")
        print("  然后**对比改前改后的实测耗时**与固件体积 —— 真机上常见到几倍差距。")
        return 2

    print()
    print("[结论] OK —— 未发现 -O0 规则。")
    return 0


def main():
    ap = argparse.ArgumentParser(description="构建选项取证")
    ap.add_argument("builddir")
    ap.add_argument("source", nargs="?", help="源文件路径片段")
    ap.add_argument("--config-check", action="store_true")
    args = ap.parse_args()

    rc = 0
    if args.config_check or not args.source:
        print("=== 1) 配置是否已生效 (.config vs build.ninja) ===")
        rc = max(rc, config_check(args.builddir))
        print()

    if args.source:
        print("=== 2) '%s' 的实际编译选项 ===" % args.source)
        rc = max(rc, flag_check(args.builddir, args.source))

    return rc


if __name__ == "__main__":
    sys.exit(main())
