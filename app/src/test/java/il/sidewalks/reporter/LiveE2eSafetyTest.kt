package il.sidewalks.reporter

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live submission E2E (androidTest live/ package + its manual workflow)
 * must stay opt-in, secret-free in public output and encrypted in storage.
 * Complements StubE2eSafetyTest, which keeps the synthetic E2E stub-only;
 * both guards run as plain JVM unit tests in every build.
 */
class LiveE2eSafetyTest {
    private fun repoFile(vararg candidates: String): File =
        candidates.map(::File).first { it.isFile }

    private val liveTest by lazy {
        repoFile(
            "src/androidTest/java/il/sidewalks/reporter/live/LiveEmulatorSubmissionTest.kt",
            "app/src/androidTest/java/il/sidewalks/reporter/live/LiveEmulatorSubmissionTest.kt")
    }
    private val workflow by lazy {
        repoFile(
            "../.github/workflows/live-emulator-e2e.yml",
            ".github/workflows/live-emulator-e2e.yml")
    }

    @Test fun liveE2eRunsOnlyWithStagedParams() {
        val source = liveTest.readText()
        assertTrue(source.contains("live-params.json"))
        assertTrue(source.contains("if (!paramsFile.isFile) return"))
        // Digest binding to the owner-approved text, with a constant failure
        // message (assertEquals would print both digests on mismatch).
        assertTrue(source.contains("approvedSha256"))
        assertTrue(source.contains("SHA-256"))
        assertFalse(source.contains("assertEquals("))
        // A photo that already produced a report is never re-filed.
        assertTrue(source.contains("refusing to file again"))
    }

    @Test fun liveE2eNeverLeaksSecrets() {
        val source = liveTest.readText()
        assertFalse(source.contains("android.util.Log"))
        assertFalse(source.contains("println"))
        assertFalse(source.contains("getArguments("))
        // Settings reach the UI in memory; nothing persists them on device.
        assertFalse(source.contains("SecureSettings"))
        // The scrubbed marker log is the only text output of the test.
        assertTrue(source.contains("live-e2e-log.txt"))
        // The staged params carry secrets and must always be wiped.
        assertTrue(source.contains("paramsFile.delete()"))
    }

    @Test fun liveProviderServesOnlyAStagedPhoto() {
        val provider = repoFile(
            "src/androidTest/java/il/sidewalks/reporter/live/LiveShareProvider.java",
            "app/src/androidTest/java/il/sidewalks/reporter/live/LiveShareProvider.java")
            .readText()
        assertTrue(provider.contains("live-photo.jpg"))
        assertTrue(provider.contains("FileNotFoundException"))
        // No write surface: the provider only ever reads the staged file.
        assertFalse(provider.contains("MODE_WRITE"))
    }

    @Test fun liveWorkflowIsManualGatedAndEncryptsEvidence() {
        val yaml = workflow.readText()
        assertTrue(yaml.contains("workflow_dispatch:"))
        assertFalse(Regex("(?m)^  (push|pull_request|schedule):").containsMatchIn(yaml))
        assertTrue(yaml.contains("environment: m0-trial-submit"))
        assertTrue(yaml.contains("FILE-LIVE-REPORT"))
        // The staged text digest is validated on the runner before staging.
        assertTrue(yaml.contains("approved text digest mismatch"))
        // Recording, screenshots and raw instrumentation output are uploaded
        // only inside the openssl-encrypted bundle.
        assertTrue(yaml.contains("openssl enc"))
        val uploadPaths = Regex("(?m)^\\s+path:\\s*(\\S.*)$")
            .findAll(yaml).map { it.groupValues[1].trim() }.toList()
        assertTrue("expected an encrypted upload", uploadPaths.any { it.endsWith(".enc") })
        uploadPaths.forEach { path ->
            assertFalse("unencrypted private evidence upload: $path",
                path.endsWith(".mp4") || path.endsWith(".png") ||
                    path.endsWith("instrumentation.txt") || path.endsWith("params.json"))
        }
        // The staged params and photo are wiped from the device afterwards.
        assertTrue(yaml.contains("rm -f files/live-params.json"))
        assertTrue(yaml.contains("rm -f files/live-photo.jpg"))
    }

    @Test fun regularCiNeverStagesLiveData() {
        val androidCi = repoFile(
            "../.github/workflows/android.yml",
            ".github/workflows/android.yml").readText()
        assertFalse(androidCi.contains("live-params.json"))
        assertFalse(androidCi.contains("live-photo.jpg"))
        assertFalse(androidCi.contains("liveshare"))
    }
}
