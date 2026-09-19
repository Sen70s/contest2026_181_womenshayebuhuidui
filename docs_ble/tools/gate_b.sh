#!/usr/bin/env bash
# Gate B: serial log -> pcap -> tshark BNEP dissection -> verdict.
set -Eeuo pipefail

LOG="${1:?usage: gate_b.sh <serial.log> [out.pcap]}"
PCAP="${2:-${LOG%.log}.pcap}"
TSHARK="${TSHARK:-$HOME/.local/tshark/tshark}"

[[ -x "$TSHARK" ]] || { echo "tshark not found; run setup_tshark.sh"; exit 1; }

python3 "$(dirname "$0")/bnep_pcap.py" "$LOG" "$PCAP"

echo "--- dissection ---"
"$TSHARK" -r "$PCAP"

echo "--- verdict ---"
malformed=$("$TSHARK" -r "$PCAP" -Y '_ws.malformed' 2>/dev/null | wc -l)
bnep=$("$TSHARK" -r "$PCAP" -Y 'btbnep' 2>/dev/null | wc -l)
setup_ok=$("$TSHARK" -r "$PCAP" -Y 'btbnep.control_type == 0x02' 2>/dev/null | wc -l)
dhcp=$("$TSHARK" -r "$PCAP" -Y 'dhcp || bootp' 2>/dev/null | wc -l)

echo "BNEP frames      : $bnep"
echo "Setup responses  : $setup_ok"
echo "DHCP frames      : $dhcp"
echo "Malformed frames : $malformed"

if [[ "$malformed" -ne 0 ]]; then echo "GATE B: FAIL (malformed)"; exit 1; fi
if [[ "$bnep"      -eq 0 ]]; then echo "GATE B: FAIL (no BNEP)";   exit 1; fi
echo "GATE B: PASS"
