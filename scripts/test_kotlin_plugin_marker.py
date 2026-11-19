"""Pin both official Kotlin marker encodings without widening dependency verification."""
import hashlib
import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / 'app/src/test/resources/gradle/kotlin-jvm-marker-2.4.21'
NS = {'p': 'http://maven.apache.org/POM/4.0.0'}

def semantics(data):
    doc = ET.fromstring(data)
    def field(node, key):
        return node.findtext('p:' + key, namespaces=NS)
    # Descriptive publication metadata cannot add executable dependencies/plugins/repositories.
    allowed = {'modelVersion', 'groupId', 'artifactId', 'version', 'packaging', 'dependencies',
               'name', 'description', 'url', 'licenses', 'developers', 'scm'}
    if any(child.tag.split('}')[-1] not in allowed for child in doc):
        raise ValueError('Unexpected marker element')
    deps = []
    for dep in doc.findall('p:dependencies/p:dependency', NS):
        if any(child.tag.split('}')[-1] not in {'groupId', 'artifactId', 'version'} for child in dep):
            raise ValueError('Unexpected marker dependency semantics')
        deps.append(tuple(field(dep, key) for key in ('groupId', 'artifactId', 'version')))
    return tuple(field(doc, key) for key in ('modelVersion', 'groupId', 'artifactId', 'version', 'packaging')), deps

class MarkerTest(unittest.TestCase):
    def test_exact_official_hashes_and_semantics(self):
        rows = json.loads((FIXTURES / 'provenance.json').read_text())['artifacts']
        self.assertEqual(2, len(rows))
        expected = (('4.0.0', 'org.jetbrains.kotlin.jvm', 'org.jetbrains.kotlin.jvm.gradle.plugin',
                     '2.4.21', 'pom'), [('org.jetbrains.kotlin', 'kotlin-gradle-plugin', '2.4.21')])
        for row in rows:
            data = (FIXTURES / row['file']).read_bytes()
            self.assertEqual(row['sha256'], hashlib.sha256(data).hexdigest())
            self.assertEqual(row['bytes'], len(data))
            self.assertEqual(expected, semantics(data))
        metadata = ET.parse(ROOT / 'api-bridge/gradle/verification-metadata.xml').getroot()
        ns = {'v': metadata.tag.split('}')[0][1:]}
        component = next(c for c in metadata.findall('.//v:component', ns)
                         if c.get('group') == 'org.jetbrains.kotlin.jvm' and c.get('version') == '2.4.21')
        artifact = component.find('v:artifact', ns)
        hashes = {element.get('value') for element in artifact.iter()
                  if element.tag.split('}')[-1] in {'sha256', 'also-trust'}}
        self.assertEqual({row['sha256'] for row in rows}, hashes)
        self.assertIsNone(component.find('v:trusted-artifacts', ns))

    def test_dependency_mutation_is_not_equivalent(self):
        data = (FIXTURES / 'gradle-plugin-portal.xml').read_bytes()
        self.assertNotEqual(semantics(data), semantics(data.replace(b'<version>2.4.21</version>',
                            b'<version>2.4.22</version>')))
        with self.assertRaises(ValueError):
            semantics(data.replace(b'</project>', b'<repositories/></project>'))

if __name__ == '__main__':
    unittest.main()
