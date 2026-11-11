# Inactive authenticated migration-journal envelope (#88)

Next codec-only unit after restart plan #285. Strict versioned AES256GCM journal
with independent AAD domain and fresh12byte nonce. Fixed two-byte payload permits
only fresh/fresh-creating, migrated/migrating, migrated/cleaning and complete with
either origin. Unknown/contradictory values, lengths, versions, tampering, wrong
key and wrong AAD fail closed. Seven JVM tests cover those cases, including
independently sealed exact-domain positive frames, no/empty/other AAD, validly
authenticated payload lengths 0/1/3/4, and unknown origins paired with COMPLETE.

AAD additionally binds a required 32-byte ledger-instance identity, supplied
from independently authenticated custody, never from the journal itself. A new
ledger must use a fresh identity even if a data key is reused. A stale COMPLETE
sealed for another instance must fail authentication under the same key. This
is an API requirement, not proof that a custody adapter implements it: no such
adapter or activation exists here. Future wiring must preserve/verify that
identity outside this replayable journal, and test fresh-create/reset/restore.

No file store, locks, migrations, custody creation, recovery or production caller.
This is not the durable journal implementation and does not prove schema/source
identity. It needs the unwrapped ledger data key; absent/corrupt custody with a
journal remains a stopped state. Later durable store must authenticate read-back
and test every fsync/rename/directory-sync crash boundary. Existing public custody
API still forbids creation for history; no shortcut or permission added here.

PR targets main for CI and contains parent restart-plan commits. Review separately,
merge #285 first then rebase and get fresh exact-head CI/review. No production
activation or real migration is approved by this code or document.
