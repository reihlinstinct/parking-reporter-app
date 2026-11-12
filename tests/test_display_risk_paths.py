import pathlib
import unittest

class DisplayRiskPathsTest(unittest.TestCase):
    def test_risky_source_families_are_not_silently_skipped(self):
        workflow = pathlib.Path('.github/workflows/display-weekly.yml').read_text()
        for pattern in ['app/src/main/assets/**', 'app/src/main/java/**/i18n/**', 'app/src/androidTest/**', 'app/src/main/res/**', 'app/src/main/java/**/ui/**', 'app/src/main/java/**/capture/**']:
            self.assertIn("- '" + pattern + "'", workflow)
        self.assertIn('compact-200pct', workflow)
        self.assertIn('foldable-normal', workflow)
        self.assertIn("font_scale: '2.0'", workflow)

if __name__ == '__main__':
    unittest.main()
