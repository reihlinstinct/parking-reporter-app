# Immutable read-only legacy history audit

`scripts/audit_legacy_history.py` compares two explicit 40-character Git commit
IDs: archive and private ledger. It reads local Git objects, not working-tree
files, remote APIs or municipal services. Fetching and verifying branch freshness
is a separate operation. A commit pin proves reproducibility, not currency.

The output is PRIVATE, despite containing mostly hashes. Source paths, recorded
statuses, metadata presence, mappings and hashes can identify incidents. Never
commit it or attach it to a public issue. The CLI requires a pre-existing 0700
output directory, creates one new 0600 file exclusively, and prints fixed status
only. It does not export report prose, plates, case IDs, guard keys or seeds.

Example with synthetic names (substitute verified immutable commits):

```sh
mkdir -m 700 /private/audit
python3 scripts/audit_legacy_history.py \
  --repo /private/archive \
  --archive-commit "$ARCHIVE_COMMIT" \
  --ledger-commit "$LEDGER_COMMIT" \
  --output /private/audit/history.json
```

## What is included

- Canonical report/request JSON in reports/ and requests/, plus app-reservations/
  JSON, from both snapshots, without collapsing distinct source provenance.
- Source-byte fingerprints, status claims, field inventories, recorded text
  candidates, explicit photo hash claims, referenced tracked photo byte hashes,
  report-ID candidates and separately namespaced recorded payload-digest claims.
- Ledger report reconciliation by SHA256 of intake folder/ID, as the helper's
  reserve contract does. Existing 64-hex ledger keys remain hashes; legacy opaque
  IDs are hashed. A hexadecimal-looking opaque ID is inherently ambiguous and
  needs manual reconciliation, not automatic interpretation as proven identity.
- Photo and payload links shown independently. A common photo never establishes
  that two report identities are the same incident. A payload digest is never
  interpreted as a text hash, and a recorded digest is not recomputed proof.
- Ledger-only identities, photo-index ownership gaps, checkpoints, unresolved
  references and text alias groups. An index owner being present is structural
  evidence only, not proof that its report owns those photo bytes.
- Ancillary archive JSON paths and byte fingerprints, explicitly uninterpreted.
  Shared-photo/guard ledger sections are fingerprinted as opaque sections.
- A list of potential script/workflow writer paths, explicitly NOT verified writer
  coverage. Other repositories, historical branches, queued jobs, manual scripts,
  credentials, deployments and mobile clients still require investigation.

JSON and photos have per-object bounds (4MiB and20MiB). Git objects must be
tracked regular files, not symlinks or submodules. Bad canonical records remain
visible as errors; invalid ledger schema blocks the audit. Relative photo paths
must resolve to exactly one tracked path. Absolute paths and parent traversal are
unresolved, even if an operator could map them manually.

## What it does not prove

The known-field extraction is deliberately a candidate inventory. Unknown JSON
fields are not silently asserted to be complete, approved text. Ancillary
payloads, confirmation artifacts and receipts must be examined under their own
schemas before migration. The ledger branch is not assumed to contain the full
archive, and a missing ID may reflect an old layout, rename, different branch or
actual missing evidence. Recorded complete/prepared/attaching statuses are
claims preserved for reconciliation, not permission to retry or refile.

This is not the held-source importer format. There is no importer invocation,
SQLite journal, encryption key, seed, remote CAS writer or activation path here.
Both seedAllowed and activationAllowed are always false, even for an empty audit.
Nothing in a successful run authorizes city submissions or establishes that
migration is safe. Keep every unresolved old identity held until a separately
reviewed reconciliation and all-writer fence close the gaps.

## Proof

Synthetic regression coverage includes immutable pinning despite dirty working
files; separate payload/text namespaces; ambiguous shared photos; source and
photo fingerprints; guard omission; traversal and symlink refusal; duplicate-key
records; unsupported ledger schema; private exclusive output; branch-only records;
ancillary artifact limits; and missing/malformed photo owners.

No real-history fixture, audit output or private identifiers belong in this repo.
