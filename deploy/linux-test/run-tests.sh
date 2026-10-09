#!/usr/bin/env bash
# Runs INSIDE the container of deploy/test-linux.ps1: unpacks the source tree (made with git archive, so the files have exactly the modes
# and line endings that a Linux checkout in CI has), runs the given test classes and prints one line per class.
#
#   run-tests.sh <comma-separated test classes> <comma-separated classes that must run with NO skipped test> <offline|online>
#
# Exit status: 0 = every selected test passed AND every "must run" class really ran (tests > 0, skipped = 0); 1 = otherwise.
set -uo pipefail

tests=${1:?test classes}
must_run=${2:-}
mode=${3:-offline}

work=/home/runner/work
rm -rf "$work" && mkdir "$work"
tar -x -f /in/src.tar -C "$work"
cd "$work" || exit 1

maven_flags=(-B --no-transfer-progress)
[ "$mode" = offline ] && maven_flags+=(-o)

./mvnw "${maven_flags[@]}" test -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false 2>&1 | tail -n 300
maven_status=${PIPESTATUS[0]}

python3 - "$must_run" <<'PY'
import glob, re, sys
must = [m for m in sys.argv[1].split(",") if m]
seen = {}
for f in sorted(glob.glob("/home/runner/work/target/surefire-reports/TEST-*.xml")):
    s = open(f, encoding="utf-8", errors="replace").read()
    m = re.search(r'<testsuite [^>]*name="([^"]+)"[^>]*tests="(\d+)" errors="(\d+)" skipped="(\d+)" failures="(\d+)"', s)
    if m:
        name = m.group(1).split(".")[-1]
        seen[name] = tuple(int(x) for x in m.groups()[1:])
print()
print("%-36s %6s %6s %7s %8s" % ("class (on Linux)", "tests", "errors", "skipped", "failures"))
bad = 0
for name, (tests, errors, skipped, failures) in seen.items():
    flag = ""
    if errors or failures:
        flag = "  <-- FAILED"
        bad += 1
    elif name in must and (tests == 0 or skipped):
        flag = "  <-- must run with no skipped test"
        bad += 1
    print("%-36s %6d %6d %7d %8d%s" % (name, tests, errors, skipped, failures, flag))
for name in must:
    if name not in seen:
        print("%-36s  NOT RUN  <-- must run" % name)
        bad += 1
sys.exit(1 if bad else 0)
PY
python_status=$?
echo
if [ "$maven_status" -eq 0 ] && [ "$python_status" -eq 0 ]; then echo "LINUX OK"; exit 0; fi
echo "LINUX FAILED (maven exit $maven_status)"
exit 1
