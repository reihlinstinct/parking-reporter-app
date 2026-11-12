#!/bin/sh
# Private diagnostics only. The caller encrypts these files; never print them.
set -u
work=${1:?private output directory required}
mkdir -p "$work"
umask 077
package=il.sidewalks.reporter
# Stream before instrumentation starts: a dump afterward can lose the crash or
# low-memory kill when the ring buffer wraps. Do not filter to the first PID.
adb logcat -b main -b system -b crash -b events -v threadtime > "$work/logcat.txt" 2> "$work/logcat.stderr" &
log_pid=$!
(
    sleeper=''
    trap 'kill "$sleeper" 2>/dev/null || true; wait "$sleeper" 2>/dev/null || true; exit 0' INT TERM
    seen=''
    previous=''
    while :; do
        current=$(timeout -k 2 10 adb shell pidof "$package")
        pidof_rc=$?
        current=$(printf '%s' "$current" | tr -d '\r')
        now=$(date -u +%FT%TZ)
        if [ -n "$current" ]; then
            if [ -z "$seen" ]; then
                printf '%s PROCESS_FIRST_SEEN pid=%s\n' "$now" "$current"
            elif [ -z "$previous" ] || [ "$current" != "$previous" ]; then
                printf '%s PROCESS_REAPPEARED_OR_CHANGED pid=%s previous=%s\n' "$now" "$current" "$previous"
            fi
            seen=1
        elif [ -n "$previous" ]; then
            # Observation, not a diagnosis: crash/LMK/normal exit need logcat
            # and exit-info. A sub-second death+restart can evade polling.
            # pidof exit 1 means no match OR an adb transport error. Keep
            # stderr privately, and never call this proof of process death.
            printf '%s PROCESS_ABSENT_OR_ADB_ERROR previous=%s pidof_rc=%s\n' "$now" "$previous" "$pidof_rc"
        fi
        previous=$current
        sleep 1 &
        sleeper=$!
        wait "$sleeper"
    done
) > "$work/process-lifecycle.txt" 2> "$work/process-lifecycle.stderr" &
watch_pid=$!
stop_capture() {
    kill "$watch_pid" "$log_pid" 2>/dev/null || true
    wait "$watch_pid" 2>/dev/null || true
    wait "$log_pid" 2>/dev/null || true
}
trap stop_capture EXIT
trap 'exit 130' INT TERM
adb shell am instrument -w -r -e class io.github.reihlinstinct.reporter.live.LiveEmulatorSubmissionTest il.sidewalks.reporter.test/androidx.test.runner.AndroidJUnitRunner > "$work/instrumentation.txt" 2>&1
printf '%s\n' "$?" > "$work/instrumentation.rc"
# Android 11+ historical exit reasons distinguish Java/native crashes, LMK,
# ANR and requested exits where supported. A failed dump is private evidence.
timeout -k 2 30 adb shell dumpsys activity exit-info "$package" > "$work/process-exit-info.txt" 2>&1 || true
timeout -k 2 30 adb shell dumpsys meminfo "$package" > "$work/process-memory.txt" 2>&1 || true
# Match the old workflow contract: record instrument rc but continue collecting.
exit 0
