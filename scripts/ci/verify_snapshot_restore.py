#!/usr/bin/env python3
"""One bounded warm-boot proof. No build, cold fallback, reboot, or cache save."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time


class RestoreRejected(Exception):
    pass


def check_guest(run=subprocess.run):
    def adb(*args):
        return run(['adb', '-s', 'emulator-5554', *args], check=True,
                   capture_output=True, text=True, timeout=15).stdout
    if adb('shell', 'getprop', 'sys.boot_completed').strip() != '1':
        raise RestoreRejected('guest_not_ready')
    for package in ('il.sidewalks.reporter', 'il.sidewalks.reporter.test'):
        if not adb('shell', 'pm', 'path', package).strip().startswith('package:'):
            raise RestoreRejected('preinstalled_package_missing')
    # Readiness only. No reboot/recovery: a cold recovery must not pass as warm.
    from emulator_preflight import unhealthy
    if unhealthy('1', adb('shell', 'dumpsys', 'window'),
                 adb('shell', 'dumpsys', 'activity', 'processes')):
        raise RestoreRejected('guest_unhealthy')


def prove(popen=subprocess.Popen, run=subprocess.run, clock=time.monotonic,
          pause=time.sleep, killpg=os.killpg):
    emulator = Path(os.environ.get('ANDROID_HOME', '/usr/local/lib/android/sdk')) / 'emulator/emulator'
    # Full logs stay only in a temporary file and are neither printed nor uploaded.
    with tempfile.TemporaryFile() as log:
        child = popen([str(emulator), '-avd', 'test', '-port', '5554', '-snapshot',
                       'default_boot', '-no-snapshot-save', '-no-window', '-gpu',
                       'swiftshader_indirect', '-noaudio', '-no-boot-anim',
                       '-camera-back', 'none'], stdout=log, stderr=log,
                      start_new_session=True)
        deadline = clock() + 180
        try:
            while clock() < deadline:
                if child.poll() is not None:
                    raise RestoreRejected('emulator_exited')
                if os.fstat(log.fileno()).st_size > 8 * 1024 * 1024:
                    raise RestoreRejected('log_size_limit')
                # Independent file offset avoids moving the writer's offset.
                data = os.pread(log.fileno(), 8 * 1024 * 1024, 0)
                if b"Failed to load snapshot" in data or b'cold boot' in data.lower():
                    raise RestoreRejected('cold_fallback_or_load_failure')
                if b"Successfully loaded snapshot 'default_boot'" in data:
                    try:
                        check_guest(run)
                    except (subprocess.SubprocessError, RestoreRejected):
                        pass  # Boot/ADB may not yet be stable. Never reboot.
                    else:
                        print('SNAPSHOT-WARM-RESTORE-VERIFIED')
                        return
                pause(2)
            raise RestoreRejected('warm_restore_timeout')
        finally:
            try:
                killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait()


def main():
    try:
        prove()
        return 0
    except (RestoreRejected, OSError, subprocess.SubprocessError):
        print('SNAPSHOT-WARM-RESTORE-REJECTED')
        return 3


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    raise SystemExit(main())
