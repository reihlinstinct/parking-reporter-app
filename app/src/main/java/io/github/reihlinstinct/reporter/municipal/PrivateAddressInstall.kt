package io.github.reihlinstinct.reporter.municipal

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Local document only. No network, relaxed pin, public copy, or contact data. */
object PrivateAddressInstall {
    const val MAX_BYTES = 8 * 1024 * 1024
    fun install(input: InputStream, directory: File): StreetDirectory {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(65536)
        while (output.size() <= MAX_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_BYTES + 1 - output.size()))
            if (count == -1) break
            if (count == 0) continue
            output.write(buffer, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size in 1..MAX_BYTES) { "address document size refused" }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        val parsed = StreetDirectory.load(AddressLookupPin.verifyPinned(text))
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()))
        val target = File(directory, "addresses.json")
        require(!Files.isSymbolicLink(target.toPath()))
        val temporary = File.createTempFile("address-import-", ".tmp", directory)
        try {
            temporary.setReadable(false, false); temporary.setWritable(false, false)
            require(temporary.setReadable(true, true) && temporary.setWritable(true, true))
            java.io.FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
        return parsed
    }
}
