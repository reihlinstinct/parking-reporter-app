# VPN-loss persistence design

Refs #609. This is a proposal, not an active fallback or execution approval.
Source boundary: `supervised_api_trial.py:execute_trial`,
`PrivateTrialClaim.save`, `live_batch_persistence.py:PrivateJournalContents`.

## Invariants

Municipal egress remains on the verified VPN. A route failure never selects a
direct municipal path. Every effect requires a durably acknowledged intent.
An unacknowledged save poisons the local writer. A missing response does not mean
nothing happened. Accepted or uncertain CreateCase/AddFile is never replayed.

| Loss point | Latest durable state | Required stop/recovery |
| --- | --- | --- |
| Before reserved acknowledgement | none or reserved, unknown | No municipal request. Reconcile claim before another attempt. |
| After reserved, before creating acknowledgement | reserved or creating, unknown | No CreateCase. Hold claim, do not reset it. |
| Creating acknowledged, request times out | creating | Case may exist. Retain encrypted evidence and privately reconcile. |
| Create returns, receipt CAS fails | creating, local receipt known | No attach. Retain exact returned IDs; no second create. |
| Created acknowledged, attaching CAS fails | created or attaching, unknown | No attach request; reconcile current claim before any decision. |
| Attach times out or success CAS fails | attaching | Attachment may exist. No automatic attach retry or case recreation. |
| Final complete acknowledged | complete | Return the durable result; no new effect. |

## Candidate separation of transport responsibilities

A future receipt transport could persist an encrypted result through a fixed
private endpoint independently of municipal VPN routing. That needs a separately
reviewed network namespace or sidecar design. It may not disable the kill switch,
add a general non-VPN route, export municipal credentials or permit arbitrary
URLs. The endpoint, credential scope and encrypted schema must be fixed and
approved before implementation. It is not a reason to add a second live writer.

A fallback acknowledgement must bind the original identity, run, operation,
exact returned receipt and monotonic state. A local file or uploaded artifact
alone is evidence, not a remote claim acknowledgement. If the private journal is
unavailable, a fallback evidence upload cannot allow the next municipal effect.

## Fault-injection acceptance for any implementation

Use a fake municipal server and disposable private-store fake. Inject loss on
both sides of every acknowledgement and after server acceptance before client
response. Check exact Create/Add counts, no direct municipal route, a poisoned
writer, preserved encrypted receipt and no automatic replay. Test fallback
endpoint outage, altered receipt digest, stale CAS, duplicate acknowledgements,
process death, restart and concurrent writers. Confirm recovery never treats an
unreadable/missing store as empty history. The current repository mocks prove
only their named paths, not this proposed sidecar or real VPN failure behavior.
