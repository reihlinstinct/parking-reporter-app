# Separate preview installation

Build with `sh gradlew assembleDebug -PpreviewArm64 -PpreviewSideBySide`.
This opt-in uses application ID `il.sidewalks.reporter.preview` instead of
`il.sidewalks.reporter` and a visibly different launcher label. Normal builds
retain their original identity. The preview owns separate settings, files,
database, permissions and Android Keystore aliases. It does not read, migrate,
replace or delete the existing app's data. Enter a separate reporter profile.

The Android workflow publishes the additional
`phone-arm64-side-by-side-preview-apk` artifact. This fallback lets testers
retain an old debug-signed installation when its signing key is unavailable.
It does not solve stable future signing (issue673), and another newly signed
preview may still refuse an in-place update of this preview. Never instruct a
tester to uninstall or clear data as an automatic workaround.

Both apps currently handle the same report deep-link scheme; choose the
visibly labelled preview when Android offers a handler. No live submission is
part of build or installation verification.
