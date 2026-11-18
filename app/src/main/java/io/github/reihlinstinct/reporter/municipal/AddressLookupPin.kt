package io.github.reihlinstinct.reporter.municipal

import java.security.MessageDigest

/** Exact bytes of the bundled production address database. A stale or modified
 * database cannot silently change address resolution. See docs/address-lookup.md
 * for source statements and the outstanding legacy generator verification gap. */
class AddressLookupMismatchException : Exception("address lookup on device does not match the pinned copy")

object AddressLookupPin {
    const val EXPECTED_SHA256 =
        "5eaa83a9eb9253798109555bf2f6ea6ab6ec941a86e86c15c6761b2e5ee046d4"

    fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Verify original bytes in a bounded buffer, before parsing or resolving any address. */
    fun verifyPinned(input: java.io.InputStream) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != EXPECTED_SHA256) throw AddressLookupMismatchException()
    }

    /** Returns the text unchanged when pinned; refuses anything else. */
    fun verifyPinned(text: String): String {
        if (sha256Hex(text) != EXPECTED_SHA256) {
            throw AddressLookupMismatchException()
        }
        return text
    }
}
