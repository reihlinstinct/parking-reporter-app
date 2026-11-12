import importlib.util
import pathlib
import sys
import tempfile
import unittest

scripts = pathlib.Path(__file__).resolve().parents[1] / 'scripts'
sys.path.insert(0, str(scripts))
spec = importlib.util.spec_from_file_location('upgrade_test_gate', scripts / 'check_upgrade_test.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class UpgradeTestGateTest(unittest.TestCase):
    def log(self, code=0, suite='expected.Suite', name='expectedTest'):
        return f'INSTRUMENTATION_STATUS: class={suite}\nINSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: {code}\n'

    def test_only_one_expected_pass_is_accepted(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / 'instrumentation.txt'
            path.write_text(self.log())
            self.assertIn('1 passed, 0 skipped, 0 failed', m.check(path, 'expected.Suite', 'expectedTest'))
            for raw in ['', self.log(-4), self.log(-3), self.log(-1), self.log(-2), self.log() * 2,
                        self.log(name='wrong'), self.log(suite='wrong.Suite')]:
                with self.subTest(raw=raw), self.assertRaises(ValueError):
                    path.write_text(raw)
                    m.check(path, 'expected.Suite', 'expectedTest')
