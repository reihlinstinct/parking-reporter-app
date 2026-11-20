"""One approved submission from an ephemeral exact-main branch, never a batch."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.request
from datetime import datetime, timezone

from live_batch_input import need
from supervised_api_trial import PrivateTrialClaim, encrypt_record
from two_car_ci import execute, validate_package

REPOSITORY = 'reihlinstinct/parking-reporter-app'
REPOSITORY_ID = '1411863751'
OWNER_ID = '337939899'
GOOD = {'success', 'skipped'}
REQUIRED_CHECKS = {'build', 'emulator', 'emulator (29)', 'emulator (30)', 'emulator (36)',
                   'standalone', 'Scaffolding and policy checks', 'Python tooling smoke test',
                   'Live staging regression (first-install files dir)', 'layout',
                   'Scorecard regression guard'}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise ValueError('redirect refused')


def github_read(path, env):
    # Only fixed GitHub repository endpoints. No user-supplied host or URL.
    request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + path,
        headers={'Authorization': 'Bearer ' + env['GITHUB_TOKEN'],
                 'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'})
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=45) as response:
        raw = response.read(2 * 1024 * 1024 + 1)
    need(len(raw) <= 2 * 1024 * 1024, 'source response too large')
    return json.loads(raw)


def pin_inputs(env, now=None):
    now = now or datetime.now(timezone.utc)
    need(env.get('GITHUB_REPOSITORY') == REPOSITORY and env.get('GITHUB_REPOSITORY_ID') == REPOSITORY_ID,
         'repository refused')
    need(env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch' and env.get('GITHUB_RUN_ATTEMPT') == '1',
         'only a new manual dispatch is allowed')
    need(env.get('GITHUB_ACTOR_ID') == OWNER_ID and env.get('GITHUB_TRIGGERING_ACTOR') == 'reihlinstinct',
         'actor refused')
    need(env.get('SUBMISSION_CONFIRM') in {'FILE_ONE_APPROVED_CASE', 'FILE_ONE_APPROVED_CABLE_HAZARD', 'FILE_ONE_APPROVED_ABANDONED_BICYCLE'} and
         env.get('TRIAL_OTHER_WRITERS_FENCED') == 'CONFIRMED', 'approval or writer fence missing')
    sha = env.get('GITHUB_SHA', '')
    need(re.fullmatch('[0-9a-f]{40}', sha), 'source SHA refused')
    original = env.get('APPROVED_ORIGINAL_SHA256', '')
    need(re.fullmatch('[0-9a-f]{64}', original), 'original identity refused')
    # A branch belongs to precisely one original photo; no text or plate in its name.
    need(env.get('GITHUB_REF') == 'refs/heads/submission/' + original[:24], 'submission branch refused')
    expires = datetime.fromisoformat(env.get('SUBMISSION_EXPIRES_AT', ''))
    need(expires.utcoffset() is not None and 0 < (expires - now).total_seconds() <= 21600,
         'approval expired or exceeds six hours')
    pin = dict(privateCommit=env.get('BATCH_PRIVATE_REF', ''), batchPath=env.get('BATCH_PRIVATE_PATH', ''),
               outerSha256=env.get('BATCH_APPROVED_OUTER_SHA256', ''),
               trialSha256=env.get('APPROVED_TRIAL_JSON_SHA256', ''), originalSha256=original,
               outgoingSha256=env.get('APPROVED_OUTGOING_SHA256', ''))
    need(re.fullmatch('[0-9a-f]{40}', pin['privateCommit']), 'immutable private ref required')
    need(re.fullmatch(r'batches/[A-Za-z0-9][A-Za-z0-9_-]{0,79}/input\.zip\.enc', pin['batchPath']),
         'private package path refused')
    for key in ('outerSha256', 'trialSha256', 'outgoingSha256'):
        need(re.fullmatch('[0-9a-f]{64}', pin[key]), 'approval digest refused')
    return pin


def source_guard(env, read=github_read):
    pin = pin_inputs(env)
    main = read('/git/ref/heads/main', env)
    branch = read('/git/ref/heads/submission/' + pin['originalSha256'][:24], env)
    need(main['object']['sha'] == env['GITHUB_SHA'] == branch['object']['sha'], 'branch is not exact current main')
    checks = read('/commits/' + env['GITHUB_SHA'] + '/check-runs?per_page=100', env)
    rows = checks.get('check_runs', [])
    need(checks.get('total_count') == len(rows) and 0 < len(rows) <= 100, 'incomplete main check evidence')
    # This workflow's own checks are necessarily running. Everything else must
    # already have passed on the main commit. The legacy independent-review
    # commit status is not technical CI and is intentionally not read here.
    rows = [c for c in rows if c.get('name') not in {'submission-source', 'submit-one'}]
    names = {c['name'] for c in rows}
    need(REQUIRED_CHECKS <= names, 'main technical checks missing')
    need(all(c.get('status') == 'completed' and c.get('conclusion') in GOOD for c in rows),
         'main technical CI is not green')
    return pin


def bridge_mode(confirm):
    modes = {'FILE_ONE_APPROVED_CASE': 'TWO_CAR_ONCE',
             'FILE_ONE_APPROVED_CABLE_HAZARD': 'CABLE_HAZARD_ONCE',
             'FILE_ONE_APPROVED_ABANDONED_BICYCLE': 'ABANDONED_BICYCLE_ONCE'}
    need(confirm in modes, 'case mode refused')
    return modes[confirm]


def main():
    import sys
    env = os.environ
    if sys.argv[1:] == ['--source-only']:
        try:
            source_guard(env)
            print('SUBMISSION_SOURCE_VERIFIED')
            return 0
        except BaseException:
            print('SUBMISSION_SOURCE_REFUSED')
            return 1
    root = Path('/tmp/supervised-api-trial')
    api = None
    try:
        need(not sys.argv[1:], 'unsupported arguments')
        pin = source_guard(env)
        os.umask(0o077)
        root.mkdir(mode=0o700, exist_ok=False)
        from fetch_live_batch import stage
        stage(root / 'input.zip.enc', env=env, encrypted_only=True)
        subprocess.run(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-md', 'sha256', '-iter', '200000',
                        '-in', str(root / 'input.zip.enc'), '-out', str(root / 'input.zip'), '-pass', 'env:TRIAL_DATA_KEY'],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
        # Parking stays unchanged. Cable hazards require a distinct dispatch confirmation
        # and an exact event/category path, verified live again before any case write.
        cable_hazard = env['SUBMISSION_CONFIRM'] == 'FILE_ONE_APPROVED_CABLE_HAZARD'
        abandoned_bicycle = env['SUBMISSION_CONFIRM'] == 'FILE_ONE_APPROVED_ABANDONED_BICYCLE'
        trial, photo = validate_package(root / 'input.zip', pin, cable_hazard=cable_hazard, abandoned_bicycle=abandoned_bicycle)
        (root / 'photos').mkdir(mode=0o700)
        (root / 'photos/approved.jpg').write_bytes(photo)
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        from trial.l2tp import verify_municipal_routes
        def guard():
            verify_municipal_routes()
            source_guard(env)
        guard()
        keys = ('PATH', 'JAVA_HOME', 'J106_SUBSCRIPTION_KEY', 'J106_APP_USERNAME', 'J106_APP_PASSWORD',
                'REPORTER_FIRST_NAME', 'REPORTER_LAST_NAME', 'REPORTER_PHONE', 'REPORTER_ID_NUMBER', 'REPORTER_EMAIL')
        child = {k: env[k] for k in keys if k in env}
        child.update(CAR_CI_AUTHORIZED=bridge_mode(env['SUBMISSION_CONFIRM']), BATCH_PRIVATE_ROOT=str(root))
        from live_batch_hybrid import BridgeAdapter
        cp = Path('app/build/batch-bridge/classpath.txt').read_text().strip()
        need(bool(cp), 'production bridge unavailable')
        with (root / 'bridge-private.stderr').open('wb') as error:
            api = BridgeAdapter(['java', '-cp', cp, 'io.github.reihlinstinct.reporter.municipal.TwoCarCiBridge'], child, error)
            claim = PrivateTrialClaim(pin['originalSha256'], env['BATCH_PRIVATE_CONTENTS_TOKEN'], env['TRIAL_DATA_KEY'].encode())
            result = execute(trial['report'], pin, claim, api, guard, env['GITHUB_RUN_ID'])
            (root / 'result.json.enc').write_bytes(encrypt_record(result, env['TRIAL_DATA_KEY'].encode()))
        print('ONE_APPROVED_CASE_COMPLETE_ENCRYPTED_RECEIPT_NO_REPLAY')
        return 0
    except BaseException:
        print('SUBMISSION_STOPPED_RECONCILE_NO_REPLAY')
        return 1
    finally:
        if api is not None:
            api.close()


if __name__ == '__main__':
    raise SystemExit(main())
