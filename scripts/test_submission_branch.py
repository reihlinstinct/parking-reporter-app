"""Synthetic source-boundary tests. No accounts, secrets, or municipal calls."""
import copy
from datetime import datetime, timezone, timedelta
import unittest
from unittest.mock import patch
import submission_branch as s


class SourceTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime(2026, 11, 18, 15, tzinfo=timezone.utc)
        self.env = dict(GITHUB_REPOSITORY=s.REPOSITORY, GITHUB_REPOSITORY_ID=s.REPOSITORY_ID,
            GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_RUN_ATTEMPT='1', GITHUB_ACTOR_ID=s.OWNER_ID,
            GITHUB_TRIGGERING_ACTOR='reihlinstinct', GITHUB_SHA='a'*40,
            GITHUB_REF='refs/heads/submission/'+'b'*24, SUBMISSION_CONFIRM='FILE_ONE_APPROVED_CASE',
            TRIAL_OTHER_WRITERS_FENCED='CONFIRMED', APPROVED_ORIGINAL_SHA256='b'*64,
            SUBMISSION_EXPIRES_AT=(self.now+timedelta(hours=1)).isoformat(), BATCH_PRIVATE_REF='c'*40,
            BATCH_PRIVATE_PATH='batches/synthetic-input/input.zip.enc', BATCH_APPROVED_OUTER_SHA256='d'*64,
            APPROVED_TRIAL_JSON_SHA256='e'*64, APPROVED_OUTGOING_SHA256='f'*64)
        self.checks = [dict(name=n,status='completed',conclusion='success') for n in s.REQUIRED_CHECKS]

    def guard(self, env=None, branch=None, main=None, checks=None, total=None):
        rows = self.checks if checks is None else checks
        def read(path, env):
            if path == '/git/ref/heads/main': return {'object':{'sha':main or 'a'*40}}
            if path.startswith('/git/ref/heads/submission/'): return {'object':{'sha':branch or 'a'*40}}
            return dict(total_count=len(rows) if total is None else total, check_runs=rows)
        with patch.object(s,'pin_inputs',wraps=lambda e:s._real_pin(e,self.now)):
            return s.source_guard(env or self.env,read)

    def test_exact_main_owner_new_dispatch_and_green_checks_pass(self):
        self.assertEqual(self.guard()['originalSha256'],'b'*64)

    def test_wrong_actor_fork_pr_rerun_main_or_unbound_branch_refused(self):
        for change in [dict(GITHUB_ACTOR_ID='other'),dict(GITHUB_TRIGGERING_ACTOR='other'),
                       dict(GITHUB_REPOSITORY='other/fork'),dict(GITHUB_REPOSITORY_ID='other'),
                       dict(GITHUB_EVENT_NAME='pull_request_target'),dict(GITHUB_RUN_ATTEMPT='2'),
                       dict(GITHUB_REF='refs/heads/main'),dict(GITHUB_REF='refs/heads/submission/other'),
                       dict(SUBMISSION_CONFIRM='NO'),dict(TRIAL_OTHER_WRITERS_FENCED='NO')]:
            with self.assertRaises(ValueError):self.guard(dict(self.env,**change))

    def test_stale_diverged_or_moved_main_refused(self):
        for kw in [dict(main='d'*40),dict(branch='d'*40)]:
            with self.assertRaises(ValueError):self.guard(**kw)

    def test_red_pending_missing_or_truncated_main_evidence_refused(self):
        for kw in [dict(checks=self.checks[:-1]),dict(total=101),
                   dict(checks=self.checks+[dict(name='extra',status='in_progress',conclusion=None)]),
                   dict(checks=self.checks+[dict(name='extra',status='completed',conclusion='failure')])]:
            with self.assertRaises(ValueError):self.guard(**kw)

    def test_own_in_progress_checks_do_not_deadlock_source(self):
        rows=self.checks+[dict(name=n,status='in_progress',conclusion=None) for n in ['submission-source','submit-one']]
        self.guard(checks=rows)

    def test_bad_expiry_digest_private_ref_path_and_fence_refused(self):
        for change in [dict(SUBMISSION_EXPIRES_AT=self.now.isoformat()),
                       dict(SUBMISSION_EXPIRES_AT=(self.now+timedelta(hours=7)).isoformat()),
                       dict(APPROVED_TRIAL_JSON_SHA256='bad'),dict(BATCH_PRIVATE_REF='main'),
                       dict(BATCH_PRIVATE_PATH='../../private')]:
            with self.assertRaises(ValueError):self.guard(dict(self.env,**change))


s._real_pin=s.pin_inputs
if __name__=='__main__':unittest.main()
