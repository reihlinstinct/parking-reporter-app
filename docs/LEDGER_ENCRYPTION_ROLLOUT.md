# #88 storage wiring: staged review and activation plan

Status: proposed implementation sequence. Extends, does not replace,
`LEDGER_ENCRYPTION.md`. This document changes no storage or app behavior.
The isolated record envelope is in PR #280. No claim of encrypted storage is
valid until the final activation gates and raw-file tests pass.

## Current boundary inventory

`ReportDatabase` is version 1. `ReportRecord.draftJson` and `reviewedDigest`
are Strings. `ReportDao.reserve` has ABORT-on-conflict and the unique
`originalSha256` index; `store` increments revision with an expected-revision
predicate. `AppShell` constructs Room directly, then reloads, sweeps evidence,
reserves imports, saves drafts and opens records through that DAO.

The rollout must replace all those consumers together at activation. Installing
an encrypted entity without replacing direct DAO consumers is not a safe
intermediate release. Tests that open the existing Room database directly must
move to the same repository/session boundary, not a plaintext test escape hatch.

## Small review units

1. **Envelope (#280).** Pure AES-256-GCM with column/record/revision AAD.
   Existing v1 storage and consumers unchanged. Already prepared separately.
2. **Inactive storage codec.** A typed encrypted row plus strict String/BLOB
   conversion above the envelope. Plaintext identity/hash/path/revision remain
   unchanged. Explicit tombstoned result contains no draft or digest. Tests:
   null digest, strict UTF-8, column/row/revision swap, any decrypt failure,
   tombstones preserved and never decrypted. No Room builder or migration yet.
3. **Inactive v2 DAO/repository.** Separate candidate Room database type and
   synthetic database name only; never opened by production. ABORT unique hash
   reservation; compare-and-swap update binds both envelopes to next revision;
   `AND tombstoned = 0`; overflow fails before write. No decrypt-filtered lists.
   Repository session owns key copy; close zeroes it and blocks every operation.
   One bad row poisons the session, including later reserve/store. Duplicate
   checks remain SQL/index-based and cannot create a second row after decrypt
   failure. Synthetic Room reopen/conflict/duplicate/tamper tests required.
4. **Durable migration journal and pure restart reducer.** Authenticated,
   strict versioned journal; explicit sync/rename/directory-sync/read-back.
   Implement every restart row in the parent design as a closed state machine.
   Unknown/schema/key/journal contradictions stop and retain files. No state
   inferred from mtimes. Fault-inject each durability boundary.
5. **Migration proof and inactive migration factory.** Exclusive process and
   OS locks; schema identity/user_version/canonical source/stat/inventory proof
   before custody creation for history. Migration key proof is internal and
   unforgeable by callers, not a boolean. No production caller in this unit.
6. **Synthetic Room v1-to-v2 migration.** Within the Room upgrade transaction:
   secure_delete ON, rebuild BLOB table with tombstoned DEFAULT0, encrypt and
   decrypt-verify every row, then rename/index. Room validates v2 schema before
   commit. Cleanup is VACUUM then WAL checkpoint then byte scan before complete.
   Golden schema1/schema2, kill points, wrong key, header corruption and zero
   file tests must pass. Never use destructive migration/corruption defaults.
7. **Separate recovery proof.** User-confirmed key-loss recovery retains every
   row, hash, original path, ciphertext and submission-state file. Tombstone
   UPDATE and new custody commit have separate durability domains. A reviewed
   recovery restart table covers both orderings and every crash point before
   any implementation is activated. New custody only after secure lock screen
   restored and bound to the exact tombstone set. Tests prove duplicates still
   abort after reopen and sweep keeps every referenced original.
8. **Lifecycle/UI wiring, synthetic activation tests only.** One authenticated
   session/factory replaces direct Room access everywhere. ON_STOP/screen-off
   waits for transaction finish/rollback, closes, zeroes key, blocks operations.
   Startup sweep runs only after complete ledger open and all-row validation.
   Locked/migrating/stopped state blocks import, reserve, store, open and send.
   Tombstones are visible unreadable rows, not absence; submission case details
   remain available without re-sending. Instrumented tests port duplicate guards
   and full-flow tests to this boundary. No hidden plaintext fallback.
9. **Activation PR.** Only after all above are independently reviewed and green.
   Show the full builder/caller inventory, migration/recovery matrix receipts,
   backup exclusions and no-destructive-fallback scan. Get separate explicit
   approval before migrating any real installation. A code merge or this plan
   alone does not authorize a real user's migration or recovery.

Steps may split further for review. Steps2/3 are stacked on the envelope; later
units must not silently activate earlier inactive code. Keep schema1 production
behavior until the complete activation PR, rather than letting a release bot
ship a partially connected migration.

## Non-negotiable gate

While independent review is unavailable: PRs, local tests and CI only, no merges.
When available: green CI and approval at the exact head, then the normal merge
gate. This plan is not approval of its own implementation. No live submission,
production migration, key reset, deletion, or data rewrite is part of this work.

"No data loss risk" is an engineering goal, not a guarantee. Before activation,
show tested retention on every stopped/crash state; unresolved states block it.
No old app rollback may open v2 for writes. No app reset affordance may erase
the duplicate guard. Original-photo and submission-state confidentiality remain
out of scope as stated in the parent design, and must not be misrepresented.
