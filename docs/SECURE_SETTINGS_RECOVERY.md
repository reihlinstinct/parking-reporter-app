# SecureSettings key recovery (#132)

`SecureSettings` keeps the reporter profile and municipal credentials under an AndroidKeyStore AES key that needs recent device-credential authentication. Removing or force-resetting the screen lock permanently invalidates that key, and the old ciphertext can never be read again (by design, no plaintext fallback).

Behavior:
- `save` runs after a fresh device-credential check and replaces all settings. If the key is permanently invalidated (`KeyPermanentlyInvalidatedException` anywhere in the cause chain, or on API 33+ an `UnrecoverableKeyException` caused by a non-transient `KeyStoreException`; a transient keystore error never deletes the key), the unusable key and its file are deleted once and the save retries under a new key.
- `read` on an invalidated key deletes the key and file once and returns no settings, so the app asks for re-entry like a first run.
- Any other failure (for example a missing or timed-out authentication) is never treated as invalidation and nothing is deleted.
- Never retried more than once. No plaintext fallback. No secrets are logged.

Not covered here: a dedicated Hebrew message that says the saved details were lost because the lock screen was removed. That is new user-visible wording and needs owner-approved copy. Until then the form simply opens empty.

Tests: `SecureSettingsRecoveryTest` (JVM) pins discard-once, retry-once and no swallowing of other failures. An instrumented invalidation test needs the lock screen removed on the emulator, which Android CI images do not allow reliably, so it stays a manual real-device check.
