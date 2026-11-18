"""Inactive hybrid controller, adapters injected. No built-in live dispatch.

The UI adapter must expose create/attach at the same boundaries as the direct
adapter. Calling an old all-in-one workflow is not compatible with this port.
"""
import json
import subprocess
import select
import os
import time

from live_batch_input import InvalidBatch, need, no_duplicates


class BridgeAdapter:
    """Private stdio to already-built production Kotlin code, never a public log."""
    def __init__(self, command, env, error_file, timeout=120):
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=error_file, env=env, bufsize=0)
        self.case, self.poisoned, self.timeout = None, False, timeout

    def _stop(self):
        self.poisoned = True
        try:
            if self.process.poll() is None:
                self.process.kill()
            self.process.wait(timeout=5)
        except (subprocess.TimeoutExpired, OSError):
            # Remain poisoned; never let cleanup replace the controlled error.
            pass

    def request(self, operation, **payload):
        need(not self.poisoned and self.process.poll() is None, 'private bridge unavailable')
        try:
            message = (json.dumps(dict(operation=operation, **payload), ensure_ascii=False) + '\n').encode()
            need(len(message) <= 1_048_576, 'private bridge request too large')
            deadline, line, sent = time.monotonic() + self.timeout, bytearray(), 0
            os.set_blocking(self.process.stdin.fileno(), False)
            while sent < len(message):
                remaining = deadline - time.monotonic()
                need(remaining > 0 and bool(select.select([], [self.process.stdin], [], max(0, remaining))[1]),
                     'private bridge request timed out')
                sent += os.write(self.process.stdin.fileno(), message[sent:sent + 65536])
            # select followed by readline can hang on a partial line forever.
            # Read only ready bytes under one absolute response deadline.
            while b'\n' not in line:
                remaining = deadline - time.monotonic()
                need(remaining > 0 and bool(select.select([self.process.stdout], [], [], max(0, remaining))[0]),
                     'private bridge timed out; reconcile before continuing')
                chunk = os.read(self.process.stdout.fileno(), 65536)
                need(bool(chunk), 'private bridge closed; reconcile before continuing')
                line.extend(chunk)
                need(len(line) <= 1_048_576, 'private bridge response too large')
            need(line.endswith(b'\n') and line.count(b'\n') == 1, 'private bridge response invalid')
            result = json.loads(line, object_pairs_hook=no_duplicates, parse_constant=lambda _: (_ for _ in ()).throw(ValueError('nonfinite bridge JSON')))
            need(isinstance(result, dict) and result.get('success') is True, 'municipal result unknown or refused')
            return result
        except BaseException:
            self._stop()
            raise InvalidBatch('private bridge operation failed; reconcile before continuing') from None

    def preflight(self, reports):
        # Payload/address validation for ALL reports precedes any Login or write.
        for report in reports:
            self.request('prepare', report=report)
        self.request('login')  # Read-only login before durable per-case intent.

    def create(self, report):
        self.case = self.request('create', report=report)['case']
        return self.case

    def attach(self, case, report, photo):
        self.request('attach', case=case, photo=photo)
        return True

    def close(self):
        try:
            if self.process.poll() is None:
                try:
                    self.process.stdin.close()
                    self.process.wait(timeout=5)
                except (OSError, subprocess.TimeoutExpired):
                    self._stop()
        finally:
            self.process.stdin.close()
            self.process.stdout.close()


def partition(manifest, ui_report_ids):
    reports = manifest['reports']
    ids = [r['reportId'] for r in reports]
    need(isinstance(ui_report_ids, list) and len(ui_report_ids) == len(set(ui_report_ids)), 'invalid UI selection')
    need(1 <= len(ui_report_ids) <= min(2, len(reports)) and set(ui_report_ids) <= set(ids), 'one or two UI reports required')
    return {rid: ('ui' if rid in ui_report_ids else 'direct') for rid in ids}


def run_hybrid(journal, manifest, ui_report_ids, direct, ui):
    """One whole-batch preflight; durable remote reservation/intent before writes.

    No automatic resume, release, route fallback or retry. If an adapter's
    preflight fails, nothing is reserved/sent. If any later step fails, stop.
    A server return followed by remote-save failure leaves intent held remotely.
    """
    lanes = partition(manifest, ui_report_ids)
    need(ui is not None, 'UI boundary adapter not implemented; no live writes')
    need(direct is not None, 'direct boundary adapter unavailable')
    for lane, adapter in [('ui', ui), ('direct', direct)]:
        selected = [r for r in manifest['reports'] if lanes[r['reportId']] == lane]
        if selected:
            adapter.preflight(selected)
    journal.reserve(manifest)  # Durable whole batch before first write.
    for report in manifest['reports']:
        adapter = ui if lanes[report['reportId']] == 'ui' else direct
        rid = report['reportId']
        journal.begin_create(rid)
        case = adapter.create(report)
        journal.record_case(rid, case)
        for photo in report['photos']:
            journal.begin_attachment(rid, photo['sha256'])
            need(adapter.attach(case, report, photo) is True, 'attachment result not confirmed')
            journal.record_attachment(rid, photo['sha256'])
