# Assistant entry and AppFunctions

This integration exposes two narrow AppFunctions, both default disabled and behind the same consent switch:

- `startNewReport()` returns an immutable PendingIntent to the existing camera entry.
- `startNewReportInCategory(categoryCode)` (issue #190 first slice) returns the same kind of PendingIntent, with one of the four known category codes (`sidewalk_parking`, `broken_sign`, `faded_crosswalk`, `bollard_request`) preselected as the review draft's starting category. Any other value is rejected with an invalid-argument error, never guessed. The category travels as an intent data link, not as an extra, and is a hint the person can change in review. The hint lives for one camera session only (any other page clears it), is honored only while the consent switch is on, and a browsable VIEW link never carries it.

Neither function submits a report, creates an invisible draft (the draft exists only once the person captures or imports a photo in the visible flow), reads saved reports, exports evidence, or returns identity fields, credentials, photos or locations. There is no plate, address or free-text parameter.

## Consent and compatibility

- OFF by default, only in full Settings. Basic reporter setup is unchanged.
- System metadata declares the function disabled by default. Enabling first updates system availability, then stores local consent. If indexing is not ready, the UI reports failure instead of pretending it succeeded.
- Disabling revokes local consent first. Every execution rechecks that consent, so delayed system state cannot grant access.
- Service is guarded by `android.permission.BIND_APP_FUNCTION_SERVICE` and is disabled below API36. There is no HTTP server or new network permission.
- Camera permissions, photo review, municipal account access, device authentication and final send remain in the existing app flow.
- The returned action carries no photo, text, location, token or approval fields.

## What is and is not proven

Source contracts check the default-disabled and system-protected surface. Instrumentation checks the generated service/metadata, local refusal after consent is revoked, immutable action and unchanged installed entry point. These are not a claim that Gemini can discover or invoke this app on an owner's phone.

Actual Gemini availability, Hebrew invocation, Connected Apps Console enrollment and end-to-end assistant discovery remain unverified. No registration, account/terms acceptance or Play upload is part of this implementation. The settings note keeps that limit visible.

On an API36 device, inspect `adb shell cmd app_function help` before attempting the platform CLI. The CLI surface varies by system image; lack of CLI is not proof the app's generated metadata is broken. Never use a municipal submission as an integration test.

## Sources

- Android implementation guide: https://developer.android.com/ai/appfunctions/add-appfunctions
- API entry point: https://developer.android.com/reference/androidx/appfunctions/AppFunctionServiceEntryPoint
- RC01 release: https://developer.android.com/jetpack/androidx/releases/appfunctions
- Connected Apps requirements: https://developer.android.com/ai/appfunctions/create-manage-connected-apps

The runtime and compiler are pinned to `1.0.0-rc01`. The service and function XML are compiler-generated, not hand-authored.

## API36 index compatibility

The stock API36 AppSearch indexer asks for service property
`android.app.appfunctions` when dynamic schemas are unavailable, not the v2
property alone. Enable the pinnedrc01 compiler's `appfunctions:generateV1Xml`
option and point that property to `report_app_functions-v1.xml`. Keep v2/schema
properties for newer indexers. Never hand-author a function identifier or schema.
Evidence: API36 logcat `AppSearchAppsUtil ... Failed to get property`, followed by
function-does-not-exist, and official platform AppsUtil.java lines355-380:
https://android.googlesource.com/platform/packages/modules/AppSearch/+/refs/heads/android16-release/service/java/com/android/server/appsearch/appsindexer/AppsUtil.java
The required real manager invocation/revocation test remains enabled, not skipped.

### Stock API36 discovery limit

The rc01 Jetpack caller metadata reader returns null for custom, schemaless
functions on a legacy AppSearch index (AppSearchAppFunctionReader's
getAppFunctionComponentsMetadata), even when system indexing and enable succeed.
The integration gate therefore invokes the real Android platform manager binder
on API36, checks the immutable action's creator, then verifies revocation on the
same binder. It does not invoke the service directly or simulate execution.
This does not prove Gemini/other Jetpack callers discover custom functions on
stock API36. Do not advertise that until tested on a dynamic-schema indexer.
Pinned rc01 sources: https://dl.google.com/dl/android/maven2/androidx/appfunctions/appfunctions/1.0.0-rc01/appfunctions-1.0.0-rc01-sources.jar


## Older Android compatibility
Owner requirement (2026-11-12): adding the experimental feature must not block publishing or installing the app on older Android. minSdk remains26. The AppFunctions service is disabled by resource default and enabled only by values-v36; no required uses-library or uses-feature was introduced. The Settings wrapper returns before loading saved state on unsupported API levels; the component is absent, not a disabled upsell. Instrumentation checks the installed merged application minSdk26 and disabled service on API30, and the API30 suite installs/launches the same APK. Basic first-run setup asserts the assistant title and toggle are absent. Latest exact-head API30 results must pass before merge.

## Voice entry, phase 2 proposal (issue #149)

Status: step 1 implemented (owner approved step 1 and the "דיווח חדש" wording on 15 Nov 2026). No paid speech API, no account or Play step.

Already on main: deep link, share-sheet target and Quick Settings tile (#151), opt-in AppFunctions entry (#320, #389). These are language-independent and cover the "report on this photo" case without any assistant.

Proposed phase 2, in order:

1. `OPEN_APP_FEATURE` built-in intent with an inline inventory, one feature "new report", targeting the existing `il.sidewalks.reporter://report/new` link. Free, on-device, no new permission, no network.
2. Feature names and shortcut labels live in Android `res/values-*` resources (never literals), so each locale works when the device and assistant language match. Hebrew and English names are owner-approved wording; any other locale name is derived and must be disclosed and approved before shipping.
3. The voice path never carries a photo, plate, text or location. It only opens the visible capture flow. Review, consent, device authentication and Send stay unchanged.
4. Defer custom `queryPatterns` intents (documented en-US only).

Tests without a device: manifest and `shortcuts.xml` parse test (feature, deep link target, exported flags, resource-only labels), a resource completeness check across locales, and the existing deep-link parser tests.

Open and unverified, to settle before promising anything: how Gemini treats App Actions today, and Hebrew support. Documentation: https://developer.android.com/develop/devices/assistant/intents and https://developer.android.com/develop/devices/assistant/custom-intents. Do not advertise voice entry until it is tested on a real device.

Decision for the owner: approve step 1 and the wording of the "new report" feature names, then implementation is a separate PR.

### Step 1 as built

`res/xml/shortcuts.xml` declares the `actions.intent.OPEN_APP_FEATURE` capability and one static shortcut `new_report`, both opening `il.sidewalks.reporter://report/new` in `MainActivity`. The feature name and shortcut label reuse the approved `tile_new_report` string ("דיווח חדש", "New report"). The am, ar and ru feature names are the existing tile translations (derived); the owner approved their use as voice feature names on 15 Nov 2026. The shortcut also shows on a long press of the launcher icon.

Proven on a real system image in CI: the shortcut is registered, points only at the new-report link, and that link makes the shell request the camera (`VoiceShortcutTest`). A plain Play-Store emulator without any account only opens the Google app search page and has no Gemini app, so nothing further can be tested there. Not proven: that Google Assistant or Gemini matches a spoken phrase, and Hebrew matching. Claim voice entry only after a real-device test. Gemini and App Actions also need the app published on Google Play per Google's documentation.
