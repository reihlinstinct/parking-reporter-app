# Proposed encrypted intake

Design only. The app may send an approved encrypted package to a public intake. No production enrollment, municipal transport, retention policy or live data upload is implemented by this preview.

The only application transport address intended for the public client is the public encrypted intake repository. Municipal destinations and private storage identifiers belong in protected server configuration, never this source tree or APK.

Require independent device signatures, enrollment/revocation, exact digest-bound approval, strict size/schema limits, bounded quotas, one-use IDs and durable pre-write checkpoints. Encryption is not proof of identity or consent. An uncertain write blocks retry. A transport acknowledgment is not a municipal receipt.

See [protocol](ENCRYPTED_INTAKE_PROTOCOL.md).
