import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import emulator_snapshot_cache as cache
spec = importlib.util.spec_from_file_location('clean_marker', ROOT / 'scripts/ci/clean_snapshot_read_marker.py')
cleaner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cleaner)


class SnapshotMarkerCleanupTest(unittest.TestCase):
    def test_only_exact_regular_marker_removed_before_unchanged_validation(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            avd = root / 'test.avd' / 'snapshots' / 'default_boot'
            avd.mkdir(parents=True)
            marker = avd / 'read-snapshot.txt'
            marker.write_text('synthetic transient emulator marker')
            with self.assertRaises(cache.CacheRejected):
                cache.scan_tree(root)
            self.assertEqual(1, cleaner.clean(root))
            self.assertFalse(marker.exists())
            cache.scan_tree(root)
            self.assertEqual(0, cleaner.clean(root))
            (avd / 'private.txt').write_text('synthetic forbidden file')
            self.assertEqual(0, cleaner.clean(root))
            with self.assertRaises(cache.CacheRejected):
                cache.scan_tree(root)

    def test_symlink_marker_is_not_deleted_or_followed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            outside = root / 'outside'
            outside.write_text('synthetic')
            (root / 'read-snapshot.txt').symlink_to(outside)
            with self.assertRaises(ValueError):
                cleaner.clean(root)
            self.assertTrue(outside.exists())
            self.assertTrue((root / 'read-snapshot.txt').is_symlink())

    def test_no_symlink_directory_traversal(self):
        with tempfile.TemporaryDirectory() as temp, tempfile.TemporaryDirectory() as external:
            root, outside = Path(temp), Path(external)
            marker = outside / 'read-snapshot.txt'
            marker.write_text('synthetic')
            (root / 'linked').symlink_to(outside, target_is_directory=True)
            self.assertEqual(0, cleaner.clean(root))
            self.assertTrue(marker.exists())
            with self.assertRaises(cache.CacheRejected):
                cache.scan_tree(root)

    def test_workflow_cleans_before_validation_before_publication(self):
        text = (ROOT / '.github/workflows/emulator-snapshot-producer.yml').read_text()
        self.assertLess(text.index('scripts/ci/clean_snapshot_read_marker.py'), text.index('write-manifest --avd-dir'))
        self.assertLess(text.index('write-manifest --avd-dir'), text.index('- name: Publish'))
        source = (ROOT / 'scripts/emulator_snapshot_cache.py').read_text()
        self.assertIn("'read-snapshot.txt': 'emulator_read_snapshot_txt'", source)
