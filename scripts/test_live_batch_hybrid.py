import copy
from pathlib import Path
import tempfile
import unittest

from live_batch_hybrid import partition, run_hybrid
from live_batch_input import InvalidBatch
from live_batch_journal import BatchJournal


class HybridTest(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory(); self.addCleanup(self.t.cleanup)
        self.j = BatchJournal(Path(self.t.name) / 'journal.db'); self.addCleanup(self.j.close)
        self.m = {'schemaVersion': 2, 'batchId': 'batch', 'reports': [
            dict(reportId='a', textSha256='a' * 64, photos=[]),
            dict(reportId='b', textSha256='b' * 64, photos=[dict(sha256='c' * 64, path='photos/b.jpg')])]}
        self.events = []
        parent = self
        class Adapter:
            def __init__(self, lane): self.lane = lane
            def preflight(self, reports): parent.events.append(('preflight', self.lane))
            def create(self, report):
                parent.assertEqual(parent.j.state(report['reportId']), 'creating')
                parent.events.append(('create', self.lane))
                return {'WebCaseIdInCRM': 'synthetic-' + report['reportId']}
            def attach(self, case, report, photo):
                parent.assertEqual(parent.j.state(report['reportId']), 'attaching')
                parent.events.append(('attach', self.lane)); return True
        self.ui, self.direct = Adapter('ui'), Adapter('direct')

    def test_hybrid_order_and_zero_photo(self):
        run_hybrid(self.j, self.m, ['a'], self.direct, self.ui)
        self.assertEqual(self.events, [('preflight','ui'), ('preflight','direct'), ('create','ui'), ('create','direct'), ('attach','direct')])
        self.assertEqual(self.j.state('a'), 'complete'); self.assertEqual(self.j.state('b'), 'complete')
        with self.assertRaises(InvalidBatch): run_hybrid(self.j, self.m, ['a'], self.direct, self.ui)
        self.assertEqual(sum(e[0] == 'create' for e in self.events), 2)

    def test_missing_ui_or_invalid_partition_never_writes(self):
        for selected in [[], ['a','a'], ['unknown']]:
            with self.assertRaises(InvalidBatch): partition(self.m, selected)
        with self.assertRaises(InvalidBatch): run_hybrid(self.j, self.m, ['a'], self.direct, None)
        self.assertEqual(self.events, [])

    def test_readonly_preflight_failure_never_reserves(self):
        def fail(reports): raise InvalidBatch('synthetic login failure')
        self.direct.preflight = fail
        with self.assertRaises(InvalidBatch): run_hybrid(self.j, self.m, ['a'], self.direct, self.ui)
        self.assertEqual(self.j.db.execute('SELECT count(*) FROM reports').fetchone()[0], 0)
        self.assertFalse(any(e[0] == 'create' for e in self.events))

    def test_create_failure_holds_and_prevents_other_lane(self):
        def fail(report): raise InvalidBatch('synthetic unknown create')
        self.ui.create = fail
        with self.assertRaises(InvalidBatch): run_hybrid(self.j, self.m, ['a'], self.direct, self.ui)
        self.assertEqual(self.j.state('a'), 'creating'); self.assertEqual(self.j.state('b'), 'reserved')
        self.assertFalse(any(e == ('create','direct') for e in self.events))

    def test_attachment_failure_no_fallback_or_recreate(self):
        self.direct.attach = lambda *args: False
        with self.assertRaises(InvalidBatch): run_hybrid(self.j, self.m, ['a'], self.direct, self.ui)
        self.assertEqual(self.j.state('a'), 'complete'); self.assertEqual(self.j.state('b'), 'attaching')
        self.assertEqual(sum(e[0] == 'create' for e in self.events), 2)

    def test_workflow_stays_protected_prepare_only_and_secret_free_public(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/live-batch-hybrid.yml').read_text()
        for required in ['workflow_dispatch:', "github.ref == 'refs/heads/main'", 'github.run_attempt == 1',
                         'environment: m0-trial-submit', 'group: live-emulator-e2e', 'cancel-in-progress: false',
                         'persist-credentials: false', 'BATCH_PREPARATION_ONLY_NO_SUBMISSION']:
            self.assertIn(required, workflow)
        self.assertNotIn('BATCH_BRIDGE_AUTHORIZED', workflow)
        self.assertNotIn('J106_APP_PASSWORD', workflow)
        self.assertNotIn('REPORTER_ID_NUMBER', workflow)
        self.assertNotIn('contents: write', workflow)
        self.assertNotIn('run_hybrid(', workflow)
        self.assertIn('path: /tmp/hybrid-prepared/effective.json.enc', workflow)
        prep = (root / 'scripts/prepare_hybrid_batch.py').read_text()
        self.assertNotIn('live_batch_hybrid', prep)
        self.assertNotIn('live_batch_persistence', prep)
        self.assertNotIn('CreateCase', prep)


if __name__ == '__main__': unittest.main()
