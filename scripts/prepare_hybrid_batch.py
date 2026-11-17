"""Prepare-only workflow entry. Never imports live adapters or journal writer."""
import hashlib
import json
import os
from pathlib import Path
import subprocess

from fetch_live_batch import stage
from live_batch_input import validate_zip, no_duplicates, digest, need


def main(root=None):
    root = Path('/tmp/hybrid-prepared') if root is None else Path(root)
    try:
        root.mkdir(mode=0o700, exist_ok=False)
        os.umask(0o077)
        stage(root / 'input')
        source = root / 'input'
        expected_outer = os.environ['BATCH_APPROVED_OUTER_SHA256']
        digest(expected_outer)
        need(hashlib.sha256(source.read_bytes()).hexdigest() == expected_outer, 'outer approval mismatch')
        expected_inner = os.environ['BATCH_APPROVED_INNER_SHA256']
        expected_addresses = os.environ['BATCH_APPROVED_ADDRESSES_SHA256']
        digest(expected_inner)
        digest(expected_addresses)
        if os.environ.get('BATCH_PRIVATE_PATH', '').endswith('.enc'):
            result = subprocess.run(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-md', 'sha256', '-iter', '200000',
                                     '-in', str(source), '-out', str(root / 'input.zip'), '-pass', 'env:TRIAL_DATA_KEY'],
                                     capture_output=True, timeout=60)
            if result.returncode: raise ValueError()
            source = root / 'input.zip'
        policy = json.loads(os.environ['BATCH_VERIFIED_TOPIC_POLICY'], object_pairs_hook=no_duplicates)
        data = validate_zip(source, expected_inner, set(policy),
                            expected_addresses, topic_policy=policy)
        output = root / 'effective.json'
        output.write_text(json.dumps(data, ensure_ascii=False, allow_nan=False))
        result = subprocess.run(['openssl', 'enc', '-aes-256-cbc', '-pbkdf2', '-md', 'sha256', '-iter', '200000', '-salt',
                                 '-in', str(output), '-out', str(root / 'effective.json.enc'), '-pass', 'env:TRIAL_DATA_KEY'],
                                 capture_output=True, timeout=60)
        if result.returncode: raise ValueError()
        print('BATCH_PREPARED_OFFLINE_NO_SUBMISSION')
        return 0
    except Exception:
        print('BATCH_PREPARATION_BLOCKED_NO_SUBMISSION')
        return 1
    finally:
        if root.is_dir():
            for name in ['input', 'input.zip', 'effective.json']:
                (root / name).unlink(missing_ok=True)


if __name__ == '__main__':
    raise SystemExit(main())
