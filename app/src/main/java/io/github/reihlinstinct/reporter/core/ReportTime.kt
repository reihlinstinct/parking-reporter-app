package io.github.reihlinstinct.reporter.core

import java.time.LocalDateTime
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

    /** Capture time if present; otherwise preserve this intent's time or read the clock once. */
    fun forSend(draft: ReviewDraft, clock: Clock = Clock.systemUTC()): ReviewDraft {
        val time = draft.evidence.capturedAtIso ?: draft.confirmedTime
            ?: OffsetDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Jerusalem")).toString()
        return draft.copy(confirmedTime = time, unresolved = draft.unresolved - retiredWarnings)
    }

    /** PRPKG2 accepts ISO offset timestamps or original offset-free EXIF wall times.
     * Offset-free values remain wall times, never silently assigned a timezone. */
    fun validPackageTime(value: String): Boolean = try {
        OffsetDateTime.parse(value); true
    } catch (_: java.time.format.DateTimeParseException) {
        try { LocalDateTime.parse(value); true }
        catch (_: java.time.format.DateTimeParseException) { false }
    }
}
