"""Synthetic legacy candidates only. No real archive, credentials or remote seed."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from live_batch_input import InvalidBatch
from live_batch_journal import BatchJournal
from live_batch_persistence import DurableBatchJournal


class HeldAliasesTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / 'held.db'
        self.j = BatchJournal(self.path)
        self.addCleanup(lambda: self.j.close())
        self.commit = 'a' * 40
        self.record = dict(source='reports/synthetic/report.json', sourceSha256='b' * 64,
                           status='uncertain', reportIds=['old-synthetic'],
                           textCandidates=[dict(field='description', sha256='c' * 64)],
                           photoCandidates=[dict(field='photo_sha256', sha256='d' * 64)],
                           unresolvedReferences=['missing-synthetic-original.jpg'])
        self.manifest = dict(batchId='new', reports=[dict(
            reportId='fresh', textSha256='e' * 64, photos=[dict(sha256='f' * 64)])])

    def test_shared_candidates_preserve_all_sources_and_fields_without_completion(self):
        other = copy.deepcopy(self.record)
        other.update(source='requests/synthetic-followup/request.json', status='historical_only')
        other['textCandidates'].append(dict(field='approved_text', sha256='c' * 64))
        self.j.hold_legacy_sources(self.commit, [self.record, other])
        self.assertEqual(self.j.db.execute('SELECT count(*) FROM legacy_sources').fetchone()[0], 2)
        self.assertEqual(self.j.db.execute('SELECT count(*) FROM legacy_aliases').fetchone()[0], 7)
        raw = self.j.db.execute('SELECT provenance_json FROM legacy_sources WHERE source=?',
                                (other['source'],)).fetchone()[0]
        self.assertEqual(json.loads(raw), other)
        self.assertEqual(self.j.db.execute('SELECT count(*) FROM reports').fetchone()[0], 0)
        with self.assertRaises(InvalidBatch): self.j.begin_create('old-synthetic')

    def test_report_text_and_unresolved_photo_claim_each_block_new_reservation(self):
        self.j.hold_legacy_sources(self.commit, [self.record])
        for field in ('report', 'text', 'photo'):
            manifest = copy.deepcopy(self.manifest)
            report = manifest['reports'][0]
            if field == 'report': report['reportId'] = 'old-synthetic'
            elif field == 'text': report['textSha256'] = 'c' * 64
            else: report['photos'][0]['sha256'] = 'd' * 64
            with self.subTest(kind=field), self.assertRaisesRegex(InvalidBatch, 'legacy identity held'):
                self.j.reserve(manifest)
            self.assertEqual(self.j.db.execute('SELECT count(*) FROM batches').fetchone()[0], 0)
            self.assertEqual(self.j.db.execute('SELECT count(*) FROM reports').fetchone()[0], 0)
        self.j.reserve(self.manifest)
        self.assertEqual(self.j.state('fresh'), 'reserved')

    def test_candidates_survive_reopen_and_archive_versions_preserve_provenance(self):
        self.j.hold_legacy_sources(self.commit, [self.record])
        self.j.close()
        self.j = BatchJournal(self.path)
        self.j.hold_legacy_sources('0' * 40, [self.record])
        self.assertEqual(self.j.db.execute('SELECT count(*) FROM legacy_sources').fetchone()[0], 2)
        manifest = copy.deepcopy(self.manifest)
        manifest['reports'][0]['textSha256'] = 'c' * 64
        with self.assertRaises(InvalidBatch): self.j.reserve(manifest)

    def test_invalid_late_record_rolls_back_entire_import(self):
        invalid = copy.deepcopy(self.record)
        invalid['source'] = 'requests/synthetic/request.json'
        invalid['photoCandidates'][0]['sha256'] = 'not-a-digest'
        with self.assertRaises(InvalidBatch):
            self.j.hold_legacy_sources(self.commit, [self.record, invalid])
        self.assertEqual(self.j.db.execute('SELECT count(*) FROM legacy_sources').fetchone()[0], 0)
        self.assertEqual(self.j.db.execute('SELECT count(*) FROM legacy_aliases').fetchone()[0], 0)

    def test_duplicate_source_is_not_silently_overwritten_or_dropped(self):
        with self.assertRaises(InvalidBatch):
            self.j.hold_legacy_sources(self.commit, [self.record, self.record])
        self.assertEqual(self.j.db.execute('SELECT count(*) FROM legacy_sources').fetchone()[0], 0)

    def test_import_refuses_runtime_batches_and_remote_class(self):
        self.j.reserve(self.manifest)
        with self.assertRaises(InvalidBatch): self.j.hold_legacy_sources(self.commit, [self.record])
        remote = object.__new__(DurableBatchJournal)
        with self.assertRaisesRegex(InvalidBatch, 'offline bootstrap'):
            remote.hold_legacy_sources(self.commit, [self.record])

    def test_invalid_commit_and_empty_identity_refused(self):
        for commit in ('main', 'a' * 39, 'A' * 40):
            with self.assertRaises(InvalidBatch): self.j.hold_legacy_sources(commit, [self.record])
        record = dict(self.record, reportIds=[], textCandidates=[], photoCandidates=[])
        with self.assertRaises(InvalidBatch): self.j.hold_legacy_sources(self.commit, [record])

    def test_held_photo_prevents_both_hybrid_lanes_before_any_create(self):
        from live_batch_hybrid import run_hybrid
        self.j.hold_legacy_sources(self.commit, [self.record])
        manifest = copy.deepcopy(self.manifest)
        manifest['reports'].append(dict(reportId='other', textSha256='0' * 64,
                                        photos=[dict(sha256='d' * 64)]))
        class Adapter:
            def preflight(self, reports): pass
            def create(self, report): raise AssertionError('held batch must not write')
        with self.assertRaisesRegex(InvalidBatch, 'legacy identity held'):
            run_hybrid(self.j, manifest, ['fresh'], Adapter(), Adapter())
        self.assertEqual(self.j.db.execute('SELECT count(*) FROM reports').fetchone()[0], 0)

    def test_malformed_existing_version_cannot_reinitialize_missing_history(self):
        self.j.db.executescript('DROP TABLE reports;')
        self.j.close()
        # Keep cleanup bound to a valid connection even though opening must fail.
        import sqlite3
        self.j.db = sqlite3.connect(self.path)
        with self.assertRaisesRegex(InvalidBatch, 'schema structure invalid'):
            BatchJournal(self.path)
        self.assertNotIn('reports', {row[0] for row in self.j.db.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")})

    def test_v1_runtime_history_migration_preserves_reserved_identities(self):
        self.j.reserve(self.manifest)
        self.j.db.executescript('DROP TABLE legacy_aliases; DROP TABLE legacy_sources; PRAGMA user_version=1;')
        before = self.j.db.execute('SELECT * FROM reports').fetchall()
        self.j.close()
        self.j = BatchJournal(self.path)
        self.assertEqual(self.j.db.execute('PRAGMA user_version').fetchone()[0], 2)
        self.assertEqual(self.j.db.execute('SELECT * FROM reports').fetchall(), before)
        self.assertEqual(self.j.state('fresh'), 'reserved')
        with self.assertRaises(InvalidBatch): self.j.reserve(self.manifest)


if __name__ == '__main__': unittest.main()
