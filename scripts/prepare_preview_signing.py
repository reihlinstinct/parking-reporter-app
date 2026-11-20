#!/usr/bin/env python3
"""Decode the CI-only preview keystore without printing secrets or retaining env copies."""
import base64
import os
from pathlib import Path


def prepare(directory):
    encoded = os.environ.pop('PREVIEW_SIGNING_STORE_B64', '')
    if not encoded or len(encoded) > 100_000:
        raise ValueError('Preview signing material unavailable')
    try:
        data = base64.b64decode(encoded, validate=True)
    except ValueError:
        raise ValueError('Preview signing material invalid') from None
    if not 1024 <= len(data) <= 65_536:
        raise ValueError('Preview signing material invalid')
    directory = Path(directory)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    directory.chmod(0o700)
    destination = directory / 'preview.p12'
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, 'O_NOFOLLOW'):
        flags |= os.O_NOFOLLOW
    fd = os.open(destination, flags, 0o600)
    try:
        with os.fdopen(fd, 'wb') as output:
            output.write(data)
    except BaseException:
        destination.unlink(missing_ok=True)
        raise
    return destination


if __name__ == '__main__':
    prepare(os.environ['PREVIEW_SIGNING_DIRECTORY'])
