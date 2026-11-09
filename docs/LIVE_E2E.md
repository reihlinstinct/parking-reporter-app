# Live emulator submission E2E (opt-in, recorded)

The manual workflow `.github/workflows/live-emulator-e2e.yml` files **one real
municipal case through the real app UI** in a CI emulator and records the
screen while it happens. It exists so a live submission can be demonstrated
end to end - camera-roll entry, review, local approval, send - not only as an
engine-level JVM test (`m0-engine-trial.yml`, no UI).

This path is strictly opt-in. Nothing here runs on push, on pull requests, or
on a schedule.

## What runs where

- `app/src/androidTest/java/il/sidewalks/reporter/live/LiveEmulatorSubmissionTest.kt`
  is compiled into the regular test APK but **returns immediately unless the
  workflow staged `live-params.json`** into the app's private files directory.
  Regular CI (android.yml) never stages it, so the suite stays green.
- The test drives the same `FlowDriver` and the same `AppShell` as the
  synthetic stub E2E (`FullFlowE2eTest`), with the app's production defaults:
  the pinned municipal endpoint and the real on-device street directory.
- `StubE2eSafetyTest` still enforces that the synthetic E2E has no live mode;
  `LiveE2eSafetyTest` enforces this path's guards (opt-in staging, digest
  binding, no secret output, encrypted evidence, device wipe).

## Opt-in chain (all four required)

1. **Manual dispatch** of `live-emulator-e2e.yml` on `main` with
   `confirm = FILE-LIVE-REPORT`.
2. **Environment approval**: the job runs in the `m0-trial-submit`
   environment, whose required reviewer is the owner.
3. **Digest binding**: the dispatch input `approved_sha256` must equal the
   sha256 of the staged `TRIAL_APPROVED_TEXT` secret. The runner checks this
   before staging anything, and the test re-checks it on-device before the
   flow starts. A mismatch aborts with a constant message.
4. **Staged case data**: the run files exactly the case currently staged in
   the `TRIAL_*` secrets (photo in `trial/trial-photo.jpg.enc`, address,
   coordinates, capture time, plate, approved text) with the reporter and
   municipal credentials (`REPORTER_*`, `J106_*`). Dispatch it only for a case
   the owner approved for filing.

## Egress

The municipal API blocks GitHub egress for some calls. The workflow first runs
the engine's login-only probe (no case writes); only when the probe proves a
block (403 / connect failure marker) does it bring up the owner L2TP tunnel
(`trial/l2tp.py`) and re-probe. The tunnel is always torn down afterwards.

## Privacy model

- Public run log: only the scrubbed `LIVE_E2E` markers (fixed step names, the
  municipal case number, exception class names) plus "params staged (values
  withheld)".
- Never in public output: credentials, reporter identity, plate, address,
  report text, the photo, screenshots, raw instrumentation output, logcat.
- The screen recording (up to six ~170-second `screenrecord` segments,
  concatenated), both screenshots and raw instrumentation output go into a
  tar bundle encrypted with `TRIAL_DATA_KEY` (openssl AES-256-CBC PBKDF2, the
  same key and rotation process as the trial photo) and uploaded as the
  `live-e2e-private-bundle-encrypted` artifact (14-day retention). Artifacts
  on a public repo are downloadable by any signed-in GitHub user, hence the
  encryption.
- The device copy of the params, photo, screenshots and recording is removed
  in the same run; runner plaintext is shredded.

Decrypt a bundle locally:

```sh
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 \
  -in live-e2e-private-bundle.tgz.enc -out bundle.tgz -pass env:TRIAL_DATA_KEY
tar xzf bundle.tgz   # recording.mp4, instrumentation.txt, screenshots
```

## Running it

1. Stage the approved case in the `TRIAL_*` secrets (rotation workflow covers
   the photo) and compute `sha256` of the exact approved Hebrew text.
2. Actions -> "Live emulator E2E (owner-approved)" -> Run workflow on `main`:
   `confirm = FILE-LIVE-REPORT`, `approved_sha256 = <digest>`, optionally a
   different `category_label` (default חניה על המדרכה).
3. Approve the environment deployment when prompted.
4. The run is green only when the marker log reaches
   `LIVE_E2E SUBMITTED case=<number>`. The case number also lands in the
   scrubbed artifact `live-e2e-scrubbed-log`.

## Known limits

- Recording is segmented (screenrecord caps a segment); the workflow keeps up
  to six segments, about 17 minutes. A stalled run keeps evidence up to the
  cut only.
- One submission attempt per dispatch; a transient municipal failure fails
  the run (evidence is still uploaded, encrypted).
- Device-unlock is a pass-through in the test, as in the synthetic E2E; on a
  real device the user confirms with the device credential.
