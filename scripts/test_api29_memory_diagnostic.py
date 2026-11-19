"""Synthetic adb replay for the diagnostic runner's result gate and no-retry rule."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
MOCK = '''#!/usr/bin/env python3
import os,sys,json
from pathlib import Path
args=sys.argv[1:]
with open(os.environ['CALLS'],'a') as f:f.write(json.dumps(args)+'\\n')
if args[:3]==['shell','am','instrument']:
 if os.environ.get('ZERO')!='1':
  print('INSTRUMENTATION_STATUS: class=synthetic.DiagnosticTest')
  print('INSTRUMENTATION_STATUS: test=syntheticCase')
  print('INSTRUMENTATION_STATUS_CODE: '+('-2' if os.environ.get('FAIL')=='1' else '0'))
 sys.exit(int(os.environ.get('RC','0')))
elif args and args[0]=='logcat':
 if '-d' in args and os.environ.get('NO_TRACE')!='1':print('API29_MEMORY synthetic metadata')
elif args and args[0]=='exec-out':
 if os.environ.get('NO_HEAP')=='1':sys.exit(1)
 if os.environ.get('BAD_HEAP')=='1':print('cat: no such file')
 else:sys.stdout.buffer.write(b'JAVA PROFILE 1.0.3\\0synthetic')
elif args[:2]==['shell','getprop']:print('16m')
'''


class DiagnosticRunnerTest(unittest.TestCase):
    def run_case(self, mode='suite', **values):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'scripts').mkdir()
            shutil.copy(ROOT / 'scripts/test_summary.py', root / 'scripts/test_summary.py')
            (root / 'adb').write_text(MOCK)
            (root / 'adb').chmod(0o700)
            calls = root / 'calls'
            result = subprocess.run(['bash', str(ROOT / 'scripts/api29_memory_diagnostic.sh'), mode],
                                    cwd=root, env=dict(os.environ, PATH=tmp+os.pathsep+os.environ['PATH'],
                                                      CALLS=str(calls), **values), capture_output=True, text=True, timeout=15)
            import json
            invoked = [json.loads(line) for line in calls.read_text().splitlines()] if calls.exists() else []
            return result, invoked

    def test_pass(self):
        result, calls = self.run_case()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sum(c[:3] == ['shell', 'am', 'instrument'] for c in calls), 1)

    def test_failure_zero_tests_and_transport_error_fail_without_retry(self):
        for values in [dict(FAIL='1'), dict(ZERO='1'), dict(RC='7'), dict(NO_TRACE='1')]:
            with self.subTest(values=values):
                result, calls = self.run_case(**values)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(sum(c[:3] == ['shell', 'am', 'instrument'] for c in calls), 1)

    def test_isolated_classes_have_fresh_storage_and_no_retries(self):
        result, calls = self.run_case('isolated')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sum(c[:3] == ['shell', 'pm', 'clear'] for c in calls), 4)
        self.assertEqual(sum(c[:3] == ['shell', 'am', 'instrument'] for c in calls), 4)

    def test_paired_keeps_full_suite_and_four_isolated_classes(self):
        result, calls = self.run_case('paired')
        self.assertEqual(result.returncode, 0, result.stderr)
        runs = [c for c in calls if c[:3] == ['shell', 'am', 'instrument']]
        self.assertEqual(len(runs), 5)
        self.assertNotIn('class', runs[0])
        self.assertEqual(sum(c[:3] == ['shell', 'pm', 'clear'] for c in calls), 4)

    def test_fresh_suite_has_one_reset_and_one_full_run(self):
        result, calls = self.run_case('fresh-suite')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sum(c[:3] == ['shell', 'pm', 'clear'] for c in calls), 1)
        runs = [c for c in calls if c[:3] == ['shell', 'am', 'instrument']]
        self.assertEqual(len(runs), 1)
        self.assertNotIn('class', runs[0])

    def test_snapshot_magic_and_exact_ordinals(self):
        result, calls = self.run_case('snapshots')
        self.assertEqual(result.returncode, 0, result.stderr)
        pulls = [c[-1] for c in calls if c and c[0] == 'exec-out']
        self.assertEqual(pulls, ['files/memory-diagnostic/before-24.hprof',
                                 'files/memory-diagnostic/before-27.hprof'])
        result, _ = self.run_case('snapshots', BAD_HEAP='1')
        self.assertNotEqual(result.returncode, 0)

    def test_missing_snapshot_fails(self):
        result, _ = self.run_case('snapshots', NO_HEAP='1')
        self.assertNotEqual(result.returncode, 0)

    def test_invalid_mode_has_no_adb_effect(self):
        result, calls = self.run_case('unknown')
        self.assertEqual(result.returncode, 2)
        self.assertEqual(calls, [])


if __name__ == '__main__':
    unittest.main()
