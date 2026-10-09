# Release checklist

No app release is authorized by this checklist. Each gate needs recorded evidence and owner approval where applicable.

- [ ] M0 mobile connectivity verified with login-only diagnostic and redacted result; no contact/case/photo writes.
- [ ] Reproducible pinned Android build, unit tests and emulator/e2e checks passing.
- [ ] M1-M4 acceptance criteria met, including offline helper parity and manual fallback.
- [ ] Credential provisioning resolved. No shared municipal key in APK/source; signing material handled outside Git.
- [ ] Keystore/encryption, backup exclusions, logs, FLAG_SECURE and lost-device threat model tested on target devices.
- [ ] Approved M5 report proven with receipt, crash/timeout reconciliation and duplicate blocking. Existing pipeline fallback retained until proven.
- [ ] MIT source license is included. Check dependency/model licenses and redistribution terms before distribution.
- [ ] Play privacy policy, data-safety declaration, permissions, supported devices and release signing reviewed against actual behavior.
- [ ] Versioning, supported versions, vulnerability intake and release notes defined.
- [ ] Optional M6 separately opted into; full-image masking review and platform permissions/page-role behavior verified, no unapproved social posts.
- [ ] Main branch PR/check protection enabled if the account plan supports it; any limitation recorded.
