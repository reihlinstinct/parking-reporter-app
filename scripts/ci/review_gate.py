"""Trusted-base review status writer. Never import, fetch or execute PR source code.

A GitHub Actions status is NOT a distinct trust identity from other workflows.
Do not enable auto-merge on the strength of this check alone. See #518.
"""
import json
import os
import re
import urllib.request

CONTEXT = 'Independent review gate'
REVIEWERS = {'reihl', 'reihl-poke', 'reihl-folk'}
PASS = re.compile(r'^Verdict:\s*PASS\b', re.I)
VERDICT = re.compile(r'\bVerdict\b', re.I)
BLOCK = re.compile(r'\b(?:FAIL|BLOCK|CHANGES\s+REQUESTED)\b', re.I)


def verdict(body):
    """Only prose verdicts count; ambiguous verdicts block rather than fail open."""
    body = re.sub(r'(?s)<!--.*?(?:-->|$)', '', body or '')
    fence = None
    found_pass = False
    for raw in body.splitlines():
        marker = re.match(r'^ {0,3}(`{3,}|~{3,})', raw)
        if marker:
            token = marker.group(1)
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence) and raw.strip() == token:
                fence = None
            continue
        if fence:
            continue
        # Blocks scan indented/quoted text too: missing a block must never fail open.
        line = re.sub(r'^(?:[-+*] |\d+[.)] )', '', raw.strip())
        line = re.sub(r'[*_`]', '', line)
        if not VERDICT.search(line):
            continue
        if BLOCK.search(line):
            return 'block'
        if raw.startswith(('    ', '\t')) or raw.lstrip().startswith('>'):
            continue
        if not PASS.match(line):
            return 'block'
        found_pass = True
    return 'pass' if found_pass else None


def evaluate(head, reviews, author):
    """Keep each trusted account's latest decision, not its latest incidental note."""
    latest = {}
    for r in sorted(reviews, key=lambda r: r.get('id', 0)):
        user = r.get('user') or {}
        if user.get('login') not in REVIEWERS or user.get('type') == 'Bot' or user.get('login') == author:
            continue
        if r.get('commit_id') != head:
            continue
        decision = verdict(r.get('body'))
        state = r.get('state')
        if state not in ('CHANGES_REQUESTED', 'APPROVED', 'DISMISSED') and decision is None:
            continue
        latest[user['login']] = (r, decision)
    passed = []
    for login, (r, decision) in latest.items():
        state = r.get('state')
        if state == 'DISMISSED':
            continue
        if state == 'CHANGES_REQUESTED' or decision == 'block':
            return False, 'Blocking independent review at this head'
        if state not in ('COMMENTED', 'APPROVED'):
            continue
        if re.search(r'not independent|Instinct task agent', r.get('body') or '', re.I):
            continue
        if decision == 'pass':
            passed.append(login)
    return (True, 'Independent exact-head PASS verified') if passed else (False, 'No valid independent PASS at this head')


class Client:
    def __init__(self, repo, token):
        self.base = 'https://api.github.com/repos/' + repo
        self.token = token

    def api(self, path, data=None):
        req = urllib.request.Request(self.base + path,
            data=None if data is None else json.dumps(data).encode(),
            headers={'Authorization': 'Bearer ' + self.token,
                     'Accept': 'application/vnd.github+json', 'Content-Type': 'application/json'})
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.load(r)

    def pages(self, path):
        result = []
        for page in range(1, 101):
            rows = self.api(path + ('&' if '?' in path else '?') + f'per_page=100&page={page}')
            result.extend(rows)
            if len(rows) < 100:
                return result
        raise RuntimeError('Review pagination limit reached; refusing success')

    def status(self, head, state, description):
        result = self.api('/statuses/' + head, {'state': state, 'context': CONTEXT,
            'description': description, 'target_url': os.environ.get('RUN_URL', '')})
        # GitHub's commit-status response identifies the SHA in url, not a sha field.
        if (result.get('url') != self.base + '/statuses/' + head
                or result.get('context') != CONTEXT or result.get('state') != state):
            raise RuntimeError('Status response did not identify the exact requested head')


def update(client, number):
    path = '/pulls/' + str(number)
    pr = client.api(path)
    if pr.get('state') != 'open' or pr.get('base', {}).get('ref') != 'main':
        return
    head = pr['head']['sha']
    client.status(head, 'pending', 'Checking the current independent review state')
    try:
        reviews = client.pages(path + '/reviews')
        ok, reason = evaluate(head, reviews, pr['user']['login'])
        # A stale event or push during evaluation may never green the current new head.
        if client.api(path)['head']['sha'] != head:
            client.status(head, 'failure', 'Head changed during evaluation; awaiting a fresh run')
            return
        # Re-read review state to catch a dismissal/edit arriving during evaluation.
        fresh = client.pages(path + '/reviews')
        if fresh != reviews:
            ok, reason = evaluate(head, fresh, pr['user']['login'])
        if client.api(path)['head']['sha'] != head:
            client.status(head, 'failure', 'Head changed during evaluation; awaiting a fresh run')
            return
        client.status(head, 'success' if ok else 'failure', reason)
    except Exception:
        client.status(head, 'failure', 'Review verification failed; no merge authorization')
        raise


def main():
    client = Client(os.environ['GITHUB_REPOSITORY'], os.environ['GH_TOKEN'])
    # Ignore event payloads; sweep current PRs after coalesced events.
    numbers = [p['number'] for p in client.pages('/pulls?state=open&base=main')]
    failed = False
    for number in numbers:
        try:
            update(client, number)
        except Exception:
            failed = True  # continue so one bad PR never leaves later PRs unchecked
    if failed:
        raise RuntimeError('One or more review checks failed')


if __name__ == '__main__':
    main()
