#!/usr/bin/env python3
"""仿 xiaozhi 主动 PAN 流程（参考 bt_interface_conn_ext）:
enable → createbond → 等 BONDED+encrypt → pan connect → BNEP → DHCP → ping
"""
import serial, sys, time

PORT = "/dev/ttyACM0"
PHONE = "a4:cc:b3:fe:d1:a4"
TS = time.strftime('%H%M%S')
LOG = open(f"/home/aila/projects/vela_contest/logs/xiaozhi_{TS}.log", "wb")

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

def cmd(ser, c, wait=5):
    out(f"\n>>> {c}\n"); ser.write((c + "\n").encode())
    time.sleep(0.2)
    return pump(ser, wait)

ser = serial.Serial(PORT, 1000000, timeout=0.3)
ser.reset_input_buffer()

# 1) 清理 + 启动服务
cmd(ser, "\n", 6)
cmd(ser, "q", 3, (b"nsh>",))
cmd(ser, "rm -rf /data/misc/bt", 3)
cmd(ser, "bluetoothd &", 8)
cmd(ser, "bttool", 8, (b"bttool>",))

# 2) Enable
cmd(ser, "enable", 30, (b"Adapter Name:",))
cmd(ser, "state", 3)

# 3) 主动配对（xiaozhi 模式：bt_cm_set_profile_target + auto_connect）
out("\n=== 主动配对（仿 xiaozhi createbond 流程）===\n")
cmd(ser, f"createbond {PHONE} 1", 90)

# 4) 检查 BOND 状态
cmd(ser, f"device {PHONE}", 3)

# 5) 仿 xiaozhi: 配对成功后立即 pan connect
#    xiaozhi bt_interface_conn_ext(addr, BT_PROFILE_PAN) = pan connect <addr> 1 2
out("\n=== 主动 PAN 连接（仿 xiaozhi bt_interface_conn_ext）===\n")
cmd(ser, f"pan connect {PHONE} 1 2", 90)
cmd(ser, "pan dump", 4)

# 6) 监听 BNEP + DHCP
out("\n=== 等待 BNEP 建立 ===\n")
end = time.time() + 60
bnep_ok = False
while time.time() < end:
    d = ser.read(4096)
    if d:
        txt = d.decode(errors="replace")
        out(txt)
        if "bt-pan, state:1" in txt or "BNEP setup SUCCESS" in txt:
            bnep_ok = True
            out("\n[BNEP OK] 切 nsh\n")
            break

# 7) 退到 nsh 跑网络
cmd(ser, "q", 4, (b"nsh>",))
cmd(ser, "ifconfig", 4)
cmd(ser, "ifconfig bt-pan dhcp", 35)
cmd(ser, "ifconfig", 4)
cmd(ser, "ping -c 3 8.8.8.8", 20)

out(f"\n[XIAOZHI_STYLE DONE {TS}]\n")
LOG.close(); ser.close()
