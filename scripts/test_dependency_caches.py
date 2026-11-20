import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import dependency_cache_size as size

ROOT = Path(__file__).resolve().parents[1]
PIN = '55cc8345863c7cc4c66a329aec7e433d2d1c52a9'
SAVE_GUARD = ("success() && github.event_name == 'push' && github.ref == 'refs/heads/main'"
              " && steps.dependency-cache.outputs.cache-hit != 'true'"
              " && steps.dependency-cache-size.outputs.save == 'true'")


def validate(workflow, writers):
    restore = workflow.count('uses: actions/cache/restore@' + PIN)
    save = workflow.count('uses: actions/cache/save@' + PIN)
    assert restore > 0 and save == writers
    assert 'cache: gradle' not in workflow
    assert 'restore-keys:' not in workflow
    assert workflow.count('~/.gradle/caches/modules-2\n') == restore + save
    assert workflow.count('!~/.gradle/caches/modules-2/**/*.lock') == restore + save
    assert workflow.count('key: ${{ runner.os }}-${{ runner.arch }}-') == restore + save
    for step in re.split(r'(?m)^      - |^  [a-zA-Z][^\n]*:', workflow)[1:]:
        if 'uses: actions/cache/save@' not in step:
            continue
        guards = [line.strip()[4:] for line in step.splitlines() if line.strip().startswith('if: ')]
        assert guards == [SAVE_GUARD]
        assert 'continue-on-error: true' in step



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
        for name in ['android.yml', 'api-bridge.yml']:
            text = (ROOT / '.github/workflows' / name).read_text()
            mutations = [
                text.replace("github.event_name == 'push'", "github.event_name == 'pull_request'"),
                text.replace('~/.gradle/caches/modules-2\n', '~/.gradle\n'),
                text + '\nrestore-keys: broad',
                text.replace(SAVE_GUARD, SAVE_GUARD + ' || true'),
            ]
            for mutated in mutations:
                with self.subTest(name=name, mutation=mutations.index(mutated)):
                    with self.assertRaises(AssertionError):
                        validate(mutated, 1)

    def test_android_test_graph_is_built_before_size_and_save(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        build = text.split('  build:\n', 1)[1].split('  emulator-matrix:', 1)[0]
        command = 'sh gradlew --no-daemon lintDebug testDebugUnitTest assembleDebug assembleDebugAndroidTest compileReleaseKotlin'
        self.assertIn(command, build)
        self.assertLess(build.index(command), build.index('id: dependency-cache-size'))
        self.assertLess(build.index('id: dependency-cache-size'), build.index('uses: actions/cache/save@'))
        # Graph resolution is validated on PRs too, not hidden behind a push-only step.
        step = build.split('      - name: Build debug', 1)[1].split('      - name:', 1)[0]
        self.assertNotIn('if:', step)

    def test_cache_size_and_save_errors_are_nonfatal(self):
        for name in ['android.yml', 'api-bridge.yml']:
            text = (ROOT / '.github/workflows' / name).read_text()
            for step in text.split('      - name: ')[1:]:
                if 'id: dependency-cache-size' in step or 'uses: actions/cache/save@' in step:
                    self.assertIn('continue-on-error: true', step)

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

    def test_symlinked_directory_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            real = root / 'real'
            real.mkdir()
            (real / 'artifact.jar').write_bytes(b'abc')
            (root / 'linked-directory').symlink_to(real, target_is_directory=True)
            self.assertFalse(size.eligible(root))

    def test_warmup_and_official_fallback_not_bypassed(self):
        text = (ROOT / '.github/workflows/android.yml').read_text()
        self.assertEqual(text.count('run: python3 scripts/warm_gradle_plugins.py'), 2)
        self.assertNotIn('--offline', text)
        self.assertIn('https://repo1.maven.org/maven2', (ROOT / 'settings.gradle.kts').read_text())


if __name__ == '__main__':
    unittest.main()
