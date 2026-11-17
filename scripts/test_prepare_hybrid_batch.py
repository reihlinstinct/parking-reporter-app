"""Offline preparation pins and inactive workflow checks; no network or secrets."""
import hashlib
import os
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import prepare_hybrid_batch as preparation


class PreparePinsTest(unittest.TestCase):
    def test_independent_pins_are_forwarded_not_derived(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'private'
            data = b'synthetic ZIP bytes'
            env = dict(BATCH_APPROVED_OUTER_SHA256=hashlib.sha256(data).hexdigest(),
                       BATCH_APPROVED_INNER_SHA256='1' * 64,
                       BATCH_APPROVED_ADDRESSES_SHA256='2' * 64,
                       BATCH_VERIFIED_TOPIC_POLICY='{"9000": {"plateRequired": false}}')
            def stage(path): path.write_bytes(data)
            with patch.dict(os.environ, env, clear=True), patch.object(preparation, 'stage', stage), \
                    patch.object(preparation, 'validate_zip', return_value={}) as validate, \
                    patch.object(preparation.subprocess, 'run', return_value=SimpleNamespace(returncode=0)):
                self.assertEqual(preparation.main(root), 0)
                self.assertEqual(validate.call_args.args[1], '1' * 64)
                self.assertEqual(validate.call_args.args[3], '2' * 64)
            self.assertFalse((root / 'input').exists())
            self.assertFalse((root / 'effective.json').exists())

    def test_wrong_outer_pin_refuses_before_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'private'
            with patch.dict(os.environ, {'BATCH_APPROVED_OUTER_SHA256': '0' * 64}, clear=True), \
                    patch.object(preparation, 'stage', lambda path: path.write_bytes(b'synthetic')), \
                    patch.object(preparation, 'validate_zip') as validate:
                self.assertEqual(preparation.main(root), 1)
                validate.assert_not_called()
            self.assertFalse((root / 'input').exists())

    def test_inactive_workflow_is_hard_disabled(self):
        text = Path(__file__).resolve().parents[1].joinpath('.github/workflows/live-batch-hybrid.yml').read_text()
        self.assertIn('if: ${{ false }}', text)
        self.assertNotIn('run_attempt', text)
        self.assertIn('BATCH_APPROVED_INNER_SHA256:', text)
        self.assertIn('BATCH_APPROVED_ADDRESSES_SHA256:', text)


if __name__ == '__main__':
    unittest.main()
