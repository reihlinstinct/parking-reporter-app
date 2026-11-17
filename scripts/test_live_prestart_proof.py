"""Run actual workflow encryption/shred then guard, with synthetic files only."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = (ROOT / '.github/workflows/live-emulator-e2e.yml').read_text()


def block(name):
    section = WORKFLOW.split('      - name: ' + name, 1)[1]
    body = section.split('        run: |\n', 1)[1].split('      - name:', 1)[0]
    return '\n'.join(line[10:] for line in body.splitlines())


class ProofTest(unittest.TestCase):
    def run_case(self, change=None, expected=False):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp)
            for name, value in {'staged.ok': '', 'instrumentation.rc': '7\n',
                                'instrumentation.txt': 'synthetic failed before test start\n',
                                'submissions.proven-empty': '', 'case.proven-absent': ''}.items():
                (p / name).write_text(value)
            if change:
                change(p)
            env = dict(os.environ, GITHUB_WORKSPACE=str(ROOT), TRIAL_DATA_KEY='synthetic-test-key-only',
                       APPROVED_SHA256_INPUT='a' * 64, APPROVED_PHOTO_SHA256_INPUT='b' * 64,
                       GITHUB_RUN_ID='synthetic', GITHUB_RUN_ATTEMPT='1')
            encrypt = block('Encrypt the recording and raw output').replace('cd /tmp/live-e2e', 'cd "$TEST_DIR"')
            guard = block("Record this run's digest (encrypted, for the duplicate guard)").replace('cd /tmp/live-e2e', 'cd "$TEST_DIR"')
            env['TEST_DIR'] = tmp
            for script in [encrypt, guard]:
                subprocess.run(['bash', '-c', script], env=env, check=True, capture_output=True)
            self.assertTrue((p / 'live-e2e-private-bundle.tgz.enc').stat().st_size)
            self.assertFalse((p / 'instrumentation.txt').exists())
            self.assertEqual((p / 'failed-before-start.proven').exists(), expected)
            self.assertEqual((p / 'live-e2e-run-record.json.enc').exists(), not expected)

    def test_prestart_proof_survives_real_encrypt_and_shred(self):
        self.run_case(expected=True)

    def test_uncertain_or_started_always_burns(self):
        for name in ['submissions.proven-empty', 'case.proven-absent', 'instrumentation.txt', 'instrumentation.rc']:
            with self.subTest(missing=name):
                self.run_case(lambda p: (p / name).unlink())
        for name, value in [('instrumentation.txt', ''), ('instrumentation.rc', '0'),
                            ('instrumentation.rc', 'garbage'), ('instrumentation.txt', 'INSTRUMENTATION_STATUS_CODE: 1'),
                            ('log.collected', ''), ('live-e2e-case-number.txt', 'synthetic case'),
                            ('live-e2e-submission-state.json', '{"status":"creating"}'),
                            ('live-e2e-response-log.jsonl', 'synthetic response')]:
            with self.subTest(observation=name, value=value):
                self.run_case(lambda p: (p / name).write_text(value))

    def test_workflow_probes_are_positive_before_cleanup(self):
        self.assertIn('entries=\\$(ls -A no_backup/submissions) && test -z', WORKFLOW)
        self.assertIn('test -d files && test -r files && test ! -e files/live-e2e-case-number.txt', WORKFLOW)
        self.assertLess(WORKFLOW.index('touch /tmp/live-e2e/case.proven-absent'),
                        WORKFLOW.index('rm -f files/live-params.json'))
        bundle = next(line for line in WORKFLOW.splitlines() if 'tar czf private-bundle.tgz' in line)
        self.assertNotIn('log.collected', bundle)
        self.assertLess(WORKFLOW.index('snapshot-live-prestart-proof.sh'),
                        WORKFLOW.index('instrumentation output unavailable;'))


if __name__ == '__main__':
    unittest.main()
