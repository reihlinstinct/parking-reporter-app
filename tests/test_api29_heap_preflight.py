import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('heap', Path(__file__).resolve().parents[1] / 'scripts/api29_heap_preflight.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class HeapPreflightTest(unittest.TestCase):
    def run_gate(self, rows, props=('512m', '512m')):
        calls, reads = [], []
        rows, props = iter(rows), iter(props)
        def read(label):
            reads.append(label)
            return next(rows)
        result = m.gate(read, lambda: next(props), lambda: calls.append('restart'))
        return result, calls, reads

    def test_healthy_runs_one_probe_without_restart(self):
        result, calls, reads = self.run_gate([dict(pid=41, javaMaxBytes=m.EXPECTED)])
        self.assertFalse(result['recoveryAttempted'])
        self.assertEqual([], calls)
        self.assertEqual(['before'], reads)
        self.assertFalse(result['heapPropertiesChanged'])

    def test_bad_boot_restores_configured_budget_in_new_process(self):
        result, calls, reads = self.run_gate([dict(pid=41, javaMaxBytes=m.BAD_BOOT),
                                               dict(pid=53, javaMaxBytes=m.EXPECTED)])
        self.assertTrue(result['recoveryAttempted'])
        self.assertEqual(['restart'], calls)
        self.assertEqual(['before', 'after'], reads)

    def test_persistent_bad_budget_fails_without_second_restart(self):
        with self.assertRaisesRegex(RuntimeError, 'one restart'):
            self.run_gate([dict(pid=41, javaMaxBytes=m.BAD_BOOT), dict(pid=53, javaMaxBytes=m.BAD_BOOT)])

    def test_reused_process_never_counts_as_recovery(self):
        with self.assertRaisesRegex(RuntimeError, 'fresh process'):
            self.run_gate([dict(pid=41, javaMaxBytes=m.BAD_BOOT), dict(pid=41, javaMaxBytes=m.EXPECTED)])

    def test_unknown_budget_is_not_repaired(self):
        with self.assertRaisesRegex(RuntimeError, 'unexpected actual'):
            self.run_gate([dict(pid=41, javaMaxBytes=33554432)])

    def test_configured_budget_must_stay_unchanged(self):
        for props in [('256m',), ('512m', '256m')]:
            with self.subTest(props=props), self.assertRaises(RuntimeError):
                self.run_gate([dict(pid=41, javaMaxBytes=m.BAD_BOOT)], props)

    def test_probe_requires_passing_single_test_and_numeric_metadata(self):
        for raw in ['{}', '{"pid": true, "javaMaxBytes": 536870912}',
                    '{"pid": 4, "javaMaxBytes": "536870912"}']:
            with tempfile.TemporaryDirectory() as directory, patch.object(m, 'adb', side_effect=['', 'OK (1 test)', raw]):
                with self.assertRaises(RuntimeError):
                    m.budget(Path(directory), 'before')
        with tempfile.TemporaryDirectory() as directory, patch.object(m, 'adb', side_effect=['', 'FAILURES!!!']):
            with self.assertRaises(RuntimeError):
                m.budget(Path(directory), 'before')

    def test_non_api29_is_not_touched(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(sys, 'argv', ['heap', '--report-dir', directory]), patch.object(m, 'adb', return_value='36') as adb, patch.object(m, 'gate') as gate:
            self.assertEqual(0, m.main())
            gate.assert_not_called()
            self.assertEqual(1, adb.call_count)

    def test_workflow_orders_heap_and_readiness_before_full_suite(self):
        text = (Path(__file__).resolve().parents[1] / '.github/workflows/android.yml').read_text()
        heap = text.index('python3 scripts/api29_heap_preflight.py')
        readiness = text.index('python3 scripts/emulator_preflight.py')
        suite = text.index('adb shell am instrument -w -r', readiness)
        self.assertLess(readiness, heap)
        self.assertLess(readiness, suite)
        source = (Path(__file__).resolve().parents[1] / 'scripts/api29_heap_preflight.py').read_text()
        self.assertNotIn("'setprop', 'dalvik.vm", source)
        self.assertNotIn('largeHeap', source)
