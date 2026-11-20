import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class RecoveryCopyTest(unittest.TestCase):
    def test_reviewed_hebrew_claims_and_all_languages_present(self):
        catalog = json.loads((ROOT / 'app/src/main/assets/i18n/strings.json').read_text())
        codes = {language['code'] for language in catalog['languages']}
        strings = catalog['strings']
        keys = {key for key in strings if key.startswith('review.recovery.')}
        self.assertEqual(23, len(keys))
        for key in keys:
            self.assertEqual(codes, set(strings[key]), key)
            self.assertTrue(all(isinstance(value, str) and value.strip() for value in strings[key].values()), key)
        self.assertNotIn('נמחק', strings['review.recovery.failed']['he'])
        self.assertNotIn('נמחק', strings['review.recovery.expired']['he'])
        self.assertIn('נמחק', strings['review.recovery.failed_deleted']['he'])
        self.assertIn('נמחק', strings['review.recovery.expired_deleted']['he'])
        self.assertIn('עד 15', strings['review.recovery.a11y_started']['he'])
        self.assertIn('לא נשלח ולא אושר', strings['review.recovery.restored']['he'])

    def test_controls_have_no_side_effect_without_supplied_actions(self):
        screen = (ROOT / 'app/src/main/java/io/github/reihlinstinct/reporter/ui/ReviewScreen.kt').read_text()
        shell = (ROOT / 'app/src/main/java/io/github/reihlinstinct/reporter/ui/AppShell.kt').read_text()
        self.assertIn('recoveryActions: ReviewRecoveryActions? = null', screen)
        self.assertIn('recoveryActions?.let { ReviewRecoveryControls(it) }', screen)
        self.assertNotIn('recoveryActions =', shell)

if __name__ == '__main__':
    unittest.main()
