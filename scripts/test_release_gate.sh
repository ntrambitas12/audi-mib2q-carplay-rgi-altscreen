#!/bin/sh
# The release-build gate of scripts/build_java.sh (verify_release_jar): it must FAIL for a jar that
# holds RgiDiag.class or the text "RGI-DIAG" in any class, and pass for a clean one.  The real jar
# needs the firmware JAR, so the function is extracted from build_java.sh and fed fake jars.
set -eu
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $1"; exit 1; }
PY=$(command -v python3 2>/dev/null || command -v python 2>/dev/null) || fail "no python to build fake jars"
"$PY" -c 'import sys' 2>/dev/null || PY=$(command -v python) || fail "no working python"
command -v unzip >/dev/null 2>&1 || fail "unzip is needed (the gate needs it too)"
sed -n '/^verify_release_jar()/,/^}/p' "$ROOT/scripts/build_java.sh" | tr -d '\r' > "$T/gate.sh"
[ -s "$T/gate.sh" ] || fail "verify_release_jar not found in build_java.sh"

mkjar() {   # mkjar <out> <entry=content>...
    "$PY" - "$@" <<'PYEOF'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED)
for spec in sys.argv[2:]:
    name, _, data = spec.partition("=")
    z.writestr(name, data)
z.close()
PYEOF
}
gate() { sh -c '. "$1"; verify_release_jar "$2"' sh "$T/gate.sh" "$1" > "$T/out.txt" 2>&1; }

mkjar "$T/ok.jar" "com/luka/carplay/rgd/BAPBridge.class=BAPBridge reroute ENTER text" "res/vc-text.bin=RGI-DIAG in a resource is not a class"
gate "$T/ok.jar" || { cat "$T/out.txt"; fail "clean jar rejected"; }
grep -q 'Release check OK' "$T/out.txt" || fail "no OK line"

mkjar "$T/cls.jar" "com/luka/carplay/rgd/RgiDiag.class=x" "com/luka/carplay/rgd/BAPBridge.class=y"
! gate "$T/cls.jar" || fail "jar with RgiDiag.class accepted"
grep -q 'RgiDiag' "$T/out.txt" || fail "RgiDiag.class not named"

mkjar "$T/inner.jar" "com/luka/carplay/rgd/RgiDiag\$1.class=x"
! gate "$T/inner.jar" || fail "jar with RgiDiag inner class accepted"

mkjar "$T/txt.jar" "com/luka/carplay/rgd/BAPBridge.class=xx RGI-DIAG reroute ENTER yy"
! gate "$T/txt.jar" || fail "jar with RGI-DIAG text accepted"
grep -q 'BAPBridge.class' "$T/out.txt" || fail "offending class not named"
# an unguarded call site: BAPBridge.class references RgiDiag in its constant pool, no RgiDiag.class entry
mkjar "$T/ref.jar" "com/luka/carplay/rgd/BAPBridge.class=xx com/luka/carplay/rgd/RgiDiag yy"
! gate "$T/ref.jar" || fail "jar whose BAPBridge.class references RgiDiag accepted"
grep -q 'BAPBridge.class' "$T/out.txt" || fail "referencing class not named"
# ... also from a nested/anonymous class
mkjar "$T/ref2.jar" "com/luka/carplay/rgd/BAPBridge\$1.class=aa RgiDiag bb" "com/luka/carplay/rgd/BAPBridge.class=clean"
! gate "$T/ref2.jar" || fail "jar with RgiDiag reference in a nested class accepted"
# a name that merely contains other letters is fine (no false positive on unrelated classes)
mkjar "$T/fine.jar" "com/luka/carplay/rgd/BAPBridge.class=Rgi Diag reroute"
gate "$T/fine.jar" || { cat "$T/out.txt"; fail "clean jar rejected"; }
mkjar "$T/empty.jar"
! gate "$T/empty.jar" || fail "empty jar accepted"
mkjar "$T/nocls.jar" "res/vc-text.bin=data" "META-INF/MANIFEST.MF=x"
! gate "$T/nocls.jar" || fail "jar without any .class accepted"
echo "release gate: RgiDiag.class, RgiDiag references and RGI-DIAG text fail the build, a clean jar passes PASS"
