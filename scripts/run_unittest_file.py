#!/usr/bin/env python3
"""Run one unittest file and fail on zero tests, import errors or test failures."""
import sys
from pathlib import Path
import unittest


def main():
    if len(sys.argv) != 2:
        return 1
    path = Path(sys.argv[1]).resolve()
    if not path.is_file() or path.suffix != '.py':
        return 1
    suite = unittest.defaultTestLoader.discover(str(path.parent), pattern=path.name)
    if suite.countTestCases() == 0:
        print('ZERO_TESTS', file=sys.stderr)
        return 1
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == '__main__':
    raise SystemExit(main())
