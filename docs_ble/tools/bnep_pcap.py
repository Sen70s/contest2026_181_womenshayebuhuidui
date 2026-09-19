#!/usr/bin/env python3
"""Turn sf32lb52 bth4 ACL traces into a pcap that Wireshark can dissect.

Reads a serial log on stdin (or a file argument), reassembles the chunked
'sf32lb52 bth4 acl:' lines into whole H4 packets, and writes a pcap with
linktype 201 (LINKTYPE_BLUETOOTH_HCI_H4_WITH_PHDR): each record is a
4-byte big-endian direction word (0 = sent, 1 = received) followed by the
raw H4 packet.

Wireshark needs the L2CAP Connection Request/Response on CID 0x0001 to
bind the BNEP dissector to the data channel, so capture from before the
PAN connection starts.
"""
import io
import re
import struct
import sys

LINE = re.compile(
    r"sf32lb52 bth4 acl: dir=(tx|rx) seq=(\d+) off=(\d+) total=(\d+) ([0-9a-fA-F ]+)"
)


def reassemble(lines):
    """Yield (direction, packet_bytes) in log order."""
    pending = {}   # (dir, seq) -> bytearray
    order = []     # (dir, seq) first-seen order
    totals = {}

    for line in lines:
        m = LINE.search(line)
        if not m:
            continue
        d, seq, off, total, hexs = m.groups()
        key = (d, int(seq))
        off, total = int(off), int(total)
        data = bytes.fromhex(hexs.replace(" ", ""))

        if key not in pending:
            pending[key] = bytearray(total)
            totals[key] = total
            order.append(key)
        buf = pending[key]
        if off + len(data) <= len(buf):
            buf[off:off + len(data)] = data

    for key in order:
        d, _ = key
        yield (0 if d == "tx" else 1), bytes(pending[key])


def write_pcap(path, packets):
    with open(path, "wb") as f:
        # magic, ver 2.4, tz 0, sigfigs 0, snaplen, linktype 201
        f.write(struct.pack("<IHHiIII",
                            0xa1b2c3d4, 2, 4, 0, 0, 262144, 201))
        for i, (direction, pkt) in enumerate(packets):
            payload = struct.pack(">I", direction) + pkt
            f.write(struct.pack("<IIII", i, 0, len(payload), len(payload)))
            f.write(payload)


def main():
    out = sys.argv[2] if len(sys.argv) > 2 else "bnep.pcap"
    # errors="replace": a real serial capture contains framing garbage and
    # partial lines from before the terminal attached, and a single stray
    # byte would otherwise abort the whole conversion. The regex only ever
    # matches clean ASCII, so replaced bytes are dropped harmlessly.
    if len(sys.argv) > 1:
        src = open(sys.argv[1], "r", encoding="utf-8", errors="replace")
    else:
        src = io.TextIOWrapper(sys.stdin.buffer, encoding="utf-8",
                               errors="replace")
    pkts = list(reassemble(src))
    write_pcap(out, pkts)
    print(f"wrote {out}: {len(pkts)} ACL packets")


if __name__ == "__main__":
    main()
