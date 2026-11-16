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
PASS = re.compile(r'^\s*(?:\*\*)?Verdict:\s*(?:\*\*)?\s*PASS\b', re.MULTILINE)
BLOCK = re.compile(r'^\s*(?:\*\*)?Verdict:\s*(?:\*\*)?\s*(?:FAIL|BLOCK|CHANGES\s+REQUESTED)\b', re.MULTILINE)


def evaluate(head, reviews, author):
    """Only the latest review per trusted account at this exact head can count."""
    latest = {}
    for r in sorted(reviews, key=lambda r: r.get('id', 0)):
        user = r.get('user') or {}
        if user.get('login') not in REVIEWERS or user.get('type') == 'Bot' or user.get('login') == author:
            continue
        if r.get('commit_id') != head:
            continue
        latest[user['login']] = r
    passed = []
    for login, r in latest.items():
        body = re.sub(r'(?ms)^\s*(`{3,}|~{3,}).*?^\s*\1[^\n]*$', '', r.get('body') or '')
        if r.get('state') == 'CHANGES_REQUESTED' or (r.get('state') != 'DISMISSED' and BLOCK.search(body)):
            return False, 'Blocking independent review at this head'
        if r.get('state') not in ('COMMENTED', 'APPROVED'):
            continue
        if re.search(r'not independent|Instinct task agent', body, re.I):
            continue
        if PASS.search(body):
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
        if result.get('sha') != head or result.get('context') != CONTEXT:
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
    event = json.load(open(os.environ['GITHUB_EVENT_PATH']))
    client = Client(os.environ['GITHUB_REPOSITORY'], os.environ['GH_TOKEN'])
    if 'pull_request' in event:
        # Sweep every current PR so concurrency coalescing cannot lose another PR's status.
        numbers = [p['number'] for p in client.pages('/pulls?state=open&base=main')]
    else:
        # workflow_run comes from the unprivileged review notification workflow.
        # Its content is not authority: resolve every current PR and review live.
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
