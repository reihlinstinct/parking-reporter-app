#!/usr/bin/env python3
import hashlib
import importlib.util
import io
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('fetch', Path(__file__).with_name('fetch-live-photo.py'))
fetch = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch)


class FakeOpener:
    def __init__(self, data):
        self.data, self.requests = data, []

    def open(self, request, timeout):
        self.requests.append(request)
        return io.BytesIO(self.data)


class PhotoTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dest = Path(self.tmp.name) / 'photo.jpg'
        self.env = dict(TRIAL_PRIVATE_PHOTO_REF='a' * 40,
            TRIAL_PRIVATE_PHOTO_PATH='reports/synthetic-1/original.jpg',
            APPROVED_PHOTO_SHA256_INPUT=hashlib.sha256(b'photo').hexdigest(),
            PRIVATE_LOOKUP_READ_TOKEN='synthetic-token')

    def test_success_exact_private_repo_immutable_ref_and_mode(self):
        opener = FakeOpener(b'photo')
        fetch.stage(self.dest, self.env, opener)
        self.assertEqual(b'photo', self.dest.read_bytes())
        self.assertEqual(0o600, self.dest.stat().st_mode & 0o777)
        request = opener.requests[0]
        self.assertEqual('https://api.github.com/repos/reihlinstinct/parking-reports/contents/reports/synthetic-1/original.jpg?ref=' + 'a' * 40, request.full_url)
        self.assertEqual('Bearer synthetic-token', request.get_header('Authorization'))

    def test_invalid_input_never_requests(self):
        for key, values in {
            'TRIAL_PRIVATE_PHOTO_REF': ['', 'main', 'A' * 40, 'a' * 40 + '&x=1'],
            'TRIAL_PRIVATE_PHOTO_PATH': ['', '../original.jpg', 'reports/../original.jpg', 'https://elsewhere/original.jpg', 'reports/x/original.jpg?x=1', 'reports/a/b/original.jpg'],
            'APPROVED_PHOTO_SHA256_INPUT': ['', 'x' * 64],
            'PRIVATE_LOOKUP_READ_TOKEN': [''],
        }.items():
            for value in values:
                with self.subTest(key=key, value=value):
                    opener = FakeOpener(b'photo')
                    with self.assertRaises(ValueError):
                        fetch.stage(self.dest, {**self.env, key: value}, opener)
                    self.assertFalse(opener.requests)
                    self.assertFalse(self.dest.exists())

    def test_bad_or_empty_or_oversize_preserves_existing_and_cleans_temp(self):
        self.dest.write_bytes(b'prior')
        for data in [b'', b'wrong', b'photo' * 30]:
            with patch.object(fetch, 'MAX_BYTES', 100):
                with self.assertRaises(ValueError):
                    fetch.stage(self.dest, self.env, FakeOpener(data))
            self.assertEqual(b'prior', self.dest.read_bytes())
            self.assertEqual([self.dest], list(self.dest.parent.iterdir()))

    def test_network_failure_cleans_temp(self):
        opener = FakeOpener(b'photo')
        with patch.object(opener, 'open', side_effect=OSError('synthetic failure')):
            with self.assertRaises(OSError):
                fetch.stage(self.dest, self.env, opener)
        self.assertEqual([], list(self.dest.parent.iterdir()))

    def test_redirect_cannot_forward_credential(self):
        with self.assertRaises(ValueError):
            fetch.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://elsewhere/')


if __name__ == '__main__':
    unittest.main()
