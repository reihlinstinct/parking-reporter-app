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
first Room open.

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

A database arriving without its key (for example a vendor transfer tool that
ignores the backup rules) stops the same way: `hasHistory()` is true and no
envelope is committed.

### 4. Migration from v1 (plaintext) to v2 (encrypted)

Marker convention: a strict versioned journal file under `noBackupFilesDir`
(`report-ledger-migration-journal`), matching how `hasHistory()` already
detects `report-ledger-migration*`. Not an in-database table: the marker must
be visible to custody history checks without opening the database. Journal
writes use explicit sync/rename/directory-sync plus authenticated read-back
(the same durability discipline as key custody, not AtomicFile's
logged-error success).

Schema plan: Room version 1 -> 2. The Room `Migration` is a schema stub that
creates a new `reports_v2` table with BLOB `draftJson`/`reviewedDigest`
columns, the same unique index on `originalSha256`, and the other columns
unchanged. SQLite cannot ALTER a column's type and the SQLite shipped with
minSdk 26 has no DROP COLUMN, so the data pass rebuilds rather than alters:
copy-encrypt every row into `reports_v2`, verify, drop the old table, rename.
The Kotlin-facing ledger API keeps today's String-in/String-out shape in a
repository layer above the `ByteArray` Room entity; DAO signatures for
`reserve`, `find`, `findPhoto`, `all`, `store`, `approve` are unchanged at
the repository boundary.

Who opens the database during migration: the migration factory is the sole
opener. It opens through Room's `openHelper` (a raw `SupportSQLiteDatabase`)
in migration mode so the schema-stub `Migration` runs; the normal Room DAO
path never opens a v1 or partially migrated file. Existing Room handles must
close before migration; UI is stopped until it completes.

Data pass, under the exclusive lock:

1. `PRAGMA secure_delete = ON` before the first write (a step, not an
   assumption).
2. Encrypt rows one transaction per record into `reports_v2`, in `stableId`
   order. The v2 table contents are the resume cursor: on restart, rows
   already present (and verified) in `reports_v2` are skipped. A record is
   recognized as ciphertext by the envelope version byte and GCM
   verification; while the journal state is `migrating`, plaintext in a v2
   column mid-copy is expected and handled by re-encryption of that row; in
   state `complete` it is a hard failure, never a silent passthrough.
3. After the last row: decrypt and compare every v2 row against the v1
   source, privately (no values in logs).
4. Drop the v1 table, rename `reports_v2`, journal `state = cleaning`.
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
| No journal, v1 file | validate proof under lock, create/commit custody, start | treat any database as a known v1 source |
| Journal `migrating`, v2 partial | reverify proof identity; resume copy from v2 contents | replace the key or reopen v1 for writes |
| Journal `cleaning` | reverify v2 against v1; resume at VACUUM/checkpoint step | re-copy from a stale v1 or skip the byte scan |
| Journal `complete` | authenticated encrypted open only | reclassify wrong key/corruption as plaintext |
| v2 schema, no journal | fixed stopped state, retain data | infer state by mtime or delete anything |
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
"unreferenced".

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

### 7. Key invalidation versus the duplicate guard (OWNER DECISION PENDING)

Removing or force-resetting the lock screen permanently invalidates the
auth-bound wrapping key (section 2). The one-report-per-photo guard lives
only in the ledger (unique index on `originalSha256`, enforced through
`reserve`), and the app accepts any gallery image via `ACTION_SEND`. Without
a recovery path, the user's only way back to a working app is Clear storage
or reinstall, which deletes the ledger and every `originalSha256` row -
after which every previously filed photo can be imported and filed again.
Encryption would thereby tie duplicate-guard loss to an ordinary action
(removing the lock screen) that today is harmless.

Two documented options; the owner picks one before implementation:

- **(a) Tombstone-preserving recovery (RECOMMENDED).** A user-confirmed
  key-loss recovery keeps every row's plaintext columns (`stableId`,
  `originalSha256`, `originalRelativePath`, `revision`) as permanent
  tombstones, marks the encrypted fields unreadable, and permanently refuses
  open, approve and send for tombstoned rows. Their
  `submissions/<stableId>.json` files are kept. New custody is created only
  through a separately reviewed recovery proof bound to that exact tombstone
  set. Tests must show `reserve` of a tombstoned hash still aborts after
  recovery and after reopen. This keeps the guard - the property the whole
  live-run machinery exists to protect - with no plaintext content exposure.
- **(b) Accepted limitation.** An explicit owner ruling that guard loss
  after lock-screen removal plus Clear storage is accepted, stated in the
  threat model. This is weaker: duplicate filings are exactly what the guard
  and the cross-run duplicate detection exist to prevent, and the trigger
  (removing a lock screen) is a common user action.

Recommendation: (a).

### 8. Tests

- Encryption at rest: write a draft with known plate/text, read the raw
  database file bytes, assert neither the plate nor text fragments appear;
  assert ciphertext round-trips.
- Tamper: flip one ciphertext byte; read fails closed with a GCM error and
  no partial plaintext.
- Wrong key: unwrap/record decrypt under a different key fails closed.
- AAD binding: a `draftJson` ciphertext copied into `reviewedDigest` (same
  row) fails verification; a ciphertext copied to another row fails.
- Migration: golden v1 database migrates to identical logical content;
  interrupted migration (kill between records, between rename and VACUUM,
  between VACUUM and checkpoint, before `complete`) resumes per the restart
  table; post-migration raw file, WAL and SHM contain no plaintext
  fragments; "v2 schema, no journal" stops and retains data.
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
- Tombstone recovery (if option (a) is chosen): recovery preserves
  tombstones, tombstoned rows refuse open/approve/send, `reserve` of a
  tombstoned hash aborts after recovery and after reopen.
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
