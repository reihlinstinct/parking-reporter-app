#!/usr/bin/env python3
"""Read-only proof for retrying one confirmed pre-case live failure. Fail closed."""
import argparse
import io
import json
import os
import re
import subprocess
import tarfile
import tempfile
import urllib.error
import urllib.request
import zipfile

MAX_ARCHIVE = 40 * 1024 * 1024
MAX_TEXT = 1024 * 1024
PRE_CASE = 'LIVE_E2E SUBMIT_FAILED_PRE_CASE'
ASSERTION = 'submit failed before case creation - nothing reached the city'


def need(condition):
    if not condition:
        raise ValueError('pre-case retry proof unavailable')


def prove(record, run_id, text_digest, photo_digest, log, bundle):
    """Pure bounded verification over same-run provider artifacts; never creates a case."""
    need(str(record.get('runId')) == str(run_id))
    need(record.get('approvedSha256') == text_digest)
    need(record.get('approvedPhotoSha256') == photo_digest)
    need(record.get('outcome') == 'aborted_after_send_tap')
    need(isinstance(log, bytes) and 0 < len(log) <= MAX_TEXT)
    lines = log.decode('utf-8', errors='strict').splitlines()
    markers = [line.split(' LIVE_E2E ', 1)[1] for line in lines if ' LIVE_E2E ' in line]
    need(markers.count('START') == 1 and markers.count('DIGEST_OK') == 1)
    need(markers.count('SUBMIT_FAILED_PRE_CASE') == 1)
    need(markers.count('SUBMIT_FAILED') == 1)
    need(sum(m.startswith('SEND_TAPPED tap=') for m in markers) == 1)
    need(not any(m.startswith(('OUTCOME_UNKNOWN', 'SUBMITTED', 'SEND_NOT_FIRED')) for m in markers))
    need(markers.index('START') < markers.index('DIGEST_OK') < markers.index('SUBMIT_FAILED') < markers.index('SUBMIT_FAILED_PRE_CASE'))
    need(isinstance(bundle, bytes) and 0 < len(bundle) <= MAX_ARCHIVE)
    with tarfile.open(fileobj=io.BytesIO(bundle), mode='r:gz') as archive:
        files = {}
        total = 0
        for member in archive:
            need(member.isfile() and '/' not in member.name and '\\' not in member.name)
            need(member.name not in files and member.size <= MAX_ARCHIVE)
            total += member.size
            need(total <= MAX_ARCHIVE)
            files[member.name] = member
        need('live-e2e-submission-state.json' not in files)
        need('live-e2e-response-log.jsonl' not in files)
        def text(name):
            need(name in files and files[name].size <= MAX_TEXT)
            return archive.extractfile(files[name]).read(MAX_TEXT + 1).decode('utf-8', errors='strict')
        need(text('submissions-count.txt').strip() == '0')
        instrumentation = text('instrumentation.txt')
        need(ASSERTION in instrumentation)
        need('LiveEmulatorSubmissionTest' in instrumentation)
        need('INSTRUMENTATION_CODE: -1' in instrumentation)
        need(text('instrumentation.rc').strip() == '0')
        need('Tests run: 1,  Failures: 1' in instrumentation)
        app_files = text('files-app.txt').splitlines()
        need(not any('submissions/' in name or name.endswith('.json') for name in app_files))
    return True


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def bounded(response):
    data = response.read(MAX_ARCHIVE + 1)
    need(len(data) <= MAX_ARCHIVE)
    return data


def artifact_bytes(url, token):
    # Authorization is sent only to the fixed GitHub API. Signed storage gets no token.
    need(url.startswith('https://api.github.com/repos/'))
    request = urllib.request.Request(url, headers={'Authorization': 'Bearer ' + token})
    try:
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=45) as response:
            return bounded(response)
    except urllib.error.HTTPError as error:
        need(error.code == 302)
        location = error.headers.get('Location', '')
        need(location.startswith('https://'))
        with urllib.request.urlopen(location, timeout=45) as response:
            return bounded(response)


def unzip_one(data, name):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        need(archive.namelist() == [name])
        item = archive.infolist()[0]
        need(item.file_size <= MAX_ARCHIVE and not item.flag_bits & 1)
        return archive.read(item)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--record', required=True)
    args = parser.parse_args()
    record = json.loads(open(args.record, encoding='utf-8').read(MAX_TEXT + 1))
    run_id = str(record.get('runId', ''))
    need(re.fullmatch('[1-9][0-9]{0,19}', run_id))
    repo = os.environ['GITHUB_REPOSITORY']
    need(re.fullmatch('[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repo))
    token = os.environ['GH_TOKEN']
    base = 'https://api.github.com/repos/' + repo + '/actions/runs/' + run_id
    def api(suffix):
        request = urllib.request.Request(base + suffix, headers={'Authorization': 'Bearer ' + token})
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=45) as response:
            return json.loads(bounded(response))
    run = api('')
    need(run['status'] == 'completed' and run['conclusion'] == 'failure')
    need(str(run['id']) == run_id and run['event'] == 'workflow_dispatch')
    need(str(run['run_attempt']) == str(record.get('runAttempt', '')))
    need(run['path'] == '.github/workflows/live-emulator-e2e.yml')
    listing = api('/artifacts?per_page=100')
    need(listing['total_count'] <= 100)
    artifacts = listing['artifacts']
    def get(name, inner):
        matches = [a for a in artifacts if a['name'] == name and not a['expired']]
        need(len(matches) == 1)
        artifact = matches[0]
        need(str(artifact['workflow_run']['id']) == run_id)
        url = 'https://api.github.com/repos/' + repo + '/actions/artifacts/' + str(artifact['id']) + '/zip'
        return unzip_one(artifact_bytes(url, token), inner)
    encrypted = get('live-e2e-private-bundle-encrypted', 'live-e2e-private-bundle.tgz.enc')
    log = get('live-e2e-scrubbed-log', 'live-log.txt')
    with tempfile.TemporaryDirectory() as directory:
        source = os.path.join(directory, 'bundle.enc')
        with open(source, 'wb') as out:
            out.write(encrypted)
        result = subprocess.run(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-iter', '200000',
                                 '-in', source, '-pass', 'env:TRIAL_DATA_KEY'],
                                check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30)
        prove(record, run_id, os.environ['APPROVED_SHA256_INPUT'],
              os.environ['APPROVED_PHOTO_SHA256_INPUT'], log, result.stdout)
    print('same-run evidence proves a pre-case failure with zero submissions')


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('pre-case retry proof unavailable - refusing') from None
