#!/usr/bin/env python3
"""Aggregate mock-only cold and warm preparation timings. Offline.

Input: a directory of JSON files {"mode": "cold"|"warm", "run": int,
"phases": {name: seconds}, "cache_bytes": int|null}. It prints a markdown table
of medians. It states a saving only with at least three cold and three warm
runs, and says "not measured" otherwise.
"""
import math
import json
import statistics
import sys
from pathlib import Path

MIN_RUNS = 3
PHASES = ('sdk_install', 'apk_build', 'cache_restore', 'boot_to_ready', 'apk_install', 'preflight', 'total')


def load(directory: Path):
    runs = {'cold': [], 'warm': [], 'warm-fell-back-cold': []}
    seen = set()
    for f in sorted(directory.glob('*.json')):
        d = json.loads(f.read_text())
        if d.get('mode') not in runs or not isinstance(d.get('phases'), dict):
            raise ValueError('bad timing file')
        run = d.get('run')
        if type(run) is not int or run < 0 or (d['mode'], run) in seen:
            raise ValueError('invalid or repeated benchmark run')
        seen.add((d['mode'], run))
        if d.get('cache_bytes') is not None and (type(d['cache_bytes']) is not int or d['cache_bytes'] < 0):
            raise ValueError('invalid cache size')
        if any(type(v) not in (int, float) or not math.isfinite(v) or v < 0 for v in d['phases'].values()):
            raise ValueError('bad phase value')
        runs[d['mode']].append(d)
    return runs


def median(runs, phase):
    vals = [r['phases'][phase] for r in runs if phase in r['phases']]
    return statistics.median(vals) if len(vals) == len(runs) and vals else None


def report(runs) -> str:
    cold, warm = runs['cold'], runs['warm']
    lines = [f'cold runs: {len(cold)}, warm runs: {len(warm)}, warm runs that fell back to cold (excluded): {len(runs["warm-fell-back-cold"])}', '',
             '| phase | cold median s | warm median s | difference s |', '|---|---|---|---|']
    for ph in PHASES:
        c, w = median(cold, ph), median(warm, ph)
        diff = '' if c is None or w is None else f'{c - w:+.0f}'
        lines.append(f"| {ph} | {'' if c is None else f'{c:.0f}'} | {'' if w is None else f'{w:.0f}'} | {diff} |")
    sizes = [r['cache_bytes'] for r in warm if isinstance(r.get('cache_bytes'), int)]
    if sizes:
        lines += ['', f'cache size: {statistics.median(sizes) / 1e6:.0f} MB']
    lines.append('')
    if len(cold) < MIN_RUNS or len(warm) < MIN_RUNS:
        lines.append(f'Saving: not measured. Need at least {MIN_RUNS} cold and {MIN_RUNS} warm runs.')
    else:
        c, w = median(cold, 'total'), median(warm, 'total')
        if c is None or w is None:
            lines.append('Saving: not measured. Runs lack a total phase.')
        else:
            lines.append(f'Measured median total preparation: cold {c:.0f}s, warm {w:.0f}s, saving {c - w:+.0f}s.')
    return '\n'.join(lines)


if __name__ == '__main__':
    print(report(load(Path(sys.argv[1]))))
