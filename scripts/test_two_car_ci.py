"""Synthetic-only fixed-case controller boundary tests."""
import copy
import unittest
from datetime import datetime, timezone
from unittest.mock import patch
from two_car_ci import execute, binding


class Claim:
    def __init__(self, fail=None): self.rows=[]; self.fail=fail
    def save(self, row):
        if row['state'] == self.fail: raise ValueError('synthetic CAS failure')
        self.rows.append(copy.deepcopy(row))


class Api:
    def __init__(self, cid='00000000-0000-0000-0000-000000000000', reject=False): self.cid=cid; self.calls=[]; self.reject=reject
    def preflight(self, reports): self.calls.append('preflight')
    def create(self, report):
        self.calls.append('create')
        if self.reject: raise ValueError('synthetic rejection')
        return dict(CaseIdInCRM=self.cid, WebCaseIdInCRM='synthetic-web', CaseNumberInCRM=None)
    def attach(self, *args): self.calls.append('attach'); return True


class FixedCaseTest(unittest.TestCase):
    def run_case(self, claim=None, api=None, guard=lambda: None):
        c=claim or Claim(); a=api or Api();r=execute({'photos':[{'path':'synthetic'}]}, {'originalSha256':'a'*64,'outgoingSha256':'b'*64},c,a,guard,'synthetic-run');return c,a,r

    def test_zero_canonical_preserves_exact_web_receipt_and_attaches_once(self):
        c,a,r=self.run_case();self.assertEqual(a.calls,['preflight','create','attach']);self.assertEqual(r['state'],'complete');self.assertTrue(r['photoAttached']);self.assertTrue(r['noReplay']);self.assertEqual(c.rows[-1]['case']['WebCaseIdInCRM'],'synthetic-web')

    def test_valid_canonical_attaches_only_after_durable_intent(self):
        c=Claim()
        class Checked(Api):
            def create(self, report): assert c.rows[-1]['state']=='creating';return super().create(report)
            def attach(self,*args): assert c.rows[-1]['state']=='attaching';return super().attach(*args)
        _,a,r=self.run_case(c,Checked(cid='synthetic-canonical'));self.assertEqual(a.calls,['preflight','create','attach']);self.assertTrue(r['photoAttached'])

    def test_zero_crm_attachment_failure_keeps_accepted_receipt_without_retry(self):
        c=Claim()
        class FailAttach(Api):
            def attach(self,*args):
                self.calls.append('attach')
                raise ValueError('synthetic uncertain attachment')
        a=FailAttach()
        with self.assertRaises(ValueError):self.run_case(c,a)
        self.assertEqual(a.calls,['preflight','create','attach'])
        self.assertTrue(c.rows[-1]['municipalCreateAccepted'])
        self.assertTrue(c.rows[-1]['noReplay'])
        self.assertFalse(c.rows[-1]['photoAttached'])
        self.assertEqual(c.rows[-1]['state'],'attaching')

    def test_claim_failure_prevents_create(self):
        a=Api()
        with self.assertRaises(ValueError):self.run_case(Claim('creating'),a)
        self.assertEqual(a.calls,['preflight'])

    def test_post_create_persistence_failure_never_attaches(self):
        a=Api(cid='synthetic-canonical')
        with self.assertRaises(ValueError):self.run_case(Claim('created'),a)
        self.assertEqual(a.calls,['preflight','create'])

    def test_rejection_stops_no_attach_no_retry(self):
        a=Api(reject=True)
        with self.assertRaises(ValueError):self.run_case(api=a)
        self.assertEqual(a.calls,['preflight','create'])

    def test_expiry_before_create_stops_write(self):
        a=Api();n=0
        def guard():
            nonlocal n;n+=1
            if n==2:raise ValueError('expired')
        with self.assertRaises(ValueError):self.run_case(api=a,guard=guard)
        self.assertEqual(a.calls,['preflight'])

    def test_expiry_after_create_keeps_receipt_no_attach(self):
        c=Claim();a=Api(cid='synthetic-canonical');n=0
        def guard():
            nonlocal n;n+=1
            if n==3:raise ValueError('expired')
        with self.assertRaises(ValueError):self.run_case(c,a,guard)
        self.assertEqual(c.rows[-1]['state'],'attaching');self.assertEqual(c.rows[-1]['case']['WebCaseIdInCRM'],'synthetic-web');self.assertEqual(a.calls,['preflight','create'])

    def test_package_topics_preserve_explicit_vehicle_categories_only(self):
        original=b'synthetic original';photo=b'\xff\xd8synthetic jpeg'
        sha=lambda b:hashlib.sha256(b).hexdigest()
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'input.zip'
            for subject in ('1144','1145','1068','1015','1137','unknown'):
                report=dict(subjectCode=subject,plate='1234567',text='synthetic vehicle 1234567',
                    photos=[dict(path='photos/approved.jpg',sha256=sha(photo))])
                raw=json.dumps(dict(report=report,originalSha256=sha(original),outgoingSha256=sha(photo))).encode()
                with zipfile.ZipFile(path,'w') as z:
                    z.writestr('trial.json',raw);z.writestr('original.jpg',original);z.writestr('photos/approved.jpg',photo)
                pin=dict(trialSha256=sha(raw),originalSha256=sha(original),outgoingSha256=sha(photo))
                if subject in ('1145','1068'):
                    self.assertEqual(subject,validate_package(path,pin)[0]['report']['subjectCode'])
                else:
                    with self.assertRaises(ValueError):validate_package(path,pin)

    def test_abandoned_vehicle_still_requires_a_real_plate_in_text(self):
        original=b'synthetic original';photo=b'\xff\xd8synthetic jpeg'
        sha=lambda b:hashlib.sha256(b).hexdigest()
        with tempfile.TemporaryDirectory() as d:
            path=Path(d)/'input.zip'
            for plate,text in [('', 'no plate'), ('1234567', 'not identified'), ('unknown','unknown')]:
                report=dict(subjectCode='1068',plate=plate,text=text,
                    photos=[dict(path='photos/approved.jpg',sha256=sha(photo))])
                raw=json.dumps(dict(report=report,originalSha256=sha(original),outgoingSha256=sha(photo))).encode()
                with zipfile.ZipFile(path,'w') as z:
                    z.writestr('trial.json',raw);z.writestr('original.jpg',original);z.writestr('photos/approved.jpg',photo)
                pin=dict(trialSha256=sha(raw),originalSha256=sha(original),outgoingSha256=sha(photo))
                with self.assertRaises(ValueError):validate_package(path,pin)

    def test_activation_refuses_unknown_slot_and_expiry(self):
        env={'CAR_CI_AUTHORIZED':'TWO_CAR_ONCE','TRIAL_OTHER_WRITERS_FENCED':'CONFIRMED','CAR_SLOT':'car1'}
        self.assertEqual(binding(env,datetime(2026,11,18,13,tzinfo=timezone.utc))['slot'],'car1')
        for mutation in [{'CAR_SLOT':'other'},{'TRIAL_OTHER_WRITERS_FENCED':'NO'}]:
            with self.assertRaises(ValueError):binding(dict(env,**mutation),datetime(2026,11,18,13,tzinfo=timezone.utc))
        with self.assertRaises(ValueError):binding(env,datetime(2026,11,18,16,tzinfo=timezone.utc))


if __name__=='__main__':unittest.main()
