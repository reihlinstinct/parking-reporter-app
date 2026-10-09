"""Check the documentation-stage foundation, not app correctness or all secrets."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
REQUIRED = (
    "README.md", "SECURITY.md", "CONTRIBUTING.md", "docs/DESIGN.md",
    "docs/RELEASE_CHECKLIST.md", ".gitignore", ".editorconfig",
    ".github/PULL_REQUEST_TEMPLATE.md", ".github/ISSUE_TEMPLATE/bug_report.md",
    ".github/ISSUE_TEMPLATE/feature_request.md",
    ".github/workflows/repository-checks.yml",
)


def check() -> list[str]:
    errors: list[str] = []
    for relative in REQUIRED:
        path = ROOT / relative
        if not path.is_file() or not path.read_text(encoding="utf-8").strip():
            errors.append(f"Missing or empty required file: {relative}")
    for path in ROOT.rglob("*.md"):
        if ".git" in path.parts:
            continue
        text = path.read_text(encoding="utf-8")
        for target in re.findall(r"\]\(([^)]+)\)", text):
            if ":" in target or target.startswith("#"):
                continue
            local = target.split("#", 1)[0]
            if local and not (path.parent / local).exists():
                errors.append(f"Broken local link in {path.relative_to(ROOT)}: {target}")
    readme = (ROOT / "README.md").read_text(encoding="utf-8") if (ROOT / "README.md").exists() else ""
    for milestone in range(7):
        if f"M{milestone}" not in readme:
            errors.append(f"README missing M{milestone}")
    for path in ROOT.rglob("*"):
        if not path.is_file() or ".git" in path.parts:
            continue
        if path.suffix.lower() in {".jks", ".keystore", ".p12", ".apk", ".aab", ".db"} or path.name == ".env":
            errors.append(f"Forbidden sensitive/generated file: {path.relative_to(ROOT)}")
    return errors


if __name__ == "__main__":
    failures = check()
    for failure in failures:
        print(failure, file=sys.stderr)
    if failures:
        sys.exit(1)
    print("Scaffolding checks passed. This script does not prove Android build/unit/e2e results.")
