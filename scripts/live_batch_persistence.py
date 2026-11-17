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

from live_batch_input import InvalidBatch, need
from live_batch_journal import BatchJournal

MAGIC = b'parking-journal-aesgcm-v1\x00'
AAD = b'reihlinstinct/parking-reports:journal/live-batch.sqlite.aesgcm:v1'
URL = 'https://api.github.com/repos/reihlinstinct/parking-reports/contents/journal/live-batch.sqlite.aesgcm'
MAX_PLAIN = 768 * 1024


def seal(data, password):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    need(isinstance(password, bytes) and len(password) >= 16, 'journal key unavailable')
    need(0 < len(data) <= MAX_PLAIN, 'journal capacity exceeded')
    salt, nonce = os.urandom(16), os.urandom(12)
    key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=200000).derive(password)
    return MAGIC + salt + nonce + AESGCM(key).encrypt(nonce, data, AAD)


def unseal(blob, password):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    need(isinstance(password, bytes) and len(password) >= 16, 'journal key unavailable')
    need(blob.startswith(MAGIC) and len(blob) <= MAX_PLAIN + len(MAGIC) + 44, 'invalid journal envelope')
    at = len(MAGIC); salt, nonce = blob[at:at + 16], blob[at + 16:at + 28]
    try:
        key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=200000).derive(password)
        data = AESGCM(key).decrypt(nonce, blob[at + 28:], AAD)
    except Exception:
        raise InvalidBatch('journal authentication failed') from None
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
                data = response.read(2 * MAX_PLAIN + 4096)
                need(len(data) < 2 * MAX_PLAIN + 4096, 'journal response too large')
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
            need(content and len(content) <= MAX_PLAIN + len(MAGIC) + 44, 'journal capacity exceeded')
            return revision, content
        except (KeyError, TypeError, ValueError):
            raise InvalidBatch('private journal content invalid') from None

    def save(self, revision, blob):
        # GitHub Contents SHA is a compare-and-swap precondition. No blind PUT,
        # force push, automatic retries, branch creation or journal replacement.
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
            blob = seal(Path(tmp).read_bytes(), self.password)
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
