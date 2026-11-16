package io.github.reihlinstinct.reporter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #149 step 1: the voice shortcut only opens the existing new-report deep link, with resource-only names. */
class VoiceEntryShortcutTest {
    private fun file(path: String) = File("src/main/$path").readText(Charsets.UTF_8)
    private val shortcuts get() = file("res/xml/shortcuts.xml")

    @Test fun openAppFeatureTargetsTheExistingDeepLinkOnly() {
        assertTrue(shortcuts.contains("actions.intent.OPEN_APP_FEATURE"))
        val links = Regex("""android:data="([^"]+)"""").findAll(shortcuts).map { it.groupValues[1] }.toSet()
        assertEquals(setOf(EntryIntents.DEEP_LINK_NEW_REPORT), links)
        assertEquals(2, Regex("""android:targetClass="il\.sidewalks\.reporter\.MainActivity"""").findAll(shortcuts).count())
    }

    @Test fun noLiteralLabelsAndNoDataParameters() {
        for (attr in Regex("""android:shortcut\w*Label="([^"]*)"""").findAll(shortcuts).map { it.groupValues[1] }) {
            assertTrue("label is a resource: $attr", attr.startsWith("@string/"))
        }
        assertFalse(shortcuts.contains("<extra"))
        assertEquals(1, Regex("""<parameter\s""").findAll(shortcuts).count()) // only the assistant's feature name
    }

    @Test fun manifestRegistersTheShortcutsOnTheLauncherActivityOnly() {
        val manifest = file("AndroidManifest.xml")
        assertEquals(1, Regex("""android\.app\.shortcuts""").findAll(manifest).count())
        val activity = manifest.substringAfter("<activity").substringBefore("</activity>")
        assertTrue(activity.contains("""android:resource="@xml/shortcuts""""))
    }

    @Test fun featureNamesReuseTheApprovedTileLabel() {
        val names = file("res/values/shortcut_features.xml")
        assertEquals(listOf("@string/tile_new_report"), Regex("""<item>([^<]*)</item>""").findAll(names).map { it.groupValues[1] }.toList())
    }
}
