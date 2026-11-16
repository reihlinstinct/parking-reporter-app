package io.github.reihlinstinct.reporter.core

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class ReportTimeTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-12T05:48:00Z"), ZoneOffset.UTC)
    private fun draft(time: String?) = ReviewDraft(Evidence("time", "a".repeat(64), time, 31.77, 35.21),
        "12345678", "רחוב 12", "sidewalk_parking", "בדיקה", ReportTime.retiredWarnings)

    @Test fun exifPreservedExactlyAndOldManualTimeIgnored() {
        val original = draft("2026-11-12T05:30:41+02:00").copy(confirmedTime = "2026-01-01T12:00:00Z")
        val saved = ReportTime.forSave(original)
        assertEquals(original.evidence.capturedAtIso, saved.confirmedTime)
        assertEquals(original.evidence, saved.evidence)
        assertEquals(saved, ReportTime.forSend(saved, clock))
    }
    @Test fun absentExifUsesSendTimeNotSaveTimeAndDoesNotBlockSend() {
        val original = draft(null)
        val saved = ReportTime.forSave(original)
        assertNull(saved.confirmedTime)
        assertTrue(saved.isReady())
        val sent = ReportTime.forSend(saved, clock)
        assertEquals("2026-11-12T07:48+02:00", sent.confirmedTime)
        assertEquals(original.evidence, sent.evidence)
        assertNull(sent.evidence.capturedAtIso)
    }
    @Test fun sendAndExportReuseOneIntentTimeWithoutReadingClockAgain() {
        val first = ReportTime.forSend(ReportTime.forSave(draft(null)), clock)
        val noRead = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant(): Instant = error("second clock read")
        }
        assertEquals(first, ReportTime.forSend(first, noRead))
    }
    @Test fun packageTimestampGrammarIsExplicit() {
        listOf("2026-11-12T05:30:41", "2026-11-12T05:30:41Z", "2026-11-12T05:30:41+02:00")
            .forEach { assertTrue(ReportTime.validPackageTime(it)) }
        listOf("", "tomorrow", "2026-02-30T10:00:00", "2026-11-12", "12/11/2026 05:30", "2026-11-12T25:00:00")
            .forEach { assertFalse(ReportTime.validPackageTime(it)) }
    }
    @Test fun addressAndLocationRequirementsStillApply() {
        assertFalse(ReportTime.forSave(draft(null)).copy(unresolved = setOf("address_needs_confirmation")).isReady())
        assertFalse(ReportTime.forSave(draft(null)).copy(confirmedLatitude = null).isReady())
    }
}
