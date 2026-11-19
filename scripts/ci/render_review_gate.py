#!/usr/bin/env python3
"""Generate trusted workflow code from the standalone review gate, never at runtime.

The checked-in workflow remains checkout-free. This developer-side renderer is not
executed in the privileged pull_request_target job and never reads PR event data.
"""
from pathlib import Path
import argparse

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/review-gate.yml'
SOURCE = ROOT / 'scripts/ci/review_gate.py'
MARKER = "          python3 - <<'PYCODE'\n"


def render(workflow, source):
    prefix, embedded = workflow.split(MARKER, 1)
    _, suffix = embedded.split('          PYCODE', 1)
    if any(line == 'PYCODE' for line in source.splitlines()):
        raise ValueError('Unsafe workflow heredoc delimiter in review gate source')
    return prefix + MARKER + ''.join(('          ' + line if line else '') + '\n' for line in source.splitlines()) + '          PYCODE' + suffix


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--check', action='store_true')
    args = p.parse_args()
    current = WORKFLOW.read_text()
    expected = render(current, SOURCE.read_text())
    if args.check:
        if current != expected: raise SystemExit('Review workflow differs from its canonical source; run scripts/ci/render_review_gate.py')
    else:
        WORKFLOW.write_text(expected)

if __name__ == '__main__': main()
