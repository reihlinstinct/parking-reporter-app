package io.github.reihlinstinct.reporter

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.PublicKeyVerify
import io.github.reihlinstinct.reporter.intake.OfflineReceiver
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Android parity for #353: the platform `org.json.JSONException` is a checked exception, unlike the JVM test
 * library, so malformed inbound JSON must still reach the caller as the typed [IllegalArgumentException].
 * Synthetic text only. The decryptor and verifier are never reached: every case fails at the manifest.
 */
class OfflineReceiverPlatformJsonTest {
    private val decryptor = HybridDecrypt { _, _ -> error("not reached") }
    private val verifier = PublicKeyVerify { _, _ -> error("not reached") }

    @Test fun malformedManifestAndCompletionAreTheTypedRejection() {
        val bad = listOf("{", "[]", "null", "", "{\"schema\":\"2\"}", "{\"schema\":2}", "{\"schema\":2,\"package\":5}")
        for (text in bad) {
            assertThrows(IllegalArgumentException::class.java) {
                OfflineReceiver.open(text, listOf("x"), "{}", 1, "test-receiver", "test-device", decryptor, verifier)
            }
        }
    }
}
