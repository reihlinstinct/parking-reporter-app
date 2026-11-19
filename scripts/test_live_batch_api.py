"""Synthetic API batch execution with actual journal, no network or credentials."""
import copy
from pathlib import Path
import tempfile
import unittest

from live_batch_api import run_api_batch
from live_batch_input import InvalidBatch
from live_batch_journal import BatchJournal


class ApiBatchTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'journal.db'
        self.journal = BatchJournal(self.path)
        self.addCleanup(self.journal.close)
        self.manifest = dict(schemaVersion=2, batchId='synthetic-batch', reports=[
            dict(reportId='synthetic-a', textSha256='a' * 64, photos=[]),
            dict(reportId='synthetic-b', textSha256='b' * 64, photos=[
                dict(path='photos/first.jpg', sha256='c' * 64),
                dict(path='photos/second.jpg', sha256='d' * 64)])])
        self.events = []
        parent = self

        class Api:
            def preflight(self, reports):
                parent.assertEqual(reports, parent.manifest['reports'])
                parent.events.append(('preflight',))

            def create(self, report):
                rid = report['reportId']
                parent.assertEqual('creating', parent.journal.state(rid))
                parent.events.append(('create', rid))
                return dict(WebCaseIdInCRM='synthetic-case-' + rid)

            def attach(self, case, report, photo):
                rid = report['reportId']
                parent.assertEqual('attaching', parent.journal.state(rid))
                parent.assertEqual('synthetic-case-' + rid, case['WebCaseIdInCRM'])
                parent.events.append(('attach', rid, photo['sha256']))
                return True

        self.api = Api()

    def test_two_reports_distinct_cases_zero_and_multiple_photos(self):
        run_api_batch(self.journal, self.manifest, self.api)
        self.assertEqual([('preflight',), ('create', 'synthetic-a'), ('create', 'synthetic-b'),
                          ('attach', 'synthetic-b', 'c' * 64), ('attach', 'synthetic-b', 'd' * 64)], self.events)
        for report in self.manifest['reports']:
            self.assertEqual('complete', self.journal.state(report['reportId']))
        before = list(self.events)
        with self.assertRaises(InvalidBatch):
            run_api_batch(self.journal, self.manifest, self.api)
        self.assertEqual(before + [('preflight',)], self.events)

    def test_readonly_preflight_failure_never_reserves_or_writes(self):
        def fail(reports):
            raise InvalidBatch('synthetic login refused')
        self.api.preflight = fail
        with self.assertRaises(InvalidBatch):
            run_api_batch(self.journal, self.manifest, self.api)
        self.assertEqual(0, self.journal.db.execute('SELECT count(*) FROM reports').fetchone()[0])
        self.assertEqual([], self.events)

    def test_missing_port_and_empty_or_oversize_batch_refuse(self):
        for manifest, api in [(self.manifest, None), (dict(reports=[]), self.api),
                              (dict(reports=[{}] * 51), self.api)]:
            with self.assertRaises(InvalidBatch):
                run_api_batch(self.journal, manifest, api)
        self.assertEqual([], self.events)

    def test_malformed_port_contract_refuses_before_preflight_or_reservation(self):
        variants = []
        duplicate = copy.deepcopy(self.manifest)
        duplicate['reports'][1]['reportId'] = duplicate['reports'][0]['reportId']
        variants.append(duplicate)
        for value in (None, {}, 'not-list'):
            invalid = copy.deepcopy(self.manifest)
            invalid['reports'][0]['photos'] = value
            variants.append(invalid)
        missing = copy.deepcopy(self.manifest)
        del missing['reports'][0]['photos']
        variants.append(missing)
        for photos in ([{}], [{'sha256': 'bad'}], [{'sha256': 'c' * 64}] * 2):
            invalid = copy.deepcopy(self.manifest)
            invalid['reports'][0]['photos'] = photos
            variants.append(invalid)
        variants.extend((None, {'reports': [None]}, {'reports': [{'reportId': ' ', 'photos': []}]}))
        for invalid in variants:
            with self.subTest(invalid=invalid), self.assertRaises(InvalidBatch):
                run_api_batch(self.journal, invalid, self.api)
        self.assertEqual([], self.events)
        self.assertEqual(0, self.journal.db.execute('SELECT count(*) FROM reports').fetchone()[0])

    def test_cross_report_photo_and_bad_text_identity_refuse_before_preflight(self):
        for field in ('shared-photo', 'textSha256'):
            bad = copy.deepcopy(self.manifest)
            if field == 'shared-photo':
                bad['reports'][0]['photos'] = [copy.deepcopy(bad['reports'][1]['photos'][0])]
            else:
                bad['reports'][1]['textSha256'] = None
            with self.assertRaises(InvalidBatch): run_api_batch(self.journal, bad, self.api)
            self.assertEqual([], self.events)
            self.assertEqual(0, self.journal.db.execute('SELECT count(*) FROM reports').fetchone()[0])

    def test_unknown_create_holds_and_never_moves_to_next_report(self):
        def fail(report):
            self.events.append(('unknown-create',))
            raise InvalidBatch('synthetic uncertain response')
        self.api.create = fail
        with self.assertRaises(InvalidBatch):
            run_api_batch(self.journal, self.manifest, self.api)
        self.assertEqual('creating', self.journal.state('synthetic-a'))
        self.assertEqual('reserved', self.journal.state('synthetic-b'))
        self.assertEqual([('preflight',), ('unknown-create',)], self.events)
        with self.assertRaises(InvalidBatch):
            run_api_batch(self.journal, self.manifest, self.api)
        self.assertEqual(1, self.events.count(('unknown-create',)))

    def test_second_photo_uncertain_stops_without_recreate(self):
        original = self.api.attach
        def attach(case, report, photo):
            if photo['sha256'] == 'd' * 64:
                raise InvalidBatch('synthetic attachment uncertain')
            return original(case, report, photo)
        self.api.attach = attach
        with self.assertRaises(InvalidBatch):
            run_api_batch(self.journal, self.manifest, self.api)
        self.assertEqual('attaching', self.journal.state('synthetic-b'))
        self.assertEqual(2, sum(e[0] == 'create' for e in self.events))
        with self.assertRaises(InvalidBatch):
            run_api_batch(self.journal, self.manifest, self.api)
        self.assertEqual(2, sum(e[0] == 'create' for e in self.events))

    def test_unconfirmed_attachment_result_stops_held(self):
        self.api.attach = lambda *args: False
        with self.assertRaises(InvalidBatch):
            run_api_batch(self.journal, self.manifest, self.api)
        self.assertEqual('attaching', self.journal.state('synthetic-b'))
        self.assertEqual(2, sum(e[0] == 'create' for e in self.events))

    def test_journal_ack_failure_before_each_write_prevents_that_write(self):
        for method, expected_creates, expected_attaches in [('reserve', 0, 0), ('begin_create', 0, 0),
                                                           ('begin_attachment', 2, 0)]:
            with self.subTest(method=method), tempfile.TemporaryDirectory() as directory:
                journal = BatchJournal(Path(directory) / 'journal.db')
                self.addCleanup(journal.close)
                self.events.clear()
                def fail(*args):
                    raise InvalidBatch('synthetic durable acknowledgement failure')
                setattr(journal, method, fail)
                old = self.journal
                self.journal = journal
                try:
                    with self.assertRaises(InvalidBatch):
                        run_api_batch(journal, self.manifest, self.api)
                    self.assertEqual(expected_creates, sum(e[0] == 'create' for e in self.events))
                    self.assertEqual(expected_attaches, sum(e[0] == 'attach' for e in self.events))
                finally:
                    self.journal = old

    def test_failed_receipt_persistence_preserves_prior_intent_on_reopen(self):
        for method, state in [('record_case', 'creating'), ('record_attachment', 'attaching')]:
            with self.subTest(method=method), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / 'journal.db'
                journal = BatchJournal(path)
                self.events.clear()
                def fail(*args):
                    raise InvalidBatch('synthetic receipt persistence failure')
                setattr(journal, method, fail)
                old = self.journal
                self.journal = journal
                try:
                    with self.assertRaises(InvalidBatch):
                        run_api_batch(journal, self.manifest, self.api)
                finally:
                    self.journal = old
                    journal.close()
                reopened = BatchJournal(path)
                try:
                    rid = 'synthetic-a' if method == 'record_case' else 'synthetic-b'
                    self.assertEqual(state, reopened.state(rid))
                    before = list(self.events)
                    with self.assertRaises(InvalidBatch):
                        run_api_batch(reopened, self.manifest, self.api)
                    self.assertEqual(before + [('preflight',)], self.events)
                finally:
                    reopened.close()


if __name__ == '__main__':
    unittest.main()
