# Ledger encryption design (#88)

Status: design for review. No implementation in this change.

## Gap and threat model

The Room report ledger (`report-ledger.db`, version 1) stores `draftJson`
(plate, address, exact report text, original and confirmed coordinates/time)
and `reviewedDigest` without application-layer encryption. Reporter settings
are already protected by SecureSettings; the ledger is not. Android sandbox
and device encryption still apply; no public leak is established. This design
adds application-layer encryption at rest so that a database file read outside
the app (rooted device, backup extraction, forensic image) yields no report
content.

Out of scope: network, filing, release distribution, production data access,
and the original photo files under the app sandbox (tracked separately; the
ledger is the metadata store the issue names).

## Existing pieces (reused, not redesigned)

- `LedgerEnvelope` (#90): versioned AES-256-GCM wrap/unwrap of a 32-byte data
  key under a Keystore wrapping key, with fixed AAD.
- `LedgerKeyCustody` (#90): fail-closed custody - durable commit, read-back
  verification, OS-process and cross-instance locking, and no API that can
  create replacement custody for existing data.
- `LedgerKeyStore` (#90): Keystore wrapping-key lifecycle.
- Backup exclusions: `backup_rules.xml` and `data_extraction_rules.xml`
  already exclude every domain (root, file, database, sharedpref, external)
  for cloud backup and device transfer. They must stay exactly this broad;
  the new tests pin them.

## Design

### 1. What is encrypted

Field-level encryption of the sensitive columns, not whole-database
encryption:

- Encrypted: `draftJson`, `reviewedDigest` (both become `ByteArray`
  ciphertext columns).
- Plaintext: `stableId`, `originalSha256`, `originalRelativePath`,
  `revision`. These carry no report content (a content hash, a stable id, a
  path, a counter) and are required for the uniqueness index
  (`originalSha256`), the duplicate guard, and optimistic-concurrency checks.

Whole-database encryption (SQLCipher) was rejected: it adds a native
dependency ahead of the 16 KB page-size Play gate (#84), re-encrypts columns
that must stay queryable, and duplicates what #90 already built.

Record envelope: AES-256-GCM under the ledger data key, random 12-byte nonce
per write, AAD = `stableId || 0x00 || revision`. Binding the AAD to the
record identity and revision prevents ciphertext swap between records and
rollback of an encrypted field to an older revision. Format mirrors
`LedgerEnvelope` (version byte || nonce || ciphertext||tag) with its own AAD
label `parking-reporter/ledger-record/v1`.

### 2. Key lifecycle

- One 32-byte data key encrypts all records. It exists in memory only while
  the ledger is open and is zeroed on close.
- The data key is wrapped by the Keystore wrapping key and persisted through
  `LedgerKeyCustody`, exactly as #90 implements: exclusive store, durable
  commit, read-back verify.
- The Keystore wrapping key follows the SecureSettings pattern: device
  credential authentication required for use. On a locked or
  credential-changed device the key is unusable and the ledger fails closed
  (section 5).
- Key loss, corrupted envelope, or a custody-history/custody mismatch is
  unrecoverable by design. There is no destructive fallback: the app must
  never delete or re-initialize a ledger it cannot decrypt. The UI shows the
  stopped state and keeps the file untouched.

### 3. Migration from v1 (plaintext) to v2 (encrypted)

Crash-safe, resumable, one-way:

1. Migration runs inside `LedgerCustodyStore.exclusive` before the ledger
   opens for normal use. UI is stopped until it completes.
2. A migration marker row (new `ledger_meta` table) records
   `state = migrating`. Absence of the marker with a v1 file means
   not-started; presence means resume.
3. Records are encrypted one transaction per record: read plaintext, write
   ciphertext, advance a `last_migrated_stableId` cursor in the same
   transaction. A crash anywhere leaves a consistent per-record state; the
   resume pass skips already-encrypted rows (detected by format version
   byte, not by a flag that could lie).
4. A record is recognized as ciphertext by the envelope version byte and
   GCM verification; anything else in a v2 column is a hard failure, never a
   silent plaintext passthrough.
5. After the last record: `PRAGMA secure_delete = ON` was set before the
   first overwrite, then the marker advances to `state = complete` and the
   database is checkpointed (WAL truncate). Freelist pages holding old
   plaintext are returned by `VACUUM` in the final transaction.
6. Only after `state = complete` does normal operation open the ledger.
   Per #90, the migration proof (marker + spot verification) is what
   authorizes custody use for a ledger with history.

Room schema version moves 1 to 2; the Room `Migration` is a schema stub
(new columns/meta table), with the data transformation owned by the
migration pass above, not by Room's callback ordering.

### 4. Semantics preserved

- `reserve` (insert-ABORT on duplicate `originalSha256`), `find`,
  `findPhoto`, `all`, `store` (expected-revision conditional update), and
  `approve` keep their exact signatures and conflict behavior; encryption
  and decryption happen at the DAO boundary.
- Concurrent writers still serialize on `exclusive`; revision checks are
  evaluated on plaintext columns before any decryption.
- No behavior change when the ledger is empty (fresh install): custody
  creation follows #90's existing `loadOrCreateEmpty`.

### 5. Stopped states (UI)

The UI blocks on ledger initialization with a dedicated stopped state for:
migration in progress (with progress), locked device / authentication
required (with an unlock action), and unrecoverable key loss (explains the
data is unreadable, offers no destructive action). No state auto-clears
without the underlying condition clearing.

### 6. Backup and device transfer

No change to the existing full exclusions. New instrumentation tests pin
both XML files (legacy and modern, cloud and device-transfer) so a future
edit that narrows the exclusions fails CI.

### 7. Tests

- Encryption at rest: write a draft with known plate/text, read the raw
  database file bytes, assert neither the plate nor text fragments appear;
  assert ciphertext round-trips.
- Tamper: flip one ciphertext byte; read fails closed with a GCM error and
  no partial plaintext.
- Wrong key: unwrap/record decrypt under a different key fails closed.
- Migration: golden v1 database migrates to identical logical content;
  interrupted migration (kill between records) resumes and completes;
  post-migration raw file contains no plaintext fragments.
- Locked device / invalidated key: read refuses, UI stopped state shown,
  file unchanged.
- Backup: both XML rules exclude all domains (parsed and asserted).
- Ledger behavior: revision conflicts, duplicate reserve, ordering, and the
  duplicate guard behave exactly as before across reopen.

### 8. Explicit non-goals

No network, filing, release distribution, or production data access. No
change to what is filed or to the 106 pipeline. No key escrow, no recovery
service, no plaintext debug escape hatch.
