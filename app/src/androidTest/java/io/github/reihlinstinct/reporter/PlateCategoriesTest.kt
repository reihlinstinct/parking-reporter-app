package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.core.*
import io.github.reihlinstinct.reporter.ui.*
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

class PlateCategoriesTest {
    @get:Rule val compose = createComposeRule()
    @Before fun language() { io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(InstrumentationRegistry.getInstrumentation().targetContext).set("he") }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun failedFreshEntryDoesNotExposeStaleRowsAndRetryMakesThemEditable() {
        var reads = 0; var enabled = ""
        compose.setContent { AppTheme { PlateCategoriesScreen(true, emptySet(), refresh = {
            reads++
            if (reads == 1) error("synthetic fetch failure")
            CityTopicCache.Snapshot(listOf(MunicipalSubject("5164", "בדיקה", listOf("בדיקה"))), Instant.now(), "synthetic")
        }, enable = { code, _ -> enabled = code }, back = {}) } }
        compose.waitUntil { reads == 1 }
        compose.onNodeWithText("נסה לרענן שוב").performClick()
        compose.waitUntil { reads == 2 }
        compose.onNodeWithText("בדיקה (5164)").assertExists()
        compose.onAllNodes(isToggleable()).onFirst().performClick()
        compose.runOnIdle { assertEquals("5164", enabled) }
        screenshot("plate-categories-fresh-retry.png")
    }
    @Test fun offlineScreenNeverFetchesOrEnablesList() {
        compose.setContent { AppTheme { PlateCategoriesScreen(false, setOf("5164"), refresh = { error("offline fetch") }, enable = { _, _ -> error("offline edit") }, back = {}) } }
        compose.onNodeWithText("נדרש חיבור לאינטרנט").assertExists()
        compose.onAllNodes(isToggleable()).assertCountEquals(0)
        screenshot("plate-categories-offline-no-list.png")
    }
}
