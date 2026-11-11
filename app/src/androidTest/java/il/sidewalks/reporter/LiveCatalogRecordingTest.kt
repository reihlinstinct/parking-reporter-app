package il.sidewalks.reporter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.core.*
import il.sidewalks.reporter.ui.CityTopicPicker
import il.sidewalks.reporter.ui.theme.AppTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Public catalog fetched on 2026-11-11, no credentials, no network or filings. */
class LiveCatalogRecordingTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun recordedPublicCatalogCacheAndSearch() {
        val root = JSONObject(instrumentation.context.assets.open("live-city-subjects-20261111.json").bufferedReader().use { it.readText() })
        val rawRows = root.getJSONArray("subject_rows")
        val rows = (0 until rawRows.length()).map { i ->
            val row = rawRows.getJSONObject(i); val path = row.getJSONArray("path")
            MunicipalSubject(row.getString("code"), row.getString("name"), (0 until path.length()).map { path.getString(it) })
        }
        assertEquals(86, rows.size)
        assertEquals(72, rows.map { it.code }.toSet().size)
        assertEquals(root.getString("fingerprint"), MunicipalSubjects.canonicalFingerprint(rows))
        val cache = CityTopicCache(File(context.cacheDir, "live-public-catalog/catalog.json"))
        cache.replace(rows)
        val cached = cache.read()!!.topics
        assertEquals(rows, cached)
        assertTrue(cached.count { it.code == "5164" } > 1)
        var chosen: MunicipalSubject? = null
        il.sidewalks.reporter.i18n.UiPrefsLanguageStore.of(context).set("he")
        compose.setContent { AppTheme { CityTopicPicker(cached, null, emptyList(),
            mapOf("1280" to listOf("התקנת עמודים")), {}, { chosen = it }) } }
        fun capture(name: String) {
            compose.waitForIdle(); Thread.sleep(2000)
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        capture("live-catalog-open.png")
        val query = compose.onNodeWithText("חפש נושא, תיאור או מספר")
        query.performTextInput("התקנת עמודים")
        compose.onNodeWithText("עמוד מחסום ברזל נוטה ליפול / נפל", useUnmergedTree = true).assertIsDisplayed()
        capture("live-catalog-alias.png")
        query.performTextReplacement("1145")
        compose.onNodeWithText("חנייה אסורה - חנייה על מדרכה", useUnmergedTree = true).assertIsDisplayed()
        capture("live-catalog-1145.png")
        compose.onNodeWithText("חנייה אסורה - חנייה על מדרכה", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("1145", chosen?.code) }
        query.performTextReplacement("5164")
        assertEquals(1, LocalTopicRanker().search("5164", cached, emptyList()).size)
        compose.onAllNodesWithText("אחר", useUnmergedTree = true).assertCountEquals(1)
        capture("live-catalog-duplicate.png")
    }
}
