"""Offline, non-executing batch ZIP validation. Does not authorize or submit reports."""
import datetime as dt
import hashlib
import json
import math
import re
import stat
import zipfile
from pathlib import PurePosixPath

MAX_ENTRIES = 512
MAX_ARCHIVE_BYTES = 100 * 1024 * 1024
MAX_EXPANDED_BYTES = 200 * 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
HEX = re.compile(r"[0-9a-f]{64}\Z")
ID = re.compile(r"[A-Za-z0-9_-]{1,80}\Z")


class InvalidBatch(ValueError):
    """Constant messages only: safe to surface without report contents."""


def need(condition, message="invalid batch data"):
    if not condition:
        raise InvalidBatch(message)


def keys(obj, required):
    need(isinstance(obj, dict) and set(obj) == set(required))


def text(value, limit):
    need(isinstance(value, str) and 0 < len(value) <= limit and "\x00" not in value)


def digest(value):
    need(isinstance(value, str) and HEX.fullmatch(value) is not None)


def identifier(value):
    need(isinstance(value, str) and ID.fullmatch(value) is not None)


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def safe_name(name):
    need(isinstance(name, str) and "\\" not in name and "\x00" not in name)
    path = PurePosixPath(name)
    need(not path.is_absolute() and name == str(path) and all(p not in (".", "..", "") for p in name.split("/")), "unsafe ZIP path")
    return name


def validate_manifest(m, files, subjects):
    """subjects is an independently verified set of current leaf topic codes."""
    keys(m, ("schemaVersion", "batchId", "reports"))
    need(type(m["schemaVersion"]) is int and m["schemaVersion"] == 1)
    identifier(m["batchId"])
    reports = m["reports"]
    need(isinstance(reports, list) and 1 <= len(reports) <= 50)
    ids, photos, used = set(), set(), {"manifest.json", "addresses.json"}
    for report in reports:
        keys(report, ("reportId", "text", "textSha256", "plate", "address", "latitude", "longitude", "capturedAt", "subjectCode", "photos"))
        identifier(report["reportId"])
        need(report["reportId"] not in ids, "duplicate report identity")
        ids.add(report["reportId"])
        text(report["text"], 10000)
        digest(report["textSha256"])
        need(hashlib.sha256(report["text"].encode()).hexdigest() == report["textSha256"], "text digest mismatch")
        need(isinstance(report["plate"], str) and re.fullmatch(r"[0-9]{5,8}", report["plate"]) is not None)
        text(report["address"], 300)
        for field, bound in (("latitude", 90), ("longitude", 180)):
            value = report[field]
            need(type(value) in (int, float) and math.isfinite(value) and -bound <= value <= bound)
        text(report["capturedAt"], 40)
        try:
            captured = dt.datetime.fromisoformat(report["capturedAt"])
            need(captured.utcoffset() is not None)
        except (ValueError, TypeError):
            raise InvalidBatch("invalid capture time") from None
        need(isinstance(report["subjectCode"], str) and report["subjectCode"] in subjects, "unverified subject code")
        images = report["photos"]
        need(isinstance(images, list) and 1 <= len(images) <= 10)
        for image in images:
            keys(image, ("path", "sha256"))
            name = safe_name(image["path"])
            need(name.startswith("photos/") and name.endswith((".jpg", ".jpeg", ".png")))
            digest(image["sha256"])
            need(name in files and name not in used, "missing or reused photo")
            need(image["sha256"] not in photos, "duplicate photo digest")
            data = files[name]
            need(data.startswith(b"\xff\xd8\xff") or data.startswith(b"\x89PNG\r\n\x1a\n"), "invalid photo signature")
            need(hashlib.sha256(data).hexdigest() == image["sha256"], "photo digest mismatch")
            photos.add(image["sha256"])
            used.add(name)
    need(set(files) == used, "unexpected ZIP content")
    return m


def validate_zip(path, expected_sha256, subjects, addresses_sha256):
    """Validate immutable bytes before staging; never extract arbitrary archive paths."""
    digest(expected_sha256)
    digest(addresses_sha256)
    with open(path, "rb") as stream:
        payload = stream.read(MAX_ARCHIVE_BYTES + 1)
    need(len(payload) <= MAX_ARCHIVE_BYTES, "archive too large")
    need(hashlib.sha256(payload).hexdigest() == expected_sha256, "archive digest mismatch")
    import io
    try:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            entries = archive.infolist()
            need(1 <= len(entries) <= MAX_ENTRIES, "invalid ZIP entry count")
            need(sum(e.file_size for e in entries) <= MAX_EXPANDED_BYTES, "expanded archive too large")
            files = {}
            for entry in entries:
                name = safe_name(entry.filename)
                need(name not in files and not entry.is_dir(), "duplicate or directory ZIP entry")
                mode = entry.external_attr >> 16
                need(stat.S_IFMT(mode) in (0, stat.S_IFREG), "special ZIP entry")
                need(not entry.flag_bits & 1, "unsupported ZIP encryption")
                need(entry.compress_type in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED), "unsupported ZIP compression")
                if name == "manifest.json":
                    need(entry.file_size <= MAX_MANIFEST_BYTES, "manifest too large")
                files[name] = archive.read(entry)
    except (zipfile.BadZipFile, RuntimeError, NotImplementedError, OSError):
        raise InvalidBatch("unreadable ZIP") from None
    need("manifest.json" in files and "addresses.json" in files, "missing manifest or directory")
    need(hashlib.sha256(files["addresses.json"]).hexdigest() == addresses_sha256, "directory pin mismatch")
    try:
        manifest = json.loads(files["manifest.json"], object_pairs_hook=no_duplicates,
                              parse_constant=lambda _: (_ for _ in ()).throw(InvalidBatch("invalid JSON number")))
    except (ValueError, UnicodeError):
        raise InvalidBatch("invalid manifest JSON") from None
    return validate_manifest(manifest, files, subjects)
