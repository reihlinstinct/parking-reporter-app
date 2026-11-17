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
        version = self.db.execute('PRAGMA user_version').fetchone()[0]
        tables = {r[0] for r in self.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if version not in (1, 2) and (version != 0 or tables):
            self.db.close()
            raise InvalidBatch('unsupported journal schema version')
        expected_columns = {
            'batches': ('id', 'manifest_digest'),
            'reports': ('id', 'batch', 'position', 'state', 'case_json', 'text_digest'),
            'photos': ('digest', 'report', 'position', 'state'),
        }
        if version == 2:
            expected_columns.update({
                'legacy_sources': ('archive_commit', 'source', 'source_digest', 'provenance_json'),
                'legacy_aliases': ('kind', 'digest', 'archive_commit', 'source', 'field'),
            })
        if version in (1, 2):
            for table, columns in expected_columns.items():
                actual = tuple(row[1] for row in self.db.execute('PRAGMA table_info(' + table + ')'))
                if actual != columns:
                    self.db.close()
                    raise InvalidBatch('journal schema structure invalid')
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
        self.db.executescript('''
            BEGIN IMMEDIATE;
            CREATE TABLE IF NOT EXISTS legacy_sources(
                archive_commit TEXT NOT NULL, source TEXT NOT NULL,
                source_digest TEXT NOT NULL, provenance_json TEXT NOT NULL,
                PRIMARY KEY(archive_commit, source));
            CREATE TABLE IF NOT EXISTS legacy_aliases(
                kind TEXT NOT NULL CHECK(kind IN ('report', 'text', 'photo')),
                digest TEXT NOT NULL, archive_commit TEXT NOT NULL, source TEXT NOT NULL,
                field TEXT NOT NULL,
                PRIMARY KEY(kind, digest, archive_commit, source, field),
                FOREIGN KEY(archive_commit, source) REFERENCES legacy_sources(archive_commit, source));
            PRAGMA user_version=2;
            COMMIT;
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

    def hold_legacy_sources(self, archive_commit, records):
        """Offline-only import of provenance-limited candidates, never completion.

        Requires a journal with no runtime batches. One alias can belong to many
        historical sources/fields. Every candidate blocks future reservation;
        none is promoted to exact submitted text, verified photo, or receipt.
        This does not verify archive completeness, grant seed permission or
        enable a remote bootstrap. Callers must independently reconcile writers.
        """
        import re
        from live_batch_input import digest, keys, text
        need(isinstance(archive_commit, str) and re.fullmatch(r'[a-f0-9]{40}', archive_commit),
             'immutable legacy archive commit required')
        need(isinstance(records, list) and bool(records), 'legacy sources required')
        try:
            with self.transaction():
                need(self.db.execute('SELECT 1 FROM batches LIMIT 1').fetchone() is None,
                     'legacy import requires offline journal without runtime batches')
                for record in records:
                    keys(record, ('source', 'sourceSha256', 'status', 'reportIds',
                                  'textCandidates', 'photoCandidates', 'unresolvedReferences'))
                    source = record['source']
                    text(source, 2000)
                    digest(record['sourceSha256'])
                    text(record['status'], 300)
                    need(isinstance(record['reportIds'], list))
                    need(isinstance(record['unresolvedReferences'], list))
                    for unresolved in record['unresolvedReferences']:
                        text(unresolved, 4000)
                    aliases = []
                    for report_id in record['reportIds']:
                        text(report_id, 2000)
                        aliases.append(('report', hashlib.sha256(report_id.encode()).hexdigest(), 'reportIds'))
                    for field, kind in [('textCandidates', 'text'), ('photoCandidates', 'photo')]:
                        need(isinstance(record[field], list))
                        for candidate in record[field]:
                            keys(candidate, ('field', 'sha256'))
                            text(candidate['field'], 2000)
                            digest(candidate['sha256'])
                            aliases.append((kind, candidate['sha256'], candidate['field']))
                    need(bool(aliases), 'legacy source needs held identity')
                    provenance = json.dumps(record, ensure_ascii=False, sort_keys=True, allow_nan=False)
                    self.db.execute('INSERT INTO legacy_sources VALUES(?,?,?,?)',
                                    (archive_commit, source, record['sourceSha256'], provenance))
                    self.db.executemany('INSERT INTO legacy_aliases VALUES(?,?,?,?,?)',
                                        [(kind, value, archive_commit, source, field)
                                         for kind, value, field in aliases])
        except sqlite3.IntegrityError:
            raise InvalidBatch('legacy source or alias conflict; reconcile before import') from None

    def _refuse_legacy_identity(self, kind, value):
        held = self.db.execute('SELECT 1 FROM legacy_aliases WHERE kind=? AND digest=? LIMIT 1',
                               (kind, value)).fetchone()
        need(held is None, 'legacy identity held; reconcile incident first')

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
                    self._refuse_legacy_identity('report', hashlib.sha256(report['reportId'].encode()).hexdigest())
                    self._refuse_legacy_identity('text', report['textSha256'])
                    self.db.execute('INSERT INTO reports VALUES(?,?,?,?,NULL,?)',
                                    (report['reportId'], manifest['batchId'], i, 'reserved', report['textSha256']))
                    for j, photo in enumerate(report['photos']):
                        self._refuse_legacy_identity('photo', photo['sha256'])
                        self.db.execute('INSERT INTO photos VALUES(?,?,?,?)',
                                        (photo['sha256'], report['reportId'], j, 'reserved'))
        except sqlite3.IntegrityError as error:
            if 'reports.text_digest' in str(error):
                raise InvalidBatch('text identity already reserved; reconcile incident first') from None
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
            # A confirmed CreateCase with zero planned attachments is complete.
            # This is transport completion, not a claim of municipal enforcement.
            pending = self.db.execute('SELECT 1 FROM photos WHERE report=? LIMIT 1', (report_id,)).fetchone()
            if pending is None:
                self.db.execute("UPDATE reports SET state='complete' WHERE id=?", (report_id,))

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
