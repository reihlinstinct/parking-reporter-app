package io.github.reihlinstinct.reporter.municipal

import java.io.File
import java.io.InputStream

/** Public static dataset only. Never sends an address, coordinate or reporter field. */
class AddressFileCache(
    private val directory: File,
    private val name: String = "addresses-v1.json",
    private val expectedSha256: String = AddressLookupPin.EXPECTED_SHA256,
) {
    init {
        require(name.matches(Regex("[a-z0-9-]+\\.json")))
        require(expectedSha256.matches(Regex("[a-f0-9]{64}")))
    }
    companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
        private val lock = Any()
    }
    private val cached get() = File(directory, name)

    /** A corrupt/partial file never counts as an offline cache hit. */
    fun available(): Boolean = synchronized(lock) {
        try { verifiedFile(); true } catch (_: Exception) { false }
    }

    fun verifiedFile(): File = synchronized(lock) {
        check(cached.isFile && cached.length() in 1..MAX_BYTES) { "Address data unavailable" }
        cached.inputStream().use { verify(it) }
        cached
    }

    /** Validation precedes replacement. Failed network/import leaves the last good cache intact. */
    fun store(input: InputStream): Unit = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs()) { "Address cache unavailable" }
        val temporary = File.createTempFile("address-", ".pending", directory)
        try {
            temporary.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var count = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= MAX_BYTES) { "Address data too large" }
                    output.write(buffer, 0, read)
                }
            }
            temporary.inputStream().use { verify(it) }
            check(temporary.renameTo(cached)) { "Address cache replacement failed" }
        } finally {
            temporary.delete()
        }
    }

    private fun verify(input: InputStream) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        if (digest.digest().joinToString("") { "%02x".format(it) } != expectedSha256)
            throw AddressLookupMismatchException()
    }

    fun clear(): Unit = synchronized(lock) {
        check(!cached.exists() || cached.delete()) { "Address cache removal failed" }
    }
}
