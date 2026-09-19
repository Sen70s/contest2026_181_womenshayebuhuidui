#!/usr/bin/env python3
"""擦除 /data 的 littlefs 分区（NOR 0x129A0000, 4 MiB）。

corrupt 的 littlefs 会让 nx_mount() 在 AppBringUp 里 HardFault，板子起不来。
擦掉这块分区后 sifli_ap.c 的 forceformat 分支会重新格式化。
镜像本体在 0x12010000..0x125FA000，与该区间无重叠。
"""
import subprocess, threading, time, serial

PORT = "/dev/ttyACM0"
BAUD = 1000000
REGION = "0x129A0000:0x400000"

def run():
    time.sleep(0.5)
    cmd = ["sftool", "-c", "SF32LB52", "-p", PORT, "-b", str(BAUD),
           "--before", "no_reset", "--connect-attempts", "20",
           "--after", "soft_reset", "erase_region", REGION]
    print("[erase] %s" % " ".join(cmd), flush=True)
    r = subprocess.run(cmd, timeout=300)
    print("[erase] returncode: %d" % r.returncode, flush=True)

th = threading.Thread(target=run)
th.start()
s = serial.Serial(PORT, BAUD, timeout=0.1)
s.rts = True
time.sleep(0.5)
s.rts = False
s.close()
th.join(timeout=320)
