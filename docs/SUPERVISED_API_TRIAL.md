# Isolated supervised GPS-only API trial

Issue #603. This is not general API batch activation and is not the earlier pre-case retry.

The workflow is hard-disabled with `if: ${{ false }}`. No dispatch, remote claim, key,
input upload or municipality request is part of this change. Activation is a separate
reviewed change after exact-head green CI and independent complete-source PASS.

## One incident only

The private ZIP has exactly `trial.json`, `original.jpg`, and `photos/approved.jpg`.
The outer encrypted package, exact JSON, original photo and reviewed outgoing JPEG
are separately SHA-256-bound. The package is read from a full immutable commit in
the fixed private records repository. No public report text, photo, coordinates,
person data, token or receipt is placed in this repository or workflow logs.

The JSON has `report`, `originalSha256`, `outgoingSha256`. The report has only
`reportId`, `text`, `subjectCode`, `latitude`, `longitude`, `locationDescription`, and
one `photos` entry with `path: photos/approved.jpg` and its `sha256`.

The experimental payload leaves street code, street name and house number empty.
It sends ITM derived from exact approved GPS and approved junction/location text.
Municipal acceptance of that variant is unknown. Normal app/batch address
validation is unchanged. A refusal is a final stop, never permission to guess an
address, fall back to UI or retry. A topic requiring a plate is refused.

## Durable one-shot boundary

Before dispatch, the operator must reconcile accepted/unknown history for this
incident and fence all other writers. The confirm input records that check; it
does not prove or grant permission. Shared concurrency serializes the existing CI
writer, but is not a fence for a phone or another workflow. No dispatch is allowed
if the operator cannot establish that boundary.

The initial private claim is a create-only Contents PUT keyed by the ORIGINAL
photo SHA-256. An existing claim refuses. Each later update uses the exact last
acknowledged git-blob SHA as a compare-and-swap. Records are AES-GCM encrypted
with PBKDF2-derived keys. Unacknowledged/failed persistence poisons the instance;
there is no automatic resume, deletion or claim reset.

Order: offline photo/payload validation, read-only login/catalog validation,
acknowledged reserved record, acknowledged creating intent, contact and case
request, acknowledged created receipt, acknowledged attaching intent, one photo
attachment, acknowledged complete receipt. Any ambiguity holds the incident for
private reconciliation. A case GUID is required for attachment; a missing case
number remains missing and is never invented.

The bridge is already compiled before secrets arrive. Its private environment
contains municipal identity only, not the repository token or encryption key.
The existing bounded L2TP transport is mandatory. Resolved municipal IPv4 routes
must select the VPN before login, creation and attachment. There is no direct
route fallback. Only tunnel negotiation can retry, never a municipal operation.

## Evidence and activation checklist

Public logs contain only a constant final outcome. Private diagnostics, request
results and staging are encrypted before artifact upload on success or failure.
Always-cleanup tears down the tunnel and removes plaintext staging. The durable
claim remains held even if artifact collection fails.

Before activation:

- [ ] Exact-head full CI and independent complete-source PASS.
- [ ] Exact outgoing derivative inspected and approved, original identity retained.
- [ ] Exact report JSON and blank-address experimental mechanics approved.
- [ ] Current incident history checked; no accepted or unknown duplicate.
- [ ] Other writers demonstrably fenced; shared CI concurrency alone is insufficient.
- [ ] Fixed private input uploaded encrypted and pinned to immutable commit/digests.
- [ ] Protected environment has narrowly scoped private claim token and data key.
- [ ] Separately reviewed activation of this one workflow, no general writer activation.
- [ ] After one attempt, privately reconcile Create/Add responses and encrypted claim.

Synthetic tests exercise actual encryption, HTTP claim create/CAS/poison behavior,
archive inventory and digest refusal, durable intent/write ordering, every save
failure, API uncertainty, GPS-only payload, photo validation, and in-process
create/attachment burning. They make no municipal call. Full Gradle/Android and
workflow execution remain CI gates, not local claims.
