import copy
import os
import tempfile
import unittest
from pathlib import Path

from live_batch_input import InvalidBatch
from live_batch_journal import BatchJournal, run_mock_batch


class Adapter:
    def __init__(self, fail=None):
        self.calls = []
        self.fail = fail

    def create(self, report):
        self.calls.append(('create', report['reportId']))
        if self.fail == len(self.calls):
            raise RuntimeError('synthetic interruption')
        return {'WebCaseIdInCRM': 'synthetic-' + report['reportId'], 'CaseNumberInCRM': None}

    def attach(self, case, photo):
        self.calls.append(('attach', case['WebCaseIdInCRM'], photo['sha256']))
        if self.fail == len(self.calls):
            raise RuntimeError('synthetic interruption')
        return True


class JournalTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'journal.db'
        self.journal = BatchJournal(self.path)
        self.addCleanup(lambda: self.journal.close())
        self.manifest = dict(batchId='batch', reports=[
            dict(reportId='one', photos=[dict(sha256='a'*64), dict(sha256='b'*64)]),
            dict(reportId='two', photos=[dict(sha256='c'*64)])])
        self.journal.reserve(self.manifest, 'd'*64)

    def test_order_and_one_case_per_report(self):
        adapter = Adapter()
        run_mock_batch(self.journal, self.manifest, adapter)
        self.assertEqual([x[0] for x in adapter.calls], ['create', 'attach', 'attach', 'create', 'attach'])
        self.assertEqual(adapter.calls[1][1], adapter.calls[2][1])
        self.assertEqual(self.journal.state('one'), 'complete')
        self.assertEqual(self.journal.state('two'), 'complete')
        with self.assertRaises(InvalidBatch):
            run_mock_batch(self.journal, self.manifest, adapter)
        self.assertEqual(len(adapter.calls), 5)

    def test_each_adapter_interruption_stops_no_replay(self):
        # Every external write boundary is tested against reopening the durable DB.
        for failure in range(1, 6):
            with self.subTest(failure=failure):
                path = Path(self.tmp.name) / ('crash%d.db' % failure)
                j = BatchJournal(path)
                try:
                    j.reserve(self.manifest, 'd'*64)
                    adapter = Adapter(failure)
                    with self.assertRaises(RuntimeError):
                        run_mock_batch(j, self.manifest, adapter)
                    self.assertEqual(len(adapter.calls), failure)
                    j.close()
                    j = BatchJournal(path)
                    replay = Adapter()
                    with self.assertRaises(InvalidBatch):
                        run_mock_batch(j, self.manifest, replay)
                    self.assertEqual(replay.calls, [])
                finally:
                    j.close()

    def test_real_process_exit_preserves_intent(self):
        import json
        import subprocess
        import sys
        script = r"""
import json, os, sys
from live_batch_journal import BatchJournal, run_mock_batch
j = BatchJournal(sys.argv[1])
m = json.loads(sys.argv[2])
j.reserve(m, 'd' * 64)
class Adapter:
    calls = 0
    def write(self):
        self.calls += 1
        if self.calls == int(sys.argv[3]):
            os._exit(73)
    def create(self, report):
        self.write()
        return {'WebCaseIdInCRM': 'synthetic-' + report['reportId']}
    def attach(self, case, photo):
        self.write()
        return True
run_mock_batch(j, m, Adapter())
"""
        for boundary in range(1, 6):
            with self.subTest(boundary=boundary):
                path = Path(self.tmp.name) / ('killed%d.db' % boundary)
                env = dict(os.environ, PYTHONPATH=str(Path(__file__).parent))
                result = subprocess.run([sys.executable, '-c', script, str(path),
                                         json.dumps(self.manifest), str(boundary)], env=env,
                                        capture_output=True, timeout=10)
                self.assertEqual(result.returncode, 73, result.stderr)
                reopened = BatchJournal(path)
                try:
                    adapter = Adapter()
                    with self.assertRaises(InvalidBatch):
                        run_mock_batch(reopened, self.manifest, adapter)
                    self.assertEqual(adapter.calls, [])
                    report = 'one' if boundary <= 3 else 'two'
                    self.assertEqual(reopened.state(report), 'creating' if boundary in (1, 4) else 'attaching')
                finally:
                    reopened.close()

    def test_before_create_is_durable_uncertainty(self):
        self.journal.begin_create('one')
        self.journal.close()
        self.journal = BatchJournal(self.path)
        self.assertEqual(self.journal.state('one'), 'creating')
        with self.assertRaises(InvalidBatch):
            self.journal.begin_create('one')
        with self.assertRaises(InvalidBatch):
            self.journal.begin_create('two')

    def test_case_before_first_attachment_is_safe_but_not_replayed(self):
        self.journal.begin_create('one')
        self.journal.record_case('one', {'WebCaseIdInCRM': 'synthetic-case'})
        with self.assertRaises(InvalidBatch):
            self.journal.begin_attachment('one', 'b'*64)
        self.journal.begin_attachment('one', 'a'*64)
        with self.assertRaises(InvalidBatch):
            self.journal.begin_attachment('one', 'a'*64)
        with self.assertRaises(InvalidBatch):
            self.journal.begin_create('two')
        self.journal.record_attachment('one', 'a'*64)
        self.assertEqual(self.journal.state('one'), 'case_created')

    def test_atomic_conflict_reservation(self):
        other = dict(batchId='second', reports=[dict(reportId='three', photos=[dict(sha256='e'*64)]),
                                                    dict(reportId='four', photos=[dict(sha256='a'*64)])])
        with self.assertRaises(InvalidBatch):
            self.journal.reserve(other, 'd'*64)
        self.assertIsNone(self.journal.db.execute("SELECT 1 FROM batches WHERE id='second'").fetchone())
        self.assertIsNone(self.journal.db.execute("SELECT 1 FROM photos WHERE digest=?", ('e'*64,)).fetchone())

    def test_no_placeholder_case_or_false_attachment_success(self):
        self.journal.begin_create('one')
        for case in [{}, {'CaseNumberInCRM': None}, {'CaseIdInCRM': '00000000-0000-0000-0000-000000000000'}]:
            with self.assertRaises(InvalidBatch):
                self.journal.record_case('one', case)
        self.assertEqual(self.journal.state('one'), 'creating')
        self.journal.record_case('one', {'WebCaseIdInCRM': 'synthetic'})
        self.journal.begin_attachment('one', 'a'*64)
        with self.assertRaises(InvalidBatch):
            self.journal.record_attachment('one', 'b'*64)
        self.assertEqual(self.journal.state('one'), 'attaching')

    def test_private_permissions_and_symlink_refusal(self):
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        alias = Path(self.tmp.name) / 'link'
        alias.symlink_to(self.path)
        with self.assertRaises(OSError):
            BatchJournal(alias)
        os.chmod(self.path, 0o644)
        with self.assertRaises(InvalidBatch):
            BatchJournal(self.path)

    def test_concurrent_connections_cannot_reserve_same_photo(self):
        second = BatchJournal(self.path)
        try:
            m = dict(batchId='other', reports=[dict(reportId='other', photos=[dict(sha256='a'*64)])])
            with self.assertRaises(InvalidBatch):
                second.reserve(m, 'd'*64)
        finally:
            second.close()


if __name__ == '__main__':
    unittest.main()
