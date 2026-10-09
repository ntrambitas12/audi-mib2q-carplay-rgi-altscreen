#!/bin/bash
#
# Build the one-ZIP SD-card release that end users download.
#
#   scripts/make_release.sh <version> [--build] [--payload DIR] [--out DIR] [--publish]
#
#   <version>      e.g. v1.0.0
#   --build        compile first: build_hook.sh, build_renderers.sh, build_java.sh (Docker + the stock jar,
#                  see README - Build).  Without it the already built files in build/ are used.
#   --payload DIR  take the three compiled files (carplay_hook.jar, libcarplay_hook.so, maneuver_render)
#                  from DIR instead of build/ - e.g. the mod/carplay folder of a card you already tested.
#                  Everything else always comes from this repository.
#   --out DIR      output folder (default dist/release)
#   --publish      create the GitHub release and upload the ZIP (needs the gh CLI, logged in)
#
# The ZIP unpacks into one folder; the user copies its contents to the root of the M.I.B. SD card:
#   READ ME FIRST.txt, mod/ (installer + payload), EXTRAS/{INSTALL,UNINSTALL,COLLECT_LOGS}/mod, LICENSES/
#
# The AltScreen binaries are patched while staging (scripts/stage_release.sh); the originals stay
# untouched in deploy/altscreen/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$PROJECT_DIR"

VERSION=""; DO_BUILD=0; PAYLOAD=""; OUT_DIR="$PROJECT_DIR/dist/release"; PUBLISH=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --build)   DO_BUILD=1 ;;
        --payload) PAYLOAD="${2:?--payload needs a folder}"; shift ;;
        --out)     OUT_DIR="${2:?--out needs a folder}"; shift ;;
        --publish) PUBLISH=1 ;;
        -h|--help) sed -n 2,22p "$0"; exit 0 ;;
        -*)        echo "unknown option: $1" >&2; exit 2 ;;
        *)         [ -z "$VERSION" ] && VERSION="$1" || { echo "unexpected argument: $1" >&2; exit 2; } ;;
    esac
    shift
done
[ -n "$VERSION" ] || { echo "usage: $0 <version> [--build] [--payload DIR] [--out DIR] [--publish]" >&2; exit 2; }
case "$VERSION" in v[0-9]*) ;; *) echo "version should look like v1.0.0 (got '$VERSION')" >&2; exit 2 ;; esac

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

lf_copy() { tr -d '\015' < "$1" > "$2"; }     # QNX /bin/sh cannot run CRLF scripts

# ---- 1. the three compiled files ----
if [ "$DO_BUILD" = 1 ]; then
    echo "=== Building (this needs Docker and the stock jar, see README - Build) ==="
    bash "$SCRIPT_DIR/build_hook.sh"
    bash "$SCRIPT_DIR/build_renderers.sh"
    bash "$SCRIPT_DIR/build_java.sh"
fi
if [ -n "$PAYLOAD" ]; then
    mkdir -p "$WORK/build"
    for f in carplay_hook.jar libcarplay_hook.so maneuver_render; do
        [ -s "$PAYLOAD/$f" ] || { echo "ERROR: $PAYLOAD/$f is missing or empty" >&2; exit 1; }
        cp "$PAYLOAD/$f" "$WORK/build/$f"
    done
    export BUILD_DIR="$WORK/build"
else
    export BUILD_DIR="$PROJECT_DIR/build"
    for f in carplay_hook.jar libcarplay_hook.so maneuver_render; do
        [ -s "$BUILD_DIR/$f" ] || { echo "ERROR: build/$f is missing - run with --build, or pass --payload DIR" >&2; exit 1; }
    done
fi
# a stub or truncated jar would install fine and then break the HMI at boot
jar_size=$(wc -c < "$BUILD_DIR/carplay_hook.jar")
[ "$jar_size" -gt 100000 ] || { echo "ERROR: carplay_hook.jar is only $jar_size bytes - not a real build" >&2; exit 1; }

# ---- 2. stage the card payload (applies the AltScreen patches) ----
echo "=== Staging ==="
STAGE="$WORK/stage"
bash "$SCRIPT_DIR/stage_release.sh" "$STAGE" > "$WORK/stage.log" 2>&1 || { cat "$WORK/stage.log" >&2; exit 1; }
grep -E "^(WARNING|patch_)" "$WORK/stage.log" || true
for f in libcarplay_hook.so maneuver_render flag_atlas.rgba carplay_startup.sh carplay_monitor.sh \
         carplay_processes.sh carplay_cleanup.sh carplay_hook.jar carplay_child.json \
         libcarplay_altscreen.so carplay-alt111-mirror-display start_vehicle.sh stop_vehicle.sh stream_supervisor.sh; do
    [ -s "$STAGE/mod/carplay/$f" ] || { echo "ERROR: staged payload is missing $f" >&2; exit 1; }
done

# ---- 3. assemble the package folder ----
NAME="MHI2Q-CarPlay-AltScreen-RGI_${VERSION}"
PKG="$WORK/$NAME"
mkdir -p "$PKG/mod/carplay" "$PKG/LICENSES"
cp "$STAGE"/mod/carplay/* "$PKG/mod/carplay/"
cp "$STAGE/mod/custom.sh" "$STAGE/mod/command.sh" "$PKG/mod/"
for kind in INSTALL:install UNINSTALL:uninstall COLLECT_LOGS:logging; do
    dest="${kind%%:*}"; src="${kind##*:}_MoreIncredibleBash/mod"
    mkdir -p "$PKG/EXTRAS/$dest/mod"
    lf_copy "$PROJECT_DIR/$src/custom.sh"  "$PKG/EXTRAS/$dest/mod/custom.sh"
    lf_copy "$PROJECT_DIR/$src/command.sh" "$PKG/EXTRAS/$dest/mod/command.sh"
done
cp "$PROJECT_DIR"/licenses/* "$PKG/LICENSES/"
cp "$PROJECT_DIR/THIRD_PARTY_NOTICES.md" "$PKG/LICENSES/"
# plain-text guide for Windows Notepad: fill in version/date, CRLF line endings
sed -e "s/@VERSION@/$VERSION/g" -e "s/@DATE@/$(date +%Y-%m-%d)/g" -e 's/$/\r/' \
    "$PROJECT_DIR/release/READ_ME_FIRST.txt" > "$PKG/READ ME FIRST.txt"

# every shell script that ends up on the card must have LF endings
bad=""
while IFS= read -r f; do
    [ "$(tr -cd '\015' < "$f" | wc -c)" -eq 0 ] || bad="$bad $f"
done < <(find "$PKG/mod" "$PKG/EXTRAS" -name '*.sh')
[ -z "$bad" ] || { echo "ERROR: CRLF line endings in:$bad" >&2; exit 1; }

# ---- 4. zip ----
mkdir -p "$OUT_DIR"
ZIP="$OUT_DIR/$NAME.zip"
rm -f "$ZIP"
if command -v zip >/dev/null 2>&1; then
    (cd "$WORK" && zip -qr -X "$ZIP" "$NAME")
else
    PY=""; for c in python3 python; do "$c" -c 'import sys' >/dev/null 2>&1 && { PY=$c; break; }; done
    [ -n "$PY" ] || { echo "ERROR: need zip or python to create the archive" >&2; exit 1; }
    # a native (Windows) Python cannot open Git-Bash style /c/... paths
    ZIP_NATIVE="$(cygpath -m "$ZIP" 2>/dev/null || echo "$ZIP")"
    (cd "$WORK" && "$PY" - "$ZIP_NATIVE" "$NAME" <<'PYEOF'
import os, sys, zipfile
zip_path, root = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
    for base, _dirs, files in os.walk(root):
        for f in sorted(files):
            p = os.path.join(base, f)
            z.write(p, p.replace(os.sep, "/"))
PYEOF
    )
fi
( cd "$OUT_DIR" && sha256sum "$NAME.zip" > "$NAME.zip.sha256" )

echo
echo "=== Release ZIP ready ==="
ls -l "$ZIP"
cat "$ZIP.sha256"
echo "contents:"
if command -v unzip >/dev/null 2>&1; then unzip -l "$ZIP" | sed -n '4,$p' | head -40; fi

# ---- 5. publish ----
if [ "$PUBLISH" = 1 ]; then
    command -v gh >/dev/null 2>&1 || { echo "ERROR: --publish needs the gh CLI" >&2; exit 1; }
    sed -e "s/@VERSION@/$VERSION/g" "$PROJECT_DIR/release/RELEASE_NOTES.md" > "$WORK/notes.md"
    gh release create "$VERSION" "$ZIP" "$ZIP.sha256" --title "CarPlay AltScreen + RGI $VERSION" \
        --notes-file "$WORK/notes.md" --latest
fi
