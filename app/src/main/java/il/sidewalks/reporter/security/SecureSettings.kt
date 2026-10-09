package il.sidewalks.reporter.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ReporterSettings(
    val firstName: String, val lastName: String, val phone: String, val idNumber: String, val email: String,
) {
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
        val json = JSONObject().put("firstName", settings.firstName).put("lastName", settings.lastName)
            .put("phone", settings.phone).put("idNumber", settings.idNumber).put("email", settings.email)
        val plaintext = json.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(plaintext)
            val stream = file.startWrite()
            try {
                stream.write(cipher.iv.size); stream.write(cipher.iv); stream.write(encrypted)
                file.finishWrite(stream)
            } catch (failure: Exception) { file.failWrite(stream); throw failure }
        } finally { plaintext.fill(0) }
    }
    fun read(): ReporterSettings? {
        if (!file.baseFile.exists()) return null
        val data = file.readFully()
        val size = data.firstOrNull()?.toInt()?.and(255) ?: error("Invalid encrypted settings")
        require(size == 12 && data.size > size + 17)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, size + 1)))
        }
        val plaintext = cipher.doFinal(data.copyOfRange(size + 1, data.size))
        try {
            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            return ReporterSettings(json.getString("firstName"), json.getString("lastName"), json.getString("phone"),
                json.getString("idNumber"), json.getString("email"))
        } finally { plaintext.fill(0) }
    }
    // No plaintext fallback, no automatic key reset on invalidation.
}
