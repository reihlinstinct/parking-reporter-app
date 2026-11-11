package il.sidewalks.reporter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import il.sidewalks.reporter.core.*
import il.sidewalks.reporter.ui.*
import il.sidewalks.reporter.ui.theme.AppTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CityTopicPickerTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun draft() = ReviewDraft(Evidence("synthetic-topic", "a".repeat(64), "2026-01-01T12:00:00Z", 31.0, 35.0), "1234567", "רחוב בדיקה 1", "sidewalk_parking", "בדיקה", emptySet())
    @Test fun municipalSearchFiltersByCodeAndAliasesAndAnnouncesResults() {
        var chosen: MunicipalSubject? = null
        compose.setContent { AppTheme { CityTopicPicker(syntheticCityTopics().topics, null, emptyList(),
            mapOf("1280" to listOf("התקנת עמודים")), {}, { chosen = it }) } }
        compose.onNodeWithText("חפש נושא, תיאור או מספר").performTextInput("התקנת עמודים")
        compose.onNodeWithText("1 תוצאות").assertIsDisplayed()
        compose.onNodeWithText("עמוד מחסום ברזל נוטה ליפול / נפל", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("1280", chosen?.code) }
        compose.onNodeWithText("חפש נושא, תיאור או מספר").performTextReplacement("17841")
        compose.onNodeWithText("תמרור תנועה נפל / דהוי / שבור / נוטה ליפול", useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun searchableMunicipalCatalogRecordingUsesMockRefreshNotLiveCity() {
        il.sidewalks.reporter.i18n.UiPrefsLanguageStore.of(context).set(il.sidewalks.reporter.i18n.AppCatalog.defaultCode(context))
        val cache = CityTopicCache(File(context.cacheDir, "topic-recording/catalog.json"))
        cache.replace(syntheticCityTopics().topics)
        var selected: MunicipalSubject? = null
        compose.setContent { AppTheme { CityTopicPicker(cache.read()!!.topics, null, emptyList(),
            mapOf("1280" to listOf("התקנת עמודים")), {}, { selected = it }) } }
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        Thread.sleep(1200)
        capture("city-topic-search-open.png")
        compose.onNodeWithText("חפש נושא, תיאור או מספר").performTextInput("התקנת עמודים")
        compose.onNodeWithText("עמוד מחסום ברזל נוטה ליפול / נפל", useUnmergedTree = true).assertIsDisplayed()
        Thread.sleep(1800)
        capture("city-topic-search-alias.png")
        compose.onNodeWithText("חפש נושא, תיאור או מספר").performTextReplacement("17841")
        Thread.sleep(1800)
        capture("city-topic-search-code.png")
        compose.onNodeWithText("תמרור תנועה נפל / דהוי / שבור / נוטה ליפול", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals("17841", selected?.code) }
    }
    @Test fun noCityCredentialsDisablesTopicFieldWithExplanation() {
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}) } }
        compose.onNodeWithText("בחר נושא מהעירייה").assertIsNotEnabled()
        compose.onNodeWithText("נדרשים פרטי גישה וחיבור לעירייה לטעינה ראשונה של הרשימה").assertExists()
    }
    @Test fun removedCodeCannotBeSubmittedAndDraftRemains() {
        var submitted = false
        val snap = syntheticCityTopics().copy(topics = syntheticCityTopics().topics.filter { it.code != "1145" })
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, onSubmit = { submitted = true }, topicSnapshot = snap, topicsAvailable = true) } }
        compose.onNodeWithText("הנושא הוסר מהקטלוג העירוני - בחר נושא מחדש").assertExists()
        compose.onNodeWithText("שלח לעירייה").performScrollTo().assertIsNotEnabled()
        assertFalse(submitted)
    }
}
