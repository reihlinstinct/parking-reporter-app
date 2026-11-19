#!/usr/bin/env python3
"""Bounded natural-reproduction control for the merged API29 heap preflight."""
import argparse
import json
from pathlib import Path
import time
import subprocess

from api29_heap_preflight import adb, budget, gate, restart, BAD_BOOT, EXPECTED
from emulator_preflight import gate as readiness


def reboot():
    adb('reboot')
    adb('wait-for-device', timeout=150)
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        try:
            if (adb('shell', 'getprop', 'sys.boot_completed').strip() == '1'
                    and 'package:' in adb('shell', 'cmd', 'package', 'path', 'il.sidewalks.reporter')):
                adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
                adb('shell', 'wm', 'dismiss-keyguard')
                adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                return
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        time.sleep(3)
    raise RuntimeError('bounded cold boot failed')


def investigate(read, configured, recover, cold_boot, maximum=8):
    rows = []
    for ordinal in range(1, maximum + 1):
        if ordinal > 1:
            cold_boot()
        if configured() != '512m':
            raise RuntimeError('configured budget changed; no experiment permitted')
        row = read('cold-' + str(ordinal))
        rows.append(row)
        if row['javaMaxBytes'] == BAD_BOOT:
            # Invoke exactly the merged gate. Its own fresh before/after probes
            # prove the same measured condition and recovery in this session.
            result = gate(read, configured, recover)
            if not result['recoveryAttempted']:
                raise RuntimeError('bad process did not persist to control; inconclusive')
            return dict(status='recovered', coldBoots=rows, control=result)
        if row['javaMaxBytes'] != EXPECTED:
            raise RuntimeError('unknown process budget, experiment stopped')
    return dict(status='not-reproduced', coldBoots=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report-dir', required=True)
    args = parser.parse_args()
    directory = Path(args.report_dir)
    directory.mkdir(parents=True, exist_ok=True)
    if adb('shell', 'getprop', 'ro.build.version.sdk').strip() != '29':
        raise RuntimeError('this diagnostic requires API29')
    if readiness() != 0:
        raise RuntimeError('initial readiness failed')
    def cold_boot():
        reboot()
        # No second recovery reboot: unstable boots stop the experiment.
        if readiness(reboot=lambda: (_ for _ in ()).throw(RuntimeError('no additional recovery'))) != 0:
            raise RuntimeError('cold boot readiness failed')
    result = investigate(lambda label: budget(directory, label),
                         lambda: adb('shell', 'getprop', 'dalvik.vm.heapsize').strip(),
                         restart, cold_boot)
    (directory / 'recovery-proof.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)
    # Full suite runs once afterward even when all boots were healthy. Its PASS
    # never upgrades not-reproduced to recovery proof.


if __name__ == '__main__':
    main()
