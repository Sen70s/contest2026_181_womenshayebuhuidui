#!/usr/bin/env python3
"""Drive a PAN bring-up on the board and keep the raw serial log.

Everything this prints to stdout is a summary; the raw bytes always go to
--log so bnep_pcap.py can turn the ACL trace in them into a pcap afterwards
(the debug firmware's "sf32lb52 bth4 acl:" lines are the Gate B capture
source, and they are far too noisy to read on screen).

Phases:
  reset   wdog reset, capture the boot log
  stack   bluetoothd + bttool + enable, report adapter name/address/COD
  scan    show bonded devices and scan results so the phone can be identified
  pan     pan connect <addr>, then watch for the BNEP handshake and DHCP

Only the "pan" phase needs a human at the phone, so the phases are separate
subcommands: the rest can be verified before anyone touches a handset.

Usage:
  pan_bringup.py reset [--log F]
  pan_bringup.py stack [--log F]
  pan_bringup.py scan  [--log F] [--secs N]
  pan_bringup.py pan --addr AA:BB:.. [--log F] [--secs N]
  pan_bringup.py cmd  "nsh command" ... [--log F]
"""
import argparse
import re
import sys
import time

import serial

PORT = "/dev/ttyACM0"
BAUD = 1000000
DEFAULT_LOG = "/tmp/pan_bringup.log"


class Board:
    """Serial console with a tee to disk.

    Every read is appended to the log file immediately rather than buffered
    until exit: a bring-up that hangs the board is exactly the case where
    the last few lines matter most, and a buffer dies with the process.
    """

    def __init__(self, log_path, append=False):
        self.log = open(log_path, "ab" if append else "wb")
        self.ser = serial.Serial(PORT, BAUD, timeout=0.2)
        self.tail = b""

    def read(self, seconds, markers=()):
        """Read for up to `seconds`, returning early on any marker."""
        got = b""
        deadline = time.time() + seconds
        while time.time() < deadline:
            data = self.ser.read(4096)
            if not data:
                continue
            got += data
            self.log.write(data)
            self.log.flush()
            self.tail = (self.tail + data)[-65536:]
            if any(m in got for m in markers):
                break
        return got

    def send(self, line, echo=True):
        if echo:
            print(">>> %s" % line, flush=True)
        self.log.write(b"\n### SENT: " + line.encode() + b"\n")
        # bttool's getline() only strips \n; a stray \r becomes part of the
        # command and every command comes back "UnKnow command".
        self.ser.write((line + "\n").encode())

    def reset(self):
        """Reset the board with the wdog builtin.

        RTS does not gate power here and DTR does nothing either: all four
        line states and all twelve transitions between them were probed with
        holds up to 3 s and the board's uptime never moved. There is no reset
        pin and CONFIG_BOARDCTL_RESET is unset, so there is no reboot command.
        The watchdog example is the only software reset - it pings for ~5 s,
        stops feeding, and the chip comes back with "SFBL" at t=6.2 s.
        """
        self.send("wdog")
        return self.read(8, (b"SFBL",))

    def close(self):
        self.log.close()
        self.ser.close()


def show(tag, blob, keys, limit=200):
    """Print only the lines carrying one of `keys`, de-duplicated."""
    text = blob.decode("utf-8", errors="replace")
    seen = set()
    hits = 0
    for raw in re.split(r"[\r\n]+", text):
        line = raw.strip()
        if not line or not any(k in line for k in keys):
            continue
        if line in seen:
            continue
        seen.add(line)
        print("  %s| %s" % (tag, line[:limit]), flush=True)
        hits += 1
    return hits


def phase_reset(b):
    print("=== reset: wdog reset, 30s boot capture ===", flush=True)
    b.reset()
    out = b.read(25)
    print("  captured %d bytes during boot" % len(out), flush=True)
    show("boot", out, ["assert", "ASSERT", "Backtrace", "panic", "ERROR",
                       "bluetoothd", "ai_agent", "up_assert", "[pan]"])
    b.send("")
    if b"nsh>" not in b.read(4, (b"nsh>",)):
        print("  FAIL: no nsh> prompt after boot", flush=True)
        return 1
    print("  nsh> alive", flush=True)
    out = b.read(0.2)
    b.send("ps")
    out = b.read(4, (b"nsh>",))
    show("ps", out, ["bluetoothd", "ai_agent", "btsvc", "BT ", "nsh_main",
                     "pan"])
    return 0


def phase_stack(b):
    """bluetoothd + bttool + enable.

    rcS already tries to start bluetoothd, so this tolerates it being up
    and only reports what the adapter came back with.
    """
    print("=== stack: bluetoothd / bttool / enable ===", flush=True)
    b.send("")
    b.read(3, (b"nsh>",))

    b.send("ps")
    out = b.read(4, (b"nsh>",))
    if b"bluetoothd" not in out:
        print("  bluetoothd not running, starting it", flush=True)
        b.send("bluetoothd &")
        out = b.read(8)
        show("btd", out, ["btsvc", "ERROR", "assert", "fail"])
        time.sleep(2)
    else:
        print("  bluetoothd already running (rcS)", flush=True)

    b.send("bttool")
    out = b.read(10, (b"bttool>",))
    if b"create instance error" in out:
        print("  FAIL: bttool create instance error", flush=True)
        return 1
    if b"bttool>" not in out:
        print("  FAIL: no bttool> prompt", flush=True)
        show("btt", out, ["error", "ERROR", "fail"])
        return 1
    print("  bttool> ready", flush=True)

    b.send("enable")
    out = b.read(35, (b"Adapter Name:",))
    hits = show("en", out, ["Adapter Name", "Adapter Address", "Class",
                            "adapter state", "STATE_ON", "scan mode",
                            "ERROR", "assert"])
    if b"Adapter Name:" not in out:
        print("  FAIL: adapter did not report up within 35s", flush=True)
        return 1

    for cmd in ("getstate", "getaddr", "getname", "getcod", "getscanmode"):
        b.send(cmd)
        show(cmd[:6], b.read(3, (b"bttool>",)),
             ["state", "Address", "Name", "cod", "Cod", "COD", "class",
              "scan", "UnKnow"])
    return 0


def phase_scan(b, secs):
    print("=== scan: %ds discovery ===" % secs, flush=True)
    b.send("")
    b.read(2)
    b.send("startscan")
    out = b.read(secs, ())
    show("scan", out, ["device found", "Device found", "addr", "name",
                       "class", "rssi"])
    b.send("stopscan")
    b.read(3)
    b.send("getbondeddevices")
    show("bond", b.read(4), ["addr", "Addr", "bond", "device"])
    return 0


PAN_MARKERS = [
    "[pan] state=",          # panu_service.c lifecycle, raw syslog
    "pan_",                  # sal_pan_interface.c raw syslog
    "bnep",
    "BNEP",
    "connection state",
    "bt-pan",
    "assert", "ASSERT", "Backtrace", "up_assert",
]


def phase_pan(b, addr, secs):
    print("=== pan connect %s (watch %ds) ===" % (addr, secs), flush=True)
    b.send("")
    b.read(2)
    # local_role 1 = PANU, remote_role 2 = NAP: the board is the client and
    # the phone is the network access point handing out the uplink.
    b.send("pan connect %s 1 2" % addr)
    out = b.read(secs, (b"dhcp_ok",))
    show("pan", out, PAN_MARKERS)
    print("  --- interface state ---", flush=True)
    b.send("q")
    b.read(3)
    for cmd in ("ifconfig", "ifconfig bt-pan", "route"):
        b.send(cmd)
        show(cmd[:8], b.read(4, (b"nsh>",)),
             ["bt-pan", "inet", "HWaddr", "MTU", "Default", "0.0.0.0",
              "RUNNING", "UP"])
    return 0 if b"dhcp_ok" in out else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("phase",
                    choices=["reset", "stack", "scan", "pan", "cmd"])
    ap.add_argument("args", nargs="*")
    ap.add_argument("--log", default=DEFAULT_LOG)
    ap.add_argument("--addr", default=None)
    ap.add_argument("--secs", type=int, default=30)
    ap.add_argument("--append", action="store_true")
    a = ap.parse_args()

    b = Board(a.log, append=a.append)
    try:
        if a.phase == "reset":
            rc = phase_reset(b)
        elif a.phase == "stack":
            rc = phase_stack(b)
        elif a.phase == "scan":
            rc = phase_scan(b, a.secs)
        elif a.phase == "pan":
            if not a.addr:
                ap.error("pan phase needs --addr")
            rc = phase_pan(b, a.addr, a.secs)
        else:
            for c in a.args:
                b.send(c)
                out = b.read(4, (b"nsh>", b"bttool>"))
                sys.stdout.write(out.decode("utf-8", errors="replace"))
            rc = 0
    finally:
        b.close()
    print("\nraw log: %s" % a.log, flush=True)
    return rc


if __name__ == "__main__":
    sys.exit(main())


