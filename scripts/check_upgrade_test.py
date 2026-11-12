#!/usr/bin/env python3
"""An upgrade proof must execute its one expected test, never skip it."""
import sys
from test_summary import load_instrumentation


def check(path, suite, name):
    cases = load_instrumentation(path)
    if len(cases) != 1 or (cases[0].suite, cases[0].name, cases[0].status) != (suite, name, 'passed'):
        raise ValueError('upgrade proof requires exactly one expected passed test, zero skips and zero failures')
    return '1 passed, 0 skipped, 0 failed: ' + suite + '.' + name


if __name__ == '__main__':
    try:
        print(check(*sys.argv[1:]))
    except (ValueError, OSError, TypeError) as error:
        sys.exit(str(error))
