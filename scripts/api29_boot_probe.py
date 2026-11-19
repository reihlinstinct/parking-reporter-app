#!/usr/bin/env python3
"""Bounded synthetic-only investigation of the API29 zygote heap mismatch."""
import json
from pathlib import Path
import subprocess
import time

OUT = Path('app/build/reports/api29-memory')
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, check=True, timeout=60):
    return subprocess.run(['adb', *args], capture_output=True, text=True,
                          check=check, timeout=timeout).stdout


def ready():
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        result = subprocess.run(['adb', 'shell', 'cmd', 'package', 'path',
                                 'il.sidewalks.reporter'], capture_output=True,
                                text=True, timeout=15)
        if result.returncode == 0 and 'package:' in result.stdout:
            return
        time.sleep(3)
    raise RuntimeError('Package service did not become ready, no further reboot')


def capture(label):
    (OUT / (label + '-boot-logcat.txt')).write_text(adb('logcat', '-b', 'all', '-d', '-v', 'threadtime'))
    (OUT / (label + '-properties.txt')).write_text(adb('shell', 'getprop'))
    (OUT / (label + '-processes.txt')).write_text(adb('shell', 'ps', '-A'))


def probe(label):
    adb('logcat', '-c')
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'listener',
                 'io.github.reihlinstinct.reporter.MemoryTraceListener', '-e', 'memoryTrace', '1',
                 '-e', 'class', 'io.github.reihlinstinct.reporter.HeapBudgetProbeTest',
                 'il.sidewalks.reporter.test/androidx.test.runner.AndroidJUnitRunner')
    (OUT / (label + '-instrumentation.txt')).write_text(output)
    if 'OK (1 test)' not in output:
        raise RuntimeError('Probe did not run exactly one passing test')
    text = adb('logcat', '-d', '-v', 'threadtime')
    (OUT / (label + '-logcat.txt')).write_text(text)
    rows = [json.loads(line[line.index('{'):]) for line in text.splitlines()
            if 'API29_MEMORY' in line and '{' in line]
    if len(rows) != 2 or {r['phase'] for r in rows} != {'before', 'after'}:
        raise RuntimeError('Probe metadata missing or ambiguous')
    maxima = {r['javaMaxBytes'] for r in rows}
    if len(maxima) != 1:
        raise RuntimeError('Probe maximum changed within one process')
    return rows[0]


def main():
    summary = dict(status='not_reproduced',coldBoots=[],heapPropertiesChanged=False)
    for ordinal in range(1, 9):
        label = f'cold-{ordinal}'
        if ordinal > 1:
            adb('reboot')
            adb('wait-for-device', timeout=150)
            ready()
        capture(label)
        before = probe(label)
        summary['coldBoots'].append(before)
        (OUT / 'boot-probe-summary.json').write_text(json.dumps(summary, indent=2))
        print('Cold boot', ordinal, 'runtimeMax', before['javaMaxBytes'],
              'memoryClassMiB', before['memoryClassMiB'], flush=True)
        if before['javaMaxBytes'] not in (16777216, 536870912):
            summary.update(status='stopped_unexpected_budget', unexpectedBudget=before['javaMaxBytes'])
            (OUT / 'boot-probe-summary.json').write_text(json.dumps(summary, indent=2))
            raise RuntimeError('Unexpected process budget; experiment stopped without intervention')
        if before['javaMaxBytes'] == 16777216:
            # A control intervention on this synthetic emulator only. No heap
            # property, app manifest, APK or production workflow is changed.
            properties = adb('shell', 'getprop', 'dalvik.vm.heapsize').strip()
            if properties != '512m':
                raise RuntimeError('Expected configured512m absent; refuse control intervention')
            adb('shell', 'su', 'root', 'setprop', 'ctl.restart', 'zygote')
            time.sleep(5)
            ready()
            capture('warm-control')
            after = probe('warm-control')
            summary.update(status='reproduced_with_control',propertyBeforeRestart=properties,
                           before=before,after=after,
                           restoredConfiguredBudget=after['javaMaxBytes'] == 536870912)
            (OUT / 'boot-probe-summary.json').write_text(json.dumps(summary, indent=2))
            print('Warm control runtimeMax', after['javaMaxBytes'], flush=True)
            break


if __name__ == '__main__':
    main()
