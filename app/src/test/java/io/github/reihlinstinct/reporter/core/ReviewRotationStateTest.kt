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
    @Test fun phoneCoordinatesFlagSurvivesRotation() {
        val initial = draft("r1")
        ReviewRotationState.hold(ReviewRotationState.Held(initial, initial, "31.7", "35.2", "", "", "", "", null, gpsFilled = true))
        assertTrue(ReviewRotationState.take(initial)!!.gpsFilled)
        ReviewRotationState.hold(held(initial, initial))
        assertFalse(ReviewRotationState.take(initial)!!.gpsFilled)
    }
    @Test fun typedTextFlagSurvivesRotation() {
        val initial = draft("r1")
        ReviewRotationState.hold(ReviewRotationState.Held(initial, initial, "31.0", "35.0", "", "", "", "", null, textTyped = true))
        assertTrue(ReviewRotationState.take(initial)!!.textTyped)
        ReviewRotationState.hold(held(initial, initial))
        assertFalse(ReviewRotationState.take(initial)!!.textTyped)
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
        val taken = ReviewRotationState.takeShell()
        assertEquals(3L, taken?.revision)
        assertEquals(a, taken?.draft)
        assertNull("a second recreation must not reopen the same revision", ReviewRotationState.takeShell())
    }
    @Test fun nothingIsHeldPastTheSixtySecondBound() {
        var now = 1_000L
        ReviewRotationState.clock = { now }
        val a = draft("a")
        ReviewRotationState.holdShell(ReviewRotationState.Shell(a, null, 0L))
        ReviewRotationState.hold(held(a, a.copy(plate = "1234567")))
        now += ReviewRotationState.MAX_AGE_MS
        now += 1
        assertNull(ReviewRotationState.takeShell())
        ReviewRotationState.hold(held(a, a))
        now += ReviewRotationState.MAX_AGE_MS + 1
        assertNull(ReviewRotationState.take(a))
    }
    @Test fun takingTheShellKeepsTheScreenStateForTheReviewScreen() {
        val a = draft("a")
        val edited = a.copy(plate = "1234567")
        ReviewRotationState.holdShell(ReviewRotationState.Shell(a, null, 1L))
        ReviewRotationState.hold(held(a, edited))
        assertNotNull(ReviewRotationState.takeShell())
        assertEquals(edited, ReviewRotationState.take(a)?.draft)
    }
    @Test fun eachEntryKeepsItsOwnTimestamp() {
        var now = 1_000L
        ReviewRotationState.clock = { now }
        val a = draft("a")
        ReviewRotationState.hold(held(a, a.copy(plate = "1234567")))
        now += ReviewRotationState.MAX_AGE_MS - 10
        ReviewRotationState.holdShell(ReviewRotationState.Shell(a, null, 0L))
        now += 20
        assertNull("parking the shell must not extend the screen state", ReviewRotationState.take(a))
        assertNotNull(ReviewRotationState.takeShell())
        ReviewRotationState.holdShell(ReviewRotationState.Shell(a, null, 0L))
        now += ReviewRotationState.MAX_AGE_MS - 10
        ReviewRotationState.hold(held(a, a))
        now += 20
        assertNull("holding the screen state must not extend the shell", ReviewRotationState.takeShell())
        assertNotNull(ReviewRotationState.take(a))
    }

    @Test fun editingPlatesManualAddressAndDiscardDialogSurviveRotation() {
        val initial = draft("r1")
        val address = io.github.reihlinstinct.reporter.municipal.StreetDirectory.ConfirmableAddress("test 1", 31.0, 35.0)
        ReviewRotationState.hold(ReviewRotationState.Held(initial, initial, "31.0", "35.0", "", "", "", "", null,
            editing = true, plates = listOf("1234567"), manualResult = address, confirmExit = true))
        val taken = ReviewRotationState.take(initial)!!
        assertTrue(taken.editing); assertTrue(taken.confirmExit)
        assertEquals(listOf("1234567"), taken.plates); assertEquals(address, taken.manualResult)
        ReviewRotationState.hold(held(initial, initial))
        val plain = ReviewRotationState.take(initial)!!
        assertFalse(plain.editing); assertFalse(plain.confirmExit); assertTrue(plain.plates.isEmpty()); assertNull(plain.manualResult)
    }
}
