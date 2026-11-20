"""Synthetic bicycle package tests. Never municipal calls."""
import copy, hashlib, json, tempfile, unittest, zipfile
from pathlib import Path
from two_car_ci import validate_package

class BicycleTest(unittest.TestCase):
    def test_null_plate_description_exact_mode_and_digests(self):
        photo=b'\xff\xd8synthetic';h=lambda x:hashlib.sha256(x).hexdigest()
        description='Synthetic dark mountain bicycle with green frame markings locked to a tree.'
        report=dict(reportId='synthetic',text=description,subjectCode='1015',plate=None,streetCode='1',streetName='synthetic',houseNumber='2',coordinateX=220000,coordinateY=630000,photos=[dict(path='photos/approved.jpg',sha256=h(photo))],eventType='abandoned_bicycle',subjectPath=['ניקיון','גזם / גרוטאות','אשפה - גרוטאות /מקרר/מכונת כביסה'],distinguishingDescription=description)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'input.zip'
            def package(r):
                raw=json.dumps(dict(report=r,originalSha256=h(photo),outgoingSha256=h(photo))).encode()
                pin=dict(originalSha256=h(photo),outgoingSha256=h(photo),trialSha256=h(raw))
                with zipfile.ZipFile(path,'w') as z:
                    for n,b in [('trial.json',raw),('original.jpg',photo),('photos/approved.jpg',photo)]:z.writestr(n,b)
                return pin
            pin=package(report)
            self.assertIsNone(validate_package(path,pin,abandoned_bicycle=True)[0]['report']['plate'])
            for mode in ({},dict(cable_hazard=True),dict(cable_hazard=True,abandoned_bicycle=True)):
                with self.assertRaises((ValueError,TypeError)):validate_package(path,pin,**mode)
            for mutation in [dict(plate='1234567'),dict(plate=''),dict(subjectCode='1145'),dict(eventType='parking'),dict(subjectPath=['ניקיון','אחר']),dict(distinguishingDescription='short'),dict(distinguishingDescription='x'*40),dict(text=description+' סימון הפנייה'),dict(extra='unexpected')]:
                pin=package(dict(report,**mutation))
                with self.assertRaises(ValueError):validate_package(path,pin,abandoned_bicycle=True)
            # Keep the marker vocabulary in parity with TwoCarCiBridge.kt.
            for marker in ('לא נמסר לי שניתן דוח', 'סימון הפנייה', 'מחדל האכיפה'):
                pin=package(dict(report,text=description+' '+marker))
                with self.assertRaises(ValueError):validate_package(path,pin,abandoned_bicycle=True)
            for invalid in ('short', ' '*30+'short'+' '*30, 'x'*1001):
                pin=package(dict(report,text=invalid,distinguishingDescription=invalid))
                with self.assertRaises(ValueError):validate_package(path,pin,abandoned_bicycle=True)
            for field in ('plate','distinguishingDescription','subjectPath'):
                missing=copy.deepcopy(report);del missing[field];pin=package(missing)
                with self.assertRaises((ValueError,KeyError)):validate_package(path,pin,abandoned_bicycle=True)
            pin=package(report);pin['trialSha256']='f'*64
            with self.assertRaises(ValueError):validate_package(path,pin,abandoned_bicycle=True)
if __name__=='__main__':unittest.main()
