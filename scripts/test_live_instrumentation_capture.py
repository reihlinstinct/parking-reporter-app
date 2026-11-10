"""Synthetic adb only: never starts an emulator or touches staged/live inputs."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

MOCK = '''#!/usr/bin/env python3
import os,sys,time
from pathlib import Path
p=Path(os.environ['MOCK_STATE'])
a=sys.argv[1:]
if a[0]=='logcat':
    print('PRIVATE synthetic diagnostic', flush=True)
    if os.environ.get('MOCK_LOGCAT_FAIL'): sys.exit(1)
    time.sleep(20)
elif a[:2]==['shell','pidof']:
    f=p/'polls'
    n=int(f.read_text()) if f.exists() else 0
    f.write_text(str(n+1))
    value=['111','','222','222'][min(n,3)]
    if value: print(value)
    else: sys.exit(1)
elif a[:3]==['shell','am','instrument']:
    print('PRIVATE synthetic instrumentation')
    time.sleep(3.3)
    sys.exit(int(os.environ.get('MOCK_RC','0')))
elif a[:3]==['shell','dumpsys','activity']:
    print('PRIVATE synthetic crash exit-info')
elif a[:3]==['shell','dumpsys','meminfo']:
    print('PRIVATE synthetic memory')
else: sys.exit(88)
'''


class CaptureTest(unittest.TestCase):
    def capture(self, rc, logcat_fail=False):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            (base / 'adb').write_text(MOCK)
            (base / 'adb').chmod(0o700)
            env = dict(os.environ, PATH=str(base) + os.pathsep + os.environ['PATH'],
                       MOCK_STATE=tmp, MOCK_RC=str(rc))
            if logcat_fail:
                env['MOCK_LOGCAT_FAIL'] = '1'
            out = base / 'private'
            result = subprocess.run(['sh', str(ROOT / 'scripts/capture-live-instrumentation.sh'), str(out)],
                                    env=env, text=True, capture_output=True, timeout=12)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout + result.stderr, '')
            self.assertEqual((out / 'instrumentation.rc').read_text().strip(), str(rc))
            self.assertIn('PRIVATE', (out / 'logcat.txt').read_text())
            self.assertIn('PRIVATE', (out / 'process-exit-info.txt').read_text())
            self.assertIn('PRIVATE', (out / 'process-memory.txt').read_text())
            lifecycle = (out / 'process-lifecycle.txt').read_text()
            for marker in ['PROCESS_FIRST_SEEN pid=111', 'PROCESS_ABSENT_AFTER_SEEN previous=111',
                           'PROCESS_REAPPEARED_OR_CHANGED pid=222']:
                self.assertIn(marker, lifecycle)
            for path in out.iterdir():
                self.assertEqual(path.stat().st_mode & 0o077, 0, path)

    def test_success_and_process_disappearance(self):
        self.capture(0)

    def test_instrument_failure_preserves_rc_and_private_diagnostics(self):
        self.capture(7)

    def test_logcat_failure_does_not_stop_instrument_or_other_collection(self):
        self.capture(0, logcat_fail=True)

    def test_workflow_encrypts_all_diagnostics_without_public_upload(self):
        workflow = (ROOT / '.github/workflows/live-emulator-e2e.yml').read_text()
        self.assertIn('sh scripts/capture-live-instrumentation.sh /tmp/live-e2e', workflow)
        bundle = next(line for line in workflow.splitlines() if 'tar czf private-bundle.tgz' in line)
        shred = next(line for line in workflow.splitlines() if 'shred -u logcat.txt' in line)
        for name in ['logcat.txt', 'logcat.stderr', 'process-lifecycle.txt', 'process-lifecycle.stderr',
                     'process-exit-info.txt', 'process-memory.txt']:
            self.assertIn(name, bundle)
            self.assertIn(name, shred)
            self.assertNotIn('path: /tmp/live-e2e/' + name, workflow)
        self.assertIn('path: /tmp/live-e2e/live-e2e-private-bundle.tgz.enc', workflow)
        self.assertIn('retention-days: 14', workflow)


if __name__ == '__main__':
    unittest.main()
