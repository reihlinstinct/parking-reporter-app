package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.ledger.DraftCodec
import io.github.reihlinstinct.reporter.ledger.ReportRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentReportDetailTest {
    private fun record(address: String = "addr 1", subject: String = "sidewalk_parking"): ReportRecord {
        val draft = ReviewDraft(Evidence("id1", "a".repeat(64), null, null, null), "1234567", address, subject, "text", emptySet())
        return ReportRecord("id1", "a".repeat(64), "p", 1L, DraftCodec.encode(draft), null)
    }

    @Test fun showsIdentifierDateCategoryAndAddressOnly() {
        // 2026-11-16T10:00:00Z is 16/11/2026 in Jerusalem.
        val d = sentReportDetail(record(), "2611-0000001", 1_794_823_200_000L) { "cat:$it" }
        assertEquals("2611-0000001", d.identifier)
        assertEquals("16/11/2026", d.sentOn)
        assertEquals("cat:sidewalk_parking", d.category)
        assertEquals("addr 1", d.address)
    }

    @Test fun unknownValuesAreNullSoTheirRowsAreLeftOut() {
        val d = sentReportDetail(record(address = ""), "2611-0000002", null) { null }
        assertNull(d.sentOn)
        assertNull(d.category)
        assertNull(d.address)
    }

    @Test fun unreadableDraftStillShowsTheIdentifier() {
        val bad = ReportRecord("id2", "h", "p", 1L, "not json", null)
        val d = sentReportDetail(bad, "2611-0000003", 0L) { "x" }
        assertEquals("2611-0000003", d.identifier)
        assertNull(d.address)
        assertNull(d.category)
        assertNull(d.sentOn)
    }
}
