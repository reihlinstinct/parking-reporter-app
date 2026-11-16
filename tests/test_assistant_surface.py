import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = '{http://schemas.android.com/apk/res/android}'
SOURCE = ROOT / 'app/src/main/java/io/github/reihlinstinct/reporter/assistant'


class AssistantSurfaceTest(unittest.TestCase):
    def test_only_narrow_default_disabled_function(self):
        source = (SOURCE / 'ReportAppFunctions.kt').read_text() + (SOURCE / 'BaseReportAppFunctionService.kt').read_text()
        self.assertEqual(2, source.count('@AppFunctionDeclaration('))
        self.assertIn('@AppFunctionDeclaration(isEnabled = false, isDescribedByKDoc = true)', source)
        self.assertIn('fun startNewReport(): PendingIntent = AssistantEntry.entryAction(this)', source)
        # Issue #190 first slice: one typed category function, same consent gate, no free text.
        self.assertIn('fun startNewReportInCategory(categoryCode: String): PendingIntent = AssistantEntry.categoryEntryAction(this, categoryCode)', source)
        self.assertEqual(2, source.count('if (!AssistantEntryStore(context).enabled())'))
        self.assertIn('?: throw AppFunctionInvalidArgumentException("Unknown report category")', source)
        self.assertEqual(2, source.count('isEnabled = false'))
        self.assertIn('getBoolean("enabled", false)', source)
        self.assertIn('PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE', source)
        for forbidden in ['putExtra(', 'MunicipalClient', 'SettingsProfileStore', 'ReportDatabase', 'ServerSocket']:
            self.assertNotIn(forbidden, source)

    def test_category_hint_is_one_shot_and_consent_gated(self):
        shell = (ROOT / 'app/src/main/java/io/github/reihlinstinct/reporter/ui/AppShell.kt').read_text()
        # Cleared on every page except the camera, read only by a camera capture, never by startReview itself.
        self.assertIn('LaunchedEffect(page) { if (page != "camera") pendingCategory = null }', shell)
        self.assertIn('val hint = pendingCategory; pendingCategory = null; startReview(file, categoryHint = hint)', shell)
        self.assertNotIn('pendingCategory?.let', shell)
        self.assertIn('Button(onClick = { pendingCategory = null; requestCamera', shell)
        # The consent switch gates the hint for every sender, including a hand-built intent.
        self.assertIn('AssistantEntryStore(context).enabled()) request.categoryCode else null', shell)
        entry = (ROOT / 'app/src/main/java/io/github/reihlinstinct/reporter/EntryIntents.kt').read_text()
        self.assertIn('action == ACTION_TILE_NEW_REPORT && knownCategory(dataString) != null', entry)
        self.assertNotIn('ACTION_TILE_NEW_REPORT || action == Intent.ACTION_VIEW', entry)

    def test_system_protected_service_and_old_android_disabled(self):
        manifest = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml')
        service = next(s for s in manifest.findall('./application/service')
            if s.get(A + 'name').endswith('.assistant.ReportAppFunctionService'))
        self.assertEqual('android.permission.BIND_APP_FUNCTION_SERVICE', service.get(A + 'permission'))
        self.assertEqual('@bool/appfunctions_supported', service.get(A + 'enabled'))
        for folder, value in [('values', 'false'), ('values-v36', 'true')]:
            resource = ET.parse(ROOT / f'app/src/main/res/{folder}/appfunctions.xml')
            self.assertEqual(value, resource.find('./bool').text)

    def test_disable_rechecks_local_consent_and_enable_needs_system_success(self):
        source = (SOURCE / 'ReportAppFunctions.kt').read_text()
        self.assertLess(source.index('if (!enabled) store.save(false)'), source.index('manager.setAppFunctionEnabled'))
        self.assertLess(source.index('manager.setAppFunctionEnabled'), source.index('store.save(enabled)'))
        self.assertIn('if (!supported()) return false', source)
