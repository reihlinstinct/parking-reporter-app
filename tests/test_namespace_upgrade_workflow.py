"""Install-upgrade evidence must keep data and share a CI-only signer."""
import pathlib
import unittest


class NamespaceUpgradeWorkflowTest(unittest.TestCase):
    def test_old_and_new_builds_share_signer_and_compare_certificate(self):
        workflow = pathlib.Path('.github/workflows/namespace-upgrade-proof.yml').read_text()
        builds = [line for line in workflow.splitlines() if 'sh gradlew --no-daemon assembleDebug assembleDebugAndroidTest' in line]
        self.assertEqual(2, len(builds))
        for build in builds:
            self.assertIn('-Pandroid.injected.signing.store.file="$UPGRADE_KEYSTORE"', build)
            self.assertIn('-Pandroid.injected.signing.key.alias=androiddebugkey', build)
        self.assertIn('keytool -genkeypair', workflow)
        self.assertIn('python3 scripts/check_upgrade_certificates.py app/build/reports/namespace-upgrade/old-certificate.txt app/build/reports/namespace-upgrade/new-certificate.txt', workflow)
        self.assertNotIn('adb uninstall', workflow)
        self.assertNotIn('pm clear', workflow)
        self.assertIn('adb install -r app/build/outputs/apk/debug/app-debug.apk', workflow)
        self.assertIn('InstalledNamespaceUpgradeTest', workflow)


if __name__ == '__main__':
    unittest.main()
