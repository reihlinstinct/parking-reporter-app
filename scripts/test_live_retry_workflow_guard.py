#!/usr/bin/env python3
"""Run the actual workflow guard with fake artifact/network/crypto tools, never a city."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
FAKE = '''#!/usr/bin/python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
root = pathlib.Path(os.environ['FAKE_ROOT'])
if name == 'curl':
    if '-o' in sys.argv:
        pathlib.Path(sys.argv[sys.argv.index('-o') + 1]).write_bytes(b'synthetic')
    else:
        print((root / 'listing.json').read_text())
elif name == 'unzip':
    dest = pathlib.Path(sys.argv[sys.argv.index('-d') + 1]); dest.mkdir(parents=True, exist_ok=True)
    (dest / 'record.enc').write_text(dest.name)
elif name == 'openssl':
    source = pathlib.Path(sys.argv[sys.argv.index('-in') + 1])
    print((root / (source.parent.name + '.json')).read_text())
elif name == 'python3':
    record = pathlib.Path(sys.argv[sys.argv.index('--record') + 1])
    with (root / 'calls').open('a') as out: out.write(str(record) + '\\n')
    sys.exit(0 if json.loads(record.read_text()).get('proof') == 'allow' else 1)
elif name == 'shred':
    path = pathlib.Path(sys.argv[-1])
    with (root / 'shredded').open('a') as out: out.write(str(path) + '\\n')
    path.unlink()
'''


class WorkflowGuardTest(unittest.TestCase):
    def run_guard(self, records, total=None):
        workflow = (ROOT / '.github/workflows/live-emulator-e2e.yml').read_text()
        block = workflow.split('          set -euo pipefail\n', 1)[1].split('      - name: Build app', 1)[0]
        block = '\n'.join(line[10:] for line in block.splitlines())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bindir = root / 'bin'
            bindir.mkdir()
            for name in ('curl', 'unzip', 'openssl', 'python3', 'shred'):
                tool = bindir / name
                tool.write_text(FAKE)
                tool.chmod(0o700)
            artifacts = []
            for number, record in enumerate(records, 1):
                artifacts.append(dict(id=number, expired=False, archive_download_url='https://example.invalid/synthetic'))
                (root / (str(number) + '.json')).write_text(json.dumps(record))
            (root / 'listing.json').write_text(json.dumps(dict(total_count=len(records) if total is None else total, artifacts=artifacts)))
            # Isolate the real shell fragment's scratch paths, no shared /tmp mutations.
            block = block.replace('/tmp/live-e2e', str(root / 'live-e2e'))
            env = dict(os.environ, PATH=str(bindir) + ':' + os.environ['PATH'], FAKE_ROOT=directory,
                       GH_TOKEN='synthetic', GITHUB_REPOSITORY='synthetic/repository',
                       APPROVED_SHA256_INPUT='a' * 64, APPROVED_PHOTO_SHA256_INPUT='b' * 64,
                       TRIAL_DATA_KEY='synthetic')
            result = subprocess.run(['bash', '-c', 'set -euo pipefail\n' + block], env=env, capture_output=True, text=True, timeout=10)
            calls = (root / 'calls').read_text().splitlines() if (root / 'calls').exists() else []
            shredded = (root / 'shredded').read_text().splitlines() if (root / 'shredded').exists() else []
            self.assertEqual(calls, shredded, 'every plaintext proof input must be shredded even on refusal')
            self.assertTrue(all(not Path(path).exists() for path in calls))
            return result.returncode, len(calls)

    def record(self, proof='allow', photo='b' * 64):
        return dict(approvedSha256='a' * 64, approvedPhotoSha256=photo, proof=proof)

    def test_no_matching_record_needs_no_exception(self):
        self.assertEqual((0, 0), self.run_guard([self.record(photo='c' * 64)]))

    def test_proven_record_passes_and_is_shredded(self):
        self.assertEqual((0, 1), self.run_guard([self.record()]))

    def test_refused_record_stops_and_is_shredded(self):
        code, calls = self.run_guard([self.record('refuse')])
        self.assertNotEqual(0, code)
        self.assertEqual(1, calls)

    def test_every_matching_record_requires_proof(self):
        code, calls = self.run_guard([self.record(), self.record('refuse')])
        self.assertNotEqual(0, code)
        self.assertEqual(2, calls)

    def test_truncated_or_malformed_listing_refuses_before_proof(self):
        for total in (101, 2, 0, -1, '1', {}):
            code, calls = self.run_guard([self.record()], total=total)
            self.assertNotEqual(0, code)
            self.assertEqual(0, calls)


if __name__ == '__main__':
    unittest.main()
