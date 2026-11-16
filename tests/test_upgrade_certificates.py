import importlib.util
import pathlib
import unittest

spec = importlib.util.spec_from_file_location('upgrade_certificates', pathlib.Path(__file__).resolve().parents[1] / 'scripts/check_upgrade_certificates.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class UpgradeCertificatesTest(unittest.TestCase):
    def test_current_and_legacy_apksigner_formats(self):
        self.assertEqual('a' * 64, m.check('V2 Signer: certificate SHA-256 digest: ' + 'a' * 64,
                                         'Signer #1 certificate SHA-256 digest: ' + 'A' * 64))

    def test_missing_malformed_multiple_and_mismatch_fail(self):
        valid = 'V2 Signer: certificate SHA-256 digest: ' + 'a' * 64
        for invalid in ['', valid[:-1], valid + '\n' + valid, valid.replace('a' * 64, 'b' * 64)]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                m.check(valid, invalid)
