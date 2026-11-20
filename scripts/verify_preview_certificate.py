#!/usr/bin/env python3
"""Refuse an APK signed by anything other than the reviewed preview certificate."""
import re
import sys
from pathlib import Path


def verify(report, expected):
    pin = expected.strip().lower()
    if not re.fullmatch('[a-f0-9]{64}', pin):
        raise ValueError('Invalid preview certificate pin')
    signers = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})\s*$', report, re.MULTILINE)
    if len(signers) != 1 or signers[0].lower() != pin:
        raise ValueError('Preview certificate does not match the persistent identity')
    if 'DOES NOT VERIFY' in report:
        raise ValueError('Preview signature verification failed')


if __name__ == '__main__':
    verify(Path(sys.argv[1]).read_text(), Path(sys.argv[2]).read_text())
    print('Preview certificate matches the persistent identity')
