#!/usr/bin/env python3
"""把真机崩溃日志里的地址批量符号化 —— 从"一堆 0x1216xxxx"到"哪一行"。

为什么需要: 嵌入式崩溃现场通常只有一串地址
    sched_dumpstack: [42] [<0x1216bb92>] Unknown+0x1216bb92/0xffffffff
    up_dump_register: PC: 1201b29b LR: 1201b29b
外加一个任务表(用来判断崩在哪个线程、那个线程的栈多大)。
**不看符号化结果就猜根因, 几乎一定会猜错** —— 本次实战中它两次直接改变了结论:
一次指向 silk_Encode(栈溢出), 一次指向 bt_hci_cmd_send_sync(控制器不回包)。

用法:
    symbolize.py <elf> <日志文件或地址...>

例:
    symbolize.py out/nuttx/nuttx crash.log
    symbolize.py out/nuttx/nuttx 0x1216bb92 0x1216b1ea
    cat crash.log | symbolize.py out/nuttx/nuttx -
"""

import argparse
import os
import re
import shutil
import subprocess
import sys

ADDR_PATTERNS = [
    re.compile(r"\[<0x([0-9a-fA-F]{6,8})>\]"),      # sched_dumpstack
    re.compile(r"\bPC:\s*([0-9a-fA-F]{6,8})\b"),     # up_dump_register
    re.compile(r"\bLR:\s*([0-9a-fA-F]{6,8})\b"),
    re.compile(r"\b0x([0-9a-fA-F]{6,8})\b"),          # 裸地址
]

TASK_RE = re.compile(
    r"dump_task:\s*(?P<pid>\d+)\s+\d+\s+(?P<prio>\d+)\s+\S+\s+\S+\s+\S+\s+"
    r"(?P<state>[A-Za-z ]+?)\s+\S+\s+\S+\s+(?P<stack>\d+)\s+(?P<name>\S+)")


def find_tool(explicit, prefix, tool):
    if explicit:
        return explicit
    for cand in (prefix + tool, tool):
        p = shutil.which(cand)
        if p:
            return p
    return None


def extract(text):
    """返回 (有序去重的地址列表, 任务表行)。"""
    seen = []
    for pat in ADDR_PATTERNS:
        for m in pat.finditer(text):
            a = int(m.group(1), 16)
            if a not in seen:
                seen.append(a)
    tasks = [m.group(0).strip() for m in TASK_RE.finditer(text)]
    return seen, tasks


def main():
    ap = argparse.ArgumentParser(description="崩溃地址符号化")
    ap.add_argument("elf")
    ap.add_argument("sources", nargs="+", help="日志文件、'-' 表示 stdin、或裸地址")
    ap.add_argument("--addr2line")
    ap.add_argument("--toolchain-prefix", default="arm-none-eabi-")
    args = ap.parse_args()

    if not os.path.exists(args.elf):
        sys.exit("找不到 ELF: " + args.elf)

    a2l = find_tool(args.addr2line, args.toolchain_prefix, "addr2line")
    if not a2l:
        sys.exit("找不到 addr2line(可用 --addr2line 指定)")

    chunks = []
    for src in args.sources:
        if src == "-":
            chunks.append(sys.stdin.read())
        elif os.path.exists(src):
            chunks.append(open(src, encoding="utf-8", errors="replace").read())
        else:
            chunks.append(src)
    text = "\n".join(chunks)

    addrs, tasks = extract(text)
    if not addrs:
        sys.exit("没从输入里认出任何地址。")

    # ★ 极重要: 符号化结果只对**同一次构建**的 ELF 有效。代码一改一重建,
    #   地址就全部错位 —— 此时 addr2line 既不报错也不警告, 只会安安静静地
    #   给出**完全错误**的函数名, 比不给答案更危险。
    #   (本技能作者实测: 同一个 0x1216bb92, 重建前是 silk_find_pitch_lags_FIX,
    #    重建后变成 silk_DIV32_varQ —— 两次都"看着很像真的"。)
    import time as _t
    print("=== ELF 构建时间: %s ==="
          % _t.strftime("%Y-%m-%d %H:%M:%S", _t.localtime(os.path.getmtime(args.elf))))
    print("  ⚠ 先确认这份 ELF 与产生日志的固件是**同一次构建**; 若之后重建过,")
    print("    下面所有函数名都会错位, 而且不会报错。习惯: 每次烧录的固件与 ELF 一起留档。")
    print()
    print("=== 提取到 %d 个地址, 符号化中 ===" % len(addrs))
    fmt = a2l.replace("addr2line", "addr2line")
    for a in sorted(addrs):
        out = subprocess.run([fmt, "-f", "-C", "-e", args.elf, "0x%08x" % a],
                             capture_output=True, text=True, errors="replace")
        lines = [l for l in out.stdout.strip().splitlines() if l.strip()]
        func = lines[0] if lines else "?"
        loc = lines[1] if len(lines) > 1 else "?"
        print("  0x%08x  %-44s %s" % (a, func[:44], loc))

    if tasks:
        print()
        print("=== 任务表(注意最后一列是**栈大小**) ===")
        for t in tasks[:20]:
            print("  " + t)
        print("  ↑ 崩在哪个任务上, 就先看那个任务的栈够不够 —— "
              "libopus / TLS / 递归解析这类代码的栈开销远大于直觉。")

    return 0


if __name__ == "__main__":
    sys.exit(main())
