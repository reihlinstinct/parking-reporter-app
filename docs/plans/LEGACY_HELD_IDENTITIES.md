# Offline legacy held identities

This slice supplies an offline journal representation. It does not import the
real archive, create a remote journal, approve a seed, or activate any writer.

## Candidate, never proof

Historical records can share text and photos across follow-ups and reservations.
A unique text/photo row per report would either reject valid history or silently
drop a source. Schema 2 adds many-to-many held aliases keyed by identity kind,
digest, immutable archive commit, source and field. Full normalized provenance
also retains the source file digest, recorded status and unresolved references.
A photo hash without matching bytes remains a held claim. A recorded text field
remains a candidate, not an assertion about the exact submitted municipal text.

`BatchJournal.hold_legacy_sources` accepts normalized records only in an offline
journal with no runtime batches. One transaction imports all records or none.
Conflicting sources are refused, not overwritten. Shared candidates retain every
source/field. Archive versions have separate provenance. There is no release or
promotion-to-complete operation. Historical status never creates a live receipt.

New reservations check report ID, text digest and each photo digest against held
aliases inside their whole-batch transaction. Any match refuses the entire batch
before its first create. Missing photo bytes do not remove a held photo claim.
Existing runtime identity uniqueness remains unchanged. Schema-1 migration
preserves existing runtime rows; malformed existing schema is refused rather
than rebuilt. `DurableBatchJournal` refuses the import operation: this runtime
class cannot provision or bootstrap the remote journal.

## Normalized record contract

Each record contains `source`, `sourceSha256`, `status`, `reportIds`,
`textCandidates`, `photoCandidates`, and `unresolvedReferences`. Candidates contain
`field` and `sha256`. The call separately pins the 40-character archive commit.
Report IDs are hashed for lookup while their source values remain in encrypted
private provenance. This format contains no instruction or live-action field.

## Still required before any real seed

- Re-read the immutable private archive, verify source bytes and references, and
  reconcile every accepted, uncertain and reserved incident. Do not substitute
  recorded text candidates for proof of exact submitted text.
- Cover existing legacy ledgers and all old/new writers. An archive-only import
  cannot establish complete coverage or prevent a writer bypass.
- Independently verify completeness, identity normalization and unresolved
  aliases. Test capacity with the full set without dropping records to fit.
- Obtain and verify the separate bootstrap/key authority and encrypted remote
  provisioning plan. Prove remote acknowledgement, concurrency and rollback
  boundaries before enabling a writer. No empty-journal fallback is acceptable.

The tests use synthetic records only. They cover shared provenance, each identity
kind, missing-byte claims, reopen, transactional rollback, conflict refusal,
schema migration, remote-import refusal, and both-lane write blocking. They do
not establish real historical coverage or authorize a production seed.

## Capacity is still a blocker

The persistence limit remains 768 KiB of plaintext SQLite. A synthetic 640-source
history with two text and two photo candidates per source exceeds it. The
regression proves that every alias remains locally represented and encryption
refuses the oversized snapshot. This deliberately blocks remote seed; it does
not silently raise the cap, truncate history, or claim that production storage
can hold the full archive. Storage migration/capacity design remains separate.

This PR initially targeted the inactive hybrid branch. Its earlier smaller check
list was not full CI proof because main-target workflows did not run. It is now
retargeted to main after incorporating the parent hybrid fixes. Require the
normal full CI on the new head and independent exact-head review before merge;
land the parent hybrid slice first. No earlier stacked-base check list is used
as a substitute for those checks.
