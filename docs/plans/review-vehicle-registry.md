## Request
Show government-registry results directly below the relevant plate field: disability parking-tag status with a tag icon, plus whether the vehicle licence is expired. The reviewed plate must appear in the offense description.

## Scope
Separate from #330 so the approved single-report path stays unchanged. Only topics1144/1145 show plate and registry UI; unrelated city topics stay plate-less. Reuse the existing free public data.gov.il client and read-only access. No paid provider, signup, new credentials or private dataset.

Existing VehicleRegistryClient reads licence dates but is not wired into UI. It does NOT yet implement disability-tag lookup; verify the official public resource/schema before adding that result. Never interpret failed/missing data as no tag or a valid licence. Show unknown/loading/error states, source and check time. A tag result is information, not a conclusion that parking is legal.

Clear previous-plate results immediately on edit; prevent late callbacks from overwriting the current plate. Use bounded requests/cache/rate limiting and no plate logging. Accessible icon/text and live announcements, five UI languages.

Ensure the reviewed plate is in the review-visible offense text before approval. Do not silently append at transport time or modify already approved text. Existing single SUV description already contains its exact plate.

## Acceptance
Mocked lookup success/not-found/error/expired/boundary-date/stale-edit tests, plate-required description tests, irrelevant-topic absence, and real-emulator screenshots/recording inspected before completion. Standard exact-head review and CI before merge. No real municipal filing in tests.

## Catalog-driven plate requirements and user overrides
Plate relevance must not be a release-bound hardcoded code list. Read a plate-required flag from validated catalog metadata when supplied, with1144/1145 only as bootstrap defaults for entries lacking that metadata. Bind identity to the stable city code, never the translated name or path. A Settings control lets the user additionally enable plate entry for any current catalog topic without a new app version; persist selected code overrides locally. Do not provide an override that disables an explicit required flag.

A new/unknown code with no plate metadata and no user enable stays plate-less, clearly offering the Settings enable path, not guessing from its label. Explicit catalog metadata takes precedence over bootstrap defaults; user enables are additive. A renamed label with the same code retains the override. A removed code retains a dormant override but cannot be selected or submitted from stale data; a replacement code is a new identity and never inherits the old code's setting silently. Never automatically remap codes from similar names. Validate/sanitize catalog flags, preserve last-good data on malformed refresh, and test all precedence, rename/removal/replacement and stale-result paths. #330 remains unchanged for the current approved single report.
