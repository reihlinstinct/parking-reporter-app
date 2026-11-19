#!/usr/bin/env bash
# Synthetic emulator diagnostics only. Never raise the heap or retry a failed test.
set -euo pipefail
mode=${1:?suite, fresh-suite, snapshots, isolated or paired required}
case "$mode" in suite|fresh-suite|snapshots|isolated|paired) ;; *) exit 2 ;; esac
out=app/build/reports/api29-memory
mkdir -p "$out"
adb logcat -c
adb shell getprop dalvik.vm.heapgrowthlimit > "$out/heapgrowthlimit.txt"
adb shell getprop dalvik.vm.heapsize > "$out/heapsize.txt"
adb shell getprop > "$out/emulator-properties.txt"
adb shell dumpsys package il.sidewalks.reporter > "$out/package-metadata.txt"
adb shell ps -A > "$out/processes-before.txt"
failed=0
run_once() {
  local name=$1; shift
  local rc=0
  adb shell am instrument -w -r -e listener io.github.reihlinstinct.reporter.MemoryTraceListener -e memoryTrace 1 "$@" il.sidewalks.reporter.test/androidx.test.runner.AndroidJUnitRunner > "$out/$name.txt" 2>&1 || rc=$?
  printf '%s\n' "$rc" > "$out/$name.rc"
  cat "$out/$name.txt"
  if ! python3 scripts/test_summary.py --title "API29 memory diagnostic: $name" --instrumentation "$out/$name.txt" --check; then failed=1; fi
  if [ "$rc" -ne 0 ]; then failed=1; fi
}
if [ "$mode" = fresh-suite ]; then
  # Same full suite after the same process/storage reset as isolated mode.
  # No system property or app heap setting changes.
  adb shell am force-stop il.sidewalks.reporter
  adb shell pm clear il.sidewalks.reporter
  run_once fresh-full-suite
elif [ "$mode" = isolated ] || [ "$mode" = paired ]; then
  # A matched-device comparison, still record and compare each process heap cap.
  if [ "$mode" = paired ]; then run_once paired-full-suite; fi
  for name in EntryPointsTest FirstRunScreenTest ReviewRecreationTest ReviewUnsavedBackTest; do
    # A fresh target process and private storage for each class. This diagnoses
    # dependence on suite history; it is not a replacement for the full suite.
    adb shell am force-stop il.sidewalks.reporter
    adb shell pm clear il.sidewalks.reporter
    run_once "$name" -e class "io.github.reihlinstinct.reporter.$name"
  done
elif [ "$mode" = snapshots ]; then
  run_once full-suite-with-snapshots -e memorySnapshots 1
  for ordinal in 24 27; do
    if ! adb exec-out run-as il.sidewalks.reporter cat "files/memory-diagnostic/before-$ordinal.hprof" > "$out/before-$ordinal.hprof"; then failed=1; fi
    # adb can exit zero while run-as/cat writes an error message to stdout.
    # Require the binary HPROF magic, not merely a nonempty redirected file.
    if ! python3 - "$out/before-$ordinal.hprof" <<'PYHPROF'
import pathlib
import sys
header = pathlib.Path(sys.argv[1]).read_bytes()[:32]
sys.exit(0 if header.startswith(b"JAVA PROFILE 1.0.") and b"\0" in header else 1)
PYHPROF
    then failed=1; fi
  done
else
  run_once full-suite
fi
adb logcat -d -v threadtime > "$out/logcat.txt"
# Extract metadata only, leaving the complete synthetic trace for diagnosis.
grep 'API29_MEMORY' "$out/logcat.txt" > "$out/per-test-memory.txt" || true
if [ ! -s "$out/per-test-memory.txt" ]; then failed=1; fi
printf '%s\n' "$failed" > "$out/diagnostic.rc"
exit "$failed"
