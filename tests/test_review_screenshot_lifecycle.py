"""Bounded synthetic review screenshot inventory and CI retention contract (#679)."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]

class ReviewScreenshotLifecycleTest(unittest.TestCase):
    def test_all_nine_fixed_outputs_have_explicit_retention(self):
        directory = ROOT / 'app/src/androidTest/java/io/github/reihlinstinct/reporter'
        outputs = set()
        for name in ('ReviewAddressTest.kt', 'ReviewRecreationTest.kt'):
            source = (directory / name).read_text()
            outputs.update(re.findall(r'"([^"/]+\.png)"', source))
        self.assertEqual(9, len(outputs))
        policy = (ROOT / 'docs/SYNTHETIC_SCREENSHOTS.md').read_text()
        for name in outputs:
            self.assertEqual(1, policy.count('- ' + name + '\n'), name)
        workflow = (ROOT / '.github/workflows/android.yml').read_text()
        collect = workflow.index('for f in $(adb shell run-as il.sidewalks.reporter ls files/')
        upload = workflow.index('name: emulator-test-results-api')
        self.assertLess(collect, upload)
        self.assertIn('retention-days: 14', workflow[upload:upload + 300])
        self.assertIn('if: always()', workflow[collect:upload])
        recreation = (directory / 'ReviewRecreationTest.kt').read_text()
        cleanup = recreation[recreation.index('} finally {'):]
        for required in ('scenario?.close()', 'ReviewRotationState.clear()', 'DELETE FROM reports', 'db.close()', 'original.delete()'):
            self.assertIn(required, cleanup)
        self.assertNotIn('review-recreation-synthetic.png', cleanup)

if __name__ == '__main__':
    unittest.main()
