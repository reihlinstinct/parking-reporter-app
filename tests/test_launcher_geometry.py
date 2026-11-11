"""Owner-selected launcher v5 geometry, including themed-mask parity."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as E

ROOT=Path(__file__).resolve().parents[1]
A='{http://schemas.android.com/apk/res/android}'
class LauncherGeometryTests(unittest.TestCase):
    def test_adaptive_layers_share_geometry_and_equal_white_strokes(self):
        layers=[]
        for name in ['ic_launcher_foreground','ic_launcher_monochrome']:
            root=E.parse(ROOT/f'app/src/main/res/drawable/{name}.xml').getroot()
            self.assertEqual((root.get(A+'viewportWidth'),root.get(A+'viewportHeight')),('108','108'))
            paths=list(root);self.assertEqual(len(paths),5)
            self.assertEqual([p.get(A+'strokeWidth') for p in [paths[1],paths[3],paths[4]]],['3.66']*3)
            layers.append([p.get(A+'pathData') for p in paths])
        self.assertEqual(layers[0],layers[1])
        foreground=E.parse(ROOT/'app/src/main/res/drawable/ic_launcher_foreground.xml').getroot()
        self.assertEqual(foreground[2].get(A+'fillColor'),'#D8A038')

    def test_round_and_regular_launchers_use_same_layers(self):
        for name in ['ic_launcher','ic_launcher_round']:
            root=E.parse(ROOT/f'app/src/main/res/mipmap-anydpi-v26/{name}.xml').getroot()
            self.assertEqual(root.find('foreground').get(A+'drawable'),'@drawable/ic_launcher_foreground')
            self.assertEqual(root.find('monochrome').get(A+'drawable'),'@drawable/ic_launcher_monochrome')
