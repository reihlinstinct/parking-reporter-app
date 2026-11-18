# One fresh branch per approved car submission

This is an execution lane, not approval to file a case. Get the user's approval
for the incident, exact words, location and photo before preparing a dispatch.
Existing accepted cases must never be recreated or recovered by this lane.

## Branch and environment

Create `submission/<first 24 hex characters of original photo SHA256>` at the
current main commit only. Make no code or data commits on it. Do not open a PR
from it or merge it back to main. The immutable encrypted input stays in the
existing private data repository; its exact ref, path and approved digests are
manual workflow inputs. Do not put a plate, address, wording or photo in the
public branch, inputs or logs.

The separate `submission-no-review` environment allows only the `submission/*`
branch pattern and has no reviewer or wait timer. The protected main environment
is unchanged. Provision the five reporter fields and the private-repository
contents token in this new environment through secure setup. Repository-level
VPN, municipal-login and encryption-key secrets already exist. Never widen a
private-token grant to use a broad account token as a shortcut.

## Execution

Dispatch `submission-branch.yml` from that fresh branch exactly once. Confirm
`FILE_ONE_APPROVED_CASE`, the exclusive writer fence, all approved package
pins, and a timezone-bearing expiry no more than six hours away. This first
lane supports the existing numbered-address car schema (topic 1145) only.
Other categories refuse; this does not activate the parked general batch writer.

Before execution secrets are used, check the exact repository and account IDs,
manual-dispatch event, first attempt, branch/photo binding, source SHA equality
with live main, and completed green technical checks on that main commit.
The legacy independent-review commit status is not a submission gate. The
same source and expiry checks run again before each municipal write.

The dispatch does not run the Android, display or unit-test matrix again. It
compiles the strict verified production JVM bridge and pinned tooling, brings
up the VPN, validates the exact private input, verifies routes, and makes one
claimed create/attach attempt. It uses the fixed photo contract from #663:
zero CRM plus a usable web ID is accepted and the exact pair is used for the
attachment. The original-hash claim and accepted receipt are durable before
attachment. A refusal or uncertain result stops, with no automatic retry,
resume, fallback, claim reset or second case.

## End of a submission

The assistant that dispatches owns monitoring and branch deletion. Read the
private receipt and reconcile case/photo status with the actual responses.
Report the result promptly. After the run is terminal and its state has been
reconciled, delete only its `submission/<photo-prefix>` branch after verifying
it still points to that run's source SHA. Keep the encrypted evidence, durable
claim and run history. On uncertainty, reconcile first, never rerun; deletion
must not erase the evidence needed to decide what happened. A failed cleanup
is still outstanding work for the submitting assistant, not for the user.

Workflow credentials remain read-only for the public app repository. Branch
creation and deletion use the assistant's existing authorized repository
operation, so this lane does not add a repository-write permission to CI.

## Security boundary and proof limits

Only the verified owner account may dispatch. There are no PR, fork or
pull_request_target triggers in this workflow. Fork authors and accounts
without write access cannot create these branches in the owner's repository
or receive its secrets. The source must equal already-checked main, not a
modified branch with a similar name. The private claim token is confined to
the fixed private repository and is not passed to the municipal child process.

A collaborator who already has repository write access is a trusted GitHub
principal, not an outsider defeated by an in-workflow check. Such a person
can edit workflows; do not claim this design prevents malicious write access.
No collaborators or main protections are changed here. Successful synthetic
boundary tests and settings readback do not prove a live municipal submission.
No real submission is part of this setup PR.
