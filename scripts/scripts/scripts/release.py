#!/usr/bin/env python3
"""Automated semantic release from conventional commits.

Computes the next version from commit subjects since the last v* tag
(breaking -> major, feat -> minor, anything else -> patch), updates
CHANGELOG.md and app/build.gradle.kts, and writes bilingual (Hebrew/English)
release notes. Prints key=value outputs for the workflow. Exits without
releasing when there is no baseline tag, no new commits, or only release
commits.
"""
import os
import re
import subprocess
import sys
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = ROOT / "CHANGELOG.md"
GRADLE = ROOT / "app/build.gradle.kts"
RELEASE_COMMIT_PREFIX = "chore(release):"


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(ROOT), *args], capture_output=True, text=True, check=True).stdout.strip()


def last_tag() -> str | None:
    try:
        return git("describe", "--tags", "--abbrev=0", "--match=v[0-9]*")
    except subprocess.CalledProcessError:
        return None


def emit(name: str, value: str) -> None:
    print(f"{name}={value}")
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a", encoding="utf-8") as handle:
            handle.write(f"{name}={value}\n")


def main() -> int:
    tag = last_tag()
    if not tag:
        print("No baseline v* tag exists; skipping automated release.")
        emit("released", "false")
        return 0
    log = git("log", f"{tag}..HEAD", "--pretty=%H%x00%s")
    commits = [line.split("\x00", 1) for line in log.splitlines() if "\x00" in line]
    subjects = [(sha[:7], subject) for sha, subject in commits if not subject.startswith(RELEASE_COMMIT_PREFIX)]
    if not subjects:
        print(f"Nothing release-worthy since {tag}; skipping.")
        emit("released", "false")
        return 0
    texts = [subject for _, subject in subjects]
    breaking = any(re.match(r"^[A-Za-z]+(\([^)]*\))?!:", s) or "BREAKING CHANGE" in s for s in texts)
    feat = any(re.match(r"^feat(\([^)]*\))?:", s) for s in texts)
    kind = "major" if breaking else "minor" if feat else "patch"
    version = tag.lstrip("v")
    major, minor, patch = (int(part) for part in version.split("."))
    if kind == "major":
        new_version = f"{major + 1}.0.0"
    elif kind == "minor":
        new_version = f"{major}.{minor + 1}.0"
    else:
        new_version = f"{major}.{minor}.{patch + 1}"

    groups = [("Breaking changes", "שינויים שוברים"), ("Features", "תכונות חדשות"), ("Fixes", "תיקונים"), ("Other changes", "שינויים נוספים")]
    buckets: dict[str, list[str]] = {en: [] for en, _ in groups}
    for sha, subject in subjects:
        line = f"- {subject} ({sha})"
        if re.match(r"^[A-Za-z]+(\([^)]*\))?!:", subject) or "BREAKING CHANGE" in subject:
            buckets["Breaking changes"].append(line)
        elif re.match(r"^feat(\([^)]*\))?:", subject):
            buckets["Features"].append(line)
        elif re.match(r"^fix(\([^)]*\))?:", subject):
            buckets["Fixes"].append(line)
        else:
            buckets["Other changes"].append(line)

    today = date.today().isoformat()
    section_lines = [f"## [{new_version}] - {today}", ""]
    notes_en = [f"Changes in v{new_version}:", ""]
    notes_he = [f"שינויים בגרסה v{new_version} (כותרות הקומיטים נשארות באנגלית):", ""]
    for en, he in groups:
        if not buckets[en]:
            continue
        section_lines += [f"### {en}", ""] + buckets[en] + [""]
        notes_en += [f"### {en}", ""] + buckets[en] + [""]
        notes_he += [f"### {he}", ""] + buckets[en] + [""]
    section = "\n".join(section_lines).rstrip() + "\n"
    notes = "\n".join(notes_en).rstrip() + "\n\n---\n\n" + "\n".join(notes_he).rstrip() + "\n"

    changelog = CHANGELOG.read_text(encoding="utf-8")
    anchor = "## [Unreleased]"
    if anchor not in changelog:
        print("CHANGELOG.md missing ## [Unreleased] anchor", file=sys.stderr)
        return 1
    changelog = changelog.replace(anchor, anchor + "\n\n" + section.rstrip(), 1)
    CHANGELOG.write_text(changelog, encoding="utf-8")

    gradle = GRADLE.read_text(encoding="utf-8")
    name_match = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)
    code_match = re.search(r"versionCode\s*=\s*(\d+)", gradle)
    if not name_match or not code_match:
        print("app/build.gradle.kts missing versionName/versionCode", file=sys.stderr)
        return 1
    gradle = gradle.replace(name_match.group(0), f'versionName = "{new_version}"', 1)
    gradle = gradle.replace(code_match.group(0), f"versionCode = {int(code_match.group(1)) + 1}", 1)
    GRADLE.write_text(gradle, encoding="utf-8")

    notes_path = Path(os.environ.get("RELEASE_NOTES_PATH", ROOT / "release_notes.md"))
    notes_path.write_text(notes, encoding="utf-8")
    emit("released", "true")
    emit("new_version", new_version)
    emit("notes_path", str(notes_path))
    print(f"Prepared release v{new_version} ({kind}) from {len(subjects)} commits since {tag}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
