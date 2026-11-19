#!/usr/bin/env python3
"""Warm pinned Gradle plugins before tests; only exact KSP resolution may retry."""
import re
import http.client
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def retryable(output, version):
    # No task may have run: a task/compiler/test error is never resolution-only.
    if re.search(r'^> Task\b', output, re.M):
        return False
    marker = f"Plugin [id: 'com.google.devtools.ksp', version: '{version}', apply: false] was not found"
    artifact = f"could not resolve plugin artifact 'com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:{version}'"
    return marker in output and artifact in output and '* What went wrong:' in output


def central_probe(version, opener=None):
    """Two fixed-origin HEADs after terminal KSP refusal, no body or response text."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = opener or urllib.request.build_opener(NoRedirect())
    if not re.fullmatch(r'[0-9.]+', version):
        return
    for artifact in ('com.google.devtools.ksp.gradle.plugin', 'symbol-processing-gradle-plugin'):
        url = f'https://repo.maven.apache.org/maven2/com/google/devtools/ksp/{artifact}/{version}/{artifact}-{version}.pom'
        try:
            with opener.open(urllib.request.Request(url, method='HEAD'), timeout=5) as response:
                status = response.status
        except urllib.error.HTTPError as error:
            try:
                status = error.code
            finally:
                error.close()
        except (OSError, ValueError, http.client.HTTPException):
            status = 'network-unavailable'
        print(f'KSP Central HEAD {artifact}: {status}. Diagnostic only, not a build pass.', flush=True)


def warm(run=subprocess.run, pause=time.sleep, probe=central_probe):
    source = (ROOT / 'build.gradle.kts').read_text()
    match = re.search(r'id\("com\.google\.devtools\.ksp"\) version "([0-9.]+)" apply false', source)
    if not match:
        print('Pinned KSP declaration not recognized; no resolution retry.', flush=True)
        return 2
    for attempt in range(3):
        # `help` configures projects/plugins, but does not compile or run tests.
        # Gradle caches missing artifacts. A later invocation must recheck those
        # entries instead of returning the same negative-cache hit immediately.
        command = ['sh', 'gradlew', '--no-daemon']
        if attempt:
            command.append('--refresh-dependencies')
        command.append('help')
        result = run(command, cwd=ROOT,
                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180)
        print(result.stdout, end='', flush=True)
        if result.returncode == 0:
            return 0
        exact_ksp = retryable(result.stdout, match.group(1))
        if attempt == 2 or not exact_ksp:
            if exact_ksp:
                probe(match.group(1))
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
