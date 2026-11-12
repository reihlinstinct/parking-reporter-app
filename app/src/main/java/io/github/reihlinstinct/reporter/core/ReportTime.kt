package io.github.reihlinstinct.reporter.core

import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId

/** Local metadata only. City CreateCase has no capture-time field. */
object ReportTime {
    val retiredWarnings = setOf("missing_time", "timezone_needs_confirmation", "time_needs_confirmation")

    /** Never replace/modify original evidence. A save does not fabricate a report time. */
    fun forSave(draft: ReviewDraft): ReviewDraft = draft.copy(
        confirmedTime = draft.evidence.capturedAtIso,
        unresolved = draft.unresolved - retiredWarnings)

    /** Capture time if present; otherwise this send intent's time, not app/draft startup. */
    fun forSend(draft: ReviewDraft, clock: Clock = Clock.systemUTC()): ReviewDraft = forSave(draft).let {
        it.copy(confirmedTime = it.evidence.capturedAtIso
            ?: OffsetDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Jerusalem")).toString())
    }
}
