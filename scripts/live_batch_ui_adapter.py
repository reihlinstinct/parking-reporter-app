"""Inactive UI boundary adapter for the hybrid batch controller (#548, #558).

It exposes the same create/attach boundaries as the direct adapter, so
run_hybrid calls create() only AFTER the shared remote creating intent was
acknowledged and attach() only AFTER the remote attaching intent was
acknowledged. The recorded UI flow itself still runs in the app, but the app
only ever sees a gated municipal client (BatchWriteGate.kt): each write waits
for a grant that this adapter issues from inside create()/attach(). The
existing all-in-one coordinator therefore cannot write outside these
boundaries, and no step is retried or falls back to another lane.

The device is an injected port (real adb transport is not part of this
slice). Staging comes from the per-report manifest, never from shared TRIAL
secrets. Nothing here makes a live call.
"""
import hashlib
import json

from live_batch_input import InvalidBatch, need

MAX_UI_REPORTS = 2


def selection_digest(batch_digest, ui_report_ids):
    """Digest binding the approved 1-2 UI reports to one immutable batch."""
    need(isinstance(batch_digest, str) and len(batch_digest) == 64, 'invalid batch digest')
    ids = sorted(ui_report_ids)
    need(1 <= len(ids) <= MAX_UI_REPORTS and len(set(ids)) == len(ids), 'one or two UI reports required')
    blob = json.dumps({'batchDigest': batch_digest, 'uiReportIds': ids}, sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(blob.encode()).hexdigest()


class UiBoundaryAdapter:
    """device port: ready()->None (read-only checks), stage(report)->None,
    wait_gate(timeout)->dict(op, reportId, sequence, outgoingSha256?),
    grant(op, reportId, sequence)->None, wait_result(timeout)->dict(success, case?),
    abort()->None. Every device error or mismatch aborts and poisons the adapter."""

    def __init__(self, device, batch_digest, ui_report_ids, approved_selection_digest,
                 photo_preflight, timeout=300):
        need(selection_digest(batch_digest, ui_report_ids) == approved_selection_digest,
             'UI selection is not the approved binding')
        need(callable(photo_preflight), 'photo preflight (hash, format, bounded decode) is required')
        self.device, self.ui_ids = device, set(ui_report_ids)
        self.photo_preflight, self.timeout = photo_preflight, timeout
        self.poisoned, self.current, self.case, self.next_attach = False, None, None, 0

    def _fail(self, message):
        self.poisoned = True
        try:
            self.device.abort()
        finally:
            raise InvalidBatch(message)

    def _guard(self):
        need(not self.poisoned, 'UI adapter closed after an earlier failure')

    def _expect_gate(self, op, report_id, sequence):
        try:
            gate = self.device.wait_gate(self.timeout)
        except BaseException:
            self._fail('UI gate not reached; reconcile before continuing')
        if not (isinstance(gate, dict) and gate.get('op') == op and gate.get('reportId') == report_id
                and gate.get('sequence') == sequence):
            self._fail('UI requested a write outside the current boundary; none was granted')
        return gate

    def _grant_and_wait(self, op, report_id, sequence):
        try:
            self.device.grant(op, report_id, sequence)
            result = self.device.wait_result(self.timeout)
        except BaseException:
            self._fail('UI write result unknown; reconcile before continuing')
        if not (isinstance(result, dict) and result.get('success') is True):
            self._fail('UI write not confirmed; reconcile before continuing')
        return result

    def preflight(self, reports):
        """Validation plus read-only device checks, before any reservation or write."""
        self._guard()
        need(1 <= len(reports) <= MAX_UI_REPORTS, 'one or two UI reports required')
        need({r['reportId'] for r in reports} == self.ui_ids, 'UI reports differ from the approved selection')
        for report in reports:
            for photo in report['photos']:
                self.photo_preflight(report, photo)
        try:
            self.device.ready()
        except BaseException:
            self._fail('UI device not ready; nothing was written')

    def create(self, report):
        self._guard()
        need(report['reportId'] in self.ui_ids, 'report is not in the approved UI selection')
        need(self.current is None, 'previous UI report is not finished')
        rid = report['reportId']
        try:
            self.device.stage(report)
        except BaseException:
            self._fail('UI staging failed; no write was granted')
        self.current, self.next_attach = report, 0
        self._expect_gate('create', rid, 0)
        result = self._grant_and_wait('create', rid, 0)
        case = result.get('case')
        if not (isinstance(case, dict) and case.get('CaseIdInCRM') and case.get('WebCaseIdInCRM')):
            self._fail('UI create returned no case reference; reconcile before continuing')
        self.case = case
        if not report['photos']:
            self.current = None
        return case

    def attach(self, case, report, photo):
        self._guard()
        rid = report['reportId']
        need(self.current is not None and self.current['reportId'] == rid, 'no UI report in progress')
        need(case == self.case, 'attach case differs from the created case')
        need(self.next_attach < len(report['photos']) and report['photos'][self.next_attach] == photo,
             'photos must be attached once, in manifest order')
        gate = self._expect_gate('attach', rid, self.next_attach)
        if self.next_attach > 0:
            # Photos after the first are sent by the harness with the exact original bytes.
            need_sha = photo['sha256']
            if gate.get('outgoingSha256') != need_sha:
                self._fail('UI attach bytes differ from the manifest photo; none was granted')
        self._grant_and_wait('attach', rid, self.next_attach)
        self.next_attach += 1
        if self.next_attach == len(report['photos']):
            self.current = None
        return True

    def close(self):
        if not self.poisoned and self.current is not None:
            self.poisoned = True
            self.device.abort()
