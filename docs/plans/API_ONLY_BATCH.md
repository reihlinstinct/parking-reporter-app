# API-only multi-report CI over VPN

Status: inactive implementation. No municipal write, workflow activation or journal provisioning.

## Ordered slices

1. Issue #596: separate API-only sequential controller using existing journal and API ports. Whole-batch preflight and durable reservations before writes; one CreateCase per report, N same-case attachments. Stop on uncertainty. No mandatory UI selection. This is the current slice.
2. Issue #597: isolated main-only CI runner, production Kotlin bridge, private immutable approved input, bounded VPN setup/cleanup and read-only final-network auth probe. Initially hard-disabled. Mock HTTP integration and encrypted evidence inventory first. No browser or screen driving. Android emulator is not needed for the existing JVM production bridge if the CI VPN egress works; an Android instrumentation API harness would be a separately reviewed fallback, not permission to bypass the network edge.
3. Issue #598: complete history reconciliation, approved durable encrypted journal/key/seed, every writer under the same fence and concurrency group, effective-input approval, supervised write proof and activation review. Capacity change #580 is a dependency, not proof the historical inventory is complete.

The runner and history reconciliation can proceed while the controller is reviewed. The existing hybrid route remains unchanged; API-only execution does not pretend a missing UI transport is complete. Multi-photo engine #552 is merged, but its presence alone does not activate batch submission.

## Critical path to a real batch

- Verify current read-only login through the final CI VPN. The past direct-route 403 does not establish present VPN write availability.
- After explicit execution approval, inspect an actual CreateCase plus attachment response over that same route. Login success is not proof that writes pass the edge. On 403, timeout or uncertain receipt, stop and reconcile; no UI fallback or blind retry.
- Reconcile accepted and uncertain old identities, including recent operational filings, before provisioning the shared journal. Confirm source completeness, key/credential authority and rollback procedure. A fresh empty journal would erase duplicate protection and is not acceptable.
- Bind exact approved effective inputs and current topic/address/photo policy. Keep credentials and private inputs out of public logs and dispatch fields.
- Green exact-head CI, independent full-source PASS and manager merge precede activation. Real submissions require separate authority; development approval is not execution approval.
- Start with a small supervised batch and inspect encrypted per-report receipts before larger volume.

Review latency, edge rejection on writes and incomplete old identity coverage are schedule risks. An inactive controller passing mock tests is progress, not a claim of an operational filing route.

## Controller boundary

`run_api_batch` has no command-line entry point, default adapter, credentials, VPN operation or workflow caller. Its caller must supply a validated effective manifest, an authorized API adapter and a journal whose return means durable remote acknowledgement. The existing local journal in tests demonstrates ordering and held-state semantics, not remote storage readiness. Any exception stops the batch. Successful responses followed by failed receipt persistence leave intent held. There is no resume, release, replacement case or automatic attachment retry.

Review boundary: this controller rejects duplicate report IDs and malformed/duplicate
photo identities before preflight. Full approved-manifest validation remains intake's
job. A supplied adapter is trusted executable code: the controller cannot sandbox
its preflight method. Production wiring must bind only the reviewed read-only
preflight adapter, never arbitrary caller-supplied code. No caller is activated here.
Synthetic report/batch/case IDs in these offline tests are non-numeric labels, not
national IDs, payment identifiers or real municipal references; Luhn is inapplicable.
Review dependencies at the same head: scripts/live_batch_input.py and
scripts/live_batch_journal.py, including reservation, duplicate and intent persistence.
