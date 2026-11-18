import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from fetch_live_batch import NoRedirect, stage


class Opener:
    def __init__(self, payload=b'synthetic zip bytes', error=None):
        self.payload, self.error = payload, error
        self.calls = []

    def open(self, request, timeout):
        self.calls.append((request, timeout))
        if self.error:
            raise self.error
        return io.BytesIO(self.payload)


class FetchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dest = Path(self.tmp.name) / 'private' / 'input.zip'
        self.env = dict(BATCH_PRIVATE_REF='a'*40, BATCH_PRIVATE_PATH='batches/synthetic/input.zip',
                        BATCH_APPROVED_OUTER_SHA256=hashlib.sha256(b'synthetic zip bytes').hexdigest(),
                        PRIVATE_LOOKUP_READ_TOKEN='synthetic-not-a-real-secret')

    def test_trial_encrypted_only_refuses_plain_before_network(self):
        opener = Opener()
        with self.assertRaises(ValueError): stage(self.dest, self.env, opener, encrypted_only=True)
        self.assertEqual([], opener.calls)
        self.env['BATCH_PRIVATE_PATH'] += '.enc'
        stage(self.dest, self.env, opener, encrypted_only=True)
        self.assertEqual(1, len(opener.calls))

    def test_pinned_fixed_private_endpoint(self):
        for suffix in ['', '.enc']:
            self.env['BATCH_PRIVATE_PATH'] = 'batches/synthetic/input.zip' + suffix
            opener = Opener()
            stage(self.dest, self.env, opener)
            request, timeout = opener.calls[0]
            self.assertEqual(request.full_url, 'https://api.github.com/repos/reihlinstinct/parking-reports/contents/batches/synthetic/input.zip' + suffix + '?ref=' + 'a'*40)
            self.assertEqual(timeout, 60)
            self.assertEqual(request.get_header('Authorization'), 'Bearer synthetic-not-a-real-secret')
            self.assertEqual(self.dest.stat().st_mode & 0o777, 0o600)

    def test_bad_inputs_never_call_network(self):
        for key, value in [('BATCH_PRIVATE_REF', 'main'), ('BATCH_PRIVATE_REF', 'a'*39),
                           ('BATCH_PRIVATE_PATH', 'https://attacker.invalid/input.zip'),
                           ('BATCH_PRIVATE_PATH', 'batches/../input.zip'),
                           ('BATCH_PRIVATE_PATH', 'reports/synthetic/original.jpg'),
                           ('BATCH_PRIVATE_PATH', 'batches/id/input.zip?ref=main'),
                           ('BATCH_APPROVED_OUTER_SHA256', 'bad'), ('PRIVATE_LOOKUP_READ_TOKEN', '')]:
            with self.subTest(key=key, value=value):
                env = dict(self.env, **{key: value})
                opener = Opener()
                with self.assertRaises(ValueError):
                    stage(self.dest, env, opener)
                self.assertEqual(opener.calls, [])

    def test_failure_preserves_prior_file_and_cleans_partial(self):
        self.dest.parent.mkdir(mode=0o700)
        self.dest.write_bytes(b'prior')
        for opener in [Opener(b''), Opener(b'wrong'), Opener(error=RuntimeError('sensitive fake URL'))]:
            with self.subTest(opener=opener), self.assertRaisesRegex(ValueError, '^private batch staging failed$'):
                stage(self.dest, self.env, opener)
            self.assertEqual(self.dest.read_bytes(), b'prior')
            self.assertEqual(list(self.dest.parent.iterdir()), [self.dest])

    def test_size_limit(self):
        with patch('fetch_live_batch.MAX_BYTES', 2), self.assertRaises(ValueError):
            stage(self.dest, self.env, Opener())
        self.assertFalse(self.dest.exists())
        self.assertEqual(list(self.dest.parent.iterdir()), [])

    def test_existing_staging_directory_must_be_private(self):
        self.dest.parent.mkdir(mode=0o755)
        with self.assertRaisesRegex(ValueError, 'private staging directory required'):
            stage(self.dest, self.env, Opener())
        self.assertEqual(list(self.dest.parent.iterdir()), [])

    def test_redirect_http_error_never_publishes_or_retries_encrypted_input(self):
        import urllib.error
        import urllib.request
        self.env['BATCH_PRIVATE_PATH'] += '.enc'
        for code in (301, 302, 303, 307, 308):
            with self.subTest(code=code):
                failure = urllib.error.HTTPError('https://fixed.invalid/', code,
                    'synthetic redirect', {'Location': 'https://other.invalid/'}, None)
                opener = Opener(error=failure)
                with self.assertRaisesRegex(ValueError, '^private batch staging failed$'):
                    stage(self.dest, self.env, opener, encrypted_only=True)
                self.assertEqual(1, len(opener.calls))
                self.assertFalse(self.dest.exists())
                self.assertEqual([], list(self.dest.parent.iterdir()))
                request = opener.calls[0][0]
                self.assertEqual('Bearer synthetic-not-a-real-secret', request.get_header('Authorization'))
                with self.assertRaises(ValueError):
                    NoRedirect().redirect_request(request, None, code, '',
                        {'Location': 'https://other.invalid/'}, 'https://other.invalid/')
        # Offline refusal proof only. No approved private package was retrieved.

    def test_no_auth_redirect(self):
        with self.assertRaisesRegex(ValueError, 'redirects are forbidden'):
            NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.invalid/')


if __name__ == '__main__':
    unittest.main()
