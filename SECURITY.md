# Security policy

## Supported versions

Pre-release design/scaffolding only. No released app is currently supported. Before the first release, define supported versions and a maintenance policy.

## Reporting a vulnerability

Do not open an issue containing credentials, real plates, evidence photos, reporter identities, tokens or exploit details that expose private data. Use the repository's private vulnerability-reporting control if enabled. Otherwise contact the repository owner through an already-established private channel with a sanitized summary and ask for a secure transfer method. No response-time promise is made yet.

## Security requirements

- On-device recognition; no cloud photo analysis. Optional bundled Google ML Kit sends API usage/performance metrics to Google, not input images or recognition output.
- Explicit per-report approval bound to the exact reviewed payload; edits invalidate it.
- Deduplication by original-photo hash and stable report ID, single-flight writes, durable checkpoints and no automatic retry of municipal POSTs.
- Uncertain outcomes block resubmission until reconciliation.
- Credentials and reporter details stay out of source, fixtures, logs, crash reports and Android backup. Use Android Keystore-backed encryption; validate the selected libraries and device behavior before implementation.
- Never package shared API credentials or signing keys inside an APK or commit them. Credential provisioning blocks Play release until approved and tested.
- Treat network responses and recognition output as untrusted data. Validate inputs, refuse redirects on sensitive writes, use HTTPS and redact errors.
- Use synthetic fixtures. Sensitive review/settings screens must block screenshots and recent-app thumbnails.
- Social export requires plate masking, full-image review and separate approval. No implicit municipal-to-social consent.

## Incident handling

Stop the affected path, preserve only redacted evidence, notify the owner privately and rotate exposed credentials through their authorized service. Do not paste secrets into an issue or PR. Removal in a new commit does not remove old Git history; assess history cleanup and rotation separately.
