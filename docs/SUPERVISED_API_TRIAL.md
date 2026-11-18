# Isolated supervised GPS-only API trial

Issue #603. This is not general API batch activation and is not the earlier pre-case retry.

Owner approved one supervised submission on 18 November 2026. The activation
pins one immutable encrypted package in both workflow and Python, expires at
16:00 UTC that day, and does not activate the general batch/journal writer.
Only main, the exact package and explicit confirm/fence inputs may proceed.
Repeated dispatches hit the same create-only original-photo claim and cannot replay.
Merge follows the owner's CI-green findings rule, not a claim of independent PASS.

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

- [ ] Exact-head full CI and review under the owner-approved findings rule.
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

Current credential scope: BATCH_PRIVATE_CONTENTS_TOKEN must read encrypted input
and write encrypted claims in the fixed private records repository. The fetch
alias PRIVATE_LOOKUP_READ_TOKEN does not reduce that scope. Owner deferred
credential splitting to issue #607; this is not a claim of read-only privilege or
activation approval. No credential/settings changes are made here.

Trial activation resolves the trial-specific parts of #611 and #612: Python
validates exactly the bridge envelope and helper encrypted_only mode refuses
plaintext before network. Normal batch/derive paths are unchanged and remain
inactive. Topic 1137 is pinned against the independent ReportCategory non-plate
policy as well as the live catalog; unknown catalog metadata cannot widen it. Python success/mock fixtures now use an in-envelope synthetic point;
none exercises the real bridge or municipality. CoordinateX/Y are integer ITM
values by Itm.toItm's Pair<Int, Int> contract and five executed bridge tests.
Existing scoped token and key are retained (#607/#608). VPN drop or failed
post-create save holds the claim, requires manual reconciliation, and never
permits an automatic replay. No broader credentials or settings are provisioned.

Expiry is a request-initiation cutoff, checked after route verification immediately
before create and attach. An in-flight municipal request can complete after the
cutoff and cannot safely be recalled. Its receipt is durably saved; the next write
is refused, the incident remains held for manual reconciliation, and no replay is
allowed. Production Routed boundary tests exercise route-check time crossing and
a create returning at cutoff, preserving its receipt and preventing attachment.

The complete writer inventory and current acknowledgements are a private owner
execution check, not a source-review assertion. No dispatch is allowed until the
owner supplies the complete current inventory and explicitly verifies every route
is fenced. Earlier partial acknowledgements do not establish completeness.
