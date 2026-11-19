import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('summary', Path(__file__).parents[1] / 'scripts/test_summary.py')
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)

class CoverageScopeTest(unittest.TestCase):
    def test_nested_counter_cannot_substitute_for_production_total(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory) / 'report.xml'
            p.write_text('<report><counter type="LINE" missed="90" covered="10"/><package><class><counter type="LINE" missed="0" covered="100"/></class></package></report>')
            self.assertEqual({'LINE': 10.0}, summary.load_jacoco(str(p)))
            p.write_text('<report><package><counter type="LINE" missed="0" covered="100"/></package></report>')
            self.assertIsNone(summary.load_jacoco(str(p)))
