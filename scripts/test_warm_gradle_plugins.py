import subprocess
import unittest
from unittest.mock import Mock, MagicMock
import warm_gradle_plugins as target

VERSION = '2.3.14'
RESOLUTION = f"""FAILURE: Build failed with an exception.
* What went wrong:
Plugin [id: 'com.google.devtools.ksp', version: '{VERSION}', apply: false] was not found in any of the following sources:
- Plugin Repositories (could not resolve plugin artifact 'com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:{VERSION}')
"""


class PluginWarmTest(unittest.TestCase):
    def result(self, code, text):
        return subprocess.CompletedProcess([], code, text)

    def test_ksp_routes_exclusively_to_central_in_both_scopes(self):
        text = (target.ROOT / 'settings.gradle.kts').read_text()
        self.assertEqual(2, text.count('exclusiveContent {'))
        self.assertEqual(2, text.count('filter { includeGroup("com.google.devtools.ksp") }'))
        self.assertEqual(2, text.count('forRepositories('))
        self.assertEqual(2, text.count('url = uri("https://repo1.maven.org/maven2")'))
        self.assertEqual(2, text.count('name = "KspCentralAlternate"'))
        self.assertNotIn('allowInsecureProtocol', text)
        self.assertNotIn('isAllowInsecureProtocol', text)
        self.assertIn('google()', text)
        self.assertIn('gradlePluginPortal()', text)
        self.assertIn('includeGroup("cz.adaptech.tesseract4android")', text)

    def test_terminal_exact_failure_probes_once_and_still_fails(self):
        run = Mock(return_value=self.result(1, RESOLUTION))
        probe = Mock()
        self.assertEqual(1, target.warm(run, Mock(), probe))
        probe.assert_called_once_with(VERSION)
        self.assertEqual(3, run.call_count)

    def test_probe_is_bounded_head_only_and_does_not_read_content(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.status = 200
        target.central_probe(VERSION, opener)
        self.assertEqual(4, opener.open.call_count)
        for call in opener.open.call_args_list:
            request = call.args[0]
            self.assertEqual('HEAD', request.get_method())
            self.assertIn(request.host, ('repo.maven.apache.org', 'repo1.maven.org'))
            self.assertIn('/maven2/com/google/devtools/ksp/', request.full_url)
            self.assertEqual(5, call.kwargs['timeout'])
        opener.open.return_value.__enter__.return_value.read.assert_not_called()

    def test_probe_network_error_redacts_message_and_returns(self):
        import contextlib
        import io
        opener = MagicMock()
        opener.open.side_effect = OSError('SYNTHETIC_PRIVATE_ERROR_MUST_NOT_PRINT')
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            target.central_probe(VERSION, opener)
        self.assertEqual(4, opener.open.call_count)
        self.assertNotIn('SYNTHETIC_PRIVATE', output.getvalue())
        self.assertEqual(4, output.getvalue().count('network-unavailable'))
        opener.reset_mock()
        target.central_probe('bad/version', opener)
        opener.open.assert_not_called()

    def test_http_protocol_failure_never_prints_server_text(self):
        import contextlib
        import http.client
        import io
        for error in [http.client.BadStatusLine('SYNTHETIC_PRIVATE_SERVER_TEXT'),
                      http.client.IncompleteRead(b'SYNTHETIC_PRIVATE_BODY'),
                      http.client.RemoteDisconnected('SYNTHETIC_PRIVATE_REASON')]:
            with self.subTest(error=type(error).__name__):
                opener = MagicMock()
                opener.open.side_effect = error
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    target.central_probe(VERSION, opener)
                self.assertNotIn('SYNTHETIC_PRIVATE', output.getvalue())
                self.assertEqual(4, output.getvalue().count('network-unavailable'))

    def test_http_error_is_closed_without_reading_body(self):
        import contextlib
        import io
        import urllib.error
        body = MagicMock()
        error = urllib.error.HTTPError('https://synthetic.invalid', 404, 'private reason', {}, body)
        opener = MagicMock()
        opener.open.side_effect = error
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            target.central_probe(VERSION, opener)
        body.read.assert_not_called()
        self.assertTrue(body.close.called)
        self.assertNotIn('private reason', output.getvalue())
        self.assertEqual(4, output.getvalue().count(': 404.'))

    def test_success_once_help_only(self):
        run = Mock(return_value=self.result(0, 'BUILD SUCCESSFUL'))
        pause = Mock()
        self.assertEqual(target.warm(run, pause, Mock()), 0)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0], ['sh', 'gradlew', '--no-daemon', 'help'])
        pause.assert_not_called()

    def test_exact_resolution_retries_bounded_and_returns_failure(self):
        run = Mock(return_value=self.result(1, RESOLUTION))
        pause = Mock()
        self.assertEqual(target.warm(run, pause, Mock()), 1)
        self.assertEqual(run.call_count, 3)
        commands = [c.args[0] for c in run.call_args_list]
        self.assertNotIn('--refresh-dependencies', commands[0])
        self.assertTrue(all('--refresh-dependencies' in c for c in commands[1:]))
        self.assertTrue(all(c[-1] == 'help' for c in commands))
        self.assertEqual([c.args[0] for c in pause.call_args_list], [10, 30])
        self.assertNotIn('--info', commands[0])
        self.assertNotIn('--info', commands[1])
        self.assertIn('--info', commands[2])

    def test_resolution_then_success(self):
        run = Mock(side_effect=[self.result(1, RESOLUTION), self.result(0, 'ok')])
        self.assertEqual(target.warm(run, Mock(), Mock()), 0)
        self.assertEqual(run.call_count, 2)

    def test_compile_test_other_plugin_and_wrong_version_never_retry(self):
        for text in [RESOLUTION + '\n> Task :app:testDebugUnitTest FAILED',
                     RESOLUTION + '\n> Task :app:compileDebugKotlin FAILED',
                     RESOLUTION.replace(VERSION, '9.9.9'),
                     RESOLUTION.replace('com.google.devtools.ksp', 'another.plugin'),
                     'FAILURE: assertion failed', 'Network timeout']:
            with self.subTest(text=text):
                run = Mock(return_value=self.result(7, text))
                pause = Mock()
                self.assertEqual(target.warm(run, pause, Mock()), 7)
                self.assertEqual(run.call_count, 1)
                pause.assert_not_called()

    def test_timeout_not_retried(self):
        run = Mock(side_effect=subprocess.TimeoutExpired('gradle', 180))
        with self.assertRaises(subprocess.TimeoutExpired):
            target.warm(run, Mock(), Mock())
        self.assertEqual(run.call_count, 1)


if __name__ == '__main__':
    unittest.main()
