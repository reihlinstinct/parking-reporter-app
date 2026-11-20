package io.github.reihlinstinct.reporter.ui

import io.github.reihlinstinct.reporter.core.Evidence
import io.github.reihlinstinct.reporter.core.ReviewDraft
import io.github.reihlinstinct.reporter.security.ReviewRecoveryCodec
import org.junit.Assert.*
import org.junit.Test

/** Mapping proof only, not Android auth, file durability or process-death proof. */
class ReviewRecoveryMappingTest {
    private fun snapshot() = ReviewRecoveryCodec.Snapshot("1122334", "regular", true,
        "edited address", "municipal:1145", "edited text", setOf("address_needs_confirmation"),
        "2026-11-19T12:00:00Z", 31.75, 35.25, "1122334", 2, "31.75", "35.25",
        "raw street", "12a", "cross street", "saved street", "10", null, true,
        true, true, true, "synthetic-tag", listOf(ReviewRecoveryCodec.Sentence("state.synthetic", "template")),
        true, "unconfirmed baseline", setOf("address_needs_confirmation"))

    @Test fun restoresRawInputAndProvenanceButNeverCheckpointEvidenceOrTransientResults() {
        val evidence = Evidence("saved-report", "a".repeat(64), null, null, null)
        val initial = ReviewDraft(evidence, "", "saved address", "", "saved text", emptySet())
        val held = ReviewRecoveryMapping.held(initial, snapshot())
        assertSame(initial, held.initial); assertSame(evidence, held.draft.evidence)
        assertEquals("edited text", held.draft.exactHebrewText)
        assertEquals("12a", held.manualHouse); assertEquals("10", held.savedManualHouse)
        assertEquals("cross street", held.manualCross); assertEquals("cross street", held.draft.pendingCross)
        assertEquals("1122334", held.plateRaw); assertEquals(2, held.plateEdits)
        assertEquals("unconfirmed baseline", held.addressBaseline)
        assertEquals(setOf("address_needs_confirmation"), held.pendingAddressWarnings)
        assertTrue(held.gpsFilled); assertTrue(held.textTyped); assertTrue(held.templateState.frozen)
        assertTrue(held.confirmExit); assertTrue(held.plates.isEmpty()); assertNull(held.manualResult)
        assertEquals(setOf("address_needs_confirmation"), held.draft.unresolved)
        assertFalse(held.draft.isReady())
    }
}
