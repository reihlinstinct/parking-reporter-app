"""Synthetic-only immutable history reconciliation coverage."""
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from audit_legacy_history import Snapshot, audit, main, sha, InvalidBatch


class HistoryAuditTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        self.git('init', '-q')
        self.git('config', 'user.email', 'synthetic@example.invalid')
        self.git('config', 'user.name', 'Synthetic Test')
        self.write('reports/alpha/report.json', {'id': 'alpha', 'approved_description': 'exact text',
                   'photo': 'original.jpg', 'photo_sha256': sha(b'image'), 'submit_digest': sha(b'payload')})
        (self.repo / 'reports/alpha/original.jpg').write_bytes(b'image')
        self.write('requests/beta/request.json', {'id': 'beta', 'description': 'exact text', 'photo': '../elsewhere.jpg', 'parent': {'id': 'missing'}})
        self.write('app-reservations/reserved.json', {'report_id': 'reserved'})
        self.write('ledger.json', {'version': 1, 'reports': {
            sha(b'alpha'): {'photo_sha256': sha(b'image'), 'digest': sha(b'payload'), 'state': {'status': 'complete'}},
            'missing': {'photo_sha256': sha(b'image'), 'digest': sha(b'exact text'), 'state': {'status': 'uncertain'}}},
            'photos': {sha(b'image'): sha(b'alpha'), sha(b'orphan'): sha(b'missing')},
            'photo_vehicle': {'sensitive': 'NEVER-EXPORT-GUARD'}, 'shared_photos': {}})
        self.write('.106-state/' + sha(b'alpha') + '.json', {'status': 'complete'})
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic')
        self.commit = self.git('rev-parse', 'HEAD').strip()

    def tearDown(self): self.tmp.cleanup()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], text=True)

    def write(self, path, data):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data))

    def run_audit(self):
        return audit(Snapshot(self.repo, self.commit), Snapshot(self.repo, self.commit))

    def test_namespace_separation_and_ambiguous_photo_not_identity(self):
        result = self.run_audit()
        self.assertEqual(result['counts']['archiveRecords'], 3)
        self.assertEqual(result['counts']['ledgerReportsWithoutReportMatch'], 1)
        missing = next(r for r in result['ledgerReports'] if not r['matchedByReportIdentity'])
        self.assertEqual(missing['links']['payload'], [])
        self.assertEqual(missing['links']['photo'], ['reports/alpha/report.json'])
        self.assertEqual(len(result['textAliasGroups']), 1)
        self.assertFalse(result['seedAllowed'])
        self.assertFalse(result['activationAllowed'])

    def test_fingerprints_and_reference_bytes_no_guard_export(self):
        result = self.run_audit()
        self.assertNotIn('NEVER-EXPORT-GUARD', json.dumps(result))
        self.assertEqual(result['counts']['unresolvedPhotoReferences'], 1)
        self.assertEqual(result['counts']['photoIndexWithoutCandidateMatch'], 1)
        self.assertEqual(result['counts']['checkpoints'], 1)
        record = next(r for r in result['records'] if r['source'].startswith('reports/'))
        self.assertEqual(record['identities']['byteReferences'][0]['sha256'], sha(b'image'))

    def test_commit_pinned_despite_worktree_changes(self):
        first = self.run_audit()
        self.write('reports/alpha/report.json', {'id': 'mutated'})
        self.assertEqual(self.run_audit(), first)
        with self.assertRaises(InvalidBatch): Snapshot(self.repo, 'HEAD')
        with self.assertRaises(InvalidBatch): Snapshot(self.repo, 'f' * 40)

    def test_symlink_and_missing_photo_not_followed(self):
        (self.repo / 'reports/alpha/original.jpg').unlink()
        (self.repo / 'reports/alpha/original.jpg').symlink_to('/etc/passwd')
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic-symlink')
        self.commit = self.git('rev-parse', 'HEAD').strip()
        result = self.run_audit()
        self.assertEqual(result['counts']['unresolvedPhotoReferences'], 2)
        self.assertFalse(any(r['identities']['byteReferences'] for r in result['records']))

    def test_bad_record_visible_bad_ledger_fails_closed(self):
        (self.repo / 'reports/alpha/report.json').write_text('{"id":"a","id":"b"}')
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic-duplicate')
        self.commit = self.git('rev-parse', 'HEAD').strip()
        self.assertEqual(self.run_audit()['counts']['unreadableSources'], 1)
        self.write('ledger.json', {'version': 2, 'reports': {}, 'photos': {}})
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic-ledger')
        self.commit = self.git('rev-parse', 'HEAD').strip()
        with self.assertRaises(InvalidBatch): self.run_audit()

    def test_second_snapshot_retains_ledger_branch_records(self):
        self.write('reports/missing/report.json', {'id': 'missing', 'photo_sha256': sha(b'orphan')})
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic-ledger-only')
        other = self.git('rev-parse', 'HEAD').strip()
        result = audit(Snapshot(self.repo, self.commit), Snapshot(self.repo, other))
        self.assertEqual(result['counts']['ledgerBranchRecords'], 4)
        self.assertEqual(result['counts']['ledgerReportsWithoutReportMatch'], 0)
        self.assertEqual(result['counts']['photoIndexWithoutCandidateMatch'], 0)
        self.assertTrue(any(r['source'] == 'ledger:reports/missing/report.json' for r in result['records']))

    def test_ancillary_payload_is_not_silently_interpreted_as_text(self):
        self.write('reports/alpha/municipal-payload.json', {'description': 'ancillary-not-approved'})
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic-ancillary')
        self.commit = self.git('rev-parse', 'HEAD').strip()
        result = self.run_audit()
        self.assertTrue(any(r['source'].endswith('municipal-payload.json') for r in result['uninterpretedAncillaryJson']))
        self.assertFalse(any(c['sha256'] == sha(b'ancillary-not-approved')
                             for r in result['records'] for c in r['identities']['text']))
        self.assertFalse(result['writerCoverageVerified'])

    def test_git_object_size_limit_and_invalid_photo_owner(self):
        snapshot = Snapshot(self.repo, self.commit)
        with self.assertRaises(InvalidBatch): snapshot.read('reports/alpha/original.jpg', 1)
        with self.assertRaises(InvalidBatch): snapshot.read('absent.json')
        ledger = json.loads((self.repo / 'ledger.json').read_text())
        ledger['photos'][sha(b'image')] = None
        self.write('ledger.json', ledger)
        self.git('add', '.')
        self.git('commit', '-qm', 'synthetic-owner')
        self.commit = self.git('rev-parse', 'HEAD').strip()
        with self.assertRaises(InvalidBatch): self.run_audit()

    def test_private_exclusive_cli_output(self):
        private = self.root / 'private'
        private.mkdir(mode=0o700)
        output = private / 'audit.json'
        argv = ['audit', '--repo', str(self.repo), '--archive-commit', self.commit,
                '--ledger-commit', self.commit, '--output', str(output)]
        with patch.object(sys, 'argv', argv), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(main(), 0)
            original = output.read_bytes()
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            self.assertEqual(main(), 1)
            self.assertEqual(output.read_bytes(), original)


if __name__ == '__main__': unittest.main()
