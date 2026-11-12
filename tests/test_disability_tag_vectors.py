import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
A = '{http://schemas.android.com/apk/res/android}'
NAMES = ['disability_tag_triangle', 'disability_tag_wheelchair']


def luminance(color):
    values = [int(color[i:i+2], 16) / 255 for i in (1, 3, 5)]
    values = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in values]
    return sum(a*b for a, b in zip(values, [.2126, .7152, .0722]))


class DisabilityTagVectorTest(unittest.TestCase):
    def test_svg_and_android_geometry_match_without_tint_or_mirroring(self):
        for name in NAMES:
            svg = ET.parse(ROOT / f'docs/assets/{name}.svg').getroot()
            xml = ET.parse(ROOT / f'app/src/main/res/drawable/ic_{name}.xml').getroot()
            self.assertEqual('0 0 64 64', svg.attrib['viewBox'])
            self.assertEqual('false', xml.attrib[A+'autoMirrored'])
            self.assertNotIn(A+'tint', xml.attrib)
            svg_paths = [(e.attrib['fill'], e.attrib['d']) for e in svg.iter() if e.tag.endswith('path')]
            self.assertEqual(svg_paths, [(e.attrib[A+'fillColor'], e.attrib[A+'pathData']) for e in xml.iter() if e.tag.endswith('path')])
            group = next(e for e in svg if e.tag.endswith('g'))
            self.assertEqual('translate(5.12 5.12) scale(0.84)', group.attrib['transform'])
            android_group = next(e for e in xml if e.tag.endswith('group'))
            self.assertEqual(['5.12', '5.12', '0.84', '0.84'],
                [android_group.attrib[A+k] for k in ['translateX', 'translateY', 'scaleX', 'scaleY']])
            self.assertEqual('#FFFFFF', svg_paths[0][0])
            self.assertTrue(any(e.tag.endswith('title') and e.text for e in svg))

    def test_contrast_and_five_language_labels(self):
        for color in ['#12449A', '#087F5B', '#17231F']:
            self.assertGreaterEqual(1.05 / (luminance(color) + .05), 4.5)
        strings = json.loads((ROOT / 'app/src/main/assets/i18n/strings.json').read_text())['strings']
        for key in ['registry.tag.triangle_icon', 'registry.tag.wheelchair_icon']:
            self.assertEqual({'he', 'en', 'ar', 'ru', 'am'}, set(strings[key]))
            self.assertTrue(all(strings[key].values()))
