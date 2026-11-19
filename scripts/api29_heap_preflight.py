#!/usr/bin/env python3
"""Fail-closed API29 emulator heap check, before the full application suite."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time

EXPECTED = 536870912
BAD_BOOT = 16777216
PACKAGE = 'il.sidewalks.reporter'
PROBE = 'io.github.reihlinstinct.reporter.CiHeapBudgetProbeTest'


def adb(*args, timeout=30):
    result = subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('heap preflight adb command failed')
    return result.stdout


def budget(directory, label):
    adb('shell', 'run-as', PACKAGE, 'rm', '-f', 'files/ci-heap-probe.json')
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', PROBE,
                 PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner', timeout=90)
    (directory / (label + '-probe.txt')).write_text(output)
    if 'OK (1 test)' not in output or 'FAILURES!!!' in output:
        raise RuntimeError('heap probe did not run exactly one passing test')
    row = json.loads(adb('shell', 'run-as', PACKAGE, 'cat', 'files/ci-heap-probe.json'))
    if (type(row.get('pid')) is not int or row['pid'] <= 0
            or type(row.get('javaMaxBytes')) is not int or row['javaMaxBytes'] <= 0):
        raise RuntimeError('heap probe returned invalid process metadata')
    (directory / (label + '-heap.json')).write_text(json.dumps(row, indent=2) + '\n')
    return row


def restart():
    from emulator_preflight import probe as device_problem
    old = adb('shell', 'pidof', 'zygote64').strip()
    if not old.isdecimal():
        raise RuntimeError('cannot identify one 64-bit zygote')
    # Restore neither a property nor an app: restart Android's runtime after
    # its existing configured budget has settled. Never reboot into the race.
    adb('shell', 'su', 'root', 'setprop', 'ctl.restart', 'zygote')
    deadline = time.monotonic() + 150
    while time.monotonic() < deadline:
        try:
            new = adb('shell', 'pidof', 'zygote64').strip()
            if (new.isdecimal() and new != old
                    and 'package:' in adb('shell', 'cmd', 'package', 'path', PACKAGE)):
                adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
                adb('shell', 'wm', 'dismiss-keyguard')
                adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                if device_problem() is None:
                    return
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        time.sleep(3)
    raise RuntimeError('bounded Android runtime restart timed out')


def gate(read, property_value, recover):
    if property_value() != '512m':
        raise RuntimeError('unexpected configured API29 heap; no recovery permitted')
    before = read('before')
    result = {'before': before, 'recoveryAttempted': False, 'heapPropertiesChanged': False}
    if before['javaMaxBytes'] == EXPECTED:
        return result
    if before['javaMaxBytes'] != BAD_BOOT:
        raise RuntimeError('unexpected actual API29 heap; refuse to mask it')
    recover()
    if property_value() != '512m':
        raise RuntimeError('configured heap changed during runtime restart')
    after = read('after')
    if after['pid'] == before['pid'] or after['javaMaxBytes'] != EXPECTED:
        raise RuntimeError('fresh process did not receive the configured heap after one restart')
    result.update(after=after, recoveryAttempted=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report-dir', required=True)
    args = parser.parse_args()
    directory = Path(args.report_dir)
    directory.mkdir(parents=True, exist_ok=True)
    result = {'status': 'failure', 'recoveryAttempted': False}
    try:
        if adb('shell', 'getprop', 'ro.build.version.sdk').strip() != '29':
            result = {'status': 'not-applicable', 'recoveryAttempted': False}
            return 0
        def recover():
            # Written before the attempt, so a failed restart cannot look clean.
            result['recoveryAttempted'] = True
            (directory / 'heap-recovery-attempted.txt').write_text('One runtime restart requested before application tests.\n')
            restart()
        result = dict(status='success', **gate(lambda label: budget(directory, label),
                      lambda: adb('shell', 'getprop', 'dalvik.vm.heapsize').strip(), recover))
        return 0
    except (RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as error:
        # Exception class only; subprocess output is retained in fixed artifacts.
        result['errorType'] = type(error).__name__
        return 1
    finally:
        (directory / 'heap-preflight.json').write_text(json.dumps(result, indent=2) + '\n')
        print('API29 heap preflight:', json.dumps(result), flush=True)
        target = os.environ.get('GITHUB_STEP_SUMMARY')
        if target:
            with open(target, 'a', encoding='utf-8') as summary:
                summary.write('## API29 emulator heap preflight\n\n```json\n' + json.dumps(result, indent=2) + '\n```\n\n'
                              'Runtime restart, if requested, is before the full suite. No app tests are retried and no heap property is changed.\n')


if __name__ == '__main__':
    raise SystemExit(main())
