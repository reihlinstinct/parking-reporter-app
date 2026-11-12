package io.github.reihlinstinct.reporter.core

import io.github.reihlinstinct.reporter.ui.ReviewRotationState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class ReviewRotationStateTest {
    @After fun reset() { ReviewRotationState.clear(); ReviewRotationState.clock = { System.nanoTime() / 1_000_000 } }
    private fun draft(id: String, plate: String = "") = ReviewDraft(
        Evidence(id, "a".repeat(64), "2026-01-01T12:00:00+02:00", 31.0, 35.0),
        plate, "test 1", "sidewalk_parking", "synthetic", emptySet())
    private fun held(initial: ReviewDraft, edited: ReviewDraft) = ReviewRotationState.Held(
        initial, edited, "31.0", "35.0", "street", "7", "", "", null)

    @Test fun sameReportGetsTheHeldStateOnceAfterRotation() {
        val initial = draft("r1")
        val edited = initial.copy(plate = "1234567")
        ReviewRotationState.hold(held(initial, edited))
        assertEquals(edited, ReviewRotationState.take(initial)?.draft)
        assertNull(ReviewRotationState.take(initial))
    }
    @Test fun anotherReportOrChangedInitialNeverGetsIt() {
        val initial = draft("r1")
        ReviewRotationState.hold(held(initial, initial.copy(plate = "1234567")))
        assertNull(ReviewRotationState.take(draft("r2")))
        ReviewRotationState.hold(held(initial, initial.copy(plate = "1234567")))
        assertNull(ReviewRotationState.take(initial.copy(address = "saved elsewhere")))
        assertNull(ReviewRotationState.take(initial))
    }
    @Test fun clearEmptiesTheHolderAndOnlyOneEntryIsKept() {
        val a = draft("a"); val b = draft("b")
        ReviewRotationState.hold(held(a, a.copy(plate = "1111111")))
        ReviewRotationState.hold(held(b, b.copy(plate = "2222222")))
        assertNull(ReviewRotationState.take(a))
        ReviewRotationState.hold(held(b, b))
        ReviewRotationState.clear()
        assertNull(ReviewRotationState.take(b))
    }

    @Test fun shellParksTheOpenReportAndClearEndsIt() {
        val a = draft("a")
        ReviewRotationState.holdShell(ReviewRotationState.Shell(a, null, 3L))
        assertEquals(3L, ReviewRotationState.peekShell()?.revision)
        assertEquals(a, ReviewRotationState.peekShell()?.draft)
        ReviewRotationState.clear()
        assertNull(ReviewRotationState.peekShell())
    }
    @Test fun nothingIsHeldPastTheSixtySecondBound() {
        var now = 1_000L
        ReviewRotationState.clock = { now }
        val a = draft("a")
        ReviewRotationState.holdShell(ReviewRotationState.Shell(a, null, 0L))
        ReviewRotationState.hold(held(a, a.copy(plate = "1234567")))
        now += ReviewRotationState.MAX_AGE_MS
        assertNotNull(ReviewRotationState.peekShell())
        now += 1
        assertNull(ReviewRotationState.peekShell())
        ReviewRotationState.hold(held(a, a))
        now += ReviewRotationState.MAX_AGE_MS + 1
        assertNull(ReviewRotationState.take(a))
    }
}
