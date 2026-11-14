package io.github.reihlinstinct.reporter.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SidewalkSuggestionTest {
    @Test
    fun v4PinIsAFullSha256AndTheHeadIsAProbability() {
        assertTrue(SidewalkSuggestion.PINNED_SHA256.matches(Regex("[a-f0-9]{64}")))
        assertFalse(SidewalkSuggestion.OUTPUT_IS_LOGIT)
        assertEquals(0.3f, SidewalkSuggestion.probability(0.3f)!!, 1e-6f)
    }

    @Test
    fun suggestsOnlyAtOrAboveTheThresholdAndNotWhenAlreadySidewalk() {
        assertTrue(SidewalkSuggestion.shouldSuggest(0.10f, "17841"))
        assertTrue(SidewalkSuggestion.shouldSuggest(0.9f, null))
        assertFalse(SidewalkSuggestion.shouldSuggest(0.09f, null))
        assertFalse(SidewalkSuggestion.shouldSuggest(0.9f, SidewalkSuggestion.SIDEWALK_SUBJECT_CODE))
        assertFalse(SidewalkSuggestion.shouldSuggest(null, null))
        assertFalse(SidewalkSuggestion.shouldSuggest(Float.NaN, null))
    }

    @Test
    fun probabilityIsKeptOrSquashedAsTheModelDeclares() {
        assertEquals(0.3f, SidewalkSuggestion.probability(0.3f, isLogit = false)!!, 1e-6f)
        assertEquals(0f, SidewalkSuggestion.probability(0f, isLogit = false)!!, 1e-6f)
        assertEquals(0.574f, SidewalkSuggestion.probability(0.3f, isLogit = true)!!, 1e-3f)
        assertEquals(0.5f, SidewalkSuggestion.probability(0f, isLogit = true)!!, 1e-6f)
        assertTrue(SidewalkSuggestion.probability(4f, isLogit = true)!! > 0.9f)
        assertTrue(SidewalkSuggestion.probability(-4f, isLogit = true)!! < 0.1f)
        assertNull(SidewalkSuggestion.probability(Float.NaN, isLogit = true))
        assertNull(SidewalkSuggestion.probability(Float.NaN, isLogit = false))
    }

    @Test
    fun blankOrWrongPinNeverMatches() {
        val hash = "a".repeat(64)
        assertFalse(SidewalkSuggestion.pinMatches(hash, ""))
        assertFalse(SidewalkSuggestion.pinMatches(hash, "b".repeat(64)))
        assertFalse(SidewalkSuggestion.pinMatches(hash, "not hex"))
        assertTrue(SidewalkSuggestion.pinMatches(hash, hash))
    }

    @Test
    fun normalizeLaysOutChannelsAndAppliesMeanAndStd() {
        val size = SidewalkSuggestion.INPUT_SIZE
        val red = IntArray(size * size) { 0xFFFF0000.toInt() }
        val first = SidewalkSuggestion.normalize(red, true)
        val last = SidewalkSuggestion.normalize(red, false)
        val expectedR = (1f - 0.485f) / 0.229f
        val expectedG = (0f - 0.456f) / 0.224f
        assertEquals(expectedR, first[0], 1e-5f)
        assertEquals(expectedG, first[size * size], 1e-5f)
        assertEquals(expectedR, last[0], 1e-5f)
        assertEquals(expectedG, last[1], 1e-5f)
        assertEquals(size * size * 3, first.size)
    }
}
