"""The incomplete recovery integration must remain inert in ordinary builds."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class ActivationBoundaryTest(unittest.TestCase):
    def test_owner_is_hard_disabled_before_resources(self):
        source = (ROOT / 'app/src/main/java/io/github/reihlinstinct/reporter/security/ReviewRecoveryOwner.kt').read_text()
        self.assertIn('const val ENABLED = false', source)
        guard = source.index('if (!ENABLED || controller != null) return')
        for resource in ('Executors.newSingleThreadExecutor', 'ReviewRecoveryStore(app.noBackupFilesDir)',
                         'ReviewRecoveryPlatform.attach(app, owner)', 'owner.startupSweep()'):
            self.assertGreater(source.index(resource), guard)

    def test_shell_does_not_enable_recovery_ports(self):
        source = (ROOT / 'app/src/main/java/io/github/reihlinstinct/reporter/ui/AppShell.kt').read_text()
        self.assertNotIn('onRecoveryEdit =', source)
        self.assertNotIn('recoverySnapshot =', source)

if __name__ == '__main__':
    unittest.main()
