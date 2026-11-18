"""One inactive supervised API trial. No retry/resume/fallback or implicit claim reset."""
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.request

from live_batch_input import need, no_duplicates
from live_batch_hybrid import BridgeAdapter
import stat
import subprocess
import zipfile

FIXED_REPOSITORY = 'reihlinstinct/parking-reports'
MAX_RECORD = 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encrypt_record(record, password):
    need(isinstance(password, bytes) and len(password) >= 16, 'trial encryption unavailable')
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    from cryptography.hazmat.primitives import hashes
    data = json.dumps(record, ensure_ascii=False, sort_keys=True, allow_nan=False).encode()
    need(len(data) <= MAX_RECORD, 'trial record too large')
    salt, nonce = os.urandom(16), os.urandom(12)
    key = PBKDF2HMAC(algorithm=hashes.SHA256(), length=32, salt=salt, iterations=200000).derive(password)
    return b'api-trial-v1\x00' + salt + nonce + AESGCM(key).encrypt(nonce, data, b'api-trial-record-v1')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('trial redirects refused')


class PrivateTrialClaim:
    """Fixed private destination; initial create has no SHA, updates require exact CAS.

    Any failure poisons this instance. Existing records refuse, never auto-resume.
    A timeout can mean the claim landed; it must not be repeated automatically.
    """
    def __init__(self, original_hash, token, password, opener=None):
        need(re.fullmatch('[0-9a-f]{64}', original_hash or ''), 'invalid original identity')
        need(isinstance(token, str) and bool(token), 'trial private credential unavailable')
        self.url = 'https://api.github.com/repos/' + FIXED_REPOSITORY + '/contents/api-trials/' + original_hash + '.aesgcm'
        self.token, self.password, self.revision, self.poisoned = token, password, None, False
        self.opener = opener or urllib.request.build_opener(NoRedirect())

    def save(self, record):
        need(not self.poisoned, 'trial claim held after uncertain persistence')
        try:
            blob = encrypt_record(record, self.password)
            payload = dict(message='Persist encrypted supervised trial state [skip ci]', branch='main',
                           content=base64.b64encode(blob).decode())
            if self.revision is not None:
                payload['sha'] = self.revision
            request = urllib.request.Request(self.url, data=json.dumps(payload).encode(), method='PUT', headers={
                'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
                'Content-Type': 'application/json', 'X-GitHub-Api-Version': '2022-11-28'})
            with self.opener.open(request, timeout=45) as response:
                raw = response.read(MAX_RECORD + 1)
                need(len(raw) <= MAX_RECORD, 'trial acknowledgement too large')
                result = json.loads(raw)
            expected = hashlib.sha1(b'blob ' + str(len(blob)).encode() + b'\x00' + blob).hexdigest()
            need(result.get('content', {}).get('sha') == expected, 'trial persistence not acknowledged')
            self.revision = expected
        except BaseException:
            self.poisoned = True
            raise ValueError('trial claim unavailable or already held; reconcile before continuing') from None


def attachable_receipt(case):
    """The exact returned ID pair, including zero CRM plus usable web ID."""
    if not isinstance(case, dict):
        return False
    crm, web = case.get('CaseIdInCRM'), case.get('WebCaseIdInCRM', '')
    usable = lambda value: isinstance(value, str) and bool(value.strip('0- '))
    return isinstance(crm, str) and bool(crm.strip()) and (usable(crm) or usable(web))


def execute_trial(report, original_hash, outgoing_hash, claim, api, run_id, write_guard=lambda: None):
    """Caller validates exact approved bytes and current topic before this boundary.

    Preflight may only validate/login. The durable reservation and creating intent
    acknowledge before CreateCase, attaching intent before AddFile. Claim failures
    and API uncertainty stop immediately, preserving the latest acknowledged state.
    """
    need(re.fullmatch('[0-9a-f]{64}', original_hash or '') and
         re.fullmatch('[0-9a-f]{64}', outgoing_hash or ''), 'trial identity unavailable')
    need(len(report.get('photos', [])) == 1 and report['photos'][0]['sha256'] == outgoing_hash,
         'exactly one approved outgoing photo required')
    need(api is not None and claim is not None, 'trial ports unavailable')
    api.preflight([report])
    record = dict(schemaVersion=1, originalHash=original_hash, outgoingHash=outgoing_hash,
                  report=report, runId=run_id, state='reserved')
    claim.save(record)
    record['state'] = 'creating'
    claim.save(record)
    write_guard()
    case = api.create(report)
    need(attachable_receipt(case), 'trial case receipt not confirmed')
    record.update(case=case, state='created', municipalCreateAccepted=True, noReplay=True, photoAttached=False)
    claim.save(record)
    record['state'] = 'attaching'
    claim.save(record)
    write_guard()
    need(api.attach(case, report, report['photos'][0]) is True, 'trial attachment not confirmed')
    record.update(state='complete', photoAttached=True)
    claim.save(record)
    return record


def validate_package(path, env):
    need(path.stat().st_size <= 30 * 1024 * 1024, 'trial package too large')
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = {'trial.json', 'original.jpg', 'photos/approved.jpg'}
        need(len(entries) == 3 and {e.filename for e in entries} == names, 'trial package inventory invalid')
        limits = {'trial.json': 65536, 'original.jpg': 25 * 1024 * 1024, 'photos/approved.jpg': 1100000}
        for entry in entries:
            need(not entry.is_dir() and stat.S_IFMT(entry.external_attr >> 16) in (0, stat.S_IFREG)
                 and not entry.flag_bits & 1 and 0 < entry.file_size <= limits[entry.filename], 'invalid trial entry')
        raw = archive.read('trial.json')
        original = archive.read('original.jpg')
        outgoing = archive.read('photos/approved.jpg')
    need(digest(raw) == env['APPROVED_TRIAL_JSON_SHA256'], 'trial text/location approval mismatch')
    need(digest(original) == env['APPROVED_ORIGINAL_SHA256'], 'trial original mismatch')
    need(digest(outgoing) == env['APPROVED_OUTGOING_SHA256'], 'trial derivative mismatch')
    need(original.startswith(b'\xff\xd8') and outgoing.startswith(b'\xff\xd8'), 'trial images must be JPEG')
    trial = json.loads(raw, object_pairs_hook=no_duplicates)
    need(set(trial) == {'report', 'originalSha256', 'outgoingSha256'}, 'invalid trial fields')
    need(trial['originalSha256'] == digest(original) and trial['outgoingSha256'] == digest(outgoing), 'trial identities mismatch')
    report = trial['report']
    need(set(report) == {'reportId', 'text', 'subjectCode', 'latitude', 'longitude', 'locationDescription', 'photos'}, 'invalid trial report')
    need(report['photos'] == [{'path': 'photos/approved.jpg', 'sha256': digest(outgoing)}], 'trial photo mismatch')
    validate_trial_coordinates(report)
    return trial, outgoing


def validate_activation(env, now=None):
    from datetime import datetime, timezone
    config = json.loads((Path(__file__).parent / 'supervised_trial_activation.json').read_text())
    now = now or datetime.now(timezone.utc)
    need(now < datetime.fromisoformat(config['expiresAt']), 'single-trial authorization expired')
    for key, field in [('BATCH_PRIVATE_REF', 'privateCommit'), ('BATCH_PRIVATE_PATH', 'batchPath'),
                       ('BATCH_APPROVED_OUTER_SHA256', 'outerSha256')]:
        need(env.get(key) == config[field], 'not the one approved trial package')


class Routed:
    """Production route/expiry boundary; expiry limits initiation, never remote completion."""
    def __init__(self, api, route_check, env, clock=None):
        self.api, self.route_check, self.env, self.clock = api, route_check, env, clock

    def preflight(self, reports):
        self.route_check()
        return self.api.preflight(reports)

    def _before_write(self):
        self.route_check()
        validate_activation(self.env, self.clock() if self.clock else None)

    def create(self, report):
        self._before_write()
        return self.api.create(report)

    def attach(self, case, report, photo):
        self._before_write()
        return self.api.attach(case, report, photo)


def validate_trial_coordinates(report):
    import math
    lat, lon = report['latitude'], report['longitude']
    need(type(lat) in (int, float) and type(lon) in (int, float) and
         math.isfinite(lat) and math.isfinite(lon) and
         31.65 <= lat <= 31.95 and 35.05 <= lon <= 35.35, 'trial coordinate envelope refused')
    need(report['subjectCode'] == '1137', 'only approved crosswalk topic permitted')


def main():
    root = Path('/tmp/supervised-api-trial')
    api = None
    try:
        need(os.environ['GITHUB_REF'] == 'refs/heads/main' and os.environ['SUPERVISED_API_TRIAL'] == 'GPS_ONLY_ONCE', 'trial disabled')
        # This flag is an explicit dispatch binding, not proof of authority on its own.
        need(os.environ['TRIAL_OTHER_WRITERS_FENCED'] == 'CONFIRMED', 'other writers must be fenced')
        for name in ('APPROVED_TRIAL_JSON_SHA256', 'APPROVED_ORIGINAL_SHA256', 'APPROVED_OUTGOING_SHA256', 'BATCH_APPROVED_OUTER_SHA256'):
            need(re.fullmatch('[0-9a-f]{64}', os.environ[name]), 'invalid trial approval binding')
        validate_activation(os.environ)
        os.umask(0o077)
        root.mkdir(mode=0o700, exist_ok=False)
        from fetch_live_batch import stage
        need(os.environ['BATCH_PRIVATE_PATH'].endswith('.zip.enc'), 'encrypted trial input required')
        stage(root / 'input.zip.enc', encrypted_only=True)
        need((root / 'input.zip.enc').stat().st_size <= 30 * 1024 * 1024, 'trial envelope too large')
        subprocess.run(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-md', 'sha256', '-iter', '200000',
                        '-in', str(root / 'input.zip.enc'), '-out', str(root / 'input.zip'), '-pass', 'env:TRIAL_DATA_KEY'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
        trial, photo = validate_package(root / 'input.zip', os.environ)
        (root / 'photos').mkdir(mode=0o700)
        (root / 'photos/approved.jpg').write_bytes(photo)
        # Reuse the existing transport's DNS/route verifier. This sends no HTTP request.
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from trial.l2tp import verify_municipal_routes
        verify_municipal_routes()
        classpath = Path('app/build/batch-bridge/classpath.txt').read_text().strip()
        need(bool(classpath), 'compiled production bridge unavailable')
        # Child receives only municipal identity, never private repository/encryption credentials.
        keys = ('PATH', 'JAVA_HOME', 'SUPERVISED_API_TRIAL', 'J106_SUBSCRIPTION_KEY', 'J106_APP_USERNAME',
                'J106_APP_PASSWORD', 'REPORTER_FIRST_NAME', 'REPORTER_LAST_NAME', 'REPORTER_PHONE',
                'REPORTER_ID_NUMBER', 'REPORTER_EMAIL')
        env = {key: os.environ[key] for key in keys if key in os.environ}
        env['BATCH_PRIVATE_ROOT'] = str(root)
        with (root / 'bridge-private.stderr').open('wb') as error:
            api = BridgeAdapter(['java', '-cp', classpath,
                'io.github.reihlinstinct.reporter.municipal.SupervisedApiTrialBridge'], env, error)
            claim = PrivateTrialClaim(trial['originalSha256'], os.environ['BATCH_PRIVATE_CONTENTS_TOKEN'],
                                      os.environ['TRIAL_DATA_KEY'].encode())
            record = execute_trial(trial['report'], trial['originalSha256'], trial['outgoingSha256'],
                                   claim, Routed(api, verify_municipal_routes, os.environ), os.environ['GITHUB_RUN_ID'],
                                   write_guard=lambda: validate_activation(os.environ))
            (root / 'result.json.enc').write_bytes(encrypt_record(record, os.environ['TRIAL_DATA_KEY'].encode()))
        print('SUPERVISED_API_TRIAL_COMPLETE_RECEIPT_ENCRYPTED')
        return 0
    except BaseException:
        print('SUPERVISED_API_TRIAL_STOPPED_RECONCILE_NO_REPLAY')
        return 1
    finally:
        if api is not None:
            api.close()
        # The workflow always-cleanup removes the remaining private tree.


if __name__ == '__main__':
    raise SystemExit(main())
