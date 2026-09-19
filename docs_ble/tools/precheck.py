#!/usr/bin/env python3
"""演示前一键自检：跑完直接告诉你「可以开拍」还是「需要先做什么」。

检查项：
  1 控制台/NSH 是否响应
  2 bt-pan 是否拿到 IP
  3 公网 ping（手机热点上行是否通）
  4 DNS 域名解析
  5 云端 LLM 是否已配置（router_status 的 backend_count）
  6 关怀调度是否存活（hr_set 能读到心率值）
  7 自定义 Skill 是否在设备上

若第 5 项为 0，脚本会**尝试自动补配置**：key 从环境变量 $STEPFUN_KEY
或 ~/.stepfun_key 读取（为安全起见不写进仓库）。

用法:
  python3 docs_ble/tools/precheck.py                # 只自检
  STEPFUN_KEY=xxx python3 docs_ble/tools/precheck.py # 缺配置时自动补
"""
import os
import re
import sys
import time

import serial

PORT = os.environ.get("BOARD_PORT", "/dev/ttyACM0")
BAUD = 1000000
LLM_URL = "https://api.stepfun.com/v1/chat/completions"
LLM_MODEL = "step-3.7-flash"
KEY_FILE = os.path.expanduser("~/.stepfun_key")


def open_port():
    s = serial.Serial()
    s.port = PORT
    s.baudrate = BAUD
    s.timeout = 0.5
    s.rtscts = False
    s.dsrdtr = False
    # RTS 接在板子电源上：必须先置 False 再 open，否则等于给板子断电
    s.rts = False
    s.dtr = False
    s.open()
    return s


def drain(s, sec):
    b = b""
    end = time.time() + sec
    while time.time() < end:
        d = s.read(8192)
        if d:
            b += d
    return b.decode("utf-8", "replace")


def nsh(s, cmd, wait=5):
    s.reset_input_buffer()
    s.write(cmd.encode("utf-8") + b"\n")
    return drain(s, wait)


def cli(s, cmd, wait=12):
    """vela> CLI 与 NSH 抢同一个 console，NSH 优先级更高总赢。
    先发一条耗时命令占住 NSH，此时只有 CLI 线程在读输入。"""
    s.reset_input_buffer()
    s.write(b"ping -c 10 223.5.5.5\n")
    drain(s, 1.5)
    s.write(cmd.encode("utf-8") + b"\n")
    return drain(s, wait)


def read_key():
    k = os.environ.get("STEPFUN_KEY", "").strip()
    if k:
        return k
    if os.path.isfile(KEY_FILE):
        return open(KEY_FILE).read().strip()
    return ""


def main():
    results = []
    fixes = []

    try:
        s = open_port()
    except Exception as e:
        print(f"❌ 打不开串口 {PORT}: {e}")
        print("   → 检查 USB 连接；若刚拔插过，稍等 2 秒再试")
        return 1

    # 1 控制台
    r = nsh(s, "echo PRECHECK", 4)
    ok = "PRECHECK" in r
    results.append(("1 控制台/NSH 响应", ok, ""))
    if not ok:
        print("❌ 控制台无响应")
        print("   → 按一次板上 RESET；仍无响应就拔插 USB（这是已知的偶发问题）")
        s.close()
        return 1

    # 2 网络
    r = nsh(s, "ifconfig", 5)
    m = re.search(r"inet addr:(192\.168\.\d+\.\d+)", r)
    results.append(("2 bt-pan 拿到 IP", bool(m), m.group(1) if m else "未连接"))
    if not m:
        fixes.append("手机打开「蓝牙网络共享」并保持亮屏，然后冷启动板子（别用 RESET）")

    # 3/4 公网与 DNS
    r = nsh(s, "ping -c 3 223.5.5.5", 12)
    pub = "0% packet loss" in r
    results.append(("3 公网 ping", pub, "0% 丢包" if pub else "不通"))
    if not pub and m:
        fixes.append("有 IP 但公网不通 → 手机热点**上行**没通（换网络/重开共享），不是板子问题")
    r = nsh(s, "ping -c 2 www.baidu.com", 12)
    dns = "0% packet loss" in r
    results.append(("4 DNS 解析", dns, "0% 丢包" if dns else "不通"))

    # 5 云端配置
    r = cli(s, "router_status")
    m = re.search(r'"backend_count":\s*(\d+)', r)
    cnt = int(m.group(1)) if m else -1
    results.append(("5 云端 LLM 已配置", cnt > 0, f"backend_count={cnt}" if cnt >= 0 else "未读到"))
    if cnt == 0:
        key = read_key()
        if key:
            print("… 检测到云端配置缺失，尝试自动补配置")
            r = cli(s, f"set_llm {LLM_URL} {LLM_MODEL} {key}", 14)
            ok2 = "LLM backend" in r or "API key saved" in r
            results.append(("5b 自动补配置", ok2, "已写入" if ok2 else "失败"))
            if not ok2:
                fixes.append("自动补配置失败 → 手动执行：先 `ping -c 10 223.5.5.5` 占住 NSH，再发 set_llm <url> <model> <key>")
        else:
            fixes.append("云端配置缺失且无 key → 设 $STEPFUN_KEY 或写入 ~/.stepfun_key 后重跑本脚本")

    # 6 关怀调度
    r = nsh(s, "hr_set", 5)
    care = "current" in r and "bpm" in r
    results.append(("6 关怀调度存活", care, re.search(r"current (\d+) bpm", r).group(0) if care else "无响应"))

    # 7 Skill
    r = nsh(s, "ls /data/ai_agent/skills", 5)
    skill = "care-reminder.md" in r
    results.append(("7 自定义 Skill 已安装", skill, "care-reminder.md" if skill else "缺失"))
    if not skill:
        fixes.append("Skill 缺失 → 删掉 /data/ai_agent/skills/ 下对应文件后重启，固件会自动重装")

    s.close()

    print("\n=== 演示前自检 ===")
    for name, ok, note in results:
        print(f"  {'✅' if ok else '❌'} {name:24s} {note}")

    blocker = [r for r in results if not r[1]]
    print()
    if not blocker:
        print("🎬 可以开拍：网络、云端、关怀、Skill 全部就绪")
        print("   提醒：测「空闲关怀」要静置 5 分钟且期间不碰屏幕/按键；建议冷启动后再测。")
        return 0
    print("⚠️ 还需处理：")
    for f in fixes or ["见上面标 ❌ 的项"]:
        print(f"   · {f}")
    return 2


if __name__ == "__main__":
    sys.exit(main())
