import tempfile
import unittest
from pathlib import Path

from live_batch_hybrid import run_hybrid
from live_batch_input import InvalidBatch
from live_batch_journal import BatchJournal
from live_batch_ui_adapter import UiBoundaryAdapter, selection_digest

BATCH = 'b' * 64
P1, P2 = {'sha256': '1' * 64, 'path': 'photos/a1.jpg'}, {'sha256': '2' * 64, 'path': 'photos/a2.jpg'}
REPORTS = [{'reportId': 'a', 'photos': [P1, P2]}, {'reportId': 'z', 'photos': []}]
CASE = {'CaseIdInCRM': 'case', 'WebCaseIdInCRM': 'web'}


class FakeDevice:
    def __init__(self):
        self.log, self.gates, self.results, self.fail = [], [], [], set()

    def ready(self):
        self.log.append('ready')
        if 'ready' in self.fail: raise RuntimeError('x')

    def stage(self, report): self.log.append(('stage', report['reportId']))
    def wait_gate(self, timeout):
        if 'gate' in self.fail: raise TimeoutError()
        return self.gates.pop(0)
    def grant(self, op, rid, seq): self.log.append(('grant', op, rid, seq))
    def wait_result(self, timeout):
        if 'result' in self.fail: raise TimeoutError()
        return self.results.pop(0)
    def abort(self): self.log.append('abort')


class UiAdapterTest(unittest.TestCase):
    def adapter(self, ids=('a',), approved=None, check=None, device=None):
        self.d = device or FakeDevice()
        self.checked = []
        return UiBoundaryAdapter(self.d, BATCH, list(ids), approved or selection_digest(BATCH, ids),
                                 check or (lambda r, p: self.checked.append(p['sha256'])))

    def test_selection_must_match_approved_binding(self):
        with self.assertRaises(InvalidBatch): self.adapter(ids=('a', 'z'), approved=selection_digest(BATCH, ['a']))
        self.assertEqual(selection_digest(BATCH, ['a', 'z']), selection_digest(BATCH, ['z', 'a']))
        self.assertNotEqual(selection_digest(BATCH, ['a']), selection_digest('c' * 64, ['a']))
        for bad in ([], ['a', 'b', 'c'], ['a', 'a']):
            with self.assertRaises(InvalidBatch): selection_digest(BATCH, bad)

    def test_requires_photo_preflight(self):
        with self.assertRaises(InvalidBatch):
            UiBoundaryAdapter(FakeDevice(), BATCH, ['a'], selection_digest(BATCH, ['a']), None)

    def test_preflight_checks_photos_and_device_before_anything(self):
        ad = self.adapter()
        ad.preflight(REPORTS[:1])
        self.assertEqual(self.checked, ['1' * 64, '2' * 64]); self.assertEqual(self.d.log, ['ready'])
        with self.assertRaises(InvalidBatch): self.adapter().preflight(REPORTS)  # not the approved set
        def bad(r, p): raise InvalidBatch('synthetic bad image')
        with self.assertRaises(InvalidBatch): self.adapter(check=bad).preflight(REPORTS[:1])
        d = FakeDevice(); d.fail.add('ready')
        with self.assertRaises(InvalidBatch): self.adapter(device=d).preflight(REPORTS[:1])

    def test_full_report_grants_only_at_boundaries_in_order(self):
        ad = self.adapter()
        self.d.gates = [dict(op='create', reportId='a', sequence=0), dict(op='attach', reportId='a', sequence=0, outgoingSha256='d' * 64),
                        dict(op='attach', reportId='a', sequence=1, outgoingSha256='2' * 64)]
        self.d.results = [dict(success=True, case=CASE), dict(success=True), dict(success=True)]
        self.assertEqual(ad.create(REPORTS[0]), CASE)
        self.assertEqual(self.d.log, [('stage', 'a'), ('grant', 'create', 'a', 0)])
        self.assertTrue(ad.attach(CASE, REPORTS[0], P1)); self.assertTrue(ad.attach(CASE, REPORTS[0], P2))
        self.assertEqual(self.d.log[2:], [('grant', 'attach', 'a', 0), ('grant', 'attach', 'a', 1)])
        with self.assertRaises(InvalidBatch): ad.attach(CASE, REPORTS[0], P2)

    def test_unexpected_gate_is_never_granted_and_aborts(self):
        for gate in [dict(op='attach', reportId='a', sequence=0), dict(op='create', reportId='other', sequence=0),
                     dict(op='create', reportId='a', sequence=1)]:
            ad = self.adapter(); self.d.gates = [gate]
            with self.assertRaises(InvalidBatch): ad.create(REPORTS[0])
            self.assertNotIn(('grant', 'create', 'a', 0), self.d.log); self.assertIn('abort', self.d.log)
            with self.assertRaises(InvalidBatch): ad.create(REPORTS[0])

    def test_timeouts_and_unconfirmed_results_poison_without_retry(self):
        d = FakeDevice(); d.fail.add('gate'); ad = self.adapter(device=d)
        with self.assertRaises(InvalidBatch): ad.create(REPORTS[0])
        d = FakeDevice(); ad = self.adapter(device=d)
        d.gates = [dict(op='create', reportId='a', sequence=0)]; d.results = [dict(success=False)]
        with self.assertRaises(InvalidBatch): ad.create(REPORTS[0])
        self.assertEqual(sum(1 for e in d.log if e == ('grant', 'create', 'a', 0)), 1)
        d = FakeDevice(); ad = self.adapter(device=d)
        d.gates = [dict(op='create', reportId='a', sequence=0)]; d.results = [dict(success=True)]  # no case
        with self.assertRaises(InvalidBatch): ad.create(REPORTS[0])

    def test_harness_photo_bytes_must_match_manifest_after_first(self):
        ad = self.adapter()
        self.d.gates = [dict(op='create', reportId='a', sequence=0), dict(op='attach', reportId='a', sequence=0),
                        dict(op='attach', reportId='a', sequence=1, outgoingSha256='e' * 64)]
        self.d.results = [dict(success=True, case=CASE), dict(success=True)]
        ad.create(REPORTS[0]); ad.attach(CASE, REPORTS[0], P1)
        with self.assertRaises(InvalidBatch): ad.attach(CASE, REPORTS[0], P2)
        self.assertNotIn(('grant', 'attach', 'a', 1), self.d.log)

    def test_out_of_order_unapproved_or_foreign_case_refused(self):
        ad = self.adapter()
        with self.assertRaises(InvalidBatch): ad.create(REPORTS[1])  # z not approved
        ad = self.adapter(); self.d.gates = [dict(op='create', reportId='a', sequence=0)]
        self.d.results = [dict(success=True, case=CASE)]; ad.create(REPORTS[0])
        with self.assertRaises(InvalidBatch): ad.attach(CASE, REPORTS[0], P2)  # skipped P1
        with self.assertRaises(InvalidBatch): ad.attach({'CaseIdInCRM': 'x', 'WebCaseIdInCRM': 'y'}, REPORTS[0], P1)

    def test_zero_photo_report_finishes_after_create_and_close_aborts_unfinished(self):
        ad = self.adapter(ids=('z',)); self.d.gates = [dict(op='create', reportId='z', sequence=0)]
        self.d.results = [dict(success=True, case=CASE)]
        ad.create(REPORTS[1]); ad.close(); self.assertNotIn('abort', self.d.log)
        ad = self.adapter(); self.d.gates = [dict(op='create', reportId='a', sequence=0)]
        self.d.results = [dict(success=True, case=CASE)]; ad.create(REPORTS[0]); ad.close()
        self.assertIn('abort', self.d.log)

    def test_controller_never_reaches_the_device_before_journal_intent(self):
        t = tempfile.TemporaryDirectory(); self.addCleanup(t.cleanup)
        j = BatchJournal(Path(t.name) / 'journal.db'); self.addCleanup(j.close)
        manifest = {'schemaVersion': 2, 'batchId': 'batch', 'reports': [
            dict(reportId='a', textSha256='a' * 64, photos=[P1]), dict(reportId='z', textSha256='b' * 64, photos=[])]}
        d = FakeDevice(); states = []
        orig_grant = d.grant
        d.grant = lambda op, rid, seq: (states.append((op, j.state(rid))), orig_grant(op, rid, seq))
        d.gates = [dict(op='create', reportId='a', sequence=0), dict(op='attach', reportId='a', sequence=0)]
        d.results = [dict(success=True, case=CASE), dict(success=True)]
        ad = self.adapter(device=d)
        class Direct:
            def preflight(self, reports): pass
            def create(self, report): return {'WebCaseIdInCRM': 'w', 'CaseIdInCRM': 'c'}
            def attach(self, *a): return True
        run_hybrid(j, manifest, ['a'], Direct(), ad)
        self.assertEqual(states, [('create', 'creating'), ('attach', 'attaching')])
        self.assertEqual(j.state('a'), 'complete'); self.assertEqual(j.state('z'), 'complete')


if __name__ == '__main__':
    unittest.main()
