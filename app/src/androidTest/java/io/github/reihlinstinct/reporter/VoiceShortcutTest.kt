package io.github.reihlinstinct.reporter

import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.ui.AppShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * #149 step 1 on a real system image: the manifest shortcut exists, carries only the new-report
 * deep link, resolves to MainActivity, and that link makes the shell request the camera.
 * This does NOT prove that Google Assistant or Gemini matches a spoken phrase. Synthetic only.
 */
class VoiceShortcutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortcutOpensTheNewReportEntryAndNothingElse() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shortcut = context.getSystemService(ShortcutManager::class.java).manifestShortcuts.single { it.id == "new_report" }
        assertEquals(context.getString(R.string.tile_new_report), shortcut.shortLabel.toString())
        val intent: Intent = shortcut.intent!!
        assertEquals(EntryIntents.DEEP_LINK_NEW_REPORT, intent.dataString)
        assertEquals("il.sidewalks.reporter.MainActivity", intent.component?.className)
        assertNotNull(context.packageManager.resolveActivity(intent, 0))
        val request = EntryIntents.parse(intent.action, intent.dataString, intent.type, null)
        assertEquals(EntryIntents.Request.NewReport, request)

        var cameraRequested = false
        val signal = mutableStateOf(EntrySignal(1, request!!))
        compose.setContent {
            MaterialTheme {
                AppShell(authenticate = { _, _, action -> action() }, requestCamera = { cameraRequested = true },
                    readSettings = { null }, saveSettings = {}, entry = signal)
            }
        }
        compose.waitUntil(15_000) { cameraRequested }
        assertTrue(cameraRequested)
        val file = File(context.filesDir, "voice-shortcut-synthetic.png")
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
