package io.github.reihlinstinct.reporter

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.assistant.AssistantEntrySettingsContent
import io.github.reihlinstinct.reporter.i18n.*
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File

/** Synthetic render/callback coverage, never platform consent or a voice-assistant demo. */
class AssistantEntrySettingsTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun defaultOffToggleRevocationFailureAndUnsupportedHiddenInHebrew() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UiPrefsLanguageStore.of(context).set(AppCatalog.defaultCode(context))
        var supported by mutableStateOf(true)
        var enabled by mutableStateOf(false)
        var failed by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        compose.setContent { AppTheme { ProvideStringsIfMissing {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistantEntrySettingsContent(supported, enabled, false, failed) {
                        changes += it; enabled = it
                    }
                }
            }
        } } }
        val toggle = compose.onNodeWithTag("assistant-entry-toggle")
        toggle.assertIsOff().assertIsEnabled()
        compose.onNodeWithText("כניסה מעוזר חכם").assertIsDisplayed()
        compose.onNodeWithText("החיבור משתמש בחבילה ניסיונית", substring = true).assertIsDisplayed()
        capture("assistant-settings-default-off.png")
        toggle.performClick().assertIsOn()
        capture("assistant-settings-on.png")
        toggle.performClick().assertIsOff()
        capture("assistant-settings-revoked.png")
        compose.runOnIdle { assertEquals(listOf(true,false), changes); failed = true }
        val failure = compose.onNodeWithText("לא ניתן לעדכן את החיבור לעוזר כרגע. אפשר לנסות שוב.").performScrollTo().assertIsDisplayed()
        val bounds = failure.fetchSemanticsNode().boundsInRoot
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Complete failure message must fit after normal scroll: $bounds vs $viewport",
            bounds.top >= viewport.top && bounds.bottom <= viewport.bottom &&
                bounds.left >= viewport.left && bounds.right <= viewport.right)
        capture("assistant-settings-failure.png")
        compose.runOnIdle { supported = false; failed = false }
        toggle.assertDoesNotExist()
        compose.onNodeWithText("כניסה מעוזר חכם").assertDoesNotExist()
        compose.onNodeWithText("נדרש Android 16 ומעלה.").assertDoesNotExist()
        compose.onNodeWithText("החיבור משתמש בחבילה ניסיונית", substring = true).assertDoesNotExist()
        capture("assistant-settings-api30-hidden.png")
    }
}
