"""Opt-in preview identity must not overwrite the production app."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class PreviewIdentityTest(unittest.TestCase):
    def test_default_identity_is_unchanged_and_suffix_is_opt_in(self):
        text = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('applicationId = "il.sidewalks.reporter"', text)
        self.assertIn('testApplicationId = "il.sidewalks.reporter.test"', text)
        self.assertIn('providers.gradleProperty("previewSideBySide").isPresent', text)
        self.assertIn('applicationIdSuffix = ".preview"', text)
        self.assertIn('if (sideBySidePreview)', text)
        self.assertNotIn('signingConfig', text)

    def test_overlay_has_distinct_label_without_changing_default(self):
        import xml.etree.ElementTree as ET
        key = '{http://schemas.android.com/apk/res/android}label'
        default = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot().find('application').get(key)
        preview = ET.parse(ROOT / 'app/src/sideBySidePreview/AndroidManifest.xml').getroot().find('application').get(key)
        self.assertNotEqual(default, preview)
        self.assertIn('106', preview)

    def test_replacement_debug_manifest_preserves_debug_contract(self):
        import xml.etree.ElementTree as ET
        default = ET.parse(ROOT / 'app/src/debug/AndroidManifest.xml').getroot().find('application')
        preview = ET.parse(ROOT / 'app/src/sideBySidePreview/AndroidManifest.xml').getroot().find('application')
        for key, value in default.attrib.items():
            self.assertEqual(value, preview.get(key))
        name = '{http://schemas.android.com/apk/res/android}name'
        for child in default:
            matches = [candidate for candidate in preview if candidate.tag == child.tag and candidate.get(name) == child.get(name)]
            self.assertEqual(1, len(matches))
            self.assertEqual(child.attrib, matches[0].attrib)

    def test_ci_trigger_targets_current_main_not_old_stack(self):
        workflow = (ROOT / '.github/workflows/side-by-side-preview.yml').read_text()
        triggers = workflow.split('permissions:', 1)[0]
        self.assertIn('branches: [main]', triggers)
        self.assertNotIn('fix/private-address-install', triggers)
        self.assertIn('workflow_dispatch:', triggers)

    def test_ci_publishes_both_standard_and_separate_artifacts(self):
        workflow = (ROOT / '.github/workflows/side-by-side-preview.yml').read_text()
        standard = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertIn('name: phone-arm64-preview-apk', standard)
        self.assertIn('name: phone-arm64-side-by-side-preview-apk', workflow)
        self.assertIn('-PpreviewArm64 -PpreviewSideBySide', workflow)
        self.assertNotIn('adb uninstall', workflow)

if __name__ == '__main__':
    unittest.main()
