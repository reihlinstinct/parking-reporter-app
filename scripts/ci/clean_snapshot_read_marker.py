#!/usr/bin/env python3
"""Remove only disposable emulator read-snapshot markers before cache validation."""
import argparse
import os
from pathlib import Path
import stat


def clean(root):
    root = Path(root)
    if root.is_symlink() or not root.is_dir():
        raise ValueError('AVD root must be a plain directory')
    removed = 0
    for directory, directories, files in os.walk(root, followlinks=False):
        # Never follow symlinks or special nodes. Leave them for the unchanged
        # fail-closed cache validator rather than cleaning evidence away.
        directories[:] = [name for name in directories if not (Path(directory) / name).is_symlink()]
        if 'read-snapshot.txt' not in files:
            continue
        marker = Path(directory) / 'read-snapshot.txt'
        if not stat.S_ISREG(marker.lstat().st_mode):
            raise ValueError('snapshot marker is not a regular file')
        marker.unlink()
        removed += 1
    return removed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--avd-dir', required=True, type=Path)
    args = parser.parse_args()
    print('Removed disposable emulator read markers:', clean(args.avd_dir))


if __name__ == '__main__':
    main()
