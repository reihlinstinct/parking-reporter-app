"""Two fixed owner-bound CI cases. One create each; no retry or resume."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile
from datetime import datetime, timezone
from live_batch_input import need, no_duplicates
from live_batch_hybrid import BridgeAdapter
from supervised_api_trial import PrivateTrialClaim, encrypt_record

CONFIG = Path(__file__).with_name('two_car_ci_activation.json')


def binding(env, now=None):
    cfg = json.loads(CONFIG.read_text())
    need((now or datetime.now(timezone.utc)) < datetime.fromisoformat(cfg['expiresAt']), 'case authorization expired')
    need(env.get('CAR_CI_AUTHORIZED') == 'TWO_CAR_ONCE' and env.get('TRIAL_OTHER_WRITERS_FENCED') == 'CONFIRMED', 'case fence unavailable')
    matches = [x for x in cfg['packages'] if x['slot'] == env.get('CAR_SLOT')]
    need(len(matches) == 1, 'case slot refused')
    return matches[0]


def validate_package(path, pin):
    need(path.stat().st_size <= 30 * 1024 * 1024, 'case package too large')
    with zipfile.ZipFile(path) as z:
        es = z.infolist(); names = {'trial.json', 'original.jpg', 'photos/approved.jpg'}
        need(len(es) == 3 and {x.filename for x in es} == names, 'case package inventory refused')
        for e in es:
            need(not e.is_dir() and not e.flag_bits & 1 and 0 < e.file_size <= (65536 if e.filename == 'trial.json' else 25 * 1024 * 1024), 'case entry refused')
        raw, original, photo = [z.read(x) for x in ['trial.json', 'original.jpg', 'photos/approved.jpg']]
    for rawbytes, key in [(raw, 'trialSha256'), (original, 'originalSha256'), (photo, 'outgoingSha256')]:
        need(hashlib.sha256(rawbytes).hexdigest() == pin[key], 'case approval digest mismatch')
    need(photo.startswith(b'\xff\xd8') and len(photo) <= 1100000, 'case photo refused')
    trial = json.loads(raw, object_pairs_hook=no_duplicates)
    need(set(trial) == {'report', 'originalSha256', 'outgoingSha256'}, 'case fields refused')
    need(trial['originalSha256'] == pin['originalSha256'] and trial['outgoingSha256'] == pin['outgoingSha256'], 'case identity refused')
    r = trial['report']
    need(r['subjectCode'] == '1145' and re.fullmatch('[0-9]{7,8}', r['plate']) and r['plate'] in r['text'], 'case topic/plate refused')
    need(r['photos'] == [{'path': 'photos/approved.jpg', 'sha256': pin['outgoingSha256']}], 'case photo binding refused')
    return trial, photo


def usable(value):
    return isinstance(value, str) and bool(value.strip('0- '))


def execute(report, pin, claim, api, guard, run_id):
    guard()
    api.preflight([report])  # Includes live municipal category verification.
    record = dict(schemaVersion=1, originalHash=pin['originalSha256'], outgoingHash=pin['outgoingSha256'], report=report, runId=run_id, state='reserved')
    claim.save(record)
    record['state'] = 'creating'; claim.save(record)
    guard()  # Recheck route and expiry immediately before contact/create.
    case = api.create(report)
    need(isinstance(case, dict) and any(usable(case.get(k)) for k in ['CaseIdInCRM', 'WebCaseIdInCRM']), 'case receipt uncertain')
    record.update(case=case, state='created', municipalCreateAccepted=True, noReplay=True, photoAttached=False)
    claim.save(record)  # Preserve accepted web receipt even if canonical ID is zero.
    if not usable(case.get('CaseIdInCRM')):
        record['state'] = 'accepted_photo_not_attached'; claim.save(record)
        return record
    record['state'] = 'attaching'; claim.save(record)
    guard()
    need(api.attach(case, report, report['photos'][0]) is True, 'case attachment uncertain')
    record.update(state='complete', photoAttached=True); claim.save(record)
    return record


def main():
    root = Path('/tmp/supervised-api-trial'); api = None
    try:
        need(os.environ.get('GITHUB_REF') == 'refs/heads/main', 'main only')
        pin = binding(os.environ)
        env = dict(os.environ, BATCH_PRIVATE_REF=pin['privateCommit'], BATCH_PRIVATE_PATH=pin['batchPath'], BATCH_APPROVED_OUTER_SHA256=pin['outerSha256'])
        os.umask(0o077); root.mkdir(mode=0o700, exist_ok=False)
        from fetch_live_batch import stage
        stage(root / 'input.zip.enc', env=env, encrypted_only=True)
        subprocess.run(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-md', 'sha256', '-iter', '200000', '-in', str(root / 'input.zip.enc'), '-out', str(root / 'input.zip'), '-pass', 'env:TRIAL_DATA_KEY'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
        trial, photo = validate_package(root / 'input.zip', pin)
        (root / 'photos').mkdir(mode=0o700); (root / 'photos/approved.jpg').write_bytes(photo)
        import sys
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from trial.l2tp import verify_municipal_routes
        def guard():
            verify_municipal_routes(); binding(os.environ)
        guard()
        keys = ('PATH', 'JAVA_HOME', 'CAR_CI_AUTHORIZED', 'J106_SUBSCRIPTION_KEY', 'J106_APP_USERNAME', 'J106_APP_PASSWORD', 'REPORTER_FIRST_NAME', 'REPORTER_LAST_NAME', 'REPORTER_PHONE', 'REPORTER_ID_NUMBER', 'REPORTER_EMAIL')
        child = {k: os.environ[k] for k in keys if k in os.environ}; child['BATCH_PRIVATE_ROOT'] = str(root)
        cp = Path('app/build/batch-bridge/classpath.txt').read_text().strip(); need(bool(cp), 'case bridge unavailable')
        with (root / 'bridge-private.stderr').open('wb') as error:
            api = BridgeAdapter(['java', '-cp', cp, 'io.github.reihlinstinct.reporter.municipal.TwoCarCiBridge'], child, error)
            claim = PrivateTrialClaim(pin['originalSha256'], os.environ['BATCH_PRIVATE_CONTENTS_TOKEN'], os.environ['TRIAL_DATA_KEY'].encode())
            result = execute(trial['report'], pin, claim, api, guard, os.environ['GITHUB_RUN_ID'])
            (root / 'result.json.enc').write_bytes(encrypt_record(result, os.environ['TRIAL_DATA_KEY'].encode()))
        print('FIXED_CASE_ACCEPTED_ENCRYPTED_RECEIPT_NO_REPLAY')
        return 0
    except BaseException:
        print('FIXED_CASE_STOPPED_RECONCILE_NO_REPLAY')
        return 1
    finally:
        if api is not None: api.close()


if __name__ == '__main__': raise SystemExit(main())
