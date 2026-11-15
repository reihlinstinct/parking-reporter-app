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

    private fun unrecoverable(cause: Throwable?) = java.security.UnrecoverableKeyException("x").apply { cause?.let { initCause(it) } }

    @Test fun unrecoverableKeyWithoutAKeystoreCauseIsNotInvalidation() {
        assertEquals(false, SecureSettings.isKeyInvalidation(RuntimeException("w", unrecoverable(null)), 34) { it is java.security.KeyStoreException })
    }

    @Test fun transientKeystoreFailureIsNeverInvalidation() {
        val e = RuntimeException("w", unrecoverable(java.security.KeyStoreException("busy")))
        assertEquals(false, SecureSettings.isKeyInvalidation(e, 34) { false })
    }

    @Test fun permanentKeystoreFailureIsFoundThroughTheCauseChainOnApi33Plus() {
        val e = RuntimeException("w", unrecoverable(java.security.KeyStoreException("permanent")))
        assertEquals(true, SecureSettings.isKeyInvalidation(e, 34) { it is java.security.KeyStoreException })
        assertEquals(false, SecureSettings.isKeyInvalidation(e, 32) { it is java.security.KeyStoreException })
        assertEquals(false, SecureSettings.isKeyInvalidation(IllegalStateException("x"), 34) { false })
    }
}
