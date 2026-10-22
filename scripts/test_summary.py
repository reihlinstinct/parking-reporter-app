#!/usr/bin/env python3
"""Render GitHub job summaries from test outputs: JUnit XML, Android
instrumentation raw output (-r), unittest -v text, JaCoCo XML.

Counts first, then failed and skipped with reasons, then a nested
collapsible per-test list. --check exits 1 on failures or zero tests.
"""
import argparse, glob, html, os, re, sys
import xml.etree.ElementTree as ET

def esc(text):
    return html.escape(text or '', quote=False)

def trim(text, limit=2000):
    text = (text or '').strip()
    return text if len(text) <= limit else text[:limit] + '\n... (truncated)'

class Case:
    def __init__(self, suite, name, seconds, status, detail='', reason=''):
        self.suite, self.name, self.seconds = suite, name, seconds
        self.status, self.detail, self.reason = status, detail, reason

def load_junit(pattern):
    if os.path.isdir(pattern):
        pattern = os.path.join(pattern, '**', '*.xml')
    cases = []
    for path in sorted(glob.glob(pattern, recursive=True)):
        if not path.endswith('.xml'):
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == 'testsuite' else root.findall('.//testsuite')
        for suite in suites:
            for tc in suite.iter('testcase'):
                name = tc.get('name', '?')
                cls = tc.get('classname') or suite.get('name') or '?'
                seconds = float(tc.get('time', '0') or 0)
                failure = tc.find('failure')
                if failure is None:
                    failure = tc.find('error')
                skipped = tc.find('skipped')
                out = tc.findtext('system-out') or ''
                if failure is not None:
                    message = failure.get('message') or ''
                    body = failure.text or ''
                    detail = (message + '\n' + body).strip() or out
                    cases.append(Case(cls, name, seconds, 'failed', detail=detail))
                elif skipped is not None:
                    cases.append(Case(cls, name, seconds, 'skipped', reason=skipped.get('message') or out or 'no reason recorded'))
                else:
                    cases.append(Case(cls, name, seconds, 'passed', detail=out))
    return cases

def load_instrumentation(path):
    """Parse `am instrument -w -r` raw status output."""
    cases, current = [], None
    for line in open(path, encoding='utf-8', errors='replace'):
        line = line.rstrip('\n')
        if line.startswith('INSTRUMENTATION_STATUS: class='):
            cls = line.split('=', 1)[1]
            if current is None:
                current = {'class': cls}
            else:
                current['class'] = cls
        elif line.startswith('INSTRUMENTATION_STATUS: test=') and current is not None:
            current['test'] = line.split('=', 1)[1]
        elif line.startswith('INSTRUMENTATION_STATUS: stack=') and current is not None:
            current.setdefault('stack', line.split('=', 1)[1])
        elif line.startswith('INSTRUMENTATION_STATUS: stream=') and current is not None:
            current.setdefault('stream', line.split('=', 1)[1])
        elif line.startswith('INSTRUMENTATION_STATUS_CODE:') and current is not None:
            code = int(line.split(':', 1)[1].strip())
            if code == 1:
                current = {'class': current.get('class', '?'), 'test': current.get('test', '?')}
            elif current.get('test'):
                if code == 0:
                    cases.append(Case(current['class'], current['test'], 0, 'passed', detail=current.get('stream', '')))
                elif code == -3:
                    cases.append(Case(current['class'], current['test'], 0, 'skipped', reason=current.get('stream') or 'ignored'))
                else:
                    cases.append(Case(current['class'], current['test'], 0, 'failed', detail=current.get('stack') or current.get('stream') or 'no detail recorded'))
                current = None
    return cases

UNITTEST_LINE = re.compile(r'^(\S+)\s+\(([^)]+)\)\s+\.\.\.\s+(ok|FAIL|ERROR|skipped\s+.*|expected failure|unexpected success)\s*$')

def load_unittest(path):
    cases = []
    for line in open(path, encoding='utf-8', errors='replace'):
        m = UNITTEST_LINE.match(line.rstrip())
        if m:
            name, cls, outcome = m.group(1), m.group(2), m.group(3)
            if outcome == 'ok':
                cases.append(Case(cls, name, 0, 'passed'))
            elif outcome.startswith('skipped'):
                cases.append(Case(cls, name, 0, 'skipped', reason=outcome))
            else:
                cases.append(Case(cls, name, 0, 'failed'))
    text = open(path, encoding='utf-8', errors='replace').read()
    for block in re.split(r'\n={20,}\n', text):
        m = re.match(r'(FAIL|ERROR): (\S+) \(([^)]+)\)\n-+\n(.*)', block, re.S)
        if m:
            for case in cases:
                if case.name == m.group(2) and case.suite == m.group(3):
                    case.detail = trim(m.group(4), 2000)
    return cases

def load_jacoco(pattern):
    if os.path.isdir(pattern):
        pattern = os.path.join(pattern, '**', '*.xml')
    for path in sorted(glob.glob(pattern, recursive=True)):
        if not path.endswith('.xml'):
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        found = {}
        for counter in root.iter('counter'):
            kind = counter.get('type')
            if kind in ('INSTRUCTION', 'LINE', 'BRANCH'):
                covered = int(counter.get('covered', 0)); missed = int(counter.get('missed', 0))
                total = covered + missed
                found[kind] = (100.0 * covered / total) if total else 0.0
        if found:
            return found
    return None

ICON = {'passed': '✅', 'failed': '❌', 'skipped': '⏭️'}

def case_block(case):
    head = f"{ICON[case.status]} {esc(case.name)} ({case.seconds:.2f}s)"
    body_parts = []
    if case.status == 'failed':
        body_parts.append('**Failure detail (assertion message, expected vs actual, stack):**')
        body_parts.append('```\n' + trim(case.detail or 'no detail recorded') + '\n```')
    elif case.status == 'skipped':
        reason = trim(case.reason or 'no reason recorded', 500)
        body_parts.append('**Why it was skipped (as recorded in the test source):** ' + esc(reason))
        elsewhere = re.search(r'(?:runs?|runs instead|covered|separate) (?:in|by|elsewhere)[:\s]+(.+)', reason, re.I)
        if elsewhere:
            body_parts.append('**Where it runs instead:** ' + esc(elsewhere.group(1).strip()))
        else:
            body_parts.append('**Where it runs instead:** no alternative run location is recorded in the test source, so it currently runs nowhere automatically.')
    elif case.detail.strip():
        body_parts.append('**Captured output:**')
        body_parts.append('```\n' + trim(case.detail, 1000) + '\n```')
    else:
        body_parts.append('_Passed; the runner records no per-assertion detail for passing tests._')
    return '<details><summary>' + head + '</summary>\n\n' + '\n\n'.join(body_parts) + '\n\n</details>'

def render(title, cases, coverage):
    total = len(cases)
    failed = [c for c in cases if c.status == 'failed']
    skipped = [c for c in cases if c.status == 'skipped']
    passed = total - len(failed) - len(skipped)
    seconds = sum(c.seconds for c in cases)
    lines = [f'## {title}', '',
             '| Total | Passed | Failed | Skipped | Time |', '| --- | --- | --- | --- | --- |',
             f'| {total} | {passed} | {len(failed)} | {len(skipped)} | {seconds:.1f}s |', '']
    if coverage:
        cov = ' · '.join(f'{k.lower()} {v:.1f}%' for k, v in coverage.items())
        lines += [f'**Coverage (JaCoCo):** {cov}', '']
    if failed:
        lines += [f'### Failed ({len(failed)})', '']
        lines += [case_block(c) for c in failed]
    if skipped:
        lines += [f'### Skipped ({len(skipped)})', '']
        lines += [case_block(c) for c in skipped]
    suites = {}
    for c in cases:
        suites.setdefault(c.suite, []).append(c)
    lines += [f'### All tests ({total})', '']
    for suite in sorted(suites):
        members = suites[suite]
        suite_seconds = sum(c.seconds for c in members)
        lines.append(f'<details><summary>{esc(suite)} - {len(members)} tests, {suite_seconds:.1f}s</summary>')
        lines.append('')
        lines += [case_block(c) for c in members]
        lines.append('')
        lines.append('</details>')
    return '\n'.join(lines) + '\n'

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--title', required=True)
    parser.add_argument('--junit', action='append', default=[])
    parser.add_argument('--instrumentation', action='append', default=[])
    parser.add_argument('--unittest', action='append', default=[])
    parser.add_argument('--jacoco', action='append', default=[])
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    cases = []
    for pattern in args.junit:
        cases += load_junit(pattern)
    for path in args.instrumentation:
        if os.path.isfile(path):
            cases += load_instrumentation(path)
    for path in args.unittest:
        if os.path.isfile(path):
            cases += load_unittest(path)
    coverage = None
    for pattern in args.jacoco:
        coverage = load_jacoco(pattern) or coverage
    summary = render(args.title, cases, coverage)
    target = os.environ.get('GITHUB_STEP_SUMMARY')
    if target:
        with open(target, 'a', encoding='utf-8') as handle:
            handle.write(summary)
    else:
        sys.stdout.write(summary)
    if args.check and (not cases or any(c.status == 'failed' for c in cases)):
        print(f'test_summary: gate failed ({len(cases)} tests, {sum(1 for c in cases if c.status=="failed")} failed)', file=sys.stderr)
        return 1
    return 0

if __name__ == '__main__':
    sys.exit(main())
