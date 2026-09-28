#!/bin/sh
# DEBUG-ONLY "flight recorder": every CP_LC_INTERVAL seconds (15) copy only the NEW bytes of the
# CarPlay logs in /tmp to the SD card, so the log of a drive survives a power cut or a crash.
# Scope: /tmp/carplay_*.log (carplay_java.log first - it is the one that matters when the pass
# budget is short; the hook's carplay_hook.log, carplay_wrapper.log, ...), their rotated .1 files
# and /tmp/maneuver_render.log.  Nothing else in /tmp is touched.
#
#   <card>/carplay_logs/live_NNN/<log name>     append-only, one file per log
#   <card>/carplay_logs/live_NNN/info.txt        unit clock, build, uptime (written on start)
#
# Started (background, `nice`d, LD_PRELOAD cleared) by carplay_startup.sh; it is NOT part of the
# hook / renderer supervision and never touches them.  It runs only while the debug marker
# /mnt/app/carplay_verbose exists and exits by itself (cleanly) once the marker is gone.
# Release builds do not ship this file at all (CARPLAY_RELEASE=1, see docs/deploy/install.md).
#
# QNX 6.5 /bin/sh is ksh (pdksh) - this script stays inside its portable subset (no arrays, no
# [[ ]], no local, no GNU tools).  It never exits on an error and never blocks: a missing card is
# just "skip this pass", and every card path is tested right before it is written.
#
# Test overrides (host only): CP_LOG_SRC CP_LC_CARDS CP_LC_MARKER CP_LC_HOOKS CP_LC_PID
# CP_LC_INTERVAL CP_LC_PASSES CP_LC_PASS_BYTES CP_LC_CAP_BYTES CP_LC_MOUNT CP_LC_REMOUNT_EVERY.
# Copyright (c) 2026 LuKa (@LuKa_dev)
PATH=/proc/boot:/bin:/usr/bin:/usr/sbin:/sbin:/mnt/app/armle/bin:/mnt/app/armle/usr/bin:/mnt/app/armle/sbin:/mnt/app/armle/usr/sbin
export PATH
unset LD_PRELOAD

SRC=${CP_LOG_SRC:-/tmp}
MARKER=${CP_LC_MARKER:-/mnt/app/carplay_verbose}
HOOKS=${CP_LC_HOOKS:-/mnt/app/root/hooks}
PIDFILE=${CP_LC_PID:-/tmp/carplay_logcopy.pid}     # /tmp is /dev/shmem: files only, no directories
INTERVAL=${CP_LC_INTERVAL:-15}
PASSES=${CP_LC_PASSES:-0}                          # 0 = run until the marker is removed
PASS_BYTES=${CP_LC_PASS_BYTES:-524288}             # most bytes copied per pass (512 KiB)
CAP_BYTES=${CP_LC_CAP_BYTES:-20971520}             # all live_* folders together (20 MiB)
ROLL_BYTES=$((CAP_BYTES / 4))                      # one live_* folder rolls over at this size
MOUNT=${CP_LC_MOUNT:-mount}
CANDS=${CP_LC_CARDS:-/fs/sda0 /fs/sdb0 /fs/usb0_0 /net/mmx/fs/sda0 /net/mmx/fs/sdb0 /net/mmx/fs/usb0_0}

CARD=          # mount point of the card in use
BASE=          # $CARD/carplay_logs
LIVE=          # $BASE/live_NNN in use
LIVE_N=0       # its number (0 = none chosen yet in this run)
BUDGET=0
WROTE=0
PASSN=0            # passes so far in this run
CARD_OK=0          # 1 = the card in use was probed writable and nothing has failed since
PENDING_SYNC=0     # 1 = bytes written since the last sync
REMOUNT_EVERY=${CP_LC_REMOUNT_EVERY:-20}          # a read-only card is remounted at most every 20 passes

# ---- helpers ----------------------------------------------------------------------------------
# echo, not ":" - a failed redirection on a special builtin (":") would END the whole script.
can_write() { { echo probe > "$1/.lc_wtest" && rm -f "$1/.lc_wtest"; } 2>/dev/null; }

file_size() {   # file_size <file>: bytes (0 if unreadable)
    set -- $(wc -c < "$1" 2>/dev/null)
    case ${1:-} in ''|*[!0-9]*) echo 0 ;; *) echo "$1" ;; esac
}

is_live_name() {   # live_ followed by digits only: nothing else under carplay_logs is ever touched
    case $1 in live_) return 1 ;; live_*[!0-9]*) return 1 ;; live_*) return 0 ;; *) return 1 ;; esac
}

num_of() {      # num_of <name>: digits after the last '_' without leading zeros (008 is octal to ksh)
    v=${1##*_}
    case $v in ''|*[!0-9]*) echo 0; return ;; esac
    while :; do case $v in 0?*) v=${v#0} ;; *) break ;; esac; done
    echo "$v"
}

dir_bytes() {   # dir_bytes <dir>: total size of the (flat) files in it
    total=0
    ls -l "$1" 2>/dev/null | while read perm links owner group size rest; do
        case $perm in total|d*) continue ;; esac
        case $size in ''|*[!0-9]*) continue ;; esac
        echo "$size"
    done | { while read n; do total=$((total + n)); done; echo "$total"; }
}

write_info() {  # write_info <dir>
    {
        echo "live        $LIVE_N"
        echo "started     $(date 2>&1)"
        up=$(uptime 2>/dev/null)
        [ -n "$up" ] || up=$(pidin info 2>/dev/null | sed -n '1,2p' | tr '
' ' ')
        echo "uptime      ${up:-unknown}"
        bid=$(sed -n '2p' "$HOOKS/carplay_build_mode" 2>/dev/null)
        echo "build       ${bid#build_id=}"
        echo "mode        $(sed -n '1p' "$HOOKS/carplay_build_mode" 2>/dev/null)"
        echo "card        $CARD"
        echo "script      $0"
        echo "interval    ${INTERVAL}s  pass-cap ${PASS_BYTES}B  cap ${CAP_BYTES}B"
    } > "$1/info.txt" 2>/dev/null
}

# ---- card discovery (never waits: every candidate is a plain test) ----------------------------
find_card() {
    # A card that has been probed writable stays in use until a write fails (do_pass clears
    # CARD_OK) - no write probe on every pass.
    if [ "$CARD_OK" = 1 ] && [ -n "$CARD" ] && [ -d "$CARD/carplay_logs" ]; then return 0; fi
    CARD_OK=0; CARD=; BASE=
    for m in $CANDS; do
        fc_k=$(echo "$m" | tr -c 'A-Za-z0-9\n' '_')
        if [ ! -d "$m/carplay_logs" ] && [ ! -d "$m/mod" ]; then     # not our card / not there:
            eval "rm_$fc_k="                                          # forget its remount history
            continue
        fi
        if ! can_write "$m"; then
            # Read-only: try `mount -uw` at most once every REMOUNT_EVERY passes per mount.
            eval "fc_last=\${rm_$fc_k:-}"
            if [ -n "$fc_last" ] && [ $((PASSN - fc_last)) -lt "$REMOUNT_EVERY" ]; then continue; fi
            eval "rm_$fc_k=$PASSN"
            $MOUNT -uw "$m" 2>/dev/null
            can_write "$m" || continue
        fi
        [ -d "$m/carplay_logs" ] || mkdir "$m/carplay_logs" 2>/dev/null
        [ -d "$m/carplay_logs" ] || continue
        CARD=$m; BASE=$m/carplay_logs; CARD_OK=1
        return 0
    done
    return 1
}

new_live() {    # new_live <number>: create live_NNN (and its info.txt); sets LIVE / LIVE_N
    nl_n=$1
    case $nl_n in ?) nl_nn=00$nl_n ;; ??) nl_nn=0$nl_n ;; *) nl_nn=$nl_n ;; esac
    [ -d "$BASE" ] || return 1
    if [ ! -d "$BASE/live_$nl_nn" ]; then mkdir "$BASE/live_$nl_nn" 2>/dev/null || return 1; fi
    LIVE=$BASE/live_$nl_nn; LIVE_N=$nl_n
    write_info "$LIVE"
    return 0
}

pick_live() {   # first use in this run: highest existing live_NNN + 1 (deleted numbers are never reused)
    last=0
    for d in "$BASE"/live_*; do
        [ -d "$d" ] && is_live_name "${d##*/}" || continue
        v=$(num_of "${d##*/}")
        [ "$v" -gt "$last" ] && last=$v
    done
    new_live $((last + 1))
}

# ---- cap: delete the oldest live_* folder first, never anything else --------------------------
enforce_cap() {
    [ -d "$BASE" ] || return 0
    while :; do
        total=0; oldest=; oldest_n=-1
        for d in "$BASE"/live_*; do
            [ -d "$d" ] && is_live_name "${d##*/}" || continue
            sz=$(dir_bytes "$d"); total=$((total + sz))
            v=$(num_of "${d##*/}")
            [ "$d" = "$LIVE" ] && continue
            if [ "$oldest_n" -lt 0 ] || [ "$v" -lt "$oldest_n" ]; then oldest=$d; oldest_n=$v; fi
        done
        [ "$total" -gt "$CAP_BYTES" ] || return 0
        [ -n "$oldest" ] || return 0                 # only the folder in use is left
        rm -f "$oldest"/* 2>/dev/null; rmdir "$oldest" 2>/dev/null || return 0
    done
}

# ---- byte-exact copy of [start, start+count) of a file, appended to dest ----------------------
copy_range() {  # copy_range <src> <start> <count> <dest>
    cr_src=$1; cr_st=$2; cr_cnt=$3; cr_dst=$4
    cr_pad=$(( (4096 - cr_st % 4096) % 4096 ))
    [ "$cr_pad" -gt "$cr_cnt" ] && cr_pad=$cr_cnt
    if [ "$cr_pad" -gt 0 ]; then
        { dd if="$cr_src" bs=1 skip="$cr_st" count="$cr_pad" >> "$cr_dst"; } 2>/dev/null || return 1
    fi
    cr_st=$((cr_st + cr_pad)); cr_cnt=$((cr_cnt - cr_pad))
    cr_blocks=$((cr_cnt / 4096))
    if [ "$cr_blocks" -gt 0 ]; then
        { dd if="$cr_src" bs=4096 skip=$((cr_st / 4096)) count="$cr_blocks" >> "$cr_dst"; } 2>/dev/null || return 1
    fi
    cr_st=$((cr_st + cr_blocks * 4096)); cr_rem=$((cr_cnt - cr_blocks * 4096))
    if [ "$cr_rem" -gt 0 ]; then
        { dd if="$cr_src" bs=1 skip="$cr_st" count="$cr_rem" >> "$cr_dst"; } 2>/dev/null || return 1
    fi
    return 0
}

# append_new <src> <name> <from>: copy [from, size) under the pass budget; sets NEWOFF
# (where to continue).  Head of a small backlog is copied first; a backlog bigger than a whole
# pass keeps only its tail and leaves a marker line.  A missing card dir stops the copy.
append_new() {
    an_src=$1; an_name=$2; an_from=$3
    NEWOFF=$an_from
    an_size=$(file_size "$an_src")
    an_want=$((an_size - an_from))
    [ "$an_want" -gt 0 ] || return 0
    [ "$BUDGET" -gt 0 ] || return 0                       # budget used up: continue next pass
    [ -d "$LIVE" ] || return 1
    an_dest=$LIVE/$an_name
    if [ "$an_want" -le "$BUDGET" ]; then
        copy_range "$an_src" "$an_from" "$an_want" "$an_dest" || return 1
        NEWOFF=$an_size; BUDGET=$((BUDGET - an_want)); WROTE=1
    elif [ "$an_want" -le "$PASS_BYTES" ]; then
        copy_range "$an_src" "$an_from" "$BUDGET" "$an_dest" || return 1
        NEWOFF=$((an_from + BUDGET)); WROTE=1; BUDGET=0
    else
        an_skip=$((an_want - BUDGET))
        [ -d "$LIVE" ] || return 1
        printf '\n--- carplay_logcopy: skipped %s bytes of %s ---\n' "$an_skip" "$an_name" >> "$an_dest" 2>/dev/null || return 1
        copy_range "$an_src" $((an_size - BUDGET)) "$BUDGET" "$an_dest" || return 1
        NEWOFF=$an_size; WROTE=1; BUDGET=0
    fi
    return 0
}

get_off() { eval "GOFF=\${off_$1:-0}"; }
set_off() { eval "off_$1=$2"; }

sig64() {       # checksum of the first 64 bytes; empty if the file is shorter (or cksum is missing)
    [ "$(file_size "$1")" -ge 64 ] || return 0
    dd if="$1" bs=64 count=1 2>/dev/null | cksum 2>/dev/null | { read sg_c sg_rest; echo "$sg_c"; }
}

# copy_log <path of X.log>.  Rotation (X.log -> X.log.1) is detected by X.log.1 changing (size or
# head checksum), even when the new X.log has already regrown past the old offset; the unread tail
# of the old file is finished from X.log.1 first, then the new X.log is read from 0.  In-place
# truncation (the monitor's cp_cap_log) is detected by the offset passing the size or by the head
# checksum changing.
copy_log() {
    cl_f=$1; cl_name=${cl_f##*/}
    cl_key=$(echo "$cl_name" | tr -c 'A-Za-z0-9\n' '_')
    cl_size=$(file_size "$cl_f")
    get_off "$cl_key"; cl_off=$GOFF
    if [ -f "$cl_f.1" ]; then
        cl_sz1=$(file_size "$cl_f.1"); cl_s1=$cl_sz1:$(sig64 "$cl_f.1")
    else
        cl_sz1=0; cl_s1=none
    fi
    eval "cl_seen=\${seen_$cl_key:-0}"
    if [ "$cl_seen" = 0 ]; then
        # First sight in this run: the rotated older half (X.log.1) is history - keep it once.
        if [ -f "$cl_f.1" ]; then
            get_off "${cl_key}_1"; cl_o1=$GOFF
            append_new "$cl_f.1" "$cl_name.1" "$cl_o1" && set_off "${cl_key}_1" "$NEWOFF"
            [ "$NEWOFF" -lt "$cl_sz1" ] && return 0            # backlog not finished: next pass
        fi
        eval "seen_$cl_key=1; p1_$cl_key=\$cl_s1"
    fi
    eval "cl_tl=\${tl_$cl_key:-}"                             # unfinished tail of a rotated file
    eval "cl_p1=\${p1_$cl_key:-none}"
    if [ -z "$cl_tl" ] && [ "$cl_s1" != none ] && [ "$cl_s1" != "$cl_p1" ]; then
        # X.log.1 changed: rotation.  Its content is the file we were reading (if its head matches
        # what we saw last), so the unread tail starts at our offset; otherwise take all of it.
        eval "p1_$cl_key=\$cl_s1"
        eval "cl_os=\${sg_$cl_key:-}"
        cl_start=$cl_off
        if [ -n "$cl_os" ] && [ "${cl_s1#*:}" != "$cl_os" ]; then cl_start=0; fi
        if [ "$cl_sz1" -gt "$cl_start" ]; then
            [ -d "$LIVE" ] && printf '\n--- carplay_logcopy: %s rotated ---\n' "$cl_name" >> "$LIVE/$cl_name" 2>/dev/null
            cl_tl=$cl_start
        fi
        cl_off=0; set_off "$cl_key" 0; eval "sg_$cl_key="
    fi
    if [ -n "$cl_tl" ]; then
        append_new "$cl_f.1" "$cl_name" "$cl_tl" || return 1
        if [ "$NEWOFF" -lt "$cl_sz1" ]; then eval "tl_$cl_key=$NEWOFF"; return 0; fi   # finish the old tail first
        eval "tl_$cl_key="
        cl_off=0; set_off "$cl_key" 0
    fi
    cl_cur=$(sig64 "$cl_f")
    eval "cl_sg=\${sg_$cl_key:-}"
    if [ "$cl_size" -lt "$cl_off" ] || { [ -n "$cl_sg" ] && [ -n "$cl_cur" ] && [ "$cl_cur" != "$cl_sg" ]; }; then
        # Truncated in place (or replaced): start again from byte 0.
        [ -d "$LIVE" ] && printf '\n--- carplay_logcopy: %s truncated ---\n' "$cl_name" >> "$LIVE/$cl_name" 2>/dev/null
        cl_off=0; set_off "$cl_key" 0
    fi
    append_new "$cl_f" "$cl_name" "$cl_off" || return 1
    set_off "$cl_key" "$NEWOFF"
    eval "sg_$cl_key=\$cl_cur"
    return 0
}

do_pass() {
    PASSN=$((PASSN + 1))
    find_card || return 0                                # no writable card: skip silently
    if [ "$LIVE_N" = 0 ]; then
        pick_live || { CARD_OK=0; return 0; }
    elif [ ! -d "$LIVE" ]; then
        new_live "$LIVE_N" || { CARD_OK=0; return 0; }   # card swapped: same number on this card
    fi
    BUDGET=$PASS_BYTES; WROTE=0
    dp_first=1
    for dp_f in "$SRC/carplay_java.log" "$SRC"/carplay_*.log "$SRC/maneuver_render.log"; do
        dp_n=${dp_f##*/}
        if [ "$dp_n" = carplay_java.log ]; then [ "$dp_first" = 1 ] || continue; fi
        dp_first=0
        [ -f "$dp_f" ] || continue
        [ -d "$LIVE" ] || { CARD_OK=0; break; }          # card pulled meanwhile: stop this pass
        copy_log "$dp_f" || { CARD_OK=0; break; }        # a failed write: probe the card again next pass
    done
    if [ "$WROTE" = 1 ]; then
        PENDING_SYNC=1
        [ -d "$LIVE" ] && [ "$(dir_bytes "$LIVE")" -gt "$ROLL_BYTES" ] && new_live $((LIVE_N + 1))
        enforce_cap
    fi
    # After EVERY pass that wrote something (every 15 s while the logs grow): the point of the
    # recorder is the last moments before a power-off, and a few KB per pass costs the card nothing.
    # A pass that wrote nothing does not sync.
    if [ "$PENDING_SYNC" = 1 ]; then
        sync; PENDING_SYNC=0
    fi
    return 0
}

# ---- single instance: exclusive create of the pid file (noclobber), stale pid taken over -------
cleanup() {
    if [ "$PENDING_SYNC" = 1 ]; then          # only after a pass cut short by TERM/exit
        PENDING_SYNC=0                        # the TERM trap and the EXIT trap both run this: sync once
        [ -n "$CARD" ] && [ -d "$CARD/carplay_logs" ] && sync     # never touch a card that was pulled
    fi
    [ "$(cat "$PIDFILE" 2>/dev/null)" = "$$" ] && rm -f "$PIDFILE"
}
own_pidfile() {
    tries=0
    while [ "$tries" -lt 3 ]; do
        if ( set -o noclobber; echo "$$" > "$PIDFILE" ) 2>/dev/null; then return 0; fi
        old=$(cat "$PIDFILE" 2>/dev/null)
        case $old in
            ''|*[!0-9]*) rm -f "$PIDFILE" ;;                       # garbage: take over
            "$$") return 0 ;;
            *) kill -0 "$old" 2>/dev/null && return 1 ; rm -f "$PIDFILE" ;;   # live owner / stale
        esac
        tries=$((tries + 1))
    done
    return 1
}

# Host tests source this file for its functions only.
[ -n "${CP_LC_SOURCE_ONLY:-}" ] && return 0
own_pidfile || exit 0
trap 'cleanup; exit 0' 1 2 15
trap 'cleanup' 0

# ---- main loop --------------------------------------------------------------------------------
pass_no=0
while :; do
    [ -e "$MARKER" ] || exit 0                           # debug marker removed: stop cleanly
    do_pass
    pass_no=$((pass_no + 1))
    [ "$PASSES" -gt 0 ] && [ "$pass_no" -ge "$PASSES" ] && exit 0
    sleep "$INTERVAL"
done
