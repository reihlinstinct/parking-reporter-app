import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class AddressDisplayTest(unittest.TestCase):
    def test_actual_address_settings_test_and_all_captures_are_required(self):
        workflow = (ROOT / '.github/workflows/display-weekly.yml').read_text()
        test = (ROOT / 'app/src/androidTest/java/io/github/reihlinstinct/reporter/AddressSettingsTest.kt').read_text()
        self.assertIn("-e class 'io.github.reihlinstinct.reporter.AddressSettingsTest,", workflow)
        for name in ('top', 'bottom', 'import', 'refresh'):
            filename = 'address-advanced-settings-' + name + '.png'
            self.assertIn(filename, test)
            self.assertEqual(2, workflow.count(filename), filename)
        self.assertIn('"address-refresh").performScrollTo().assertIsDisplayed()', test)

if __name__ == '__main__':
    unittest.main()
