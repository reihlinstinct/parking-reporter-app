# Parking Reporter for Android

Open-source Android development preview for local review of sidewalk-parking evidence. Kotlin and English documentation; Hebrew UI and report text. MIT licensed. Not a production reporting app.

## Current capabilities

CameraX original-byte capture, SHA-256, EXIF metadata, manual corrections, optional geocoder suggestions, encrypted reporter settings, local Room ledger and digest-bound manual review. No live report submission, bundled municipal secrets or cloud photo recognition.

## Build and tests

Java 17, Android SDK 35, Gradle 8.11.1. Accept Android SDK terms before installing its packages.

```sh
sh scripts/prepare_wrapper.sh
sh gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug
sh gradlew --no-daemon connectedDebugAndroidTest
python3 scripts/check_repository.py
```

CI builds, lints and runs synthetic unit/emulator tests. Check the current run before claiming success. Debug APKs are test artifacts, not signed production releases. Physical camera, device authentication and target-device behavior still need validation.

## Milestones

| Stage | Goal |
| --- | --- |
| M0 | Synthetic transport diagnostic, no configured live endpoint |
| M1 | Capture, encrypted settings, ledger and manual review |
| M2 | On-device recognition with uncertainty and manual correction |
| M3 | Vehicle registry checks and mismatch stops |
| M4 | Hebrew text and approval-digest parity |
| M5 | Approved submission, receipt and uncertain-state reconciliation |
| M6 | Optional separate plate-masked social export |

See [design](docs/DESIGN.md), [preview status](M1_STATUS.md), [security](SECURITY.md), [contributing](CONTRIBUTING.md) and [release checklist](docs/RELEASE_CHECKLIST.md).

## Safety

Never commit real plates, evidence, profiles, keys or tokens. Fixtures are explicitly synthetic. Local review is not a submission or permission to post. Edits invalidate review. Original hash and stable ID guard against duplicates. Uncertain writes must not be retried automatically. Photos remain local for recognition. No municipal credentials or endpoints belong in public code or APKs.
