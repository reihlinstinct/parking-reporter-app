package io.github.reihlinstinct.reporter.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Send is the single confirmation: the gate is that the shown draft is complete. */
class SendGateTest {

    private fun readyDraft(): ReviewDraft {
        val evidence = Evidence("stable-gate", "a".repeat(64), "2026-11-08T10:00:00+02:00", 31.7683, 35.2137)
        return ReviewDraft(evidence, "12345678", "רחוב 12", "sidewalk_parking",
            "רכב חנה על המדרכה", emptySet())
    }

    @Test fun completeShownDraftIsReady() {
        assertTrue(SendGate.readyToSend(readyDraft()))
    }

    @Test fun incompleteShownDraftRefused() {
        assertFalse(SendGate.readyToSend(readyDraft().copy(plate = "")))
        assertFalse(SendGate.readyToSend(readyDraft().copy(unresolved = setOf("address_needs_confirmation"))))
    }

    @Test fun missingDraftRefused() {
        assertFalse(SendGate.readyToSend(null))
    }
}
