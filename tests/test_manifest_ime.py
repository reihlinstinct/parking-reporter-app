import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class ManifestImeTest(unittest.TestCase):
    def test_main_activity_resizes_for_keyboard(self):
        text = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertIn('android:windowSoftInputMode="adjustResize"', text)

    def test_screens_use_the_compat_keyboard_padding(self):
        for name in ('SettingsScreen', 'ReviewScreen'):
            text = (ROOT / f'app/src/main/java/io/github/reihlinstinct/reporter/ui/{name}.kt').read_text()
            self.assertIn('imePaddingCompat()', text)
            self.assertNotIn('.imePadding()', text)

    def test_api29_is_in_the_pr_matrix(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertIn('api: ["29", "30", "36"]', text)


if __name__ == '__main__':
    unittest.main()
