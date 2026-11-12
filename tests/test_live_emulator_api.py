"""Read-only workflow regression. Never boots an emulator or dispatches a live run."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LiveEmulatorApiTest(unittest.TestCase):
    def test_api36_matches_predownload_and_checks_before_network_tunnel(self):
        text = (ROOT / '.github/workflows/live-emulator-e2e.yml').read_text()
        api = re.findall(r'^\s+api-level:\s*(\d+)\s*$', text, re.M)
        self.assertEqual(['36'], api)
        self.assertIn("'platforms;android-36'", text)
        self.assertIn("'system-images;android-36;default;x86_64'", text)
        self.assertIn('test -d "$sdkroot/system-images/android-36/default/x86_64"', text)
        self.assertNotIn('android-30', text)
        self.assertNotIn('android-37', text)
        self.assertLess(text.index('Pre-download the emulator system image'), text.index('Egress probe'))
        self.assertLess(text.index('Pre-download the emulator system image'), text.index('Bring up owner L2TP'))

    def test_upgrade_does_not_remove_explicit_input_or_duplicate_send_guards(self):
        text = (ROOT / '.github/workflows/live-emulator-e2e.yml').read_text()
        self.assertIn('workflow_dispatch:', text)
        self.assertIn('approved_sha256:', text)
        self.assertIn('approved digest already went through a live run', text)
        self.assertIn('fail closed', text)
