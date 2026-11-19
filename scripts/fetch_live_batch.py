"""Inactive immutable batch download from the existing fixed private data repo.

No CLI entry point, dispatch, decryption, extraction or live execution. Uses the
same redirect-free credential boundary as fetch-live-photo.py. Caller encrypts
outputs and cleans private staging; credentials never appear in the manifest.
"""
import hashlib
import os
from pathlib import Path
import re
import tempfile
import urllib.request

MAX_BYTES = 100 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('batch redirects are forbidden')


def stage(destination, env=os.environ, opener=None, *, encrypted_only=False):
    ref = env.get('BATCH_PRIVATE_REF', '')
    path = env.get('BATCH_PRIVATE_PATH', '')
    digest = env.get('BATCH_APPROVED_OUTER_SHA256', '')
    token = env.get('PRIVATE_LOOKUP_READ_TOKEN', '')
    if not re.fullmatch(r'[0-9a-f]{40}', ref):
        raise ValueError('immutable batch ref required')
    if not re.fullmatch(r'batches/[A-Za-z0-9][A-Za-z0-9_-]{0,79}/input\.zip(?:\.enc)?', path):
        raise ValueError('invalid batch path')
    if encrypted_only and not path.endswith('/input.zip.enc'):
        raise ValueError('encrypted trial input required')
    if not re.fullmatch(r'[0-9a-f]{64}', digest) or not token:
        raise ValueError('batch digest and private credential required')
    url = f'https://api.github.com/repos/reihlinstinct/parking-reports/contents/{path}?ref={ref}'
    request = urllib.request.Request(url, headers={
        'Authorization': f'Bearer {token}',
        'Accept': 'application/vnd.github.raw+json',
        'X-GitHub-Api-Version': '2022-11-28',
    })
    opener = opener or urllib.request.build_opener(NoRedirect())
    destination = Path(destination)
    # Check the lexical ancestry before mkdir: a private-looking immediate parent
    # may still redirect through a symlink. This is a trusted disposable-runner
    # tree, not a defence against a concurrent same-UID rename adversary.
    if any(p.is_symlink() for p in (destination, *destination.parents)):
        raise ValueError('private staging path must not contain symlinks')
    destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if destination.parent.is_symlink() or destination.parent.stat().st_mode & 0o077:
        raise ValueError("private staging directory required")
    fd, name = tempfile.mkstemp(prefix='.batch-', dir=destination.parent)
    try:
        sha, total = hashlib.sha256(), 0
        with os.fdopen(fd, 'wb') as out, opener.open(request, timeout=60) as response:
            while chunk := response.read(64 * 1024):
                total += len(chunk)
                if total > MAX_BYTES:
                    raise ValueError('batch exceeds size limit')
                sha.update(chunk)
                out.write(chunk)
            out.flush()
            os.fsync(out.fileno())
        if not total or sha.hexdigest() != digest:
            raise ValueError('batch digest mismatch')
        os.replace(name, destination)
    except Exception:
        # Never surface URL/path, server response, token or manifest values.
        raise ValueError('private batch staging failed') from None
    finally:
        if os.path.exists(name):
            os.unlink(name)
