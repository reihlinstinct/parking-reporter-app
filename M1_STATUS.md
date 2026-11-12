# Current development preview status

Development preview, not ready for production. This replaces the old M1-only
snapshot. Status checked on 2026-11-12 against main after registry PR #333 and
display-coverage PR #324 (release 0.41.1). For later changes, check
[CHANGELOG.md](CHANGELOG.md), the current source and the exact commit's CI.

## Implemented on main

- CameraX capture, original SHA-256 and EXIF metadata, manual corrections,
  on-device recognition with explicit candidate selection and manual fallback.
- Encrypted reporter settings, local Room ledger, revision-checked draft storage,
  digest-bound review and explicit protection against discarding unsaved edits.
- City-sourced searchable subject catalog with checksummed bundled data and
  last-cache offline behavior. Plate requirements come from topic policy;
  a plate must be visible in the approved report text before Send is available.
- Independent, bounded public tag and vehicle-registry lookups below a relevant
  plate. Verified missing tags stay silent; failed lookups say unavailable.
  Expired licence dates are red, 0-13 days remaining are orange and 14 or more
  are normal, calculated from the check date in Jerusalem. Active-record absence
  can fall back to the public inactive dataset. Registry information never
  rewrites the report or gates Save/Send. See [registry details](docs/VEHICLE_REGISTRY.md).
- Production submission code and an explicitly opt-in recorded live-emulator
  workflow exist. This is **not** automatic filing permission or production
  enrollment. Regular CI stages no live case and files nothing. A real case
  requires the documented approval, staging and duplicate/uncertain-outcome
  safeguards. See [live E2E limits and gates](docs/LIVE_E2E.md).
- Offline signed/encrypted-package diagnostics use synthetic data and a local
  sink. They are separate from the opt-in municipal submission path.

## Evidence and limits

PR #333's exact head `2c5edb7` passed API30 and API36 instrumentation
(`OK (118 tests)` on each), plus normal foldable and compact 200% display
checks: [recorded CI run](https://github.com/reihlinstinct/parking-reporter-app/actions/runs/43202844871).
This is evidence for that commit, not a promise that every future main commit
passes. Counts include guarded/opt-in cases and are not a count of live filings.

Per-PR emulator coverage is API30 and API36. Weekly coverage probes additional
versions; API29 and API37 remain known-unstable probes, and API26 emulator
coverage is not claimed. minSdk remains26. A build supporting an API is not
proof of field validation on every physical device.

Inspected synthetic screenshots and recordings cover selected Hebrew layouts,
IME scrolling, duplicate catalog paths and registry states. They do not prove
physical-camera behavior, real-device authentication/Keystore behavior, universal
accessibility, or end-to-end voice-assistant discovery. Do not infer those from
compile success or a green emulator run.

## Not shipped by this snapshot

- PR #320: experimental, opt-in AppFunctions entry. Its package, UI conditions,
  older-Android compatibility and exact-head reviews remain separate gates.
- PR #361: private expiry-bounded registry cache fallback with a live request on
  every lookup. The merged registry feature alone is not this persistent cache.
- PR #364: additional editor-test diagnostics/hardening; not a production feature.

For remaining work, use current issues and PRs rather than the old M1-M4 checklist.
Non-blocking registry wording/theme/parity follow-ups are #365-#369; display
coverage/discoverability/recovery-summary follow-ups are #370-#372. None makes
this a production-readiness certification. No reporter secrets or evidence are
bundled in the public APK.
