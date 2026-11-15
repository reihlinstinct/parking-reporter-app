package io.github.reihlinstinct.reporter.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** #132: only an invalidated key is discarded, once; other failures are never swallowed. */
class SecureSettingsRecoveryTest {
    private class Invalid : Exception("invalid")
    private val isInvalid: (Throwable) -> Boolean = { it is Invalid }

    @Test fun invalidatedKeyIsDiscardedOnceThenRetried() {
        var discards = 0; var calls = 0
        val result = SecureSettings.recoverIfInvalidated(isInvalid, { discards++ }) {
            calls++; if (calls == 1) throw Invalid() else "saved"
        }
        assertEquals("saved", result); assertEquals(1, discards); assertEquals(2, calls)
    }

    @Test fun otherFailuresPropagateWithoutDiscarding() {
        var discards = 0
        assertThrows(IllegalStateException::class.java) {
            SecureSettings.recoverIfInvalidated(isInvalid, { discards++ }) { throw IllegalStateException("auth timed out") }
        }
        assertEquals(0, discards)
    }

    @Test fun aSecondInvalidationIsNotRetriedForever() {
        var discards = 0; var calls = 0
        assertThrows(Invalid::class.java) {
            SecureSettings.recoverIfInvalidated(isInvalid, { discards++ }) { calls++; throw Invalid() }
        }
        assertEquals(1, discards); assertEquals(2, calls)
    }

    @Test fun invalidationIsFoundThroughTheCauseChain() {
        val wrapped = RuntimeException("wrapper", java.security.UnrecoverableKeyException("gone"))
        assertEquals(true, SecureSettings.isKeyInvalidation(wrapped))
        assertEquals(false, SecureSettings.isKeyInvalidation(IllegalStateException("x")))
    }
}
