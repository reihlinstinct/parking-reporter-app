#!/usr/bin/env python3
"""Bounded emulator health gate. Never dismiss dialogs or retry app tests."""
import argparse
from pathlib import Path
import re
import subprocess
import sys
import time


def adb(*args, timeout=20):
    result = subprocess.run(['adb', *args], capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError('adb command failed')
    return result.stdout


def unhealthy(boot, windows, processes):
    if boot.strip() != '1':
        return 'boot incomplete'
    if re.search(r'(notResponding|crashing)\s*=\s*true', processes, re.I):
        return 'active process ANR/crash'
    focus = '\n'.join(line for line in windows.splitlines() if 'mCurrentFocus=' in line or 'mFocusedApp=' in line)
    if re.search(r'Application (Error|Not Responding)|App Not Responding|AppErrorDialog', focus, re.I):
        return 'system error dialog has focus'
    if not re.search(r'mCurrentFocus=Window\{', focus):
        return 'no focused window'
    return None


def probe():
    return unhealthy(adb('shell', 'getprop', 'sys.boot_completed'),
                     adb('shell', 'dumpsys', 'window', 'windows'),
                     adb('shell', 'dumpsys', 'activity', 'processes'))


def recover():
    # Reboot once rather than masking an ANR dialog with a click. App tests have
    # not started, so no test assertions or uncertain external writes can repeat.
    adb('reboot')
    adb('wait-for-device', timeout=120)
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        try:
            if adb('shell', 'getprop', 'sys.boot_completed').strip() == '1':
                adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
                adb('shell', 'wm', 'dismiss-keyguard')
                adb('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                time.sleep(10)
                return
        except (RuntimeError, subprocess.TimeoutExpired):
            pass
        time.sleep(3)
    raise RuntimeError('recovery boot timed out')


def gate(check=probe, reboot=recover, pause=time.sleep):
    # Two stable observations before allowing UI tests. A single recovery only.
    for attempt in range(2):
        reason = None
        for _ in range(2):
            try:
                reason = check()
            except (RuntimeError, subprocess.TimeoutExpired):
                reason = 'device probe unavailable'
            if reason:
                break
            pause(3)
        if not reason:
            print('PREFLIGHT PASS: stable boot and window focus; no active ANR/crash')
            return 0
        print('PREFLIGHT unhealthy:', reason)
        if attempt == 0:
            print('PREFLIGHT recovery: one bounded reboot before tests')
            try:
                reboot()
            except (RuntimeError, subprocess.TimeoutExpired):
                print('PREFLIGHT FAIL: recovery unavailable')
                return 1
    print('PREFLIGHT FAIL: emulator still unhealthy after one recovery; tests not started')
    return 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--report-dir', required=True)
    args = parser.parse_args()
    directory = Path(args.report_dir)
    directory.mkdir(parents=True, exist_ok=True)
    code = gate()
    (directory / 'preflight.rc').write_text(str(code) + '\n')
    if code:
        # System diagnostics only, before any synthetic app flow or private input.
        for name, command in [('preflight-window.txt', ('shell', 'dumpsys', 'window', 'windows')),
                              ('preflight-processes.txt', ('shell', 'dumpsys', 'activity', 'processes'))]:
            try:
                (directory / name).write_text(adb(*command))
            except (RuntimeError, subprocess.TimeoutExpired):
                (directory / name).write_text('device diagnostics unavailable\n')
    return code


if __name__ == '__main__':
    sys.exit(main())
