#!/usr/bin/env python3
"""PAN 长稳：保持一个串口会话，周期性打流并盯崩溃/断链/内存。

用法: python3 logs/pan_soak.py [分钟数] [日志路径]

每轮（默认 60s 间隔）:
  ping -c 5 223.5.5.5        公网可达
  ping -c 3 -s 1472 <gw>     大包 / ACL 分片
  free                       堆用量（查泄漏）
串口只 open 一次（RTS 接板子电源，反复 open 会把板子打掉电）。
"""
import re
import sys
import time

import serial

PORT = "/dev/ttyACM0"
BAUD = 1000000
MINUTES = float(sys.argv[1]) if len(sys.argv) > 1 else 60.0
LOG = sys.argv[2] if len(sys.argv) > 2 else "logs/pan_soak.log"
INTERVAL = 60.0
GW = "192.168.44.1"

HOT = ("Assert", "dump_assert", "up_dump_register", "panic", "HARD FAULT",
       "packet loss", "rtt ", "[pan] state=disc", "[pan] state=dhcp_ok",
       "[pan] state=connect", "Hardware error", "Unable to allocate",
       "tx encode failed", "tx send failed", "rx_tun_write_fail",
       "inet addr", "Umem")


def open_quiet():
    s = serial.Serial()
    s.port = PORT
    s.baudrate = BAUD
    s.timeout = 0.3
    s.rtscts = False
    s.dsrdtr = False
    s.rts = False
    s.dtr = False
    s.open()
    return s


def main():
    ser = open_quiet()
    fh = open(LOG, "w")
    t0 = time.time()
    tail = [""]
    stats = {"loss_lines": 0, "asserts": 0, "disconnects": 0, "rounds": 0}

    def pump(sec):
        end = time.time() + sec
        while time.time() < end:
            d = ser.read(8192)
            if not d:
                time.sleep(0.05)
                continue
            txt = tail[0] + re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "",
                                   d.replace(b"\x00", b"").decode("utf-8", "replace"))
            txt = txt.replace("\r", "\n")
            parts = txt.split("\n")
            tail[0] = parts.pop()
            for ln in parts:
                v = ln.strip()
                if not v:
                    continue
                fh.write("%8.1f %s\n" % (time.time() - t0, v))
                if any(k in v for k in HOT):
                    print("%8.1f | %s" % (time.time() - t0, v[:150]), flush=True)
                if "Assert" in v or "dump_assert" in v:
                    stats["asserts"] += 1
                if "[pan] state=disc" in v:
                    stats["disconnects"] += 1
                if "packet loss" in v and " 0% " not in v:
                    stats["loss_lines"] += 1
            fh.flush()

    ser.write(b"\n")
    pump(2)
    deadline = t0 + MINUTES * 60
    while time.time() < deadline:
        stats["rounds"] += 1
        print("=== round %d  t=%.0fs ===" % (stats["rounds"], time.time() - t0),
              flush=True)
        for cmd, wait in (("ping -c 5 223.5.5.5", 14),
                          ("ping -c 3 -s 1472 %s" % GW, 12),
                          ("free", 4)):
            fh.write("%8.1f >>> %s\n" % (time.time() - t0, cmd))
            ser.write(cmd.encode() + b"\n")
            pump(wait)
        idle = INTERVAL - 30
        if idle > 0:
            pump(idle)

    ser.close()
    fh.close()
    print("[soak] done %.1f min rounds=%d asserts=%d disconnects=%d lossy=%d -> %s"
          % ((time.time() - t0) / 60.0, stats["rounds"], stats["asserts"],
             stats["disconnects"], stats["loss_lines"], LOG), flush=True)


main()
