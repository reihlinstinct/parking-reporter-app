import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('review_gate', Path(__file__).resolve().parents[1] / 'scripts/ci/review_gate.py')
gate = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gate)
HEAD = 'a' * 40


def evaluate(head, reviews, author='reihlinstinct'):
    return gate.evaluate(head, reviews, author)


def review(**changes):
    r = dict(id=1, user={'login': 'reihl', 'type': 'User'}, commit_id=HEAD,
             state='COMMENTED', body='**Verdict: PASS** (minors only).')
    r.update(changes)
    return r


class ReviewGateTest(unittest.TestCase):
    def test_exact_head_and_latest_dismissal(self):
        self.assertTrue(evaluate(HEAD, [review()])[0])
        self.assertFalse(evaluate('b' * 40, [review()])[0])
        self.assertFalse(evaluate(HEAD, [review(state='DISMISSED')])[0])
        self.assertFalse(evaluate(HEAD, [review(), review(id=2, state='DISMISSED')])[0])
        self.assertFalse(evaluate(HEAD, [review(body='Review edited, no verdict')])[0])

    def test_untrusted_review_and_quoted_verdict_rejected(self):
        for user in ['reihlinstinct', 'github-actions[bot]', 'attacker']:
            self.assertFalse(evaluate(HEAD, [review(user={'login': user})])[0])
        for body in ['Instinct task agent (not independent)\nVerdict: PASS', '> Verdict: PASS', 'No Verdict: PASS', 'Verdict: PASSING']:
            self.assertFalse(evaluate(HEAD, [review(body=body)])[0])

    def test_other_trusted_reviewer_blocks(self):
        self.assertFalse(evaluate(HEAD, [review(), review(id=2, user={'login': 'reihl-poke'}, state='CHANGES_REQUESTED')])[0])
        self.assertFalse(evaluate(HEAD, [review(), review(id=2, body='**Verdict: FAIL**')])[0])

    def test_commented_changes_requested_blocks_other_pass(self):
        block = review(body='**Verdict: CHANGES REQUESTED**')
        passed = review(id=2, user={'login': 'reihl-poke'}, body='**Verdict: PASS**')
        self.assertFalse(evaluate(HEAD, [block, passed])[0])

    def test_author_cannot_self_review_in_comment(self):
        self.assertFalse(evaluate(HEAD, [review()], author='reihl')[0])
        self.assertTrue(evaluate(HEAD, [review()], author='reihl-poke')[0])

    def test_fenced_verdict_is_not_review(self):
        self.assertFalse(evaluate(HEAD, [review(body='Example:\n```\nVerdict: PASS\n```')])[0])
        self.assertFalse(evaluate(HEAD, [review(body='Example:\n~~~\nVerdict: PASS\n~~~')])[0])

    def test_verdictless_note_cannot_mask_own_block(self):
        for state in ['COMMENTED', 'CHANGES_REQUESTED']:
            rows = [review(state=state, body='Verdict: CHANGES REQUESTED'),
                    review(id=2, user={'login': 'reihl-poke'}),
                    review(id=3, body='A follow-up note with no decision')]
            self.assertFalse(evaluate(HEAD, rows)[0])
            rows.append(review(id=4))
            self.assertTrue(evaluate(HEAD, rows)[0])

    def test_block_markdown_variants_fail_closed(self):
        for body in ['- **Verdict:** CHANGES REQUESTED', '**Verdict**: CHANGES REQUESTED',
                     'Verdict - CHANGES REQUESTED', 'Verdict: UNKNOWN', 'Verdict: FAIL']:
            self.assertFalse(evaluate(HEAD, [review(body=body),
                review(id=2, user={'login': 'reihl-poke'})])[0])
        self.assertTrue(evaluate(HEAD, [review(body='**Verdict:** PASS')])[0])

    def test_non_prose_verdicts_never_pass(self):
        for body in ['```\nVerdict: PASS', '    Verdict: PASS', '<!--\nVerdict: PASS\n-->',
                     '~~~python\nVerdict: PASS\n~~~~']:
            self.assertFalse(evaluate(HEAD, [review(body=body)])[0])

    def test_writer_always_uses_current_head_not_event_sha(self):
        client = Fake()
        gate.update(client, 123)
        self.assertEqual(client.statuses, [(HEAD, 'pending'), (HEAD, 'success')])

    def test_push_during_evaluation_cannot_green_old_or_new_head(self):
        client = Fake(move=True)
        gate.update(client, 123)
        self.assertNotIn((HEAD, 'success'), client.statuses)
        self.assertTrue(all(h == HEAD for h, _ in client.statuses))

    def test_dismissal_during_read_revokes_success(self):
        client = Fake(dismiss=True)
        gate.update(client, 123)
        self.assertEqual(client.statuses[-1], (HEAD, 'failure'))

    def test_workflow_has_no_pr_checkout_or_pr_script_import(self):
        root = Path(__file__).resolve().parents[1]
        workflow = (root / '.github/workflows/review-gate.yml').read_text()
        self.assertNotIn('pull_request.head', workflow)
        self.assertNotIn('actions/checkout', workflow)
        embedded = workflow.split("python3 - <<'PYCODE'\n", 1)[1].rsplit('          PYCODE', 1)[0]
        source = '\n'.join(line[10:] for line in embedded.splitlines()) + '\n'
        self.assertEqual(source, (root / 'scripts/ci/review_gate.py').read_text())
        self.assertNotIn('pull_request:', workflow)
        notifier = (root / '.github/workflows/review-notification.yml').read_text()
        self.assertIn('permissions: {}', notifier)
        self.assertNotIn('uses:', notifier)


class Fake:
    def __init__(self, move=False, dismiss=False):
        self.move, self.dismiss = move, dismiss
        self.reads = 0
        self.review_reads = 0
        self.statuses = []

    def api(self, path):
        self.reads += 1
        return {'state': 'open', 'user': {'login': 'reihlinstinct'}, 'base': {'ref': 'main'}, 'head': {'sha': 'b' * 40 if self.move and self.reads > 1 else HEAD}}

    def pages(self, path):
        self.review_reads += 1
        return [review(state='DISMISSED' if self.dismiss and self.review_reads > 1 else 'COMMENTED')]

    def status(self, head, state, description):
        self.statuses.append((head, state))
