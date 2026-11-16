package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.ledger.DraftCodec
import io.github.reihlinstinct.reporter.ledger.ReportRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the details screen of a sent report shows (owner-approved 2026-11-16): the identifier as the city returned it,
 * the date the submission record was last written (the closest local record of when it was sent), the category and the
 * address from the saved draft. No photo, no plate, no description text. A value that is not known is null and its row is not shown.
 */
internal class SentReportDetail(val identifier: String, val sentOn: String?, val category: String?, val address: String?)

internal fun sentReportDetail(record: ReportRecord, identifier: String, stateWrittenAtMs: Long?, categoryLabel: (String) -> String?): SentReportDetail {
    val draft = try { DraftCodec.decode(record.draftJson) } catch (_: Exception) { null }
    val date = stateWrittenAtMs?.takeIf { it > 0 }?.let {
        Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Jerusalem")).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT))
    }
    return SentReportDetail(identifier, date, draft?.subject?.let(categoryLabel)?.takeIf { it.isNotBlank() }, draft?.address?.takeIf { it.isNotBlank() })
}
