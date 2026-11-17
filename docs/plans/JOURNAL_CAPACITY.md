# Bounded journal capacity, inactive format slice

No remote file, credential, journal seed, writer or municipal action is created.

## Why change the format

The old envelope capped plaintext SQLite at768KiB. A synthetic640-source history
with3200 held aliases already exceeds that cap. Dropping aliases to fit would
lose duplicate protection. Simply raising the stored-file cap would also break
the current GitHub Contents base64-content reader's small-file assumptions.

V2 compresses the complete SQLite snapshot before authenticated encryption. It
keeps a768KiB compressed-payload limit and adds a separate8MiB decoded-database
limit. Eight MiB is a finite memory budget, not unlimited growth or a promise
about real history. The stored file is at most786515 bytes including the header,
salt, nonce and authentication tag. GitHub documents full Contents API features
for files at most1MB: https://docs.github.com/en/rest/repos/contents . The existing
fixed-path Contents CAS remains unchanged. Response reads and saves are bounded
against the envelope limit, not the larger decoded limit.

## Format and safety

V2 is a new magic/version, followed by an eight-byte big-endian decoded size,
16-byte random salt,12-byte random nonce and AES-GCM ciphertext/tag. The existing
PBKDF2 SHA256/200000 key derivation is retained. The compressed payload and size
header are authenticated, with version/path-specific associated data. Encryption
happens after compression; no remote plaintext or separate unauthenticated size
metadata is stored. Stored length can reveal approximate compressed size, so the
file remains private and hashes are not treated as anonymous data.

Reject oversized plaintext and compressed payloads before crypto imports or key
derivation. On read, authenticate first, then decode at most the authenticated
size plus one byte, never more than8MiBplus one. Require exact decoded size, end of
the one zlib stream, no trailing bytes/second stream, and SQLite signature.
Truncated streams, false sizes, compression bombs and malformed envelopes fail
closed. A snapshot read in the persistence path is capped at8MiBplus one too.
Local SQLite and snapshots remain plaintext with private permissions; encryption
starts at `seal`, not when provenance is inserted.

V1 remains readable under its original768KiB plaintext limit and original AAD.
Only V2 is emitted. Opening old data alone does not remotely rewrite it; the next
successful runtime transaction produces V2 under normal CAS. New format headers
cannot be downgraded without authentication failure. Unknown versions fail.
CAS is not independent rollback protection: restoring an old valid encrypted
snapshot is still a separate activation blocker.

## Proof and limits

Synthetic tests preserve every source/provenance/alias through seal, unseal and
SQLite reopen. They test the exact decoded limit, refusal of both capacity
limits before crypto imports, valid V1 read/V2 write, header tampering, wrong key,
authenticated size lies, truncated/concatenated streams and no-network refusal
of oversized saves. Existing save-failure, two-writer CAS and process-death tests
continue to run. Crypto tests run in the pinned dependency environment, not the
dependency-free scaffolding job.

Still required: measure the real normalized full archive and old ledgers without
dropping candidates, verify completeness and all writers, and prove bootstrap,
key authority, concurrency, rollback and leaks before activation. Incompressible
or growing data may still exceed the stored limit. Refuse and plan another
storage migration; do not truncate or silently increase a limit. No real archive
has been imported or encrypted by this slice.
