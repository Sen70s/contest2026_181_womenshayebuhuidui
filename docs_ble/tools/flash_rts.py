#!/usr/bin/env python3
"""烧录 SF32LB52 开发板（RTS 控制电源的板子专用流程）。

依据 contest2026_181_womenshayebuhuidui/docs/setup_guide.md §3.2 已验证流程：
后台启动 sftool 等待连接，主线程通过 RTS 断电 500ms 再上电，抢占芯片上电瞬间
的 ROM bootloader 窗口。成功标志: "Connected success!" + "Download stub success!"。

用法:
  python3 logs/flash_rts.py [镜像路径] [串口]      # 默认镜像为当前真机固件
"""
import os
import serial
import subprocess
import sys
import threading
import time

ROOT = "/home/aila/projects/vela_contest"
IMAGE = (sys.argv[1] if len(sys.argv) > 1 else
         os.path.join(ROOT, "out/openvela_contest2026_181_board_ai_agent/nuttx.bin"))
PORT = sys.argv[2] if len(sys.argv) > 2 else "/dev/ttyACM0"
BAUD = int(sys.argv[3]) if len(sys.argv) > 3 else 1000000
ADDR = "0x12010000"          # XIP 烧录地址

if not os.path.isfile(IMAGE):
    sys.exit("[flash] 镜像不存在: %s" % IMAGE)
if not os.path.exists(PORT):
    sys.exit("[flash] 串口不存在: %s（先拔插 USB 让设备重新枚举）" % PORT)

print("[flash] 镜像: %s (%d bytes)" % (IMAGE, os.path.getsize(IMAGE)))
print("[flash] 串口: %s @ %d" % (PORT, BAUD))


def flash_board():
    """后台执行 sftool，等待与芯片建立连接。"""
    time.sleep(0.5)  # 等 sftool 完成启动
    cmd = ["sftool", "-c", "SF32LB52", "-p", PORT, "-b", str(BAUD),
           "--before", "no_reset", "--connect-attempts", "20",
           "--after", "soft_reset",
           "write_flash", "%s@%s" % (IMAGE, ADDR)]
    print("[flash] sftool: %s" % " ".join(cmd), flush=True)
    try:
        r = subprocess.run(cmd, timeout=180)
    except subprocess.TimeoutExpired:
        print("[flash] sftool 超时（180s）", flush=True)
        return
    print("[flash] sftool returncode: %d" % r.returncode, flush=True)


flash_thread = threading.Thread(target=flash_board)
flash_thread.start()

# 关键时序：RTS 控制板子电源 —— 断电 500ms 再上电，让芯片落入 bootloader
ser = serial.Serial(PORT, BAUD, timeout=0.1)
ser.rts = True     # 断电
time.sleep(0.5)    # 保持 500ms
ser.rts = False    # 上电（芯片启动进入 ROM bootloader）
ser.close()

flash_thread.join(timeout=200)
if flash_thread.is_alive():
    print("[flash] 超时未完成；拔插 USB 物理断电重试（芯片需物理重启）")
    sys.exit(1)
print("[flash] 流程结束")
