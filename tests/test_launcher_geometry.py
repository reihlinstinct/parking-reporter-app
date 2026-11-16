"""Stroke-aware launcher safe-zone and complete adaptive-layer parity."""
from pathlib import Path
import math
import re
import unittest
import xml.etree.ElementTree as E
ROOT=Path(__file__).resolve().parents[1]
A='{http://schemas.android.com/apk/res/android}'

def points(path):
    tokens=re.findall(r'[MLCZ]|-?\d+(?:\.\d+)?',path)
    pos=0;cursor=None;start=None;result=[]
    def pair():
        nonlocal pos
        p=(float(tokens[pos]),float(tokens[pos+1]));pos+=2;return p
    while pos<len(tokens):
        cmd=tokens[pos];pos+=1
        if cmd=='M':cursor=pair();start=cursor;result.append(cursor)
        elif cmd=='L':cursor=pair();result.append(cursor)
        elif cmd=='C':
            a=cursor;b=pair();c=pair();d=pair()
            for n in range(201):
                t=n/200;u=1-t
                result.append(tuple(u**3*a[k]+3*u*u*t*b[k]+3*u*t*t*c[k]+t**3*d[k] for k in [0,1]))
            cursor=d
        elif cmd=='Z':cursor=start
        else:raise AssertionError('Unsupported path command')
    return result

class LauncherGeometryTests(unittest.TestCase):
    def test_stroke_aware_safe_zone_and_viewport(self):
        for name in ['foreground','monochrome']:
            root=E.parse(ROOT/f'app/src/main/res/drawable/ic_launcher_{name}.xml').getroot()
            self.assertEqual((root.get(A+'viewportWidth'),root.get(A+'viewportHeight')),('108','108'))
            for path in root:
                radius=float(path.get(A+'strokeWidth','0'))/2
                for x,y in points(path.get(A+'pathData')):
                    self.assertLessEqual(math.hypot(x-54,y-54)+radius,32.99)
                    self.assertGreaterEqual(min(x,y)-radius,0)
                    self.assertLessEqual(max(x,y)+radius,108)

    def test_full_geometry_caps_colors_and_star_parity(self):
        roots=[E.parse(ROOT/f'app/src/main/res/drawable/ic_launcher_{n}.xml').getroot() for n in ['foreground','monochrome']]
        self.assertEqual([len(r) for r in roots],[5,5])
        for index,(color,mono) in enumerate(zip(*roots)):
            attrs=[A+k for k in ['pathData','strokeWidth','strokeLineCap','strokeLineJoin']]
            self.assertEqual([color.get(k) for k in attrs],[mono.get(k) for k in attrs])
            if index!=2:
                self.assertEqual(color.get(A+'fillColor'),'#00000000')
                self.assertEqual(mono.get(A+'fillColor'),'#00000000')
                self.assertEqual(color.get(A+'strokeLineCap'),'round')
                self.assertEqual(color.get(A+'strokeLineJoin'),'round')
                self.assertEqual(mono.get(A+'strokeColor'),'#FFFFFF')
            else:
                self.assertEqual(color.get(A+'fillColor'),'#D8A038')
                self.assertEqual(mono.get(A+'fillColor'),'#FFFFFF')
        for r in roots:
            self.assertEqual([r[i].get(A+'strokeWidth') for i in [1,3,4]],['3.477']*3)

    def test_complete_round_and_regular_launcher_parity(self):
        roots=[E.parse(ROOT/f'app/src/main/res/mipmap-anydpi-v26/{n}.xml').getroot() for n in ['ic_launcher','ic_launcher_round']]
        self.assertEqual(E.tostring(roots[0]),E.tostring(roots[1]))
        for r in roots:
            self.assertEqual(r.find('foreground').get(A+'drawable'),'@drawable/ic_launcher_foreground')
            self.assertEqual(r.find('monochrome').get(A+'drawable'),'@drawable/ic_launcher_monochrome')
            self.assertEqual(r.find('background').get(A+'drawable'),'@drawable/ic_launcher_background')
if __name__=='__main__':unittest.main()
