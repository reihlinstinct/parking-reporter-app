"""Execute the actual workflow reporter offline, with no checkout git remote."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[1]


class WeeklyReporterTest(unittest.TestCase):
    def run_reporter(self, count='0', lookup_failure=False):
        source = (ROOT / '.github/workflows/emulator-weekly.yml').read_text()
        reporter = source.split('  report-failure:', 1)[1]
        self.assertIn('GH_REPO: ${{ github.repository }}', reporter)
        script = textwrap.dedent(reporter.split('        run: |\n', 1)[1])
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / 'calls.jsonl'
            gh = Path(tmp) / 'gh'
            gh.write_text('''#!/usr/bin/env python3
import json, os, sys
with open(os.environ['MOCK_LOG'], 'a') as f:
    f.write(json.dumps({'args': sys.argv[1:], 'repo': os.environ.get('GH_REPO')}) + '\\n')
if not os.environ.get('GH_REPO'): sys.exit(9)
if sys.argv[1:3] == ['issue', 'list']:
    if os.environ['MOCK_FAIL'] == '1': sys.exit(7)
    print(os.environ['MOCK_COUNT'])
''')
            gh.chmod(0o755)
            env = dict(os.environ, PATH=tmp + os.pathsep + os.environ['PATH'],
                       GH_REPO='test-owner/test-repo', RUN_SHA='synthetic-sha',
                       RUN_URL='https://example.invalid/run/1', MOCK_LOG=str(log),
                       MOCK_COUNT=count, MOCK_FAIL=str(int(lookup_failure)))
            result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', script],
                                    cwd=tmp, env=env, capture_output=True, text=True)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            return result, calls

    def test_creates_with_explicit_repository_and_current_run(self):
        result, calls = self.run_reporter()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c['args'][:2] for c in calls], [['issue', 'list'], ['issue', 'create']])
        self.assertTrue(all(c['repo'] == 'test-owner/test-repo' for c in calls))
        args = calls[1]['args']
        body = args[args.index('--body') + 1]
        self.assertIn('synthetic-sha', body)
        self.assertIn('https://example.invalid/run/1', body)
        self.assertNotIn('(26,', body)

    def test_existing_issue_is_not_duplicated(self):
        result, calls = self.run_reporter(count='1')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(calls), 1)

    def test_lookup_failure_stops_without_creating(self):
        result, calls = self.run_reporter(lookup_failure=True)
        self.assertEqual(result.returncode, 7)
        self.assertEqual(len(calls), 1)

    def test_design_actions_evidence_is_required(self):
        source = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertIn('test -s app/build/reports/androidTests/screenshots/design-sign-actions-synthetic.png', source)


if __name__ == '__main__':
    unittest.main()
