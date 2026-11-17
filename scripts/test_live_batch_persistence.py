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

    def test_real_hybrid_process_death_keeps_remote_intent_across_both_lanes(self):
        import json
        import os
        import subprocess
        import sys
        # The file-backed remote is a local test stand-in. Only encrypted bytes
        # cross the child-process boundary; no municipal/network operation exists.
        child = r'''
import hashlib,json,os,sys
from pathlib import Path
from live_batch_hybrid import run_hybrid
from live_batch_persistence import DurableBatchJournal
class DiskRemote:
    def load(self):
        data=Path(sys.argv[1]).read_bytes()
        return hashlib.sha256(data).hexdigest(),data
    def save(self, revision, blob):
        assert self.load()[0]==revision
        temporary=Path(sys.argv[1]+'.tmp')
        with temporary.open('wb') as stream:
            stream.write(blob);stream.flush();os.fsync(stream.fileno())
        os.replace(temporary,sys.argv[1])
        return hashlib.sha256(blob).hexdigest()
j=DurableBatchJournal(sys.argv[2],DiskRemote(),b'synthetic-test-key-not-real-secret')
m=json.loads(sys.argv[3]);counter=[0]
class Port:
    def preflight(self,reports): pass
    def boundary(self):
        counter[0]+=1
        if counter[0]==int(sys.argv[4]):os._exit(73)
    def create(self,report):
        self.boundary();return {'WebCaseIdInCRM':'synthetic-'+report['reportId']}
    def attach(self,case,report,photo):
        self.boundary();return True
run_hybrid(j,m,['ui'],Port(),Port())
'''
        manifest = {'schemaVersion':2,'batchId':'hybrid-kill','reports':[
            dict(reportId='ui',textSha256='1'*64,photos=[dict(sha256='3'*64)]),
            dict(reportId='direct',textSha256='2'*64,photos=[dict(sha256='4'*64)])]}
        expected=[('creating','reserved'),('attaching','reserved'),
                  ('complete','creating'),('complete','attaching')]
        for boundary,states in enumerate(expected,1):
            with self.subTest(boundary=boundary):
                remote=self.p/('remote%d.enc'%boundary)
                remote.write_bytes(self.remote.blob)
                result=subprocess.run([sys.executable,'-c',child,str(remote),
                    str(self.p/('child%d.db'%boundary)),json.dumps(manifest),str(boundary)],
                    env=dict(os.environ,PYTHONPATH=str(Path(__file__).parent)),capture_output=True,timeout=15)
                self.assertEqual(result.returncode,73)
                class ReadOnlyRemote:
                    def load(inner):return 'synthetic',remote.read_bytes()
                    def save(inner,*args):raise AssertionError('replay must not save or write')
                reopened=DurableBatchJournal(self.p/('parent%d.db'%boundary),ReadOnlyRemote(),PASSWORD)
                try:
                    self.assertEqual((reopened.state('ui'),reopened.state('direct')),states)
                    from live_batch_hybrid import run_hybrid
                    class NoWrites:
                        def preflight(inner,reports):pass
                        def create(inner,report):raise AssertionError('must never replay')
                        def attach(inner,*args):raise AssertionError('must never resend')
                    with self.assertRaises(InvalidBatch):
                        run_hybrid(reopened,manifest,['ui'],NoWrites(),NoWrites())
                finally:reopened.close()


if __name__ == '__main__': unittest.main()
