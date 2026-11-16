package io.github.reihlinstinct.reporter.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmittedVisibleTextTest {
    private val receipt = "נשלח לעירייה. מספר קריאה: synthetic-123"

    @Test fun releaseShowsOnlyTheNeutralTitle() {
        val text = submittedVisibleText(debug = false, receiptText = receipt, neutral = "הדיווח הוגש בהצלחה")
        assertEquals("הדיווח הוגש בהצלחה", text)
        assertFalse(text.contains("synthetic-123"))
    }

    @Test fun debugKeepsTheReceipt() {
        assertEquals(receipt, submittedVisibleText(debug = true, receiptText = receipt, neutral = "x"))
    }

    @Test fun shellNavigationAndDebugGateStayWired() {
        val dir = listOf("src/main/java", "app/src/main/java", "../app/src/main/java").map(::File).first { it.exists() }
        val src = File(dir, "io/github/reihlinstinct/reporter/ui/AppShell.kt").readText()
        assertTrue(src.contains("\"submitted\" -> SubmittedScreen(submittedReceipt"))
        assertTrue(src.contains("page = \"submitted\""))
        assertTrue(src.contains("if (BuildConfig.DEBUG) municipalOutcomeText(strings, outcome) else null"))
        assertTrue(src.contains("submittedVisibleText(BuildConfig.DEBUG, submissionNote"))
        assertTrue(src.contains("onDone = { submittedReceipt = null; page = \"reports\" }"))
    }
}
