"""Synthetic offline package/chunk validation, NOT production crypto or GitHub transport."""
import base64, hashlib, io, json, os, random, unittest, struct
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from PIL import Image

CONTEXT = b'parking-intake-synthetic-v1'
CHUNK_BYTES = 32_000
MAX_PACKAGE = 1_536_000
MAX_CHUNKS = 48

def encode(value):
    return json.dumps(value, separators=(',', ':'), sort_keys=True, ensure_ascii=False).encode()

def package(photo):
    report = {'schema': 1, 'id': 'synthetic-only', 'reporter': {'first': 'synthetic', 'last': 'reporter', 'phone': '0000000', 'id': '000000000', 'email': 'synthetic@example.invalid'},
              'exact_text': 'דיווח בדיקה סינתטי בלבד', 'photo_sha256': hashlib.sha256(photo).hexdigest()}
    header = encode({'report': report, 'approval_digest': hashlib.sha256(encode(report)).hexdigest()})
    return b'PRPKG1' + struct.pack('>I', len(header)) + header + photo

def unpack(value):
    if not value.startswith(b'PRPKG1') or len(value)<10: raise ValueError('bad magic')
    size = struct.unpack('>I', value[6:10])[0]
    if size>16_000 or 10+size>=len(value): raise ValueError('bad header')
    header = json.loads(value[10:10+size]); photo=value[10+size:]
    if hashlib.sha256(photo).hexdigest()!=header['report']['photo_sha256']: raise ValueError('photo mismatch')
    if hashlib.sha256(encode(header['report'])).hexdigest()!=header['approval_digest']: raise ValueError('approval mismatch')
    return header,photo

def encrypt(public, plaintext):
    key = AESGCM.generate_key(bit_length=256); nonce = os.urandom(12)
    sealed_key = public.encrypt(key, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=CONTEXT))
    return encode({'v': 1, 'key': base64.b64encode(sealed_key).decode(), 'nonce': base64.b64encode(nonce).decode(),
                   'ciphertext': base64.b64encode(AESGCM(key).encrypt(nonce, plaintext, CONTEXT)).decode()})

def decrypt(private, blob):
    envelope = json.loads(blob)
    key = private.decrypt(base64.b64decode(envelope['key']), padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=CONTEXT))
    return AESGCM(key).decrypt(base64.b64decode(envelope['nonce']), base64.b64decode(envelope['ciphertext']), CONTEXT)

def chunks(blob):
    if len(blob) > MAX_PACKAGE: raise ValueError('oversized')
    pieces = [blob[i:i+CHUNK_BYTES] for i in range(0, len(blob), CHUNK_BYTES)]
    if not pieces or len(pieces) > MAX_CHUNKS: raise ValueError('too many')
    return [dict(index=i, count=len(pieces), sha256=hashlib.sha256(blob).hexdigest(), data=base64.b64encode(p).decode()) for i,p in enumerate(pieces)]

def assemble(parts):
    if not parts or len(parts)>MAX_CHUNKS: raise ValueError('bad count')
    count=parts[0]['count']; digest=parts[0]['sha256']
    if count != len(parts) or set(p['index'] for p in parts) != set(range(count)): raise ValueError('missing/duplicate')
    if any(p['count'] != count or p['sha256'] != digest or len(p['data']) > 43000 for p in parts): raise ValueError('mismatch')
    decoded = [base64.b64decode(p['data'], validate=True) for p in sorted(parts,key=lambda p:p['index'])]
    if any(len(p)>CHUNK_BYTES for p in decoded): raise ValueError('oversized chunk')
    blob = b''.join(decoded)
    if len(blob)>MAX_PACKAGE or hashlib.sha256(blob).hexdigest()!=digest: raise ValueError('corrupt')
    return blob

def synthetic_jpeg():
    rng = random.Random(7)
    image=Image.frombytes('RGB',(1280,960),rng.randbytes(1280*960*3))
    out=io.BytesIO();image.save(out,format='JPEG',quality=65)
    return out.getvalue()

class Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.private=rsa.generate_private_key(public_exponent=65537,key_size=3072)
        cls.photo=synthetic_jpeg();cls.plain=package(cls.photo);cls.blob=encrypt(cls.private.public_key(),cls.plain)
    def test_roundtrip_one_package_photo_and_report(self):
        self.assertEqual(self.plain,decrypt(self.private,assemble(chunks(self.blob))))
        decoded,photo=unpack(decrypt(self.private,self.blob))
        self.assertEqual(self.photo,photo)
        self.assertEqual(hashlib.sha256(encode(decoded['report'])).hexdigest(),decoded['approval_digest'])
    def test_binary_package_and_approval_tamper(self):
        with self.assertRaises(ValueError):unpack(self.plain[:-1]+b'x')
        with self.assertRaises(ValueError):unpack(b'wrong'+self.plain)
    def test_device_signature_is_independent_of_encryption(self):
        from cryptography.hazmat.primitives.asymmetric import ed25519
        signer=ed25519.Ed25519PrivateKey.generate()
        sig=signer.sign(CONTEXT+self.plain)
        signer.public_key().verify(sig,CONTEXT+self.plain)
        with self.assertRaises(Exception):signer.public_key().verify(sig,CONTEXT+self.plain+b'x')
        stranger=ed25519.Ed25519PrivateKey.generate()
        with self.assertRaises(Exception):stranger.public_key().verify(sig,CONTEXT+self.plain)
    def test_corrupted_ciphertext_rejected(self):
        j=json.loads(self.blob);c=bytearray(base64.b64decode(j['ciphertext']));c[-1]^=1;j['ciphertext']=base64.b64encode(c).decode()
        with self.assertRaises(Exception):decrypt(self.private,encode(j))
    def test_duplicate_missing_cross_package_chunk_rejected(self):
        p=chunks(self.blob)
        for bad in [p[:-1],p+[p[0]],p[1:]+[p[1]]]:
            with self.assertRaises(ValueError):assemble(bad)
        p[0]=dict(p[0],sha256='0'*64)
        with self.assertRaises(ValueError):assemble(p)
    def test_size_bound(self):
        with self.assertRaises(ValueError):chunks(b'x'*(MAX_PACKAGE+1))
        self.assertLess(max(len(encode(p)) for p in chunks(self.blob)),44000)
    def test_public_blob_does_not_contain_plaintext(self):
        self.assertNotIn(b'synthetic@example.invalid',self.blob)
        self.assertNotIn('דיווח'.encode(),self.blob)
        self.assertNotEqual(self.blob,encrypt(self.private.public_key(),self.plain))
    def test_public_key_is_not_sender_authentication(self):
        # Anyone with the public key can create a valid ciphertext: separate signing/enrollment is mandatory.
        anonymous=encrypt(self.private.public_key(),b'anonymous')
        self.assertEqual(b'anonymous',decrypt(self.private,anonymous))

if __name__ == '__main__':
    photo=synthetic_jpeg();print('synthetic jpeg bytes',len(photo),'package bytes',len(package(photo)))
    unittest.main()
