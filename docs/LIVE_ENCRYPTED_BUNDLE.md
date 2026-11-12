# Protected encrypted input bundle

For the current one-report live app path, the protected workflow stages only `trial/live-input.zip.enc`, whose ciphertext SHA256 is pinned in `trial/live-input.sha256`. No legacy photo fixture or private-repo token fallback is used. The cipher is OpenSSL AES256CBC with PBKDF2/200000 iterations and the existing protected TRIAL_DATA_KEY. No key or plaintext is committed.

The ZIP must contain exactly two bounded regular root files: `photo.jpg` and `addresses.json`. The original photo must match the dispatch-approved digest. The directory must match the app's AddressLookupPin and its expected JSON structure. Traversal, duplicate/extra names, symlinks, oversized files, wrong hashes and malformed data fail closed before destination writes. The ciphertext pin is checked before decryption. Key, private content and errors are withheld from logs. This digest-bound scheme uses encryption plus a reviewed SHA256 pin; CBC by itself is not an authenticated format.

The source owner prepares this bundle from the verified immutable private report commit, returns ciphertext only with its byte size and SHA256, and verifies both inner digests privately. The reviewed PR adds ciphertext and its exact pin together before any dispatch. Missing files are a blocker, never permission to fall back. Plaintext temp files are removed after preparation/staging; no plaintext evidence belongs in the public repository/artifacts.

Main-only dispatch, protected environment approval, FILE-LIVE-REPORT, exact approved text/photo hashes and cross-run duplicate reservation remain required. This input change is not approval to file or repeat a filing. The real app still creates its reviewed EXIF-free derivative. Stop on any uncertain outcome and reconcile rather than rerun.
