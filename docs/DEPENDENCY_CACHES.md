# Dependency-only CI caches

The standalone bridge and root Android build resolve different plugin graphs.
The bridge's Kotlin cache cannot repair an Android KSP miss. These caches keep
the two graphs separate while sharing each graph between its normal consumers.

## What is stored

Only `~/.gradle/caches/modules-2`, excluding lock files. This includes downloaded
public dependency bytes and Gradle resolution metadata, not transforms, compiled
project output, Gradle user properties, init scripts, daemon state, credentials,
AVDs, photos, or municipal receipts. Resolution metadata is needed with the JARs
and POMs; caching only the KSP JAR does not make Gradle resolve its plugin marker.
The Android writer builds the debug app and androidTest APK before its size/save
steps, resolving their runtime and code-generation dependencies, not just two KSP
files. This same build runs on PRs to validate the task graph before main seeds it.
A 768 MiB per-entry cap skips publication for oversized or symlinked trees.
Size-check or save errors are nonfatal and prevent publication. They never skip
or change a build/test result.

## Keys, producers and readers

- Android build, emulator matrix and display jobs use the same exact key, bound
  to OS, architecture, root/app Gradle inputs and wrapper. No broad restore prefix.
- The Android key also names an optional future `gradle/libs.versions.toml`; that
  file is currently absent and `hashFiles` ignores it until it exists.
- The bridge key binds its settings/build files, strict verification metadata,
  wrapper, OS and architecture. Its verification hashes and strict flags remain
  unchanged. A cache hit does not waive verification.
- Only a successful `push` build on `refs/heads/main` publishes. The Android build
  job is its graph's only writer; the standalone job is the bridge's only writer.
  PR jobs and Android/display matrix jobs restore only. No per-PR duplicate saves.
- The first entry is created after a successful post-merge main build. A PR
  cannot prime a trusted main cache. Cold misses still use the existing official
  repository order and bounded warmup; no new repository, pin, retry or bypass.
- These three workflows stop using setup-java's broad Gradle cache to avoid
  saving the same graph twice. Other workflows' cache policies are unchanged.

## Capacity and limits

On 20 November 2026 the live repository cache page showed 34 caches, 9.6 GiB
used of a configured 10 GiB limit, with automatic least-recently-used eviction.
No limit, payment method or billing setting was changed. No existing cache was
manually deleted. New bounded entries can evict older entries, including emulator
snapshots. Other workflows still contribute to the repository-wide limit.

Caches reduce exposure to transient Maven 404s. They do not promise zero red CI:
new versions, eviction, metadata misses and the first successful seed still need
working official origins. The bridge's strict verification remains separate from
the root build, which has no dependency-verification metadata.

Sources:
- https://github.com/reihlinstinct/parking-reporter-app/actions/caches
- https://docs.github.com/en/actions/reference/workflows-and-actions/dependency-caching
- https://docs.github.com/en/billing/concepts/product-billing/github-actions
