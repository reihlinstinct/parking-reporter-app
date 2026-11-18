# Standalone municipal API bridge

`sh gradlew -p api-bridge --no-daemon test writeBatchBridgeClasspath`

This is a separate JVM build, not a subproject of the Android build. It does not configure the app, Android Gradle Plugin, Compose, OCR, an SDK, or an emulator. Gradle dependency caching is supported by setup-java in free public CI. No prebuilt third-party bridge binary is trusted.

An explicit source allowlist compiles the existing production municipal transport, payloads, coordinates and catalog policy, plus the two private stdio entry points. There is no duplicate client implementation. The same existing synthetic bridge, payload, coordinates and photo tests run in this build. Resources come from the existing test fixtures. Main and test sources remain separate even though the private entry points currently live under app/src/test.

The output classpath stays at app/build/batch-bridge/classpath.txt, so the private controller and its single-use claim, expiry, VPN, pinned bytes and no-retry rules do not change. writeBatchBridgeClasspath resolves the actual runtime as part of every bridge CI run. The offline workflow also starts the real main class without credentials to prove it loads and refuses before network use.

Only the supervised trial workflow uses this build in this change. The inactive general batch workflow is unchanged. No new live trial is triggered by this build or by merging it. A separate approved dispatch, protected environment approval and renewed writer fence are still required.

Issue #658 follows the first trial's pre-network failure in Android's self-project debugUnitTestRuntimeClasspath variant resolution. The standalone build has no Android runtime to resolve.

## Build integrity and offline boundary

Both bridge workflows verify the tracked Gradle wrapper jar against the same fixed SHA-256 used by scripts/prepare_wrapper.sh. They deliberately do not call prepare_wrapper.sh or its OCR model downloader: this JVM source allowlist does not load an OCR model or Android assets. The synthetic payload golden fixtures are test resources only.

Dependencies use exact versions from Maven Central. api-bridge/gradle/verification-metadata.xml records SHA-256 hashes for 55 resolved plugin, compiler, runtime and test components (104 artifacts, including metadata). Both workflows explicitly use strict dependency verification, with no trusted-artifact bypass or lenient mode. The bridge is compiled from reviewed source in the same checkout rather than downloaded as a prebuilt binary.

Checksum provenance: bootstrapped with Gradle from the local resolved graph on 2026-11-18, then the credential-bearing okhttp-jvm, okio-jvm, org.json and Kotlin standard-library jars were fetched afresh from Maven Central and compared byte-for-byte by SHA-256. This freezes the reviewed bytes; it does not prove their original authorship or replace a signature audit. A clean strict build and 33 tests passed. A negative control temporarily changed the okhttp-jvm expected SHA-256 to zeros: Gradle rejected the artifact before classpath output. The correct metadata was restored. Neither CI nor the live workflow regenerates or accepts new hashes automatically. No dependency lockfile is claimed.

Offline CI also runs a positive control proving the task-graph expression recognizes a synthetic :app:compileDebugKotlin task. The actual graph must have no match. Controller, trial utilities and locked Python requirements also trigger this offline CI.
