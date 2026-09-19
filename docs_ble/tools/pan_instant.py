#!/usr/bin/env python3
"""PAN instant: 监听到 BNEP 成功瞬间，立即切 nsh 跑网络命令。
手机网络共享一直开着时使用：板子配对/连接后手机自动拨 BNEP。
"""
import serial, sys, time, os

PORT = "/dev/ttyACM0"
PHONE = "a4:cc:b3:fe:d1:a4"
TS = time.strftime('%H%M%S')
LOG = open(f"/home/aila/projects/vela_contest/logs/paninst_{TS}.log", "wb")

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

def nsh_cmd(ser, c, wait=5):
    """在 nsh 下执行命令"""
    ser.write((c + "\n").encode())
    end = time.time() + wait; buf = b""
    while time.time() < end:
        d = ser.read(4096)
        if d:
            buf += d; out(d.decode(errors="replace"))
    return buf.decode(errors="replace")

ser = serial.Serial(PORT, 1000000, timeout=0.3)
ser.reset_input_buffer()
send(ser, "\n"); pump(ser, 5, (b"nsh>", b"bttool>"))
# 退出已有 bttool 会话（如果有）
send(ser, "q"); pump(ser, 3, (b"nsh>",))
send(ser, "rm -rf /data/misc/bt"); pump(ser, 3)
send(ser, "bluetoothd &"); pump(ser, 8)
send(ser, "bttool"); pump(ser, 8, (b"bttool>",))
send(ser, "enable"); pump(ser, 30, (b"Adapter Name:",))

out("\n[LISTEN] 等 BNEP 成功后瞬间切网络，最长240s\n")

# 阶段1: 监听240s，一见 BNEP SUCCESS 立即跳出
end = time.time() + 240
bnep_ok = False
while time.time() < end:
    d = ser.read(4096)
    if d:
        txt = d.decode(errors="replace")
        out(txt)
        if "BNEP setup SUCCESS" in txt or "bt-pan, state:1" in txt:
            bnep_ok = True
            out("\n[INSTANT] BNEP 成功！立即切 nsh\n")
            break

if not bnep_ok:
    out("\n[TIMEOUT] 240s 无 BNEP\n")
    LOG.close(); ser.close(); sys.exit(1)

# 阶段2: 立即退 bttool → nsh
send(ser, "q")
time.sleep(0.5)
end = time.time() + 3; buf = b""
while time.time() < end:
    d = ser.read(4096)
    if d: buf += d; out(d.decode(errors="replace"))
out(f"\n[DEBUG] post-q: {buf[-100:]}\n")

# 如果 q 没成功，强制 Ctrl-C 模拟
if b"nsh>" not in buf:
    ser.write(b"\x03"); time.sleep(0.5)
    ser.write(b"\n"); time.sleep(0.5)
    end = time.time() + 3; buf = b""
    while time.time() < end:
        d = ser.read(4096)
        if d: buf += d; out(d.decode(errors="replace"))
    out(f"\n[DEBUG] post-ctrlc: {buf[-100:]}\n")

# 阶段3: nsh 网络命令
out("\n[NET] 执行网络命令\n")
for cmd_str, wait_sec in [
    ("ifconfig", 4),
    ("ifconfig bt-pan dhcp", 35),
    ("ifconfig", 4),
    ("ping -c 3 8.8.8.8", 20),
]:
    out(f"\n>>> {cmd_str}\n")
    nsh_cmd(ser, cmd_str, wait_sec)

out(f"\n[DONE {TS}]\n")
LOG.close(); ser.close()
