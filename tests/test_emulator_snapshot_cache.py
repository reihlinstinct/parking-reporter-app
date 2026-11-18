import importlib.util
import io
import json
import os
import subprocess
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timedelta, timezone
from pathlib import Path

spec = importlib.util.spec_from_file_location(
    'snapcache', Path(__file__).resolve().parents[1] / 'scripts/emulator_snapshot_cache.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

NOW = datetime(2026, 11, 17, 12, 0, tzinfo=timezone.utc)
SHA = 'a' * 64
SHA40 = 'b' * 40


def inputs(**kw):
    d = dict(api_level='36', arch='x86_64', target='default', profile='pixel_2',
             system_image_revision='r2', emulator_version='36.1.9', runner_image='ubuntu24/20261110',
             app_source_sha256=SHA, prepare_script_sha256='c' * 64, epoch=m.epoch_for(NOW))
    d.update(kw)
    return d


def manifest(created=NOW, **kw):
    mm = m.build_manifest(inputs(), created, 'refs/heads/main', SHA40)
    mm.update(kw)
    return mm


def git(cwd, *a):
    subprocess.run(['git', *a], cwd=cwd, check=True, capture_output=True,
                   env={**os.environ, 'GIT_AUTHOR_NAME': 't', 'GIT_AUTHOR_EMAIL': 't@t', 'GIT_COMMITTER_NAME': 't',
                        'GIT_COMMITTER_EMAIL': 't@t'})


class KeyTest(unittest.TestCase):
    def test_directory_rejection_uses_redacted_classifier(self):
        import tempfile
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'trial-private-name').mkdir()
            with self.assertRaisesRegex(m.CacheRejected, '^forbidden directory name in cache tree: sensitive_name_redacted$'):
                m.scan_tree(root)

    def test_every_input_changes_the_key(self):
        base = m.cache_key(inputs())
        self.assertEqual(base, m.cache_key(inputs()))
        for field, value in [('api_level', '35'), ('arch', 'arm64'), ('target', 'google_apis'),
                             ('profile', 'pixel_6'), ('system_image_revision', 'r3'),
                             ('emulator_version', '36.2.0'), ('runner_image', 'ubuntu24/20261120'),
                             ('app_source_sha256', 'd' * 64), ('prepare_script_sha256', 'e' * 64),
                             ('epoch', m.epoch_for(NOW) + 1)]:
            with self.subTest(field=field):
                self.assertNotEqual(base, m.cache_key(inputs(**{field: value})))

    def test_bad_inputs_rejected(self):
        for bad in [inputs(app_source_sha256='XYZ'), inputs(api_level=''), inputs(epoch=-1),
                    inputs(epoch=True), {**inputs(), 'extra': '1'}]:
            with self.subTest(bad=bad), self.assertRaises(m.CacheRejected):
                m.cache_key(bad)

    def test_epoch_is_at_most_every_two_days(self):
        self.assertNotEqual(m.epoch_for(NOW), m.epoch_for(NOW + timedelta(days=2)))
        self.assertLess(m.EPOCH_DAYS * 24, 72)


class ProvenanceTest(unittest.TestCase):
    def check(self, mm, exp=None, now=NOW):
        m.verify_manifest(mm, exp or inputs(), now)

    def test_fresh_valid_passes(self):
        self.check(manifest(NOW - timedelta(hours=71, minutes=59)))

    def test_rejections(self):
        stale = manifest(NOW - timedelta(hours=72, minutes=1))
        future = manifest(NOW + timedelta(hours=1))
        cases = {
            'stale': (stale, None), 'future': (future, None),
            'other inputs': (manifest(), inputs(api_level='35')),
            'dirty marker': (manifest(clean_build=False), None),
            'private marker': (manifest(private_data_injected=True), None),
            'pr producer': (manifest(producer_ref='refs/pull/1/merge'), None),
            'branch producer': (manifest(producer_ref='refs/heads/feat/x'), None),
            'no sha': (manifest(producer_sha='zz'), None),
            'wrong key': (manifest(key='emu-snap-v1-' + '0' * 40), None),
            'extra field': (manifest(note='x'), None),
            'bad time': (manifest(created_at='yesterday'), None),
            'schema': (manifest(schema=2), None),
            'not object': ([], None),
        }
        for name, (mm, exp) in cases.items():
            with self.subTest(name), self.assertRaises(m.CacheRejected):
                self.check(mm, exp)
        mm = manifest(); del mm['created_at']
        with self.assertRaises(m.CacheRejected):
            self.check(mm)

    def test_allowed_ref_is_exact(self):
        mm = manifest(producer_ref='refs/heads/feat/x')
        m.verify_manifest(mm, inputs(), NOW, allowed_ref='refs/heads/feat/x')
        with self.assertRaises(m.CacheRejected):
            m.verify_manifest(mm, inputs(), NOW)

    def test_small_clock_skew_tolerated(self):
        self.check(manifest(NOW + timedelta(minutes=4)))


class ScanTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name) / 'avd'
        (self.root / 'snap/default_boot').mkdir(parents=True)
        for n in ['config.ini', 'hardware-qemu.ini', 'userdata-qemu.img', 'sdcard.img', 'snap/default_boot/ram.bin',
                  'snap/default_boot/snapshot.pb', 'snap/default_boot/screenshot.png']:
            (self.root / n).write_bytes(b'x')

    def test_clean_avd_passes(self):
        m.scan_tree(self.root)

    def test_private_looking_content_rejected(self):
        for n in ['photo.jpg', 'live-params.json', 'addresses.json', 'rec.mp4', 'a.zip', 'x.enc',
                  'TRIAL_DATA', 'token', 'run.log', 'notes.txt', 'id_rsa', 'secret.key']:
            with self.subTest(n):
                p = self.root / n
                p.write_bytes(b'x')
                with self.assertRaises(m.CacheRejected):
                    m.scan_tree(self.root)
                p.unlink()

    def test_rejection_diagnostics_are_fixed_vocabulary_and_keep_every_file_blocked(self):
        cases = {
            'read-snapshot.txt': 'emulator_read_snapshot_txt',
            'qemu.log': 'emulator_qemu_log',
            'emulator.log': 'emulator_log',
            'quickbootChoice.txt': 'emulator_quickboot_choice_txt',
            'synthetic-private-label.txt': 'blocked_suffix_txt',
            'synthetic-token-value.json': 'sensitive_name_redacted',
            'synthetic-photo.jpg': 'blocked_suffix_jpg',
        }
        for name, label in cases.items():
            with self.subTest(name=name):
                file = self.root / name
                file.write_text('synthetic private body never logged')
                try:
                    with self.assertRaises(m.CacheRejected) as raised:
                        m.scan_tree(self.root)
                    self.assertEqual('forbidden file name in cache tree: ' + label, str(raised.exception))
                    self.assertNotIn(name, str(raised.exception))
                    self.assertNotIn('private body', str(raised.exception))
                finally:
                    file.unlink()

    def test_symlink_and_bad_root_rejected(self):
        os.symlink('/etc/passwd', self.root / 'link.img')
        with self.assertRaises(m.CacheRejected):
            m.scan_tree(self.root)
        with self.assertRaises(m.CacheRejected):
            m.scan_tree(Path(self.tmp.name) / 'missing')

    def test_forbidden_directory_rejected(self):
        (self.root / 'credentials').mkdir()
        with self.assertRaises(m.CacheRejected):
            m.scan_tree(self.root)


class CliTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        t = Path(self.tmp.name)
        self.avd = t / 'avd'; self.avd.mkdir()
        (self.avd / 'config.ini').write_text('k=v')
        (t / 'src').mkdir(); (t / 'src/app').mkdir()
        (t / 'src/app/a.kt').write_text('code')
        (t / 'src/.gitignore').write_text('app/ignored/\n')
        git(t / 'src', 'init', '-q')
        git(t / 'src', 'add', '-A')
        (t / 'prep.sh').write_text('prep')
        (t / 'prep2.sh').write_text('prep2')
        self.env = dict(EMU_API_LEVEL='36', EMU_ARCH='x86_64', EMU_TARGET='default', EMU_PROFILE='pixel_2',
                        EMU_IMAGE_REVISION='r2', EMU_VERSION='1', EMU_RUNNER_IMAGE='img',
                        EMU_SOURCE_ROOT=str(t / 'src'), EMU_PREPARE_PATHS=f"{t / 'prep.sh'} {t / 'prep2.sh'}",
                        GITHUB_REF='refs/heads/main', GITHUB_SHA=SHA40)

    def run_cli(self, *a, env=None):
        buf = io.StringIO()
        with redirect_stdout(buf):
            rc = m.main(list(a), self.env if env is None else env)
        return rc, buf.getvalue()

    def test_roundtrip_and_source_change(self):
        rc, out = self.run_cli('write-manifest', '--avd-dir', str(self.avd))
        self.assertEqual((rc, out.strip()), (0, 'SNAPSHOT-MANIFEST-WRITTEN'))
        rc, out = self.run_cli('verify', '--avd-dir', str(self.avd))
        self.assertEqual((rc, out.strip()), (0, 'SNAPSHOT-VERIFIED'))
        (Path(self.env['EMU_SOURCE_ROOT']) / 'app/a.kt').write_text('changed')
        rc, out = self.run_cli('verify', '--avd-dir', str(self.avd))
        self.assertEqual(rc, 3)
        self.assertIn('SNAPSHOT-REJECTED', out)

    def test_ignored_and_untracked_files_do_not_change_source_digest(self):
        # Models the OCR asset that prepare_wrapper.sh downloads into app/
        # after the key is computed (review P2-2).
        root = Path(self.env['EMU_SOURCE_ROOT'])
        before = m.source_digest(root)
        (root / 'app/ignored').mkdir(); (root / 'app/ignored/eng.traineddata').write_text('model')
        (root / 'app/untracked.bin').write_text('x')
        (root / 'app/build').mkdir(); (root / 'app/build/x.apk').write_text('1')
        self.assertEqual(before, m.source_digest(root))

    def test_key_before_build_equals_inputs_at_verify_time(self):
        rc, key_before = self.run_cli('key')
        self.assertEqual(rc, 0)
        root = Path(self.env['EMU_SOURCE_ROOT'])
        (root / 'app/ignored').mkdir(); (root / 'app/ignored/eng.traineddata').write_text('model')
        self.assertEqual(self.run_cli('write-manifest', '--avd-dir', str(self.avd))[0], 0)
        manifest = json.loads((self.avd / 'provenance.json').read_text())
        self.assertEqual(manifest['key'], key_before.strip())
        self.assertEqual(self.run_cli('verify', '--avd-dir', str(self.avd))[1].strip(), 'SNAPSHOT-VERIFIED')

    def test_tracked_change_changes_source_digest_and_non_git_fails_closed(self):
        root = Path(self.env['EMU_SOURCE_ROOT'])
        before = m.source_digest(root)
        (root / 'app/a.kt').write_text('changed')
        self.assertNotEqual(before, m.source_digest(root))
        with tempfile.TemporaryDirectory() as d, self.assertRaises(m.CacheRejected):
            m.source_digest(Path(d))

    def test_every_prepare_path_rotates_the_key(self):
        _, k1 = self.run_cli('key')
        Path(self.env['EMU_PREPARE_PATHS'].split()[1]).write_text('changed')
        _, k2 = self.run_cli('key')
        self.assertNotEqual(k1, k2)

    def test_two_allowed_refs_on_cli(self):
        self.run_cli('write-manifest', '--avd-dir', str(self.avd))
        env = {**self.env, 'GITHUB_REF': 'refs/heads/feat/x'}
        buf = io.StringIO()
        with redirect_stdout(buf):
            self.assertEqual(m.main(['write-manifest', '--avd-dir', str(self.avd)], env), 0)
        self.assertEqual(self.run_cli('verify', '--avd-dir', str(self.avd))[0], 3)
        self.assertEqual(self.run_cli('verify', '--avd-dir', str(self.avd), '--allowed-ref', 'refs/heads/main',
                                      '--allowed-ref', 'refs/heads/feat/x')[0], 0)

    def test_missing_env_and_missing_manifest_reject(self):
        self.assertEqual(self.run_cli('verify', '--avd-dir', str(self.avd))[0], 3)
        self.assertEqual(self.run_cli('key', env={})[0], 3)

    def test_private_file_blocks_publishing(self):
        (self.avd / 'live-params.json').write_text('{}')
        rc, out = self.run_cli('write-manifest', '--avd-dir', str(self.avd))
        self.assertEqual(rc, 3)
        self.assertFalse((self.avd / 'provenance.json').exists())


if __name__ == '__main__':
    unittest.main()


class WorkflowBoundaryTest(unittest.TestCase):
    root = Path(__file__).resolve().parents[1]
    files = ['.github/workflows/emulator-snapshot-producer.yml',
             '.github/workflows/emulator-snapshot-benchmark.yml',
             '.github/actions/emulator-snapshot-key/action.yml',
             '.github/actions/emulator-snapshot-restore/action.yml']

    def text(self, rel):
        return (self.root / rel).read_text()

    def test_no_secrets_environments_or_tunnels(self):
        for rel in self.files:
            t = self.text(rel)
            for bad in ('secrets.', 'environment:', 'TRIAL', 'vpn', 'openssl', 'id-token'):
                with self.subTest(rel=rel, bad=bad):
                    self.assertNotIn(bad, t.replace('secrets.GITHUB_TOKEN', ''))

    def test_restore_is_exact_key_only(self):
        for rel in self.files:
            self.assertNotIn('restore-keys', self.text(rel))

    def test_benchmark_branch_may_accept_main_and_own_ref(self):
        r = self.text('.github/actions/emulator-snapshot-restore/action.yml')
        self.assertIn('--allowed-ref refs/heads/main', r)
        self.assertIn('extra-allowed-ref', self.text('.github/workflows/emulator-snapshot-benchmark.yml'))
        self.assertNotIn('extra-allowed-ref', self.text('.github/workflows/emulator-snapshot-producer.yml'))

    def test_only_producer_saves_and_only_the_avd_dir(self):
        saves = [rel for rel in self.files if 'cache/save' in self.text(rel)]
        self.assertEqual(saves, ['.github/workflows/emulator-snapshot-producer.yml'])
        t = self.text(saves[0])
        self.assertEqual(t.count('path: ~/.android/avd'), 2)  # lookup and save
        self.assertNotIn('~/.android/adb', t)

    def test_producer_publishes_only_from_main_or_branch_scoped_trial(self):
        t = self.text('.github/workflows/emulator-snapshot-producer.yml')
        self.assertIn("github.ref == 'refs/heads/main' || inputs.allow-trial-ref == true", t)
        self.assertIn('contents: read', t)

    def test_live_workflow_not_referencing_the_cache(self):
        t = self.text('.github/workflows/live-emulator-e2e.yml')
        self.assertNotIn('snapshot', t.lower())
