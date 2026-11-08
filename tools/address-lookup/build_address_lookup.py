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
Deps: python3 only. Network: Overpass + data.gov.il.
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
# National street registry (רשות האוכלוסין וההגירה) on data.gov.il:
# locality 3000 = Jerusalem; סמל_רחוב is the municipal street code the
# 106 API uses (verified: אגריפס -> 613, matching the pipeline lookup).
DATASTORE_URL = "https://data.gov.il/api/3/action/datastore_search"
STREETS_RESOURCE_ID = "9ad3862c-8391-4b2f-84a4-2d4c68625f4b"
JERUSALEM_LOCALITY = "3000"

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
    """Street name -> municipal street code, from the national registry."""
    streets = {}
    offset = 0
    while True:
        query = urllib.parse.urlencode({
            "resource_id": STREETS_RESOURCE_ID,
            "filters": json.dumps({"סמל_ישוב": JERUSALEM_LOCALITY}),
            "limit": 5000,
            "offset": offset,
        })
        data = json.loads(fetch(f"{DATASTORE_URL}?{query}"))
        records = data["result"]["records"]
        for row in records:
            name = str(row.get("שם_רחוב") or "").strip()
            code = row.get("סמל_רחוב")
            if not name or code is None:
                continue
            streets[str(code)] = {
                "StreetName": name,
                "StreetCode": str(code),
                "CityName": str(row.get("שם_ישוב") or "").strip(),
                "CityCode": str(row.get("סמל_ישוב") or "").strip(),
            }
        offset += len(records)
        if offset >= data["result"]["total"] or not records:
            break
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
