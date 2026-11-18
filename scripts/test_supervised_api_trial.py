"""Synthetic supervised trial boundaries. Never calls network, VPN or municipality."""
import base64
import copy
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import supervised_api_trial as trial


class TrialTest(unittest.TestCase):
    def setUp(self):
        self.report = dict(reportId='synthetic-trial', text='synthetic exact text', subjectCode='9000',
                           latitude=0, longitude=0, locationDescription='synthetic crossing',
                           photos=[dict(path='photos/approved.jpg', sha256='b' * 64)])
        self.events, self.records = [], []
        parent = self
        class Claim:
            def save(self, record):
                parent.events.append(('save', record['state']))
                parent.records.append(copy.deepcopy(record))
        class Api:
            def preflight(self, reports): parent.events.append(('preflight',))
            def create(self, report):
                parent.assertEqual('creating', parent.records[-1]['state'])
                parent.events.append(('create',))
                return dict(CaseIdInCRM='synthetic-case', WebCaseIdInCRM='synthetic-web', CaseNumberInCRM=None)
            def attach(self, case, report, photo):
                parent.assertEqual('attaching', parent.records[-1]['state'])
                parent.events.append(('attach',))
                return True
        self.claim, self.api = Claim(), Api()

    def run_trial(self):
        return trial.execute_trial(self.report, 'a' * 64, 'b' * 64, self.claim, self.api, 'synthetic-run')

    def test_success_has_durable_intent_before_each_write_and_exact_receipts(self):
        result = self.run_trial()
        self.assertEqual([('preflight',), ('save', 'reserved'), ('save', 'creating'), ('create',),
                          ('save', 'created'), ('save', 'attaching'), ('attach',), ('save', 'complete')], self.events)
        self.assertIsNone(result['case']['CaseNumberInCRM'])
        self.assertEqual('synthetic-case', result['case']['CaseIdInCRM'])
        self.assertEqual(self.report, result['report'])

    def test_every_persistence_failure_prevents_next_external_write(self):
        for failed, writes in [('reserved', 0), ('creating', 0), ('created', 1), ('attaching', 1), ('complete', 2)]:
            self.events.clear(); self.records.clear()
            def save(record):
                if record['state'] == failed: raise ValueError('synthetic storage uncertainty')
                self.records.append(copy.deepcopy(record))
            self.claim.save = save
            with self.assertRaises(ValueError): self.run_trial()
            self.assertEqual(writes, sum(e[0] in ('create', 'attach') for e in self.events))

    def test_preflight_create_and_attachment_failure_never_retry(self):
        for method in ('preflight', 'create', 'attach'):
            self.setUp()
            def fail(*args):
                self.events.append(('failed', method)); raise ValueError('synthetic operation uncertainty')
            setattr(self.api, method, fail)
            with self.assertRaises(ValueError): self.run_trial()
            self.assertEqual(1, self.events.count(('failed', method)))
            self.assertFalse(any(e[0] == 'attach' for e in self.events))

    def test_photo_identity_mismatch_refuses_before_ports(self):
        self.report['photos'][0]['sha256'] = 'c' * 64
        with self.assertRaises(ValueError): self.run_trial()
        self.assertEqual([], self.events)

    def test_real_private_claim_http_is_create_once_then_cas_and_poison_on_failure(self):
        requests = []
        class Opener:
            def open(self, request, timeout):
                requests.append(request)
                content = base64.b64decode(json.loads(request.data)['content'])
                sha = hashlib.sha1(b'blob ' + str(len(content)).encode() + b'\x00' + content).hexdigest()
                return io.BytesIO(json.dumps(dict(content=dict(sha=sha))).encode())
        with patch.object(trial, 'encrypt_record', side_effect=[b'first', b'second']):
            claim = trial.PrivateTrialClaim('a' * 64, 'synthetic-token', b'synthetic-key-at-least16', Opener())
            claim.save(dict(state='reserved')); claim.save(dict(state='creating'))
        first, second = [json.loads(r.data) for r in requests]
        self.assertNotIn('sha', first)
        expected = hashlib.sha1(b'blob 5\x00first').hexdigest()
        self.assertEqual(expected, second['sha'])
        self.assertEqual(['PUT', 'PUT'], [r.method for r in requests])
        self.assertTrue(all('/api-trials/' in r.full_url and r.full_url.endswith('.aesgcm') for r in requests))
        class Refuse:
            def open(self, *args, **kwargs): raise ValueError('synthetic existing claim')
        claim = trial.PrivateTrialClaim('a' * 64, 'synthetic-token', b'synthetic-key-at-least16', Refuse())
        with patch.object(trial, 'encrypt_record', return_value=b'opaque') as enc:
            with self.assertRaises(ValueError): claim.save(dict(state='reserved'))
            with self.assertRaises(ValueError): claim.save(dict(state='reserved'))
            self.assertEqual(1, enc.call_count)
        self.assertTrue(claim.poisoned)

    def test_real_encryption_is_authenticated_and_does_not_expose_private_text(self):
        blob = trial.encrypt_record(dict(text='synthetic-private-text'), b'synthetic-password16')
        self.assertNotIn(b'synthetic-private-text', blob)
        self.assertNotEqual(blob, trial.encrypt_record(dict(text='synthetic-private-text'), b'synthetic-password16'))

    def test_pinned_zip_exact_inventory_hashes_and_duplicate_rejection(self):
        original, photo = b'\xff\xd8synthetic-original', b'\xff\xd8synthetic-outgoing'
        report = copy.deepcopy(self.report)
        report['photos'][0]['sha256'] = trial.digest(photo)
        data = dict(report=report, originalSha256=trial.digest(original), outgoingSha256=trial.digest(photo))
        raw = json.dumps(data).encode()
        env = dict(APPROVED_TRIAL_JSON_SHA256=trial.digest(raw), APPROVED_ORIGINAL_SHA256=trial.digest(original),
                   APPROVED_OUTGOING_SHA256=trial.digest(photo))
        def package(path, extra=None):
            with zipfile.ZipFile(path, 'w') as archive:
                for name, body in [('trial.json', raw), ('original.jpg', original), ('photos/approved.jpg', photo)]:
                    archive.writestr(name, body)
                if extra: archive.writestr(*extra)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'input.zip'
            package(path)
            self.assertEqual((data, photo), trial.validate_package(path, env))
            for key in env:
                wrong = dict(env, **{key: 'c' * 64})
                with self.assertRaises(ValueError): trial.validate_package(path, wrong)
            for extra in [('trial.json', b'{}'), ('../synthetic', b'x')]:
                package(path, extra)
                with self.assertRaises(ValueError): trial.validate_package(path, env)

    def test_workflow_is_hard_disabled_and_always_encrypts_and_cleans_up(self):
        source = (Path(__file__).resolve().parents[1] / '.github/workflows/supervised-api-trial.yml').read_text()
        for text in ('if: ${{ false }}', 'environment: m0-trial-submit', 'group: live-emulator-e2e',
                     'cancel-in-progress: false', 'persist-credentials: false', 'if: always()',
                     'supervised-api-trial-evidence.tgz.enc', 'sudo python3 trial/l2tp.py down'):
            self.assertIn(text, source)
        self.assertNotIn('continue-on-error', source)
        self.assertNotIn('contents: write', source)
        self.assertLess(source.index('up-bounded'), source.index('J106_APP_PASSWORD'))


if __name__ == '__main__': unittest.main()
