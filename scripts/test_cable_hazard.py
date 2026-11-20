"""Synthetic package boundary tests, never municipal calls."""
import copy, hashlib, json, tempfile, unittest, zipfile
from pathlib import Path
from two_car_ci import validate_package

class CableHazardTest(unittest.TestCase):
    def test_exact_mode_path_and_all_digests_required(self):
        photo=b'\xff\xd8synthetic';h=lambda x:hashlib.sha256(x).hexdigest()
        report=dict(reportId='synthetic',text='synthetic cable 012-34-567',subjectCode='5164',plate='01234567',streetCode='1',streetName='synthetic',houseNumber='2',coordinateX=220000,coordinateY=630000,photos=[dict(path='photos/approved.jpg',sha256=h(photo))],eventType='charging_cable_sidewalk_hazard',subjectPath=['כבישים מדרכות','אחר'])
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'input.zip'
            def package(r):
                raw=json.dumps(dict(report=r,originalSha256=h(photo),outgoingSha256=h(photo))).encode()
                pin=dict(originalSha256=h(photo),outgoingSha256=h(photo),trialSha256=h(raw))
                with zipfile.ZipFile(path,'w') as z:
                    for n,b in [('trial.json',raw),('original.jpg',photo),('photos/approved.jpg',photo)]:z.writestr(n,b)
                return pin
            pin=package(report)
            self.assertEqual(validate_package(path,pin,cable_hazard=True)[0]['report']['plate'],'01234567')
            with self.assertRaises(ValueError):validate_package(path,pin)
            for mutation in [dict(subjectCode='1145'),dict(subjectPath=['חניה אסורה','אחר']),dict(eventType='parking'),dict(text='changed no identifier'),dict(subjectPath=[])]:
                pin=package(dict(report,**mutation))
                with self.assertRaises(ValueError):validate_package(path,pin,cable_hazard=True)
            missing=copy.deepcopy(report);del missing['subjectPath'];pin=package(missing)
            with self.assertRaises(ValueError):validate_package(path,pin,cable_hazard=True)
            pin=package(report);pin['trialSha256']='f'*64
            with self.assertRaises(ValueError):validate_package(path,pin,cable_hazard=True)

if __name__=='__main__':unittest.main()
