# Persistent preview signing plan

Refs #673. Design only. No keystore is created or rotated, no secret is provisioned,
and no existing app/data is deleted. APK source review and CI success do not
establish a compatible signing identity with the app already on a phone.

## Current limitation

CI debug builds can use a newly generated runner debug keystore. A later APK with
the same application ID but another certificate cannot update that installation.
A distinct preview application ID avoids replacing the old app, but does not fix
future updates of that preview. Never recommend uninstall/clear-data as a
signature workaround when local drafts or settings may exist.

## Proposed durable preview identity

Use a dedicated preview-only signing identity, separate from any future store
release identity and separate from the normal app if its original identity is
not recoverable. Keep the private keystore and password outside Git, APK assets,
logs and build artifacts. Use a protected CI secret or an approved secure signing
service only after free entitlement, owner authority and recovery are verified.
Do not pass signing secrets to fork or untrusted pull-request code.

A reviewed build on trusted main can copy signing material into an ephemeral
private file, sign the exact reviewed build, record only certificate fingerprints,
verify the signed artifact and remove the temporary file on every exit. A signing
step must not enable municipal credentials or a live report test.

## Acceptance before use

- Owner selects preview identity, secure storage, access limits and recovery route.
- Record the existing installed preview certificate if available, without claiming
  a source commit or filename proves that certificate.
- Install version A with synthetic drafts/settings, update to version B signed by
  the same identity, verify data/permissions and the actual installed certificate.
- Prove a different certificate is refused without changing installed data.
- Test cold runner signing, missing/wrong credentials, cleanup after failure and
  secret-free logs/artifact inventory. Retain the public certificate fingerprint.
- Keep stable preview and production app names/IDs distinct and explain migration
  limits. No automatic profile copy is authorized by this plan.

An unreviewed APK does not become reviewed by signing it. A reviewed debug APK
also does not become stable-signed merely because it was merged or published.
Both source provenance and signing identity need separate recorded proof.
