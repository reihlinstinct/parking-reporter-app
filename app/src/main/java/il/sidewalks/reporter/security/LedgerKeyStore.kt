package il.sidewalks.reporter.security

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Custody only. Caller must serialize initialization and close DB/key buffers on lock. */
class LedgerKeyStore(context: Context) {
    private val app = context.applicationContext
    private val wrapped = AtomicFile(File(app.noBackupFilesDir, "report-ledger-key.enc"))
    private val alias = "parking-reporter-ledger-wrap-v1"

    /** Creation allowed ONLY when the factory has proved no DB or migration history. */
    @Synchronized
    fun loadOrCreate(firstInitialization: Boolean): ByteArray {
        val guard = app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        check(guard.isDeviceSecure && !guard.isDeviceLocked) { "Ledger authentication required" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        // AtomicFile.openRead also recovers a committed backup after an interrupted write.
        if (wrapped.baseFile.exists() || File(wrapped.baseFile.path + ".bak").exists()) {
            check(existing != null) { "Ledger wrapping key unavailable" }
            val bytes = wrapped.openRead().use { it.readBytes() }
            return try { LedgerEnvelope.unwrap(bytes, existing) } finally { bytes.fill(0) }
        }
        check(firstInitialization) { "Ledger key unavailable" }
        val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(30)
                .build())
            generateKey()
        }
        val dataKey = LedgerEnvelope.newDataKey()
        try {
            val envelope = LedgerEnvelope.wrap(dataKey, key)
            try {
                val stream = wrapped.startWrite()
                try {
                    stream.write(envelope)
                    wrapped.finishWrite(stream)
                } catch (failure: Exception) {
                    wrapped.failWrite(stream)
                    throw failure
                }
            } finally { envelope.fill(0) }
            return dataKey
        } catch (failure: Exception) {
            dataKey.fill(0)
            throw failure
        }
    }
    // No delete/reset API. Invalidated keys and failed authentication propagate as stopped state.
}
