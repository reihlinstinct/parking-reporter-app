package io.github.reihlinstinct.reporter

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.core.*
import io.github.reihlinstinct.reporter.ui.*
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
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
        compose.waitUntil(5_000) { compose.onNodeWithText("\u20661\u2069 תוצאות").isDisplayed() }
        compose.onNodeWithText("\u20661\u2069 תוצאות").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite))
        compose.onNodeWithText("עמוד מחסום ברזל נוטה ליפול / נפל", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("1280", chosen?.code) }
        compose.onNodeWithText("חפש נושא, תיאור או מספר").performTextReplacement("17841")
        compose.onNodeWithText("תמרור תנועה נפל / דהוי / שבור / נוטה ליפול", useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun searchableMunicipalCatalogRecordingUsesMockRefreshNotLiveCity() {
        io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(context).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(context))
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
    @Test fun duplicateCodePickerShowsAllPathsForCodeAndPathSearch() {
        val rows = listOf(
            MunicipalSubject("5164", "אחר", listOf("בעלי חיים", "אחר")),
            MunicipalSubject("5164", "אחר", listOf("חניה אסורה", "אחר")),
            MunicipalSubject("5164", "גרפיטי", listOf("ניקיון", "גרפיטי")))
        var chosen: MunicipalSubject? = null
        compose.setContent { AppTheme { CityTopicPicker(rows, null, emptyList(), emptyMap(), {}, { chosen = it }) } }
        val label = "בעלי חיים › אחר\nחניה אסורה › אחר\nניקיון › גרפיטי"
        val query = compose.onNodeWithText("חפש נושא, תיאור או מספר")
        query.performTextInput("5164")
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("אחר · גרפיטי. " + label).assertIsDisplayed()
        compose.onNodeWithText("\u20661\u2069 תוצאות").assertIsDisplayed()
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "picker312-all-paths.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        query.performTextReplacement("חניה")
        compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("5164", chosen?.code) }
    }
    @Test fun futureCatalogTimestampShowsUnknownDateNotEpoch() {
        io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(context).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(context))
        val file = File(context.cacheDir, "future-date-synthetic/catalog.json")
        val now = java.time.Instant.parse("2026-11-11T12:00:00Z")
        val cache = CityTopicCache(file, { now })
        cache.replace(syntheticCityTopics().topics)
        val json = org.json.JSONObject(file.readText()).put("checked_at", now.plusSeconds(86400).toString())
        file.writeText(json.toString())
        val snapshot = cache.read()!!
        assertNull(snapshot.knownCheckDate())
        assertTrue(snapshot.refreshDue(now))
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, topicSnapshot = snapshot) } }
        compose.onNodeWithText("מועד הבדיקה האחרון אינו ידוע").assertIsDisplayed()
        compose.onNodeWithText("1970-01-01", substring = true).assertDoesNotExist()
        compose.onNodeWithText("90 ימים", substring = true).assertDoesNotExist()
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.filesDir, "catalog-unknown-date-synthetic.png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        file.delete()
    }

    @Test fun real91DayOldCatalogStillShowsStaleWarning() {
        io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(context).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(context))
        val snapshot = syntheticCityTopics().copy(checkedAt = java.time.Instant.now().minusSeconds(91L * 86400), checkDateKnown = true)
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}, topicSnapshot = snapshot) } }
        compose.onNodeWithText("מועד הבדיקה האחרון אינו ידוע").assertDoesNotExist()
        compose.onNodeWithText("90 ימים", substring = true).assertIsDisplayed()
    }

    @Test fun realDuplicate5164PathsRemainReadableAndOneSelectableIdentity() {
        io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(context).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(context))
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val json = org.json.JSONObject(testContext.assets.open("city-subjects-public-20261111.json").bufferedReader().use { it.readText() })
        val array = json.getJSONArray("subject_rows")
        val rows = (0 until array.length()).map { i ->
            val row = array.getJSONObject(i); val path = row.getJSONArray("path")
            MunicipalSubject(row.getString("code"), row.getString("name"), (0 until path.length()).map { path.getString(it) })
        }
        assertTrue(rows.size > 3)
        var choices = 0
        compose.setContent { AppTheme { CityTopicPicker(rows, null, emptyList(), emptyMap(), {}, {
            assertEquals("5164", it.code); choices++
        }) } }
        compose.onNodeWithText("חפש נושא, תיאור או מספר").performTextInput("5164")
        compose.onNodeWithText("\u20661\u2069 תוצאות").assertIsDisplayed()
        val paths = rows.map { it.path.joinToString(" › ") }.distinct().joinToString("\n")
        val names = rows.map { it.name }.distinct().joinToString(" · ")
        compose.onNodeWithContentDescription(names + ". " + paths).assertExists().assertHasClickAction()
        fun capture(name: String) {
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        capture("picker315-real-paths-top.png")
        compose.onNodeWithTag("topic-results").performTouchInput { swipeUp() }
        capture("picker315-real-paths-bottom.png")
        compose.onNodeWithText("סגור").assertIsDisplayed()
        compose.onNodeWithContentDescription(names + ". " + paths).performClick()
        compose.runOnIdle { assertEquals(1, choices) }
    }

    @Test fun noCityCredentialsDisablesTopicFieldWithExplanation() {
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {}) } }
        compose.onNodeWithText("בחר נושא מהעירייה").assertIsNotEnabled()
        compose.onNodeWithText("נדרשים פרטי גישה וחיבור לעירייה לטעינה ראשונה של הרשימה").assertExists()
    }
    @Test fun cachedCityListRemainsBrowsableWithoutCurrentCredentials() {
        compose.setContent { AppTheme { ReviewScreen(draft(), null, {}, {},
            topicSnapshot = syntheticCityTopics(), topicsAvailable = false) } }
        compose.onNodeWithText("חנייה אסורה - חנייה על מדרכה").assertIsEnabled().performClick()
        compose.onNodeWithText("חפש נושא, תיאור או מספר").assertIsDisplayed()
    }
    @Test fun duplicateCodeShowsEveryMunicipalPathRatherThanMislabelingTheChoice() {
        val rows = listOf(MunicipalSubject("5164", "נושא א", listOf("גינון", "נושא א")),
            MunicipalSubject("5164", "נושא ב", listOf("תאורה", "נושא ב")))
        val snap = syntheticCityTopics().copy(topics = rows)
        compose.setContent { AppTheme { ReviewScreen(draft().copy(subject = "municipal:5164", plate = ""),
            null, {}, {}, topicSnapshot = snap, topicsAvailable = true) } }
        compose.onNodeWithText("גינון / נושא א; תאורה / נושא ב").assertIsDisplayed()
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
