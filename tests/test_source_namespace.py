"""Installed identity must not change with a source-only namespace rename."""
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = '{http://schemas.android.com/apk/res/android}'
NEW = 'io/github/reihlinstinct/reporter'


class SourceNamespaceTest(unittest.TestCase):
    def test_source_tree_and_namespace(self):
        for source in ('main', 'test', 'androidTest'):
            self.assertFalse((ROOT / f'app/src/{source}/java/il/sidewalks').exists())
            self.assertTrue((ROOT / f'app/src/{source}/java/{NEW}').is_dir())
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('namespace = "io.github.reihlinstinct.reporter"', gradle)
        self.assertIn('applicationId = "il.sidewalks.reporter"', gradle)
        self.assertIn('testApplicationId = "il.sidewalks.reporter.test"', gradle)

    def test_installed_components_and_links_remain_stable(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
        activity = manifest.find('./application/activity')
        self.assertEqual('il.sidewalks.reporter.MainActivity', activity.get(A + 'name'))
        self.assertEqual('singleTask', activity.get(A + 'launchMode'))
        service = manifest.find('./application/service')
        self.assertEqual('il.sidewalks.reporter.ReportTileService', service.get(A + 'name'))
        self.assertEqual('android.permission.BIND_QUICK_SETTINGS_TILE', service.get(A + 'permission'))
        schemes = [data.get(A + 'scheme') for data in activity.findall('./intent-filter/data')]
        self.assertIn('il.sidewalks.reporter', schemes)
        entries = (ROOT / f'app/src/main/java/{NEW}/EntryIntents.kt').read_text()
        self.assertIn('"il.sidewalks.reporter.action.NEW_REPORT"', entries)
        self.assertIn('"il.sidewalks.reporter://report/new"', entries)
        compat = (ROOT / f'app/src/main/java/{NEW}/compat/LegacyEntryComponents.kt').read_text()
        self.assertIn('class MainActivity : io.github.reihlinstinct.reporter.MainActivity()', compat)
        self.assertIn('class ReportTileService : io.github.reihlinstinct.reporter.ReportTileService()', compat)
        tile = (ROOT / f'app/src/main/java/{NEW}/ReportTileService.kt').read_text()
        self.assertIn('Intent(this, il.sidewalks.reporter.MainActivity::class.java)', tile)

    def test_ledger_identity_and_filename_remain_stable(self):
        schema = json.loads((ROOT / 'app/schemas/io.github.reihlinstinct.reporter.ledger.ReportDatabase/1.json').read_text())
        self.assertEqual(1, schema['database']['version'])
        self.assertEqual('54340c9bf76fd96e24f7fd16eee41624', schema['database']['identityHash'])
        shell = (ROOT / f'app/src/main/java/{NEW}/ui/AppShell.kt').read_text()
        self.assertIn('ReportDatabase::class.java, "report-ledger.db"', shell)
