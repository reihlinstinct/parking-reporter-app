import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
TEXT = (ROOT / '.github/workflows/emulator-rotating.yml').read_text()


class RotatingEmulatorWorkflowTest(unittest.TestCase):
    def test_runs_weekly_and_on_demand_only(self):
        self.assertIn("cron: '0 5 * * 0'", TEXT)
        self.assertIn('workflow_dispatch:', TEXT)
        self.assertNotIn('pull_request', TEXT)

    def test_four_slices_cover_every_combination_once(self):
        combos = re.findall(r'\("(\d+)", "([^"]+)", "([^"]*)"\)', TEXT)
        self.assertEqual(len(combos), 8)
        self.assertEqual(len(set(combos)), 8)
        self.assertEqual({c[0] for c in combos}, {'33', '34', '35', '36'})
        self.assertIn('toordinal() // 7', TEXT)
        self.assertIn('slices[week % 4]', TEXT)
        self.assertNotIn('isocalendar', TEXT)

    def test_no_write_permission_beyond_contents_read(self):
        self.assertNotIn('issues: write', TEXT)
        self.assertNotIn('report-failure', TEXT)


if __name__ == '__main__':
    unittest.main()
