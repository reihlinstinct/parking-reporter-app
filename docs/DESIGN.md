# Parking Reporter - Android Design

Development preview. Local review is not authorization for network submission.

## Architecture

Kotlin, Jetpack Compose, CameraX and Room. Preserve original photo bytes under noBackupFilesDir and compute SHA-256. Record manual time/location corrections separately from original metadata. Geocoding is opt-in; address results are suggestions, not proof.

Reporter settings contain first name, last name, phone, ID and email. Keystore AES-GCM, recent device credential authentication and AtomicFile protect storage. No plaintext fallback or backup; optional ML Kit sends disclosed API usage/performance metrics to Google. Municipal API credentials are not part of the build: they are entered manually on the settings screen and stored only inside the same encrypted envelope (never logged, never backed up, omitted when blank, masked with password keyboard options). Live submission stays disabled until separately approved. Sensitive production screens use FLAG_SECURE.

A revision-checked Room ledger reserves stable report IDs and original hashes. Manual review binds an exact content digest; editing clears review. This is a local review gate, not a submission receipt. Recovery checks original hashes.

## Planned stages

M0: synthetic transport diagnostics, with no configured production endpoint.
M1: capture, metadata, encrypted settings, local ledger and manual review.
M2: smaller on-device OCR/model, explicit uncertainty and manual correction. No cloud photo recognition.
M3: public vehicle registry validation; mismatches stop review.
M4: Hebrew report text and approval-digest parity with synthetic golden fixtures.
M5: separately approved submission through a secure intake, bounded quotas, durable duplicate guards and receipt reconciliation. No automatic retry after an uncertain write.
M6: optional separate social-export review with every plate masked. Not implemented.

## Network boundary

The public client contains no municipal endpoint or secret, and no private repository identifier. Proposed encrypted intake is described separately. Profile, photo and location transfer requires reviewed exact payloads and a validated enrollment/approval path. No live transport is implemented by this preview.

## Release gates

Camera lifecycle, authenticated Keystore behavior, device storage recovery, schema migrations, EXIF orientation, physical-device UI, model terms and target-device support require validation. Emulator checks use synthetic data only and do not prove physical-device or municipal behavior. minSdk 26 is a build target, not universal-device validation.

## Photo-first review

The home screen asks for a photo; Settings and saved drafts are secondary. Local OCR runs after capture and suggests plate candidates, never approval. The compact review exposes a small Edit action and category dropdown. Automatic category classification is not implemented: a new report has no selected category and cannot be sent until the user chooses one. These are local preview categories, not validated municipal category identifiers.

Broken-sign and faded-crosswalk categories require an empty plate; changing from a vehicle category clears the plate and the saved ready digest. Returning to vehicle parking requires a reviewed plate again. Reporter details remain in Settings. Missing address, time offset, location or description still blocks sending. Geocoding remains opt-in with provider disclosure. Send is visibly disabled; production enrollment and transport remain outside this preview.

## Review address presentation (#204)

The normal review and Edit screen show street and house number, never raw coordinate fields. When a new draft has GPS and lacks an address, a disclosure asks permission before the device Geocoder receives coordinates. Accepting runs the lookup without typing coordinates; declining performs no lookup. The existing Edit lookup button remains available. Results use provider street/house components, not the freeform city/postcode label, and stay suggestions until selected and confirmed. No house number is invented if missing. Lookup failure leaves manual address entry.

When the photo has no location and was taken at most 10 minutes ago (by its capture time; unknown time means no button, so an old photo never receives the phone's position), the review screen offers an optional "use my current location" button (issue #186). A fix that arrives after the user already confirmed coordinates (for example through the manual address) is discarded. Only a tap asks for the location runtime permission (ACCESS_FINE_LOCATION, requested together with ACCESS_COARSE_LOCATION as lint requires; approximate is enough for the network provider) (never at start-up, never in the background); it reads one fix from the phone (GPS, then network), 15 seconds at most, keeps no history, and only fills the review coordinates. The user still checks and confirms the address, and nothing leaves the phone before sending. Denial or no fix leaves the manual street and house entry.

Coordinate correction remains under an Advanced location correction disclosure, closed by default when location exists and open when it is missing for missing/wrong metadata. Original Evidence/EXIF, hashes, outgoing photo path and digest algorithms are unchanged. Selecting a suggestion adds a confirmation warning; it never submits. Save and send fold the checked time and coordinates into the draft; send is the single confirmation (no separate approval step). The fold confirms time and location only: address warnings clear only by an explicit address act - editing the address field (labeled "שאישרת") to a changed value, or the lookup's אשר כתובת - and GPS/location warnings survive a coordinate-less fold. Opening a ledger record binds draft/original/revision atomically after the path and hash checks, and the coordinator refuses before any network call when the photo bytes do not match the draft evidence hash. The shared synthetic/live FlowDriver dismisses optional provider consent (its fixture already supplies a reviewed address) and explicitly opens Advanced to type its known fixture coordinates. No live input, encrypted blob or workflow dispatch is part of this change.

### First-run basic setup (#209)

Welcome now leads to basic setup before home. The compact language picker uses
ui-prefs without authentication. Only reporter personal fields appear here; no
OCR/model choice, API credentials or diagnostics. Later (including system Back)
finishes onboarding without writing a profile, so capture/import stays available.
Missing municipal credentials still block Send through the existing gate.

Personal fields are transient Compose state, not saved instance state or plaintext
prefs. Save asks for the existing device unlock and uses the same fresh-read merge,
encrypted write and missing-field index/rollback as Settings, preserving untouched
API credentials. Success clears/disposes the form; cancellation or failure stays
on setup. Only the nonsensitive setup stage survives rotation. Pending shared-photo
and tile entry is deferred until setup finishes. No live workflow or evidence changes.

## Experimental sidewalk-parking suggestion (issue #158)

An optional, advisory line on the review screen: "הצעה ניסיונית: נראה כמו חניה על מדרכה". Tapping it selects the sidewalk-parking city topic (code 1145), only when that topic is in the loaded city list; ignoring it changes nothing. It never selects, fills or files anything by itself. The model is private: not in this repository, not a release asset, not in the APK. The owner side-loads `sidewalk_v4.tflite` into the app's own external files folder (`Android/data/<package>/files/models/`). The app runs it only when the file's SHA-256 equals the pin in `SidewalkSuggestion.PINNED_SHA256` (blank pin means the feature is off). Preprocessing: resize the short side to 256, center crop 224, scale to 0..1, ImageNet mean/std; channel order follows the model's input shape. Output 0 (sidewalk_parking) is shown at 0.10 or above (threshold from the v4 model owner; the model also has red_white and crosswalk_obstruction outputs, unused here). Whether that output is a probability or a raw logit is a model property set by `SidewalkSuggestion.OUTPUT_IS_LOGIT` together with the pin (v4 is verified as a probability, sigmoid folded into the graph). Only a float32 model with a [1, N] output is accepted. The verified interpreter is built once per process from the verified bytes; the heap copy is released right after the buffer copy. Inference runs on the phone, off the main thread, with LiteRT; the photo never leaves the device. The en/ar/ru/am wording is derived from the owner-approved Hebrew line and needs native review.

## Description templates, engine (issue #424)

A suggested description is built from parts, not from a category code. A template is sentences of text and slots (plate, vehicle, street, house number, licence expiry, last test). A sentence with an empty slot, or with an unmet permit-state condition, is dropped whole; the text never contains a placeholder. A sentence that depends on the disabled-permit state is shown only when that state was checked. When the form changes, the description is re-rendered per sentence: a sentence still exactly as last rendered follows the form, a sentence the user edited is kept as written and never re-added. Templates are keyed by a stable tag. A separate mapping (user entries first, then the shipped ones) connects a live city category to a tag: by city code first, by normalised name as the fallback, and a code whose name changed is flagged for review instead of applied. An unmapped category gets the generic template when one exists, otherwise nothing. This first slice is the engine and its tests only: no Hebrew template text and no screen yet, because each wording needs the owner's approval.
