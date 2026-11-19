# Persistent preview signing (#673)

The dedicated `Signed persistent preview` workflow runs only when manually dispatched from main. Its checkout is pinned to the event's exact main SHA. It never signs arbitrary PR source and has no source-ref input. The existing normal PR build stays secret-free.

The keystore and its password are encrypted GitHub repository secrets. Neither is in source, chat, APK metadata or artifacts. A private runner-temporary PKCS12 file is created only for the build and removed in an always step. This is logical file removal on a disposable runner, not a physical-erasure claim. Gradle receives the password through its environment, never a command-line argument. Do not use debug/stacktrace environment dumps around this job.

The public certificate SHA-256 is pinned in `preview-certificate.sha256`. Each published artifact includes the same certificate digest, APK digest and source SHA. Signature verification must find exactly one matching signer before artifact upload. A key mismatch stops publication; creating a replacement key is not an automatic recovery action.

Signing is opt-in with `-PpersistentPreviewSigning -PpreviewSideBySide`. Without the separate `.preview` package configuration from #675, the build refuses. This patch is intentionally not a replacement for the existing installed app or its update path. It must not be activated by putting a signing secret into a PR workflow. Main-only execution still depends on the ordinary reviewed-main merge policy; this is not a sandbox for malicious main code.

## Remaining proof

No signed build or update proof is claimed by this source patch. After #675 and this patch have landed under normal review/CI, build the separate preview and verify its pinned certificate. On a synthetic-only emulator, install that preview, seed a synthetic settings profile and report history, rebuild with the same persistent identity, replace-install without uninstalling, and verify the exact settings and history survived. Record package ID, both APK hashes, both certificate digests, source SHAs and strict test results. Never substitute a successful install for the data-preservation assertion.

A new key cannot update an APK signed by a different/lost key. The owner accepted that a tester may perform a one-time reinstall, but no uninstall or data deletion is performed by this workflow. Keep the ordinary app installed separately. Do not silently delete its settings, drafts, reports or evidence.
