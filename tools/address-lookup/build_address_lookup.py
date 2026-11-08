#!/usr/bin/env python3
"""Build the app's version-1 address lookup from open, attributed sources.

Sources (both ODbL - see README.md):
  * Street codes/names: Jerusalem municipality open-data portal
    (jerusalem.datacity.org.il) "צירי דרכים בירושלים" layer.
  * Address points (house numbers): OpenStreetMap via the Overpass API,
    (c) OpenStreetMap contributors.

Output: the same {"version": 1, "streets": [...], "addresses": [...]}
shape the helper pipeline and the app's StreetDirectory load.

Usage:
  python3 build_address_lookup.py --out addresses.json
Deps: python3 + openpyxl (pip install openpyxl). Network: Overpass + the
municipal open-data portal.
"""
import argparse
import json
import sys
import time
import urllib.parse
import urllib.request

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
# Jerusalem municipality boundary (OSM relation 1381350).
OVERPASS_QUERY = """
[out:json][timeout:180];
area(3601381350)->.jerusalem;
(
  node["addr:housenumber"]["addr:street"](area.jerusalem);
  way["addr:housenumber"]["addr:street"](area.jerusalem);
);
out center tags;
"""
STREETS_XLSX_URL = (
    "https://jerusalem.datacity.org.il/dataset/"
    "8be07be2-c31c-4a53-bd0e-28cb1ff84333/resource/"
    "902ecb1b-ae98-455a-b755-ba5b4285d59c/download/-.xlsx"
)

HEBREW_FINALS = str.maketrans({"ך": "כ", "ם": "מ", "ן": "נ", "ף": "פ", "ץ": "צ"})


def normalize(text):
    import unicodedata
    folded = unicodedata.normalize("NFKC", text).translate(HEBREW_FINALS)
    return "".join(c for c in folded if c.isalnum())


def fetch(url, data=None, retries=3):
    for attempt in range(retries):
        try:
            req = urllib.request.Request(
                url, data=data,
                headers={"User-Agent": "parking-reporter-app address-lookup builder (ODbL attribution kept)"})
            with urllib.request.urlopen(req, timeout=200) as resp:
                return resp.read()
        except Exception as exc:  # noqa: BLE001 - retry boundary
            if attempt == retries - 1:
                raise
            wait = 20 * (attempt + 1)
            print(f"fetch failed ({exc}); retrying in {wait}s", file=sys.stderr)
            time.sleep(wait)


def load_streets():
    """Street name -> municipal street code, from the ODbL municipal layer."""
    import io
    import openpyxl
    raw = fetch(STREETS_XLSX_URL)
    book = openpyxl.load_workbook(io.BytesIO(raw), read_only=True)
    sheet = book.active
    rows = sheet.iter_rows(values_only=True)
    header = [str(c).strip() if c else "" for c in next(rows)]
    col = {name: i for i, name in enumerate(header)}
    needed = ("STREET", "STREETCODE", "CITYNAME", "CITYCODE")
    missing = [n for n in ("STREET", "STREETCODE") if n not in col]
    if missing:
        raise SystemExit(f"municipal layer missing columns {missing}: header={header}")
    streets = {}
    for row in rows:
        name = row[col["STREET"]]
        code = row[col["STREETCODE"]]
        if not name or code is None:
            continue
        code = str(code).strip()
        entry = streets.setdefault(code, {
            "StreetName": str(name).strip(),
            "StreetCode": code,
        })
        if "CITYNAME" in col and row[col["CITYNAME"]]:
            entry["CityName"] = str(row[col["CITYNAME"]]).strip()
        if "CITYCODE" in col and row[col["CITYCODE"]] is not None:
            entry["CityCode"] = str(row[col["CITYCODE"]]).strip()
    # One street name must map to ONE code, like the pipeline's lookup.
    by_name = {}
    for code, entry in streets.items():
        by_name.setdefault(normalize(entry["StreetName"]), set()).add(code)
    ambiguous = {n: c for n, c in by_name.items() if len(c) > 1}
    for name in ambiguous:
        for code in ambiguous[name]:
            del streets[code]
    print(f"streets: {len(streets)} rows ({len(ambiguous)} ambiguous names dropped)",
          file=sys.stderr)
    return list(streets.values()), by_name


def load_address_points():
    """OSM addr:housenumber + addr:street points inside Jerusalem."""
    payload = urllib.parse.urlencode({"data": OVERPASS_QUERY}).encode()
    raw = fetch(OVERPASS_URL, data=payload)
    data = json.loads(raw)
    points = []
    for el in data.get("elements", []):
        tags = el.get("tags", {})
        house = (tags.get("addr:housenumber") or "").strip()
        street = (tags.get("addr:street") or "").strip()
        if not house or not street:
            continue
        if el.get("type") == "node":
            lat, lon = el.get("lat"), el.get("lon")
        else:
            center = el.get("center") or {}
            lat, lon = center.get("lat"), center.get("lon")
        if lat is None or lon is None:
            continue
        points.append([round(float(lat), 6), round(float(lon), 6), house, street])
    print(f"addresses: {len(points)} OSM points", file=sys.stderr)
    return points


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default="addresses.json")
    args = parser.parse_args()
    streets, _ = load_streets()
    addresses = load_address_points()
    out = {"version": 1, "streets": streets, "addresses": addresses}
    with open(args.out, "w", encoding="utf-8") as fh:
        json.dump(out, fh, ensure_ascii=False, indent=1)
        fh.write("\n")
    print(f"wrote {args.out}: {len(streets)} streets, {len(addresses)} addresses",
          file=sys.stderr)


if __name__ == "__main__":
    main()
