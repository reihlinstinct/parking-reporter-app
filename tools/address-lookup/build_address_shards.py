#!/usr/bin/env python3
"""Build deterministic geographic address shards from a pinned public dataset.

Boundary input must be a separately reviewed WGS84 GeoJSON FeatureCollection.
No geometry is inferred from street names. Uncovered points stop generation.
"""
import argparse
import hashlib
import json
import re
import unicodedata
from pathlib import Path

PIN = '5eaa83a9eb9253798109555bf2f6ea6ab6ec941a86e86c15c6761b2e5ee046d4'
FINALS = str.maketrans('ךםןףץ', 'כמנפצ')


def norm(value):
    return ''.join(c for c in unicodedata.normalize('NFKC', value).translate(FINALS) if c.isalnum())


def words(value):
    # Use Unicode categories, matching the app's letter/number token boundaries.
    tokens, token = [], ''
    for char in value:
        if char.isalnum():
            token += char
        elif token:
            tokens.append(norm(token)); token = ''
    if token:
        tokens.append(norm(token))
    return tokens


def on_segment(point, a, b):
    x, y = point; ax, ay = a; bx, by = b
    return (abs((y-ay)*(bx-ax)-(x-ax)*(by-ay)) <= 1e-12
            and min(ax,bx)-1e-12 <= x <= max(ax,bx)+1e-12
            and min(ay,by)-1e-12 <= y <= max(ay,by)+1e-12)


def ring_contains(ring, point):
    inside = False
    x,y = point
    for a,b in zip(ring, ring[1:] + ring[:1]):
        if (a[1] > y) != (b[1] > y) and x < (b[0]-a[0])*(y-a[1])/(b[1]-a[1])+a[0]:
            inside = not inside
    return inside


def contains(polygons, point):
    for rings in polygons:
        outer=rings[0]
        if not (min(p[0] for p in outer) <= point[0] <= max(p[0] for p in outer) and
                min(p[1] for p in outer) <= point[1] <= max(p[1] for p in outer)):
            continue
        if any(on_segment(point,a,b) for ring in rings for a,b in zip(ring,ring[1:]+ring[:1])):
            return True
        if ring_contains(rings[0],point) and not any(ring_contains(r,point) for r in rings[1:]):
            return True
    return False


def build(dataset, boundaries, allow_outside=False):
    regions = []
    for feature in boundaries['features']:
        rid = str(feature['properties']['id'])
        if not re.fullmatch('[a-z0-9-]+', rid):
            raise ValueError('unsafe region id')
        geometry = feature['geometry']
        if geometry['type'] not in ('Polygon','MultiPolygon'):
            raise ValueError('polygon geometry required')
        polygons = [geometry['coordinates']] if geometry['type'] == 'Polygon' else geometry['coordinates']
        regions.append(dict(id=rid, polygons=polygons, rows=[], codes=set()))
    if not regions or len({r['id'] for r in regions}) != len(regions):
        raise ValueError('missing or duplicate regions')
    streets = [{k:r[k] for k in ('StreetCode','StreetName','StreetArabicName','aliases') if k in r}
               for r in dataset['streets']]
    aliases = {}
    for r in streets:
        for name in [r['StreetName'], r.get('StreetArabicName','')] + r.get('aliases',[]):
            if isinstance(name,str) and norm(name):aliases.setdefault(norm(name),set()).add(str(r['StreetCode']))
    outside=dict(id='outside-cbs-coverage',polygons=[],rows=[],codes=set())
    street_words=[(str(r['StreetCode']),words(r['StreetName'])) for r in streets]
    for region in regions:
        pts=[p for poly in region['polygons'] for ring in poly for p in ring]
        region['bbox']=[min(p[0] for p in pts),min(p[1] for p in pts),max(p[0] for p in pts),max(p[1] for p in pts)]
    for row in dataset['addresses']:
        point = [row[1],row[0]]
        matching = [r for r in regions if r['bbox'][0]<=point[0]<=r['bbox'][2] and
                    r['bbox'][1]<=point[1]<=r['bbox'][3] and contains(r['polygons'],point)]
        if not matching:
            if not allow_outside:raise ValueError('address point outside boundary coverage')
            matching=[outside]
        codes = aliases.get(norm(str(row[3])),set())
        if not codes:
            tail=words(str(row[3]))
            if tail:
                candidates={code for code,w in street_words if len(w)>len(tail) and w[-len(tail):]==tail}
                if len(candidates)==1:codes=candidates
        for region in matching:
            region['rows'].append(row)
            region['codes'].update(codes)
    if outside['rows']:regions.append(outside)
    files={}
    index=dict(version=1, sourceSha256=PIN, streets=streets, regions=[])
    for r in sorted(regions,key=lambda r:r['id']):
        payload=encode(dict(version=1,polygons=r['polygons'],addresses=r['rows']))
        files[r['id']+'.json']=payload
        index['regions'].append(dict(id=r['id'],sha256=hashlib.sha256(payload).hexdigest(),
            bbox=r.get('bbox'),streetCodes=sorted(r['codes'])))
    files['index.json']=encode(index)
    return files


def encode(value):
    return (json.dumps(value,ensure_ascii=False,separators=(',',':'),allow_nan=False)+'\n').encode()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--dataset',required=True,type=Path)
    parser.add_argument('--polygons',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--allow-outside-cbs-coverage',action='store_true')
    args=parser.parse_args()
    raw=args.dataset.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=PIN:raise ValueError('dataset pin mismatch')
    files=build(json.loads(raw),json.loads(args.polygons.read_bytes()),args.allow_outside_cbs_coverage)
    args.output.mkdir(parents=True,exist_ok=True)
    for name,data in files.items():(args.output/name).write_bytes(data)
    print('index-sha256',hashlib.sha256(files['index.json']).hexdigest())
    print('shards',len(files)-1,'index-bytes',len(files['index.json']))


if __name__=='__main__':main()
