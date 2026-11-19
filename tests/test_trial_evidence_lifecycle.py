"""Offline evidence lifecycle contract, no private artifacts."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]

class EvidenceLifecycleTest(unittest.TestCase):
    def test_all_submission_workflows_upload_only_ciphertext_then_cleanup_plaintext(self):
        for name, prefix in [('supervised-api-trial', 'supervised-api-trial'), ('two-car-ci', 'two-car-ci'), ('submission-branch', 'submission')]:
            source = (ROOT / ('.github/workflows/' + name + '.yml')).read_text()
            upload = source.split('uses: actions/upload-artifact@', 1)[1]
            path_line = next(line.strip() for line in upload.splitlines() if line.strip().startswith('path:'))
            self.assertTrue(path_line.endswith('.enc'), (name, path_line))
            self.assertIn('retention-days: 14', upload)
            cleanup = upload.split('- name:', 1)[1]
            self.assertNotIn('.tgz.enc', cleanup)
            self.assertNotIn('.aesgcm', cleanup)
            self.assertIn('rm -rf /tmp/supervised-api-trial', cleanup)
