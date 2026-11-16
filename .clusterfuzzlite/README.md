# Offline receiver fuzz target

ClusterFuzzLite/Jazzer runs on selected PRs on the standard Linux runner, with no
paid service, Android SDK, municipal calls, real profiles or photo evidence.

The target compiles the actual OfflineReceiver, EncryptedPackage and compatible
core Kotlin sources. The sole source replacement is the five-field, fuzz-only
ReporterSettings stub (the real settings class depends on Android). Decryption
is identity and signature verification accepts everything: this exercises
post-decryption parsing, not cryptographic security. Unexpected exceptions are
findings; IllegalArgumentException is the documented rejection.

Odd first byte: split raw input into manifest, chunks, completion. Even first
byte: wrap the input as the decrypted signed blob with valid transport metadata.
The corpus generator uses the synthetic golden report and asserts one valid
package reaches successful validation. Other seeds exercise malformed JSON and
the array regression (#514). Inputs are capped at 100001 bytes for this target;
full-size photo processing, Android JSON behaviour and other parsers are outside
its coverage. JVM assertions are enabled with -ea.

Two-minute PR runs are a starting budget, not proof that no bug remains. A failed
run must be diagnosed from its input, never waived as random fuzz noise. No
external corpus store or new write permission is used. The third-party actions
are pinned by commit, build image by digest and downloaded jars/compiler by
SHA-256. Upstream action-owned container tags remain managed by upstream.
