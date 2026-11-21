# Clean emulator snapshot cache

Issue #548. Status: producer, consumer actions and mock benchmark exist. The live workflow does not use them yet.

**Measured saving: none yet.** The benchmark workflow measures it. No number appears here until a run prints one.

## What is cached

An Android virtual device directory (`~/.android/avd`) with the system booted, the app and the test APK installed, then shut down cleanly. Nothing private goes in. It is built with no secrets, no environment, no photos, no reporter identity, no municipal traffic and no tunnel. A snapshot is never saved after private data is injected.

## Rules

- Exact key, no restore-prefix fallback. The key covers API level, architecture, target, profile, system image revision, emulator version, runner image, a digest of the tracked app build sources (`git ls-files`, so the OCR model that `prepare_wrapper.sh` downloads does not change it, and the key computed before the build equals the one verified after), a digest of the preparation inputs (producer workflow, both composite actions and `scripts/emulator_snapshot_cache.py`) and a 2-day epoch.
- The key follows the sources, not APK bytes. Debug APKs are not byte-reproducible across runners (debug signing key). The consumer installs the freshly built APKs over the snapshot and uninstalls first when signatures differ.
- `provenance.json` is stored in the cache. The consumer refuses a snapshot when any field is missing or unexpected, the inputs differ, the clean-build marker is not set, the producer ref is not an allowed ref, the timestamp is in the future (5 minute skew allowed) or the age is over 72 hours.
- **What is and is not a boundary.** `producer_ref` in the manifest is written by the producer itself, so checking it is a consistency check, not authentication. The real boundaries are GitHub cache scoping (a branch's caches are invisible to main) and a producer job that never holds secrets or private inputs, which `WorkflowBoundaryTest` pins. The content scan is name-based, a tripwire for stray files. It cannot see data inside `userdata-qemu.img*` or `snapshots/*/ram.bin`, so nothing private may ever reach the producer. Expect real AVD file names to trip it on the first producer run; the allowlist is then adjusted deliberately.
- A content scan rejects symlinks and file names that look private (photos, params, addresses, credentials, recordings, logs, archives, keys) before publishing and again after restore.
- Any rejection deletes the restored files and the caller cold-builds the same clean image. Cache presence never grants filing authority.
- Only the producer saves. It runs from `main` on a daily schedule. The key epoch makes all but one run per 2 days a no-op, so a snapshot is rebuilt at least every 2 days (requirement: 3) and on any emulator, image, runner image, app source or producer change. GitHub evicts entries unused for 7 days, which is why a miss must always fall back cold.
- The benchmark, run from a branch, accepts `refs/heads/main` and its own branch ref (a branch can restore main's snapshot, so both must be accepted for warm samples). Live consumers accept only `refs/heads/main`.

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

Benchmark input checks reject repeated mode/run identities, non-finite or boolean
phase values, and invalid cache sizes before computing a saving. Copying one
run file under multiple names cannot meet the three-run evidence threshold.

## Disposable emulator marker cleanup

After clean emulator shutdown, the producer removes only regular files named exactly `read-snapshot.txt` inside its disposable AVD tree before writing the manifest. It never reads their contents, follows directory symlinks or removes symlink markers. All other files and all cache-content, credential/PII, symlink and provenance checks remain unchanged. The marker is still forbidden in a published or restored cache; this is producer cleanup, not a validator exception. No private evidence is processed.


### Clean producer refusal diagnosis

The main-only producer opts into `write-manifest --diagnose-clean-producer`. On rejection it prints only the rejected basename, JSON escaped and capped at 160 characters, never the directory path or file contents. This is limited to the fresh synthetic producer that has no credentials or private inputs. Consumers retain redacted fixed-vocabulary diagnostics. Validation, the allowlist and cache-save ordering are unchanged: a rejected file still stops publication. This diagnostic does not guess which file failed run 44515878752; one reviewed producer run is needed to identify it before any further cleanup change.

## Single warm-restore proof

`Single warm snapshot restore proof (mock only)` is manual and main-only. It
runs one standard Linux job with a ten-minute cap. It computes the current
identity key and calls the existing exact-key restore/manifest/age/content
validator. A miss is a failure, not a cold fallback. It never calls the
producer, builds APKs, reinstalls APKs, saves a cache, uses secrets or contacts
the municipality. A producer cache must already exist for the same sources,
preparation files, emulator/image and hosted-runner image. A runner-image or
identity change can cause an honest miss; do not call that a warm success.

The bounded boot helper starts the restored `test` AVD with `default_boot` and
no snapshot save. It requires the emulator's explicit successful-load marker,
boot completion, both APK packages already installed, and a healthy guest.
Cold-fallback/failure markers, early exit, oversize logs or timeout refuse the
proof. There is no recovery reboot. Emulator stdout stays in a temporary file,
not an artifact or log dump, and only a fixed success/refusal label is printed.
The process group is always killed/reaped and restored material is removed by
an always-run workflow step. Emulator log vocabulary changes can fail closed.

Passing this proof establishes one usable warm restore. It is not a benchmark,
not a measured savings claim, and does not activate any live consumer. The
three-cold/three-warm benchmark is a separate, larger operation.
