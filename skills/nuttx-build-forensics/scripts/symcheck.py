#!/usr/bin/env python3
"""扫描 ELF 里一组符号的**强弱**, 判断"真实现到底有没有被链接进去"。

为什么需要: 源码里明明有实现, 运行时却走的是桩 —— 这种故障**读代码永远看不出来**,
因为问题出在链接结果上。典型场景是同一个符号同时存在:
  · 某处的 __attribute__((weak)) 桩(常为"sim 构建用"而写),
  · 某个归档(.a)成员里的强实现。
静态链接器**先**满足于已拉进来的弱定义, 之后再也不会去归档里捞那个强实现,
于是固件里只有桩。表现为"功能 100% 失效", 但日志只说"打开失败", 毫无线索。

用法:
    symcheck.py <elf> <pattern> [--nm PATH] [--toolchain-prefix arm-none-eabi-]

例:
    symcheck.py out/nuttx/nuttx 'media_recorder_'
    symcheck.py out/nuttx/nuttx 'bt_' --nm /opt/gcc/bin/arm-none-eabi-nm
"""

import argparse
import os
import re
import shutil
import subprocess
import sys

# nm 的类型字母 -> (是否定义, 是否强符号)
DEFINED = set("TtDdBbRrGgSsVvWw")
STRONG_TEXT = set("Tt")
WEAK = set("WwVv")


def find_nm(explicit, prefix):
    if explicit:
        return explicit
    for cand in (prefix + "nm", "nm"):
        p = shutil.which(cand)
        if p:
            return p
    # 常见嵌入式工具链位置
    for root in ("/opt", os.path.expanduser("~")):
        for dirpath, _dirnames, filenames in os.walk(root):
            if dirpath.count(os.sep) > 6:
                continue
            if prefix + "nm" in filenames:
                return os.path.join(dirpath, prefix + "nm")
    return None


def collect(nm, elf, pattern):
    """返回 [(type, name, addr)]，按名字排序。"""
    out = subprocess.run([nm, "-C", elf], capture_output=True, text=True,
                         errors="replace")
    if out.returncode != 0:
        sys.exit("nm 失败: " + (out.stderr or "").strip())

    rx = re.compile(pattern)
    rows = []
    for line in out.stdout.splitlines():
        parts = line.split(None, 2)
        if len(parts) < 2:
            continue
        if len(parts) == 2:
            typ, name, addr = parts[0], parts[1], ""
            if not typ or typ[0] not in DEFINED and typ != "U":
                continue
        else:
            addr, typ, name = parts
        if typ == "U":
            addr = ""
        if rx.search(name):
            rows.append((typ, name, addr))
    rows.sort(key=lambda r: r[1])
    return rows


def diagnose(rows, pattern):
    """给出一句话结论 + 建议动作。"""
    if not rows:
        return ("NOT_FOUND",
                "固件里**完全没有**匹配 '%s' 的符号 —— 说明这段代码没被编译/链接进去。"
                % pattern,
                "检查它在 CMakeLists/Kconfig 里是否真的被加入构建; "
                "也可能是被 LTO/--gc-sections 丢弃。")

    types = {t for t, _n, _a in rows}
    strong = [r for r in rows if r[0] in STRONG_TEXT]
    weak = [r for r in rows if r[0] in WEAK]
    undef = [r for r in rows if r[0] == "U"]

    if strong and not weak:
        return ("OK", "都是强符号, 正常。", "")

    if weak and not strong:
        return ("ALL_WEAK",
                "**全部是弱符号(W/w), 没有任何强定义** —— 这就是'真实现被桩遮蔽'。"
                "弱定义先被拉进来后, 归档里的强实现再也不会被选中。",
                "1) 找到定义这些桩的源文件(通常注释写着 'stubs for sim build');\n"
                "   2) **把桩删掉**(只加真实现是不够的);\n"
                "   3) 重新构建后本脚本应显示强符号胜出。")

    if strong and weak:
        names_s = {n for _t, n, _a in strong}
        names_w = {n for _t, n, _a in weak}
        both = names_s & names_w
        if both:
            return ("MIXED_SAME_NAME",
                    "同一批里有强有弱(可能有重名): %s" % ", ".join(sorted(both)),
                    "确认最终 ELF 里生效的是强定义; 若弱桩仍被别处引用, 建议删除桩。")
        return ("MIXED", "强/弱都有(名字不同), 通常正常。", "")

    if undef:
        return ("UNDEFINED",
                "只有未定义引用(U) —— 实现根本没链接进来。",
                "把实现所在的库/目标加入链接; 检查 CMake 依赖是否写全。")

    return ("OTHER", "符号类型: %s" % "".join(sorted(types)), "")


def main():
    ap = argparse.ArgumentParser(description="ELF 符号强弱取证")
    ap.add_argument("elf")
    ap.add_argument("pattern", help="符号名正则(不需要写 ^, 内部用 search)")
    ap.add_argument("--nm")
    ap.add_argument("--toolchain-prefix", default="arm-none-eabi-")
    args = ap.parse_args()

    if not os.path.exists(args.elf):
        sys.exit("找不到 ELF: " + args.elf)
    nm = find_nm(args.nm, args.toolchain_prefix)
    if not nm:
        sys.exit("找不到 nm(可用 --nm 指定)")

    rows = collect(nm, args.elf, args.pattern)
    print("=== %s 中匹配 '%s' 的符号 (%d 个) ===" % (args.elf, args.pattern, len(rows)))
    for typ, name, addr in rows[:60]:
        kind = "弱" if typ in WEAK else ("未定义" if typ == "U" else "强")
        print("  %-3s %-6s %-10s %s" % (typ, kind, addr, name))
    if len(rows) > 60:
        print("  ... 还有 %d 个" % (len(rows) - 60))

    code, msg, action = diagnose(rows, args.pattern)
    print()
    print("[结论] %s" % code)
    print("  " + msg)
    if action:
        print("[建议]")
        for line in action.split("\n"):
            print("  " + line)

    return 1 if code in ("ALL_WEAK", "UNDEFINED", "NOT_FOUND") else 0


if __name__ == "__main__":
    sys.exit(main())
