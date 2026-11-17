#!/usr/bin/env python3
"""Offline private preparation only. Never dispatches, authorizes or calls a city."""
import argparse
import json
import os
from pathlib import Path

from live_batch_input import InvalidBatch, validate_zip, no_duplicates


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--zip', required=True)
    p.add_argument('--approved-zip-sha256', required=True)
    p.add_argument('--approved-addresses-sha256', required=True)
    p.add_argument('--topic-policy-file', required=True, help='Independent verified topic policy, outside ZIP')
    p.add_argument('--output', required=True, help='New private effective-manifest file, parent mode0700')
    args = p.parse_args()
    try:
        policy = json.loads(Path(args.topic_policy_file).read_text(), object_pairs_hook=no_duplicates)
        if not isinstance(policy, dict): raise InvalidBatch('invalid topic policy')
        manifest = validate_zip(args.zip, args.approved_zip_sha256, set(policy), args.approved_addresses_sha256, topic_policy=policy)
        out = Path(args.output)
        if out.parent.is_symlink() or out.parent.stat().st_mode & 0o077:
            raise InvalidBatch('private output directory required')
        fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(manifest, stream, ensure_ascii=False, sort_keys=True, allow_nan=False)
            stream.write('\n'); stream.flush(); os.fsync(stream.fileno())
    except (InvalidBatch, ValueError, OSError, TypeError, KeyError):
        # No manifest details, paths or exception values in public output.
        print('BATCH_INVALID_OR_PREPARATION_BLOCKED')
        return 1
    print('BATCH_VALID_OFFLINE_ONLY')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
