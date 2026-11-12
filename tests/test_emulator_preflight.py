import importlib.util
from pathlib import Path
import unittest
import tempfile
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('preflight', Path(__file__).resolve().parents[1] / 'scripts/emulator_preflight.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class EmulatorPreflightTest(unittest.TestCase):
    def test_health_classifier(self):
        window = 'mCurrentFocus=Window{123 u0 com.android.launcher3/Main}'
        self.assertIsNone(m.unhealthy('1', window, 'notResponding=false crashing=false'))
        self.assertEqual('boot incomplete', m.unhealthy('0', window, ''))
        self.assertEqual('active process ANR/crash', m.unhealthy('1', window, 'notResponding=true'))
        self.assertEqual('active process ANR/crash', m.unhealthy('1', window, 'crashing=true'))
        self.assertEqual('system error dialog has focus', m.unhealthy('1', 'mCurrentFocus=Window{Application Not Responding}', ''))
        self.assertEqual('no focused window', m.unhealthy('1', 'mCurrentFocus=null', ''))

    def test_probe_requests_global_focus_not_windows_only(self):
        # API36 `dumpsys window windows` has Window details but no mCurrentFocus.
        # Full `dumpsys window` includes global state. Never infer health from
        # the presence of an arbitrary Window or waive the focus requirement.
        def fake_adb(*args):
            if args == ('shell', 'getprop', 'sys.boot_completed'):
                return '1'
            if args == ('shell', 'dumpsys', 'window'):
                return 'mCurrentFocus=Window{123 u0 com.android.launcher3/Main}'
            if args == ('shell', 'dumpsys', 'activity', 'processes'):
                return 'notResponding=false crashing=false'
            self.fail('unexpected adb command: ' + repr(args))
        with patch.object(m, 'adb', side_effect=fake_adb):
            self.assertIsNone(m.probe())
        self.assertEqual('no focused window', m.unhealthy(
            '1', 'WINDOW MANAGER WINDOWS\n Window #0 Window{123 u0 Main}', ''))

    def run_gate(self, states):
        iterator = iter(states)
        recovery = []
        result = m.gate(lambda: next(iterator), lambda: recovery.append(True), lambda _: None)
        return result, len(recovery)

    def test_healthy_needs_two_samples_no_recovery(self):
        self.assertEqual((0, 0), self.run_gate([None, None]))

    def test_unhealthy_once_recovers(self):
        self.assertEqual((0, 1), self.run_gate(['ANR', None, None]))

    def test_second_sample_unhealthy_triggers_only_one_recovery(self):
        self.assertEqual((1, 1), self.run_gate([None, 'ANR', None, 'ANR']))

    def test_persistent_failure_never_retries_tests(self):
        self.assertEqual((1, 1), self.run_gate(['ANR', 'ANR']))

    def test_recovery_marker_is_retained_only_when_recovery_requested(self):
        for should_recover in (False, True):
            with tempfile.TemporaryDirectory() as directory:
                def fake_gate(**kwargs):
                    if should_recover:
                        kwargs['reboot']()
                    return 0
                with patch.object(m.sys, 'argv', ['preflight', '--report-dir', directory]), \
                     patch.object(m, 'gate', side_effect=fake_gate), patch.object(m, 'recover'):
                    self.assertEqual(0, m.main())
                self.assertEqual(should_recover, (Path(directory) / 'preflight-recovered.txt').exists())
                self.assertEqual('0\n', (Path(directory) / 'preflight.rc').read_text())

    def test_recovery_failure_fails_closed(self):
        def fail():
            raise RuntimeError('offline')
        self.assertEqual(1, m.gate(lambda: 'ANR', fail, lambda _: None))


class PreflightSummaryTest(unittest.TestCase):
    def test_recovery_attempt_is_visible_for_both_final_results(self):
        from tempfile import TemporaryDirectory
        from pathlib import Path
        for code, outcome in [(0, 'PASS'), (1, 'FAIL')]:
            with self.subTest(code=code), TemporaryDirectory() as temp:
                directory = Path(temp)
                (directory / 'preflight-recovered.txt').write_text('attempted')
                text = m.summary_markdown(directory, code)
                self.assertIn('One bounded emulator reboot was requested', text)
                self.assertIn('not a clean first boot', text)
                self.assertIn('**' + outcome + '**', text)
                self.assertIn('not an app-test result', text)

    def test_no_recovery_is_reported_without_hiding_failure(self):
        from tempfile import TemporaryDirectory
        from pathlib import Path
        with TemporaryDirectory() as temp:
            for code, outcome in [(0, 'PASS'), (1, 'FAIL')]:
                text = m.summary_markdown(Path(temp), code)
                self.assertIn('No bounded recovery reboot was requested.', text)
                self.assertIn('**' + outcome + '**', text)

    def test_main_writes_failure_and_recovery_to_job_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            target = directory / 'job.md'
            def failed_gate(reboot):
                # Match the durable meaning of a failed recovery attempt.
                (directory / 'preflight-recovered.txt').write_text('requested')
                return 1
            with patch('sys.argv', ['preflight', '--report-dir', temp]), patch.object(m, 'gate', side_effect=failed_gate), patch.object(m, 'adb', return_value='diagnostics'), patch.dict('os.environ', {'GITHUB_STEP_SUMMARY': str(target)}):
                self.assertEqual(1, m.main())
            self.assertIn('**FAIL**', target.read_text())
            self.assertIn('not a clean first boot', target.read_text())
            self.assertEqual('1\n', (directory / 'preflight.rc').read_text())
