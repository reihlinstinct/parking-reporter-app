import importlib.util
from pathlib import Path
import unittest
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

    def test_recovery_failure_fails_closed(self):
        def fail():
            raise RuntimeError('offline')
        self.assertEqual(1, m.gate(lambda: 'ANR', fail, lambda _: None))
