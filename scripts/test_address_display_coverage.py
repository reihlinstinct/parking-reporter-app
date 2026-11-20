import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class AddressDisplayTest(unittest.TestCase):
    def test_actual_address_settings_test_and_all_captures_are_required(self):
        workflow = (ROOT / '.github/workflows/display-weekly.yml').read_text()
        test = (ROOT / 'app/src/androidTest/java/io/github/reihlinstinct/reporter/AddressSettingsTest.kt').read_text()
        classes = re.search(r"-e class '([^']+)'", workflow)
        self.assertIsNotNone(classes)
        self.assertIn('io.github.reihlinstinct.reporter.AddressSettingsTest', classes.group(1).split(','))
        self.assertIn('io.github.reihlinstinct.reporter.ApprovedCopySettingsTest', classes.group(1).split(','))
        for name in ('top', 'bottom', 'import', 'refresh'):
            filename = 'address-advanced-settings-' + name + '.png'
            self.assertIn(filename, test)
            self.assertEqual(2, workflow.count(filename), filename)
        self.assertIn('"address-refresh").performScrollTo().assertIsDisplayed()', test)

if __name__ == '__main__':
    unittest.main()
