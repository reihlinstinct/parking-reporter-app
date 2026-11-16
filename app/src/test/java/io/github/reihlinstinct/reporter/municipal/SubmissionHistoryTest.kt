package io.github.reihlinstinct.reporter.municipal

import org.junit.Assert.assertEquals
import org.junit.Test

class SubmissionHistoryTest {
    private fun state(status: String, number: String?, web: String?) = mapOf<String, Any?>(
        "status" to status,
        "case" to mapOf("CaseIdInCRM" to "id-1", "WebCaseIdInCRM" to web, "CaseNumberInCRM" to number))

    @Test fun caseNumberWinsWhenPresent() {
        assertEquals("2611-0000001", historyIdentifier(state("complete", "2611-0000001", "web-1")))
    }

    @Test fun webCaseIdIsUsedAsReturnedWhenThereIsNoNumber() {
        assertEquals("web-1", historyIdentifier(state("complete", null, "web-1")))
        assertEquals("web-1", historyIdentifier(state("created", "", "web-1")))
        assertEquals("web-1", historyIdentifier(state("attaching", " ", "web-1")))
    }

    @Test fun nothingIsInventedWhenThereIsNothingToShow() {
        assertEquals(null, historyIdentifier(state("complete", null, null)))
        assertEquals(null, historyIdentifier(state("prepared", "2611-0000001", "web-1")))
        assertEquals(null, historyIdentifier(state("creating", "2611-0000001", "web-1")))
        assertEquals(null, historyIdentifier(mapOf("status" to "complete")))
    }
}
