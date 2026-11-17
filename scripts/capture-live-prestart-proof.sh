#!/bin/sh
# Fixed private sentinels only. Run before raw diagnostics are substituted/shredded.
# A missing, unreadable or partial observation never grants replay permission.
set -eu
cd "$1"
rm -f failed-before-start.proven
[ -f staged.ok ] || exit 0
[ ! -f log.collected ] || exit 0
[ -s instrumentation.txt ] || exit 0
[ -s instrumentation.rc ] || exit 0
rc=$(tr -d '\r\n' < instrumentation.rc)
case "$rc" in ''|*[!0-9]*|0) exit 0;; esac
! grep -q 'INSTRUMENTATION_STATUS_CODE' instrumentation.txt || exit 0
[ -f submissions.proven-empty ] || exit 0
[ -f case.proven-absent ] || exit 0
[ ! -s live-e2e-case-number.txt ] || exit 0
[ ! -s live-e2e-submission-state.json ] || exit 0
[ ! -s live-e2e-response-log.jsonl ] || exit 0
: > failed-before-start.proven
