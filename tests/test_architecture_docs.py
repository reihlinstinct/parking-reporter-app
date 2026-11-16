"""Keep the runtime architecture's source map and security boundary explicit.

These checks validate documentation structure, not Android behavior or encryption.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/io/github/reihlinstinct/reporter"


class ArchitectureDocumentationTest(unittest.TestCase):
    def test_source_map_paths_exist(self):
        text = (ROOT / "ARCHITECTURE.md").read_text()
        table = text.split("### Source map", 1)[1].split("Paths in this table", 1)[0]
        paths = re.findall(r"`([^`]+)`", table)
        self.assertGreater(len(paths), 10)
        for path in paths:
            with self.subTest(path=path):
                self.assertTrue((SOURCE / path).exists(), path)

    def test_active_and_inactive_storage_are_distinguished(self):
        text = (ROOT / "ARCHITECTURE.md").read_text()
        active = text.split("## Active Android runtime", 1)[1].split("Paths in this table", 1)[0]
        self.assertIn("no application-layer encryption", active)
        self.assertIn("SecureSettings", active)
        self.assertNotIn("EncryptedLedgerRepository", active)
        self.assertIn("`EncryptedLedgerRepository` is explicitly inactive", text)

    def test_architecture_is_discoverable_and_reviewed(self):
        self.assertIn("(ARCHITECTURE.md)", (ROOT / "README.md").read_text())
        checklist = (ROOT / ".github/PULL_REQUEST_TEMPLATE.md").read_text()
        for path in ("ARCHITECTURE.md", "docs/DESIGN.md", "docs/VEHICLE_REGISTRY.md", "M1_STATUS.md", "README.md"):
            with self.subTest(path=path):
                self.assertIn(path, checklist)
        self.assertIn("updated only where stale", checklist)


if __name__ == "__main__":
    unittest.main()
