## Request
Show government-registry results directly below the relevant plate field: disability parking-tag status with a tag icon, plus whether the vehicle licence is expired. The reviewed plate must appear in the offense description.

## Scope
Separate from #330 so the approved single-report path stays unchanged. Only topics1144/1145 show plate and registry UI; unrelated city topics stay plate-less. Reuse the existing free public data.gov.il client and read-only access. No paid provider, signup, new credentials or private dataset.

Existing VehicleRegistryClient reads licence dates but is not wired into UI. It does NOT yet implement disability-tag lookup; verify the official public resource/schema before adding that result. Never interpret failed/missing data as no tag or a valid licence. Show unknown/loading/error states, source and check time. A tag result is information, not a conclusion that parking is legal.

Clear previous-plate results immediately on edit; prevent late callbacks from overwriting the current plate. Use bounded requests/cache/rate limiting and no plate logging. Accessible icon/text and live announcements, five UI languages.

Ensure the reviewed plate is in the review-visible offense text before approval. Do not silently append at transport time or modify already approved text. Existing single SUV description already contains its exact plate.

## Acceptance
Mocked lookup success/not-found/error/expired/boundary-date/stale-edit tests, plate-required description tests, irrelevant-topic absence, and real-emulator screenshots/recording inspected before completion. Standard exact-head review and CI before merge. No real municipal filing in tests.
