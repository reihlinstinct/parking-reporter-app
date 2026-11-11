# Assistant entry and AppFunctions

This integration exposes one narrow AppFunction: `startNewReport`. It returns an immutable PendingIntent to the existing camera entry. It does not submit a report, create an invisible draft, read saved reports, or return identity fields, credentials, photos or locations.

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
