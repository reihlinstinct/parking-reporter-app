package io.github.reihlinstinct.reporter.security

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ReviewRecoveryAuthRequestTest {
    private var now = 1000L
    private val gate = ReviewRecoveryAuthRequest { now }
    private val start = ReviewRecoveryAuthRequest.Operation.START_SESSION
    private val restore = ReviewRecoveryAuthRequest.Operation.RESTORE_SESSION
    private fun binding(report: String = "synthetic-1", session: String = "a".repeat(32)) =
        ReviewRecoveryEnvelope.Binding(session, report, "b".repeat(64), "c".repeat(64), 7, 197, 4, 1000, 901000)
    private fun complete(t: ReviewRecoveryAuthRequest.Ticket, ok: Boolean = true,
        foreground: Boolean = true, unlocked: Boolean = true) = gate.complete(t, binding(), start, ok, foreground, unlocked)

    @Test fun matchingResultConsumedOnceAndDoesNotRepresentSendAuthority() {
        val ticket = gate.begin(binding(), start)
        assertTrue(complete(ticket)); assertFalse(complete(ticket))
    }
    @Test fun canceledLockedAndBackgroundResultsConsumeIdentity() {
        for (condition in 0..2) {
            val ticket = gate.begin(binding(), start)
            assertFalse(complete(ticket, ok = condition != 0, foreground = condition != 1, unlocked = condition != 2))
            assertFalse(complete(ticket))
        }
    }
    @Test fun staleCallbackCannotConsumeNewPrompt() {
        val first = gate.begin(binding(), start); val second = gate.begin(binding(), start)
        assertNotEquals(first.nonce, second.nonce)
        assertFalse(complete(first)); assertTrue(complete(second))
    }
    @Test fun reportSessionAndOperationMismatchConsumeMatchingTicket() {
        for (variant in 0..2) {
            val ticket = gate.begin(binding(), start)
            assertFalse(gate.complete(ticket, binding(report = if (variant == 0) "other" else "synthetic-1",
                session = if (variant == 1) "d".repeat(32) else "a".repeat(32)),
                if (variant == 2) restore else start, true, true, true))
            assertFalse(complete(ticket))
        }
    }
    @Test fun exactTimeoutAndClockRollbackFailClosed() {
        val first = gate.begin(binding(), start)
        now += ReviewRecoveryAuthRequest.TIMEOUT_MS - 1; assertTrue(complete(first))
        val second = gate.begin(binding(), start)
        now += ReviewRecoveryAuthRequest.TIMEOUT_MS; assertFalse(complete(second))
        val third = gate.begin(binding(), start)
        now--; assertFalse(complete(third))
    }
    @Test fun navigationAndProcessDeathRejectButRotationRetainsPendingIdentity() {
        val ticket = gate.begin(binding(), start)
        // Rotation/credential-prompt ON_STOP must NOT invalidate the process holder.
        now++; assertTrue(complete(ticket))
        val canceled = gate.begin(binding(), start); gate.invalidate(); assertFalse(complete(canceled))
        val died = gate.begin(binding(), restore)
        val newProcess = ReviewRecoveryAuthRequest { now }
        assertFalse(newProcess.complete(died, binding(), restore, true, true, true))
    }
    @Test fun concurrentDuplicateCallbacksSucceedAtMostOnce() {
        val ticket = gate.begin(binding(), start); val ready = CountDownLatch(1); val successes = AtomicInteger()
        val workers = List(8) { Thread { ready.await(); if (complete(ticket)) successes.incrementAndGet() } }
        workers.forEach { it.start() }; ready.countDown(); workers.forEach { it.join() }
        assertEquals(1, successes.get())
    }
    @Test fun copiedTicketWithSameFieldsIsNotCurrentIdentity() {
        val ticket = gate.begin(binding(), start)
        val copy = ReviewRecoveryAuthRequest.Ticket(ticket.nonce, ticket.sessionId, ticket.reportId,
            ticket.operation, ticket.createdMs, ticket.deadlineMs)
        assertFalse(complete(copy)); assertTrue(complete(ticket))
        assertEquals("ReviewRecoveryAuthTicket(redacted)", ticket.toString())
    }
}
