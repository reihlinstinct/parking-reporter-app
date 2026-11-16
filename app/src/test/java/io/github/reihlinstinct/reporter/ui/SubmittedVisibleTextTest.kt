package io.github.reihlinstinct.reporter.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmittedVisibleTextTest {
    private val strings = io.github.reihlinstinct.reporter.i18n.Strings(
        io.github.reihlinstinct.reporter.i18n.I18nTestFiles.realCatalog(),
        io.github.reihlinstinct.reporter.i18n.I18nTestFiles.defaultCode())
    private fun submitted(number: String?) = io.github.reihlinstinct.reporter.municipal.SubmissionCoordinator.Outcome.Submitted(
        number, "id-synthetic-1", "web-synthetic-2")

    @Test fun releaseShowsTheServerCaseNumberVerbatimAndNothingElse() {
        val text = submittedReceiptText(strings, submitted("2611-0000001"), debug = false)
        assertTrue(text.contains("2611-0000001"))
        assertTrue(text.contains("מספר קריאה"))
        assertFalse(text.contains("id-synthetic-1"))
        assertFalse(text.contains("web-synthetic-2"))
    }

    @Test fun nullNumberShowsHonestLineAndNeverInventsOne() {
        val release = submittedReceiptText(strings, submitted(null), debug = false)
        assertEquals("נשלח לעירייה. המספר טרם הוקצה", release)
        assertFalse(release.contains("מספר קריאה"))
        assertFalse(release.contains("id-synthetic-1"))
        val debug = submittedReceiptText(strings, submitted(null), debug = true)
        assertTrue(debug.startsWith("נשלח לעירייה. המספר טרם הוקצה"))
        assertTrue(debug.contains("CaseIdInCRM: id-synthetic-1"))
        assertTrue(debug.contains("WebCaseIdInCRM: web-synthetic-2"))
        assertFalse(debug.contains("מספר קריאה"))
    }

    @Test fun debugAddsTheOtherReturnedReferencesVerbatim() {
        val text = submittedReceiptText(strings, submitted("2611-0000001"), debug = true)
        assertTrue(text.contains("2611-0000001"))
        assertTrue(text.contains("CaseIdInCRM: id-synthetic-1"))
        assertTrue(text.contains("WebCaseIdInCRM: web-synthetic-2"))
    }

    @Test fun shellNavigationAndDebugGateStayWired() {
        val dir = listOf("src/main/java", "app/src/main/java", "../app/src/main/java").map(::File).first { it.exists() }
        val src = File(dir, "io/github/reihlinstinct/reporter/ui/AppShell.kt").readText()
        val flat = src.replace(Regex("\\s+"), " ")
        assertTrue(Regex("\"submitted\" -> SubmittedScreen\\(submittedReceipt").containsMatchIn(flat))
        assertTrue(Regex("onDone = \\{ submittedReceipt = null; page = \"reports\" \\}").containsMatchIn(flat))
        assertTrue(Regex("submittedReceipt = submittedReceiptText\\(strings, outcome, BuildConfig\\.DEBUG\\)").containsMatchIn(flat))
        // page = "submitted" is set only inside the Submitted branch, not for AlreadySubmitted.
        val branch = Regex("if \\(outcome is SubmissionCoordinator\\.Outcome\\.Submitted\\) \\{[^}]*page = \"submitted\"").containsMatchIn(flat)
        assertTrue(branch)
        assertEquals(1, Regex("page = \"submitted\"").findAll(flat).count())
    }
}
