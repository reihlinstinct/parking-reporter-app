import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("test_summary", Path(__file__).parents[1] / "scripts/test_summary.py")
summary = importlib.util.module_from_spec(spec)
spec.loader.exec_module(summary)


class InstrumentationStatusTest(unittest.TestCase):
    def parse(self, code, reason=""):
        raw = ("INSTRUMENTATION_STATUS: class=example.Suite\n"
               "INSTRUMENTATION_STATUS: test=example\n"
               "INSTRUMENTATION_STATUS_CODE: 1\n")
        if reason:
            raw += "INSTRUMENTATION_STATUS: stack=" + reason + "\n"
        raw += "INSTRUMENTATION_STATUS_CODE: " + str(code) + "\n"
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "instrumentation.txt"
            p.write_text(raw)
            return summary.load_instrumentation(p)[0]

    def test_assumption_minus_four_is_skipped_with_reason(self):
        reason = "org.junit.AssumptionViolatedException: namespace upgrade opt-in missing"
        case = self.parse(-4, reason)
        self.assertEqual(case.status, "skipped")
        self.assertEqual(case.reason, reason)

    def test_ignored_minus_three_is_skipped(self):
        self.assertEqual(self.parse(-3).status, "skipped")

    def test_real_errors_assertions_and_unknown_codes_still_fail(self):
        for code in (-1, -2, -5, 2):
            with self.subTest(code=code):
                self.assertEqual(self.parse(code, "assertion failed").status, "failed")

    def test_success_still_passes(self):
        self.assertEqual(self.parse(0).status, "passed")
