"""Inactive v2 author-input enrichment. No network, inference from prose or writes.

Trusted topic policy is supplied by the caller, never read from the ZIP.
Address derivation is deliberately stricter than app lookup: exact unique point.
"""
import datetime as dt
import hashlib
import json
import math
import re
import struct
import unicodedata


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def identity(domain, value):
    return sha(domain.encode() + b'\x00' + canonical(value))


def _norm(value):
    value = unicodedata.normalize('NFKC', value).translate(str.maketrans('ךםןףץ', 'כמנפצ'))
    return ''.join(c for c in value if c.isalnum())


def address_location(address, directory):
    from live_batch_input import need
    need(isinstance(address, str), 'address required')
    parts = address.rsplit(' ', 1)
    need(len(parts) == 2, 'exact street and house required')
    street, house = parts
    need(isinstance(directory, dict) and directory.get('version') == 1, 'unsupported address directory')
    codes = set()
    for row in directory.get('streets', []):
        names = [row.get('StreetName', ''), row.get('StreetArabicName', '')] + row.get('aliases', [])
        if _norm(street) in {_norm(n) for n in names if isinstance(n, str) and n}:
            codes.add(str(row.get('StreetCode')))
    need(len(codes) == 1, 'address not uniquely resolved')
    code = next(iter(codes))
    names = {_norm(r['StreetName']) for r in directory['streets'] if str(r.get('StreetCode')) == code}
    matches = set()
    for row in directory.get('addresses', []):
        if isinstance(row, list) and len(row) >= 4 and _norm(str(row[2])) == _norm(house) and _norm(str(row[3])) in names:
            lat, lon = row[:2]
            need(type(lat) in (int, float) and type(lon) in (int, float) and math.isfinite(lat) and math.isfinite(lon), 'invalid address point')
            need(31.0 <= lat <= 32.0 and 34.0 <= lon <= 36.0, 'address point out of region')
            matches.add((lat, lon))
    need(len(matches) == 1, 'address point missing or ambiguous')
    return next(iter(matches))


def coordinates_address(lat, lon, directory):
    from live_batch_input import need
    need(type(lat) in (int, float) and type(lon) in (int, float) and math.isfinite(lat) and math.isfinite(lon), 'invalid coordinates')
    need(-90 <= lat <= 90 and -180 <= lon <= 180, 'invalid coordinates')
    def distance(a, b):
        # Local distances only, no external geocoder or broad nearest-street guess.
        return math.hypot((a - lat) * 111320, (b - lon) * 111320 * math.cos(math.radians(lat)))
    names = {_norm(str(r['StreetName'])): str(r['StreetName']) for r in directory.get('streets', [])}
    candidates = {}
    for row in directory.get('addresses', []):
        if isinstance(row, list) and len(row) >= 4 and _norm(str(row[3])) in names:
            d = distance(row[0], row[1])
            key = names[_norm(str(row[3]))] + ' ' + str(row[2])
            candidates[key] = min(d, candidates.get(key, math.inf))
    found = sorted((d, name) for name, d in candidates.items() if d <= 150)
    need(found and (len(found) == 1 or found[1][0] - found[0][0] > 20), 'coordinate address missing or ambiguous')
    return found[0][1]


def exif_capture(data):
    """Conservative JPEG EXIF DateTimeOriginal+OffsetTimeOriginal, no timezone guess.

    Missing/unsupported/malformed metadata returns None. Bounded IFD entries,
    offsets and segment traversal. Multiple EXIF segments refuse derivation.
    PNG/HEIC are not auto-timed in this inactive slice.
    """
    try:
        if not data.startswith(b'\xff\xd8'):
            return None
        pos, found = 2, []
        while pos + 4 <= len(data):
            if data[pos] != 255:
                return None
            marker = data[pos + 1]
            if marker in (0xDA, 0xD9):
                break
            size = int.from_bytes(data[pos + 2:pos + 4], 'big')
            if size < 2 or pos + 2 + size > len(data):
                return None
            payload = data[pos + 4:pos + 2 + size]
            if marker == 0xE1 and payload.startswith(b'Exif\x00\x00'):
                found.append(payload[6:])
            pos += 2 + size
        if len(found) != 1:
            return None
        t = found[0]
        endian = {'II': '<', 'MM': '>'}.get(t[:2].decode('ascii'))
        if not endian or struct.unpack_from(endian + 'H', t, 2)[0] != 42:
            return None
        def u32(off):
            return struct.unpack_from(endian + 'I', t, off)[0]
        def ifd(off):
            count = struct.unpack_from(endian + 'H', t, off)[0]
            if count > 256 or off + 2 + count * 12 + 4 > len(t):
                raise ValueError()
            rows = {}
            for i in range(count):
                at = off + 2 + i * 12
                tag, typ, n = struct.unpack_from(endian + 'HHI', t, at)
                if tag in rows:
                    raise ValueError()
                rows[tag] = (typ, n, at + 8)
            return rows
        root = ifd(u32(4))
        typ, n, off = root[0x8769]
        if typ != 4 or n != 1:
            return None
        rows = ifd(u32(off))
        def ascii_tag(tag, size):
            typ, n, off = rows[tag]
            if typ != 2 or n != size:
                raise ValueError()
            at = off if n <= 4 else u32(off)
            raw = t[at:at + n]
            if len(raw) != n or raw[-1] != 0:
                raise ValueError()
            return raw[:-1].decode('ascii')
        date = ascii_tag(0x9003, 20)
        offset = ascii_tag(0x9011, 7)
        if not re.fullmatch(r'\d{4}:\d{2}:\d{2} \d{2}:\d{2}:\d{2}', date) or not re.fullmatch(r'[+-]\d{2}:\d{2}', offset):
            return None
        value = date[:10].replace(':', '-') + 'T' + date[11:] + offset
        parsed = dt.datetime.fromisoformat(value)
        if abs(parsed.utcoffset().total_seconds()) > 14 * 3600:
            return None
        return parsed.isoformat()
    except (KeyError, ValueError, UnicodeError, struct.error, IndexError, OverflowError):
        return None


def derive_manifest(m, files, directory, subjects, topic_policy):
    from live_batch_input import need, identifier, text, digest
    need(isinstance(m, dict) and set(m) <= {'schemaVersion', 'batchId', 'reports'})
    need(type(m.get('schemaVersion', 2)) is int and m.get('schemaVersion', 2) == 2)
    need(isinstance(m.get('reports'), list) and 1 <= len(m['reports']) <= 50)
    need(isinstance(directory, dict) and directory.get('version') == 1, 'unsupported address directory')
    need(all(isinstance(k, str) and isinstance(v, dict) and isinstance(v.get('names', []), list) and all(isinstance(n, str) for n in v.get('names', [])) for k, v in topic_policy.items()), 'invalid independent topic policy')
    reports = []
    allowed = {'reportId', 'text', 'textSha256', 'plate', 'address', 'latitude', 'longitude', 'capturedAt', 'subjectCode', 'topic', 'photos'}
    for raw in m['reports']:
        need(isinstance(raw, dict) and {'text'} <= set(raw) <= allowed)
        text(raw['text'], 10000)
        code = raw.get('subjectCode')
        if 'topic' in raw:
            text(raw['topic'], 300)
            matches = [k for k, v in topic_policy.items() if raw['topic'] in v.get('names', []) and k in subjects]
            need(len(matches) == 1, 'topic name missing or ambiguous')
            need(code is None or code == matches[0], 'topic identity mismatch')
            code = matches[0]
        need(isinstance(code, str) and code in subjects and code in topic_policy, 'verified topic policy required')
        required = topic_policy[code].get('plateRequired')
        need(type(required) is bool, 'verified plate policy required')
        plate = raw.get('plate', '')
        need(isinstance(plate, str))
        if required:
            need(re.fullmatch(r'[0-9]{5,8}', plate) is not None, 'topic requires plate')
            need(plate in raw['text'], 'approved text must contain required plate')
        else:
            need(plate == '', 'topic does not use plate')
        need(('latitude' in raw) == ('longitude' in raw), 'both coordinates required together')
        address = raw.get('address')
        if address is not None:
            text(address, 300)
        need(address is not None or 'latitude' in raw, 'address or coordinates required')
        if 'latitude' in raw:
            lat, lon = raw['latitude'], raw['longitude']
            # Coordinate-only preparation is conservative nearest numbered address, never street-only.
            if address is None:
                address = coordinates_address(lat, lon, directory)
            if 'address' in raw:
                # A known explicit address must not contradict supplied coordinates.
                # Strict lookup fails closed on missing/ambiguous points here too.
                a, b = address_location(address, directory)
                need(type(lat) in (int, float) and type(lon) in (int, float) and math.isfinite(lat) and math.isfinite(lon), 'invalid coordinates')
                need(math.hypot((a - lat) * 111320, (b - lon) * 111320 * math.cos(math.radians(lat))) <= 200, 'address coordinate conflict')
        else:
            lat, lon = address_location(address, directory)
        photos = []
        images = raw.get('photos', [])
        need(isinstance(images, list) and len(images) <= 10)
        for photo in images:
            photo = {'path': photo} if isinstance(photo, str) else photo
            need(isinstance(photo, dict) and {'path'} <= set(photo) <= {'path', 'sha256'})
            path = photo['path']
            need(isinstance(path, str) and path in files, 'missing photo')
            actual = sha(files[path])
            if 'sha256' in photo:
                digest(photo['sha256']); need(photo['sha256'] == actual, 'photo digest mismatch')
            photos.append({'path': path, 'sha256': actual})
        need(type(lat) in (int, float) and type(lon) in (int, float) and math.isfinite(lat) and math.isfinite(lon) and -90 <= lat <= 90 and -180 <= lon <= 180, 'invalid coordinates')
        captured = raw.get('capturedAt')
        if captured is None:
            times = {exif_capture(files[p['path']]) for p in photos}
            # Missing/ambiguous EXIF stays absent. It is not a CreateCase wire field.
            captured = times.pop() if None not in times and len(times) == 1 else None
        text_sha = sha(raw['text'].encode())
        if 'textSha256' in raw:
            digest(raw['textSha256']); need(raw['textSha256'] == text_sha, 'text digest mismatch')
        report = dict(text=raw['text'], textSha256=text_sha, plate=plate, address=address, latitude=lat, longitude=lon, capturedAt=captured, subjectCode=code, photos=photos)
        # Paths/labels/batch order cannot change an incident's generated identity.
        bound = dict(report, photos=[p['sha256'] for p in photos])
        report['reportId'] = identity('parking-report-v2', bound)
        if 'reportId' in raw:
            identifier(raw['reportId']); need(raw['reportId'] == report['reportId'], 'generated report identity mismatch')
        reports.append(report)
    batch_id = identity('parking-batch-v2', reports)
    if 'batchId' in m:
        identifier(m['batchId']); need(m['batchId'] == batch_id, 'generated batch identity mismatch')
    return dict(schemaVersion=2, batchId=batch_id, reports=reports)
