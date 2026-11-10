# Ledger encryption design (#88)

Status: design for review, second revision. No implementation in this change.
This revision supersedes `LEDGER_MIGRATION_PHASES.md` (#91); see section 9.

## Gap and threat model

The Room report ledger (`report-ledger.db`, version 1) stores `draftJson`
(plate, address, exact report text, original and confirmed coordinates/time)
and `reviewedDigest` without application-layer encryption. Reporter settings
are already protected by SecureSettings; the ledger is not. Android sandbox
and device encryption still apply; no public leak is established. This design
adds application-layer encryption at rest so that a database file read outside
the app (rooted device, backup extraction, forensic image) yields no report
content.

Limits of that claim:

- Logical deletion, `PRAGMA secure_delete` and `VACUUM` do not guarantee
  physical erasure on flash (wear leveling, copies in free pages already
  written out). The claim is about the logical database image, not the flash.
- Other plaintext stores stay out of scope and keep their current exposure:
  `submissions/<stableId>.json` (municipal case data, FileSubmissionStateStore),
  `addresses.json`, and the original photo files with EXIF. "No report
  content" refers to the ledger database only.
- Residual metadata is accepted explicitly (reversing the earlier position in
  d5365e8 that hashes and digests are sensitive): a holder of a copy of a
  photo can confirm via `originalSha256` that it was reported; row count,
  ciphertext lengths, and NULL versus non-NULL `reviewedDigest` (approval
  state) also leak. These residuals do not reveal report content.

Out of scope: network, filing, release distribution, production data access.

## Existing pieces (reused, not redesigned)

- `LedgerEnvelope` (#90): versioned AES-256-GCM wrap/unwrap of a 32-byte data
  key under a Keystore wrapping key, with fixed AAD.
- `LedgerKeyCustody` (#90): fail-closed custody - durable commit, read-back
  verification, OS-process and cross-instance locking, and no API that can
  create replacement custody for existing data. Merged but not yet wired into
  the app (`LedgerKeyStore` is unused at this head).
- `LedgerKeyStore` (#90): Keystore wrapping-key lifecycle; `hasHistory()`
  reports history when `databaseList()` contains `report-ledger*` or
  `noBackupFilesDir` holds `report-ledger-migration*` - a file-based marker
  convention this design keeps.
- Backup exclusions: `backup_rules.xml` and `data_extraction_rules.xml`
  exclude the five domains the app uses (root, file, database, sharedpref,
  external) for cloud backup and device transfer, and the manifest sets
  `allowBackup="false"`. The device-protected-storage (`device_*`) domains
  are not listed; the app does not use that storage. New tests pin both XML
  files and the manifest flag (section 8); no such test exists today, so this
  is new work, not an existing receipt.

## Design

### 1. What is encrypted

Field-level encryption of the sensitive columns, not whole-database
encryption:

- Encrypted: `draftJson`, `reviewedDigest` (both become `ByteArray`
  ciphertext columns).
- Plaintext: `stableId`, `originalSha256`, `originalRelativePath`,
  `revision`. These carry no report content (a content hash, a stable id, a
  random UUID path, a counter) and are required for the uniqueness index
  (`originalSha256`), the duplicate guard, and optimistic-concurrency checks.
  Duplicate decisions never depend on decryption (section 5).

Whole-database encryption (SQLCipher) was rejected on two grounds only: the
native dependency ahead of the 16 KB page-size Play gate (#84), and the added
artifact surface for a threat the field-level envelope already covers. (It
would still have needed #90's custody to hold its passphrase, and page-level
encryption is transparent to queries, so neither "duplicates #90" nor
"re-encrypts queryable columns" is a deciding argument. No SQLCipher version
is pinned because none is adopted.)

Record envelope: AES-256-GCM under the ledger data key, random 12-byte nonce
per write. AAD =
`"parking-reporter/ledger-record/v1" || 0x00 || columnName || 0x00 || stableId || 0x00 || revision`,
where `revision` is an 8-byte big-endian encoding. The column name is part of
the AAD because `draftJson` and `reviewedDigest` would otherwise share
identical AAD and could be swapped. `store` increments `revision` in SQL, so
its ciphertext is bound to `expectedRevision + 1`; `approve` does not bump
`revision`, so its ciphertext binds the current value. The AAD prevents
ciphertext swap between records, between columns, and rollback of one
encrypted field to its own older revision. Rollback of a field together with
the plaintext `revision` column is not detected; that writer already has
write access to the file and is outside the read-only threat model. Format
mirrors `LedgerEnvelope` (version byte || nonce || ciphertext||tag).

### 2. Key lifecycle

- One 32-byte data key encrypts all records. It exists in memory only while
  the ledger is open and is zeroed on close.
- The data key is wrapped by the Keystore wrapping key and persisted through
  `LedgerKeyCustody`, exactly as #90 implements: exclusive store, durable
  commit, read-back verify.
- The Keystore wrapping key follows the SecureSettings pattern: device
  credential authentication required for use (30-second window).
- Lock behavior: the ledger is closed and the in-memory data key zeroed on
  `ON_STOP` or screen-off; reopening re-authenticates. An open ledger must
  not keep decrypting on a locked device. This restores the acceptance item
  the previous design on main carried.
- No rotation in v2; the record version byte leaves room for a future
  re-keying design.
- Key loss, corrupted envelope, or a custody-history/custody mismatch is
  unrecoverable by design, subject to the owner decision in section 7. There
  is no silent destructive fallback: the app must never delete or
  re-initialize a ledger it cannot decrypt. The UI shows the stopped state
  and keeps the file untouched.

Exact invalidation triggers (Keystore-documented, replacing the vague
"credential-changed"):

- Secure lock screen disabled or force-reset: permanently invalidates the
  auth-bound wrapping key. See section 7.
- PIN/pattern/password changed to another PIN/pattern/password: does NOT
  invalidate the key; the ledger keeps working after re-authentication.
- Biometric enrollment or removal: does NOT invalidate this
  device-credential (not biometric-bound) key.
- Factory reset, uninstall, or Clear storage: deletes the key and the
  database together. This already destroys the duplicate guard today,
  without encryption; encryption does not change it.

### 3. Custody for a ledger with history

`LedgerKeyCustody` refuses to create a key when `hasHistory()` is true, and
`AppShell` opens `report-ledger.db` with Room on every launch, so every
installed device already has history - including devices whose ledger is
empty. An existing but empty v1 file is therefore a migration, not
`loadOrCreateEmpty`. On a true fresh install (no `report-ledger*` file and
no `report-ledger-migration*` journal), custody must be created before the
first Room open. On this fresh-install path, after the custody commit and
read-back and before the first Room open, the journal is written durably as
`fresh-creating`: a fresh install has nothing to migrate, but the database
does not exist yet, and "database never created" must be a durable fact of
its own, not something inferred from how the ledger was created. The
journal advances to `complete` only after Room's create has committed and
the database has opened once. A fresh install that skipped this write would
be indistinguishable from the stopped "v2 schema, no journal" state, so the
journal write is part of custody creation, not an optional step.

The internal proof that authorizes key creation for a ledger with history:

- Issued only under the exclusive migration lock (per-process plus OS file
  lock, reusing #90's locking), after validating: the Room v1 schema identity
  (identity hash), `user_version == 1`, the canonical source path/stat
  identity, and a complete row inventory (every row readable with the v1
  columns).
- Binds the source path/stat identity and the verified schema/version. It is
  valid only under the same process/OS lock and invalid after any source
  change. No public boolean, exception fallback, or failed encrypted open is
  such a proof. Authentication is required independently; proof of schema
  never substitutes for authentication.

Ordering (strict): proof -> create custody (data key + wrapping) -> durable
commit with read-back verification -> only then write the migration journal
(section 4). Crash points:

- Key committed, no journal: a later start revalidates the proof (source
  must be unchanged) and starts the migration with the committed key.
- Journal present, no committed key: contradiction - fixed stopped state,
  retain all data, never infer or recover by mtime.

A database file can also arrive without its key (for example a vendor
transfer tool that ignores the backup rules). A v1 file in that situation is
an ordinary migration source: `hasHistory()` is true, no envelope is
committed, and the proof path above applies. Only a v2 file stops: it fails
the `user_version == 1` proof, and there is no committed envelope to
authenticate against.

### 4. Migration from v1 (plaintext) to v2 (encrypted)

Marker convention: a strict versioned journal file under `noBackupFilesDir`
(`report-ledger-migration-journal`), matching how `hasHistory()` already
detects `report-ledger-migration*`. Not an in-database table: the marker must
be visible to custody history checks without opening the database. The
journal records whether the ledger was created fresh at v2 or migrated from
v1. That mark never authorizes creating a database: once the journal says
`complete`, a missing database is a fixed stopped state for both kinds
(section 4 restart table); only `fresh-creating` may create one. Journal
writes use explicit sync/rename/directory-sync plus authenticated read-back
(the same durability discipline as key custody, not AtomicFile's
logged-error success).

Schema plan: Room version 1 -> 2. The Room `Migration` is not a stub and
does the whole data pass itself. Room runs a `Migration` inside the upgrade
transaction and, before committing, validates the resulting schema against
the version-2 entities and writes its identity hash - so no per-record work
outside that transaction is possible, and a partial result can never be
committed. The `Migration` creates a new `reports_v2` table with BLOB
`draftJson`/`reviewedDigest` columns, a plaintext
`tombstoned INTEGER NOT NULL DEFAULT 0` column (0 for every migrated row;
the v2 entity field carries `@ColumnInfo(defaultValue = "0")` so Room's
TableInfo default-value validation agrees with the migrated table),
the same unique index on `originalSha256`, and the other columns unchanged.
The fresh-install v2 schema Room creates from the entity is the same table,
so the `tombstoned` column exists from the start on every v2 database and
recovery (section 7) is an UPDATE of existing rows, never a schema change.
SQLite index names are database-wide, so the `reports_v2` unique index
cannot reuse `index_reports_originalSha256` while the old table exists: it
is created after the rename, or under a fresh `index_`-prefixed name. SQLite cannot ALTER a
column's type and the SQLite shipped with minSdk 26 has no DROP COLUMN, so
the data pass rebuilds rather than alters: copy-encrypt every row into
`reports_v2`, verify, drop the old table, rename - all inside the one
upgrade transaction.
The Kotlin-facing ledger API keeps today's String-in/String-out shape for
ordinary rows in a repository layer above the `ByteArray` Room entity, and
the repository record type additionally carries an explicit tombstoned
state with no draft and no digest (never a fake empty string). `all`,
`find` and `findPhoto` return a tombstoned row marked as such - never null
and never "not found", so no caller can mistake it for absence; `reserve`,
`store`, `approve` are unchanged at the repository boundary.

Who opens the database during migration: the migration factory is the sole
opener. It writes journal `state = migrating` durably first, then opens
through Room's `openHelper` (a raw `SupportSQLiteDatabase`) in migration
mode so the `Migration` runs; the normal Room DAO path never opens a v1 or
partially migrated file. Existing Room handles must close before migration;
UI is stopped until it completes. The factory opens on a background
dispatcher (today the first database access is already on Dispatchers.IO)
while the UI shows the stopped or migrating state; the whole
encrypt-everything `Migration` must never run on the main thread. The
ON_STOP/screen-off close rule waits for an open upgrade transaction to
finish or roll back rather than closing under it. The 30-second
authentication window applies only to unwrapping the data key, before the
pass; the data pass itself does not re-authenticate per row.

Data pass, under the exclusive lock:

1. `PRAGMA secure_delete = ON` as the first statement of the Migration's
   `migrate()` (a step, not an assumption; SQLite accepts this pragma
   inside the upgrade transaction, so no custom open-helper factory is
   needed).
2. Inside the one upgrade transaction, in `stableId` order: encrypt each v1
   row's `draftJson`/`reviewedDigest` into `reports_v2` and immediately
   decrypt-verify the written envelope against the source values, privately
   (no values in logs). Any failure rolls the whole transaction back: the
   v1 file is unchanged, and a v2 row is either a fully verified envelope
   or absent. There is no resume cursor and no mid-copy plaintext
   passthrough - a crash during the data pass re-runs the whole pass from
   the intact v1 source.
3. Drop the v1 table, rename `reports_v2`, still inside the same
   transaction. Room then validates the schema against the v2 entities and
   commits.
4. After the open returns, journal `state = cleaning` durably.
5. `VACUUM` (outside any transaction - VACUUM cannot run inside one), then
   `wal_checkpoint(TRUNCATE)` (it must follow VACUUM: in WAL mode VACUUM's
   output goes through the WAL).
6. Raw byte scan of the database, WAL and SHM for distinctive synthetic
   plaintext values; any hit is a hard failure.
7. Only then journal `state = complete` durably. Normal operation opens the
   ledger only in state `complete`, so a crash before this point can never
   yield a "complete" ledger still carrying old pages.

Restart table:

| Last durable state | Required safe action | Never do |
|---|---|---|
| No journal, v1 file, no committed key | validate proof under lock, create/commit custody, write journal `migrating`, start | treat any database as a known v1 source |
| No journal, v1 file, committed key | re-issue the proof against the current source file (its bound identity was never persisted, so it is simply re-validated against the file now present), write journal `migrating`, start | create custody again (`commitDurably` refuses when a committed file exists) or reopen v1 for writes |
| Journal `migrating`, v1 file | crash before or during the upgrade transaction: nothing was committed; revalidate the proof under lock, re-run the whole data pass | replace the key, reopen v1 for writes, or resume mid-copy (there is no resume cursor) |
| Journal `migrating`, v2 schema | the upgrade committed before the journal write: skip the data pass, journal `cleaning`, resume at the VACUUM step | re-run the data pass or skip the byte scan |
| Journal `cleaning` | decrypt-verify every v2 row (GCM authenticates each one; the v1 table no longer exists); resume at VACUUM/checkpoint step | re-run the data pass or skip the byte scan |
| Journal `fresh-creating`, no database | fresh-install crash before Room's create committed: open Room, creating the v2 database, then journal `complete` | treat it as a migration |
| Journal `complete` (fresh-created or migrated), no database | fixed stopped state, retain data | recreate an empty ledger (that would silently lose the duplicate guard) |
| Journal `complete`, database present | authenticated encrypted open only | reclassify wrong key/corruption as plaintext |
| Committed key, no journal, no database | fresh-install crash point: write journal `fresh-creating` durably, open Room creating the v2 database, then journal `complete` | treat it as a migration (there is no source) |
| v2 schema, no journal (not the fresh-install row above) | fixed stopped state, retain data | infer state by mtime or delete anything |
| Missing/corrupt/contradictory journal or files | fixed stopped state, retain data | reset, recover by guessing, destructive migration |

User-approval gate (kept from #91): no real user data migration is
authorized by this document. Development tests use synthetic databases only;
migrating a real installation requires a separately relayed user approval.

### 5. Fail-closed scope: every consumer, every stopped state

Any single record's GCM failure stops the whole ledger: `all`, `find` and
`findPhoto` throw; nothing is filtered and no row is skipped. This is what
protects the startup sweep: the sweep (`GalleryImport.sweepUnreferenced`)
runs only in the ready state, over the plaintext `originalRelativePath` of
every row; if the ledger is not ready, the sweep does not run at all, so an
undecryptable row can never cause its evidence photo to be deleted as
"unreferenced". The one exception is a tombstoned row (section 7): it is
never decrypted, so it is not a GCM failure and does not stop the ledger.
The rule above applies to every non-tombstoned row.

| Consumer | Ready | Migrating / locked / key-loss stopped |
|---|---|---|
| reload (`all`) | decrypt all rows, any failure stops the ledger | blocked; UI stopped state |
| startup sweep | runs over plaintext paths of every row | never runs |
| `reserve` (duplicate guard) | plaintext unique index only; no decryption | blocked (no new reports) |
| `store` / `approve` | encrypt at the repository boundary | blocked |
| `openRecord` (decode `draftJson`) | decrypt that row; failure stops the ledger | blocked |
| submit path | reads via repository after open | blocked |

Sweep exceptions keep their current swallow-at-callsite behavior, but the
swallow can now only affect filesystem errors, never decrypt-filtered input.

### 6. Downgrade and destructive fallbacks

Downgrade is unsupported and fails closed: a v2 file opened by a v1 build
throws in Room. Every `fallbackToDestructiveMigration*` variant is forbidden
in any build - a destructive fallback would delete the duplicate guard.
Pinned by a source test that scans the Room database builder configuration.

Corruption handling is overridden for the same reason. The default
`SupportSQLiteOpenHelper.Callback.onCorruption`, called on SQLITE_CORRUPT or
NOTADB during open or any later statement, deletes the database file; the
next open would then create an empty v2 ledger and every photo already filed
could be filed again. The ledger's builder therefore installs a custom
`SupportSQLiteOpenHelper.Factory`/`Callback` (or the Room driver API
equivalent) that never deletes or recreates the file and moves the ledger to
the fixed stopped state instead. Pinned by the same source test, plus a test
that corrupts the SQLite header of a v2 file with rows, opens it, and
expects the stopped state with the file's bytes unchanged.

### 7. Key invalidation versus the duplicate guard (DECIDED: option (a))

Removing or force-resetting the lock screen permanently invalidates the
auth-bound wrapping key (section 2). The one-report-per-photo guard lives
only in the ledger (unique index on `originalSha256`, enforced through
`reserve`), and the app accepts any gallery image via `ACTION_SEND`. Without
a recovery path, the user's only way back to a working app would be Clear
storage or reinstall, which would delete the ledger and every
`originalSha256` row -
after which every previously filed photo can be imported and filed again.
Encryption would thereby tie duplicate-guard loss to an ordinary action
(removing the lock screen) that today is harmless.

Owner decision (user-approved 2026-11-10 15:35 IST, relayed via Instinct):
option (a), tombstone-preserving recovery. A user-confirmed key-loss
recovery keeps every row's plaintext columns (`stableId`, `originalSha256`,
`originalRelativePath`, `revision`) and its ciphertext bytes untouched, and
sets the row's existing plaintext `tombstoned` flag column (the column is
part of the v2 schema from the start, section 4) to 1 in one database
transaction. The custody envelope is a file, not part of SQLite, so no
single transaction can cover both the flag update and the new custody
commit: their ordering, and the crash states between them (flag set but
custody not committed, and the reverse), belong to the separately reviewed
recovery proof; until both are durable the ledger stays in its stopped
state. The tombstoned flag means:

- The row is never decrypted, and its undecryptable ciphertext is not a
  GCM failure: the section-5 rule stops the ledger only for a
  non-tombstoned row's failure.
- `all()` returns tombstoned rows marked as such; the Reports list shows
  them as unreadable; the startup sweep's referenced set includes their
  `originalRelativePath`, so their evidence photos are kept, not deleted
  as "unreferenced".
- Open, approve and send are permanently refused for tombstoned rows; as
  defense in depth, the `store` and `approve` UPDATE statements carry
  `AND tombstoned = 0`, so even a repository bug cannot overwrite a
  tombstoned row's ciphertext with new-key data while the flag is set
  (fail closed). If a tombstoned row's `submissions/<stableId>.json`
  records an in-flight or uncertain municipal submission, send stays
  refused, but the case data from that plaintext file is still shown so
  the user can track or verify the case with the municipality.
- Recovery deletes, compacts or rewrites no row and no hash: the only
  write is the tombstone flag in the recovery transaction. There is no
  in-app reset or clear-ledger flow; Clear storage and uninstall remain
  the OS-level residual risk accepted in section 2.

Their `submissions/<stableId>.json` files are kept. New custody is created
only through a separately reviewed recovery proof bound to that exact
tombstone set, and recovery is offered only after the user has set a secure
lock screen again: a new wrapping key cannot be created or used without one
(section 2). Until recovery is confirmed, the stopped screen shows the
ledger as locked by the removed credential and offers the recovery action
- nothing destructive. Tests must show `reserve` of a tombstoned hash
still aborts after recovery and after reopen, and that the startup sweep
keeps tombstoned rows' evidence photos. This keeps the guard - the
property the whole live-run machinery exists to protect - with no
plaintext content exposure.

### 8. Tests

- Encryption at rest: write a draft with known plate/text, read the raw
  database file bytes, assert neither the plate nor text fragments appear;
  assert ciphertext round-trips.
- Tamper: flip one ciphertext byte; read fails closed with a GCM error and
  no partial plaintext.
- Wrong key: unwrap/record decrypt under a different key fails closed.
- AAD binding: a `draftJson` ciphertext copied into `reviewedDigest` (same
  row) fails verification; a ciphertext copied to another row fails.
- Schema: the v2 entity, the Migration's `reports_v2` and the fresh-install
  schema all include `tombstoned INTEGER NOT NULL DEFAULT 0`; the exported
  schema 2.json records it; a Room migration test upgrades a golden v1
  database to v2 against that exported schema and asserts every migrated
  row has tombstoned = 0.
- Migration: golden v1 database migrates to identical logical content;
  interrupted migration (kill mid-data-pass, between the upgrade commit
  and the `cleaning` journal write, between VACUUM and checkpoint, before
  `complete`) resumes per the restart table; post-migration raw file, WAL and SHM contain no plaintext
  fragments; "v2 schema, no journal" stops and retains data. Restart rows:
  `fresh-creating` with no database creates the v2 database once and
  journals `complete`; `complete` (fresh-created or migrated) with no
  database stops and retains data; a header-corrupted v2 file opens to the
  stopped state with its bytes unchanged.
- Fail-closed consumers: corrupt one row; `all()` throws, the sweep deletes
  nothing, and the photo files are untouched. `reserve` of an existing hash
  still aborts without any decryption.
- Lock behavior: ledger open, `ON_STOP`/screen-off closes it and zeroes the
  key; reopen requires re-authentication; a locked device with a closed
  ledger shows the stopped state, file unchanged.
- Key invalidation: lock-screen-removal invalidation path reaches the
  section-7 stopped/recovery state, never a destructive fallback.
- Backup: both XML rules exclude all five domains (parsed and asserted) and
  the manifest keeps `allowBackup="false"`; a future narrowing fails CI.
- Downgrade: source test forbids `fallbackToDestructiveMigration*` on the
  ledger builder.
- Tombstone recovery: recovery preserves tombstones, tombstoned rows
  refuse open/approve/send, `reserve` of a tombstoned hash aborts after
  recovery and after reopen, and after recovery the startup sweep's
  referenced set includes tombstoned rows' `originalRelativePath` so their
  evidence photos are not deleted.
- Ledger behavior: revision conflicts, duplicate reserve, ordering, and the
  duplicate guard behave exactly as before across reopen.
- androidTest boundary move: the instrumented helpers that open Room
  directly and read `draftJson` as text - FullFlowE2eTest.kt (:60, :73),
  LiveEmulatorSubmissionTest.kt (:134, :289, :302, :316), EntryPointsTest.kt
  (:35), M1ShellTest.kt (:101-113) - move to the new repository boundary;
  their duplicate-photo checks (FullFlowE2eTest.kt:62,
  LiveEmulatorSubmissionTest.kt:136) are named here so they are ported, not
  dropped.

### 9. Relationship to #91 (`LEDGER_MIGRATION_PHASES.md`)

This document supersedes #91. Replaced decisions and why:

- Whole-database SQLCipher export to a separate canonical
  `report-ledger-encrypted.db` -> in-place field-level encryption with a
  table rebuild in the same file. SQLCipher is rejected for v2 (section 1),
  which removes the export/copy mechanism and the second canonical file.
- In-SQL migration journal -> journal file under `noBackupFilesDir`, to
  match the existing `hasHistory()` file convention.

Surviving decisions (unchanged, restated where relevant):

- No custody creation for a ledger with history without an internal proof;
  no public boolean or failed-open fallback is a proof (section 3).
- Durability receipts: explicit sync/rename/directory-sync plus
  authenticated read-back; every phase boundary is fault-injected, not an
  in-memory flag (section 4).
- A restart action for every durable state; contradictions stop and retain
  data; nothing is inferred from mtime (section 4).
- Comparative verification is row-by-row and private; distinctive synthetic
  values must not survive in db/WAL/SHM (section 4).
- Flash deletion is not forensic erase (threat model).
- No real user data migration without a separately relayed user approval
  (section 4).

### 10. Explicit non-goals

No network, filing, release distribution, or production data access. No
change to what is filed or to the 106 pipeline. No key escrow, no recovery
service, no plaintext debug escape hatch. No key or wrapping-key rotation in
v2.
