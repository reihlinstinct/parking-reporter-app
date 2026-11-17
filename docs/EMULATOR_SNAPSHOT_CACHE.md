# Clean emulator snapshot cache

Issue #548. Status: producer, consumer actions and mock benchmark exist. The live workflow does not use them yet.

**Measured saving: none yet.** The benchmark workflow measures it. No number appears here until a run prints one.

## What is cached

An Android virtual device directory (`~/.android/avd`) with the system booted, the app and the test APK installed, then shut down cleanly. Nothing private goes in. It is built with no secrets, no environment, no photos, no reporter identity, no municipal traffic and no tunnel. A snapshot is never saved after private data is injected.

## Rules

- Exact key, no restore-prefix fallback. The key covers API level, architecture, target, profile, system image revision, emulator version, runner image, a digest of the app build sources, a digest of the producer workflow and a 2-day epoch.
- The key follows the sources, not APK bytes. Debug APKs are not byte-reproducible across runners (debug signing key). The consumer installs the freshly built APKs over the snapshot and uninstalls first when signatures differ.
- `provenance.json` is stored in the cache. The consumer refuses a snapshot when any field is missing or unexpected, the inputs differ, the clean-build marker is not set, the producer ref is not `refs/heads/main`, the timestamp is in the future (5 minute skew allowed) or the age is over 72 hours.
- A content scan rejects symlinks and file names that look private (photos, params, addresses, credentials, recordings, logs, archives, keys) before publishing and again after restore.
- Any rejection deletes the restored files and the caller cold-builds the same clean image. Cache presence never grants filing authority.
- Only the producer saves. It runs from `main` on a daily schedule. The key epoch makes all but one run per 2 days a no-op, so a snapshot is rebuilt at least every 2 days (requirement: 3) and on any emulator, image, runner image, app source or producer change. GitHub evicts entries unused for 7 days, which is why a miss must always fall back cold.
- The benchmark may publish under its own branch ref. GitHub scopes a branch's caches to that branch. Live consumers only accept `refs/heads/main`.

## Using it (not wired into the live workflow)

```yaml
- id: key
  uses: ./.github/actions/emulator-snapshot-key
- id: snap
  uses: ./.github/actions/emulator-snapshot-restore
  with:
    key: ${{ steps.key.outputs.key }}
- uses: reactivecircus/android-emulator-runner@...
  with:
    force-avd-creation: ${{ steps.snap.outputs.hit != 'true' }}
    emulator-options: -no-window -gpu swiftshader_indirect -noaudio -no-boot-anim -camera-back none -no-snapshot-save
```

Wiring this into `live-emulator-e2e.yml` is a separate change that needs its own review after the benchmark. Private injection must stay after the restore.

## Benchmark

`emulator-snapshot-benchmark.yml` (manual, mock only) runs three cold and three warm preparations on fresh runners, one at a time, and `scripts/benchmark_snapshot_cache.py` prints median times per phase (SDK install, APK build, cache restore, boot to ready, APK install, preflight, total) and cache size. It states a saving only with at least three cold and three warm runs. A warm run that fell back cold is excluded and counted separately. It does not fix adb disconnects or the known UI false-negative timer.

## Tests

`tests/test_emulator_snapshot_cache.py` and `tests/test_benchmark_snapshot_cache.py` run offline in repository checks: key sensitivity to every input, every provenance rejection, content scan, CLI round trip, benchmark arithmetic and the no-claim rule, and workflow boundary checks (no secrets, no environments, exact key, only the producer saves, live workflow untouched).
