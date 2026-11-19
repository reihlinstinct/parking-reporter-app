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
    private fun draft() = ReviewDraft(Evidence("synthetic-topic", "a".repeat(64), "2026-01-01T12:00:00Z", 31.77, 35.21), "1234567", "רחוב בדיקה 1", "sidewalk_parking", "בדיקה", emptySet())
    @Test fun municipalSearchFiltersByCodeAndAliasesAndAnnouncesResults() {
        var chosen: MunicipalSubject? = null
        compose.setContent { AppTheme { CityTopicPicker(syntheticCityTopics().topics, null, emptyList(),
            mapOf("1280" to listOf("התקנת עמודים")), {}, { chosen = it }) } }
        compose.onNodeWithTag("topic-results").assert(
            SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.SelectableGroup))
        compose.onNodeWithTag("picker-search-input").assert(
            SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription))
        compose.onNodeWithTag("picker-search-input").performTextInput("התקנת עמודים")
        compose.onNodeWithTag("picker-search-input").assertTextContains("התקנת עמודים")
        compose.waitUntil(5_000) { compose.onNodeWithText("\u20661\u2069 תוצאות").isDisplayed() }
        compose.onNodeWithText("\u20661\u2069 תוצאות").assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.LiveRegion,
                androidx.compose.ui.semantics.LiveRegionMode.Polite))
        compose.onNodeWithText("עמוד מחסום ברזל נוטה ליפול / נפל", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("1280", chosen?.code) }
        compose.onNodeWithTag("picker-search-input").performTextReplacement("17841")
        compose.onNodeWithText("תמרור תנועה נפל / דהוי / שבור / נוטה ליפול", useUnmergedTree = true).assertIsDisplayed()
    }
    /** #362: the selected identity is exposed once, not once per displayed path. */
    @Test fun selectedDuplicateIdentityHasOneSelectedFocusTarget() {
        val rows = listOf(
            MunicipalSubject("5164", "אחר", listOf("גינון", "אחר")),
            MunicipalSubject("5164", "אחר", listOf("תאורה", "אחר")),
            MunicipalSubject("1280", "עמוד", listOf("רחוב", "עמוד")))
        var chosen: String? = null
        compose.setContent { AppTheme { CityTopicPicker(rows, "5164", emptyList(), emptyMap(), {}, { chosen = it.code }) } }
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        compose.onNode(isSelected()).assertHasClickAction()
            .assertContentDescriptionEquals("אחר. גינון › אחר\nתאורה › אחר")
        assertAndroidSelectedChoice("אחר. גינון › אחר\nתאורה › אחר", expectAlternative = true)
        compose.onNodeWithTag("picker-search-input").performTextInput("5164")
        compose.onAllNodes(isSelected()).assertCountEquals(1)
        assertAndroidSelectedChoice("אחר. גינון › אחר\nתאורה › אחר")
        compose.onNode(isSelected()).performClick()
        compose.runOnIdle { assertEquals("5164", chosen) }
        // This verifies Compose accessibility semantics, not spoken TalkBack order.
    }

    /** Check the Android accessibility bridge as well as Compose's internal tree.
     * This still cannot establish the spoken TalkBack order or utterance count. */
    private fun assertAndroidSelectedChoice(label: String, expectAlternative: Boolean = false) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun matches(): List<android.view.accessibility.AccessibilityNodeInfo> {
            val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                if (node.contentDescription?.toString() == label && node.isVisibleToUser) {
                    // Compose may export the description as a synthetic label child. Its
                    // checkable radio parent is the actual accessibility choice target.
                    var target = node
                    while (!target.isCheckable) {
                        val parent = target.parent ?: break
                        target = parent
                    }
                    if (target.isCheckable && result.none { it == target }) result += target
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it) }
            }
            automation.rootInActiveWindow?.let { visit(it) }
            return result
        }
        compose.waitUntil(5_000) { matches().size == 1 && matches().single().isChecked }
        val nodes = matches()
        assertEquals("One Android accessibility target per city code", 1, nodes.size)
        assertTrue("Android must expose a checkable single choice", nodes.single().isCheckable)
        assertTrue("Android must expose the current choice as checked", nodes.single().isChecked)
        assertTrue("Android must expose an enabled selected choice", nodes.single().isEnabled)
        // AndroidX intentionally marks an already-selected radio as not clickable.
        // Its unselected sibling must remain a native actionable choice.
        val parent = nodes.single().parent
        assertNotNull("Radio choice has a list parent", parent)
        fun hasActionableUnselectedRadio(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            if (node.isCheckable && !node.isChecked && node.isClickable && node.isEnabled) return true
            return (0 until node.childCount).any { index ->
                node.getChild(index)?.let { hasActionableUnselectedRadio(it) } == true
            }
        }
        // Filtering by5164 intentionally removes the alternative row.
        if (expectAlternative) {
            assertTrue("Unselected city code remains actionable", hasActionableUnselectedRadio(parent!!))
        }
        // AndroidX keeps composite selectable rows as android.view.View when
        // they have independently exposed text children. Checked/checkable and
        // one-target identity above are the state contract, not a widget class.
        assertEquals("android.view.View", nodes.single().className?.toString())
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
        compose.onNodeWithTag("picker-search-input").performTextInput("התקנת עמודים")
        compose.onNodeWithText("עמוד מחסום ברזל נוטה ליפול / נפל", useUnmergedTree = true).assertIsDisplayed()
        Thread.sleep(1800)
        capture("city-topic-search-alias.png")
        compose.onNodeWithTag("picker-search-input").performTextReplacement("17841")
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
        val query = compose.onNodeWithTag("picker-search-input")
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
        compose.onNodeWithTag("picker-search-input").performTextInput("5164")
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
        assertPickerGeometry()
        assertTextInkFits("picker-path-5164")
        compose.onNodeWithTag("topic-results").performTouchInput { swipeUp() }
        capture("picker315-real-paths-bottom.png")
        compose.onNodeWithText("סגור").assertIsDisplayed()
        compose.onNodeWithContentDescription(names + ". " + paths).performClick()
        compose.runOnIdle { assertEquals(1, choices) }
    }

    private fun assertPickerGeometry() {
        val title = compose.onNodeWithTag("picker-title").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithTag("picker-search-label", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("picker-search-input").fetchSemanticsNode().boundsInRoot
        assertTrue("Search label must not cross title", label.top >= title.bottom)
        assertTrue("Floating search label must fit within the field width", label.left >= input.left && label.right <= input.right)
        // The state-based field container includes its Above label as well as the editor.
        assertTrue("Above-field search label must fit in the field container", label.bottom <= input.bottom)
        assertTextInkFits("picker-search-label")
    }
    private fun assertTextInkFits(tag: String) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Text layout must be inspected", layouts.isNotEmpty())
        for (layout in layouts) {
            assertFalse("Text overflows its layout: $tag size=${layout.size} lines=${layout.lineCount} " +
                "height=${layout.multiParagraph.height} width=${layout.multiParagraph.width} " +
                "constraints=${layout.layoutInput.constraints} overflowW=${layout.didOverflowWidth} overflowH=${layout.didOverflowHeight}", layout.hasVisualOverflow)
            for (i in layout.layoutInput.text.indices) {
                if (layout.layoutInput.text[i].isWhitespace()) continue
                val glyph = layout.getBoundingBox(i)
                assertTrue("RTL glyph crosses horizontal edge: $tag/$i $glyph", glyph.left >= -0.5f && glyph.right <= layout.size.width + 0.5f)
            }
        }
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
