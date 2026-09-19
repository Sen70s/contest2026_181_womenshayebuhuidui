#!/usr/bin/env python3
"""量化 bt-pan 链路的稳定性：统计在线/离线时长与断开间隔。

为什么要它：2026-09-19 夜排查"链路老是断"时，全靠人眼看串口日志，
得不出可比较的数字（"好像几十秒断一次"）。要做 A/B（网页版 vs 对照版、
手机放近 vs 放远、换手机）就必须有可比较的指标。

用法：
    python3 docs_ble/tools/link_watch.py            # 默认观察 300 秒
    python3 docs_ble/tools/link_watch.py 600        # 观察 10 分钟
    python3 docs_ble/tools/link_watch.py 300 out.md # 同时写一份 markdown 报告

判读：
  · 平均在线时长 < 60 s  → 链路不可用，先解决链路再谈 UI/网页
  · 断开间隔集中在某个固定值（如 60 s）→ 找定时器/租期/对端策略
  · 断开间隔随机且伴随 conn unreg → 更像 LCPU/RF 侧问题，查距离与干扰
"""
import re
import sys
import time

import serial

PORT = "/dev/ttyACM0"
BAUD = 1000000


def open_port():
    s = serial.Serial()
    s.port = PORT
    s.baudrate = BAUD
    s.timeout = 0.5
    s.rtscts = False
    s.dsrdtr = False
    # RTS 接在板子电源上：必须先置 False 再 open
    s.rts = False
    s.dtr = False
    s.open()
    return s


def main():
    dur = int(sys.argv[1]) if len(sys.argv) > 1 else 300
    out = sys.argv[2] if len(sys.argv) > 2 else None

    s = open_port()
    print("[link_watch] 观察 %d 秒（Ctrl-C 提前结束）" % dur)

    events = []          # (t, "up"/"down")
    state = None
    t0 = time.time()
    unreg = 0

    try:
        while time.time() - t0 < dur:
            d = s.read(8192)
            if not d:
                continue
            text = d.decode("utf-8", "replace")
            for line in text.splitlines():
                line = re.sub(r"\x1b\[[0-9;]*m", "", line).strip()
                if "pan] state=connected" in line and state != "up":
                    state = "up"
                    events.append((time.time() - t0, "up"))
                    print("  [%6.1fs] 链路 UP" % events[-1][0])
                elif "pan] state=disconnected" in line and state != "down":
                    state = "down"
                    events.append((time.time() - t0, "down"))
                    print("  [%6.1fs] 链路 DOWN" % events[-1][0])
                if "conn unreg" in line:
                    unreg += 1
    except KeyboardInterrupt:
        pass
    finally:
        s.close()

    span = time.time() - t0
    ups = [(events[i + 1][0] - events[i][0])
           for i in range(len(events) - 1) if events[i][1] == "up"]
    downs = [(events[i + 1][0] - events[i][0])
             for i in range(len(events) - 1) if events[i][1] == "down"]
    downs_at = [events[i][0] for i in range(len(events)) if events[i][1] == "down"]
    gaps = [downs_at[i + 1] - downs_at[i] for i in range(len(downs_at) - 1)]

    def stats(name, xs):
        if not xs:
            return "  %-14s 无样本" % name
        return "  %-14s n=%d 平均 %.1fs 最短 %.1fs 最长 %.1fs" % (
            name, len(xs), sum(xs) / len(xs), min(xs), max(xs))

    lines = [
        "=== 链路观察（%.0f 秒）===" % span,
        "  DOWN 次数      %d" % len(downs_at),
        "  conn unreg     %d" % unreg,
        stats("在线时长", ups),
        stats("离线时长", downs),
        stats("断开间隔", gaps),
    ]
    print("\n".join(lines))

    if out:
        with open(out, "w", encoding="utf-8") as f:
            f.write("# bt-pan 链路稳定性报告\n\n```\n")
            f.write("\n".join(lines))
            f.write("\n\n事件时间线\n")
            for t, what in events:
                f.write("  %7.1fs %s\n" % (t, what))
            f.write("```\n")
        print("报告已写入 %s" % out)


if __name__ == "__main__":
    main()
