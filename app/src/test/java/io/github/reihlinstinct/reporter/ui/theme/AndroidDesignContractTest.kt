package io.github.reihlinstinct.reporter.ui.theme

import io.github.reihlinstinct.reporter.i18n.I18nTestFiles
import org.junit.Assert.*
import org.junit.Test

class AndroidDesignContractTest {
    @Test fun minSdkAndStableBomAndExplicitEdgeToEdgeRemainPinned() {
        val build = I18nTestFiles.moduleFile("build.gradle.kts").readText()
        assertTrue(build.contains("minSdk = 26"))
        assertTrue(build.contains("compose-bom:2026.09.00"))
        val activity = I18nTestFiles.moduleFile("src/main/java/io/github/reihlinstinct/reporter/MainActivity.kt").readText()
        assertEquals(1, activity.split("enableEdgeToEdge()").size - 1)
    }
    @Test fun selectedNavigationLabelUsesPrimaryNotGoldOnLightSurface() {
        val shell = I18nTestFiles.moduleFile("src/main/java/io/github/reihlinstinct/reporter/ui/AppShell.kt").readText()
        assertTrue(shell.contains("selectedTextColor = MaterialTheme.colorScheme.primary"))
        assertTrue(shell.contains("selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer"))
        assertTrue(Palette.contrast(Palette.light.primary, Palette.light.surfaceContainer) >= 4.5)
    }
    @Test fun bothAdaptiveIconsAlreadyHaveMonochromeLayers() {
        for (name in listOf("ic_launcher.xml", "ic_launcher_round.xml")) {
            val icon = I18nTestFiles.moduleFile("src/main/res/mipmap-anydpi-v26/$name").readText()
            assertTrue(icon.contains("<monochrome android:drawable=\"@drawable/ic_launcher_monochrome\""))
        }
        assertTrue(I18nTestFiles.moduleFile("src/main/res/drawable/ic_launcher_monochrome.xml").isFile)
    }
}
