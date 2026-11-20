# Persistent preview signing (#673)

The dedicated `Signed persistent preview` workflow runs only when manually dispatched from main. Its checkout is pinned to the event's exact main SHA. It never signs arbitrary PR source and has no source-ref input. The existing normal PR build stays secret-free.

The signing job names the dedicated `persistent-preview-signing` environment. Before enabling it, an administrator must configure main-only deployment branches and required reviewers, provision the approved keystore/password in environment secrets, verify their presence and signing certificate, and only then remove both repository-secret copies. The owner explicitly authorized a replacement key on 2026-11-20 after being told that existing preview installations need a reinstall. The old source keystore was not recoverable. Do not rotate this replacement during routine migration or builds. The source declaration alone does not create those controls: GitHub can auto-create an unprotected environment. `PREVIEW_SIGNING_ENVIRONMENT_READY=main-only-reviewed-v1` is an environment variable set only after independent live inspection of those controls and the absence of repository-secret copies. It is an operational fail-close marker, not a security boundary against a repository writer.

Until that migration is verified, the workflow must remain unprovisioned and no signed build is authorized. The secrets were previously provisioned at repository scope; do not claim main-only secret protection until those copies are gone. This source patch does not perform the migration or change deployment settings.

After migration, the keystore and its password are encrypted GitHub environment secrets. Neither is in source, chat, APK metadata or artifacts. A private runner-temporary PKCS12 file is created only for the build and removed in an always step. This is logical file removal on a disposable runner, not a physical-erasure claim. Gradle receives the password through its environment, never a command-line argument. Do not use debug/stacktrace environment dumps around this job.

The public certificate SHA-256 is pinned in `preview-certificate.sha256`. Each published artifact includes the same certificate digest, APK digest and source SHA. Signature verification must find exactly one matching signer before artifact upload. A key mismatch stops publication; creating a replacement key is not an automatic recovery action.

Signing is opt-in with `-PpersistentPreviewSigning -PpreviewSideBySide`. Without the separate `.preview` package configuration from #675, the build refuses. This patch is intentionally not a replacement for the existing installed app or its update path. It must not be activated by putting a signing secret into a PR workflow. Main-only execution still depends on the ordinary reviewed-main merge policy; this is not a sandbox for malicious main code.

## Remaining proof

No signed build or update proof is claimed by this source patch. After #675 and this patch have landed under normal review/CI, build the separate preview and verify its pinned certificate. On a synthetic-only emulator, install that preview, seed a synthetic settings profile and report history, rebuild with the same persistent identity, replace-install without uninstalling, and verify the exact settings and history survived. Record package ID, both APK hashes, both certificate digests, source SHAs and strict test results. Never substitute a successful install for the data-preservation assertion.

A new key cannot update an APK signed by a different/lost key. The owner accepted that a tester may perform a one-time reinstall, but no uninstall or data deletion is performed by this workflow. Keep the ordinary app installed separately. Do not silently delete its settings, drafts, reports or evidence.

## Approved replacement identity (2026-11-20)

The replacement certificate SHA-256 is pinned in `preview-certificate.sha256`. The PKCS12 and its separate password are retained as separate encrypted vault items, in addition to the protected environment secrets. The original repository secrets remain until the replacement signing verification succeeds. Provisioning alone does not establish a signed build or data-preserving update. The readiness marker remains unset until an independent live inspection confirms all required controls and removal of the old repository copies.

An installation signed with the previous certificate cannot be replaced in-place by this key. Reinstalling can remove its local settings/history. Do not claim continuity with an existing installation; the synthetic data-preservation proof below starts with two builds signed by the replacement key.
