#!/usr/bin/env python3
"""Warm pinned Gradle plugins before tests; only exact KSP resolution may retry."""
import re
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def retryable(output, version):
    # No task may have run: a task/compiler/test error is never resolution-only.
    if re.search(r'^> Task\b', output, re.M):
        return False
    marker = f"Plugin [id: 'com.google.devtools.ksp', version: '{version}', apply: false] was not found"
    artifact = f"could not resolve plugin artifact 'com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:{version}'"
    return marker in output and artifact in output and '* What went wrong:' in output


def warm(run=subprocess.run, pause=time.sleep):
    source = (ROOT / 'build.gradle.kts').read_text()
    match = re.search(r'id\("com\.google\.devtools\.ksp"\) version "([0-9.]+)" apply false', source)
    if not match:
        print('Pinned KSP declaration not recognized; no resolution retry.', flush=True)
        return 2
    for attempt in range(3):
        # `help` configures projects/plugins, but does not compile or run tests.
        result = run(['sh', 'gradlew', '--no-daemon', 'help'], cwd=ROOT,
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180)
        print(result.stdout, end='', flush=True)
        if result.returncode == 0:
            return 0
        if attempt == 2 or not retryable(result.stdout, match.group(1)):
            return result.returncode
        wait = (10, 30)[attempt]
        print(f'Pinned KSP resolution failed before tasks; retry {attempt + 2}/3 after {wait}s. No test retry.', flush=True)
        pause(wait)
    return 1


if __name__ == '__main__':
    try:
        raise SystemExit(warm())
    except subprocess.TimeoutExpired:
        print('Plugin-resolution preflight timed out; no automatic retry.', flush=True)
        raise SystemExit(1)
