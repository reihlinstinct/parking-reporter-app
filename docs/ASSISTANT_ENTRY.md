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
