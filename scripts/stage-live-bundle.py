#!/usr/bin/env python3
"""Verify and privately stage one approved, encrypted local input bundle."""
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import zipfile

MAX_PHOTO = 25 * 1024 * 1024
MAX_DIRECTORY = 30 * 1024 * 1024
MAX_BUNDLE = MAX_PHOTO + MAX_DIRECTORY + 65536


def sha(data):
    return hashlib.sha256(data).hexdigest()


def validate_zip(data, photo_sha, directory_sha):
    for digest in (photo_sha, directory_sha):
        if not re.fullmatch('[0-9a-f]{64}', digest):
            raise ValueError('invalid input digest')
    if len(data) > MAX_BUNDLE:
        raise ValueError('oversized bundle')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        if len(entries) != 2 or {e.filename for e in entries} != {'photo.jpg', 'addresses.json'}:
            raise ValueError('bundle must have exactly the two expected root entries')
        for entry in entries:
            mode = entry.external_attr >> 16
            if entry.is_dir() or (stat.S_IFMT(mode) not in (0, stat.S_IFREG)) or entry.flag_bits & 1:
                raise ValueError('bundle entry must be a plain regular file')
            limit = MAX_PHOTO if entry.filename == 'photo.jpg' else MAX_DIRECTORY
            if not 0 < entry.file_size <= limit:
                raise ValueError('bundle entry exceeds size limit')
        photo = archive.read('photo.jpg')
        directory = archive.read('addresses.json')
    if sha(photo) != photo_sha or sha(directory) != directory_sha:
        raise ValueError('bundle input digest mismatch')
    if not photo.startswith(b'\xff\xd8'):
        raise ValueError('photo must be JPEG')
    parsed = json.loads(directory)
    if not isinstance(parsed, dict) or not isinstance(parsed.get('streets'), list) or not isinstance(parsed.get('addresses'), list):
        raise ValueError('invalid address directory')
    return photo, directory


def stage(root=Path('.'), env=os.environ):
    encrypted = root / 'trial/live-input.zip.enc'
    expected = (root / 'trial/live-input.sha256').read_text().strip()
    if not re.fullmatch('[0-9a-f]{64}', expected) or not 0 < encrypted.stat().st_size <= MAX_BUNDLE:
        raise ValueError('invalid pinned encrypted bundle')
    if sha(encrypted.read_bytes()) != expected:
        raise ValueError('encrypted bundle digest mismatch')
    if not env.get('TRIAL_DATA_KEY'):
        raise ValueError('bundle decryption key unavailable')
    pin_source = (root / 'app/src/main/java/io/github/reihlinstinct/reporter/municipal/AddressLookupPin.kt').read_text()
    pins = re.findall(r'^\s*"([0-9a-f]{64})"\s*$', pin_source, re.MULTILINE)
    if len(pins) != 1:
        raise ValueError('directory production pin unavailable')
    os.umask(0o077)
    with tempfile.TemporaryDirectory(prefix='live-bundle-') as private:
        plain = Path(private) / 'input.zip'
        subprocess.run(['openssl', 'enc', '-d', '-aes-256-cbc', '-pbkdf2', '-iter', '200000',
                        '-in', str(encrypted), '-out', str(plain), '-pass', 'env:TRIAL_DATA_KEY'],
                       env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        photo, directory = validate_zip(plain.read_bytes(), env.get('APPROVED_PHOTO_SHA256_INPUT', ''), pins[0])
        # Neither destination changes until all content is verified.
        for target, data in [(Path('/tmp/live-e2e/photo.jpg'), photo),
                             (Path('/tmp/live-private/addresses.json'), directory)]:
            target.parent.mkdir(parents=True, exist_ok=True)
            fd, temporary = tempfile.mkstemp(prefix='.verified-', dir=target.parent)
            try:
                with os.fdopen(fd, 'wb') as out:
                    out.write(data)
                os.replace(temporary, target)
            finally:
                Path(temporary).unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        stage()
    except Exception:
        raise SystemExit('encrypted input bundle staging failed - refusing to run') from None
    print('encrypted bundle, approved photo and pinned directory verified (contents withheld)')
