#!/bin/sh
# V3.4 stream-driven display supervisor.
# private111 negotiation/production is always owned by the preloaded native hook.
# This process only starts the display consumer after the current private111
# producer has published a stable decoded stream marker.
set -eu

PATH=${PATH:+$PATH:}/proc/boot:/armle/bin:/armle/scripts:/bin:/usr/bin:/usr/sbin:/sbin:/mnt/app/armle/bin:/mnt/app/armle/sbin:/mnt/app/armle/usr/bin:/mnt/app/armle/usr/sbin:/eso/bin:/eso/bin/apps
export PATH

case "$0" in
  */*) ROOT=${0%/*} ;;
  *) ROOT=. ;;
esac
ROOT=$(CDPATH= cd "$ROOT" 2>/dev/null && pwd) || exit 2

TMP_ROOT="${ALT111_MIRROR_TMP_ROOT:-/tmp}"
PIDFILE="$TMP_ROOT/altscreen_stream_supervisor.pid"
STATEFILE="$TMP_ROOT/altscreen_stream_supervisor.active"
LOGFILE="$TMP_ROOT/altscreen_stream_supervisor.log"
STREAM_READY="$TMP_ROOT/altscreen-private111.stream-ready"
ACTIVE="${ALT111_MIRROR_ACTIVE_FILE:-/tmp/mmi-mirror-active}"
MIRROR_PID="$TMP_ROOT/altscreen_mirror.pid"
START="$ROOT/start_vehicle.sh"
STOP="$ROOT/stop_vehicle.sh"

[ -x "$START" ] || { echo "ERROR missing start_vehicle.sh" >&2; exit 2; }
[ -x "$STOP" ] || { echo "ERROR missing stop_vehicle.sh" >&2; exit 2; }

if [ -f "$PIDFILE" ]; then
  OLD=$(cat "$PIDFILE" 2>/dev/null || true)
  if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
    echo "STREAM_SUPERVISOR=ALREADY_RUNNING pid=$OLD"
    exit 0
  fi
  rm -f "$PIDFILE"
fi

echo "$$" > "$PIDFILE"
: > "$LOGFILE"
echo "STREAM_SUPERVISOR=STARTED pid=$$ policy=private111_stable_frames_no_fixed_delay marker=$STREAM_READY" >> "$LOGFILE"

read_stream_key() {
  [ -s "$STREAM_READY" ] || return 1
  producer_pid=$(sed -n 's/^pid=//p' "$STREAM_READY" 2>/dev/null | head -n 1)
  generation=$(sed -n 's/^generation=//p' "$STREAM_READY" 2>/dev/null | head -n 1)
  cookie=$(sed -n 's/^cookie=//p' "$STREAM_READY" 2>/dev/null | head -n 1)
  frames=$(sed -n 's/^frames=//p' "$STREAM_READY" 2>/dev/null | head -n 1)
  complete=$(sed -n 's/^ready=//p' "$STREAM_READY" 2>/dev/null | head -n 1)
  case "$producer_pid" in ''|*[!0-9]*) return 1 ;; esac
  case "$generation" in ''|*[!0-9]*) return 1 ;; esac
  case "$frames" in ''|*[!0-9]*) return 1 ;; esac
  [ "$generation" -gt 0 ] || return 1
  [ "$frames" -ge 2 ] || return 1
  [ "$complete" = "1" ] || return 1
  [ -n "$cookie" ] || return 1
  kill -0 "$producer_pid" 2>/dev/null || return 1
  printf '%s:%s:%s\n' "$producer_pid" "$generation" "$cookie"
}

mirror_running() {
  [ -s "$MIRROR_PID" ] || return 1
  mpid=$(cat "$MIRROR_PID" 2>/dev/null || true)
  case "$mpid" in ''|*[!0-9]*) return 1 ;; esac
  kill -0 "$mpid" 2>/dev/null
}

stop_display() {
  rm -f "$ACTIVE" 2>/dev/null || true
  ALT111_SUPERVISOR_CHILD=1 /bin/sh "$STOP" >> "$LOGFILE" 2>&1 || true
  rm -f "$STATEFILE" 2>/dev/null || true
}

cleanup() {
  trap - 0 1 2 15
  stop_display
  rm -f "$PIDFILE" 2>/dev/null || true
  echo "STREAM_SUPERVISOR=STOPPED pid=$$" >> "$LOGFILE" 2>/dev/null || true
}
trap cleanup 0
trap 'exit 0' 1 2 15

# Crash-loop protection.  /tmp is RAM on this unit and every sidecar start creates and destroys a Screen window
# and an EGL context, so a sidecar that cannot start must not be retried once a second forever.  Back off
# between failed starts and give up on a stream (until it is renewed) after MAX_STARTS starts that did not
# survive UP_OK_TICKS seconds.  The cluster then simply keeps the stock Audi map (Java waits for the
# sidecar's first-present file).
MAX_STARTS=5
UP_OK_TICKS=60
BACKOFF_MAX=${ALT111_SUPERVISOR_BACKOFF_MAX:-30}
LOG_CAP=262144
STARTS=0
UPTICKS=0
START_KEY=""
GAVE_UP_KEY=""
MIRROR_LOG="$TMP_ROOT/altscreen_mirror.log"

cap_log() {
  f=$1
  [ -f "$f" ] || return 0
  set -- $(wc -c < "$f" 2>/dev/null)
  sz=${1:-0}
  case "$sz" in ''|*[!0-9]*) return 0 ;; esac
  if [ "$sz" -gt "$LOG_CAP" ]; then
    : > "$f" 2>/dev/null || true
    echo "STREAM_SUPERVISOR_LOG_TRUNCATED file=$f bytes=$sz" >> "$LOGFILE"
  fi
}

while :; do
  cap_log "$LOGFILE"
  cap_log "$MIRROR_LOG"
  KEY=$(read_stream_key 2>/dev/null || true)
  OLD_KEY=$(cat "$STATEFILE" 2>/dev/null || true)

  if [ -n "$KEY" ]; then
    if [ "$KEY" != "$START_KEY" ]; then
      START_KEY=$KEY
      STARTS=0
      UPTICKS=0
      GAVE_UP_KEY=""
    fi
    if [ "$KEY" = "$GAVE_UP_KEY" ]; then
      sleep 5
      continue
    fi
    if [ -n "$OLD_KEY" ] && [ "$OLD_KEY" != "$KEY" ]; then
      echo "STREAM_SESSION_CHANGE old=$OLD_KEY new=$KEY action=RESTART_DISPLAY" >> "$LOGFILE"
      stop_display
      OLD_KEY=""
    fi

    if ! mirror_running; then
      RESTART_REASON=stream_ready
      if [ -n "$OLD_KEY" ] && [ "$OLD_KEY" = "$KEY" ]; then
        RESTART_REASON=sidecar_abnormal
      fi
      echo "STREAM_READY key=$KEY reason=$RESTART_REASON action=START_DISPLAY recover_current_session=1" >> "$LOGFILE"
      if [ "$STARTS" -ge "$MAX_STARTS" ]; then
        GAVE_UP_KEY=$KEY
        rm -f "$ACTIVE" "$STATEFILE" 2>/dev/null || true
        echo "STREAM_DISPLAY_GIVEUP key=$KEY starts=$STARTS reason=sidecar_keeps_exiting action=KEEP_STOCK_MAP" >> "$LOGFILE"
        continue
      fi
      if [ "$STARTS" -gt 0 ]; then
        BACKOFF=$((STARTS * STARTS * 2))
        [ "$BACKOFF" -le "$BACKOFF_MAX" ] || BACKOFF=$BACKOFF_MAX
        echo "STREAM_DISPLAY_BACKOFF seconds=$BACKOFF start=$((STARTS + 1))/$MAX_STARTS" >> "$LOGFILE"
        sleep "$BACKOFF"
      fi
      STARTS=$((STARTS + 1))
      UPTICKS=0
      touch "$ACTIVE"
      start_rc=0
      if ALT111_MIRROR_RESTART_REASON="$RESTART_REASON" ALT111_STREAM_SUPERVISED=1 ALT111_RECOVER_CURRENT_SESSION=1 /bin/sh "$START" >> "$LOGFILE" 2>&1; then
        if ! mirror_running; then
          start_rc=3
        fi
      else
        start_rc=$?
      fi
      if [ "$start_rc" -eq 0 ]; then
        printf '%s\n' "$KEY" > "$STATEFILE"
      else
        rm -f "$ACTIVE" "$STATEFILE" 2>/dev/null || true
        echo "STREAM_DISPLAY_START_FAILED key=$KEY rc=$start_rc fail_open=YES start=$STARTS/$MAX_STARTS" >> "$LOGFILE"
      fi
    else
      [ -f "$ACTIVE" ] || touch "$ACTIVE"
      [ "$OLD_KEY" = "$KEY" ] || printf '%s\n' "$KEY" > "$STATEFILE"
      # A sidecar that has stayed up long enough counts as healthy: later restarts get a fresh budget.
      UPTICKS=$((UPTICKS + 1))
      if [ "$UPTICKS" -ge "$UP_OK_TICKS" ]; then
        STARTS=0
      fi
    fi
  else
    if mirror_running || [ -f "$ACTIVE" ] || [ -s "$STATEFILE" ]; then
      echo "STREAM_NOT_READY action=STOP_DISPLAY" >> "$LOGFILE"
      stop_display
    fi
  fi

  sleep 1
done
