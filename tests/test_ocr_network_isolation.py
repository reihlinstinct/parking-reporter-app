import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class NetworkIsolationTest(unittest.TestCase):
    def probe(self, codes):
        with tempfile.TemporaryDirectory() as directory:
            # Functions run in the same shell; calls inside if are not subshells.
            script = '''source "$1"
codes=($3)
index=0
adb() {
    if [[ "$*" == *toybox* ]]; then
        result=${codes[$index]:-0}
        index=$((index + 1))
        return "$result"
    fi
    return 0
}
sleep() { :; }
ocr_network_isolation "$2"
'''
            return subprocess.run(['bash', '-c', script, 'test', str(ROOT/'scripts/ocr-network-isolation.sh'), directory, ' '.join(map(str, codes))], capture_output=True).returncode
    def test_positive_probe_must_work(self):
        self.assertNotEqual(0, self.probe([1]))
    def test_transition_can_settle_to_three_negative_probes(self):
        self.assertEqual(0, self.probe([0, 0, 0, 1, 1, 1]))
    def test_permanently_online_fails_closed(self):
        self.assertNotEqual(0, self.probe([0]*11))
    def test_one_negative_does_not_pass(self):
        self.assertNotEqual(0, self.probe([0, 1]+[0]*9))
    def test_online_probe_resets_negative_streak(self):
        self.assertNotEqual(0, self.probe([0, 1, 1, 0, 1, 1, 0, 1, 1, 0, 1]))

if __name__ == '__main__': unittest.main()
