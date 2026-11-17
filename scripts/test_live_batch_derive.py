"""Synthetic v2 author inputs only. No municipal calls, real photos or private data."""
import copy
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import subprocess
import sys
import unittest
from unittest import mock
import zipfile

from live_batch_input import InvalidBatch, validate_zip
from live_batch_derive import exif_capture
from live_batch_journal import BatchJournal, run_mock_batch


def exif(date=b'2026:11:17 05:28:00\0', offset=b'+02:00\0'):
    # TIFF root at8, EXIF directory at26, strings at56 and76.
    t = b'II' + struct.pack('<HI', 42, 8)
    t += struct.pack('<HHHII', 1, 0x8769, 4, 1, 26) + b'\0' * 4
    t += struct.pack('<H', 2) + struct.pack('<HHII', 0x9003, 2, 20, 56)
    t += struct.pack('<HHII', 0x9011, 2, 7, 76) + b'\0' * 4 + date + offset
    payload = b'Exif\0\0' + t
    return b'\xff\xd8\xff\xe1' + (len(payload) + 2).to_bytes(2, 'big') + payload + b'\xff\xd9'


class DeriveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.directory = {'version': 1, 'streets': [{'StreetCode': '100', 'StreetName': 'synthetic street'}],
                          'addresses': [[31.7, 35.2, '10', 'synthetic street']]}
        self.policy = {'9000': {'names': ['synthetic topic'], 'plateRequired': False},
                       '9001': {'names': ['synthetic plate topic'], 'plateRequired': True}}
        self.m = {'reports': [{'text': 'synthetic report', 'address': 'synthetic street 10', 'topic': 'synthetic topic'}]}
        self.files = {}

    def read(self):
        a = json.dumps(self.directory).encode()
        p = self.root / 'batch.zip'
        with zipfile.ZipFile(p, 'w') as z:
            z.writestr('manifest.json', json.dumps(self.m)); z.writestr('addresses.json', a)
            for name, data in self.files.items(): z.writestr(name, data)
        return validate_zip(p, hashlib.sha256(p.read_bytes()).hexdigest(), set(self.policy), hashlib.sha256(a).hexdigest(), topic_policy=self.policy)

    def test_smallest_address_input_has_no_photo_plate_time_hash_or_id(self):
        m = self.read(); r = m['reports'][0]
        self.assertEqual((r['latitude'], r['longitude']), (31.7, 35.2))
        self.assertEqual(r['photos'], []); self.assertEqual(r['plate'], ''); self.assertIsNone(r['capturedAt'])
        self.assertEqual(len(r['reportId']), 64); self.assertEqual(len(m['batchId']), 64)
        self.assertEqual(m, self.read())

    def test_coordinate_only(self):
        r = self.m['reports'][0]; del r['address']; r.update(latitude=31.7, longitude=35.2)
        self.assertEqual(self.read()['reports'][0]['address'], 'synthetic street 10')

    def test_coordinate_lookup_normalizes_nfkc_and_final_letters(self):
        self.directory['streets'][0]['StreetName'] = 'דרך המלך'
        self.directory['addresses'][0][3] = 'דרכ המלכ'
        r = self.m['reports'][0]; del r['address']; r.update(latitude=31.7, longitude=35.2)
        self.assertEqual(self.read()['reports'][0]['address'], 'דרך המלך 10')
        self.directory['streets'][0]['StreetName'] = 'ＡＢＣ'
        self.directory['addresses'][0][3] = 'ABC'
        self.assertEqual(self.read()['reports'][0]['address'], 'ＡＢＣ 10')

    def test_missing_or_partial_location(self):
        del self.m['reports'][0]['address']
        for values in [{}, {'latitude': 31.7}, {'latitude': 0, 'longitude': 0}]:
            self.m['reports'][0].update(values)
            with self.assertRaises(InvalidBatch): self.read()

    def test_ambiguous_or_missing_address(self):
        for row in [[31.71, 35.21, '10', 'synthetic street'], None]:
            self.directory['addresses'] = [self.directory['addresses'][0], row] if row else []
            with self.assertRaises(InvalidBatch): self.read()

    def test_ambiguous_coordinate_address(self):
        self.directory['addresses'].append([31.70001, 35.2, '12', 'synthetic street'])
        r = self.m['reports'][0]; del r['address']; r.update(latitude=31.7, longitude=35.2)
        with self.assertRaises(InvalidBatch): self.read()

    def test_optional_hashes_checked_not_trusted(self):
        self.m['reports'][0]['textSha256'] = '0' * 64
        with self.assertRaises(InvalidBatch): self.read()

    def test_provided_generated_ids_must_match(self):
        correct = self.read(); self.m['batchId'] = correct['batchId']
        self.m['reports'][0]['reportId'] = correct['reports'][0]['reportId']; self.read()
        self.m['reports'][0]['reportId'] = 'fake'
        with self.assertRaises(InvalidBatch): self.read()

    def test_plate_policy_and_text_binding(self):
        r = self.m['reports'][0]; r['topic'] = 'synthetic plate topic'
        with self.assertRaises(InvalidBatch): self.read()
        r['plate'] = '1234567'
        with self.assertRaises(InvalidBatch): self.read()
        r['text'] += ' 1234567'; self.assertEqual(self.read()['reports'][0]['plate'], '1234567')

    def test_unknown_or_conflicting_topic(self):
        self.m['reports'][0]['subjectCode'] = '9001'
        with self.assertRaises(InvalidBatch): self.read()
        self.m['reports'][0]['topic'] = 'unknown'
        with self.assertRaises(InvalidBatch): self.read()

    def test_photo_bytes_derive_hash_and_exif_time(self):
        self.files['photos/a.jpg'] = exif(); self.m['reports'][0]['photos'] = ['photos/a.jpg']
        r = self.read()['reports'][0]
        self.assertEqual(r['capturedAt'], '2026-11-17T05:28:00+02:00')
        self.assertEqual(r['photos'][0]['sha256'], hashlib.sha256(exif()).hexdigest())

    def test_exif_without_offset_or_corrupt_never_uses_runtime(self):
        for data in [b'\xff\xd8\xffsynthetic', exif(offset=b'\0' * 7), exif()[:30]]:
            self.assertIsNone(exif_capture(data))
        self.files['photos/a.jpg'] = b'\xff\xd8\xffsynthetic'; self.m['reports'][0]['photos'] = ['photos/a.jpg']
        self.assertIsNone(self.read()['reports'][0]['capturedAt'])

    def test_photo_hash_mismatch_and_duplicates_refused(self):
        self.files['photos/a.jpg'] = exif()
        self.m['reports'][0]['photos'] = [{'path': 'photos/a.jpg', 'sha256': '0' * 64}]
        with self.assertRaises(InvalidBatch): self.read()
        self.m['reports'][0]['photos'] = ['photos/a.jpg', 'photos/a.jpg']
        with self.assertRaises(InvalidBatch): self.read()

    def test_explicit_address_rejects_180_meter_offset(self):
        self.m['reports'][0].update(latitude=31.7 + 180 / 111320, longitude=35.2)
        with self.assertRaises(InvalidBatch): self.read()

    def test_conflicting_explicit_coordinates(self):
        self.m['reports'][0].update(latitude=31.9, longitude=35.8)
        with self.assertRaises(InvalidBatch): self.read()

    def test_missing_time_is_optional_even_with_photo(self):
        self.files['photos/a.png'] = b'\x89PNG\r\n\x1a\nsynthetic'
        self.m['reports'][0]['photos'] = ['photos/a.png']
        self.assertIsNone(self.read()['reports'][0]['capturedAt'])

    def test_cli_private_output_and_constant_errors(self):
        self.read()
        policy = self.root / 'policy.json'; policy.write_text(json.dumps(self.policy))
        private = self.root / 'private'; private.mkdir(mode=0o700)
        out = private / 'effective.json'
        command = [sys.executable, str(Path(__file__).with_name('validate_live_batch.py')),
                   '--zip', str(self.root / 'batch.zip'),
                   '--approved-zip-sha256', hashlib.sha256((self.root / 'batch.zip').read_bytes()).hexdigest(),
                   '--approved-addresses-sha256', hashlib.sha256(json.dumps(self.directory).encode()).hexdigest(),
                   '--topic-policy-file', str(policy), '--output', str(out)]
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, 'BATCH_VALID_OFFLINE_ONLY\n')
        self.assertEqual(out.stat().st_mode & 0o777, 0o600)
        result = subprocess.run(command, capture_output=True, text=True)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, 'BATCH_INVALID_OR_PREPARATION_BLOCKED\n')
        self.assertEqual(result.stderr, '')

    def test_interrupted_manifest_write_never_publishes_partial_final(self):
        import validate_live_batch as cli
        self.read()
        policy = self.root / 'policy.json'; policy.write_text(json.dumps(self.policy))
        private = self.root / 'private'; private.mkdir(mode=0o700)
        out = private / 'effective.json'
        args = ['validate', '--zip', str(self.root / 'batch.zip'),
                '--approved-zip-sha256', hashlib.sha256((self.root / 'batch.zip').read_bytes()).hexdigest(),
                '--approved-addresses-sha256', hashlib.sha256(json.dumps(self.directory).encode()).hexdigest(),
                '--topic-policy-file', str(policy), '--output', str(out)]
        def partial_failure(value, stream, **kwargs):
            stream.write('{')
            raise OSError('synthetic interrupted write')
        with mock.patch.object(sys, 'argv', args), mock.patch.object(cli.json, 'dump', side_effect=partial_failure):
            self.assertEqual(cli.main(), 1)
        self.assertFalse(out.exists())
        self.assertEqual(list(private.iterdir()), [])
        with mock.patch.object(sys, 'argv', args):
            self.assertEqual(cli.main(), 0)
        self.assertEqual(out.stat().st_mode & 0o777, 0o600)
        original = out.read_bytes()
        with mock.patch.object(sys, 'argv', args):
            self.assertEqual(cli.main(), 1)
        self.assertEqual(out.read_bytes(), original)
        self.assertEqual(list(private.iterdir()), [out])
        # Atomic publication must not replace or follow an existing symlink.
        out.unlink(); target = private / 'existing.json'; target.write_text('synthetic existing')
        out.symlink_to(target)
        with mock.patch.object(sys, 'argv', args):
            self.assertEqual(cli.main(), 1)
        self.assertEqual(target.read_text(), 'synthetic existing')
        self.assertTrue(out.is_symlink())

    def test_no_photo_journal_complete_and_replay_refused(self):
        m = self.read(); j = BatchJournal(self.root / 'journal.db'); self.addCleanup(j.close)
        class Adapter:
            def create(self, report): return {'WebCaseIdInCRM': 'synthetic-case'}
            def attach(self, *args): raise AssertionError('no attachment expected')
        j.reserve(m); run_mock_batch(j, m, Adapter()); self.assertEqual(j.state(m['reports'][0]['reportId']), 'complete')
        with self.assertRaises(InvalidBatch): run_mock_batch(j, m, Adapter())

    def test_no_photo_crash_after_create_intent_stays_held(self):
        m = self.read(); path = self.root / 'journal.db'; j = BatchJournal(path); j.reserve(m)
        j.begin_create(m['reports'][0]['reportId']); j.close(); j = BatchJournal(path); self.addCleanup(j.close)
        self.assertEqual(j.state(m['reports'][0]['reportId']), 'creating')
        with self.assertRaises(InvalidBatch): j.begin_create(m['reports'][0]['reportId'])

    def test_report_identity_independent_of_batch_order_and_photo_path(self):
        self.files['photos/a.jpg'] = exif(); self.m['reports'][0]['photos'] = ['photos/a.jpg']
        old = self.read()['reports'][0]['reportId']
        self.files['photos/b.jpg'] = self.files.pop('photos/a.jpg'); self.m['reports'][0]['photos'] = ['photos/b.jpg']
        self.assertEqual(old, self.read()['reports'][0]['reportId'])

    def test_invalid_numbers_unknown_fields_and_missing_text(self):
        for k, v in [('latitude', float('nan')), ('latitude', True), ('shell', 'no'), ('subjectCode', [])]:
            m = copy.deepcopy(self.m); self.m['reports'][0][k] = v
            with self.assertRaises(InvalidBatch): self.read()
            self.m = m
        del self.m['reports'][0]['text']
        with self.assertRaises(InvalidBatch): self.read()


if __name__ == '__main__': unittest.main()
