#!/bin/sh
# deploy/smartphone_integrator/carplay_logcopy.sh (the debug SD-card flight recorder) against a
# fake card and a fake /tmp: new-bytes-only append, rotation/truncation, missing and read-only
# card, 20 MB-style cap eviction (only live_NNN, oldest first), the per-pass cap with its skip
# marker, highest+1 numbering, info.txt, single instance, exit when the debug marker is removed.
# The deterministic cases source the script (CP_LC_SOURCE_ONLY) and call do_pass directly; the
# process cases run it for real with a 1 s interval.  Every POSIX/ksh shell found here (QNX 6.5
# /bin/sh is pdksh).
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
SCRIPT=$ROOT/deploy/smartphone_integrator/carplay_logcopy.sh
T=$(mktemp -d); export T; trap 'kill $(cat "$T/pid" 2>/dev/null) 2>/dev/null; rm -rf "$T"' EXIT
fail() { echo "FAIL ($1): $2"; exit 1; }
sz() { set -- $(wc -c < "$1"); echo "$1"; }

shells=
for s in /bin/ksh /bin/mksh "$(command -v mksh 2>/dev/null)" /bin/dash /bin/sh; do
    [ -n "$s" ] && [ -x "$s" ] && shells="$shells $s"
done

fresh() {   # fresh <shell>: new fake unit (src, card with mod/, hooks, marker); exports the overrides
    rm -rf "$T/u"; mkdir -p "$T/u/src" "$T/u/card/mod" "$T/u/hooks"
    : > "$T/u/marker"
    printf 'debug\nbuild_id=test123\n' > "$T/u/hooks/carplay_build_mode"
    L=$T/u/card/carplay_logs
}
# run <shell> <snippet>: source the script (functions only) and run the snippet in that shell
run() {
    CP_LC_SOURCE_ONLY=1 CP_LOG_SRC=$T/u/src CP_LC_MARKER=$T/u/marker CP_LC_HOOKS=$T/u/hooks \
    CP_LC_PID=$T/pid CP_LC_MOUNT=${MNT:-true} CP_LC_CARDS="$T/u/nocard $T/u/card" \
    CP_LC_PASS_BYTES=${PB:-524288} CP_LC_CAP_BYTES=${CAP:-20971520} \
        "$1" -c ". \"$SCRIPT\"; $2"
}

for sh in $shells; do
    # ---- new bytes only, append-only, byte exact across the 4 KiB copy boundaries ----
    fresh
    run "$sh" 'printf aaa > "$SRC/carplay_hook.log"; do_pass
               printf bbb >> "$SRC/carplay_hook.log"; do_pass; do_pass' || fail "$sh" "run 1"
    D=$L/live_001
    [ "$(cat "$D/carplay_hook.log")" = aaabbb ] || fail "$sh" "append-only new bytes: got $(cat "$D/carplay_hook.log")"
    fresh
    run "$sh" 'i=0; : > "$SRC/carplay_big.log"
               while [ $i -lt 300 ]; do echo "line $i 0123456789abcdef0123456789abcdef" >> "$SRC/carplay_big.log"; i=$((i+1)); done
               do_pass
               i=0; while [ $i -lt 250 ]; do echo "more $i XYZXYZXYZXYZXYZXYZXYZXYZXYZXYZXYZ" >> "$SRC/carplay_big.log"; i=$((i+1)); done
               do_pass' || fail "$sh" "run 2"
    cmp "$T/u/src/carplay_big.log" "$L/live_001/carplay_big.log" || fail "$sh" "copy not byte exact"
    [ "$(sz "$T/u/src/carplay_big.log")" -gt 8192 ] || fail "$sh" "test file too small to cross blocks"

    # ---- info.txt: clock, build, uptime ----
    for k in started uptime 'build       test123' 'mode        debug' 'card        '; do
        grep -q "$k" "$L/live_001/info.txt" || fail "$sh" "info.txt lacks '$k': $(cat "$L/live_001/info.txt")"
    done

    # ---- rotation: X.log -> X.log.1 with an unread tail; history copied once ----
    fresh
    run "$sh" 'printf "A%.0s" 1 2 3 4 5 6 7 8 9 10 > "$SRC/carplay_java.log"        # 10 bytes
               printf "OLD1" > "$SRC/carplay_java.log.1"
               do_pass
               printf "BBBB" >> "$SRC/carplay_java.log"                          # unread tail
               mv "$SRC/carplay_java.log" "$SRC/carplay_java.log.1"              # rotation
               printf "CC" > "$SRC/carplay_java.log"                             # new, shorter file
               do_pass; do_pass' || fail "$sh" "rotation run"
    D=$L/live_001
    [ "$(cat "$D/carplay_java.log.1")" = OLD1 ] || fail "$sh" "rotated history not kept once: $(cat "$D/carplay_java.log.1")"
    got=$(cat "$D/carplay_java.log")
    case $got in AAAAAAAAAA*rotated*BBBBCC) ;; *) fail "$sh" "rotation lost or duplicated bytes: $got" ;; esac
    [ "$(grep -c AAAAAAAAAA "$D/carplay_java.log")" = 1 ] || fail "$sh" "old bytes copied twice"
    # truncation with no .log.1: offset > size restarts from 0 (with a marker)
    fresh
    run "$sh" 'printf 1234567890 > "$SRC/carplay_x.log"; do_pass; printf ab > "$SRC/carplay_x.log"; do_pass' || fail "$sh" "truncate run"
    got=$(cat "$L/live_001/carplay_x.log")
    case $got in 1234567890*truncated*ab) ;; *) fail "$sh" "truncation: $got" ;; esac

    # rotation where the NEW log has already regrown PAST the old offset: detected by X.log.1 changing
    fresh
    run "$sh" 'i=0; while [ $i -lt 20 ]; do echo "old line $i ......................" >> "$SRC/carplay_java.log"; i=$((i+1)); done
               do_pass
               echo "UNREAD-TAIL-MARK" >> "$SRC/carplay_java.log"                  # not copied yet
               mv "$SRC/carplay_java.log" "$SRC/carplay_java.log.1"
               i=0; while [ $i -lt 40 ]; do echo "new line $i ,,,,,,,,,,,,,,,,,,,,,," >> "$SRC/carplay_java.log"; i=$((i+1)); done
               do_pass; do_pass' || fail "$sh" "regrown rotation run"
    D=$L/live_001/carplay_java.log
    grep -q UNREAD-TAIL-MARK "$D" || fail "$sh" "regrown rotation lost the unread tail of the old file"
    [ "$(grep -c 'old line 0 ' "$D")" = 1 ] || fail "$sh" "old bytes duplicated after a regrown rotation"
    [ "$(grep -c 'new line' "$D")" = 40 ] || fail "$sh" "new file not copied from 0 after rotation: $(grep -c 'new line' "$D")"
    [ "$(grep -n UNREAD-TAIL-MARK "$D" | cut -d: -f1)" -lt "$(grep -n 'new line 0 ' "$D" | cut -d: -f1)" ] \
        || fail "$sh" "old tail must come before the new file"

    # in-place truncation by the monitor (cp_cap_log: `: > f; echo marker >> f`), regrown past the old offset
    fresh
    run "$sh" 'i=0; while [ $i -lt 10 ]; do echo "before cap $i xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" >> "$SRC/maneuver_render.log"; i=$((i+1)); done
               do_pass
               : > "$SRC/maneuver_render.log"; echo "[monitor] truncated at 999 bytes" >> "$SRC/maneuver_render.log"
               i=0; while [ $i -lt 30 ]; do echo "after cap $i yyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyyy" >> "$SRC/maneuver_render.log"; i=$((i+1)); done
               do_pass' || fail "$sh" "cap truncation run"
    D=$L/live_001/maneuver_render.log
    [ "$(grep -c 'before cap' "$D")" = 10 ] && [ "$(grep -c 'after cap' "$D")" = 30 ] && grep -q '\[monitor\] truncated' "$D" \
        || fail "$sh" "in-place truncation lost or duplicated bytes: before=$(grep -c 'before cap' "$D") after=$(grep -c 'after cap' "$D")"
    grep -q 'maneuver_render.log truncated' "$D" || fail "$sh" "no truncation marker"

    # ---- scope: carplay_*.log (+ .1) and maneuver_render.log only ----
    fresh
    run "$sh" 'echo r > "$SRC/maneuver_render.log"; echo w > "$SRC/carplay_wrapper.log"; echo h > "$SRC/carplay_hook.log"
               echo t > "$SRC/carplay_state_trace.log"; echo s > "$SRC/shm_obj"; echo o > "$SRC/other.log"; echo p > "$SRC/carplay_x.pid"; do_pass'
    for f in maneuver_render.log carplay_wrapper.log carplay_hook.log carplay_state_trace.log; do
        [ -f "$L/live_001/$f" ] || fail "$sh" "$f not copied"
    done
    [ ! -e "$L/live_001/shm_obj" ] && [ ! -e "$L/live_001/other.log" ] && [ ! -e "$L/live_001/carplay_x.pid" ] \
        || fail "$sh" "copied something outside the CarPlay logs: $(ls "$L/live_001" | tr '\n' ' ')"
    # carplay_java.log is copied first when the pass budget is short (carplay_aaa.log sorts before it)
    fresh
    PB=1000 run "$sh" 'dd if=/dev/zero bs=800 count=1 2>/dev/null | tr "\0" "j" > "$SRC/carplay_java.log"
                       dd if=/dev/zero bs=800 count=1 2>/dev/null | tr "\0" "a" > "$SRC/carplay_aaa.log"; do_pass'
    [ "$(sz "$L/live_001/carplay_java.log")" = 800 ] && [ "$(sz "$L/live_001/carplay_aaa.log")" = 200 ] \
        || fail "$sh" "carplay_java.log not first in the budget: java=$(sz "$L/live_001/carplay_java.log") aaa=$(sz "$L/live_001/carplay_aaa.log")"

    # ---- no card: silent skip, backlog copied when the card appears ----
    fresh; rm -rf "$T/u/card"
    run "$sh" 'printf one > "$SRC/carplay_x.log"; do_pass; do_pass
               mkdir -p "$T/u/card/mod"; printf two >> "$SRC/carplay_x.log"; do_pass' || fail "$sh" "missing card must not fail"
    [ "$(cat "$T/u/card/carplay_logs/live_001/carplay_x.log")" = onetwo ] || fail "$sh" "backlog after card appears"
    # a card that stays read-only is remounted at most once per 20 passes (45 passes -> 3 attempts, not 45),
    # and the history resets when the card disappears and comes back
    printf '#!/bin/sh\necho x >> "%s/mount.n"\n' "$T" > "$T/countmount.sh"; chmod +x "$T/countmount.sh"
    fresh; mkdir "$T/u/card/.lc_wtest"; rm -f "$T/mount.n"
    MNT=$T/countmount.sh run "$sh" 'echo z > "$SRC/carplay_x.log"; i=0; while [ $i -lt 45 ]; do do_pass; i=$((i+1)); done' || fail "$sh" "remount run"
    [ "$(wc -l < "$T/mount.n" | tr -d ' ')" = 3 ] || fail "$sh" "remount attempts: $(wc -l < "$T/mount.n" | tr -d ' '), expected 3 in 45 passes"
    rm -f "$T/mount.n"
    MNT=$T/countmount.sh run "$sh" 'echo z > "$SRC/carplay_x.log"; do_pass; do_pass; do_pass
                                    mv "$T/u/card/mod" "$T/u/card/mod.away"; do_pass         # card gone
                                    mv "$T/u/card/mod.away" "$T/u/card/mod"; do_pass; do_pass' || fail "$sh" "remount reset run"
    [ "$(wc -l < "$T/mount.n" | tr -d ' ')" = 2 ] || fail "$sh" "remount history not reset on reappearance: $(wc -l < "$T/mount.n" | tr -d ' ')"
    # a good card is probed once, not every pass; a failed write re-probes
    fresh
    run "$sh" 'can_write() { echo p >> "$T/probe.n"; { echo probe > "$1/.lc_wtest" && rm -f "$1/.lc_wtest"; } 2>/dev/null; }
               rm -f "$T/probe.n"; echo z > "$SRC/carplay_x.log"; i=0; while [ $i -lt 10 ]; do echo more >> "$SRC/carplay_x.log"; do_pass; i=$((i+1)); done
               echo "first=$(wc -l < "$T/probe.n" | tr -d " ")" > "$T/probe.res"
               rm -rf "$T/u/card/carplay_logs"; do_pass; do_pass          # card contents vanish: probe again
               echo "after=$(wc -l < "$T/probe.n" | tr -d " ")" >> "$T/probe.res"'
    [ "$(sed -n 1p "$T/probe.res")" = first=1 ] || fail "$sh" "write probe ran on every pass: $(cat "$T/probe.res")"
    [ "$(sed -n 2p "$T/probe.res")" != after=1 ] || fail "$sh" "no re-probe after the card lost its folder"
    # sync: after passes that wrote, at most once per 60 s (4 passes at 15 s), never without new bytes
    fresh
    run "$sh" 'sync() { echo s >> "$T/sync.n"; }; rm -f "$T/sync.n"; echo z > "$SRC/carplay_x.log"
               i=0; while [ $i -lt 9 ]; do echo more >> "$SRC/carplay_x.log"; do_pass; i=$((i+1)); done
               j=0; while [ $j -lt 12 ]; do do_pass; j=$((j+1)); done'      # nothing new: only a pending flush'
    n=$(wc -l < "$T/sync.n" | tr -d ' ')
    [ "$n" = 9 ] || fail "$sh" "sync count $n over 21 passes: expected one per writing pass (9), none for idle passes"
    # final sync: once only (TERM + EXIT traps), and never on a pulled card
    fresh
    run "$sh" 'sync() { echo s >> "$T/sync.n"; }; rm -f "$T/sync.n"; CARD=$T/u/card; mkdir -p "$CARD/carplay_logs"
               PENDING_SYNC=1; cleanup; cleanup; rm -rf "$CARD/carplay_logs"; PENDING_SYNC=1; cleanup; :'
    [ "$(wc -l < "$T/sync.n" | tr -d ' ')" = 1 ] || fail "$sh" "cleanup sync count: $(cat "$T/sync.n" 2>/dev/null | wc -l)"
    # a vanished .1 (no rotation) must not re-copy the current log
    fresh
    run "$sh" 'echo old > "$SRC/carplay_java.log.1"; i=0; while [ $i -lt 5 ]; do echo "line $i pppppppppppppppppppppppppppppppppppppppppppppppppppppppppp" >> "$SRC/carplay_java.log"; i=$((i+1)); done
               do_pass; rm -f "$SRC/carplay_java.log.1"; echo "line 5" >> "$SRC/carplay_java.log"; do_pass; do_pass'
    [ "$(grep -c 'line 0 ' "$L/live_001/carplay_java.log")" = 1 ] && grep -q 'line 5' "$L/live_001/carplay_java.log" || fail "$sh" "vanished .1 re-copied the log"
    # cksum missing: rotation still found by the .1 size change, truncation by size < offset
    fresh
    run "$sh" 'cksum() { return 127; }
               i=0; while [ $i -lt 20 ]; do echo "old line $i ......................" >> "$SRC/carplay_java.log"; i=$((i+1)); done
               do_pass; echo "TAIL-MARK" >> "$SRC/carplay_java.log"; mv "$SRC/carplay_java.log" "$SRC/carplay_java.log.1"
               i=0; while [ $i -lt 40 ]; do echo "new line $i ,,,,,,,,,,,,,,,,,,,,,," >> "$SRC/carplay_java.log"; i=$((i+1)); done
               do_pass; do_pass
               echo aaaaaaaaaa > "$SRC/carplay_hook.log"; do_pass; echo b > "$SRC/carplay_hook.log"; do_pass'
    D=$L/live_001/carplay_java.log
    grep -q TAIL-MARK "$D" && [ "$(grep -c 'new line' "$D")" = 40 ] && [ "$(grep -c 'old line 0 ' "$D")" = 1 ] || fail "$sh" "no-cksum rotation"
    grep -q 'carplay_hook.log truncated' "$L/live_001/carplay_hook.log" || fail "$sh" "no-cksum truncation"
    # a folder without carplay_logs/ or mod/ is not our card
    fresh; rm -rf "$T/u/card"; mkdir -p "$T/u/card/other"
    run "$sh" 'echo z > "$SRC/carplay_x.log"; do_pass'
    [ ! -e "$T/u/card/carplay_logs" ] || fail "$sh" "used a card without carplay_logs/ or mod/"
    # read-only / unwritable card: skipped, no error; copies once writable
    fresh; mkdir "$T/u/card/.lc_wtest"          # the write test can not create its probe file
    run "$sh" 'echo z > "$SRC/carplay_x.log"; do_pass' || fail "$sh" "read-only card must not fail"
    [ ! -e "$T/u/card/carplay_logs/live_001/carplay_x.log" ] || fail "$sh" "wrote to an unwritable card"
    rmdir "$T/u/card/.lc_wtest"
    run "$sh" 'echo z > "$SRC/carplay_x.log"; do_pass'
    [ -f "$T/u/card/carplay_logs/live_001/carplay_x.log" ] || fail "$sh" "did not use the card once writable"

    # ---- numbering: highest existing live_NNN + 1 (008/009 are octal traps); logging folders untouched ----
    fresh; mkdir -p "$L/live_007" "$L/live_009" "$L/012"
    run "$sh" 'echo z > "$SRC/carplay_x.log"; do_pass'
    [ -f "$L/live_010/carplay_x.log" ] && [ -d "$L/012" ] || fail "$sh" "numbering: $(ls "$L" | tr '\n' ' ')"

    # ---- cap: oldest live_* deleted first, never anything else ----
    fresh; mkdir -p "$L/live_001" "$L/live_002" "$L/001" "$L/live_backup"
    dd if=/dev/zero of="$L/live_001/carplay_a.log" bs=1000 count=2 2>/dev/null
    dd if=/dev/zero of="$L/live_002/carplay_a.log" bs=1000 count=2 2>/dev/null
    echo keep > "$L/001/carplay_x.log"; echo keep > "$L/live_backup/carplay_x.log"
    CAP=3000 run "$sh" 'echo new > "$SRC/carplay_x.log"; do_pass' || fail "$sh" "cap run"
    [ ! -e "$L/live_001" ] || fail "$sh" "oldest live folder not evicted"
    [ -d "$L/live_002" ] && [ -f "$L/live_003/carplay_x.log" ] || fail "$sh" "cap evicted too much: $(ls "$L" | tr '\n' ' ')"
    [ -f "$L/001/carplay_x.log" ] && [ -f "$L/live_backup/carplay_x.log" ] || fail "$sh" "cap touched a non-live folder"
    # a folder in use that outgrows a quarter of the cap rolls over; total stays bounded
    fresh
    CAP=4000 run "$sh" 'i=0; while [ $i -lt 8 ]; do dd if=/dev/zero bs=600 count=1 2>/dev/null | tr "\0" "x" >> "$SRC/carplay_x.log"; do_pass; i=$((i+1)); done' \
        || fail "$sh" "roll run"
    n=$(ls -d "$L"/live_* | wc -l | tr -d ' ')
    [ "$n" -ge 2 ] || fail "$sh" "live folder did not roll over"
    tot=0; for f in "$L"/live_*/*; do tot=$((tot + $(sz "$f"))); done
    [ "$tot" -le 5200 ] || fail "$sh" "total $tot bytes exceeds cap by more than one folder"

    # ---- per-pass cap: a huge backlog keeps its tail and says what was skipped ----
    fresh
    PB=1000 run "$sh" 'dd if=/dev/zero bs=5000 count=1 2>/dev/null | tr "\0" "z" > "$SRC/carplay_x.log"; do_pass' || fail "$sh" "pass-cap run"
    got=$(cat "$L/live_001/carplay_x.log")
    case $got in *'skipped 4000 bytes of carplay_x.log'*) ;; *) fail "$sh" "no skip marker: $got" ;; esac
    tail_n=$(tr -d '\n' < "$L/live_001/carplay_x.log" | tr -cd 'z' | wc -c | tr -d ' ')
    [ "$tail_n" = 1000 ] || fail "$sh" "kept $tail_n tail bytes, expected 1000"
    # a backlog that fits one pass but not the budget left by another file: nothing skipped, finished next pass
    fresh
    PB=1000 run "$sh" 'dd if=/dev/zero bs=800 count=1 2>/dev/null | tr "\0" "a" > "$SRC/carplay_a.log"
                       dd if=/dev/zero bs=800 count=1 2>/dev/null | tr "\0" "b" > "$SRC/carplay_b.log"; do_pass; do_pass'
    [ "$(sz "$L/live_001/carplay_a.log")" = 800 ] && [ "$(sz "$L/live_001/carplay_b.log")" = 800 ] || fail "$sh" "budget split lost bytes"
    grep -q skipped "$L/live_001/carplay_b.log" && fail "$sh" "bytes skipped although they fitted the next pass"

    # ---- real process: single instance, stale pid, clean exit on marker removal ----
    fresh
    export CP_LOG_SRC=$T/u/src CP_LC_MARKER=$T/u/marker CP_LC_HOOKS=$T/u/hooks CP_LC_PID=$T/pid \
           CP_LC_MOUNT=true CP_LC_CARDS="$T/u/card" CP_LC_INTERVAL=1
    rm -f "$T/pid"; echo live > "$T/u/src/carplay_x.log"
    "$sh" "$SCRIPT" </dev/null >/dev/null 2>&1 &
    first=$!
    w=0; while [ ! -f "$T/u/card/carplay_logs/live_001/carplay_x.log" ] && [ $w -lt 50 ]; do sleep 0.2 2>/dev/null || sleep 1; w=$((w+1)); done
    [ "$(cat "$T/pid")" = "$first" ] || fail "$sh" "pidfile does not name the first instance"
    "$sh" "$SCRIPT" </dev/null >/dev/null 2>&1; rc=$?
    [ "$rc" = 0 ] && [ "$(cat "$T/pid")" = "$first" ] || fail "$sh" "second instance did not step aside (rc=$rc)"
    kill -0 "$first" 2>/dev/null || fail "$sh" "first instance died"
    echo more >> "$T/u/src/carplay_x.log"
    w=0; while [ "$(cat "$T/u/card/carplay_logs/live_001/carplay_x.log")" != "live
more" ] && [ $w -lt 50 ]; do sleep 0.2 2>/dev/null || sleep 1; w=$((w+1)); done
    [ "$(cat "$T/u/card/carplay_logs/live_001/carplay_x.log")" = "live
more" ] || fail "$sh" "running copier did not append the new bytes"
    rm -f "$T/u/marker"
    w=0; while kill -0 "$first" 2>/dev/null && [ $w -lt 50 ]; do sleep 0.2 2>/dev/null || sleep 1; w=$((w+1)); done
    kill -0 "$first" 2>/dev/null && fail "$sh" "did not exit after the marker was removed"
    [ ! -e "$T/pid" ] || fail "$sh" "pidfile left behind after a clean exit"
    # a stale pidfile (dead pid) is taken over
    : > "$T/u/marker"; sleep 0 & dead=$!; wait "$dead" 2>/dev/null; echo "$dead" > "$T/pid"
    CP_LC_PASSES=1 "$sh" "$SCRIPT" </dev/null >/dev/null 2>&1 || fail "$sh" "stale pidfile run failed"
    [ ! -e "$T/pid" ] || fail "$sh" "stale pid takeover left the pidfile"
    unset CP_LOG_SRC CP_LC_MARKER CP_LC_HOOKS CP_LC_PID CP_LC_MOUNT CP_LC_CARDS CP_LC_INTERVAL
    # marker absent at start: exits at once, writes nothing
    fresh; rm -f "$T/u/marker"; echo z > "$T/u/src/carplay_x.log"
    CP_LOG_SRC=$T/u/src CP_LC_MARKER=$T/u/marker CP_LC_PID=$T/pid CP_LC_CARDS="$T/u/card" CP_LC_HOOKS=$T/u/hooks \
        "$sh" "$SCRIPT" </dev/null >/dev/null 2>&1
    [ ! -e "$T/u/card/carplay_logs" ] || fail "$sh" "copied without the debug marker"
    # ---- the start hook in carplay_startup.sh: its own background process, LD_PRELOAD cleared ----
    STARTUP=$ROOT/deploy/smartphone_integrator/carplay_startup.sh
    sed -n '/DEBUG-LOGCOPY-BEGIN/,/DEBUG-LOGCOPY-END/p' "$STARTUP" > "$T/hookblock.sh"
    [ -s "$T/hookblock.sh" ] || fail "$sh" "no DEBUG-LOGCOPY block in carplay_startup.sh"
    [ "$(grep -c DEBUG-LOGCOPY-BEGIN "$STARTUP")" = 1 ] || fail "$sh" "start block is not unique"
    b=$(grep -n DEBUG-LOGCOPY-BEGIN "$STARTUP" | cut -d: -f1); e=$(grep -n '^export LD_PRELOAD' "$STARTUP" | cut -d: -f1)
    x=$(grep -n '^exec ' "$STARTUP" | cut -d: -f1)
    [ "$b" -lt "$e" ] && [ "$e" -lt "$x" ] || fail "$sh" "start block must sit before the hook export and the dio exec"
    rm -rf "$T/h"; mkdir -p "$T/h"; rm -f "$T/hook.out"
    printf '#!/bin/sh
echo "${LD_PRELOAD:-unset}" > "$OUTFILE"
' > "$T/h/carplay_logcopy.sh"; chmod +x "$T/h/carplay_logcopy.sh"
    # (the hook is never really preloaded here: some hosts refuse a bogus LD_PRELOAD; the block must clear it)
    grep -q 'LD_PRELOAD= .*carplay_logcopy.sh.*&$' "$T/hookblock.sh" || fail "$sh" "start block does not clear LD_PRELOAD for a background copier"
    grep -q 'nice' "$T/hookblock.sh" || fail "$sh" "start block does not lower the copier priority"
    grep -q '3>&- 4>&- 5>&- 6>&- 7>&- 8>&- 9>&-' "$T/hookblock.sh" || fail "$sh" "start block does not close inherited fds"
    ! grep -q 'LC_NICE' "$T/hookblock.sh" || fail "$sh" "ambiguous LC_NICE name is back"
    out=$(H=$T/h OUTFILE=$T/hook.out "$sh" -c '. "$1"; echo returned' sh "$T/hookblock.sh")
    [ "$out" = returned ] || fail "$sh" "start block blocked or failed: $out"
    w=0; while [ ! -s "$T/hook.out" ] && [ $w -lt 50 ]; do sleep 0.2 2>/dev/null || sleep 1; w=$((w+1)); done
    [ "$(cat "$T/hook.out" 2>/dev/null)" = unset ] || fail "$sh" "copier started with the hook preloaded or not at all: $(cat "$T/hook.out" 2>/dev/null)"
    rm -rf "$T/h" "$T/hook.out"; mkdir -p "$T/h"      # a release unit: no script, nothing starts, no error
    out=$(H=$T/h "$sh" -c '. "$1"; echo returned' sh "$T/hookblock.sh" 2>&1)
    [ "$out" = returned ] || fail "$sh" "start block noisy without the script: $out"
done
echo "logcopy: append-only new bytes, rotation, no/read-only card, cap eviction, pass cap + skip marker, numbering, single instance, marker exit, startup hook, remount/probe/sync limits, regrown rotation, cap truncation, scope:$shells PASS"
