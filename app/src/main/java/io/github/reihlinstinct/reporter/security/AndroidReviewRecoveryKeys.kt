package io.github.reihlinstinct.reporter.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Compiled, inactive. Android Keystore wrapper for the recovery session key (#358 slice 2).
 * Dedicated alias namespace, never shared with settings or ledger aliases. The alias suffix is the validated
 * 32-hex session id, never read from an envelope without validation. Key policy: device credential, 30 s
 * validity window. That window does NOT prove a fresh prompt; the controller only calls wrap/unwrap after its
 * own one-use RESULT_OK ticket. No secrets in exception text. */
class AndroidReviewRecoveryKeys : ReviewRecoveryKeys {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun alias(sessionId: String): String {
        require(sessionId.matches(Regex("[0-9a-f]{32}"))) { "Invalid recovery session" }
        return PREFIX + sessionId
    }
    private fun aad(sessionId: String) = "parking-reporter/review-recovery/wrap/v1/$sessionId".toByteArray(Charsets.UTF_8)

    override fun wrap(sessionId: String, dataKey: ByteArray): ByteArray {
        require(dataKey.size == 32)
        val name = alias(sessionId)
        store().deleteEntry(name)
        val spec = KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= 30) {
            spec.setUserAuthenticationParameters(30, KeyProperties.AUTH_DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            spec.setUserAuthenticationValidityDurationSeconds(30)
        }
        // Defence in depth only; not the security boundary and has documented old-OS limits.
        if (Build.VERSION.SDK_INT >= 28) spec.setUnlockedDeviceRequired(true)
        try {
            val key = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply { init(spec.build()) }.generateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad(sessionId))
            val ct = cipher.doFinal(dataKey)
            check(cipher.iv.size == 12)
            return ByteBuffer.allocate(12 + ct.size).put(cipher.iv).put(ct).array()
        } catch (e: Exception) {
            store().deleteEntry(name)
            throw ReviewRecoveryKeys.KeyException(false, "Recovery key unavailable")
        }
    }

    override fun unwrap(sessionId: String, wrapped: ByteArray): ByteArray {
        val name = alias(sessionId)
        val loaded = try { store().getKey(name, null) } catch (e: Exception) {
            val permanent = generateSequence<Throwable>(e) { it.cause }.any {
                it is KeyPermanentlyInvalidatedException
            }
            throw ReviewRecoveryKeys.KeyException(permanent, "Recovery key store unavailable")
        }
        val key = loaded as? SecretKey
            ?: throw ReviewRecoveryKeys.KeyException(true, "Recovery key missing")
        if (wrapped.size != 12 + 32 + 16) throw ReviewRecoveryKeys.KeyException(true, "Recovery key invalid")
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.copyOfRange(0, 12)))
            cipher.updateAAD(aad(sessionId))
            return cipher.doFinal(wrapped, 12, wrapped.size - 12)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw ReviewRecoveryKeys.KeyException(true, "Recovery key invalidated")
        } catch (e: UserNotAuthenticatedException) {
            throw ReviewRecoveryKeys.KeyException(false, "Recovery authentication required")
        } catch (e: javax.crypto.AEADBadTagException) {
            // Present key but the stored blob does not authenticate: unusable.
            throw ReviewRecoveryKeys.KeyException(true, "Recovery key invalid")
        } catch (e: java.security.GeneralSecurityException) {
            // Includes InvalidKeyException without a fresh credential: transient, retain ciphertext.
            throw ReviewRecoveryKeys.KeyException(false, "Recovery key unavailable")
        }
    }

    override fun deleteAll(keep: String?) {
        val ks = store()
        ks.aliases().toList().filter { it.startsWith(PREFIX) && it != keep?.let { k -> alias(k) } }
            .forEach { runCatching { ks.deleteEntry(it) } }
    }

    private companion object { const val PREFIX = "parking-reporter-review-recovery-wrap-v1-" }
}
