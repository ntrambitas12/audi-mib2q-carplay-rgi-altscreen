#!/bin/sh
# The M.I.B. installer end to end in a sandbox: /mnt is redirected to a temp dir
# and mount is a no-op. Checks the flat release layout (assets dropped straight into
# mod/carplay/), the root/ tree layout, file modes, the SI child and dio_manager.json
# edits with their .carplay-stock backups, and uninstall back to stock.  Then the debug vs
# release payloads (scripts/package_release.sh): a debug install adds the SD flight recorder and
# the persistent verbose marker; a release install over it removes both (and stops a running
# copier); a debug payload missing its recorder is refused whole; uninstall removes every debug piece.
# Runs under every POSIX/ksh shell available here (QNX 6.5 /bin/sh is pdksh).
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL ($1): $2"; exit 1; }

if [ "$(uname)" = Darwin ]; then
    mode() { stat -f %Lp "$1"; }
else
    mode() { stat -c %a "$1"; }
fi

stock_si='{
    "children": {
        "carplay": {
            "exec": "dio_manager",
            "path": "/mnt/app/eso/bin/apps"
        },
        "other": {
            "exec": "o"
        }
    }
}'
stock_dio='        ## 0x5000
        "MessagesSentByAccessory":["0x5000", "0x4C05"],
        ## 0x5001
        "MessagesReceivedFromDevice":["0x5001", "0x4C04"],'

ASSETS="libcarplay_hook.so maneuver_render flag_atlas.rgba carplay_startup.sh carplay_monitor.sh carplay_processes.sh carplay_cleanup.sh carplay_hook.jar"

run() {   # $1 shell, $2 layout (flat|tree), $3 action
    S=$T/$2; mkdir -p "$S/mod/carplay" "$S/mnt/system/etc/eso/production"
    sed -e "s#/mnt/#$S/mnt/#g" -e "s#\"/\\\${f\#./}\"#\"$S/\\\${f\#./}\"#" \
        -e 's#^\( *\)mount -uw#\1: mount -uw#' \
        "$ROOT/install_MoreIncredibleBash/mod/custom.sh" > "$S/mod/custom.sh"
    mkdir -p "$S/tmp"
    CP_TMP="$S/tmp" "$1" "$S/mod/custom.sh" "$3" > "$S/out.log" 2>&1 || { cat "$S/out.log"; fail "$1 $2" "$3 returned non-zero"; }
}

stage_payload() {   # stage_payload <debug|release>: the real scripts/package_release.sh on a fake build dir
    B=$T/fakebuild; mkdir -p "$B"
    for f in libcarplay_hook.so maneuver_render carplay_hook.jar; do echo "$f" > "$B/$f"; done
    echo atlas > "$T/atlas.rgba"
    echo "$1" > "$B/carplay_hook.mode"
    if [ "$1" = release ]; then rel=1; else rel=0; fi
    CARPLAY_RELEASE=$rel CARPLAY_BUILD_ID=t1 CP_BUILD_DIR=$B CP_ATLAS=$T/atlas.rgba CP_PAYLOAD_OUT=$T/pkg_$1 \
        sh "$ROOT/scripts/package_release.sh" > "$T/pack_$1.log" 2>&1 || { cat "$T/pack_$1.log"; return 1; }
}

for sh in /bin/ksh /bin/mksh /bin/dash /bin/sh; do
    [ -x "$sh" ] || continue
    for layout in flat tree; do
        rm -rf "$T/$layout"; S=$T/$layout
        mkdir -p "$S/mod/carplay" "$S/mnt/system/etc/eso/production"
        printf '%s\n' "$stock_si" > "$S/mnt/system/etc/eso/production/smartphone_integrator.json"
        printf '%s\n' "$stock_dio" > "$S/mnt/system/etc/eso/production/dio_manager.json"
        cp "$ROOT/deploy/smartphone_integrator/carplay_child.json" "$S/mod/carplay/"
        for a in $ASSETS; do
            case $a in carplay_hook.jar) d=mnt/app/eso/hmi/lsd/jars ;; *) d=mnt/app/root/hooks ;; esac
            if [ "$layout" = flat ]; then echo "$a" > "$S/mod/carplay/$a"
            else mkdir -p "$S/mod/carplay/root/$d"; echo "$a" > "$S/mod/carplay/root/$d/$a"; fi
        done
        [ "$layout" = flat ] && echo release > "$S/mod/carplay/BUILD_MODE"
        echo junk > "$S/mod/carplay/README.txt"   # unknown flat files are ignored
        J=$S/mnt/app/eso/hmi/lsd/jars; mkdir -p "$J"; echo nav > "$J/NavActiveIgnore.jar"   # M.I.B. conflict jar

        run "$sh" "$layout" install
        H=$S/mnt/app/root/hooks
        for a in libcarplay_hook.so maneuver_render carplay_startup.sh carplay_monitor.sh carplay_processes.sh carplay_cleanup.sh; do
            [ "$(cat "$H/$a")" = "$a" ] || fail "$sh $layout" "$a not installed"
            [ "$(mode "$H/$a")" = 755 ] || fail "$sh $layout" "$a mode $(mode "$H/$a")"
        done
        [ "$(mode "$H/flag_atlas.rgba")" = 644 ] || fail "$sh $layout" "atlas mode"
        [ "$(mode "$S/mnt/app/eso/hmi/lsd/jars/carplay_hook.jar")" = 644 ] || fail "$sh $layout" "jar not installed/mode"
        [ ! -e "$H/README.txt" ] || fail "$sh $layout" "unknown flat file installed"
        [ ! -e "$H/carplay_logcopy.sh" ] && [ ! -e "$S/mnt/app/carplay_verbose" ] || fail "$sh $layout" "release install created a debug piece"
        [ "$layout" = tree ] || [ "$(cat "$H/carplay_build_mode")" = release ] || fail "$sh $layout" "build mode not recorded"
        [ ! -e "$J/NavActiveIgnore.jar" ] || fail "$sh $layout" "NavActiveIgnore.jar not removed"
        P=$S/mnt/system/etc/eso/production
        grep -q carplay_startup.sh "$P/smartphone_integrator.json" || fail "$sh $layout" "SI child not replaced"
        grep -q '"exec": "o"' "$P/smartphone_integrator.json" || fail "$sh $layout" "other SI child lost"
        [ "$(grep -o '"0x520[0-4]"' "$P/dio_manager.json" | wc -l | tr -d ' ')" = 5 ] || fail "$sh $layout" "dio IDs"
        [ -e "$P/smartphone_integrator.json.carplay-stock" ] && [ -e "$P/dio_manager.json.carplay-stock" ] \
            || fail "$sh $layout" "stock backups missing"
        ls "$S/mnt/app/root/hooks" | grep -q carplay-new && fail "$sh $layout" "temporary file left"

        run "$sh" "$layout" install   # re-run: backups must stay stock
        grep -q '"exec": "dio_manager"' "$P/smartphone_integrator.json.carplay-stock" || fail "$sh $layout" "backup overwritten"

        run "$sh" "$layout" uninstall
        for a in $ASSETS; do
            [ ! -e "$H/$a" ] && [ ! -e "$S/mnt/app/eso/hmi/lsd/jars/$a" ] || fail "$sh $layout" "$a not removed"
        done
        [ "$(cat "$P/smartphone_integrator.json")" = "$stock_si" ] || fail "$sh $layout" "SI json not restored"
        [ "$(cat "$P/dio_manager.json")" = "$stock_dio" ] || fail "$sh $layout" "dio_manager.json not restored"
    done

    # ---- debug vs release ----
    for m in debug release; do
        rm -rf "$T/pkg_$m"
    done
    for m in debug release; do
        stage_payload "$m" || fail "$sh" "package $m"
    done
    dbg_payload=$T/pkg_debug/mod/carplay; rel_payload=$T/pkg_release/mod/carplay
    [ -x "$dbg_payload/carplay_logcopy.sh" ] && grep -q DEBUG-LOGCOPY-BEGIN "$dbg_payload/carplay_startup.sh" \
        && [ "$(sed -n 1p "$dbg_payload/BUILD_MODE")" = debug ] || fail "$sh" "debug payload incomplete"
    [ ! -e "$rel_payload/carplay_logcopy.sh" ] && [ "$(sed -n 1p "$rel_payload/BUILD_MODE")" = release ] \
        || fail "$sh" "release payload has the recorder or lacks its mode"
    ! grep -rq 'logcopy\|LOGCOPY' "$rel_payload" || fail "$sh" "release payload still mentions the recorder"
    # a jar built in the other mode must never be packaged
    echo release > "$T/fakebuild/carplay_hook.mode"
    ! CARPLAY_RELEASE=0 CARPLAY_BUILD_ID=t1 CP_BUILD_DIR=$T/fakebuild CP_ATLAS=$T/atlas.rgba CP_PAYLOAD_OUT=$T/pkg_x \
        sh "$ROOT/scripts/package_release.sh" > /dev/null 2>&1 || fail "$sh" "debug package accepted a release jar"

    # debug install: recorder + persistent verbose marker + mode record
    S=$T/unit; rm -rf "$S"; mkdir -p "$S/mod" "$S/mnt/system/etc/eso/production" "$S/tmp"
    printf '%s\n' "$stock_si" > "$S/mnt/system/etc/eso/production/smartphone_integrator.json"
    printf '%s\n' "$stock_dio" > "$S/mnt/system/etc/eso/production/dio_manager.json"
    cp -R "$dbg_payload" "$S/mod/carplay"
    run "$sh" unit install
    H=$S/mnt/app/root/hooks
    [ -x "$H/carplay_logcopy.sh" ] && [ "$(mode "$H/carplay_logcopy.sh")" = 755 ] || fail "$sh debug" "recorder not installed 755"
    [ -e "$S/mnt/app/carplay_verbose" ] || fail "$sh debug" "verbose marker not created"
    [ "$(sed -n 1p "$H/carplay_build_mode")" = debug ] || fail "$sh debug" "build mode not recorded"
    grep -q DEBUG-LOGCOPY-BEGIN "$H/carplay_startup.sh" || fail "$sh debug" "startup hook missing"
    [ ! -e "$H/BUILD_MODE" ] || fail "$sh debug" "payload marker installed under its card name"

    # a debug payload missing its recorder is refused whole: nothing is written
    S2=$T/unit2; rm -rf "$S2"; mkdir -p "$S2/mod" "$S2/mnt/system/etc/eso/production"
    cp -R "$dbg_payload" "$S2/mod/carplay"; rm "$S2/mod/carplay/carplay_logcopy.sh"
    mkdir -p "$S2/tmp"
    sed -e "s#/mnt/#$S2/mnt/#g" -e 's#^\( *\)mount -uw#\1: mount -uw#' "$ROOT/install_MoreIncredibleBash/mod/custom.sh" > "$S2/mod/custom.sh"
    if CP_TMP="$S2/tmp" "$sh" "$S2/mod/custom.sh" install > "$S2/out.log" 2>&1; then fail "$sh debug" "incomplete debug payload accepted"; fi
    grep -q 'missing in .*carplay_logcopy.sh' "$S2/out.log" || fail "$sh debug" "missing recorder not named: $(cat "$S2/out.log")"
    [ ! -e "$S2/mnt/app/root/hooks/carplay_startup.sh" ] || fail "$sh debug" "partial install"

    # release install over the debug unit: every debug piece goes, a running copier is stopped
    sleep 60 & lc=$!
    echo "$lc" > "$S/tmp/carplay_logcopy.pid"
    rm -rf "$S/mod/carplay"; cp -R "$rel_payload" "$S/mod/carplay"
    run "$sh" unit install
    [ ! -e "$H/carplay_logcopy.sh" ] || fail "$sh release-over-debug" "recorder left on the unit"
    [ ! -e "$S/mnt/app/carplay_verbose" ] || fail "$sh release-over-debug" "verbose marker left"
    [ ! -e "$S/tmp/carplay_logcopy.pid" ] || fail "$sh release-over-debug" "copier pid file left"
    w=0; while kill -0 "$lc" 2>/dev/null && [ $w -lt 25 ]; do sleep 0.2 2>/dev/null || sleep 1; w=$((w+1)); done
    if kill -0 "$lc" 2>/dev/null; then kill "$lc" 2>/dev/null; fail "$sh release-over-debug" "running copier not stopped"; fi
    [ "$(sed -n 1p "$H/carplay_build_mode")" = release ] || fail "$sh release-over-debug" "mode not updated"
    ! grep -q logcopy "$H/carplay_startup.sh" || fail "$sh release-over-debug" "startup hook left"
    [ -f "$H/libcarplay_hook.so" ] && [ -f "$S/mnt/app/eso/hmi/lsd/jars/carplay_hook.jar" ] || fail "$sh release-over-debug" "release files missing"
    grep -q 'removed debug piece' "$S/out.log" || fail "$sh release-over-debug" "cleanup not reported"

    # release install through the root/ tree (no BUILD_MODE in the payload) over a debug unit:
    # the debug pieces AND the old "debug" mode record go
    rm -rf "$S/mod/carplay"; cp -R "$dbg_payload" "$S/mod/carplay"; run "$sh" unit install
    [ "$(sed -n 1p "$H/carplay_build_mode")" = debug ] && [ -e "$S/mnt/app/carplay_verbose" ] || fail "$sh tree-over-debug" "debug fixture not installed"
    echo hand-made > "$S/mnt/app/carplay_verbose"
    rm -rf "$S/mod/carplay"; mkdir -p "$S/mod/carplay"; cp "$rel_payload/carplay_child.json" "$S/mod/carplay/"
    for a in libcarplay_hook.so maneuver_render flag_atlas.rgba carplay_startup.sh carplay_monitor.sh carplay_processes.sh carplay_cleanup.sh carplay_hook.jar; do
        case $a in carplay_hook.jar) d=mnt/app/eso/hmi/lsd/jars ;; *) d=mnt/app/root/hooks ;; esac
        mkdir -p "$S/mod/carplay/root/$d"; cp "$rel_payload/$a" "$S/mod/carplay/root/$d/$a"
    done
    run "$sh" unit install
    [ ! -e "$H/carplay_logcopy.sh" ] && [ ! -e "$S/mnt/app/carplay_verbose" ] || fail "$sh tree-over-debug" "debug pieces left (a hand-made verbose marker included)"
    [ ! -e "$H/carplay_build_mode" ] || [ "$(sed -n 1p "$H/carplay_build_mode")" = release ] \
        || fail "$sh tree-over-debug" "stale debug build-mode record: $(cat "$H/carplay_build_mode")"

    # BUILD_MODE parsing: no trailing newline, CRLF and an empty file all parse; empty means release
    n=0
    for spec in 'debug|debug' 'debug\r\n|debug' 'release|release' '|release'; do
        n=$((n+1)); U=$T/mode$n; rm -rf "$U"; mkdir -p "$U/mod" "$U/mnt/system/etc/eso/production" "$U/tmp"
        printf '%s\n' "$stock_si" > "$U/mnt/system/etc/eso/production/smartphone_integrator.json"
        printf '%s\n' "$stock_dio" > "$U/mnt/system/etc/eso/production/dio_manager.json"
        cp -R "$dbg_payload" "$U/mod/carplay"
        printf "${spec%%|*}" > "$U/mod/carplay/BUILD_MODE"
        run "$sh" "mode$n" install
        if [ "${spec##*|}" = debug ]; then
            [ -x "$U/mnt/app/root/hooks/carplay_logcopy.sh" ] && [ -e "$U/mnt/app/carplay_verbose" ] || fail "$sh BUILD_MODE" "'$spec' not parsed as debug"
        else
            [ ! -e "$U/mnt/app/root/hooks/carplay_logcopy.sh" ] && [ ! -e "$U/mnt/app/carplay_verbose" ] || fail "$sh BUILD_MODE" "'$spec' not parsed as release"
        fi
    done

    # uninstall (payload-driven) after a debug install removes the debug pieces too
    rm -rf "$S/mod/carplay"; cp -R "$dbg_payload" "$S/mod/carplay"; run "$sh" unit install
    echo 4242424 > "$S/tmp/carplay_logcopy.pid"      # stale pid: must be removed, must not kill anything
    run "$sh" unit uninstall
    [ ! -e "$H/carplay_logcopy.sh" ] && [ ! -e "$H/carplay_build_mode" ] && [ ! -e "$S/mnt/app/carplay_verbose" ] \
        && [ ! -e "$S/tmp/carplay_logcopy.pid" ] || fail "$sh uninstall" "debug pieces left: $(ls "$H" "$S/mnt/app" "$S/tmp" | tr '\n' ' ')"
    # the standalone uninstaller (needs no payload) does the same
    rm -rf "$S/mod/carplay"; cp -R "$dbg_payload" "$S/mod/carplay"; run "$sh" unit install
    echo 4242424 > "$S/tmp/carplay_logcopy.pid"
    mkdir -p "$S/un"
    sed -e "s#/mnt/#$S/mnt/#g" -e 's#^\( *\)mount -uw#\1: mount -uw#' "$ROOT/uninstall_MoreIncredibleBash/mod/custom.sh" > "$S/un/custom.sh"
    CP_TMP="$S/tmp" "$sh" "$S/un/custom.sh" > "$S/un.log" 2>&1 || { cat "$S/un.log"; fail "$sh standalone uninstall" "returned non-zero"; }
    [ ! -e "$H/carplay_logcopy.sh" ] && [ ! -e "$H/carplay_build_mode" ] && [ ! -e "$S/mnt/app/carplay_verbose" ] \
        && [ ! -e "$S/tmp/carplay_logcopy.pid" ] && [ ! -e "$H/carplay_startup.sh" ] \
        || fail "$sh standalone uninstall" "debug pieces left: $(ls "$H" "$S/mnt/app" "$S/tmp" | tr '\n' ' ')"
    shells="${shells:-} $sh"
done
echo "M.I.B. installer flat + tree install/uninstall, NavActiveIgnore removal, debug/release payloads + cleanup:$shells PASS"
