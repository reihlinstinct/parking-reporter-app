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
        self.report = dict(reportId='synthetic-trial', text='synthetic exact text', subjectCode='1137',
                           latitude=31.8, longitude=35.2, locationDescription='synthetic crossing',
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

    def test_zero_crm_with_web_receipt_attaches_exact_pair_once(self):
        case = dict(CaseIdInCRM='00000000-0000-0000-0000-000000000000', WebCaseIdInCRM='synthetic-web', CaseNumberInCRM=None)
        self.api.create = lambda report: case
        calls = []
        def attach(actual, report, photo):
            self.assertEqual(case, actual)
            self.assertEqual('attaching', self.records[-1]['state'])
            calls.append('attach')
            return True
        self.api.attach = attach
        result = self.run_trial()
        self.assertEqual(['attach'], calls)
        self.assertTrue(result['photoAttached'])
        self.assertTrue(result['noReplay'])

    def test_empty_or_zero_only_receipt_never_attaches(self):
        for case in [None, {}, dict(CaseIdInCRM='', WebCaseIdInCRM='synthetic-web'),
                     dict(CaseIdInCRM='00000000-0000-0000-0000-000000000000', WebCaseIdInCRM='00000000-0000-0000-0000-000000000000')]:
            self.setUp()
            self.api.create = lambda report: case
            with self.assertRaises(ValueError): self.run_trial()
            self.assertFalse(any(e[0] == 'attach' for e in self.events))

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

    def test_activation_refuses_wrong_package_expiry_and_coordinate_envelope(self):
        from datetime import datetime, timezone
        config = json.loads((Path(trial.__file__).parent / 'supervised_trial_activation.json').read_text())
        env = dict(BATCH_PRIVATE_REF=config['privateCommit'], BATCH_PRIVATE_PATH=config['batchPath'],
                   BATCH_APPROVED_OUTER_SHA256=config['outerSha256'])
        trial.validate_activation(env, datetime(2026, 11, 18, 11, tzinfo=timezone.utc))
        with self.assertRaises(ValueError):
            trial.validate_activation(env, datetime(2026, 11, 18, 16, tzinfo=timezone.utc))
        for key in env:
            with self.assertRaises(ValueError): trial.validate_activation(dict(env, **{key: 'wrong'}))
        trial.validate_trial_coordinates(self.report)
        for changed in [dict(latitude=0), dict(longitude=0), dict(latitude=True), dict(latitude=float('nan')), dict(subjectCode='1145')]:
            with self.assertRaises(ValueError): trial.validate_trial_coordinates(dict(self.report, **changed))

    def test_coordinate_envelope_inclusive_edges_and_nonfinite_refusal(self):
        for lat in (31.65, 31.95):
            for lon in (35.05, 35.35):
                trial.validate_trial_coordinates(dict(self.report, latitude=lat, longitude=lon))
        for lat, lon in [(31.649999, 35.2), (31.950001, 35.2), (31.8, 35.049999),
                (31.8, 35.350001), (float('inf'), 35.2), (31.8, False)]:
            with self.assertRaises(ValueError):
                trial.validate_trial_coordinates(dict(self.report, latitude=lat, longitude=lon))

    def test_expiry_at_each_write_boundary_stops_without_replay(self):
        from datetime import datetime, timezone
        config = json.loads((Path(trial.__file__).parent / 'supervised_trial_activation.json').read_text())
        env = dict(BATCH_PRIVATE_REF=config['privateCommit'], BATCH_PRIVATE_PATH=config['batchPath'],
                   BATCH_APPROVED_OUTER_SHA256=config['outerSha256'])
        for expired_write in (1, 2):
            self.setUp()
            calls = []
            def guard():
                calls.append(1)
                hour = 16 if len(calls) == expired_write else 15
                trial.validate_activation(env, datetime(2026, 11, 18, hour, tzinfo=timezone.utc))
            with self.assertRaises(ValueError):
                trial.execute_trial(self.report, 'a' * 64, 'b' * 64, self.claim, self.api,
                                    'synthetic-run', write_guard=guard)
            self.assertEqual(expired_write, len(calls))
            self.assertEqual('creating' if expired_write == 1 else 'attaching', self.records[-1]['state'])
            creates = sum(event[0] == 'create' for event in self.events)
            attaches = sum(event[0] == 'attach' for event in self.events)
            self.assertEqual(0 if expired_write == 1 else 1, creates)
            self.assertEqual(0, attaches)

    def test_production_routed_rechecks_after_routes_and_inflight_expiry(self):
        from datetime import datetime, timezone
        config = json.loads((Path(trial.__file__).parent / 'supervised_trial_activation.json').read_text())
        env = dict(BATCH_PRIVATE_REF=config['privateCommit'], BATCH_PRIVATE_PATH=config['batchPath'],
                   BATCH_APPROVED_OUTER_SHA256=config['outerSha256'])
        now = [datetime(2026, 11, 18, 15, 59, 59, tzinfo=timezone.utc)]
        expired = datetime(2026, 11, 18, 16, tzinfo=timezone.utc)
        # Route checking may consume the remaining time; production wrapper must refuse.
        def route(): now[0] = expired
        routed = trial.Routed(self.api, route, env, lambda: now[0])
        for call in (lambda: routed.create(self.report), lambda: routed.attach({}, self.report, {})):
            with self.assertRaises(ValueError): call()
        self.assertEqual([], self.events)
        # An already-started request can return after cutoff. Keep its receipt, never attach/replay.
        now[0] = datetime(2026, 11, 18, 15, 59, 59, tzinfo=timezone.utc)
        create = self.api.create
        def crossing(report):
            receipt = create(report)
            now[0] = expired
            return receipt
        self.api.create = crossing
        routed = trial.Routed(self.api, lambda: None, env, lambda: now[0])
        with self.assertRaises(ValueError):
            trial.execute_trial(self.report, 'a' * 64, 'b' * 64, self.claim, routed, 'synthetic')
        self.assertEqual('attaching', self.records[-1]['state'])
        self.assertIn('case', self.records[-1])
        self.assertEqual(1, sum(e[0] == 'create' for e in self.events))
        self.assertEqual(0, sum(e[0] == 'attach' for e in self.events))

    def test_routed_deadline_before_at_after_for_both_effects(self):
        from datetime import datetime, timedelta
        config = json.loads((Path(trial.__file__).parent / 'supervised_trial_activation.json').read_text())
        env = dict(BATCH_PRIVATE_REF=config['privateCommit'], BATCH_PRIVATE_PATH=config['batchPath'], BATCH_APPROVED_OUTER_SHA256=config['outerSha256'])
        deadline = datetime.fromisoformat(config['expiresAt'])
        for delta in (-1, 0, 1):
            for effect in ('create', 'attach'):
                calls = []
                class Api:
                    def create(self, *args): calls.append('create')
                    def attach(self, *args): calls.append('attach')
                routed = trial.Routed(Api(), lambda: None, env, lambda: deadline + timedelta(microseconds=delta))
                operation = lambda: routed.create(self.report) if effect == 'create' else routed.attach({}, self.report, {})
                if delta < 0: operation()
                else:
                    with self.assertRaises(ValueError): operation()
                self.assertEqual([effect] if delta < 0 else [], calls)

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

    def test_png_bytes_with_matching_approval_pins_still_refuse(self):
        for original, photo in [(b'\x89PNG\r\n\x1a\nsynthetic', b'\xff\xd8synthetic'),
                (b'\xff\xd8synthetic', b'\x89PNG\r\n\x1a\nsynthetic')]:
            report = copy.deepcopy(self.report)
            report['photos'][0]['sha256'] = trial.digest(photo)
            raw = json.dumps(dict(report=report, originalSha256=trial.digest(original), outgoingSha256=trial.digest(photo))).encode()
            env = dict(APPROVED_TRIAL_JSON_SHA256=trial.digest(raw), APPROVED_ORIGINAL_SHA256=trial.digest(original), APPROVED_OUTGOING_SHA256=trial.digest(photo))
            with tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'input.zip'
                with zipfile.ZipFile(path, 'w') as archive:
                    for name, body in [('trial.json', raw), ('original.jpg', original), ('photos/approved.jpg', photo)]:
                        archive.writestr(name, body)
                with self.assertRaisesRegex(ValueError, 'JPEG'):
                    trial.validate_package(path, env)

    def test_suffix_precedes_fetch_and_tar_has_immediate_and_failure_cleanup(self):
        root = Path(__file__).resolve().parents[1]
        script = (root / 'scripts/supervised_api_trial.py').read_text()
        self.assertLess(script.index("need(os.environ['BATCH_PRIVATE_PATH'].endswith"), script.index("stage(root / 'input.zip.enc', encrypted_only=True)"))
        workflow = (root / '.github/workflows/supervised-api-trial.yml').read_text()
        self.assertIn("trap 'shred -u /tmp/supervised-api-trial-evidence.tgz", workflow)
        self.assertIn('test -s /tmp/supervised-api-trial-evidence.tgz.enc\n            shred -u /tmp/supervised-api-trial-evidence.tgz', workflow)
        import check_repository
        self.assertTrue(check_repository.trial_job_pinned(workflow))
        gate = next(line for line in workflow.splitlines() if line.startswith('    if:'))
        for changed in (workflow.replace(gate, '    if: ${{ true }}'), workflow.replace(gate, ''), workflow.replace(gate, '  '+gate)):
            self.assertFalse(check_repository.trial_job_pinned(changed))

    def test_workflow_is_pinned_and_always_encrypts_and_cleans_up(self):
        source = (Path(__file__).resolve().parents[1] / '.github/workflows/supervised-api-trial.yml').read_text()
        for text in ("inputs.confirm == 'GPS_ONLY_ONCE'", 'environment: m0-trial-submit', 'group: live-emulator-e2e',
                     'cancel-in-progress: false', 'persist-credentials: false', 'if: always()',
                     'supervised-api-trial-evidence.tgz.enc', 'sudo python3 trial/l2tp.py down'):
            self.assertIn(text, source)
        self.assertNotIn('continue-on-error', source)
        self.assertNotIn('contents: write', source)
        self.assertLess(source.index('up-bounded'), source.index('J106_APP_PASSWORD'))


if __name__ == '__main__': unittest.main()
