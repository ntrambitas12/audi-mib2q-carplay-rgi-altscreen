#!/bin/bash
#
# Stage unified M.I.B. & Toolbox release payload for SD card.
#
# Usage:
#   ./scripts/stage_release.sh [output_directory]
#
# Default output: dist/sd_card
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_DIR="${1:-$PROJECT_DIR/dist/sd_card}"

echo "=== Staging Unified CarPlay Release to $TARGET_DIR ==="

mkdir -p "$TARGET_DIR/mod/carplay"

# QNX /bin/sh chokes on CRLF; a Windows checkout (core.autocrlf) turns the .sh files into CRLF.
# Strip CRs from scripts and JSON, copy everything else (binaries) untouched.
copy_lf() {
    case "$1" in
        *.sh|*.json) tr -d '' < "$1" > "$2" ;;
        *)           cp "$1" "$2" ;;
    esac
}

# 1. Base M.I.B. control scripts
cp "$PROJECT_DIR/install_MoreIncredibleBash/mod/custom.sh" "$TARGET_DIR/mod/"
cp "$PROJECT_DIR/install_MoreIncredibleBash/mod/command.sh" "$TARGET_DIR/mod/"

# 2. Smartphone integrator supervisor & configs
for f in carplay_child.json carplay_startup.sh carplay_monitor.sh carplay_processes.sh carplay_cleanup.sh; do
    src="$PROJECT_DIR/deploy/smartphone_integrator/$f"
    if [ -f "$src" ]; then
        copy_lf "$src" "$TARGET_DIR/mod/carplay/$f"
    else
        echo "ERROR: Missing $src" >&2; exit 1
    fi
done

# 3. Maneuver renderer atlas
cp "$PROJECT_DIR/maneuver_render/resources/flag_atlas.rgba" "$TARGET_DIR/mod/carplay/"

# 4. Compiled binaries (check build/ first)
if [ -f "$PROJECT_DIR/build/carplay_hook.jar" ]; then
    cp "$PROJECT_DIR/build/carplay_hook.jar" "$TARGET_DIR/mod/carplay/"
else
    echo "ERROR: carplay_hook.jar not found. Run ./scripts/build_java.sh first!" >&2; exit 1
fi

if [ -f "$PROJECT_DIR/build/libcarplay_hook.so" ]; then
    cp "$PROJECT_DIR/build/libcarplay_hook.so" "$TARGET_DIR/mod/carplay/"
else
    echo "WARNING: build/libcarplay_hook.so not found. If not yet built, run ./scripts/build_hook.sh"
fi

if [ -f "$PROJECT_DIR/build/maneuver_render" ]; then
    cp "$PROJECT_DIR/build/maneuver_render" "$TARGET_DIR/mod/carplay/"
else
    echo "WARNING: build/maneuver_render not found. If not yet built, run ./scripts/build_renderers.sh"
fi

# 5. AltScreen sidecar & libraries
for f in libcarplay_altscreen.so carplay-alt111-mirror-display start_vehicle.sh stop_vehicle.sh stream_supervisor.sh; do
    src="$PROJECT_DIR/deploy/altscreen/$f"
    if [ -f "$src" ]; then
        copy_lf "$src" "$TARGET_DIR/mod/carplay/$f"
    else
        echo "ERROR: Missing $src" >&2; exit 1
    fi
done

# 6. SD Card instructions
echo "Staged successfully! Contents in $TARGET_DIR:"
ls -lh "$TARGET_DIR/mod/carplay"
echo ""
echo "SD Card Setup:"
echo "1. Copy the contents of $TARGET_DIR directly to the root of your FAT32 SD card."
echo "2. Insert SD into MHI2Q MMX Slot 1."
echo "3. In GEM: M.I.B. -> Advanced Settings -> Run Custom Script."
