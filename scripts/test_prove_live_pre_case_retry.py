#!/usr/bin/env python3
"""Synthetic fail-closed recovery checks; no municipal calls or real inputs."""
import io
import tarfile
import unittest
from unittest.mock import patch
import prove_live_pre_case_retry as proof


def archive(files):
    out = io.BytesIO()
    with tarfile.open(fileobj=out, mode='w:gz') as tar:
        for name, data in files.items():
            member = tarfile.TarInfo(name)
            member.size = len(data)
            tar.addfile(member, io.BytesIO(data))
    return out.getvalue()


class PreCaseProofTest(unittest.TestCase):
    def setUp(self):
        self.record = dict(runId='123', runAttempt='1', approvedSha256='a' * 64,
                           approvedPhotoSha256='b' * 64, outcome='aborted_after_send_tap')
        self.log = ''.join('synthetic LIVE_E2E ' + m + '\n' for m in
                           ['START', 'DIGEST_OK', 'SEND_TAPPED tap=1', 'SUBMIT_FAILED',
                            'SUBMIT_FAILED_PRE_CASE', 'FAIL class=AssertionError']).encode()
        self.files = {'submissions-count.txt': b'0\n', 'instrumentation.rc': b'0\n',
                      'files-app.txt': b'live-e2e-submit-failed.png\nprofileInstalled\n',
                      'instrumentation.txt': ('LiveEmulatorSubmissionTest\n' + proof.ASSERTION +
                                              '\nTests run: 1,  Failures: 1\nINSTRUMENTATION_CODE: -1\n').encode()}

    def run_proof(self, record=None, log=None, files=None):
        return proof.prove(self.record if record is None else record, '123', 'a' * 64, 'b' * 64,
                           self.log if log is None else log, archive(self.files if files is None else files))

    def test_exact_pre_case_proof_allows_retry(self):
        self.assertTrue(self.run_proof())

    def test_identity_and_unknown_or_submitted_outcomes_refuse(self):
        for key, value in [('runId', '124'), ('approvedSha256', 'c' * 64),
                           ('approvedPhotoSha256', 'c' * 64), ('outcome', 'submitted'),
                           ('outcome', 'outcome_unknown'), ('outcome', 'aborted_unknown')]:
            with self.subTest(key=key, value=value):
                record = dict(self.record, **{key: value})
                with self.assertRaises(ValueError):
                    self.run_proof(record=record)

    def test_missing_or_contradictory_markers_refuse(self):
        logs = [b'', self.log.replace(b'SUBMIT_FAILED_PRE_CASE', b'OTHER'),
                self.log + b'synthetic LIVE_E2E OUTCOME_UNKNOWN\n',
                self.log + b'synthetic LIVE_E2E SUBMITTED case=synthetic\n',
                self.log + b'synthetic LIVE_E2E SEND_TAPPED tap=2\n',
                self.log + b'synthetic LIVE_E2E SUBMIT_FAILED_PRE_CASE\n']
        for log in logs:
            with self.subTest(log=log):
                with self.assertRaises(ValueError):
                    self.run_proof(log=log)

    def test_any_state_response_or_submission_refuses(self):
        variants = []
        for name in ['live-e2e-submission-state.json', 'live-e2e-response-log.jsonl']:
            variants.append(dict(self.files, **{name: b'{}'}))
        variants.append(dict(self.files, **{'submissions-count.txt': b'1'}))
        variants.append(dict(self.files, **{'files-app.txt': b'submissions/synthetic.json'}))
        for files in variants:
            with self.assertRaises(ValueError):
                self.run_proof(files=files)

    def test_missing_malformed_or_oversize_probes_refuse(self):
        for name in self.files:
            files = dict(self.files)
            del files[name]
            with self.assertRaises(ValueError):
                self.run_proof(files=files)
        for name in ['submissions-count.txt', 'instrumentation.txt', 'instrumentation.rc']:
            files = dict(self.files, **{name: b'unavailable'})
            with self.assertRaises(ValueError):
                self.run_proof(files=files)
        with patch.object(proof, 'MAX_TEXT', 20):
            with self.assertRaises(ValueError):
                self.run_proof()

    def test_unsafe_archive_paths_and_corruption_refuse(self):
        for name in ['../synthetic', '/synthetic', 'subdir/synthetic', 'subdir\\synthetic']:
            with self.assertRaises(ValueError):
                self.run_proof(files=dict(self.files, **{name: b'x'}))
        with self.assertRaises(Exception):
            proof.prove(self.record, '123', 'a' * 64, 'b' * 64, self.log, b'not gzip')

    def test_workflow_guard_is_valid_bash(self):
        import subprocess
        from pathlib import Path
        source = (Path(__file__).resolve().parents[1] / '.github/workflows/live-emulator-e2e.yml').read_text()
        guard = source.split('          set -euo pipefail\n', 1)[1].split('      - name: Build app', 1)[0]
        script = '\n'.join(line[10:] for line in guard.splitlines())
        subprocess.run(['bash', '-n'], input=script, text=True, check=True)

    def test_workflow_uses_read_only_proof_without_erasing_prior_artifacts(self):
        from pathlib import Path
        source = (Path(__file__).resolve().parents[1] / '.github/workflows/live-emulator-e2e.yml').read_text()
        self.assertIn('python3 scripts/prove_live_pre_case_retry.py --record', source)
        self.assertNotIn('DELETE', source)
        self.assertIn('prior_photo" = "$APPROVED_PHOTO_SHA256_INPUT', source)


if __name__ == '__main__':
    unittest.main()
