"""Inactive API-only sequential executor. No CLI, network setup or default adapter.

The caller supplies a validated effective manifest, an authorized API port and
an acknowledged durable journal. Production workflow wiring/activation is separate.
"""
import re

from live_batch_input import need


def run_api_batch(journal, manifest, api):
    """All reports use API boundaries, never UI, automatic retries or fallback.

    Adapter preflight is read-only. Journal methods must acknowledge durable
    remote state before returning. Any exception immediately stops this batch.
    A write followed by failed receipt persistence remains held, never replayed.
    """
    need(api is not None, 'API boundary adapter unavailable')
    need(isinstance(manifest, dict), 'invalid API manifest')
    reports = manifest.get('reports')
    need(isinstance(reports, list) and 1 <= len(reports) <= 50, 'invalid API report count')
    # Fail before even read-only adapter work when the small port contract is malformed.
    # Full approved text/original/outgoing/path validation still belongs to intake.
    ids = set()
    for report in reports:
        need(isinstance(report, dict), 'invalid API report')
        report_id = report.get('reportId')
        need(isinstance(report_id, str) and bool(report_id.strip()) and report_id not in ids,
             'invalid or duplicate API report identity')
        ids.add(report_id)
        photos = report.get('photos')
        need(isinstance(photos, list), 'invalid API photos')
        hashes = set()
        for photo in photos:
            need(isinstance(photo, dict) and isinstance(photo.get('sha256'), str) and
                 re.fullmatch('[0-9a-f]{64}', photo['sha256']) is not None and
                 photo['sha256'] not in hashes, 'invalid or duplicate API photo identity')
            hashes.add(photo['sha256'])
    api.preflight(reports)
    journal.reserve(manifest)
    for report in reports:
        report_id = report['reportId']
        journal.begin_create(report_id)
        case = api.create(report)
        journal.record_case(report_id, case)
        for photo in report['photos']:
            journal.begin_attachment(report_id, photo['sha256'])
            need(api.attach(case, report, photo) is True, 'attachment result not confirmed')
            journal.record_attachment(report_id, photo['sha256'])
