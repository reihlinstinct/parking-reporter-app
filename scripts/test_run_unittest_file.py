"""Synthetic gate cases. No network or application side effects."""
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

RUNNER = Path(__file__).with_name('run_unittest_file.py')


class UnittestFileGateTest(unittest.TestCase):
    def invoke(self, body):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'test_synthetic.py'
            path.write_text(body)
            return subprocess.run([sys.executable, str(RUNNER), str(path)], capture_output=True, text=True)

    def test_pass(self):
        self.assertEqual(self.invoke('import unittest\nclass T(unittest.TestCase):\n def test_ok(self): pass\n').returncode, 0)

    def test_failure(self):
        self.assertNotEqual(self.invoke('import unittest\nclass T(unittest.TestCase):\n def test_bad(self): self.fail()\n').returncode, 0)

    def test_zero_tests(self):
        result = self.invoke('import unittest\n')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('ZERO_TESTS', result.stderr)

    def test_import_error(self):
        self.assertNotEqual(self.invoke('raise RuntimeError("synthetic import failure")\n').returncode, 0)

    def test_missing_file(self):
        result = subprocess.run([sys.executable, str(RUNNER), '/not-a-real-test-file.py'], capture_output=True)
        self.assertNotEqual(result.returncode, 0)


if __name__ == '__main__':
    unittest.main()
