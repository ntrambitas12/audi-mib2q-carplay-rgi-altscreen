#!/bin/sh
set -eu
TMP_ROOT="${ALT111_MIRROR_TMP_ROOT:-/tmp}"

PIDFILE="$TMP_ROOT/altscreen_mirror.pid"
WATCH_PIDFILE="$TMP_ROOT/altscreen_mirror.lifecycle.pid"
SUPERVISOR_PIDFILE="$TMP_ROOT/altscreen_stream_supervisor.pid"
STOP_GUARD="$TMP_ROOT/altscreen_mirror.stop.requested"
RECOVERY_LOCK="$TMP_ROOT/altscreen_mirror.recovery.lock"

stop_pidfile() {
  pf=$1
  wait_limit=$2
  [ -f "$pf" ] || return 0
  pid="$(cat "$pf" 2>/dev/null || true)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    kill -TERM "$pid" 2>/dev/null || true
    n=0
    while kill -0 "$pid" 2>/dev/null && [ "$n" -lt "$wait_limit" ]; do
      sleep 1
      n=$((n + 1))
    done
    if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || true; fi
  fi
  rm -f "$pf" 2>/dev/null || true
}

# Only explicit STOP/RESTORE publishes the persistent guard. A supervisor
# cleanup ends one session and must allow the next session to start. Preserve
# any existing explicit guard so concurrent STOP/RESTORE still wins.
if [ "${ALT111_SUPERVISOR_CHILD:-0}" != "1" ]; then
  : > "$STOP_GUARD" 2>/dev/null || true
  stop_pidfile "$SUPERVISOR_PIDFILE" 3
fi
stop_pidfile "$WATCH_PIDFILE" 3
stop_pidfile "$PIDFILE" 30

rm -f "$TMP_ROOT/altscreen_mirror.ready" \
      "$TMP_ROOT/mmi-mirror-basevideo.ready" \
      "$TMP_ROOT/altscreen_stream_supervisor.active" 2>/dev/null || true
rmdir "$RECOVERY_LOCK" 2>/dev/null || true

# Backward-compatible cleanup for builds that used /tmp/MMI-Cockpit-Carplay.
LEGACY_NS="$TMP_ROOT/MMI-Cockpit-Carplay/mirror"
stop_pidfile "$LEGACY_NS/lifecycle.pid" 3
stop_pidfile "$LEGACY_NS/pid" 30
rm -f "$LEGACY_NS/ready" "$LEGACY_NS/basevideo.ready" 2>/dev/null || true
rmdir "$LEGACY_NS/recovery.lock" 2>/dev/null || true

echo "MIRROR_DISPLAY=STOPPED lifecycle_watch=STOPPED context_writer=JAVA80 native_dmdt=DISABLED stop_guard=$([ -f "$STOP_GUARD" ] && echo RETAINED || echo ABSENT) volatile_mode=FLAT_TMP"
