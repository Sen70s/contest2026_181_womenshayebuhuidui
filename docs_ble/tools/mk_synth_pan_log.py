#!/usr/bin/env python3
"""Generate a synthetic sf32lb52 bth4 serial log for rehearsing Gate B.

Emits the exact 'sf32lb52 bth4 acl:' chunked trace lines the driver produces,
covering a full PAN bring-up: L2CAP connect on PSM 0x000F (which is what
binds Wireshark's BNEP dissector to the data channel), the BNEP setup
handshake, and a DHCP DISCOVER carried as a General Ethernet BNEP frame with
a broadcast destination.

The point is to exercise bnep_pcap.py and the tshark filters in gate_b.sh
before the board is in the loop, so a Gate B failure on hardware means the
firmware is wrong rather than the tooling. Deliberately includes a line of
serial garbage, because real captures always have some.

Usage: mk_synth_pan_log.py <out.log>
"""
import struct
import sys

SIG_CID = 0x0001
PSM_BNEP = 0x000F
SCID = 0x0040          # board's channel id
DCID = 0x0041          # phone's channel id
ACL_HANDLE = 0x0001

BOARD_MAC = bytes.fromhex("aabbccddeeff")
CHUNK = 20


def l2cap(cid, payload):
    return struct.pack("<HH", len(payload), cid) + payload


def acl(payload, pb=0b10):
    """H4 ACL packet. pb=0b10 is a first, non-flushable fragment."""
    handle_flags = ACL_HANDLE | (pb << 12)
    return (b"\x02" + struct.pack("<HH", handle_flags, len(payload)) + payload)


def sig(code, ident, data):
    return struct.pack("<BBH", code, ident, len(data)) + data


def udp_dhcp_discover():
    """A minimal but well-formed DHCP DISCOVER inside IPv4/UDP."""
    xid = 0x12345678
    bootp = bytearray(236)
    bootp[0] = 1                      # op: BOOTREQUEST
    bootp[1] = 1                      # htype: Ethernet
    bootp[2] = 6                      # hlen
    struct.pack_into(">I", bootp, 4, xid)
    struct.pack_into(">H", bootp, 10, 0x8000)   # flags: broadcast
    bootp[28:34] = BOARD_MAC
    options = bytes.fromhex("63825363") + bytes([53, 1, 1]) + bytes([255])
    dhcp = bytes(bootp) + options

    udp_len = 8 + len(dhcp)
    # UDP checksum 0 is legal for IPv4 and keeps this readable.
    udp = struct.pack(">HHHH", 68, 67, udp_len, 0) + dhcp

    total_len = 20 + len(udp)
    ip = bytearray(struct.pack(">BBHHHBBH4s4s",
                               0x45, 0x00, total_len, 0x0001, 0x0000,
                               64, 17, 0x0000,
                               bytes(4), b"\xff\xff\xff\xff"))
    chk = 0
    for i in range(0, 20, 2):
        chk += (ip[i] << 8) | ip[i + 1]
    chk = (chk & 0xFFFF) + (chk >> 16)
    struct.pack_into(">H", ip, 10, ~chk & 0xFFFF)
    return bytes(ip) + udp


def bnep_general_eth(dst, src, proto, payload):
    return bytes([0x00]) + dst + src + struct.pack(">H", proto) + payload


def trace_lines(direction, seq, pkt):
    out = []
    for off in range(0, len(pkt), CHUNK):
        piece = pkt[off:off + CHUNK]
        hexs = " ".join(f"{b:02x}" for b in piece)
        out.append(f"sf32lb52 bth4 acl: dir={direction} seq={seq} "
                   f"off={off} total={len(pkt)} {hexs}")
    return out


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else "synth_pan.log"
    lines = ["nsh> # synthetic PAN bring-up"]
    seq = 0
    flow = [
        # L2CAP connect on the BNEP PSM: without this the BNEP dissector
        # never binds and every data frame reads as raw L2CAP.
        ("tx", acl(l2cap(SIG_CID, sig(0x02, 1, struct.pack("<HH", PSM_BNEP, SCID))))),
        ("rx", acl(l2cap(SIG_CID, sig(0x03, 1, struct.pack("<HHHH", DCID, SCID, 0, 0))))),
        # Config request/response carrying the 1691-byte BNEP MTU.
        ("tx", acl(l2cap(SIG_CID, sig(0x04, 2, struct.pack("<HH", DCID, 0)
                                     + bytes([0x01, 0x02]) + struct.pack("<H", 1691))))),
        ("rx", acl(l2cap(SIG_CID, sig(0x05, 2, struct.pack("<HHH", SCID, 0, 0)
                                      + bytes([0x01, 0x02]) + struct.pack("<H", 1691))))),
        # BNEP setup handshake. 7 bytes out, 4 bytes back - the UUID Size
        # field is one byte, which is the bug this rehearsal guards.
        ("tx", acl(l2cap(DCID, bytes.fromhex("01010211161115")))),
        ("rx", acl(l2cap(SCID, bytes.fromhex("01020000")))),
        # DHCP DISCOVER to the broadcast MAC, which only General Ethernet
        # (frame type 0x00) can express.
        ("tx", acl(l2cap(DCID, bnep_general_eth(b"\xff" * 6, BOARD_MAC,
                                                0x0800, udp_dhcp_discover())))),
    ]
    for i, (d, pkt) in enumerate(flow):
        lines += trace_lines(d, seq + i, pkt)
        if i == 3:
            lines.append("\x00\xd4\xff garbled serial line from a reset")

    with open(out_path, "w", encoding="utf-8", errors="replace") as f:
        f.write("\n".join(lines) + "\n")
    print(f"wrote {out_path}: {len(flow)} ACL packets, {len(lines)} lines")


if __name__ == "__main__":
    main()
