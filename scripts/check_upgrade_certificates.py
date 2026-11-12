#!/usr/bin/env python3
"""Fail closed unless each verified APK reports one identical SHA-256 signer."""
import pathlib
import re
import sys


def digest(text):
    lines = [line for line in text.splitlines() if 'certificate SHA-256 digest:' in line]
    if len(lines) != 1:
        raise ValueError('expected exactly one signer certificate digest')
    match = re.fullmatch(r'.*certificate SHA-256 digest:\s*([0-9a-fA-F]{64})\s*', lines[0])
    if not match:
        raise ValueError('invalid signer certificate digest')
    return match.group(1).lower()


def check(old, new):
    old_digest, new_digest = digest(old), digest(new)
    if old_digest != new_digest:
        raise ValueError('old and new APK signer certificates differ')
    return old_digest


if __name__ == '__main__':
    try:
        print(check(*(pathlib.Path(p).read_text() for p in sys.argv[1:3])))
    except (ValueError, OSError) as error:
        sys.exit(str(error))
