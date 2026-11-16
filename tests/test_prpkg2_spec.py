"""PRPKG2 contract: the schema files and golden values under docs/prpkg2-spec against docs/PRPKG2.md rules.

Python stdlib only. Everything is synthetic. The Kotlin code stays the reference; this test re-derives the
golden values from the documented rules, so a drift in the values, the schema or the layout fails here.
"""
import hashlib
import json
import re
import struct
import unittest
from pathlib import Path

SPEC = Path(__file__).parents[1] / "docs" / "prpkg2-spec"
GOLDEN = json.loads((SPEC / "golden.json").read_text(encoding="utf-8"))
REPORT_SCHEMA = json.loads((SPEC / "report.schema.json").read_text(encoding="utf-8"))
HEADER_SCHEMA = json.loads((SPEC / "header.schema.json").read_text(encoding="utf-8"))


def validate(value, schema, path="$"):
    """Tiny validator for the subset of JSON Schema these files use. Returns a list of problems."""
    problems = []
    if "const" in schema and (value != schema["const"] or isinstance(value, bool)):
        problems.append(f"{path}: must equal {schema['const']!r}")
    kind = schema.get("type")
    if kind == "object":
        if not isinstance(value, dict):
            return problems + [f"{path}: not an object"]
        props = schema.get("properties", {})
        for key in schema.get("required", []):
            if key not in value:
                problems.append(f"{path}: missing {key}")
        if schema.get("additionalProperties") is False:
            for key in value:
                if key not in props:
                    problems.append(f"{path}: unexpected {key}")
        for key, sub in props.items():
            if key in value:
                problems += validate(value[key], sub, f"{path}.{key}")
    elif kind == "string":
        if not isinstance(value, str):
            return problems + [f"{path}: not a string"]
        if len(value) < schema.get("minLength", 0) or len(value) > schema.get("maxLength", 10**9):
            problems.append(f"{path}: length out of range")
        if "pattern" in schema and not re.search(schema["pattern"], value):
            problems.append(f"{path}: pattern")
    elif kind == "number":
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return problems + [f"{path}: not a number"]
        if value < schema.get("minimum", float("-inf")) or value > schema.get("maximum", float("inf")):
            problems.append(f"{path}: out of range")
    return problems


def quote(text):
    """org.json JSONObject.quote (20260814), as docs/PRPKG2.md describes it."""
    out = ['"']
    prev = ""
    for ch in text:
        if ch in '"\\':
            out.append("\\" + ch)
        elif ch == "/":
            out.append("\\/" if prev == "<" else "/")
        elif ch in "\b\t\n\f\r":
            out.append({"\b": "\\b", "\t": "\\t", "\n": "\\n", "\f": "\\f", "\r": "\\r"}[ch])
        elif ch < " " or "\u0080" <= ch < "\u00a0" or "\u2000" <= ch < "\u2100":
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
        prev = ch
    out.append('"')
    return "".join(out)


def java_double(x):
    """Java Double.toString: plain decimal for 1e-3 <= |x| < 1e7, else computerized scientific notation.

    Digits are the shortest round-trip digits (JDK 19+; older JDKs can print an extra digit for some values).
    """
    from decimal import Decimal

    x = float(x)
    if x == 0:
        return "0.0"
    if 1e-3 <= abs(x) < 1e7:
        return repr(x)
    sign, digits, exp = Decimal(repr(abs(x))).as_tuple()
    text = "".join(map(str, digits)).rstrip("0") or "0"
    point = len(digits) + exp - 1
    return ("-" if x < 0 else "") + text[0] + "." + (text[1:] or "0") + "E" + str(point)


def canonical(value):
    if isinstance(value, dict):
        return "{" + ",".join(quote(k) + ":" + canonical(value[k]) for k in sorted(value)) + "}"
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "null"
    if isinstance(value, str):
        return quote(value)
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return java_double(value)
    raise ValueError("Unsupported canonical value")


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def photo():
    p = GOLDEN["photo"]
    return bytes.fromhex(p["prefix_hex"]) + bytes.fromhex(p["fill_hex"]) * p["fill_length"] + bytes.fromhex(p["suffix_hex"])


class GoldenValuesTest(unittest.TestCase):
    def test_photo_hash_and_length(self):
        self.assertEqual(len(photo()), GOLDEN["photo"]["length"])
        self.assertEqual(sha256(photo()), GOLDEN["photo"]["sha256"])

    def test_canonical_report_is_canonical_and_matches_approval_digest(self):
        text = GOLDEN["canonical_report"]
        self.assertEqual(canonical(json.loads(text, parse_float=float)), text)
        self.assertEqual(len(text.encode("utf-8")), GOLDEN["canonical_report_bytes"])
        self.assertEqual(sha256(text.encode("utf-8")), GOLDEN["approval_digest"])

    def test_review_digest_follows_the_documented_field_order(self):
        f = GOLDEN["review_fields"]

        def number(x):
            return "null" if x is None else repr(float(x))

        values = [f["evidence_id"], f["original_sha256"], f["capture_time"] or "", number(f["latitude"]), number(f["longitude"]),
                  f["plate"], f["address"], f["subject"], f["exact_text"], f["confirmed_time"] or "",
                  number(f["confirmed_latitude"]), number(f["confirmed_longitude"])] + sorted(f["unresolved"])
        digest = sha256("".join(f"{len(v.encode('utf-8'))}:{v}" for v in values).encode("utf-8"))
        self.assertEqual(digest, GOLDEN["review_digest"])
        self.assertEqual(json.loads(GOLDEN["canonical_report"])["review_digest"], digest)

    def test_header_and_plain_layout(self):
        report = json.loads(GOLDEN["canonical_report"])
        header = canonical({"approval_digest": GOLDEN["approval_digest"], "report": report}).encode("utf-8")
        self.assertEqual(len(header), GOLDEN["header_bytes"])
        self.assertEqual(sha256(header), GOLDEN["header_sha256"])
        plain = b"PRPKG2" + struct.pack(">i", len(header)) + header + photo()
        self.assertEqual(len(plain), GOLDEN["plain_bytes"])
        self.assertEqual(sha256(plain), GOLDEN["plain_sha256"])

    def test_escaping_and_number_vectors(self):
        e = GOLDEN["escaping_vector"]
        self.assertEqual(quote(e["input"]), e["output"])
        for n in GOLDEN["number_vectors"]:
            self.assertEqual(java_double(n["input"]), n["output"], n["input"])

    def test_canonical_object_vector(self):
        v = GOLDEN["canonical_object_vector"]
        self.assertEqual(canonical(v["input"]), v["output"])


class SchemaTest(unittest.TestCase):
    def report(self):
        return json.loads(GOLDEN["canonical_report"])

    def test_golden_report_and_header_are_valid(self):
        self.assertEqual(validate(self.report(), REPORT_SCHEMA), [])
        header = {"approval_digest": GOLDEN["approval_digest"], "report": self.report()}
        self.assertEqual(validate(header, HEADER_SCHEMA), [])

    def test_malformed_reports_are_rejected(self):
        def mutate(fn):
            r = self.report()
            fn(r)
            return r

        cases = {
            "missing key": mutate(lambda r: r.pop("plate")),
            "extra key": mutate(lambda r: r.update(extra=1)),
            "wrong schema number": mutate(lambda r: r.update(schema=2)),
            "boolean schema": mutate(lambda r: r.update(schema=True)),
            "uppercase hex": mutate(lambda r: r.update(review_digest=r["review_digest"].upper())),
            "short hash": mutate(lambda r: r.update(outgoing_sha256="ab")),
            "plate too long": mutate(lambda r: r.update(plate="123456789")),
            "empty address": mutate(lambda r: r.update(address="")),
            "latitude out of range": mutate(lambda r: r.update(latitude=91.0)),
            "longitude as string": mutate(lambda r: r.update(longitude="35.0")),
            "reporter missing email": mutate(lambda r: r["reporter"].pop("email")),
            "reporter extra key": mutate(lambda r: r["reporter"].update(nick="x")),
            "blank reporter phone": mutate(lambda r: r["reporter"].update(phone="")),
        }
        for name, report in cases.items():
            with self.subTest(name):
                self.assertNotEqual(validate(report, REPORT_SCHEMA), [], name)

    def test_header_needs_exactly_its_two_keys(self):
        good = {"approval_digest": GOLDEN["approval_digest"], "report": self.report()}
        self.assertNotEqual(validate({"report": good["report"]}, HEADER_SCHEMA), [])
        self.assertNotEqual(validate({**good, "extra": 1}, HEADER_SCHEMA), [])
        self.assertNotEqual(validate({**good, "approval_digest": "zz"}, HEADER_SCHEMA), [])

    def test_schema_keys_match_the_documented_thirteen(self):
        self.assertEqual(len(REPORT_SCHEMA["required"]), 13)
        self.assertEqual(set(REPORT_SCHEMA["required"]), set(REPORT_SCHEMA["properties"]))


if __name__ == "__main__":
    unittest.main()
