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

    def test_terminal_hooks_cover_successful_save_authenticated_send_and_leave(self):
        source = (ROOT / 'app/src/main/java/io/github/reihlinstinct/reporter/ui/AppShell.kt').read_text()
        save = source.split('fun saveDraft(value: ReviewDraft)', 1)[1].split('fun openSettings', 1)[0]
        self.assertLess(save.index('check(updated == 1)'), save.index('ReviewRecoveryOwner.controller?.terminal()'))
        send = source.split('fun submitReport(', 1)[1].split('fun saveDraft', 1)[0]
        self.assertLess(send.index('authenticate(strings.t("shell.unlock.submit_title")'),
                        send.index('ReviewRecoveryOwner.controller?.terminal()'))
        self.assertLess(send.index('ReviewRecoveryOwner.controller?.terminal()'), send.index('SubmissionCoordinator('))
        self.assertIn('if (previousRecoveryPage == "review" && page != "review") {\n            io.github.reihlinstinct.reporter.security.ReviewRecoveryOwner.controller?.terminal()', source)
        self.assertIn('var previousRecoveryPage by remember { mutableStateOf(page) }', source)

if __name__ == '__main__':
    unittest.main()
