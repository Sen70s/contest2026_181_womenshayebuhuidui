#!/usr/bin/env python3
"""PAN e2e 快速反应版：BNEP 连接后立即退到 nsh 跑 DHCP/ping"""
import serial, sys, time

PORT = "/dev/ttyACM0"
PHONE = "a4:cc:b3:fe:d1:a4"
LOG = open(f"/home/aila/projects/vela_contest/logs/panfast_{time.strftime('%H%M%S')}.log", "wb")

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
send(ser, "rm -rf /data/misc/bt"); pump(ser, 2)
send(ser, "bluetoothd &"); pump(ser, 8)
send(ser, "bttool"); pump(ser, 8, (b"bttool>",))
send(ser, "enable"); pump(ser, 30, (b"Adapter Name:",))

out("\n[WAIT] 等待手机连接（120s），BNEP 成功后立即切网络\n")

# 监听 120s，出现 BNEP SUCCESS 立即退到 nsh 跑网络
end = time.time() + 120
bnep_ok = False
while time.time() < end:
    d = ser.read(4096)
    if d:
        txt = d.decode(errors="replace")
        out(txt)
        if "BNEP setup SUCCESS" in txt or "state:1" in txt:
            bnep_ok = True
            out("\n[BNEP OK] 切到 nsh 跑网络...\n")
            break

if not bnep_ok:
    out("\n[TIMEOUT] BNEP 未建立，退出\n")
    LOG.close(); ser.close()
    sys.exit(1)

# 立即退出 bttool，进 nsh
send(ser, "q"); pump(ser, 4, (b"nsh>",))
send(ser, "ifconfig"); pump(ser, 4)
send(ser, "ifconfig bt-pan dhcp"); pump(ser, 30)
send(ser, "ifconfig"); pump(ser, 4)
send(ser, "ping -c 3 192.168.44.1"); pump(ser, 15)
send(ser, "ping -c 3 8.8.8.8"); pump(ser, 20)
send(ser, "nslookup baidu.com"); pump(ser, 15)

out("\n[FAST DONE]\n")
LOG.close(); ser.close()
