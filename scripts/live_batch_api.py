"""Inactive API-only sequential executor. No CLI, network setup or default adapter.

The caller supplies a validated effective manifest, an authorized API port and
an acknowledged durable journal. Production workflow wiring/activation is separate.
"""
from live_batch_input import need


def run_api_batch(journal, manifest, api):
    """All reports use API boundaries, never UI, automatic retries or fallback.

    Adapter preflight is read-only. Journal methods must acknowledge durable
    remote state before returning. Any exception immediately stops this batch.
    A write followed by failed receipt persistence remains held, never replayed.
    """
    need(api is not None, 'API boundary adapter unavailable')
    reports = manifest.get('reports')
    need(isinstance(reports, list) and 1 <= len(reports) <= 50, 'invalid API report count')
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
