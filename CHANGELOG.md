# Changelog

All notable changes to this project are documented here. The format follows Keep a Changelog and the project uses Semantic Versioning: bug fixes bump PATCH, backward-compatible features bump MINOR, and breaking changes bump MAJOR.

Every release gets a `v<version>` git tag, a matching entry here, and release notes in Hebrew and English. Every bug fix ships with a regression test.

## [Unreleased]

## [0.4.7] - 2026-10-22

### Other changes

- build(deps): bump pillow from 12.3.0 to 13.0.0 (#30) (1564de0)

## [0.4.6] - 2026-10-22

### Other changes

- ci: add python tooling smoke test (venv + synthetic intake suite) (#37) (69faeff)

## [0.4.5] - 2026-10-22

### Other changes

- build(deps): bump ossf/scorecard-action from 2.4.4 to 2.5.0 (#31) (21d44c9)

## [0.4.4] - 2026-10-22

### Other changes

- build(deps): bump kotlin to 2.4.21, ksp to 2.3.12, room to 2.8.5 (curated compatible set) (#35) (772e831)

## [0.4.3] - 2026-10-22

### Other changes

- build(deps): bump androidx.fragment:fragment-ktx from 1.8.6 to 1.9.1 (#24) (837a875)

## [0.4.2] - 2026-10-22

### Other changes

- Update release.yml (#34) (9d60ffe)

## [0.4.1] - 2026-10-22

### Other changes

- build(deps): bump github/codeql-action/upload-sarif (#16) (cf45bae)

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
