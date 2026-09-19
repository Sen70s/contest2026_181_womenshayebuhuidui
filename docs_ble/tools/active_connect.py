#!/usr/bin/env python3
"""仿 xiaozhi 主动 PAN 流程（参考 bt_interface_conn_ext）:
1. 清存储 → enable → createbond（直接用已知地址，不依赖 inquiry）→ 等 BONDED+encrypt → pan connect → BNEP → DHCP
"""
import serial, sys, time

PORT = "/dev/ttyACM0"
PHONE = "a4:cc:b3:fe:d1:a4"
TS = time.strftime('%H%M%S')
LOG = open(f"/home/aila/projects/vela_contest/logs/active_{TS}.log", "wb")

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
send(ser, "\n"); pump(ser, 6, (b"nsh>", b"bttool>"))
send(ser, "q"); pump(ser, 3, (b"nsh>",))
send(ser, "rm -rf /data/misc/bt"); pump(ser, 3)
send(ser, "bluetoothd &"); pump(ser, 8)
send(ser, "bttool"); pump(ser, 8, (b"bttool>",))
send(ser, "enable"); pump(ser, 30, (b"Adapter Name:",))

# 仿 xiaozhi: 直接用已知地址 createbond（不依赖 inquiry）
# xiaozhi: bt_cm_set_profile_target(HID, PHONE, 1) → SDK 直接连
out("\n=== 直接 createbond（仿 xiaozhi 地址已知模式）===\n")
send(ser, f"createbond {PHONE} 1")

# 监听 90s：等 BONDED + encrypt → 立即 pan connect
buf = b""
end = time.time() + 90
bonded = False
while time.time() < end:
    d = ser.read(4096)
    if d:
        txt = d.decode(errors="replace")
        buf += d; out(txt)
        if "BOND_NONE -> BONDED" in txt or "BondState: BONDED" in txt:
            bonded = True
            out("\n[BONDED] 配对成功！等待加密...\n")
            # 等 3s 确保加密完成（仿 xiaozhi 3s delay）
            time.sleep(3)
            break

if not bonded:
    out("\n[TIMEOUT] 90s 未配对成功\n")
    # 仍然尝试 pan connect（可能已有 key）
    pass

# 仿 xiaozhi: 配对后立即 pan connect（bt_interface_conn_ext）
out("\n=== pan connect（仿 xiaozhi bt_interface_ext）===\n")
send(ser, f"pan connect {PHONE} 1 2")

# 监听 60s 等 BNEP 成功
end = time.time() + 60
bnep_ok = False
while time.time() < end:
    d = ser.read(4096)
    if d:
        txt = d.decode(errors="replace")
        out(txt)
        if "bt-pan, state:1" in txt or "BNEP setup SUCCESS" in txt or "CONNECTED" in txt:
            bnep_ok = True
            out("\n[BNEP OK] bt-pan UP！切 nsh\n")
            break

if not bnep_ok:
    out("\n[TIMEOUT] BNEP 未建立\n")
    # 仍然跑网络命令检查 bt-pan 状态
    pass

# 退到 nsh 跑网络
send(ser, "q")
time.sleep(0.5)
end = time.time() + 3; buf = b""
while time.time() < end:
    d = ser.read(4096)
    if d: buf += d; out(d.decode(errors="replace"))
if b"nsh>" not in buf:
    ser.write(b"\x03"); time.sleep(0.5)
    ser.write(b"\n"); time.sleep(0.5)
    end = time.time() + 3; buf = b""
    while time.time() < end:
        d = ser.read(4096)
        if d: buf += d; out(d.decode(errors="replace"))

out("\n[NET] 网络命令\n")
for cmd_str, wait_sec in [
    ("ifconfig", 4),
    ("ifconfig bt-pan dhcp", 35),
    ("ifconfig", 4),
    ("ping -c 3 8.8.8.8", 20),
]:
    out(f"\n>>> {cmd_str}\n")
    ser.write((cmd_str + "\n").encode())
    end = time.time() + wait_sec; buf = b""
    while time.time() < end:
        d = ser.read(4096)
        if d: buf += d; out(d.decode(errors="replace"))

out(f"\n[ACTIVE_CONNECT DONE {TS}]\n")
LOG.close(); ser.close()
