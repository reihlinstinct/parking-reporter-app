#!/usr/bin/env python3
"""Clean emulator snapshot cache: key, provenance manifest, content scan.

Issue #548. Pure standard library and offline. The cache holds an AVD with the
app and test APK installed and nothing private. This module decides three
things only: which cache key an input set maps to, whether a restored cache is
fresh and matches the inputs, and whether a directory holds only AVD files.
It never touches the network and never reads private data.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

SCHEMA = 1
KEY_PREFIX = 'emu-snap-v1-'
EPOCH_DAYS = 2  # a snapshot is rebuilt at least every 2 days, below the 3 day limit
MAX_AGE = timedelta(hours=72)
CLOCK_SKEW = timedelta(minutes=5)
INPUT_FIELDS = (
    'api_level', 'arch', 'target', 'profile', 'system_image_revision',
    'emulator_version', 'runner_image', 'app_source_sha256',
    'prepare_script_sha256', 'epoch',
)
HEX64 = re.compile(r'^[0-9a-f]{64}$')
# Names that must never be inside a clean snapshot. Matching is on file names.
FORBIDDEN_NAMES = re.compile(
    r'(params|addresses|live-params|credentials?|secrets?|trial|reporter|session|cookie|'
    r'token|\.env|id_rsa|\.pem|\.key)', re.I)
FORBIDDEN_SUFFIXES = ('.jpg', '.jpeg', '.png', '.mp4', '.zip', '.enc', '.log', '.txt')
# Files that legitimately end in a forbidden suffix inside an AVD directory.
ALLOWED_EXACT = {'hardware-qemu.ini', 'config.ini', 'provenance.json', 'screenshot.png',
                 'emu-launch-params.txt'}
MAX_FILES = 5000


class CacheRejected(Exception):
    """Raised for any reason a snapshot must not be used. Fall back to a cold build."""


def epoch_for(now: datetime, days: int = EPOCH_DAYS) -> int:
    return int(now.astimezone(timezone.utc).timestamp() // (days * 86400))


def _check_inputs(inputs: dict) -> dict:
    if not isinstance(inputs, dict) or set(inputs) != set(INPUT_FIELDS):
        raise CacheRejected('inputs must have exactly the documented fields')
    for k in INPUT_FIELDS:
        v = inputs[k]
        if k == 'epoch':
            if not isinstance(v, int) or isinstance(v, bool) or v < 0:
                raise CacheRejected('epoch must be a non-negative integer')
        elif not isinstance(v, str) or not v or len(v) > 200 or '\n' in v:
            raise CacheRejected(f'bad input value: {k}')
        if k.endswith('sha256') and not HEX64.match(str(v)):
            raise CacheRejected(f'{k} must be a lowercase sha256')
    return inputs


def cache_key(inputs: dict) -> str:
    """Exact key. There is deliberately no restore-prefix: no cross-key fallback."""
    inputs = _check_inputs(inputs)
    blob = json.dumps({k: inputs[k] for k in INPUT_FIELDS}, sort_keys=True, separators=(',', ':'))
    return KEY_PREFIX + hashlib.sha256(blob.encode()).hexdigest()[:40]


def build_manifest(inputs: dict, created_at: datetime, producer_ref: str, producer_sha: str) -> dict:
    inputs = _check_inputs(inputs)
    if created_at.tzinfo is None:
        raise CacheRejected('created_at needs a timezone')
    return {
        'schema': SCHEMA,
        'inputs': dict(inputs),
        'key': cache_key(inputs),
        'created_at': created_at.astimezone(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
        'clean_build': True,
        'private_data_injected': False,
        'producer_ref': producer_ref,
        'producer_sha': producer_sha,
    }


def verify_manifest(manifest, expected_inputs: dict, now: datetime,
                    allowed_ref='refs/heads/main') -> None:
    """Return None when the snapshot may be used; raise CacheRejected otherwise."""
    expected = _check_inputs(expected_inputs)
    if not isinstance(manifest, dict):
        raise CacheRejected('manifest is not an object')
    required = {'schema', 'inputs', 'key', 'created_at', 'clean_build',
                'private_data_injected', 'producer_ref', 'producer_sha'}
    if set(manifest) != required:
        raise CacheRejected('manifest fields are missing or unexpected')
    if manifest['schema'] != SCHEMA:
        raise CacheRejected('unsupported manifest schema')
    if manifest['clean_build'] is not True or manifest['private_data_injected'] is not False:
        raise CacheRejected('clean-build marker is not set')
    allowed = (allowed_ref,) if isinstance(allowed_ref, str) else tuple(allowed_ref)
    # Consistency check only: producer_ref is written by the producer itself.
    # The real trust boundary is GitHub cache scoping (a branch's caches are
    # invisible to main) plus a producer job that never holds secrets.
    if manifest['producer_ref'] not in allowed:
        raise CacheRejected('producer ref is not an allowed ref (consistency check, not authentication)')
    if not re.fullmatch(r'[0-9a-f]{40}', str(manifest['producer_sha'])):
        raise CacheRejected('producer sha missing')
    if manifest['inputs'] != expected:
        raise CacheRejected('inputs differ from the current identities')
    if manifest['key'] != cache_key(expected):
        raise CacheRejected('key does not match inputs')
    try:
        created = datetime.strptime(manifest['created_at'], '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc)
    except (TypeError, ValueError):
        raise CacheRejected('bad creation timestamp')
    if created > now + CLOCK_SKEW:
        raise CacheRejected('creation timestamp is in the future')
    if now - created > MAX_AGE:
        raise CacheRejected('snapshot is older than 72 hours')


def scan_tree(root: Path) -> None:
    """Reject anything in the tree that is not plain AVD content."""
    root = Path(root)
    if not root.is_dir() or root.is_symlink():
        raise CacheRejected('cache root is not a plain directory')
    count = 0
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        for name in list(dirnames) + filenames:
            p = Path(dirpath) / name
            if p.is_symlink():
                raise CacheRejected('symlink in cache tree')
        for name in filenames:
            count += 1
            if count > MAX_FILES:
                raise CacheRejected('too many files in cache tree')
            lower = name.lower()
            if lower in ALLOWED_EXACT:
                continue
            if FORBIDDEN_NAMES.search(name) or lower.endswith(FORBIDDEN_SUFFIXES):
                raise CacheRejected('forbidden file name in cache tree')
        for d in dirnames:
            if FORBIDDEN_NAMES.search(d):
                raise CacheRejected('forbidden directory name in cache tree')


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


SOURCE_PATHS = ('app', 'gradle', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties')


def source_digest(root: Path) -> str:
    """Digest of the TRACKED build inputs only (git ls-files).

    Git-ignored files such as the OCR model that scripts/prepare_wrapper.sh
    downloads into app/ must not change the digest, so the key computed before
    the build equals the one recomputed after it. Debug APKs are not
    byte-reproducible across runners (debug signing key), so the key follows
    the sources and the consumer reinstalls the freshly built APKs.
    """
    root = Path(root)
    try:
        out = subprocess.run(['git', 'ls-files', '-z', '--', *SOURCE_PATHS], cwd=root,
                             capture_output=True, check=True, timeout=60).stdout
    except (OSError, subprocess.SubprocessError):
        raise CacheRejected('cannot list tracked sources (not a git checkout)')
    names = sorted(n for n in out.decode().split('\0') if n)
    if not names:
        raise CacheRejected('no tracked source files found')
    h = hashlib.sha256()
    for name in names:
        f = root / name
        if f.is_symlink() or not f.is_file():
            raise CacheRejected('tracked source is not a regular file')
        h.update(name.encode() + b'\0' + _sha256(f).encode() + b'\n')
    return h.hexdigest()


def prepare_digest(paths) -> str:
    """Digest of the preparation inputs: producer workflow, composite actions and this script."""
    h = hashlib.sha256()
    for name in sorted(p for p in paths if p):
        h.update(name.encode() + b'\0' + _sha256(Path(name)).encode() + b'\n')
    return h.hexdigest()


def _inputs_from_env(env) -> dict:
    now = datetime.now(timezone.utc) if not env.get('SNAPSHOT_NOW') else datetime.fromisoformat(env['SNAPSHOT_NOW'])
    return {
        'api_level': env['EMU_API_LEVEL'], 'arch': env['EMU_ARCH'], 'target': env['EMU_TARGET'],
        'profile': env['EMU_PROFILE'], 'system_image_revision': env['EMU_IMAGE_REVISION'],
        'emulator_version': env['EMU_VERSION'], 'runner_image': env['EMU_RUNNER_IMAGE'],
        'app_source_sha256': source_digest(Path(env.get('EMU_SOURCE_ROOT', '.'))),
        'prepare_script_sha256': prepare_digest(env['EMU_PREPARE_PATHS'].split()),
        'epoch': epoch_for(now),
    }


def main(argv=None, env=None) -> int:
    env = os.environ if env is None else env
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest='cmd', required=True)
    sub.add_parser('key')
    p = sub.add_parser('write-manifest')
    p.add_argument('--avd-dir', required=True)
    p = sub.add_parser('verify')
    p.add_argument('--avd-dir', required=True)
    # Live consumers keep the default. Only the mock benchmark passes its own
    # branch ref, and GitHub scopes a branch's caches to that branch.
    p.add_argument('--allowed-ref', action='append')
    args = ap.parse_args(argv)
    try:
        inputs = _inputs_from_env(env)
        if args.cmd == 'key':
            print(cache_key(inputs))
            return 0
        avd = Path(args.avd_dir)
        manifest_path = avd / 'provenance.json'
        if args.cmd == 'write-manifest':
            scan_tree(avd)
            m = build_manifest(inputs, datetime.now(timezone.utc), env['GITHUB_REF'], env['GITHUB_SHA'])
            manifest_path.write_text(json.dumps(m, sort_keys=True, indent=1) + '\n')
            print('SNAPSHOT-MANIFEST-WRITTEN')
            return 0
        scan_tree(avd)
        verify_manifest(json.loads(manifest_path.read_text()), inputs, datetime.now(timezone.utc),
                        args.allowed_ref or 'refs/heads/main')
        print('SNAPSHOT-VERIFIED')
        return 0
    except (CacheRejected, KeyError, OSError, ValueError) as e:
        # Fixed marker only; the reason class is safe, never a path or value.
        print('SNAPSHOT-REJECTED', e.__class__.__name__ if not isinstance(e, CacheRejected) else str(e))
        return 3


if __name__ == '__main__':
    sys.exit(main())
