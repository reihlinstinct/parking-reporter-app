"""Bound new dependency-cache entries; a skipped save never skips build checks."""
import os
from pathlib import Path

MAX_BYTES = 768 * 1024 * 1024


def eligible(root):
    """Do not publish symlinked files or a cache above the per-entry cap."""
    total = 0
    if not root.is_dir() or root.is_symlink():
        return False
    for path in root.rglob('*'):
        if path.is_symlink():
            return False
        if path.is_file():
            total += path.stat().st_size
            if total > MAX_BYTES:
                return False
    return total > 0


if __name__ == '__main__':
    result = eligible(Path.home() / '.gradle/caches/modules-2')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        output.write('save=' + str(result).lower() + '\n')
    print('Dependency-only cache publication eligible:', result)
