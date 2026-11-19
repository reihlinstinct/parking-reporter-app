#!/usr/bin/env python3
"""An upgrade proof must execute its one expected test, never skip it."""
import sys
import pathlib
import re
from test_summary import load_instrumentation


def check(path, suite, name):
    raw = pathlib.Path(path).read_text()
    if not re.search(r'^OK \(1 test\)\s*$', raw, re.MULTILINE) or not re.search(r'^INSTRUMENTATION_CODE: -1\s*$', raw, re.MULTILINE):
        raise ValueError('upgrade proof requires successful final OK footer and instrumentation result')
    if re.search(r'INSTRUMENTATION_FAILED|FAILURES!!!|Process crashed|shortMsg=', raw):
        raise ValueError('upgrade proof contains runner failure')
    cases = load_instrumentation(path)
    if len(cases) != 1 or (cases[0].suite, cases[0].name, cases[0].status) != (suite, name, 'passed'):
        raise ValueError('upgrade proof requires exactly one expected passed test, zero skips and zero failures')
    return '1 passed, 0 skipped, 0 failed: ' + suite + '.' + name


if __name__ == '__main__':
    try:
        print(check(*sys.argv[1:]))
    except (ValueError, OSError, TypeError) as error:
        sys.exit(str(error))
