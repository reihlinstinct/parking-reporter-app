# Held creating-intent reconciliation

Refs #615. Operator decision record template, not an acceptance on behalf of any
person. No existing incident, claim or case is changed by this document.

## What creating means

`execute_trial` persists `creating` before calling the municipal create port.
If a request times out, a process dies, or saving the returned receipt fails,
the durable claim can remain creating even though the municipality accepted it.
Treat it as possibly accepted. A missing case number is not proof of refusal.
A valid web ID with a zero canonical CRM ID can still be an accepted receipt;
never substitute or invent either identifier.

## Private reconciliation record

Store this record only with the affected private incident, never in a public PR:

- Original identity and exact approved payload/photo binding.
- Last acknowledged claim revision and state; later local evidence is labelled
  as unacknowledged until its integrity and origin are established.
- Run/attempt, request timing, and exact available Create/Add responses.
- Checked municipal history or counterpart confirmation and its time.
- Classification: accepted, refused, or still uncertain, including missing proof.
- Owner decision and original authenticated approval reference for any proposed
  next effect. A report from an external source does not supply that approval.
- What stays held, the person responsible for follow-up, and the next check.

Default while uncertainty remains: no new CreateCase, no automatic AddFile,
no claim deletion/reset, and no automatic replay through another lane. Preserve
existing encrypted evidence and receipts. A user declining historical updates
ends that affected action; a new workflow does not reopen it implicitly.

## Acceptance still required

Before future activation, the owner needs to understand that uncertain outcomes
can remain held for manual reconciliation and that automation will stop rather
than guarantee completion by retrying. Record the owner's actual decision on
their authenticated channel and recover it at execution time. This template is
not that decision. Existing tests cover save failures and no next write; they
do not prove actual municipal-history visibility or human reconciliation.
