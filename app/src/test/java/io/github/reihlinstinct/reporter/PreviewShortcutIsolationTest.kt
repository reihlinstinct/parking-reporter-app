package io.github.reihlinstinct.reporter

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class PreviewShortcutIsolationTest {
    private val shortcuts get() = File("src/main/res/xml/shortcuts.xml").readText()
    @Test fun previewShortcutsTargetOnlyTheSeparatePreviewPackage() {
        val overlay = File("src/sideBySidePreview/res/xml/shortcuts.xml").readText(Charsets.UTF_8)
        assertEquals(shortcuts.replace("android:targetPackage=\"il.sidewalks.reporter\"",
            "android:targetPackage=\"il.sidewalks.reporter.preview\""), overlay)
        assertEquals(2, Regex("android:targetPackage=\"il.sidewalks.reporter.preview\"").findAll(overlay).count())
        val gradle = File("build.gradle.kts").readText()
        assertTrue(gradle.contains("res.srcDir(\"src/sideBySidePreview/res\")"))
    }

    @Test fun previewWorkflowWarmsPluginsButDoesNotRetryBuildOrChangeVerification() {
        val workflow = File("../.github/workflows/side-by-side-preview.yml").readText()
        assertTrue(workflow.indexOf("python3 scripts/warm_gradle_plugins.py") < workflow.indexOf("assembleDebug"))
        assertEquals(1, Regex("assembleDebug").findAll(workflow).count())
        assertFalse(workflow.contains("dependency-verification off"))
        assertFalse(workflow.contains("continue-on-error"))
    }

}
