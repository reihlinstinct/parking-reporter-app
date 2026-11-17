# Hybrid runtime integration: inactive delivery slice

Depends on the optional author-input contract. Not an active filing workflow.

```mermaid
flowchart TD
 A[Owner-bound private ZIP] --> B[Whole-batch offline enrichment]
 B --> C[Both lanes preflight before writes]
 C --> D[Encrypted remote CAS journal reserve]
 D --> E[Remote creating intent acknowledged]
 E --> F{Selected lane}
 F --> G[UI create boundary - not implemented]
 F --> H[Production Kotlin direct CreateCase]
 G --> I[Remote case receipt CAS]
 H --> I
 I --> J{Photos planned?}
 J -->|none| K[Complete]
 J -->|yes| L[Remote attaching intent CAS]
 L --> M[Same-case AddFile]
 M --> N[Remote attachment success CAS]
 N --> K
```

## Implemented in this slice

- `live_batch_persistence.py`: existing journal transactions backed by authenticated
  AES256GCM snapshots to fixed `parking-reports/journal/live-batch.sqlite.aesgcm`
  on main. GitHub Contents SHA compare-and-swap makes stale writers fail. Every
  reservation/intent/result transaction must be acknowledged remotely before
  returning. Save failure poisons the local instance. Missing/corrupt/oversized
  remote journal blocks; no implicit bootstrap, reset or retry. SQLite FULL sync
  and consistent backup are used. The original768KiBplain cap
  was the original format's limit. The bounded V2 compression format now separates
  an8MiBdecoded SQLite limit from a768KiBcompressed limit; see
  `JOURNAL_CAPACITY.md`. Actual full-history capacity is still unverified.
- `live_batch_hybrid.py`: injected UI/direct ports, one or two UI report IDs,
  whole-batch preflight, whole-batch reserve, sequential per-write durable state.
  No adapter fallback/resume/release. Zero-photo CreateCase completes. Mock tests
  cover preflight failure, create uncertainty and attachment failure across lanes.
- `BatchMunicipalBridge.kt` in test sources: private stdio IPC to production
  MunicipalClient/MunicipalPayloads/StreetDirectory. Compile once via
  `:app:writeBatchBridgeClasspath`, not a Gradle invocation per report. Read-only
  login, offline report preflight, CreateCase and AddFile use production code.
  It prints only private JSON IPC, not stdout intended for public CI. No live
  caller is enabled. Adapter death/error stops the session; no automatic retry. Private subprocess
  I/O has a single absolute write/read deadline; partial output cannot hang a
  line reader and any error kills/poisons the process. Six real subprocess
  tests cover partial replies, no stdin consumption, process death and malformed
  responses. Two additional mocked process tests cover cleanup timeouts and exit races. Photo preflight verifies hashes, format and full ImageIO decode
  before login, with8192-edge / 4-million-pixel / 10-MiB limits before allocation.
  These JVM checks do not establish UI-adapter parity.
- `live-batch-hybrid.yml`: hard-disabled **preparation-only** dispatch. Its job
  cannot start or receive environment secrets. Activation requires separate review
  and verification of environment reviewer protection. Inputs bind immutable private
  ref/path, approved outer/inner package and address-directory digests. The inactive
  job definition builds bridge before
  private input, stages/validates the ZIP, encrypts the effective manifest and
  uploads only encrypted preparation evidence. It receives no city credentials
  and cannot call the controller/bridge or make a municipal write. Missing
  private read token/topic policy is a blocker, not silently provisioned. The read
  variable maps only to protected `BATCH_PRIVATE_CONTENTS_TOKEN`, a narrow
  fine-grained credential for private parking-reports Contents read/write and
  Metadata read-only. The credential was provisioned separately under owner
  authority on November17 and expires December17,2026; it is not a repository
  secret and does not grant municipal access.

## Not delivered and activation blockers

1. UI create/attach boundary adapter. The existing all-in-one single-report UI
   workflow cannot safely be called as a black box: it would have separate app
   state and could write before the shared journal acknowledged intent.
2. Persistent journal initial provisioning, verified old-case import and journal
   key approval remain blocked. The narrow private credential is provisioned
   separately in the protected environment; no broad account PAT is copied
   into Actions. No journal was created by this slice.
   A fresh journal must include existing accepted/uncertain identities, not erase
   duplicate history. Old14dayartifacts alone are insufficient.
3. Live credential injection, UI-lane bounded decoding and outgoing attachment
   policy parity, verified topic policy freshness and exact effective-manifest
   approval binding. Input validation is not permission.
4. Read-only production login probe in the final network environment, tunnel
   setup/cleanup and verified pre-write fallback policy. Prior direct logins have
   succeeded, later tunnel fallback was needed; current future egress is unknown.
   No new live probe or municipal write was run for this slice.
5. Shared concurrency across ALL old/new live entry points. New preparation uses
   the current UI group, but the legacy engine has its own group. Do not activate
   a writer until both converge and the CAS journal owns both lanes.
6. Cache550consumer integration and cold fallback. Producer is merged, first
   scheduled output not yet verified/benchmarked. No speed savings claimed.
7. Full UI/direct mock HTTP integration, authenticated backup rollback
   protection beyond CAS, malformed/oversized image decode
   matrix, leak tests on actual encrypted evidence inventory and supervised
   owner-approved live proof. Current controller tests use injected mocks;
   persistence tests cover save failure and reopening. A real hybrid controller
   child-process exit at each of four UI/direct create/attach boundaries proves
   encrypted file-backed remote intent survives and forbids replay on reopening.
   This is not a full emulator/UI-adapter process-death proof.

No new workflow pretends these gates are satisfied. Existing single-report route
remains unchanged. The author guide remains honest about the missing submit step.


## Next-batch acceptance criterion: zero PRs in the filing loop

The next operational batch must need **no source PR and no data PR**. One-time
implementation changes still follow independent exact-head review and green CI.
Per batch: prepare approved private ZIP, commit it directly to the existing
private data repository, bind its full immutable commit/path/outerSHA256, then
protected workflow_dispatch. Explicit approval of exact text/location/topic/photo
bytes remains required and is bound by the generated outerdigest. Input data is
not source code. No private CI run or paid runner is needed. A preparation-only
workflow does not satisfy this criterion; no live route is claimed ready until
both lanes, durable oldidentityjournal and credential/approval boundaries work.

### Inactive workflow gate

The preparation job is hard-disabled in source (`if: false`). Dispatch cannot
expose its environment secrets while this route is inactive. Activation requires
a separate reviewed change and verification of required environment reviewers;
no environment protection/settings were changed here. Preparation also requires
independent approved outer package, decrypted ZIP and address-directory SHA256
values. The fetch layer already checked outer approval before this change; the
entry script now repeats it explicitly and no longer derives inner/directory pins
from the bytes being checked. A rerun counter is not access control.

### Encryption parameters and dispatch visibility

Both inactive preparation encryption and decryption explicitly pin PBKDF2 with
SHA256 and 200000 iterations; they do not rely on an OpenSSL digest default.
Dispatch input hashes are approval fingerprints, not secrets. On a public
repository, treat every dispatch input as visible: no text, address, plate,
credentials, private filenames containing personal data, or decryption keys may
be entered there. A digest can still correlate a known file; do not represent
hashes as anonymous or confidential. Actual private bytes remain in the private
repository and encrypted artifacts. Approval fingerprints do not grant access or
permission by themselves. This workflow remains hard-disabled; privacy review
of the final operational input path is still required before activation.

## UI boundary adapter (inactive slice, #558)

`scripts/live_batch_ui_adapter.py` implements the UI lane's create/attach port and
`BatchWriteGate.kt` (`GatedMunicipalClient`) is the client the app flow would be
given. The recorded UI flow still runs in the app, but every municipal write
(contact + CreateCase as one create boundary, then each AddFileToCase) waits for
a grant that the adapter issues only from inside `create()`/`attach()`, which the
controller calls after the remote intent was acknowledged. A refusal, error or
failed write closes the gate for good; there is no retry or lane fallback.
The all-in-one coordinator is not bypassed or rewritten, it simply cannot write
outside these boundaries. The approved 1-2 UI selection is bound by
`selection_digest(batch digest, ids)`; the adapter refuses any other set and
requires a photo preflight (hash, format, bounded decode) from the shared checks.

Not delivered: the real adb/instrumentation transport and harness test, UI-lane
outgoing-photo policy parity (the app sends its reviewed JPEG derivative for the
first photo, so its bytes differ from the manifest original; later photos are
checked against the manifest digest), controller wiring of `selection_digest`
into the preparation binding, and any live run.
