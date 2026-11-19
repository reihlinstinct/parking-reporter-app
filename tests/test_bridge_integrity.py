"""Offline structural tripwires; actual cold-cache resolution remains CI proof."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {'g': 'https://schema.gradle.org/dependency-verification'}

class BridgeIntegrityTest(unittest.TestCase):
    def test_metadata_has_no_trust_bypass_and_every_artifact_has_sha256(self):
        tree = ET.parse(ROOT / 'api-bridge/gradle/verification-metadata.xml')
        self.assertEqual('true', tree.find('g:configuration/g:verify-metadata', NS).text)
        self.assertEqual([], tree.findall('.//g:trusted-artifact', NS))
        artifacts = tree.findall('.//g:artifact', NS)
        self.assertGreater(len(artifacts), 100)
        for artifact in artifacts:
            hashes = artifact.findall('g:sha256', NS)
            self.assertTrue(hashes, artifact.attrib)
            for checksum in hashes:
                self.assertRegex(checksum.attrib['value'], r'^[0-9a-f]{64}$')
                self.assertNotEqual('0' * 64, checksum.attrib['value'])

    def test_every_credentialed_standalone_compile_is_explicitly_strict(self):
        for name in ('submission-branch', 'supervised-api-trial', 'two-car-ci', 'api-bridge'):
            source = (ROOT / ('.github/workflows/' + name + '.yml')).read_text()
            lines = [line for line in source.splitlines() if 'gradlew -p api-bridge' in line]
            self.assertTrue(lines, name)
            for line in lines:
                self.assertIn('--dependency-verification strict', line)
                self.assertNotIn('--write-verification-metadata', line)
                self.assertNotIn('|| true', line)
