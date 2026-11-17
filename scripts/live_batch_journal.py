"""Inactive private batch journal. No network, UI, secrets or live adapter.

The caller must keep this DB on persistent PRIVATE storage and encrypt backups.
Intent commits happen before an adapter call. A crash never releases an identity.
"""
import contextlib
import hashlib
import json
import os
import sqlite3

from live_batch_input import InvalidBatch, need


class BatchJournal:
    def __init__(self, path):
        # Restrictive creation from the start; never silently use a symlink.
        fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
        try:
            need(os.fstat(fd).st_mode & 0o077 == 0, 'journal permissions not private')
        finally:
            os.close(fd)
        self.db = sqlite3.connect(path, isolation_level=None, timeout=5)
        self.db.execute('PRAGMA foreign_keys=ON')
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS batches(
                id TEXT PRIMARY KEY, manifest_digest TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS reports(
                id TEXT PRIMARY KEY, batch TEXT NOT NULL REFERENCES batches(id),
                position INTEGER NOT NULL, state TEXT NOT NULL, case_json TEXT,
                text_digest TEXT NOT NULL UNIQUE,
                UNIQUE(batch, position));
            CREATE TABLE IF NOT EXISTS photos(
                digest TEXT PRIMARY KEY, report TEXT NOT NULL REFERENCES reports(id),
                position INTEGER NOT NULL, state TEXT NOT NULL,
                UNIQUE(report, position));
        ''')

    def close(self):
        self.db.close()

    @contextlib.contextmanager
    def transaction(self):
        self.db.execute('BEGIN IMMEDIATE')
        try:
            yield
        except BaseException:
            self.db.execute('ROLLBACK')
            raise
        else:
            self.db.execute('COMMIT')

    @staticmethod
    def manifest_digest(manifest):
        return hashlib.sha256(json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()

    def reserve(self, manifest, manifest_digest=None):
        """Validated data only. Atomic whole-batch reserve; no implicit resume."""
        from live_batch_input import digest
        actual_digest = self.manifest_digest(manifest)
        if manifest_digest is not None:
            digest(manifest_digest)
            need(manifest_digest == actual_digest, "manifest digest mismatch")
        manifest_digest = actual_digest
        try:
            with self.transaction():
                self.db.execute('INSERT INTO batches VALUES(?,?)', (manifest['batchId'], manifest_digest))
                for i, report in enumerate(manifest['reports']):
                    digest(report['textSha256'])
                    self.db.execute('INSERT INTO reports VALUES(?,?,?,?,NULL,?)',
                                    (report['reportId'], manifest['batchId'], i, 'reserved', report['textSha256']))
                    for j, photo in enumerate(report['photos']):
                        self.db.execute('INSERT INTO photos VALUES(?,?,?,?)',
                                        (photo['sha256'], report['reportId'], j, 'reserved'))
        except sqlite3.IntegrityError:
            raise InvalidBatch('batch or photo identity already reserved') from None

    def begin_create(self, report_id):
        """Commit uncertainty BEFORE calling the adapter. Never retry creating."""
        with self.transaction():
            row = self.db.execute('SELECT batch,position,state FROM reports WHERE id=?', (report_id,)).fetchone()
            need(row is not None and row[2] == 'reserved', 'report not safely startable')
            blocked = self.db.execute("SELECT 1 FROM reports WHERE batch=? AND position<? AND state!='complete' LIMIT 1", row[:2]).fetchone()
            need(blocked is None, 'previous report not complete')
            self.db.execute("UPDATE reports SET state='creating' WHERE id=?", (report_id,))

    def record_case(self, report_id, case):
        need(isinstance(case, dict), 'invalid case receipt')
        # Numeric-only receipts are allowed only when nonempty; all-zero IDs are not proof.
        need(any(isinstance(case.get(k), str) and case[k].strip('0- ') for k in
                 ('WebCaseIdInCRM', 'CaseIdInCRM', 'CaseNumberInCRM')), 'missing case identity')
        payload = json.dumps(case, ensure_ascii=False, allow_nan=False)
        with self.transaction():
            cursor = self.db.execute("UPDATE reports SET state='case_created',case_json=? WHERE id=? AND state='creating'", (payload, report_id))
            need(cursor.rowcount == 1, 'case transition refused')

    def begin_attachment(self, report_id, photo_digest):
        with self.transaction():
            row = self.db.execute('SELECT state FROM reports WHERE id=?', (report_id,)).fetchone()
            need(row is not None and row[0] == 'case_created', 'case not safely attachable')
            photo = self.db.execute('SELECT position,state FROM photos WHERE report=? AND digest=?', (report_id, photo_digest)).fetchone()
            need(photo is not None and photo[1] == 'reserved', 'photo not safely attachable')
            blocked = self.db.execute("SELECT 1 FROM photos WHERE report=? AND position<? AND state!='complete' LIMIT 1", (report_id, photo[0])).fetchone()
            need(blocked is None, 'previous photo not complete')
            self.db.execute("UPDATE photos SET state='attaching' WHERE digest=?", (photo_digest,))
            self.db.execute("UPDATE reports SET state='attaching' WHERE id=?", (report_id,))

    def record_attachment(self, report_id, photo_digest):
        with self.transaction():
            report = self.db.execute('SELECT state FROM reports WHERE id=?', (report_id,)).fetchone()
            need(report is not None and report[0] == 'attaching', 'attachment transition refused')
            cursor = self.db.execute("UPDATE photos SET state='complete' WHERE digest=? AND report=? AND state='attaching'", (photo_digest, report_id))
            need(cursor.rowcount == 1, 'photo transition refused')
            pending = self.db.execute("SELECT 1 FROM photos WHERE report=? AND state!='complete' LIMIT 1", (report_id,)).fetchone()
            self.db.execute('UPDATE reports SET state=? WHERE id=?', ('case_created' if pending else 'complete', report_id))

    def state(self, report_id):
        row = self.db.execute('SELECT state FROM reports WHERE id=?', (report_id,)).fetchone()
        need(row is not None, 'unknown report identity')
        return row[0]


def run_mock_batch(journal, manifest, adapter):
    """Dependency-injected mock boundary only. No built-in live implementation.

    Exceptions propagate with intent state left durable. This function cannot resume
    creating/attaching/complete identities, and deliberately has no retry loop.
    """
    for report in manifest['reports']:
        report_id = report['reportId']
        journal.begin_create(report_id)
        case = adapter.create(report)
        journal.record_case(report_id, case)
        for photo in report['photos']:
            journal.begin_attachment(report_id, photo['sha256'])
            # Adapter must return literal True only for verified municipal success.
            need(adapter.attach(case, photo) is True, 'attachment not confirmed')
            journal.record_attachment(report_id, photo['sha256'])
