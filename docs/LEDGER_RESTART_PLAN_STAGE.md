# Inactive restart-state plan (#88)

Pure implementation of the restart table, independent of the encrypted-row
stack. Its input is a set of facts, not an internal proof. Its return value is a
plan requiring validation, not permission or execution. There are no keys, file
reads/writes, Room opens, migration registrations, journals or production callers.

The test enumerates all270 combinations of schema, journal, origin and custody.
Only12 documented combinations return a non-stopped plan. Missing complete DB,
uninitialized complete DB, v2 without journal, invalid custody/journal/schema,
and all contradictory origins stop and retain every file. No mtime field exists.

Next separate units must supply locked schema/source proofs and authenticated
durable journal receipts. Fresh-create continuation still must verify schema2
identity AND zero rows before marking complete. Cleaning still must authenticate
all rows, VACUUM, checkpoint and verify logical-file cleanup. This reducer proves
none of those effects by itself, and is deliberately not wired to storage.
