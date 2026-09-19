#!/usr/bin/env python3
"""在 NSH 上跑命令，且**不在打开串口时抖动 RTS**。

这块板子的 RTS 接在电源控制上：pyserial 默认在 open() 时置位 RTS/DTR，
等于给板子来一次瞬时断电。先把 rts/dtr 设成 False 再 open()，才不会
在每次连接时把板子打掉。

用法: python3 logs/nsh2.py [-t 秒] "cmd1" "cmd2" ...
"""
import re
import sys
import time

import serial

PORT = "/dev/ttyACM0"
BAUD = 1000000

args = sys.argv[1:]
wait = 6.0
if args and args[0] == "-t":
    wait = float(args[1])
    args = args[2:]
CMDS = args or ["ifconfig"]


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
    ser.reset_input_buffer()
    buf = b""

    def pump(sec):
        nonlocal buf
        end = time.time() + sec
        while time.time() < end:
            d = ser.read(8192)
            if d:
                buf += d
            else:
                time.sleep(0.03)

    ser.write(b"\n")
    pump(2)
    for c in CMDS:
        buf += b"\n>>> " + c.encode() + b"\n"
        ser.write(c.encode() + b"\n")
        pump(wait)
    ser.close()

    txt = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "",
                 buf.replace(b"\x00", b"").decode("utf-8", "replace"))
    for ln in txt.replace("\r", "\n").split("\n"):
        if ln.strip():
            print(ln[:200])


main()
