# Inactive encrypted repository stage (#88)

Depends on #280 envelope and #283 codec. Candidate encrypted entity/DAO is not
part of production ReportDatabase. Only the synthetic instrumented database
registers it. Production remains schema1 plaintext until the complete migration,
recovery, lifecycle and activation gates in the rollout plan are satisfied.

Session close serializes with operations and zeroes its owned key. Decryption
failure stops all later operations; no row is silently skipped. Unique-index
ABORT preserves duplicate protection. SQL CAS controls revision, and SQL refuses
tombstone writes even if a higher layer errs. Recovery proof/journal, corruption
callbacks, lifecycle authentication, schema migration and actual evidence sweep
wiring are later units, not implemented claims in this stage.

PR targets main for CI. Review the repository commit separately from parent
commits, merge dependencies first, then rebase and require fresh exact-head
CI/review. No production migration or recovery is authorized here.
