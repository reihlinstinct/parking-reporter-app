"""Bounded storage format tests, synthetic bytes, no remote or real archive."""
import builtins
import os
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch
import zlib

from live_batch_input import InvalidBatch
import live_batch_persistence as storage
from live_batch_journal import BatchJournal

PASSWORD = b'synthetic-capacity-test-password'


class CapacityTest(unittest.TestCase):
    def encrypted(self, payload, size, legacy=False):
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
        from cryptography.hazmat.primitives import hashes
        salt, nonce = b's' * 16, b'n' * 12
        header = storage.LEGACY_MAGIC if legacy else storage.MAGIC + size.to_bytes(8, 'big')
        aad = storage.LEGACY_AAD if legacy else storage.AAD + header
        key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=200000).derive(PASSWORD)
        return header + salt + nonce + AESGCM(key).encrypt(nonce, payload, aad)

    def test_legacy_read_then_new_format_write_preserves_sqlite(self):
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'synthetic.db'
            j = BatchJournal(path); j.close()
            data = path.read_bytes()
        legacy = self.encrypted(data, len(data), legacy=True)
        self.assertEqual(storage.unseal(legacy, PASSWORD), data)
        current = storage.seal(storage.unseal(legacy, PASSWORD), PASSWORD)
        self.assertTrue(current.startswith(storage.MAGIC))
        self.assertEqual(storage.unseal(current, PASSWORD), data)

    def test_plaintext_and_compressed_limits_refuse_without_crypto_import(self):
        real_import = builtins.__import__
        def importer(name, *args, **kwargs):
            if name == 'cryptography' or name.startswith('cryptography.'):
                raise AssertionError('capacity refusal imported cryptography')
            return real_import(name, *args, **kwargs)
        with patch('builtins.__import__', side_effect=importer):
            with self.assertRaisesRegex(InvalidBatch, 'journal capacity exceeded'):
                storage.seal(b'x' * (storage.MAX_PLAIN + 1), PASSWORD)
            random = b'SQLite format 3\x00' + os.urandom(storage.MAX_COMPRESSED + 4096)
            with self.assertRaisesRegex(InvalidBatch, 'compressed journal capacity exceeded'):
                storage.seal(random, PASSWORD)

    def test_header_authentication_unknown_version_and_truncated_envelope(self):
        data = b'SQLite format 3\x00' + b'synthetic' * 100
        blob = storage.seal(data, PASSWORD)
        changed = bytearray(blob)
        changed[len(storage.MAGIC) + 7] ^= 1
        with self.assertRaisesRegex(InvalidBatch, 'authentication failed'):
            storage.unseal(bytes(changed), PASSWORD)
        for bad in (b'unknown-version', blob[:20], blob[:-1], blob + b'garbage'):
            with self.assertRaises(InvalidBatch): storage.unseal(bad, PASSWORD)

    def test_authenticated_compression_bomb_size_lie_trailing_and_truncated_stream(self):
        data = b'SQLite format 3\x00' + b'x' * 10000
        packed = zlib.compress(data)
        for payload, expected in ((packed, 16), (packed, len(data) + 1),
                                  (packed + b'trailing', len(data)),
                                  (packed + packed, len(data)), (packed[:-1], len(data)),
                                  (b'not-zlib', len(data))):
            with self.subTest(expected=expected, size=len(payload)):
                with self.assertRaisesRegex(InvalidBatch, 'invalid compressed journal'):
                    storage.unseal(self.encrypted(payload, expected), PASSWORD)
        oversized = storage.MAGIC + (storage.MAX_PLAIN + 1).to_bytes(8, 'big') + b'x' * 60
        with self.assertRaisesRegex(InvalidBatch, 'capacity exceeded'):
            storage.unseal(oversized, PASSWORD)

    def test_real_limit_high_ratio_bomb_refuses_with_bounded_decode(self):
        # Authenticate a legal size header around a payload expanding to twice
        # the real limit. Observe the actual decoder call, not a patched limit.
        data = b'SQLite format 3\x00' + b'x' * (2 * storage.MAX_PLAIN)
        packed = zlib.compress(data)
        self.assertLess(len(packed), storage.MAX_COMPRESSED)
        blob = self.encrypted(packed, storage.MAX_PLAIN)
        real_decoder = zlib.decompressobj
        observed = []
        class BoundedDecoder:
            def __init__(self): self.decoder = real_decoder()
            def decompress(self, payload, max_length=0):
                observed.append(max_length)
                if max_length != storage.MAX_PLAIN + 1:
                    raise AssertionError('decode must use the real finite bound')
                result = self.decoder.decompress(payload, max_length)
                self_output_sizes.append(len(result))
                return result
            def __getattr__(self, name): return getattr(self.decoder, name)
        self_output_sizes = []
        with patch.object(storage.zlib, 'decompressobj', BoundedDecoder):
            with self.assertRaisesRegex(InvalidBatch, 'invalid compressed journal'):
                storage.unseal(blob, PASSWORD)
        self.assertEqual(observed, [storage.MAX_PLAIN + 1])
        self.assertEqual(self_output_sizes, [storage.MAX_PLAIN + 1])

    def test_max_plaintext_boundary_is_supported_when_stored_size_fits(self):
        data = b'SQLite format 3\x00' + b'\x00' * (storage.MAX_PLAIN - 16)
        blob = storage.seal(data, PASSWORD)
        self.assertLessEqual(len(blob), storage.MAX_ENVELOPE)
        self.assertEqual(storage.unseal(blob, PASSWORD), data)

    def test_capacity_failure_poisoning_keeps_remote_snapshot_and_prevents_create(self):
        from live_batch_persistence import DurableBatchJournal
        class Remote:
            def load(self): return 'synthetic', self.blob
            def save(self, *args): raise AssertionError('capacity failure must not save')
        with tempfile.TemporaryDirectory() as root:
            path = Path(root) / 'seed.db'
            j = BatchJournal(path); j.close()
            remote = Remote(); remote.blob = storage.seal(path.read_bytes(), PASSWORD)
            original = remote.blob
            runtime = DurableBatchJournal(Path(root) / 'runtime.db', remote, PASSWORD)
            try:
                with patch.object(storage, 'MAX_PLAIN', 16):
                    with self.assertRaisesRegex(InvalidBatch, 'capacity exceeded'):
                        runtime.reserve(dict(batchId='synthetic', reports=[dict(
                            reportId='synthetic', textSha256='a' * 64, photos=[])]))
                self.assertTrue(runtime.poisoned)
                self.assertEqual(remote.blob, original)
                with self.assertRaises(InvalidBatch): runtime.begin_create('synthetic')
            finally: runtime.close()

    def test_remote_save_refuses_oversize_before_network(self):
        remote = storage.PrivateJournalContents('synthetic-not-a-token')
        with patch.object(remote, '_request', side_effect=AssertionError('must not send')):
            with self.assertRaises(InvalidBatch): remote.save('a' * 40, b'x' * (storage.MAX_ENVELOPE + 1))


if __name__ == '__main__': unittest.main()
