#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
IMG=qnx65-armv7-toolchain:latest
OUT="$PROJECT_DIR/deploy/altscreen/libaltscreen_egl_fix.so"

DOCKER_CMD="docker"
if ! command -v docker >/dev/null 2>&1; then
    if command -v flatpak-spawn >/dev/null 2>&1; then
        DOCKER_CMD="flatpak-spawn --host docker"
    else
        echo "ERROR: docker not found"
        exit 1
    fi
fi

echo "=== Building AltScreen EGL Fix Shim ($IMG) ==="

$DOCKER_CMD run --rm --platform=linux/amd64 -v "$PROJECT_DIR":/src "$IMG" bash -c '
  set -e
  export PATH=/opt/qnx650/host/linux/x86/usr/bin:$PATH
  export QNX_HOST=/opt/qnx650/host/linux/x86 QNX_TARGET=/opt/qnx650/target/qnx6
  CC=arm-unknown-nto-qnx6.5.0eabi-gcc

  mkdir -p /tmp/stubs
  gen_stub(){ local so="$1" rx="$2"; shift 2
    grep -rhoE "$rx" "$@" 2>/dev/null | sort -u | sed "s/.*/int &(){return 0;}/" > /tmp/st_$so.c
    $CC -shared -fPIC -Wl,-soname,"$so" /tmp/st_$so.c -o /tmp/stubs/"$so"
  }

  gen_stub libscreen.so.1 "\bscreen_[a-z_]+" /src/deploy/altscreen/altscreen_egl_fix.c
  gen_stub libEGL.so.1    "\begl[A-Z][A-Za-z0-9]+" /src/deploy/altscreen/altscreen_egl_fix.c

  $CC -shared -fPIC -O2 -Wall -D__QNX__ \
      -I/src/toolchain/qnx65-abi/include \
      /src/deploy/altscreen/altscreen_egl_fix.c \
      -o /src/deploy/altscreen/libaltscreen_egl_fix.so \
      -Wl,-soname,libaltscreen_egl_fix.so \
      -Wl,--allow-shlib-undefined \
      -L/tmp/stubs -l:libscreen.so.1 -l:libEGL.so.1

  arm-unknown-nto-qnx6.5.0eabi-readelf -d /src/deploy/altscreen/libaltscreen_egl_fix.so
'

echo "Built: $OUT"
ls -lh "$OUT"
