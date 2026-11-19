#!/usr/bin/env python3
"""Offline ELF PT_LOAD 16KiB alignment check, not a Play/release approval."""
import argparse
import json
from pathlib import Path
import struct
import zipfile

PAGE = 16384
MAX_LIBRARY = 128 * 1024 * 1024
MAX_TOTAL = 512 * 1024 * 1024


def check_elf(data):
    if len(data) < 16 or data[:4] != b'\x7fELF' or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError('invalid ELF identity')
    endian = '<' if data[5] == 1 else '>'
    wide = data[4] == 2
    header = endian + ('HHIQQQIHHHHHH' if wide else 'HHIIIIIHHHHHH')
    if len(data) < 16 + struct.calcsize(header):
        raise ValueError('truncated ELF header')
    fields = struct.unpack_from(header, data, 16)
    offset, entry_size, count = fields[4], fields[8], fields[9]
    ph = endian + ('IIQQQQQQ' if wide else 'IIIIIIII')
    size = struct.calcsize(ph)
    if not count or count > 4096 or entry_size < size or offset < 16 + struct.calcsize(header) or offset + entry_size * count > len(data):
        raise ValueError('invalid ELF program table')
    loads = 0
    for index in range(count):
        row = struct.unpack_from(ph, data, offset + index * entry_size)
        if row[0] != 1:
            continue
        loads += 1
        file_offset, address, file_size, align = ((row[2], row[3], row[5], row[7]) if wide else (row[1], row[2], row[4], row[7]))
        if file_offset + file_size > len(data):
            raise ValueError('load segment exceeds file')
        if align < PAGE or align & (align - 1) or (address - file_offset) % align:
            raise ValueError('load segment is not 16KiB aligned')
    if not loads:
        raise ValueError('ELF has no load segment')
    return loads


def check_apk(path):
    total = libraries = segments = 0
    with zipfile.ZipFile(path) as archive:
        names = set()
        for entry in archive.infolist():
            if not entry.filename.startswith('lib/') or not entry.filename.endswith('.so'):
                continue
            if entry.filename in names:
                raise ValueError('duplicate native library entry')
            names.add(entry.filename)
            total += entry.file_size
            if entry.file_size > MAX_LIBRARY or total > MAX_TOTAL:
                raise ValueError('native library inspection limit exceeded')
            segments += check_elf(archive.read(entry))
            libraries += 1
    return dict(libraries=libraries, load_segments=segments, page_size=PAGE,
                scope='ELF only; ZIP alignment, release signing and device behavior unverified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(check_apk(args.apk)))
    except (ValueError, OSError, zipfile.BadZipFile, struct.error) as error:
        parser.exit(1, 'native alignment check failed: ' + str(error) + '\n')


if __name__ == '__main__':
    main()
