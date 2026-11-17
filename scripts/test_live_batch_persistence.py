"""No network: exercise encrypted snapshots, CAS conflicts and durable write order."""
import hashlib
from pathlib import Path
import tempfile
import unittest

from live_batch_input import InvalidBatch
from live_batch_journal import BatchJournal, run_mock_batch
from live_batch_persistence import DurableBatchJournal, seal, unseal

PASSWORD = b'synthetic-test-key-not-real-secret'


class Remote:
    def __init__(self, blob): self.blob, self.revision, self.fail = blob, 'initial', False
    def load(self): return self.revision, self.blob
    def save(self, revision, blob):
        if self.fail or revision != self.revision: raise InvalidBatch('synthetic conflict or failure')
        self.blob = blob; self.revision = hashlib.sha256(blob).hexdigest(); return self.revision


class PersistenceTest(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory(); self.addCleanup(self.t.cleanup)
        self.p = Path(self.t.name)
        seed = self.p / 'seed.db'; j = BatchJournal(seed); j.close()
        self.remote = Remote(seal(seed.read_bytes(), PASSWORD))
        self.m = {'schemaVersion': 2, 'batchId': 'batch', 'reports': [dict(reportId='report', textSha256='a' * 64, photos=[])]}

    def open(self, name):
        j = DurableBatchJournal(self.p / name, self.remote, PASSWORD); self.addCleanup(j.close); return j

    def test_authenticated_envelope(self):
        plain = unseal(self.remote.blob, PASSWORD)
        self.assertTrue(plain.startswith(b'SQLite format 3'))
        corrupt = bytearray(self.remote.blob); corrupt[-1] ^= 1
        with self.assertRaises(InvalidBatch): unseal(bytes(corrupt), PASSWORD)
        with self.assertRaises(InvalidBatch): unseal(self.remote.blob, b'wrong-synthetic-password')

    def test_intent_remote_before_external_call_and_complete_persists(self):
        j = self.open('one.db'); j.reserve(self.m)
        parent = self
        class Adapter:
            def create(self, report):
                reopened = parent.open('during.db')
                parent.assertEqual(reopened.state('report'), 'creating')
                return {'WebCaseIdInCRM': 'synthetic-case'}
        run_mock_batch(j, self.m, Adapter())
        self.assertEqual(self.open('after.db').state('report'), 'complete')
        with self.assertRaises(InvalidBatch): self.open('retry.db').begin_create('report')

    def test_save_failure_before_create_never_calls_adapter(self):
        j = self.open('one.db'); j.reserve(self.m); self.remote.fail = True
        class Adapter:
            def create(self, report): raise AssertionError('adapter must not run')
        with self.assertRaises(InvalidBatch): run_mock_batch(j, self.m, Adapter())
        self.assertTrue(j.poisoned)
        with self.assertRaises(InvalidBatch): j.begin_create('report')

    def test_remote_complete_ack_failure_leaves_creating_held(self):
        j = self.open('one.db'); j.reserve(self.m); parent = self
        class Adapter:
            def create(self, report):
                parent.remote.fail = True
                return {'WebCaseIdInCRM': 'synthetic-case'}
        with self.assertRaises(InvalidBatch): run_mock_batch(j, self.m, Adapter())
        self.remote.fail = False
        reopened = self.open('after.db')
        self.assertEqual(reopened.state('report'), 'creating')
        with self.assertRaises(InvalidBatch): reopened.begin_create('report')

    def test_two_writer_cas_conflict_stops_stale_writer(self):
        j = self.open('one.db'); stale = self.open('stale.db'); j.reserve(self.m)
        with self.assertRaises(InvalidBatch): stale.reserve(dict(self.m, batchId='other'))
        self.assertTrue(stale.poisoned)


if __name__ == '__main__': unittest.main()
