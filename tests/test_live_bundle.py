import importlib.util
import io
import json
from pathlib import Path
import stat
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('live_bundle', Path(__file__).parents[1] / 'scripts/stage-live-bundle.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class LiveBundleTest(unittest.TestCase):
    photo = b'\xff\xd8synthetic-jpeg'
    directory = json.dumps({'streets': [], 'addresses': []}).encode()

    def bundle(self, extra=None):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr('photo.jpg', self.photo)
            archive.writestr('addresses.json', self.directory)
            if extra:
                archive.writestr(*extra)
        return stream.getvalue()

    def test_only_exact_bounded_hash_verified_inputs(self):
        self.assertEqual((self.photo, self.directory), m.validate_zip(self.bundle(), m.sha(self.photo), m.sha(self.directory)))
        for data, photo_sha in [(self.bundle(), '0' * 64), (b'notzip', m.sha(self.photo)),
                                (self.bundle(('../escape', b'x')), m.sha(self.photo)),
                                (self.bundle(('photo.jpg', b'x')), m.sha(self.photo))]:
            with self.subTest(data=data), self.assertRaises(Exception):
                m.validate_zip(data, photo_sha, m.sha(self.directory))

    def test_symlink_and_oversize_fail(self):
        link = zipfile.ZipInfo('photo.jpg')
        link.create_system = 3
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, 'w') as archive:
            archive.writestr(link, self.photo)
            archive.writestr('addresses.json', self.directory)
        with self.assertRaises(ValueError):
            m.validate_zip(stream.getvalue(), m.sha(self.photo), m.sha(self.directory))
        old = m.MAX_PHOTO
        try:
            m.MAX_PHOTO = 1
            with self.assertRaises(ValueError):
                m.validate_zip(self.bundle(), m.sha(self.photo), m.sha(self.directory))
        finally:
            m.MAX_PHOTO = old

    def test_workflow_removes_private_token_and_fixture_fallback(self):
        workflow = Path('.github/workflows/live-emulator-e2e.yml').read_text()
        self.assertIn('python3 scripts/stage-live-bundle.py', workflow)
        for forbidden in ['PRIVATE_LOOKUP_READ_TOKEN', 'TRIAL_PRIVATE_PHOTO_REF', 'fetch-live-photo.py', 'trial/trial-photo.jpg.enc']:
            self.assertNotIn(forbidden, workflow)
        self.assertIn("test \"$CONFIRM_INPUT\" = 'FILE-LIVE-REPORT'", workflow)
        self.assertIn('environment: m0-trial-submit', workflow)
