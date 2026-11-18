# Standalone municipal API bridge

`sh gradlew -p api-bridge --no-daemon test writeBatchBridgeClasspath`

This is a separate JVM build, not a subproject of the Android build. It does not configure the app, Android Gradle Plugin, Compose, OCR, an SDK, or an emulator. Gradle dependency caching is supported by setup-java in free public CI. No prebuilt third-party bridge binary is trusted.

An explicit source allowlist compiles the existing production municipal transport, payloads, coordinates and catalog policy, plus the two private stdio entry points. There is no duplicate client implementation. The same existing synthetic bridge, payload, coordinates and photo tests run in this build. Resources come from the existing test fixtures. Main and test sources remain separate even though the private entry points currently live under app/src/test.

The output classpath stays at app/build/batch-bridge/classpath.txt, so the private controller and its single-use claim, expiry, VPN, pinned bytes and no-retry rules do not change. writeBatchBridgeClasspath resolves the actual runtime as part of every bridge CI run. The offline workflow also starts the real main class without credentials to prove it loads and refuses before network use.

Only the supervised trial workflow uses this build in this change. The inactive general batch workflow is unchanged. No new live trial is triggered by this build or by merging it. A separate approved dispatch, protected environment approval and renewed writer fence are still required.

Issue #658 follows the first trial's pre-network failure in Android's self-project debugUnitTestRuntimeClasspath variant resolution. The standalone build has no Android runtime to resolve.
