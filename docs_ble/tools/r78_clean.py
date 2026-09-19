#!/usr/bin/env python3
"""R78 干净测试: 清存储 → 服务 → enable → 长监听 → 出站 pan connect"""
import serial, sys, time

PORT = "/dev/ttyACM0"
PHONE = "a4:cc:b3:fe:d1:a4"
LOG = open(f"/home/aila/projects/vela_contest/logs/r78_{time.strftime('%H%M%S')}.log", "wb")

def out(s):
    sys.stdout.write(s); sys.stdout.flush(); LOG.write(s.encode()); LOG.flush()

def pump(ser, sec, markers=()):
    buf = b""; end = time.time() + sec
    while time.time() < end:
        d = ser.read(4096)
        if d:
            buf += d; out(d.decode(errors="replace"))
            for m in markers:
                if m in buf: return buf
    return buf

def send(ser, t):
    out(f"\n>>> {t}\n"); ser.write((t + "\n").encode())

ser = serial.Serial(PORT, 1000000, timeout=0.3)
ser.reset_input_buffer()
send(ser, "\n"); pump(ser, 6, (b"nsh>",))

# 清存储防连锁崩溃
send(ser, "rm -rf /data/misc/bt"); pump(ser, 3)
send(ser, "bluetoothd &"); pump(ser, 8)
send(ser, "bttool"); pump(ser, 8, (b"bttool>",))
send(ser, "enable"); pump(ser, 35, (b"Adapter Name:",))

out("\n[READY] 板子已可发现 + PANU SDP 已注册。等待 150s...\n")
out("[INFO] 请在手机上点击板子设备连接。任何弹窗请告诉我。\n")

# 等 150s 让手机有时间连入
end = time.time() + 150
while time.time() < end:
    d = ser.read(4096)
    if d: out(d.decode(errors="replace"))

# 然后主动出站 pan connect
out("\n[STEP] 出站 pan connect...\n")
send(ser, f"pan connect {PHONE} 1 2")
pump(ser, 90, (b"CONNECTED",))
send(ser, "pan dump"); pump(ser, 4)
send(ser, f"device {PHONE}"); pump(ser, 3)

out("\n[R78 DONE]\n")
LOG.close(); ser.close()
