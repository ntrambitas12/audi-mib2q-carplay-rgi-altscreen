#!/bin/sh
#
# Stage the M.I.B. install payload (the SD card's mod/ folder) from the build outputs.
#
#   ./scripts/package_release.sh                       debug payload (default)
#   CARPLAY_RELEASE=1 ./scripts/package_release.sh     release payload: no debug piece at all
#
# Output: build/payload/mod/{custom.sh,command.sh,carplay/...}  - copy mod/ to the SD card root.
#
# Debug payload   = the release files + carplay_logcopy.sh (SD-card flight recorder) and
#                   BUILD_MODE=debug (the installer then also creates /mnt/app/carplay_verbose).
# Release payload = no carplay_logcopy.sh, the logcopy start block stripped out of
#                   carplay_startup.sh, BUILD_MODE=release (the installer then REMOVES any debug
#                   pieces an earlier debug install left on the unit).
# The jar must have been built in the same mode (scripts/build_java.sh records it in
# build/carplay_hook.mode); a mismatch stops here, so a debug jar can never ship as release.
# Copyright (c) 2026 LuKa (@LuKa_dev)
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD_DIR=${CP_BUILD_DIR:-$PROJECT_DIR/build}
ATLAS=${CP_ATLAS:-$PROJECT_DIR/maneuver_render/resources/flag_atlas.rgba}
OUT=${CP_PAYLOAD_OUT:-$BUILD_DIR/payload}
DEPLOY=$PROJECT_DIR/deploy/smartphone_integrator
INSTALL=$PROJECT_DIR/install_MoreIncredibleBash/mod

if [ "${CARPLAY_RELEASE:-0}" = "1" ]; then MODE=release; else MODE=debug; fi
BUILD_ID_RAW=${CARPLAY_BUILD_ID:-$(git -C "$PROJECT_DIR" describe --always --dirty 2>/dev/null || echo unknown)}
BUILD_ID=$(printf '%s' "$BUILD_ID_RAW" | tr -cd 'A-Za-z0-9._-')

fail() { echo "ERROR: $*" >&2; exit 1; }

# inputs
for f in "$BUILD_DIR/libcarplay_hook.so" "$BUILD_DIR/maneuver_render" "$BUILD_DIR/carplay_hook.jar" \
         "$ATLAS" "$DEPLOY/carplay_startup.sh" "$DEPLOY/carplay_monitor.sh" \
         "$DEPLOY/carplay_processes.sh" "$DEPLOY/carplay_cleanup.sh" "$DEPLOY/carplay_child.json" \
         "$INSTALL/custom.sh" "$INSTALL/command.sh"; do
    [ -f "$f" ] || fail "missing $f (build it first)"
done
[ "$MODE" = debug ] && { [ -f "$DEPLOY/carplay_logcopy.sh" ] || fail "missing $DEPLOY/carplay_logcopy.sh"; }
BUILT=$(cat "$BUILD_DIR/carplay_hook.mode" 2>/dev/null || echo unknown)
[ "$BUILT" = "$MODE" ] || fail "carplay_hook.jar was built as '$BUILT' but this is a '$MODE' package; rebuild with scripts/build_java.sh$( [ "$MODE" = release ] && echo ' (CARPLAY_RELEASE=1)')"

rm -rf "$OUT"
mkdir -p "$OUT/mod/carplay"
cp "$INSTALL/custom.sh" "$INSTALL/command.sh" "$OUT/mod/"
R=$OUT/mod/carplay
cp "$BUILD_DIR/libcarplay_hook.so" "$BUILD_DIR/maneuver_render" "$BUILD_DIR/carplay_hook.jar" "$R/"
cp "$ATLAS" "$R/flag_atlas.rgba"
cp "$DEPLOY/carplay_monitor.sh" "$DEPLOY/carplay_processes.sh" "$DEPLOY/carplay_cleanup.sh" \
   "$DEPLOY/carplay_child.json" "$R/"
if [ "$MODE" = release ]; then
    # Strip the flight-recorder start block; the release wrapper has no trace of it.
    sed '/DEBUG-LOGCOPY-BEGIN/,/DEBUG-LOGCOPY-END/d' "$DEPLOY/carplay_startup.sh" > "$R/carplay_startup.sh"
else
    cp "$DEPLOY/carplay_startup.sh" "$DEPLOY/carplay_logcopy.sh" "$R/"
fi
chmod 755 "$R"/*.sh "$OUT/mod"/*.sh
printf '%s\nbuild_id=%s\n' "$MODE" "$BUILD_ID" > "$R/BUILD_MODE"

# Release gate: nothing debug-related may be in the payload.
if [ "$MODE" = release ]; then
    [ ! -e "$R/carplay_logcopy.sh" ] || fail "release payload contains carplay_logcopy.sh"
    if grep -rl 'carplay_logcopy\|DEBUG-LOGCOPY' "$R" >/dev/null 2>&1; then
        fail "release payload still mentions the flight recorder: $(grep -rl 'carplay_logcopy\|DEBUG-LOGCOPY' "$R")"
    fi
fi
echo "Payload ($MODE, build $BUILD_ID): $OUT/mod"
ls -1 "$R"
