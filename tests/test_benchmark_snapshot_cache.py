import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    'bench', Path(__file__).resolve().parents[1] / 'scripts/benchmark_snapshot_cache.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


def write(d, name, mode, total, **extra):
    (Path(d) / name).write_text(json.dumps({'mode': mode, 'run': 1, 'phases': {'total': total, **extra}, 'cache_bytes': 2_000_000}))


class BenchTest(unittest.TestCase):
    def test_no_claim_below_three_runs(self):
        with tempfile.TemporaryDirectory() as d:
            for i in range(2):
                write(d, f'c{i}.json', 'cold', 600)
                write(d, f'w{i}.json', 'warm', 300)
            self.assertIn('Saving: not measured', b.report(b.load(Path(d))))

    def test_median_saving_with_three_each(self):
        with tempfile.TemporaryDirectory() as d:
            for i, t in enumerate([600, 700, 650]):
                write(d, f'c{i}.json', 'cold', t, boot_to_ready=300)
            for i, t in enumerate([300, 320, 500]):
                write(d, f'w{i}.json', 'warm', t, boot_to_ready=40)
            out = b.report(b.load(Path(d)))
            self.assertIn('cold 650s, warm 320s, saving +330s', out)
            self.assertIn('cache size: 2 MB', out)

    def test_fallback_runs_are_excluded(self):
        with tempfile.TemporaryDirectory() as d:
            for i in range(3):
                write(d, f'c{i}.json', 'cold', 600)
            for i in range(2):
                write(d, f'w{i}.json', 'warm', 300)
            write(d, 'f.json', 'warm-fell-back-cold', 600)
            out = b.report(b.load(Path(d)))
            self.assertIn('excluded): 1', out)
            self.assertIn('Saving: not measured', out)

    def test_bad_files_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / 'x.json').write_text(json.dumps({'mode': 'hot', 'phases': {}}))
            with self.assertRaises(ValueError):
                b.load(Path(d))
            (Path(d) / 'x.json').write_text(json.dumps({'mode': 'cold', 'phases': {'total': -1}}))
            with self.assertRaises(ValueError):
                b.load(Path(d))


if __name__ == '__main__':
    unittest.main()
