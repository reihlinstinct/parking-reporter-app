import base64
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
def module(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / (name + '.py'))
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m
prepare = module('prepare_preview_signing').prepare
verify = module('verify_preview_certificate').verify

class PreviewSigningTest(unittest.TestCase):
    def test_material_is_private_and_existing_key_is_never_replaced(self):
        data = b'synthetic-not-a-key' * 100
        with tempfile.TemporaryDirectory() as d:
            with patch.dict(os.environ, {'PREVIEW_SIGNING_STORE_B64': base64.b64encode(data).decode()}):
                p = prepare(Path(d) / 'key')
                self.assertNotIn('PREVIEW_SIGNING_STORE_B64', os.environ)
                self.assertEqual(data, p.read_bytes())
                self.assertEqual(0o600, p.stat().st_mode & 0o777)
                self.assertEqual(0o700, p.parent.stat().st_mode & 0o777)
                os.environ['PREVIEW_SIGNING_STORE_B64'] = base64.b64encode(data).decode()
                with self.assertRaises(FileExistsError): prepare(p.parent)
                self.assertEqual(data, p.read_bytes())
    def test_invalid_and_missing_material_refuse(self):
        for value in ('', '!', base64.b64encode(b'small').decode()):
            with tempfile.TemporaryDirectory() as d, patch.dict(os.environ, {'PREVIEW_SIGNING_STORE_B64': value}):
                with self.assertRaises(ValueError): prepare(Path(d) / 'key')
                self.assertFalse((Path(d) / 'key' / 'preview.p12').exists())
    def test_exact_single_signer_required(self):
        pin = 'a' * 64
        report = 'Signer #1 certificate SHA-256 digest: ' + pin + '\n'
        verify(report, pin)
        for invalid in ('', report.replace(pin, 'b' * 64), report + report.replace('#1', '#2'), report + 'DOES NOT VERIFY'):
            with self.assertRaises(ValueError): verify(invalid, pin)
    def test_no_secret_on_pr_head_or_arbitrary_ref(self):
        workflow = (ROOT / '.github/workflows/persistent-preview.yml').read_text()
        self.assertNotIn('pull_request:', workflow)
        self.assertIn("if: github.ref == 'refs/heads/main'", workflow)
        self.assertIn('ref: ${{ github.sha }}', workflow)
        self.assertNotIn('workflow_dispatch:\n    inputs:', workflow)
        self.assertIn('if: always()', workflow)
        self.assertIn('verify_preview_certificate.py', workflow)
        self.assertNotIn('storePassword = "', (ROOT / 'app/build.gradle.kts').read_text())

if __name__ == '__main__': unittest.main()
