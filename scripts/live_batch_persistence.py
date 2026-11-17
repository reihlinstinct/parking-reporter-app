"""Inactive encrypted CAS journal storage for the fixed private reports repo.

No credential creation or automatic bootstrap. An absent remote journal is a
blocker, never an empty replacement. Any failed/ambiguous save poisons this
instance before another external write can be attempted.
"""
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import urllib.request
import zlib

from live_batch_input import InvalidBatch, need
from live_batch_journal import BatchJournal

# V1 remains readable; only V2 compressed envelopes are emitted.
LEGACY_MAGIC = b'parking-journal-aesgcm-v1\x00'
LEGACY_AAD = b'reihlinstinct/parking-reports:journal/live-batch.sqlite.aesgcm:v1'
MAGIC = b'parking-journal-aesgcm-zlib-v2\x00'
AAD = b'reihlinstinct/parking-reports:journal/live-batch.sqlite.aesgcm:v2'
URL = 'https://api.github.com/repos/reihlinstinct/parking-reports/contents/journal/live-batch.sqlite.aesgcm'
LEGACY_MAX_PLAIN = 768 * 1024
MAX_PLAIN = 8 * 1024 * 1024
# Keep encrypted files below the Contents API's full base64-content threshold.
MAX_COMPRESSED = 768 * 1024
MAX_ENVELOPE = len(MAGIC) + 8 + 44 + MAX_COMPRESSED
MAX_RESPONSE = 2 * MAX_ENVELOPE + 4096


def seal(data, password):
    # Capacity refusal does not depend on importing crypto or deriving a key.
    need(isinstance(password, bytes) and len(password) >= 16, 'journal key unavailable')
    need(isinstance(data, bytes) and 0 < len(data) <= MAX_PLAIN, 'journal capacity exceeded')
    need(data.startswith(b'SQLite format 3\x00'), 'invalid journal database')
    packed = zlib.compress(data, level=6)
    need(len(packed) <= MAX_COMPRESSED, 'compressed journal capacity exceeded')
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    salt, nonce = os.urandom(16), os.urandom(12)
    header = MAGIC + len(data).to_bytes(8, 'big')
    key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=200000).derive(password)
    return header + salt + nonce + AESGCM(key).encrypt(nonce, packed, AAD + header)


def unseal(blob, password):
    need(isinstance(password, bytes) and len(password) >= 16, 'journal key unavailable')
    need(isinstance(blob, bytes) and len(blob) <= MAX_ENVELOPE, 'invalid journal envelope')
    legacy = blob.startswith(LEGACY_MAGIC)
    if legacy:
        need(len(LEGACY_MAGIC) + 44 < len(blob) <= len(LEGACY_MAGIC) + 44 + LEGACY_MAX_PLAIN,
             'invalid journal envelope')
        at, associated, expected = len(LEGACY_MAGIC), LEGACY_AAD, None
    else:
        need(blob.startswith(MAGIC) and len(blob) > len(MAGIC) + 8 + 44, 'invalid journal envelope')
        at = len(MAGIC) + 8
        expected = int.from_bytes(blob[len(MAGIC):at], 'big')
        need(0 < expected <= MAX_PLAIN, 'journal capacity exceeded')
        associated = AAD + blob[:at]
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    salt, nonce = blob[at:at + 16], blob[at + 16:at + 28]
    try:
        key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=200000).derive(password)
        data = AESGCM(key).decrypt(nonce, blob[at + 28:], associated)
    except Exception:
        raise InvalidBatch('journal authentication failed') from None
    if not legacy:
        try:
            decoder = zlib.decompressobj()
            data = decoder.decompress(data, expected + 1)
            need(len(data) == expected and decoder.eof and not decoder.unconsumed_tail
                 and not decoder.unused_data, 'invalid compressed journal')
        except zlib.error:
            raise InvalidBatch('invalid compressed journal') from None
    need(data.startswith(b'SQLite format 3\x00'), 'invalid journal database')
    return data


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise InvalidBatch('journal redirects forbidden')


class PrivateJournalContents:
    """Fixed destination, no URLs or branches from input; token never in a file/log."""
    def __init__(self, token, opener=None):
        need(isinstance(token, str) and bool(token.strip()), 'narrow private journal credential unavailable')
        self.token = token
        self.opener = opener or urllib.request.build_opener(NoRedirect())

    def _request(self, payload=None):
        request = urllib.request.Request(URL + ('?ref=main' if payload is None else ''),
            data=None if payload is None else json.dumps(payload).encode(),
            headers={'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
                     'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json'},
            method='GET' if payload is None else 'PUT')
        try:
            with self.opener.open(request, timeout=45) as response:
                data = response.read(MAX_RESPONSE)
                need(len(data) < MAX_RESPONSE, 'journal response too large')
                return json.loads(data)
        except Exception:
            raise InvalidBatch('private journal read or save unavailable; reconcile before continuing') from None

    def load(self):
        raw = self._request()
        try:
            need(raw.get('type') == 'file' and raw.get('encoding') == 'base64', 'journal content unavailable')
            revision = raw['sha']
            need(isinstance(revision, str) and len(revision) == 40, 'journal revision missing')
            content = base64.b64decode(''.join(raw['content'].split()), validate=True)
            need(content and len(content) <= MAX_ENVELOPE, 'journal capacity exceeded')
            return revision, content
        except (KeyError, TypeError, ValueError):
            raise InvalidBatch('private journal content invalid') from None

    def save(self, revision, blob):
        # GitHub Contents SHA is a compare-and-swap precondition. No blind PUT,
        # force push, automatic retries, branch creation or journal replacement.
        need(isinstance(blob, bytes) and 0 < len(blob) <= MAX_ENVELOPE, 'journal capacity exceeded')
        result = self._request({'message': 'Persist encrypted live batch journal [skip ci]', 'branch': 'main',
                                'sha': revision, 'content': base64.b64encode(blob).decode()})
        expected = hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\x00' + blob).hexdigest()
        need(result.get('content', {}).get('sha') == expected, 'journal save acknowledgement invalid')
        return expected


class DurableBatchJournal(BatchJournal):
    def __init__(self, path, remote, password):
        self.remote, self.password, self.poisoned = remote, password, False
        self.revision, blob = remote.load()  # Missing remote never bootstraps.
        data = unseal(blob, password)
        path = Path(path)
        need(not path.parent.is_symlink() and path.parent.stat().st_mode & 0o077 == 0, 'private journal directory required')
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'wb') as out:
            out.write(data); out.flush(); os.fsync(out.fileno())
        super().__init__(path)
        self.path = path

    def hold_legacy_sources(self, archive_commit, records):
        # Remote bootstrap is intentionally not provided by this runtime class.
        raise InvalidBatch('legacy import requires separately reviewed offline bootstrap')

    def _persist(self):
        need(not self.poisoned, 'journal held after save failure')
        fd, tmp = tempfile.mkstemp(prefix='.journal-', dir=self.path.parent)
        os.close(fd)
        try:
            copy = sqlite3.connect(tmp)
            try:
                self.db.backup(copy)
            finally:
                copy.close()
            with open(tmp, 'rb') as snapshot:
                data = snapshot.read(MAX_PLAIN + 1)
            blob = seal(data, self.password)
            self.revision = self.remote.save(self.revision, blob)
        except BaseException:
            self.poisoned = True
            raise
        finally:
            Path(tmp).unlink(missing_ok=True)

    @contextlib.contextmanager
    def transaction(self):
        need(not self.poisoned, 'journal held after save failure')
        with super().transaction():
            yield
        # Must acknowledge remote durable intent BEFORE adapter execution.
        self._persist()
