"""Deterministic synthetic corpus; regenerates binaries instead of committing them."""
import json
from pathlib import Path
import struct
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
g = json.loads((root / 'docs/prpkg2-spec/golden.json').read_text())
# Preserve canonical_report's floating point spelling, rather than re-serializing it.
header = ('{"approval_digest":' + json.dumps(g['approval_digest']) + ',"report":' + g['canonical_report'] + '}').encode()
photo = bytes.fromhex(g['photo']['prefix_hex']) + bytes.fromhex(g['photo']['fill_hex']) * g['photo']['fill_length'] + bytes.fromhex(g['photo']['suffix_hex'])
plain = b'PRPKG2' + struct.pack('>i', len(header)) + header + photo
signed = struct.pack('>i', 8) + b'\x01' * 8 + plain
seeds = {'structured-golden': b'\x00' + signed,
         'array-regression': b'\x01{"a":[1]}\x00{}\x00{}',
         'nested-array': b'\x01{"a":{"b":[1]}}\x00{}\x00{}',
         'malformed': b'\x01{\x00{\x00{'}
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
for name, value in seeds.items():
    (out / name).write_bytes(value)
if len(sys.argv) > 2:
    with zipfile.ZipFile(sys.argv[2], 'w') as z:
        for name, value in seeds.items():
            z.writestr(name, value)
