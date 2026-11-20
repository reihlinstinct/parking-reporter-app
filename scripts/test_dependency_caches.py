import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import dependency_cache_size as size

ROOT = Path(__file__).resolve().parents[1]
PIN = '55cc8345863c7cc4c66a329aec7e433d2d1c52a9'


def validate(workflow, writers):
    restore = workflow.count('uses: actions/cache/restore@' + PIN)
    save = workflow.count('uses: actions/cache/save@' + PIN)
    assert restore > 0 and save == writers
    assert 'cache: gradle' not in workflow
    assert 'restore-keys:' not in workflow
    assert workflow.count('~/.gradle/caches/modules-2\n') == restore + save
    assert workflow.count('!~/.gradle/caches/modules-2/**/*.lock') == restore + save
    assert workflow.count('key: ${{ runner.os }}-${{ runner.arch }}-') == restore + save
    for step in workflow.split('      - name: ')[1:]:
        if 'uses: actions/cache/save@' not in step:
            continue
        assert "success() && github.event_name == 'push' && github.ref == 'refs/heads/main'" in step
        assert "steps.dependency-cache.outputs.cache-hit != 'true'" in step
        assert "steps.dependency-cache-size.outputs.save == 'true'" in step


class DependencyCacheTests(unittest.TestCase):
    def test_consumers_and_only_two_main_writers(self):
        for name, writers in [('android.yml', 1), ('api-bridge.yml', 1), ('display-weekly.yml', 0)]:
            with self.subTest(name=name):
                validate((ROOT / '.github/workflows' / name).read_text(), writers)

    def test_android_keys_identical_across_build_matrix_display(self):
        android = (ROOT / '.github/workflows/android.yml').read_text()
        display = (ROOT / '.github/workflows/display-weekly.yml').read_text()
        keys = [line.strip() for text in [android, display] for line in text.splitlines() if 'key: ${{ runner.os }}-' in line]
        self.assertEqual(len(keys), 4)
        self.assertEqual(len(set(keys)), 1)
        for name in ['settings.gradle.kts', 'build.gradle.kts', 'app/build.gradle.kts', 'gradle.properties', 'gradle/wrapper/**']:
            self.assertIn("'" + name + "'", keys[0])

    def test_bridge_key_binds_verification_and_wrapper(self):
        text = (ROOT / '.github/workflows/api-bridge.yml').read_text()
        self.assertIn('bridge-modules-v1-', text)
        for path in ['api-bridge/*.gradle.kts', 'api-bridge/gradle/verification-metadata.xml', 'gradle/wrapper/**']:
            self.assertIn("'" + path + "'", text)
        self.assertEqual(text.count('--dependency-verification strict'), 2)
        self.assertLess(text.index('--dependency-verification strict'), text.index('name: Publish exact successful'))

    def test_mutations_do_not_allow_pr_writer_broad_path_or_fallback(self):
        text = (ROOT / '.github/workflows/api-bridge.yml').read_text()
        for mutated in [text.replace("github.event_name == 'push'", "github.event_name == 'pull_request'"), text.replace('~/.gradle/caches/modules-2\n', '~/.gradle\n'), text + '\nrestore-keys: broad']:
            with self.assertRaises(AssertionError):
                validate(mutated, 1)

    def test_size_cap_empty_and_small(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertFalse(size.eligible(root))
            (root / 'public-artifact.jar').write_bytes(b'abc')
            self.assertTrue(size.eligible(root))
            with patch.object(size, 'MAX_BYTES', 2):
                self.assertFalse(size.eligible(root))

    def test_symlink_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'file').write_bytes(b'abc')
            (root / 'link').symlink_to(root / 'file')
            self.assertFalse(size.eligible(root))

    def test_warmup_and_official_fallback_not_bypassed(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertEqual(text.count('run: python3 scripts/warm_gradle_plugins.py'), 2)
        self.assertNotIn('--offline', text)
        self.assertIn('https://repo1.maven.org/maven2', (ROOT / 'settings.gradle.kts').read_text())


if __name__ == '__main__':
    unittest.main()
