package io.github.reihlinstinct.reporter

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.reihlinstinct.reporter.core.CityTopicCache
import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.MunicipalSubject
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.ui.ReviewScreen
import io.github.reihlinstinct.reporter.ui.theme.AppTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Issues #105/#109/#158: the category hint is only a hint, never a decision. Synthetic data only. */
class ReviewSuggestionHintTest {
    /** The catalog value is wrapped in bidi isolates, so match the fixed prefix and the topic name apart. */
    private val HINT = "הצעה:"
    @get:Rule val compose = createComposeRule()
    @Before fun language() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        io.github.reihlinstinct.reporter.i18n.UiPrefsLanguageStore.of(context).set(io.github.reihlinstinct.reporter.i18n.AppCatalog.defaultCode(context))
    }

    private fun draft() = ReviewDraft(
        Evidence("hint-fixture", "b".repeat(64), "2026-01-01T12:00:00+02:00", 31.77, 35.21),
        "1234567", "רחוב בדיקה 1", "", "תיאור בדיקה בלבד", emptySet())

    private val snapshot = CityTopicCache.Snapshot(
        listOf(MunicipalSubject("1145", "חניה על מדרכה", listOf("חניה על מדרכה"))), Instant.now(), "synthetic")

    private fun show(loading: Boolean = false, score: Float? = null, selected: MutableList<String> = mutableListOf()) {
        compose.setContent {
            AppTheme {
                ReviewScreen(draft(), ImageBitmap(8, 8), onSave = {}, onBack = {}, topicSnapshot = snapshot,
                    topicsAvailable = true, topicsLoading = loading, topicSelected = { selected.add(it) },
                    sidewalkScorer = score?.let { value -> { _: android.graphics.Bitmap -> value } })
            }
        }
    }

    @Test fun hintShowsWithNoCategoryAndSelectsOnlyWhenTapped() {
        val selected = mutableListOf<String>()
        show(selected = selected)
        compose.onNodeWithText(HINT, substring = true).assertExists()
        compose.onNodeWithText("שנה קטגוריה").assertIsEnabled()
        compose.runOnIdle { assertEquals(emptyList<String>(), selected) }
        compose.onNodeWithText(HINT, substring = true).performClick()
        compose.runOnIdle { assertEquals(listOf("1145"), selected) }
    }

    @Test fun privateSidewalkSuggestionHidesTheStubHint() {
        show(score = 1f)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTagCount("sidewalk_suggestion") == 1
        }
        compose.onNodeWithTag("sidewalk_suggestion").assertExists()
        assertEquals(0, compose.onAllNodesWithTextCount(HINT))
    }

    @Test fun changeCategoryIsDisabledWhileTopicsLoad() {
        show(loading = true)
        compose.onNodeWithText("שנה קטגוריה").assertIsNotEnabled()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
}
