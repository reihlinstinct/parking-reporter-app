package io.github.reihlinstinct.reporter

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The synthetic E2E must not grow a real-endpoint mode or alter private lookup files. */
class StubE2eSafetyTest {
    @Test fun syntheticE2eIsStubOnlyAndInjectsItsDirectory() {
        val file = listOf(
            File("src/androidTest/java/io/github/reihlinstinct/reporter/FullFlowE2eTest.kt"),
            File("app/src/androidTest/java/io/github/reihlinstinct/reporter/FullFlowE2eTest.kt"),
        ).first { it.isFile }
        val source = file.readText()
        assertFalse(source.contains("e2eLive"))
        assertFalse(source.contains("SecureSettings"))
        assertFalse(source.contains("addresses.json"))
        assertTrue(source.contains("streetDirectoryLoader = { syntheticStreetDirectory() }"))
        assertTrue(source.contains("OkHttpMunicipalClient.forTesting(server.url"))
        assertFalse(source.contains("deleteRecursively"))
        assertTrue(source.contains("DELETE FROM reports WHERE stableId = ? AND originalSha256 = ?"))
        assertTrue(source.contains("findPhoto(hash) } == null"))
        val androidTests = file.parentFile.walkTopDown().filter { it.extension in setOf("kt", "java") }.toList()
        androidTests.forEach { testFile ->
            val text = testFile.readText()
            // The install-upgrade verifier has one local-only opt-in. It never
            // constructs a municipal client. Do not permit arbitrary live flags.
            val checkedText = if (testFile.name == "InstalledNamespaceUpgradeTest.kt") {
                val optIn = "InstrumentationRegistry.getArguments().getString(\"namespaceUpgrade\")"
                assertTrue("Upgrade test keeps its exact local opt-in", text.contains(optIn))
                text.replace(optIn, "localUpgradeOptIn")
            } else text
            assertFalse("Live arguments in ${testFile.name}", checkedText.contains("getArguments("))
            assertFalse("Live endpoint flag in ${testFile.name}", text.contains("e2eLive"))
            assertFalse("Production client in ${testFile.name}", Regex("OkHttpMunicipalClient\\s*\\(").containsMatchIn(text))
        }
    }
}
