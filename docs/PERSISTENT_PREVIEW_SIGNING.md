# Persistent preview signing (#673)

The dedicated `Signed persistent preview` workflow runs only when manually dispatched from main. Its checkout is pinned to the event's exact main SHA. It never signs arbitrary PR source and has no source-ref input. The existing normal PR build stays secret-free.

The signing job names the dedicated `persistent-preview-signing` environment. Before enabling it, an administrator must configure main-only deployment branches and required reviewers, provision the approved keystore/password in environment secrets, verify the replacement certificate OFFLINE against the reviewed pin, and only then remove both repository-secret copies. Do not use the gated signed-build workflow for this first verification. The owner explicitly authorized a replacement key on 2026-11-20 after being told that existing preview installations need a reinstall. The old source keystore was not recoverable. Do not rotate this replacement during routine migration or builds. The source declaration alone does not create those controls: GitHub can auto-create an unprotected environment. `PREVIEW_SIGNING_ENVIRONMENT_READY=main-only-reviewed-v1` is an environment variable set only after independent live inspection of those controls and the absence of repository-secret copies. It is an operational fail-close marker, not a security boundary against a repository writer.

Until that migration is verified, the readiness marker must remain absent and the signed-build workflow must remain disabled by that gate. Environment secrets may already be provisioned during preparation. The secrets were previously provisioned at repository scope; do not claim main-only secret protection until those copies are gone. This source patch does not perform the migration or change deployment settings.

After migration, the keystore and its password are encrypted GitHub environment secrets. Neither is in source, chat, APK metadata or artifacts. A private runner-temporary PKCS12 file is created only for the build and removed in an always step. This is logical file removal on a disposable runner, not a physical-erasure claim. Gradle receives the password through its environment, never a command-line argument. Do not use debug/stacktrace environment dumps around this job.

The public certificate SHA-256 is pinned in `preview-certificate.sha256`. Each published artifact includes the same certificate digest, APK digest and source SHA. Signature verification must find exactly one matching signer before artifact upload. A key mismatch stops publication; creating a replacement key is not an automatic recovery action.

Signing is opt-in with `-PpersistentPreviewSigning -PpreviewSideBySide`. Without the separate `.preview` package configuration from #675, the build refuses. This patch is intentionally not a replacement for the existing installed app or its update path. It must not be activated by putting a signing secret into a PR workflow. Main-only execution still depends on the ordinary reviewed-main merge policy; this is not a sandbox for malicious main code.

## Remaining proof

No signed build or update proof is claimed by this source patch. After #675 and this patch have landed under normal review/CI, build the separate preview and verify its pinned certificate. On a synthetic-only emulator, install that preview, seed a synthetic settings profile and report history, rebuild with the same persistent identity, replace-install without uninstalling, and verify the exact settings and history survived. Record package ID, both APK hashes, both certificate digests, source SHAs and strict test results. Never substitute a successful install for the data-preservation assertion.

A new key cannot update an APK signed by a different/lost key. Switching an existing preview installation to this signer requires uninstalling the old preview, which deletes its local settings, drafts and report history unless the tester exports/preserves them beforehand. The owner approved issuing the replacement after being told a reinstall is needed; this is not authority for this workflow to uninstall anything or delete data. Keep the ordinary app installed separately. Do not silently delete its settings, drafts, reports or evidence.

## Approved replacement identity (2026-11-20)

The replacement certificate SHA-256 is pinned in `preview-certificate.sha256`. The PKCS12 and its separate password are retained as separate encrypted vault items, in addition to the protected environment secrets. The original repository secrets remain until the replacement certificate is verified OFFLINE against the reviewed pin, independently of the gated workflow. Provisioning alone does not establish a signed build or data-preserving update. The readiness marker remains unset until an independent live inspection confirms all required controls and removal of the old repository copies.

An installation signed with the previous certificate cannot be replaced in-place by this key. Uninstalling the old preview deletes its local settings, drafts and history unless exported/preserved first. Do not claim continuity with an existing installation; the synthetic data-preservation proof above starts with two builds signed by the replacement key.


## Non-circular activation order

1. Verify the retained replacement PKCS12 OFFLINE: export its public certificate,
   compute its SHA-256 and compare it with the exact reviewed
   `docs/preview-certificate.sha256`. Prove possession by signing/verifying a
   synthetic local artifact. This step needs neither the readiness marker nor a
   workflow dispatch. Record only public digests and the verification result.
2. Confirm the new environment secrets are provisioned and secure backups retained.
   After the offline identity check succeeds, remove both OLD repository-secret
   copies. Do not delete the replacement backup material.
3. Independently inspect the live environment: main-only deployment branch,
   required reviewer, administrator bypass disabled, both environment secrets,
   and no old repository-secret copies. Only then set the readiness marker.
4. After the prerequisite PRs land normally, manually dispatch the first signed
   preview from main, verify its APK signer against the pin, then perform the
   separate synthetic update/data-preservation proof. An offline identity check
   is not proof that an APK has been built or that an existing install updates.

The previous certificate digest was
`7f4c20208074df8e86b156738ed17fbd15eac02671a66dd8eba133788164c2f3`.
It is historical identity information only, never an accepted alternate signer.
