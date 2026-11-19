import subprocess
import unittest
from unittest.mock import Mock
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

    def test_success_once_help_only(self):
        run = Mock(return_value=self.result(0, 'BUILD SUCCESSFUL'))
        pause = Mock()
        self.assertEqual(target.warm(run, pause), 0)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0], ['sh', 'gradlew', '--no-daemon', 'help'])
        pause.assert_not_called()

    def test_exact_resolution_retries_bounded_and_returns_failure(self):
        run = Mock(return_value=self.result(1, RESOLUTION))
        pause = Mock()
        self.assertEqual(target.warm(run, pause), 1)
        self.assertEqual(run.call_count, 3)
        commands = [c.args[0] for c in run.call_args_list]
        self.assertNotIn('--refresh-dependencies', commands[0])
        self.assertTrue(all('--refresh-dependencies' in c for c in commands[1:]))
        self.assertTrue(all(c[-1] == 'help' for c in commands))
        self.assertEqual([c.args[0] for c in pause.call_args_list], [10, 30])

    def test_resolution_then_success(self):
        run = Mock(side_effect=[self.result(1, RESOLUTION), self.result(0, 'ok')])
        self.assertEqual(target.warm(run, Mock()), 0)
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
                self.assertEqual(target.warm(run, pause), 7)
                self.assertEqual(run.call_count, 1)
                pause.assert_not_called()

    def test_timeout_not_retried(self):
        run = Mock(side_effect=subprocess.TimeoutExpired('gradle', 180))
        with self.assertRaises(subprocess.TimeoutExpired):
            target.warm(run, Mock())
        self.assertEqual(run.call_count, 1)


if __name__ == '__main__':
    unittest.main()
