package io.github.reihlinstinct.reporter.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ReporterSettings(
    val firstName: String, val lastName: String, val phone: String, val idNumber: String, val email: String,
    val municipalSubscriptionKey: String = "", val municipalUsername: String = "", val municipalPassword: String = "",
    val passportMode: Boolean = false, val passportNumber: String = "",
) {
    fun hasMunicipalCredentials(): Boolean =
        municipalSubscriptionKey.isNotBlank() && municipalUsername.isNotBlank() && municipalPassword.isNotBlank()
    /** IDs of unset municipal API credential fields (never the values). */
    fun missingMunicipalFields(): List<String> = listOfNotNull(
        "subscription_key".takeIf { municipalSubscriptionKey.isBlank() },
        "api_username".takeIf { municipalUsername.isBlank() },
        "api_password".takeIf { municipalPassword.isBlank() },
    )
    override fun toString(): String = "ReporterSettings(redacted)"
}
/** Requires recent system device-credential authentication for every encryption/decryption. */
class SecureSettings(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "reporter-settings.enc"))
    private val alias = "parking-reporter-settings-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(30)
                .build())
            generateKey()
        }
    }
    fun save(settings: ReporterSettings) {
        // Saving follows a fresh device-credential check and replaces all settings, so an invalidated key
        // (screen lock removed, #132) is discarded and the new settings are stored under a new key.
        recoverIfInvalidated(::keyInvalidated, ::discardInvalidatedKey) { saveOnce(settings) }
    }
    private fun saveOnce(settings: ReporterSettings) {
        val plaintext = SettingsCodec.encode(settings)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val diskBytes = encodeForDisk(plaintext, cipher)
            val stream = file.startWrite()
            try {
                stream.write(diskBytes)
                file.finishWrite(stream)
            } catch (failure: Exception) { file.failWrite(stream); throw failure }
        } finally { plaintext.fill(0) }
    }
    /** An invalidated key makes the old ciphertext unrecoverable by design: report "no settings" so re-entry is required. */
    fun read(): ReporterSettings? =
        recoverIfInvalidated(::keyInvalidated, ::discardInvalidatedKey) { readOnce() }
    private fun readOnce(): ReporterSettings? {
        if (!file.baseFile.exists()) return null
        val plaintext = decodeForDisk(file.readFully()) { iv ->
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
        }
        try {
            return SettingsCodec.decode(plaintext)
        } finally { plaintext.fill(0) }
    }
    // No plaintext fallback. Only a PERMANENTLY invalidated key is reset, never a transient failure
    // (for example a missing or timed-out authentication).
    private fun keyInvalidated(e: Throwable) = isKeyInvalidation(e)
    /** Deletes the unusable key and its undecryptable file; the next save generates a fresh key. */
    private fun discardInvalidatedKey() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
        file.delete()
    }

    companion object {
        /**
         * Permanent invalidation only: a KeyPermanentlyInvalidatedException anywhere in the cause chain, or (API 33+)
         * an UnrecoverableKeyException caused by a non-transient KeyStoreException. A transient keystore error
         * (busy, restarting) must never delete a valid key. Below API 33 only the first case counts.
         */
        internal fun isKeyInvalidation(
            e: Throwable,
            sdk: Int = android.os.Build.VERSION.SDK_INT,
            isPermanentFailure: (Throwable) -> Boolean = { permanentKeystoreFailure(it) },
        ): Boolean {
            val chain = generateSequence(e) { it.cause }.toList()
            if (chain.any { it is android.security.keystore.KeyPermanentlyInvalidatedException }) return true
            if (sdk < 33) return false
            return chain.any { it is java.security.UnrecoverableKeyException } && chain.any { isPermanentFailure(it) }
        }
        /** API 33+: a keystore failure the platform does not call transient. Anything else is not permanent. */
        private fun permanentKeystoreFailure(e: Throwable): Boolean =
            if (android.os.Build.VERSION.SDK_INT >= 33 && e is android.security.KeyStoreException) !e.isTransientFailure else false
        /** Runs [block]; on an invalidation failure runs [discard] once and retries once. Other failures propagate. */
        internal fun <T> recoverIfInvalidated(isInvalid: (Throwable) -> Boolean, discard: () -> Unit, block: () -> T): T =
            try { block() } catch (e: Exception) {
                if (!isInvalid(e)) throw e
                discard()
                block()
            }
        /** Framed as iv-length byte + IV + AES-GCM ciphertext. Unit-tested with a non-Keystore key. */
        internal fun encodeForDisk(plaintext: ByteArray, cipher: Cipher): ByteArray {
            val encrypted = cipher.doFinal(plaintext)
            val out = ByteArray(1 + cipher.iv.size + encrypted.size)
            out[0] = cipher.iv.size.toByte()
            System.arraycopy(cipher.iv, 0, out, 1, cipher.iv.size)
            System.arraycopy(encrypted, 0, out, 1 + cipher.iv.size, encrypted.size)
            return out
        }
        internal fun decodeForDisk(data: ByteArray, cipherFor: (ByteArray) -> Cipher): ByteArray {
            val size = data.firstOrNull()?.toInt()?.and(255) ?: throw IllegalArgumentException("Invalid encrypted settings")
            require(size == 12 && data.size > size + 17) { "Invalid encrypted settings" }
            val cipher = cipherFor(data.copyOfRange(1, size + 1))
            return cipher.doFinal(data.copyOfRange(size + 1, data.size))
        }
    }
}
