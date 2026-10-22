# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog and the project uses Semantic Versioning: bug fixes bump PATCH, backward-compatible features bump MINOR, and breaking changes bump MAJOR.

Every release gets a `v<version>` git tag, a matching entry here, and release notes in Hebrew and English. Every bug fix ships with a regression test.

## [Unreleased]

## [0.4.0] - 2026-10-22

### Added

- Offline OCR development preview: on-device plate recognition candidates with manual review; no cloud photo analysis.
- PRPKG2 encrypted intake prototype (Tink ECDSA/ECIES), synthetic transport demo and offline receiver reference.
- Keystore AES-GCM encrypted reporter settings (first name, last name, phone, ID, email), no plaintext fallback or backup.
- Room ledger with stable report IDs, original hashes and review binding.
- Documentation set: DESIGN, ENCRYPTED_INTAKE_PROTOCOL, OFFLINE_OCR, OFFLINE_RECEIVER, RELAY_OPTIONS, RELEASE_CHECKLIST, ARCHITECTURE with Mermaid diagrams.
- Repository security baseline: Dependabot (gradle, github-actions, pip), CodeQL default setup, OpenSSF Scorecard, secret scanning with push protection, private vulnerability reporting, SHA-pinned workflows.

### Notes

- Development preview only: no live transport, no municipal endpoint or credentials in the client, no production keys.
