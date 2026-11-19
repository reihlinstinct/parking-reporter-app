#!/usr/bin/env bash
# Synthetic emulator diagnostics only. Never raise the heap or retry a failed test.
set -euo pipefail
mode=${1:?suite, snapshots or isolated required}
case "$mode" in suite|snapshots|isolated) ;; *) exit 2 ;; esac
out=app/build/reports/api29-memory
mkdir -p "$out"
adb logcat -c
adb shell getprop dalvik.vm.heapgrowthlimit > "$out/heapgrowthlimit.txt"
adb shell getprop dalvik.vm.heapsize > "$out/heapsize.txt"
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
if [ "$mode" = isolated ]; then
  for name in EntryPointsTest FirstRunScreenTest ReviewRecreationTest ReviewUnsavedBackTest; do
    # A fresh target process and private storage for each class. This diagnoses
    # dependence on suite history; it is not a replacement for the full suite.
    adb shell am force-stop il.sidewalks.reporter
    adb shell pm clear il.sidewalks.reporter
    run_once "$name" -e class "io.github.reihlinstinct.reporter.$name"
  done
elif [ "$mode" = snapshots ]; then
  run_once full-suite-with-snapshots -e memorySnapshots 1
  for ordinal in 28 111; do
    if ! adb exec-out run-as il.sidewalks.reporter cat "files/memory-diagnostic/before-$ordinal.hprof" > "$out/before-$ordinal.hprof"; then failed=1; fi
    if [ ! -s "$out/before-$ordinal.hprof" ]; then failed=1; fi
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
