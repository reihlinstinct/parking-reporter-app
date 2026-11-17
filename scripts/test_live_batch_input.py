import copy
import hashlib
import json
import os
import stat
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from live_batch_input import InvalidBatch, validate_zip


class BatchInputTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'input.zip'
        self.photo = b'\xff\xd8\xffsynthetic-test-not-real-image'
        self.addresses = b'{}'
        self.manifest = dict(schemaVersion=1, batchId='synthetic', reports=[dict(
            reportId='one', text='synthetic report', textSha256=self.sha(b'synthetic report'),
            plate='1234567', address='synthetic street', latitude=31.0, longitude=35.0,
            capturedAt='2026-11-17T05:28:00+02:00', subjectCode='1145',
            photos=[dict(path='photos/a.jpg', sha256=self.sha(self.photo))])])

    @staticmethod
    def sha(data):
        return hashlib.sha256(data).hexdigest()

    def write(self, transform=None, extra=(), raw=None):
        m = copy.deepcopy(self.manifest)
        if transform:
            transform(m)
        with zipfile.ZipFile(self.path, 'w') as z:
            z.writestr('manifest.json', raw if raw is not None else json.dumps(m))
            z.writestr('addresses.json', self.addresses)
            z.writestr('photos/a.jpg', self.photo)
            for name, data in extra:
                z.writestr(name, data)
        return self.sha(self.path.read_bytes())

    def read(self, sha):
        return validate_zip(self.path, sha, {'1145', '1146'}, self.sha(self.addresses))

    def test_valid_no_side_effects(self):
        result = self.read(self.write())
        self.assertEqual(result['batchId'], 'synthetic')
        self.assertEqual(sorted(os.listdir(self.tmp.name)), ['input.zip'])

    def test_several_reports_topics_and_photos(self):
        def change(m):
            second = copy.deepcopy(m['reports'][0])
            second.update(reportId='two', subjectCode='1146')
            second['photos'] = [dict(path='photos/b.jpg', sha256=self.sha(self.photo + b'b')),
                                dict(path='photos/c.jpg', sha256=self.sha(self.photo + b'c'))]
            m['reports'].append(second)
        result = self.read(self.write(change, [('photos/b.jpg', self.photo + b'b'), ('photos/c.jpg', self.photo + b'c')]))
        self.assertEqual(len(result['reports']), 2)
        self.assertEqual(len(result['reports'][1]['photos']), 2)

    def test_strict_manifest_and_report_fields(self):
        for transform in [lambda m: m.update(command='bad'),
                          lambda m: m['reports'][0].update(shell='bad'),
                          lambda m: m.update(schemaVersion=True),
                          lambda m: m.update(reports=[]),
                          lambda m: m['reports'][0].update(subjectCode='unverified'),
                          lambda m: m['reports'][0].update(latitude=True),
                          lambda m: m['reports'][0].update(longitude=181),
                          lambda m: m['reports'][0].update(capturedAt='2026-11-17T05:28:00'),
                          lambda m: m['reports'][0].update(photos=[]),
                          lambda m: m['reports'][0].update(plate='\u0661\u0662\u0663\u0664\u0665'),
                          lambda m: m['reports'][0].update(text='changed')]:
            with self.subTest(transform=transform), self.assertRaises(InvalidBatch):
                self.read(self.write(transform))

    def test_zip_attacks(self):
        for name in ['../bad', '/absolute', 'photos/../bad', 'photos//bad', 'photos\\bad', 'photos/.hidden', 'extra.sh']:
            with self.subTest(name=name), self.assertRaises(InvalidBatch):
                self.read(self.write(extra=[(name, b'bad')]))

    def test_duplicate_photos_and_reports(self):
        def twice(m):
            m['reports'].append(copy.deepcopy(m['reports'][0]))
        with self.assertRaises(InvalidBatch):
            self.read(self.write(twice))
        def duplicate_digest(m):
            m['reports'][0]['photos'].append(dict(path='photos/b.jpg', sha256=self.sha(self.photo)))
        with self.assertRaises(InvalidBatch):
            self.read(self.write(duplicate_digest, [('photos/b.jpg', self.photo)]))

    def test_pins(self):
        sha = self.write()
        with self.assertRaises(InvalidBatch):
            self.read('0' * 64)
        with self.assertRaises(InvalidBatch):
            validate_zip(self.path, sha, {'1145'}, '0' * 64)
        def wrong(m):
            m['reports'][0]['photos'][0]['sha256'] = '0' * 64
        with self.assertRaises(InvalidBatch):
            self.read(self.write(wrong))

    def test_duplicate_json_and_nan(self):
        for raw in ['{"batchId":"x","batchId":"y"}', '{"number":NaN}', '{bad', '[]']:
            with self.subTest(raw=raw), self.assertRaises(InvalidBatch):
                self.read(self.write(raw=raw))

    def test_special_file(self):
        sha = self.write()
        with zipfile.ZipFile(self.path, 'a') as z:
            info = zipfile.ZipInfo('photos/link.jpg')
            info.create_system = 3
            info.external_attr = (stat.S_IFLNK | 0o777) << 16
            z.writestr(info, 'target')
        with self.assertRaises(InvalidBatch):
            self.read(self.sha(self.path.read_bytes()))

    def test_resource_caps(self):
        sha = self.write()
        for cap in ['MAX_ARCHIVE_BYTES', 'MAX_EXPANDED_BYTES', 'MAX_ENTRIES', 'MAX_MANIFEST_BYTES']:
            with self.subTest(cap=cap), patch('live_batch_input.' + cap, 1), self.assertRaises(InvalidBatch):
                self.read(sha)


if __name__ == '__main__':
    unittest.main()
