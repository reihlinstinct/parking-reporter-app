import io
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts'))
from check_native_alignment import check_apk, check_elf


def elf(wide=True, endian='<', align=16384, address=0, file_offset=0):
    identity = b'\x7fELF' + bytes([2 if wide else 1, 1 if endian == '<' else 2, 1]) + b'\0' * 9
    header = endian + ('HHIQQQIHHHHHH' if wide else 'HHIIIIIHHHHHH')
    size = 16 + struct.calcsize(header)
    ph = endian + ('IIQQQQQQ' if wide else 'IIIIIIII')
    row = ((1, 5, file_offset, address, 0, 0, 0, align) if wide else (1, file_offset, address, 0, 0, 0, 5, align))
    fields = (3, 183 if wide else 40, 1, 0, size, 0, 0, size, struct.calcsize(ph), 1, 0, 0, 0)
    return identity + struct.pack(header, *fields) + struct.pack(ph, *row)


class NativeAlignmentTest(unittest.TestCase):
    def test_both_elf_classes_and_byte_orders(self):
        for wide in (True, False):
            for endian in ('<', '>'):
                self.assertEqual(1, check_elf(elf(wide, endian)))

    def test_small_nonpower_or_incongruent_alignment_refused(self):
        for values in ({'align': 4096}, {'align': 24576}, {'address': 4096}, {'file_offset': 1}):
            with self.subTest(values=values), self.assertRaises(ValueError):
                check_elf(elf(**values))

    def test_truncation_bad_magic_and_program_table_refused(self):
        data = elf()
        for broken in (b'', b'not elf', data[:30], data[:-1], data[:4] + b'\x03' + data[5:]):
            with self.assertRaises(ValueError):
                check_elf(broken)

    def test_apk_reads_all_native_libraries_and_ignores_nonlibrary_assets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('lib/arm64-v8a/libone.so', elf())
                archive.writestr('lib/armeabi-v7a/libtwo.so', elf(False))
                archive.writestr('assets/not-a-library.so', b'not ELF')
            self.assertEqual(2, check_apk(path)['libraries'])
            self.assertEqual(2, check_apk(path)['load_segments'])

    def test_no_native_libraries_reports_zero_not_device_success(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'synthetic.apk'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('assets/fixture', b'synthetic')
            result = check_apk(path)
            self.assertEqual(0, result['libraries'])
            self.assertIn('device behavior unverified', result['scope'])


if __name__ == '__main__':
    unittest.main()
