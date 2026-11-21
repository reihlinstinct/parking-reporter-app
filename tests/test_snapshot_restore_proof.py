import importlib.util
import io
from pathlib import Path
import signal
import subprocess
import sys
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
spec = importlib.util.spec_from_file_location('restoreproof', ROOT / 'scripts/ci/verify_snapshot_restore.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class ProofTest(unittest.TestCase):
    def guest(self, unhealthy=False, missing=False):
        def run(args, **kwargs):
            self.assertEqual(args[:3], ['adb', '-s', 'emulator-5554'])
            if 'sys.boot_completed' in args:
                out = '1\n'
            elif 'path' in args:
                out = '' if missing else 'package:/synthetic/app.apk\n'
            elif 'window' in args:
                out = 'mCurrentFocus=Window{stable}'
            else:
                out = 'notResponding=true' if unhealthy else ''
            return subprocess.CompletedProcess(args, 0, out, '')
        return run

    def exercise(self, log, run=None, exited=False):
        child = Mock(pid=123)
        child.poll.return_value = 1 if exited else None
        def launch(args, **kwargs):
            self.assertIn('-no-snapshot-save', args)
            self.assertNotIn('-no-snapshot-load', args)
            self.assertEqual(args[args.index('-snapshot') + 1], 'default_boot')
            self.assertEqual(args[args.index('-avd') + 1], 'test')
            self.assertTrue(kwargs['start_new_session'])
            kwargs['stdout'].write(log)
            kwargs['stdout'].flush()
            return child
        ticks = [0]
        def pause(seconds):
            ticks[0] += seconds
        kill = Mock()
        output = io.StringIO()
        try:
            with redirect_stdout(output):
                m.prove(popen=launch, run=run or self.guest(), clock=lambda: ticks[0],
                        pause=pause, killpg=kill)
        finally:
            kill.assert_called_once_with(child.pid, signal.SIGKILL)
            child.wait.assert_called_once_with()
            self.assertNotIn('PRIVATE', output.getvalue())
        return output.getvalue()

    def test_success_requires_actual_load_and_preinstalled_packages(self):
        self.assertEqual('SNAPSHOT-WARM-RESTORE-VERIFIED\n', self.exercise(
            b"Successfully loaded snapshot 'default_boot'"))

    def test_failure_fallback_exit_and_missing_load_never_pass(self):
        for log, exited in ((b"Failed to load snapshot", False),
                            (b'Performing a cold boot', False),
                            (b'boot completed PRIVATE', False), (b'', True),
                            (b"Successfully loaded snapshot 'other'", False)):
            with self.subTest(log=log), self.assertRaises(m.RestoreRejected):
                self.exercise(log, exited=exited)

    def test_unhealthy_or_missing_apks_does_not_reboot_or_pass(self):
        for run in (self.guest(unhealthy=True), self.guest(missing=True),
                    Mock(side_effect=subprocess.TimeoutExpired('adb', 15))):
            with self.assertRaises(m.RestoreRejected):
                self.exercise(b"Successfully loaded snapshot 'default_boot'", run=run)

    def test_log_bound_and_cleanup_on_error(self):
        with self.assertRaises(m.RestoreRejected):
            self.exercise(b'x' * (8 * 1024 * 1024 + 1))
        with patch.object(m, 'prove', side_effect=OSError('PRIVATE')):
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(3, m.main())
            self.assertEqual('SNAPSHOT-WARM-RESTORE-REJECTED\n', output.getvalue())

    def test_late_deadline_failure_exit_and_log_growth_cannot_certify_success(self):
        for mode in ('deadline', 'fallback', 'failure', 'exit', 'oversize'):
            with self.subTest(mode=mode):
                ticks = [0]
                child = Mock(pid=123)
                child.poll.return_value = None
                logs = []
                def launch(args, **kwargs):
                    logs.append(kwargs['stdout'])
                    logs[0].write(b"Successfully loaded snapshot 'default_boot'")
                    logs[0].flush()
                    return child
                def probes(*args):
                    if mode == 'deadline':
                        ticks[0] = 240
                    elif mode == 'exit':
                        child.poll.return_value = 1
                    else:
                        value = {'fallback': b'cold boot', 'failure': b'Failed to load snapshot',
                                 'oversize': b'x' * (8 * 1024 * 1024 + 1)}[mode]
                        logs[0].write(value)
                        logs[0].flush()
                kill = Mock()
                output = io.StringIO()
                with patch.object(m, 'check_guest', side_effect=probes), redirect_stdout(output):
                    with self.assertRaises(m.RestoreRejected):
                        m.prove(popen=launch, clock=lambda: ticks[0], killpg=kill)
                self.assertNotIn('VERIFIED', output.getvalue())
                kill.assert_called_once_with(child.pid, signal.SIGKILL)
                child.wait.assert_called_once_with()

    def test_each_adb_probe_uses_remaining_global_budget(self):
        ticks = [170.0]
        def run(args, **kwargs):
            self.assertEqual(min(15, 180 - ticks[0]), kwargs['timeout'])
            ticks[0] += 3
            return self.guest()(args, **kwargs)
        with self.assertRaises(m.RestoreRejected):
            m.check_guest(run, deadline=180, clock=lambda: ticks[0])
        # Four probes consume the remaining10s; the fifth must not be launched.
        self.assertEqual(182, ticks[0])

    def test_workflow_is_single_manual_main_only_without_build_save_or_private_inputs(self):
        import yaml
        workflow = yaml.safe_load((ROOT / '.github/workflows/emulator-snapshot-restore-proof.yml').read_text())
        self.assertEqual({'workflow_dispatch': None}, workflow.get('on', workflow.get(True)))
        self.assertEqual({'contents': 'read'}, workflow['permissions'])
        self.assertEqual(['restore'], list(workflow['jobs']))
        job = workflow['jobs']['restore']
        self.assertEqual("github.ref == 'refs/heads/main'", job['if'])
        self.assertLessEqual(job['timeout-minutes'], 10)
        steps = job['steps']
        self.assertTrue(any(s.get('uses') == './.github/actions/emulator-snapshot-restore' for s in steps))
        text = str(steps)
        for forbidden in ('secrets.', 'environment:', 'gradlew', 'cache/save', 'extra-allowed-ref', 'adb install'):
            self.assertNotIn(forbidden, text)
        require = next(i for i, s in enumerate(steps) if s.get('run') == 'test "$HIT" = true')
        boot = next(i for i, s in enumerate(steps) if s.get('run') == 'python3 scripts/ci/verify_snapshot_restore.py')
        self.assertLess(require, boot)
        self.assertEqual('always()', steps[-1]['if'])
