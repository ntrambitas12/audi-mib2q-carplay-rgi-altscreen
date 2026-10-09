#!/bin/sh
#
# Disable the embedded startup logo of carplay-alt111-mirror-display (personal, non-commercial use).
#
# The sidecar plays a ~4.5 s intro clip ("ALTLOGO1": 137 zlib RGBA frames) once, on the first CarPlay
# frame of every session, before the map appears.  The binary has a "STARTUP_LOGO_SKIP ... show the
# CarPlay picture" path for an unusable asset, so the patch only damages the 8-byte asset magic at the
# symbol `altscreen_logo_data` (file offset 0xee8c = 61068).  Everything else stays byte-identical.
#
#   patch_altscreen_logo.sh <original-binary> <patched-output>
#
# Never edits the input.  Exit 0: patched.  Exit 1: the magic is not where this patch expects it (a
# different build, or already patched); the output is then an UNPATCHED copy so a build never breaks.
set -eu

OFFSET=61068
[ "$#" -eq 2 ] || { echo "usage: $0 <original-binary> <patched-output>" >&2; exit 2; }
IN=$1
OUT=$2
[ -f "$IN" ] || { echo "patch_altscreen_logo: missing $IN" >&2; exit 2; }

cp "$IN" "$OUT"
got=$(dd if="$IN" bs=1 skip=$OFFSET count=8 2>/dev/null | tr -d '\0')
if [ "$got" != "ALTLOGO1" ]; then
    echo "patch_altscreen_logo: WARNING no ALTLOGO1 magic at offset $OFFSET (found '$got'); copied UNPATCHED" >&2
    exit 1
fi
printf 'X' | dd of="$OUT" bs=1 seek=$OFFSET conv=notrunc 2>/dev/null
echo "patch_altscreen_logo: startup logo disabled in $OUT"
