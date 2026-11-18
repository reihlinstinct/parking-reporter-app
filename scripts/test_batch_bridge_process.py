"""Actual subprocess boundary tests, synthetic payloads only, no network."""
import os
from pathlib import Path
import sys
import tempfile
import time
import subprocess
from unittest.mock import Mock
import unittest

from live_batch_hybrid import BridgeAdapter
from live_batch_input import InvalidBatch


class ProcessTest(unittest.TestCase):
    def bridge(self, program, timeout=.3):
        err = tempfile.TemporaryFile(); self.addCleanup(err.close)
        bridge = BridgeAdapter([sys.executable, '-c', program], dict(os.environ), err, timeout)
        self.addCleanup(bridge.close)
        return bridge

    def test_stop_timeout_keeps_session_poisoned(self):
        bridge = object.__new__(BridgeAdapter)
        bridge.poisoned = False
        bridge.process = Mock()
        bridge.process.poll.return_value = None
        bridge.process.wait.side_effect = subprocess.TimeoutExpired('synthetic', 5)
        bridge._stop()
        self.assertTrue(bridge.poisoned)
        bridge.process.kill.assert_called_once_with()
        bridge.process.wait.assert_called_once_with(timeout=5)
        with self.assertRaises(InvalidBatch): bridge.request('create')

    def test_stop_process_exit_race_keeps_session_poisoned(self):
        bridge = object.__new__(BridgeAdapter)
        bridge.poisoned = False
        bridge.process = Mock()
        bridge.process.poll.return_value = None
        bridge.process.kill.side_effect = ProcessLookupError()
        bridge._stop()
        self.assertTrue(bridge.poisoned)
        with self.assertRaises(InvalidBatch): bridge.request('create')

    def test_successful_private_stdio(self):
        b = self.bridge('import sys; sys.stdin.readline(); print(\'{"success":true,"case":{"WebCaseIdInCRM":"synthetic"}}\',flush=True)')
        self.assertEqual(b.request('create')['case']['WebCaseIdInCRM'], 'synthetic')

    def test_partial_line_is_bounded_and_session_burned(self):
        b = self.bridge('import sys,time; sys.stdin.readline();sys.stdout.write("{");sys.stdout.flush();time.sleep(60)')
        start = time.monotonic()
        with self.assertRaises(InvalidBatch): b.request('create')
        self.assertLess(time.monotonic()-start, 3)
        self.assertTrue(b.poisoned); self.assertIsNotNone(b.process.poll())
        with self.assertRaises(InvalidBatch): b.request('create')

    def test_process_dies_after_request_without_retry(self):
        b = self.bridge('import sys,os; sys.stdin.readline();os._exit(7)')
        with self.assertRaises(InvalidBatch): b.request('create')
        self.assertTrue(b.poisoned)
        with self.assertRaises(InvalidBatch): b.request('create')

    def test_malformed_negative_or_extra_responses_burn_session(self):
        for response in ['{"success":false,"success":true}', '{"success":true,"case":{"id":NaN}}', 'not-json', '{"success":false}', '[]', '{"success":true}\\n{"success":true}']:
            with self.subTest(response=response):
                b=self.bridge('import sys;sys.stdin.readline();print('+repr(response)+',flush=True)')
                with self.assertRaises(InvalidBatch): b.request('create')
                self.assertTrue(b.poisoned)

    def test_child_not_reading_stdin_is_bounded(self):
        b=self.bridge('import time;time.sleep(60)')
        start=time.monotonic()
        with self.assertRaises(InvalidBatch): b.request('prepare', synthetic='a'*300000)
        self.assertLess(time.monotonic()-start,3)
        self.assertTrue(b.poisoned)

    def test_workflow_uses_protected_narrow_secret(self):
        workflow=(Path(__file__).resolve().parents[1]/'.github/workflows/live-batch-hybrid.yml').read_text()
        self.assertIn('PRIVATE_LOOKUP_READ_TOKEN: ${{ secrets.BATCH_PRIVATE_CONTENTS_TOKEN }}',workflow)
        self.assertNotIn('secrets.PRIVATE_LOOKUP_READ_TOKEN',workflow)


if __name__ == '__main__': unittest.main()
