package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.ledger.ReportRecord
import kotlin.test.Test
import kotlin.test.assertEquals

class PendingReportsTest {
    private fun record(id: String, digest: String?) = ReportRecord(id, "sha", "path", 1L, "{}", digest)

    @Test fun sentReportIsNotListedAsDraftOrReady() {
        val records = listOf(record("a", "d"), record("b", null), record("c", "d"))
        assertEquals(listOf("b", "c"), pendingReports(records, setOf("a")).map { it.stableId })
    }

    @Test fun nothingSentKeepsEveryRecord() {
        val records = listOf(record("a", "d"), record("b", null))
        assertEquals(records, pendingReports(records, emptySet()))
    }
}
