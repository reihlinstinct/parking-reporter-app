package io.github.reihlinstinct.reporter.core

import org.junit.Assert.*
import org.junit.Test

class CategorySuggesterTest {
    private fun topic(code: String) = MunicipalSubject(code, "t$code", listOf("t$code"))
    private val all = listOf("1145", "17841", "1137", "1280", "999").map(::topic)

    @Test fun stubReturnsTheCommonCategoriesAsLowConfidenceHintsInFixedOrder() {
        val out = StubCategorySuggester().suggest(SuggestionInput(all))
        assertEquals(listOf("1145", "17841", "1137"), out.map { it.code })
        assertTrue(out.all { it.confidence == StubCategorySuggester.STUB_CONFIDENCE })
        assertTrue(StubCategorySuggester.STUB_CONFIDENCE <= 0.3f)
    }

    @Test fun stubOnlySuggestsCodesThatAreInTheCatalog() {
        val out = StubCategorySuggester().suggest(SuggestionInput(listOf(topic("17841"), topic("999"))))
        assertEquals(listOf("17841"), out.map { it.code })
        assertTrue(StubCategorySuggester().suggest(SuggestionInput(emptyList())).isEmpty())
    }

    @Test fun confidenceMustBeInRange() {
        assertThrows(IllegalArgumentException::class.java) { CategorySuggestion("1145", 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { CategorySuggestion("1145", -0.1f) }
        assertNull(CategorySuggestion("1145").confidence)
    }

    @Test fun visibleDropsUnknownDuplicateAndAlreadySelectedAndOrdersByConfidence() {
        val raw = listOf(
            CategorySuggestion("1137"), CategorySuggestion("1145", 0.4f), CategorySuggestion("nope", 0.9f),
            CategorySuggestion("1145", 0.1f), CategorySuggestion("17841", 0.4f), CategorySuggestion("1280", 0.7f),
        )
        val out = CategorySuggestions.visible(raw, all, selectedCode = "1280")
        assertEquals(listOf("1145", "17841", "1137"), out.map { it.code })
        assertEquals(0.4f, out.first().confidence)
    }

    @Test fun aThrowingSuggesterYieldsNoHintsButCancellationPropagates() {
        val input = SuggestionInput(all)
        assertTrue(CategorySuggestions.safely({ error("model failed") }, input).isEmpty())
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            CategorySuggestions.safely({ throw kotlinx.coroutines.CancellationException("x") }, input)
        }
    }

    @Test fun percentRoundsAndIsNullWithoutConfidence() {
        assertEquals(20, CategorySuggestions.percent(CategorySuggestion("1145", 0.2f)))
        assertEquals(67, CategorySuggestions.percent(CategorySuggestion("1145", 0.666f)))
        assertNull(CategorySuggestions.percent(CategorySuggestion("1145")))
    }
}
