import unittest
from unittest.mock import patch
import tempfile
from pathlib import Path
import api29_boot_probe as target


class BootProbeTest(unittest.TestCase):
    def run_probe(self, maxima, configured='512m'):
        with tempfile.TemporaryDirectory() as directory:
            rows = iter([dict(javaMaxBytes=n, memoryClassMiB=512) for n in maxima])
            with patch.object(target, 'OUT', Path(directory)), \
                 patch.object(target, 'adb', return_value=configured) as calls, \
                 patch.object(target, 'ready'), patch.object(target, 'capture'), \
                 patch.object(target, 'probe', side_effect=lambda _: next(rows)), \
                 patch.object(target.time, 'sleep'):
                target.main()
                return __import__('json').loads((Path(directory)/'boot-probe-summary.json').read_text()), calls.call_args_list

    def test_bounded_cold_probe_without_heap_property_changes(self):
        result, calls = self.run_probe([536870912]*8)
        self.assertEqual(result['status'], 'not_reproduced')
        self.assertEqual(len(result['coldBoots']), 8)
        self.assertEqual(sum(c.args == ('reboot',) for c in calls), 7)
        self.assertFalse(any('setprop' in c.args for c in calls))

    def test_16m_then_warm_control_only_restarts_zygote(self):
        result, calls = self.run_probe([16777216, 536870912])
        self.assertTrue(result['restoredConfiguredBudget'])
        self.assertEqual(result['status'], 'reproduced_with_control')
        writes = [c.args for c in calls if 'setprop' in c.args]
        self.assertEqual(writes, [('shell','su','root','setprop','ctl.restart','zygote')])

    def test_wrong_configured_budget_refuses_intervention(self):
        with self.assertRaisesRegex(RuntimeError, 'refuse control'):
            self.run_probe([16777216], configured='64m')

    def test_failed_control_not_claimed_restored(self):
        result, _ = self.run_probe([16777216,16777216])
        self.assertFalse(result['restoredConfiguredBudget'])


if __name__ == '__main__':
    unittest.main()
