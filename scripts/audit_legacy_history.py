"""Read immutable Git objects into a PRIVATE audit. Never seed, submit or write a repo.

Hash matches are reconciliation leads, never approval or exact-submission proof.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess

from live_batch_input import InvalidBatch, need, no_duplicates

HEX = re.compile(r'[a-f0-9]{64}\Z')
COMMIT = re.compile(r'[a-f0-9]{40}\Z')
MAX_JSON = 4 * 1024 * 1024
MAX_PHOTO = 20 * 1024 * 1024
TEXT_FIELDS = {'approved_description', 'description', 'exactHebrewText', 'exact_hebrew_text',
               'text', 'approved_text', 'report_text', 'filed_text_preserved_from_legacy_source'}
PHOTO_FIELDS = {'photo_sha256', 'photoSha256', 'original_sha256', 'originalSha256',
                'supplementary_photo_sha256'}
PAYLOAD_FIELDS = {'digest', 'submit_digest', 'submitted_digest', 'submission_digest'}
ID_FIELDS = {'id', 'report_id', 'reportId'}
REFERENCE_FIELDS = {'photo', 'path', 'file', 'filename', 'file_name', 'original_path'}
PHOTO_SUFFIXES = {'.jpg', '.jpeg', '.png', '.heic', '.webp'}


def sha(data): return hashlib.sha256(data).hexdigest()


class Snapshot:
    def __init__(self, repo, commit):
        need(isinstance(commit, str) and COMMIT.fullmatch(commit), 'immutable commit required')
        self.repo, self.commit = str(repo), commit
        resolved = self.git('rev-parse', '--verify', commit + '^{commit}').decode().strip()
        need(resolved == commit, 'immutable commit unavailable')
        self.entries = {}
        for raw in self.git('ls-tree', '-r', '-l', '-z', commit).split(b'\0'):
            if not raw: continue
            metadata, name = raw.split(b'\t', 1)
            mode, kind, oid, size = metadata.decode().split()
            path = name.decode('utf-8')
            self.entries[path] = (mode, kind, oid, int(size) if size != '-' else -1)
        need(len(self.entries) <= 50000, 'snapshot too large')

    def git(self, *args):
        result = subprocess.run(['git', '-C', self.repo, *args], capture_output=True, timeout=30)
        need(result.returncode == 0, 'immutable snapshot unavailable')
        return result.stdout

    def read(self, path, limit=MAX_JSON):
        entry = self.entries.get(path)
        need(entry is not None and entry[0] in ('100644', '100755') and entry[1] == 'blob',
             'tracked regular file required')
        need(0 <= entry[3] <= limit, 'audit input too large')
        data = self.git('cat-file', 'blob', entry[2])
        need(len(data) == entry[3], 'immutable object size mismatch')
        return data

    def document(self, path):
        raw = self.read(path)
        try:
            value = json.loads(raw, object_pairs_hook=no_duplicates,
                               parse_constant=lambda _: (_ for _ in ()).throw(InvalidBatch('invalid JSON')))
        except (ValueError, UnicodeError): raise InvalidBatch('invalid audit JSON') from None
        need(isinstance(value, dict), 'audit record must be object')
        return raw, value


def candidates(snapshot, path, document):
    result = dict(report=[], text=[], photo=[], payload=[], byteReferences=[], unresolvedReferences=[])
    result['report'].append(dict(field='source-folder', sha256=sha(PurePosixPath(path).parent.name.encode())))

    def reference(value, field):
        if not isinstance(value, str) or PurePosixPath(value).suffix.lower() not in PHOTO_SUFFIXES: return
        # Resolve only exact tracked relative paths, never host paths or symlinks.
        if PurePosixPath(value).is_absolute() or '..' in PurePosixPath(value).parts:
            result['unresolvedReferences'].append(dict(field=field, reason='unsafe-relative-path'))
            return
        options = [str(PurePosixPath(path).parent / value), value]
        existing = [name for name in dict.fromkeys(options) if name in snapshot.entries]
        if len(existing) != 1:
            result['unresolvedReferences'].append(dict(field=field, reason='missing-or-ambiguous-tracked-path'))
            return
        name = existing[0]
        try: digest = sha(snapshot.read(name, MAX_PHOTO))
        except InvalidBatch:
            result['unresolvedReferences'].append(dict(field=field, reason='unreadable-tracked-photo'))
            return
        result['photo'].append(dict(field=field, sha256=digest, meaning='referenced-bytes-candidate'))
        result['byteReferences'].append(dict(field=field, path=name, sha256=digest))

    def walk(value, trail=()):
        if isinstance(value, dict):
            for key, item in value.items():
                field = '.'.join(trail + (key,))
                if key in TEXT_FIELDS and isinstance(item, str) and item.strip():
                    result['text'].append(dict(field=field, sha256=sha(item.encode()), meaning='recorded-text-candidate'))
                if not trail and key in ID_FIELDS and isinstance(item, str) and item:
                    result['report'].append(dict(field=field, sha256=sha(item.encode())))
                for fields, kind in ((PHOTO_FIELDS, 'photo'), (PAYLOAD_FIELDS, 'payload')):
                    if key in fields and isinstance(item, str) and HEX.fullmatch(item):
                        result[kind].append(dict(field=field, sha256=item, meaning='recorded-hash-claim'))
                if key in REFERENCE_FIELDS: reference(item, field)
                if key in ('photos', 'supplementary_photos') and isinstance(item, list):
                    for index, image in enumerate(item): reference(image, field + '.' + str(index))
                walk(item, trail + (key,))
        elif isinstance(value, list):
            for index, item in enumerate(value): walk(item, trail + (str(index),))
    walk(document)
    return result


def audit(archive, ledger):
    records, errors, checkpoint_rows = [], [], []
    snapshots = [('archive', archive)]
    if ledger.commit != archive.commit:
        snapshots.append(('ledger', ledger))
    for scope, snapshot in snapshots:
        for path in sorted(snapshot.entries):
            if not (path.startswith(('reports/', 'requests/')) and PurePosixPath(path).name in ('report.json', 'request.json')
                    or path.startswith('app-reservations/') and path.endswith('.json')): continue
            try:
                raw, document = snapshot.document(path)
                status = document.get('status', 'unrecorded')
                records.append(dict(source=path if scope == 'archive' else 'ledger:' + path, snapshot=scope,
                                    snapshotCommit=snapshot.commit, sourceSha256=sha(raw),
                                    status=status if isinstance(status, str) else 'non-scalar-status',
                                    topLevelFields=sorted(document),
                                    identities=candidates(snapshot, path, document)))
            except InvalidBatch: errors.append(dict(source=path if scope == 'archive' else 'ledger:' + path, reason='record-unreadable-or-invalid'))
    raw, data = ledger.document('ledger.json')
    need(type(data.get('version')) is int and data.get('version') == 1 and all(isinstance(data.get(k), dict) for k in ('reports', 'photos')),
         'unsupported legacy ledger')
    # Keep identity namespaces separate. Helper payload digests are NOT text hashes.
    indices = {kind: {} for kind in ('report', 'photo', 'payload')}
    text_groups = {}
    for record in records:
        for kind in (*indices, 'text'):
            target = text_groups if kind == 'text' else indices[kind]
            for value in record['identities'][kind]:
                target.setdefault(value['sha256'], set()).add(record['source'])
    reports = []
    for key, value in sorted(data['reports'].items()):
        need(isinstance(value, dict), 'invalid legacy report entry')
        identity = key if HEX.fullmatch(key) else sha(key.encode())
        matches = set(indices['report'].get(identity, set()))
        links = dict(report=sorted(matches), photo=[], payload=[])
        for field, kind in [('photo_sha256', 'photo'), ('digest', 'payload')]:
            digest = value.get(field)
            if isinstance(digest, str) and HEX.fullmatch(digest):
                links[kind] = sorted(indices[kind].get(digest, set()))
        # A photo shared by two incidents is a lead, never an automatic report match.
        state = value.get('state')
        state = state if isinstance(state, dict) else {}
        reports.append(dict(ledgerIdentity=identity, entrySha256=sha(json.dumps(value, sort_keys=True).encode()),
                            recordedStatus=state.get('status') if isinstance(state.get('status'), str) else 'unrecorded',
                            hasCaseMetadata=bool(state.get('case')),
                            links=links, matchedByReportIdentity=bool(matches)))
    for path in sorted(ledger.entries):
        if not path.startswith('.106-state/') or not path.endswith('.json'): continue
        try:
            content, document = ledger.document(path)
            checkpoint_rows.append(dict(source=path, sourceSha256=sha(content),
                                         status=document.get('status') if isinstance(document.get('status'), str) else 'unrecorded',
                                         matchedLedgerIdentity=PurePosixPath(path).stem in {r['ledgerIdentity'] for r in reports}))
        except InvalidBatch: errors.append(dict(source=path, reason='checkpoint-unreadable-or-invalid'))
    photo_index = []
    ledger_ids = {r['ledgerIdentity'] for r in reports}
    for digest in sorted(data['photos']):
        need(HEX.fullmatch(digest), 'invalid ledger photo identity')
        owner = data['photos'][digest]
        need(isinstance(owner, str) and bool(owner), 'invalid ledger photo owner')
        owner_key = owner if HEX.fullmatch(owner) else sha(owner.encode())
        photo_index.append(dict(sha256=digest, sources=sorted(indices['photo'].get(digest, set())),
                                ownerInLedger=owner_key in ledger_ids))
    canonical = {record['source'] for record in records} | {error['source'] for error in errors}
    ancillary = []
    writer_candidates = []
    for path in sorted(archive.entries):
        if path.endswith('.json') and path not in canonical:
            try:
                blob = archive.read(path)
                ancillary.append(dict(source=path, sourceSha256=sha(blob), interpreted=False))
            except InvalidBatch:
                errors.append(dict(source=path, reason='ancillary-unreadable-or-oversize'))
        if path.startswith(('scripts/', '.github/workflows/')) and Path(path).suffix in ('.py', '.sh', '.yml', '.yaml', '.js', '.mjs'):
            writer_candidates.append(path)
    unresolved = sum(len(r['identities']['unresolvedReferences']) for r in records)
    return dict(version=1, auditOnly=True, seedAllowed=False, activationAllowed=False,
                snapshots=dict(archive=archive.commit, ledger=ledger.commit, ledgerFileSha256=sha(raw)),
                counts=dict(archiveRecords=sum(r['snapshot'] == 'archive' for r in records),
                            ledgerBranchRecords=sum(r['snapshot'] == 'ledger' for r in records), unreadableSources=len(errors), ledgerReports=len(reports),
                            ledgerReportsWithoutReportMatch=sum(not r['matchedByReportIdentity'] for r in reports),
                            photoIndexEntries=len(photo_index), photoIndexWithoutCandidateMatch=sum(not r['sources'] for r in photo_index),
                            checkpoints=len(checkpoint_rows), unresolvedPhotoReferences=unresolved,
                            uninterpretedAncillaryJson=len(ancillary),
                            photoOwnersWithoutLedgerEntry=sum(not row['ownerInLedger'] for row in photo_index)),
                records=records, ledgerReports=reports, ledgerPhotos=photo_index, checkpoints=checkpoint_rows,
                textAliasGroups={key: sorted(values) for key, values in text_groups.items() if len(values) > 1},
                errors=errors, uninterpretedAncillaryJson=ancillary,
                writerCandidatePaths=writer_candidates, writerCoverageVerified=False,
                opaqueLedgerSections={key: sha(json.dumps(value, sort_keys=True).encode())
                    for key, value in data.items() if key not in ('version', 'reports', 'photos')},
                blockers=['Identity matches are leads, not exact-submission or case proof.',
                          'Missing references and unmatched entries require explicit reconciliation; retain held identities.',
                          'Shared-photo and photo-vehicle guard sections are fingerprinted only, not migrated.',
                          'All old/new writers, branch freshness and concurrent activity require separate verification.',
                          'No seed, key provisioning, writer activation or city submission is authorized by an audit.'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', required=True)
    parser.add_argument('--archive-commit', required=True)
    parser.add_argument('--ledger-commit', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        result = audit(Snapshot(args.repo, args.archive_commit), Snapshot(args.repo, args.ledger_commit))
        path = Path(args.output)
        need(path.parent.absolute() == path.parent.resolve() and path.parent.stat().st_mode & 0o077 == 0, 'private output directory required')
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, 'w') as stream:
            json.dump(result, stream, sort_keys=True, ensure_ascii=False, allow_nan=False)
        print('HISTORY_AUDIT_ONLY_NO_SEED_OR_SUBMISSION')
        return 0
    except Exception:
        print('HISTORY_AUDIT_BLOCKED')
        return 1


if __name__ == '__main__': raise SystemExit(main())
