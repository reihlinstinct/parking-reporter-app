# Contributing

## Workflow

1. Open or select an issue with scope, acceptance criteria and risks. Security-sensitive reports follow SECURITY.md.
2. Create a focused branch, such as `feat/m1-capture-shell` or `fix/approval-invalidation`.
3. Make small, meaningful commits: `docs:`, `test:`, `fix:`, `feat:` or `chore:` with a clear imperative description. Do not mix unrelated changes.
4. Open a pull request linked to the issue. Explain behavior, tests, privacy effects and any design changes.
5. Review the diff, run the applicable checks and squash-merge only after authorized review and passing CI. Delete the merged branch. Do not bypass checks or push directly to main.

## Standards

- Kotlin typed interfaces and explicit state transitions; English code/docs and Hebrew report text.
- Unit tests plus functional/emulator-based e2e tests for user flows. Mock network transport; CI never calls the municipal API or creates reports.
- Regression tests for approval invalidation, duplicate photos/IDs, crash checkpoints, uncertain outcomes and no-retry behavior.
- Synthetic photos, plates and reporter profiles only. No private evidence, personal details, secrets, generated build output or junk files.
- Update README, design and release notes when behavior changes. Record material architectural choices in a short design note.
- Pin toolchain and dependencies, verify Gradle distribution checksums and review dependency licenses before release.

## Current checks

With Python 3 installed, run `python3 scripts/check_repository.py` from the repository root. This checks the scaffolding; it is not an Android test suite or a full secret scanner.

Run the Gradle commands in README.md for Android build, lint, unit tests and emulator tests. Never use a skipped or placeholder job as evidence of correctness.
