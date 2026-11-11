# Inactive journal durability protocol (#88)

Depends on restart planner #285 and authenticated envelope #288. This small unit
specifies an abstract exclusive durable-storage port and commit protocol:
authenticate old state, validate forward phase, temp write+sync, rename, directory
sync, exact authenticated read-back. Any exception refuses success. Invalid old
journal and wrong key never permit overwrite. No complete-to-empty reset exists. The store snapshots a required 32-byte ledger
instance ID from independently authenticated custody and binds it into every
journal operation. Stale COMPLETE from another instance fails before any write,
even if the same data key is reused. This unit does not create or authenticate
that custody identity; future fresh-create/reset/restore adapters must prove it.

Seven logical fault-injection tests verify ordering, lock scope, old/new state,
transition refusal and failed read-back. These are NOT actual filesystem crash
proofs: the OS adapter, bounded private reads, process locks, file permissions,
rename semantics and physical sync errors still need separate Android tests.
After an uncertain rename/directory-sync failure restart may see either old or
new state, so later factory must use the existing restart table, not assume one.

No production adapter, caller, migration registration, schema change, custody
creation or recovery. Returned phase is not proof that database cleanup occurred.
The future migration factory must independently establish and test each phase's
postconditions before requesting a commit. No real data touched.

PR targets main for CI and includes parent commits. Merge dependencies first,
rebase, rerun CI and exact-head review. No merges while review is unavailable.
