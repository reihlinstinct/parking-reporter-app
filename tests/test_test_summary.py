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


class JacocoRequirementTest(unittest.TestCase):
    def run_main(self, xml, *flags):
        import os, subprocess, sys
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "report.xml"
            report.write_text(xml)
            junit = Path(tmp) / "junit"
            junit.mkdir()
            (junit / "TEST-a.xml").write_text('<testsuite name="s" tests="1"><testcase classname="s" name="t"/></testsuite>')
            return subprocess.run([sys.executable, str(Path(__file__).parents[1] / "scripts/test_summary.py"), "--title", "t",
                                   "--junit", str(junit), "--jacoco", str(report), *flags], capture_output=True, text=True, env={**os.environ, "GITHUB_STEP_SUMMARY": ""})

    EMPTY = '<?xml version="1.0"?><report name="app"><sessioninfo id="x" start="1" dump="2"/></report>'
    FULL = '<?xml version="1.0"?><report name="app"><counter type="LINE" missed="25" covered="75"/></report>'

    def test_empty_report_is_stated_as_not_measured_and_fails_when_required(self):
        plain = self.run_main(self.EMPTY)
        self.assertEqual(plain.returncode, 0)
        self.assertIn("not measured", plain.stdout)
        self.assertEqual(self.run_main(self.EMPTY, "--require-jacoco").returncode, 1)

    def test_report_with_line_counter_passes_and_states_the_percentage(self):
        result = self.run_main(self.FULL, "--require-jacoco")
        self.assertEqual(result.returncode, 0)
        self.assertIn("line 75.0%", result.stdout)

    def test_floor_passes_at_or_above_and_fails_below(self):
        self.assertEqual(self.run_main(self.FULL, "--min-line-coverage", "75").returncode, 0)
        below = self.run_main(self.FULL, "--min-line-coverage", "80")
        self.assertEqual(below.returncode, 1)
        self.assertIn("below the floor", below.stderr)

    def test_floor_fails_when_nothing_was_measured(self):
        result = self.run_main(self.EMPTY, "--min-line-coverage", "50")
        self.assertEqual(result.returncode, 1)
        self.assertIn("cannot check the floor", result.stderr)
