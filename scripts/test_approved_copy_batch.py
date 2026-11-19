import json
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
class ApprovedCopyBatchTest(unittest.TestCase):
    def test_complete_french_and_approved_source(self):
        a=json.loads((ROOT/'app/src/main/assets/i18n/strings.json').read_text());s=a['strings']
        self.assertEqual('french_integer',next(l for l in a['languages']if l['code']=='fr')['plural'])
        self.assertEqual('הניתוח נעשה במכשיר בלבד',s['shell.home.on_device_note']['he'])
        self.assertEqual('תרגום מכונה, עשויות להיות טעויות.',s['settings.language.machine_notice']['he'])
        for k,row in s.items():
            self.assertEqual({l['code']for l in a['languages']},set(row),k)
            self.assertTrue(row['fr'],k)
            if isinstance(row['fr'],dict):self.assertIn('many',row['fr'],k)
        for k,row in s.items():
            if k.startswith('registry.tag.'):
                self.assertIn('карта',row['ru'].lower())
                self.assertIn('ካርድ',row['am'])
    def test_french_android_resource_apostrophes_are_escaped(self):
        import re
        import xml.etree.ElementTree as ET
        for path in (ROOT / 'app/src/main/res/values-fr').glob('*.xml'):
            for value in ET.parse(path).getroot().iter('string'):
                text = ''.join(value.itertext())
                self.assertIsNone(re.search(r"(?<!\\)'", text), str(path))

    def test_explicit_passport_delete_is_fresh_authenticated_and_isolated(self):
        shell=(ROOT/'app/src/main/java/io/github/reihlinstinct/reporter/ui/AppShell.kt').read_text()
        block=shell.split('deleteSavedPassport = { cleared ->',1)[1].split('}, save =',1)[0]
        self.assertLess(block.index('authenticate('),block.index('val current = readSettings()'))
        self.assertIn('current.copy(passportNumber = "")',block)
        self.assertLess(block.index('saveSettings(updated)'),block.index('cleared()'))
        for forbidden in ('mergeReporterSettings','draftRepo','ledger','deleteRecursively','submitReport'):
            self.assertNotIn(forbidden,block)
        screen=(ROOT/'app/src/main/java/io/github/reihlinstinct/reporter/ui/SettingsScreen.kt').read_text()
        self.assertIn('confirmPassportDelete) AlertDialog(',screen)
        self.assertIn('deleteSavedPassport?.invoke',screen)
    def test_gps_permission_is_after_rationale_and_manual_path_remains(self):
        text=(ROOT/'app/src/main/java/io/github/reihlinstinct/reporter/ui/ReviewScreen.kt').read_text()
        self.assertEqual(1,text.count('locationPermission.launch('))
        self.assertIn('if (showLocationRationale) AlertDialog(',text)
        self.assertIn('else showLocationRationale = true',text)
        self.assertIn('review.location.permission_manual',text)
        self.assertIn('OutlinedTextField(manualStreet',text)
if __name__ == '__main__':unittest.main()
