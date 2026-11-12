#!/usr/bin/env bash
# Regression test for scripts/stage-live-files.sh: reproduces the first-
# install device state (the app's files/ dir does not exist yet - the exact
# condition that killed live run 42755730613) using a mock `adb`, and asserts
# staging succeeds and the inputs land on the mock device.
set -euo pipefail

cd "$(dirname "$0")/.."

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

# Mock device: two package data dirs, NEITHER has files/ (fresh install).
mkdir -p "$tmp/device/il.sidewalks.reporter" "$tmp/device/il.sidewalks.reporter.test"

cat > "$tmp/adb" <<'SH'
#!/usr/bin/env bash
# Mock adb: handles `adb shell "run-as PKG sh -c 'CMD'"` with stdin
# passthrough, executing CMD inside the mock package dir. Any other call
# fails loudly so the staging script cannot silently pass on a stub.
set -u
printf 'adb-call\n' >> "$MOCK_CALL_LOG"
[ "${1:-}" = "shell" ] || { echo "mock adb: unsupported call: $*" >&2; exit 2; }
quoted=$2
pkg=$(printf '%s' "$quoted" | sed -n "s/^run-as \([^ ]*\) .*/\1/p")
inner=$(printf '%s' "$quoted" | sed -n "s/^run-as [^ ]* sh -c '\(.*\)'\$/\1/p")
[ -n "$pkg" ] && [ -n "$inner" ] || { echo "mock adb: cannot parse: $quoted" >&2; exit 2; }
cd "$MOCK_DEVICE/$pkg" || exit 1
exec sh -c "$inner"
SH
chmod +x "$tmp/adb"

export PATH="$tmp:$PATH"
export MOCK_DEVICE="$tmp/device"
export MOCK_CALL_LOG="$tmp/adb-calls"

printf 'fake photo bytes\n' > "$tmp/photo.jpg"
printf '{"plate":"00000000"}\n' > "$tmp/params.json"

printf '{"version":1,"streets":[],"addresses":[]}\n' > "$tmp/addresses.json"
mkdir -p "$tmp/repo/scripts" "$tmp/repo/app/src/main/java/io/github/reihlinstinct/reporter/municipal"
cp scripts/stage-live-files.sh "$tmp/repo/scripts/"
pin=$(sha256sum "$tmp/addresses.json" | cut -d' ' -f1)
printf '        "%s"\n' "$pin" > "$tmp/repo/app/src/main/java/io/github/reihlinstinct/reporter/municipal/AddressLookupPin.kt"
cd "$tmp/repo"
sh "$tmp/repo/scripts/stage-live-files.sh" "$tmp/photo.jpg" "$tmp/params.json" "$tmp/addresses.json" | grep -q 'preflight: staged photo and params verified on device'
test -s "$tmp/device/il.sidewalks.reporter.test/files/live-photo.jpg"
test -s "$tmp/device/il.sidewalks.reporter/files/live-params.json"
cmp "$tmp/photo.jpg" "$tmp/device/il.sidewalks.reporter.test/files/live-photo.jpg"

cmp "$tmp/addresses.json" "$tmp/device/il.sidewalks.reporter/no_backup/addresses.json"
: > "$MOCK_CALL_LOG"
printf 'tampered' > "$tmp/addresses.json"
if sh "$tmp/repo/scripts/stage-live-files.sh" "$tmp/photo.jpg" "$tmp/params.json" "$tmp/addresses.json" >/dev/null 2>&1; then
  echo 'mismatched directory was accepted' >&2; exit 1
fi
test ! -s "$MOCK_CALL_LOG" # runner pin refusal must precede EVERY device write
rm "$tmp/addresses.json"
if sh "$tmp/repo/scripts/stage-live-files.sh" "$tmp/photo.jpg" "$tmp/params.json" "$tmp/addresses.json" >/dev/null 2>&1; then
  echo 'missing directory was accepted' >&2; exit 1
fi
test ! -s "$MOCK_CALL_LOG"
printf '{"version":1,"streets":[],"addresses":[]}\n' > "$tmp/addresses.json"
for bad_pin in malformed missing; do
  if [ "$bad_pin" = malformed ]; then
    printf 'not-a-pin\n' > app/src/main/java/io/github/reihlinstinct/reporter/municipal/AddressLookupPin.kt
  else
    rm app/src/main/java/io/github/reihlinstinct/reporter/municipal/AddressLookupPin.kt
  fi
  if sh "$tmp/repo/scripts/stage-live-files.sh" "$tmp/photo.jpg" "$tmp/params.json" "$tmp/addresses.json" >/dev/null 2>&1; then
    echo 'invalid pin was accepted' >&2; exit 1
  fi
  test ! -s "$MOCK_CALL_LOG"
done
echo 'staging regression test passed: first-install (no files/ dir) handled'
