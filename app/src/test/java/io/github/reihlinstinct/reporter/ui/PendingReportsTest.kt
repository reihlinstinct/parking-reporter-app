package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.ledger.ReportRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingReportsTest {
    private fun record(id: String, digest: String?) = ReportRecord(id, "sha", "path", 1L, "{}", digest)

    @Test fun sentReportIsNotListedAsDraftOrReady() {
        val records = listOf(record("a", "d"), record("b", null), record("c", "d"))
        assertEquals(listOf("b", "c"), pendingReports(records, setOf("a")).map { it.stableId })
    }

    @Test fun largeHistoryDoesNotLeakSentRowsOrMutateLedgerOrder() {
        val records = (0 until 10_000).map { record("id-$it", if (it % 2 == 0) "ready" else null) }
        val sent = records.filterIndexed { index, _ -> index % 3 == 0 }.map { it.stableId }.toSet()
        val pending = pendingReports(records, sent)
        assertEquals(records.filter { it.stableId !in sent }, pending)
        assertEquals(10_000, records.size)
        org.junit.Assert.assertTrue(pending.none { it.stableId in sent })
        assertEquals(emptyList<ReportRecord>(), pendingReports(records, records.map { it.stableId }.toSet()))
    }

    @Test fun nothingSentKeepsEveryRecord() {
        val records = listOf(record("a", "d"), record("b", null))
        assertEquals(records, pendingReports(records, emptySet()))
    }
}
