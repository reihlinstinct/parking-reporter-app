#!/usr/bin/env python3
"""Stage one digest-bound original from the fixed private report repository.

Never takes a URL or repository from input. A full commit ID makes the input
immutable. No redirects: an Authorization header must never follow one.
"""
import hashlib
import os
from pathlib import Path
import re
import tempfile
import urllib.request

MAX_BYTES = 25 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('photo redirects are forbidden')


def stage(destination, env=os.environ, opener=None):
    ref = env.get('TRIAL_PRIVATE_PHOTO_REF', '')
    path = env.get('TRIAL_PRIVATE_PHOTO_PATH', '')
    digest = env.get('APPROVED_PHOTO_SHA256_INPUT', '')
    token = env.get('PRIVATE_LOOKUP_READ_TOKEN', '')
    if not re.fullmatch(r'[0-9a-f]{40}', ref):
        raise ValueError('photo ref must be a full immutable commit ID')
    if not re.fullmatch(r'reports/[A-Za-z0-9][A-Za-z0-9_-]{0,159}/original\.jpg', path):
        raise ValueError('photo path must identify one report original')
    if not re.fullmatch(r'[0-9a-f]{64}', digest) or not token:
        raise ValueError('approved digest and private read credential required')
    url = f'https://api.github.com/repos/reihlinstinct/parking-reports/contents/{path}?ref={ref}'
    request = urllib.request.Request(url, headers={
        'Authorization': f'Bearer {token}',
        'Accept': 'application/vnd.github.raw+json',
        'X-GitHub-Api-Version': '2022-11-28',
    })
    opener = opener or urllib.request.build_opener(NoRedirect())
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix='.photo-', dir=destination.parent)
    try:
        sha, total = hashlib.sha256(), 0
        with os.fdopen(fd, 'wb') as out, opener.open(request, timeout=60) as response:
            while chunk := response.read(64 * 1024):
                total += len(chunk)
                if total > MAX_BYTES:
                    raise ValueError('photo exceeds size limit')
                sha.update(chunk)
                out.write(chunk)
        if not total or sha.hexdigest() != digest:
            raise ValueError('private photo does not match approved digest')
        os.replace(name, destination)
    finally:
        if os.path.exists(name):
            os.unlink(name)


if __name__ == '__main__':
    try:
        stage('/tmp/live-e2e/photo.jpg')
    except Exception:
        # Errors may contain the private URL/path or response details.
        raise SystemExit('private photo staging failed - refusing to run') from None
    print('private photo staged and approved digest verified (values withheld)')
