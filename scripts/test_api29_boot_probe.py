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

    def test_emulator_action_each_script_line_is_standalone_shell(self):
        import subprocess
        workflow = (Path(__file__).resolve().parents[1]/'.github/workflows/api29-memory.yml').read_text()
        block = workflow.split('          script: |', 1)[1].split('      - uses:', 1)[0]
        lines = [line.strip().replace('${{ matrix.mode }}', 'boot-probe')
                 for line in block.splitlines() if line.strip()]
        for line in lines:
            result = subprocess.run(['sh', '-n', '-c', line], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_optional_init_files_use_root_and_cannot_abort_all_tests(self):
        workflow = (Path(__file__).resolve().parents[1]/'.github/workflows/api29-memory.yml').read_text()
        for line in workflow.splitlines():
            if 'cat /init.rc' in line or 'cat /vendor/etc/init' in line or 'cat /system/build.prop' in line:
                self.assertIn('shell su root cat', line)
                self.assertIn('|| echo', line)

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
