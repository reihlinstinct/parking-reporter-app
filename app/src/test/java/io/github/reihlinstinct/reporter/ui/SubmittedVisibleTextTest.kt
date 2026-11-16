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
        val flat = src.replace(Regex("\\s+"), " ")
        assertTrue(Regex("\"submitted\" -> SubmittedScreen\\(submittedReceipt").containsMatchIn(flat))
        assertTrue(Regex("onDone = \\{ submittedReceipt = null; page = \"reports\" \\}").containsMatchIn(flat))
        assertTrue(Regex("submittedReceipt = if \\(BuildConfig\\.DEBUG\\) municipalOutcomeText\\(strings, outcome\\) else null").containsMatchIn(flat))
        assertTrue(Regex("submittedVisibleText\\(BuildConfig\\.DEBUG, submissionNote").containsMatchIn(flat))
        // page = "submitted" is set only inside the Submitted branch, not for AlreadySubmitted.
        val branch = Regex("if \\(outcome is SubmissionCoordinator\\.Outcome\\.Submitted\\) \\{[^}]*page = \"submitted\"").containsMatchIn(flat)
        assertTrue(branch)
        assertEquals(1, Regex("page = \"submitted\"").findAll(flat).count())
    }
}
