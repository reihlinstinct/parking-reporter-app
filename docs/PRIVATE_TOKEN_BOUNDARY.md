# Fixed private input and claim credential boundary

Refs #607. Assessment only. No credential is inspected, created, copied, revoked
or renamed, and no private repository request is made by this assessment.

## Public source facts

The supervised workflow supplies one repository secret to input fetching and
claim persistence. `fetch_live_batch.stage` reads the alias
`PRIVATE_LOOKUP_READ_TOKEN`; `PrivateTrialClaim` uses
`BATCH_PRIVATE_CONTENTS_TOKEN` for Contents PUT. The fetch alias does not make a
write-capable credential read-only. Source names do not prove actual token
permissions, repository selection, expiry or account ownership. Those remain
unverified without an authorized credential-permission check.

## Least-privilege options

- Separate fine-grained token for Contents read on the fixed input repository.
  Repository-wide read may still cover more private files than the one package.
- Separate fixed-repository Contents write credential for claim CAS, injected
  only after input validation. Repository Contents write is broader than one
  claim path; merely splitting two tokens does not create path-level permission.
- A future narrowly scoped service can enforce exact fixed paths and create/CAS
  semantics, but it introduces a new service, deployment, recovery and trust
  boundary. It is not authorized or implemented here.

Keep explicit one-way injection: the compiled municipal bridge receives only
municipal identity, not either repository credential or data key. Both private
HTTP clients must reject redirects and retain fixed host/repository/path rules.
No endpoint or credential selection may come from the package body.

## Verification before a credential transition

Record repository selection, granted permissions, expiry and revocation route
without token values. Test that the input credential cannot PUT claims and that
the writer cannot change another repository, with synthetic disposable resources
only and separate permission for those effects. Check fail-closed absence,
wrong-token, redirected endpoint and stale-CAS cases. Remove old injection only
after recovery and rollback are arranged. A failed negative test must not become
a municipal dispatch or a claim reset. Actual token scope is still an open
operational check, not a completed acceptance item.
