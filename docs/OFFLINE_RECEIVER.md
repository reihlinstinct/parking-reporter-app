# Offline PRPKG2 receiver reference

The offline validator reconstructs bounded ciphertext chunks, requires one completion marker bound to the issue, matches expected receiver and independently enrolled signing-key IDs, decrypts with Tink, verifies the enrolled ECDSA signature, parses PRPKG2 and checks exact schemas, approval digest, JPEG hash and review-required fields. All fixtures are synthetic.

This is a Kotlin reference in the preview app, not a deployed server. The caller supplies independently provisioned keys and expected enrollment; public manifest/device labels cannot establish enrollment. It has no HTTP, worker polling, persistence, credential access, municipal filing or retry. Passing validation establishes an authenticated package, not permission to file it.

Canonical byte equality rejects duplicate keys and noncanonical encodings after parsing. A real receiver still needs independent cross-language golden vectors, safe JPEG decoding, action grants, replay protection and transactional report journal. Original EXIF/evidence provenance and reporter identity cannot be proven merely by validating claimed hashes. A phone signer authenticates an enrolled key, not the truth of the photograph or violation.

## Malformed JSON and the send boundary

Every rejection, including malformed, schema-missing or wrong-type JSON, reaches the caller as `IllegalArgumentException`. On Android `org.json.JSONException` is a checked exception, unlike the JVM test library, so `OfflineReceiver.open` converts it to that typed error and no platform exception escapes. Malformed data is never accepted.

Send precondition: a loaded or legacy draft must pass `ReportTime.forSave` at the explicit Send boundary before `ReportTime.forSend` (as `ReviewScreen` does). An already captured send intent may be reused for packaging without a second clock read.
