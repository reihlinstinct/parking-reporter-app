import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
spec = importlib.util.spec_from_file_location('proof', Path(__file__).resolve().parents[1] / 'scripts/api29_recovery_proof.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class RecoveryProofTest(unittest.TestCase):
    def test_eight_healthy_boots_are_not_recovery_evidence(self):
        boots, restarts, labels = [], [], []
        def read(label):
            labels.append(label)
            return dict(pid=100 + len(labels), javaMaxBytes=m.EXPECTED)
        result = m.investigate(read, lambda: '512m', lambda: restarts.append(True), lambda: boots.append(True))
        self.assertEqual('not-reproduced', result['status'])
        self.assertEqual(8, len(labels))
        self.assertEqual(7, len(boots))
        self.assertEqual([], restarts)

    def test_observed_bad_boot_invokes_same_gate_then_stops(self):
        rows = iter([dict(pid=1, javaMaxBytes=m.EXPECTED), dict(pid=2, javaMaxBytes=m.BAD_BOOT),
                     dict(pid=3, javaMaxBytes=m.BAD_BOOT), dict(pid=4, javaMaxBytes=m.EXPECTED)])
        boots, restarts = [], []
        result = m.investigate(lambda _: next(rows), lambda: '512m', lambda: restarts.append(True), lambda: boots.append(True))
        self.assertEqual('recovered', result['status'])
        self.assertEqual(1, len(boots))
        self.assertEqual(1, len(restarts))
        self.assertTrue(result['control']['recoveryAttempted'])
        self.assertFalse(result['control']['heapPropertiesChanged'])

    def test_persistent_bad_boot_cannot_be_called_recovered(self):
        with self.assertRaises(RuntimeError):
            m.investigate(lambda _: dict(pid=1, javaMaxBytes=m.BAD_BOOT), lambda: '512m', lambda: None, lambda: None)

    def test_unknown_budget_or_changed_property_stops(self):
        for value, prop in [(33554432, '512m'), (m.BAD_BOOT, '256m')]:
            with self.subTest(value=value, prop=prop), self.assertRaises(RuntimeError):
                m.investigate(lambda _: dict(pid=1, javaMaxBytes=value), lambda: prop, lambda: None, lambda: None)

    def test_missing_bad_budget_at_control_is_inconclusive(self):
        rows = iter([dict(pid=1, javaMaxBytes=m.BAD_BOOT), dict(pid=2, javaMaxBytes=m.EXPECTED)])
        with self.assertRaisesRegex(RuntimeError, 'inconclusive'):
            m.investigate(lambda _: next(rows), lambda: '512m', lambda: None, lambda: None)

    def test_workflow_runs_full_suite_once_after_diagnostic(self):
        text = (Path(__file__).resolve().parents[1] / '.github/workflows/api29-recovery-proof.yml').read_text()
        self.assertEqual(1, text.count('adb shell am instrument -w -r'))
        self.assertLess(text.index('python3 scripts/api29_recovery_proof.py'), text.index('adb shell am instrument -w -r'))
        self.assertNotIn('continue-on-error:', text)
        self.assertIn('if: always()', text)
        self.assertNotIn('setprop dalvik.vm', text)
