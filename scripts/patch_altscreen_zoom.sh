#!/bin/sh
#
# Let the left-roller zoom work on an idle CarPlay cluster map (personal, non-commercial use).
#
# libcarplay_altscreen.so gates every wheel-zoom step on "fresh decoded video frames": when the last
# frame is older than 150 ms and no frame has arrived since the previous step, it logs
# WHEEL_ZOOM_FRAME_STALL, blocks further steps until 3 fresh frames arrive, and later aborts and discards
# the queued steps (WHEEL_ZOOM_STALL_ABORT).  A parked, route-less iPhone map is static, so it sends no
# frames until it receives a zoom command, and the gate never lets the command out.  With a route running
# the phone streams constantly and the gate stays open, which is why zoom "only works after navigation".
#
# The check is one conditional branch in native_monitor_worker:
#     0x24800  cmp  sb, r4        ; frame age vs 150 ms
#     0x24804  bls  0x247cc       ; age <= limit -> no stall   (bytes f0 ff ff 9a)
# Making the branch unconditional (bls -> b, last byte 9a -> ea) can never enter the stall path, so
# every step takes the same code path as with healthy video.  Nothing else in the file changes.
#
#   patch_altscreen_zoom.sh <original .so> <patched output>
#
# Never edits the input.  Exit 0: patched.  Exit 1: the expected instruction is not at that offset (a
# different build, or already patched); the output is then an UNPATCHED copy so a build never breaks.
set -eu

WORD_OFFSET=149508            # 0x24804, the bls
BYTE_OFFSET=149511            # 0x24807, its condition/opcode byte
[ "$#" -eq 2 ] || { echo "usage: $0 <original .so> <patched output>" >&2; exit 2; }
IN=$1
OUT=$2
[ -f "$IN" ] || { echo "patch_altscreen_zoom: missing $IN" >&2; exit 2; }

cp "$IN" "$OUT"
got=$(od -An -tx1 -j "$WORD_OFFSET" -N4 "$IN" | tr -d ' \n')
if [ "$got" != "f0ffff9a" ]; then
    echo "patch_altscreen_zoom: WARNING expected 'bls' (f0ffff9a) at 0x24804, found '$got'; copied UNPATCHED" >&2
    exit 1
fi
# 0xea = unconditional branch, same offset
printf '\352' | dd of="$OUT" bs=1 seek="$BYTE_OFFSET" conv=notrunc 2>/dev/null
echo "patch_altscreen_zoom: zoom frame-stall gate disabled in $OUT"
