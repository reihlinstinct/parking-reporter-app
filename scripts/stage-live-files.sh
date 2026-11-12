#!/bin/sh
# stage-live-files.sh PHOTO PARAMS LOOKUP - stage the live filing inputs onto the
# emulator's app file dirs and verify them (fail fast).
#
# Extracted from .github/workflows/live-emulator-e2e.yml so CI can regression-
# test it (see scripts/test-stage-live-files.sh). The mkdir -p matters: on a
# fresh install the app's files/ dir does not exist yet and a bare
# `cat > files/...` fails (run 42755730613 died exactly there).
set -eu

photo=$1
params=$2
lookup=$3
# Same whole-file pin as production AddressLookupPin. Never inject a test
# directory or bypass production verification in a real submission.
pin=$(sed -n 's/^        "\([0-9a-f]\{64\}\)"$/\1/p' app/src/main/java/io/github/reihlinstinct/reporter/municipal/AddressLookupPin.kt)
test -n "$pin" && test "$(sha256sum "$lookup" | cut -d' ' -f1)" = "$pin" || {
  echo 'FAIL-FAST: private address directory absent or mismatched' >&2; exit 1;
}

adb shell "run-as il.sidewalks.reporter.test sh -c 'mkdir -p files && cat > files/live-photo.jpg'" < "$photo"
adb shell "run-as il.sidewalks.reporter sh -c 'mkdir -p files && cat > files/live-params.json'" < "$params"
test "$(adb shell "run-as il.sidewalks.reporter.test sh -c 'sha256sum files/live-photo.jpg 2>/dev/null || toybox sha256sum files/live-photo.jpg'" | tr -d '\r' | cut -d' ' -f1)" = "$(sha256sum "$photo" | cut -d' ' -f1)" || {
  echo 'FAIL-FAST: staged photo on device does not match the approved photo' >&2
  exit 1
}
adb shell "run-as il.sidewalks.reporter sh -c 'test -s files/live-params.json'" || {
  echo 'FAIL-FAST: staged params missing on device' >&2
  exit 1
}
adb shell "run-as il.sidewalks.reporter sh -c 'mkdir -p no_backup && cat > no_backup/addresses.json'" < "$lookup"
test "$(adb shell "run-as il.sidewalks.reporter sh -c 'sha256sum no_backup/addresses.json'" | tr -d '\r' | cut -d' ' -f1)" = "$pin" || {
  echo 'FAIL-FAST: staged address directory mismatched on device' >&2; exit 1;
}
echo 'preflight: staged photo and params verified on device; pinned directory installed'
